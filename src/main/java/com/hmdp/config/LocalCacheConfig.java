package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_EVICT_TOPIC;

@Configuration
public class LocalCacheConfig {

    @Resource
    private ShopCacheEvictListener shopCacheEvictListener;

    /** L1 本地缓存：W-TinyLFU 淘汰，最大 1000 条，写后 60s 过期 */
    @Bean
    public Cache<String, Shop> shopLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build();
    }

    /** 缓存失效广播：交换机与队列（多节点部署时每个节点都会收到） */
    @Bean
    public TopicExchange cacheEvictExchange() {
        return new TopicExchange("cache.evict.exchange");
    }

    @Bean
    public Queue cacheEvictQueue() {
        // 匿名独占队列：每个应用实例各建一条，节点各自消费，互不影响
        return new Queue(CACHE_EVICT_TOPIC + ".queue." + System.currentTimeMillis(),
                false, true, true);
    }

    @Bean
    public Binding cacheEvictBinding(Queue cacheEvictQueue, TopicExchange cacheEvictExchange) {
        return BindingBuilder.bind(cacheEvictQueue).to(cacheEvictExchange).with("shop.evict");
    }

    /** Redis Pub/Sub 方案（二选一即可，默认使用 RabbitMQ 广播，见下方说明） */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(shopCacheEvictListener, new ChannelTopic(CACHE_EVICT_TOPIC));
        return container;
    }
}