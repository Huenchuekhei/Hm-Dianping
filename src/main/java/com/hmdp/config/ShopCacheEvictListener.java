package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * 缓存失效广播订阅者：收到 shop.evict 消息后失效本节点的 L1/L2 缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopCacheEvictListener implements MessageListener {

    private final Cache<String, Shop> shopLocalCache;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String shopId = new String(message.getBody());
        // 失效 L1
        shopLocalCache.invalidate(CACHE_SHOP_KEY + shopId);
        // 失效 L2（逻辑过期缓存）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        log.info("[缓存广播] 已失效商铺缓存，shopId={}", shopId);
    }
}