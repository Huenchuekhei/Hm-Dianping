package com.hmdp.listener;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import org.springframework.amqp.core.Message;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.MqConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseListener {

    private final VoucherOrderServiceImpl voucherOrderService;

    private final ISeckillVoucherService seckillVoucherService;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 订单应该从 “未支付” → “已取消”；
     * 库存应该被释放（Redis 库存 +1 + SREM 判重集合）；
     * 让其他用户可以继续抢购。
     * @param message 消息体
     * @param channel 信道
     * @throws IOException 异常
     */
    @RabbitListener(queues = MqConstants.QUEUE_ORDER_CLOSE)
    public void closeOrder(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long orderId = JSONUtil.toBean(new String(message.getBody()), Long.class);
        try{
            VoucherOrder order = voucherOrderService.getById(orderId);
            // === 状态校验（最关键的一步） ===
            if (order == null || order.getStatus() != 1) {
                // 订单不存在 或 已经不是未支付状态 → 什么都不做
                channel.basicAck(tag, false);
                return;
            }
            // === CAS 关单（互斥） ===
            boolean closed = voucherOrderService.update()
                    .set("status", 4)                    // 已取消
                    .set("update_time", LocalDateTime.now())
                    .eq("id", orderId)
                    .eq("status", 1)                     // 仅当仍未支付时才关单
                    .update();
            if (!closed){
                // CAS 失败 → 订单刚好被支付了
                log.info("[关单] CAS 失败（已支付），放弃关单。orderId={}", orderId);
                channel.basicAck(tag, false);
                return;
            }
            //回补 DB 库存
            seckillVoucherService.lambdaUpdate()
                    .setSql("stock = stock + 1")
                    .eq(com.hmdp.entity.SeckillVoucher::getVoucherId, order.getVoucherId())
                    .update();
            // 回补 Redis 预扣库存 + 释放一人一单资格（允许重新抢购）
            stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + order.getVoucherId());
            stringRedisTemplate.opsForSet().remove("seckill:order:" + order.getVoucherId(),
                    order.getUserId().toString());
            log.info("[关单] 订单超时关闭完成，库存已回补。orderId={}", orderId);
            channel.basicAck(tag, false);
        }catch (Exception e){
            log.error("[关单] 关单异常，orderId={}", orderId, e);
            channel.basicNack(tag, false, false); // 异常继续重投
        }
    }
}
