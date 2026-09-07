package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 生产者发送确认：消息是否到达交换机（confirm）与是否路由到队列（return）
 */
@Slf4j
@Component
public class MqConfirmConfig {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void init() {
        // confirm：消息是否成功到达交换机
        rabbitTemplate.setConfirmCallback((CorrelationData correlationData, boolean ack, String cause) -> {
            if (!ack) {
                log.error("[MQ确认] 消息未到达交换机，correlationData={}，原因={}", correlationData, cause);
                // P2-1 落地前，此处由「对账任务」兜底发现（见 1.9）
            }
        });
        // return：交换机收到但未路由到任何队列
        rabbitTemplate.setReturnCallback((message, replyCode, replyText, exchange, routingKey) ->
                log.error("[MQ回退] 消息路由失败，exchange={}，routingKey={}，replyText={}",
                        exchange, routingKey, replyText));
    }
}