package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.MqConstants;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.MqConstants.MAX_RETRY;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    VoucherOrderServiceImpl voucherOrderService;

    /**
     * sheng  消费者1
     *
     * @param message
     * @param channel
     * @throws Exception
     */
    @RabbitListener(queues = "QA")
    public void receivedA(Message message, Channel channel) throws Exception {
        String msg = new String(message.getBody());
        log.info("正常队列:");
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        voucherOrderService.save(voucherOrder);//保存到数据库
        //数据库秒杀库存减一
        Long voucherId = voucherOrder.getVoucherId();
        seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq(com.hmdp.entity.SeckillVoucher::getVoucherId, voucherId)
                .gt(com.hmdp.entity.SeckillVoucher::getStock, 0) // where id = ? and stock > 0
                .update();

    }

    /**
     * sheng  消费者2
     *
     * @param message
     * @throws Exception
     */
    @RabbitListener(queues = "QD")
    public void receivedD(Message message) throws Exception {
        log.info("死信队列:");
        String msg = new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        voucherOrderService.save(voucherOrder);

        Long voucherId = voucherOrder.getVoucherId();
        seckillVoucherService.lambdaUpdate()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq(com.hmdp.entity.SeckillVoucher::getVoucherId, voucherId)
                .gt(com.hmdp.entity.SeckillVoucher::getStock, 0) // where id = ? and stock > 0
                .update();

    }

    /**
     * 秒杀订单异步落库
     *
     * @param message 消息体
     * @param channel  队列信息
     * @throws Exception
     */
    @RabbitListener(queues = MqConstants.QUEUE_ORDER_CREATE)
    public void receivedXA(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        VoucherOrder order = JSONUtil.toBean(new String(message.getBody()), VoucherOrder.class);
        int retryCount = getXDeathCount(message);

        if (retryCount >= MAX_RETRY) {
            // 1.重试耗尽：不再 nack（会无限循环），直接走补偿并 ack
            try {
                voucherOrderService.compensate(order, "重试" + retryCount + "次失败");
            } catch (Exception e) {
                log.error("[下单消费] 补偿执行失败，等待人工处理。orderId={}", order.getId(), e);
            }
            channel.basicAck(tag, false);
            return;
        }
        // 2.事务内：DB 扣库存 → 唯一索引幂等落库 → 发延迟关单消息
        try {
            seckillVoucherService.createVoucherOrder(order);
            channel.basicAck(tag, false);
            log.info("[下单消费] 订单落库成功，orderId={}", order.getId());
        } catch (DuplicateKeyException e) {
            // 3.唯一索引冲突 = 重复消费/重复下单 → 幂等吞掉
            log.warn("[下单消费] 重复消息，幂等跳过。orderId={}", order.getId());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("[下单消费] 订单落库异常，orderId={}", order.getId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    @SuppressWarnings("unchecked")
    private int getXDeathCount(Message message) {
        Object xDeath = message.getMessageProperties().getHeader("x-death");
        if (xDeath instanceof List && !((List<?>) xDeath).isEmpty()) {
            Map<String, Object> death = (Map<String, Object>) ((List<?>) xDeath).get(0);
            Object count = death.get("count");
            return count == null ? 0 : Integer.parseInt(count.toString());
        }
        return 0;
    }
}
