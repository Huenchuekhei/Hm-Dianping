package com.hmdp.task;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 热点商铺预热：启动时把 TopN 热点商铺装入 L1/L2 缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotShopWarmupRunner implements ApplicationRunner {

    private static final int WARM_UP_COUNT = 20;

    private final ShopServiceImpl shopService;
    private final Cache<String, Shop> shopLocalCache;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        log.info("[预热] 开始预热热点商铺缓存...");
        // 1.取热度榜 TopN
        Set<String> hotIds = stringRedisTemplate.opsForZSet()
                .reverseRange(SHOP_HOT_KEY, 0, WARM_UP_COUNT - 1);
        List<Long> ids;
        if (hotIds == null || hotIds.isEmpty()) {
            // 2.冷启动兜底：DB 前 N 条
            ids = shopService.query().last("limit " + WARM_UP_COUNT).list()
                    .stream().map(Shop::getId).collect(java.util.stream.Collectors.toList());
        } else {
            ids = hotIds.stream().map(Long::valueOf)
                    .collect(java.util.stream.Collectors.toList());
        }
        // 3.逐个写入 L2（逻辑过期 30min）+ L1
        for (Long id : ids) {
            Shop shop = shopService.getById(id);
            if (shop == null) {
                continue;
            }
            //运营会定时修改商铺名称、价格、库存等数据 --- 设置TTL 30 分钟
            shopService.saveShop2Redis(id, CACHE_SHOP_TTL * 60);   // 复用已有方法写 L2 逻辑过期
            shopLocalCache.put(CACHE_SHOP_KEY + id, shop);          // 写 L1
        }
        log.info("[预热] 热点商铺预热完成，共 {} 条", ids.size());
    }
}