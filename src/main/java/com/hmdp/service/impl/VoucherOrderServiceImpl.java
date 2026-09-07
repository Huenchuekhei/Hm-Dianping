package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.StockReconcileLog;
import com.hmdp.entity.OrderCompensation;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderCompensationMapper;
import com.hmdp.mapper.StockReconcileLogMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private OrderCompensationMapper compensationMapper;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StockReconcileLogMapper reconcileLogMapper;
    /**
     * 脚本初始化
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private IVoucherOrderService proxy;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否获取锁成功
        if (!isLock) {
            //失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            //直接调用，不会触发spring aop的事务管理
            //要通过代理调用，获取代理对象，才会被spring aop拦截
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Override
    public Object queryOrderList(Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<VoucherOrder> page = voucherOrderService.query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在或无权操作");
        }
        // 2.支付成功回调：CAS status 1→2（与关单消费者互斥，双方只能成功一个）
        LocalDateTime now = LocalDateTime.now();
        boolean paid = update()
                .set("status", 2)
                .set("pay_time", now)
                .set("pay_type", 1)
                .eq("id", orderId)
                .eq("status", 1)
                .update();
        if (paid) {
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", orderId);
            data.put("payTime", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            return Result.ok(data);
        }
        // 3.CAS 失败：区分"已支付"与"已超时关闭"
        Integer status = getById(orderId).getStatus();
        return Result.fail(status != null && status == 2 ? "请勿重复支付" : "订单已超时关闭，支付失败");
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.秒杀资格预检（Lua 原子执行：判库存 + 判一人一单 + Redis 预扣）
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result == null ? -1 : result.intValue();
        if (r != 0) {
            if (r == -1) {
                return Result.fail("秒杀活动不存在或尚未开始");
            }
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 2.封装订单消息，异步落库
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        try {
            rabbitTemplate.convertAndSend(
                    MqConstants.ORDER_EXCHANGE, MqConstants.KEY_ORDER_CREATE,
                    JSONUtil.toJsonStr(order));
        } catch (Exception e) {
            // 3.发送失败：回补 Redis 预扣库存 + 判重集合，保证不超卖不少卖
            log.error("发送下单消息失败，订单ID:{}，开始回补库存", orderId, e);
            rollbackRedisStock(voucherId, userId);
            return Result.fail("系统繁忙，请稍后再试");
        }
        // 4.返回订单号，前端轮询订单状态（见 1.7）
        return Result.ok(orderId);
    }

    /**
     * 消费补偿：重试耗尽/发送失败后的兜底
     * 记录补偿表 + 回补 Redis 库存与判重集合（DB 库存无需回补：事务已回滚，DB 从未扣减）
     */
    public void compensate(VoucherOrder order, String s) {
        OrderCompensation  compensation = new OrderCompensation();
        BeanUtils.copyProperties(order, compensation);
        compensation.setReason(s);
        compensation.setStatus(1);
        compensation.setCreateTime(LocalDateTime.now());
        //记录回补表
        compensationMapper.insert(compensation);
        rollbackRedisStock(order.getVoucherId(), order.getUserId());
    }

    private void rollbackRedisStock(Long voucherId, Long userId) {
        // 回补Redis预扣库存
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        // 删除用户下单标记
        stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
    }

    /**
     * 订单落库（由 MQ 消费者调用，外部调用事务才会生效）
     * 事务内三步：DB 乐观锁扣库存 → 唯一索引幂等落库 → 投递延迟关单消息
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
       // 事务内先扣减库存，确保不会超卖
        boolean success = seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1")
                .eq(SeckillVoucher::getVoucherId, order.getVoucherId())
                .gt(SeckillVoucher::getStock, 0)
                .update();
        if (!success) {
            throw new IllegalStateException("库存不足，voucherId=" + order.getVoucherId());
        }
        order.setStatus(1);
        order.setPayType(1);
        order.setCreateTime(LocalDateTime.now());
        save(order);
        // 3.投递延迟关单消息（15 分钟后消费；发送失败则整个事务回滚，等待重试）
        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_EXCHANGE, MqConstants.KEY_ORDER_DELAY,
                JSONUtil.toJsonStr(order.getId()));
    }

    /**
     * 对账：Redis 库存 vs DB 库存 / DB 有效订单数 vs Redis 判重集合
     * 返回不一致差异列表；diff 为空代表账目一致
     */
    public Map<String, Object> reconcile() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();   // 1.拿所有秒杀券
        List<Map<String, Object>> diffs = new ArrayList<>();

        for (SeckillVoucher sv : vouchers) {
            Long vid = sv.getVoucherId();
            String redisStockStr = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.SECKILL_STOCK_KEY + vid);   // 2.取 Redis 库存

            if (redisStockStr == null) continue;   // 未预热的券跳过

            int redisStock = Integer.parseInt(redisStockStr);
            int dbStock = sv.getStock();   // 3.取 DB 库存

            // 4.取 DB 有效订单数（status=1 未支付 + status=2 已支付 才占用库存）
            long dbOrders = query()
                    .eq("voucher_id", vid)
                    .in("status", 1, 2)
                    .count();

            Long redisOrders = stringRedisTemplate.opsForSet()
                    .size("seckill:order:" + vid);   // 5.取 Redis 判重集合大小

            // 6.比对差异
            if (redisStock != dbStock || dbOrders != (redisOrders == null ? 0 : redisOrders)) {
                Map<String, Object> diff = new HashMap<>();
                diff.put("voucherId", vid);
                diff.put("redisStock", redisStock);
                diff.put("dbStock", dbStock);
                diff.put("dbOrderCount", dbOrders);
                diff.put("redisOrderCount", redisOrders);

                diffs.add(diff);

                // 7.记录差异到对账表
                StockReconcileLog logRow = new StockReconcileLog();
                logRow.setVoucherId(vid);
                logRow.setRedisStock(redisStock);
                logRow.setDbStock(dbStock);
                logRow.setDbOrderCount(dbOrders);
                logRow.setRedisOrderCount(redisOrders);
                logRow.setDiffDesc("redisStock=" + redisStock + ", dbStock=" + dbStock
                        + ", dbOrders=" + dbOrders + ", redisOrders=" + redisOrders);
                reconcileLogMapper.insert(logRow);
                log.warn("[对账] 发现库存差异：{}", logRow.getDiffDesc());
            }
        }

        Map<String, Object> report = new HashMap<>();
        report.put("checkedVouchers", vouchers.size());
        report.put("inconsistent", diffs.size());
        report.put("diffs", diffs);
        return report;
    }

    /**
     * 兜底关单：扫描创建超过 (15+5) 分钟仍为未支付的订单，逐单执行与关单消费者相同的 CAS 逻辑
     * 兜底场景：延迟消息丢失 / MQ 长时间不可用
     * 返回成功关单数
     */
    public int rescueTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(
                RedisConstants.ORDER_UNPAID_TTL_MINUTES + 5); // 15+5 = 20 分钟
        List<VoucherOrder> timeoutOrders = query()
                .eq("status", 1)
                .lt("create_time", deadline)
                .last("limit 200")
                .list();
        int rescued = 0;
        for (VoucherOrder order : timeoutOrders) {
            boolean closed = update()
                    .set("status", 4)
                    .eq("id", order.getId())
                    .eq("status", 1)
                    .update();
            if (closed) {
                seckillVoucherService.update()
                        .setSql("stock = stock + 1")
                        .eq("voucher_id", order.getVoucherId())
                        .update();
                stringRedisTemplate.opsForValue()
                        .increment(RedisConstants.SECKILL_STOCK_KEY + order.getVoucherId());
                stringRedisTemplate.opsForSet()
                        .remove("seckill:order:" + order.getVoucherId(),
                                order.getUserId().toString());
                rescued++;
            }
        }
        return rescued;
    }
}
