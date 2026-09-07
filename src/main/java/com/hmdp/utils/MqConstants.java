package com.hmdp.utils;

/**
 * RabbitMQ 拓扑常量：交换机 / 队列 / 路由键
 */
public class MqConstants {
    /** 业务交换机（同时充当死信交换机） */
    public static final String ORDER_EXCHANGE = "X";

    /** 路由键：秒杀下单 */
    public static final String KEY_ORDER_CREATE = "XA";
    /** 路由键：下单消费失败重试（经 TTL 延迟后重回 XA） */
    public static final String KEY_ORDER_RETRY = "XR";
    /** 路由键：延迟关单入口（TTL 到期后转 XC） */
    public static final String KEY_ORDER_DELAY = "XD";
    /** 路由键：关单执行 */
    public static final String KEY_ORDER_CLOSE = "XC";

    /** 队列：秒杀订单落库（消费队列） */
    public static final String QUEUE_ORDER_CREATE = "order.queue";
    /** 队列：重试延迟队列（TTL 10s，无消费者，到期重回 order.queue） */
    public static final String QUEUE_ORDER_RETRY = "order.retry.queue";
    /** 队列：延迟关单队列（TTL 15min，无消费者，到期转入 order.close.queue） */
    public static final String QUEUE_ORDER_DELAY = "order.delay.queue";
    /** 队列：关单执行队列 */
    public static final String QUEUE_ORDER_CLOSE = "order.close.queue";

    /** 重试队列 TTL：10s */
    public static final long TTL_RETRY_MS = 10_000L;
    /** 延迟关单 TTL：15min（与 ORDER_UNPAID_TTL_MINUTES 一致） */
    public static final long TTL_CLOSE_MS = 15 * 60 * 1000L;
    /** 最大重试次数（x-death 计数），超过则进入补偿 */
    public static final int MAX_RETRY = 3;
}