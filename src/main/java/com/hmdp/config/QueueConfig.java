package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static com.hmdp.utils.MqConstants.*;
/**
 * MQ 拓扑：
 *  X --XA--> order.queue          下单消费队列（消费失败 nack → 死信 XR）
 *  X --XR--> order.retry.queue    重试延迟队列（TTL 10s，到期死信 → XA 回到下单队列）
 *  X --XD--> order.delay.queue    延迟关单队列（TTL 15min，到期死信 → XC）
 *  X --XC--> order.close.queue    关单执行队列
 */
@Configuration
public class QueueConfig {

    @Bean("xExchange")
    public DirectExchange xExchange() {
        return new DirectExchange(ORDER_EXCHANGE);
    }

    /** 下单消费队列：失败死信到 XR（重试延迟队列） */
    @Bean("orderQueue")
    public Queue orderQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_CREATE)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", KEY_ORDER_RETRY)
                .build();
    }

    /** 重试延迟队列：TTL 10s，到期死信回 XA，形成重试闭环 */
    @Bean("orderRetryQueue")
    public Queue orderRetryQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_RETRY)
                .withArgument("x-message-ttl", (int) TTL_RETRY_MS)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", KEY_ORDER_CREATE)
                .build();
    }

    /** 延迟关单队列：TTL 15min，到期死信到 XC（关单执行队列） */
    @Bean("orderDelayQueue")
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_DELAY)
                .withArgument("x-message-ttl", (int) TTL_CLOSE_MS)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", KEY_ORDER_CLOSE)
                .build();
    }

    /** 关单执行队列 */
    @Bean("orderCloseQueue")
    public Queue orderCloseQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_CLOSE).build();
    }

    @Bean
    public Binding orderQueueBinding(@Qualifier("orderQueue") Queue queue,
                                     @Qualifier("xExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(KEY_ORDER_CREATE);
    }

    @Bean
    public Binding orderRetryQueueBinding(@Qualifier("orderRetryQueue") Queue queue,
                                          @Qualifier("xExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(KEY_ORDER_RETRY);
    }

    @Bean
    public Binding orderDelayQueueBinding(@Qualifier("orderDelayQueue") Queue queue,
                                          @Qualifier("xExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(KEY_ORDER_DELAY);
    }

    @Bean
    public Binding orderCloseQueueBinding(@Qualifier("orderCloseQueue") Queue queue,
                                          @Qualifier("xExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(KEY_ORDER_CLOSE);
    }
}