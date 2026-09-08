package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@RequiredArgsConstructor
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient clientClient;

    private final Cache<String, Shop> shopLocalCache;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id){
        String key = CACHE_SHOP_KEY + id;
        // ===== L1：Caffeine 本地缓存（W-TinyLFU，命中则零网络开销）=====
        Shop shop = shopLocalCache.getIfPresent(key);
        if (shop == null) {
            // ===== L2：Redis 逻辑过期缓存（防击穿：互斥重建 + 返回旧值）=====
            shop = clientClient.queryWithLogicalExpire(
                    CACHE_SHOP_KEY, id, Shop.class, this::getById,
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // ===== L2 miss（未预热商铺）→ 回源 DB（防穿透：空值缓存）=====
            if (shop == null) {
                shop = clientClient.queryWithPassThrough(
                        CACHE_SHOP_KEY, id, Shop.class, this::getById,
                        CACHE_SHOP_TTL, TimeUnit.MINUTES);
            }
            if (shop != null) {
                shopLocalCache.put(key, shop);
            }
        }
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // ===== 浏览埋点：UV（HLL 去重）+ 热度榜（ZSet 计数），供 2.5/2.6 与预热使用 =====
        recordShopView(id);
        return Result.ok(shop);
    }

    private void recordShopView(Long shopId) {
        String today = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //获取当前登录用户（null 就是访客）
        UserDTO user = UserHolder.getUser();
        String visitor = user != null ? String.valueOf(user.getId())
                : "guest:" + UUID.randomUUID();
        // 日 UV：HyperLogLog 去重统计（误差 0.81%，万级 UV 仅 12KB）
        stringRedisTemplate.opsForHyperLogLog().add(RedisConstants.SHOP_UV_KEY + today, visitor);
        // 热度榜：浏览计数
        stringRedisTemplate.opsForZSet().incrementScore(RedisConstants.SHOP_HOT_KEY,
                shopId.toString(), 1);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2.封装成逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除 L2 Redis 缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 3.失效本节点 L1 + 广播其他节点失效（多节点 L1 一致性）
        shopLocalCache.invalidate(CACHE_SHOP_KEY + id);
        stringRedisTemplate.convertAndSend(RedisConstants.CACHE_EVICT_TOPIC, String.valueOf(id));
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否根据坐标查询
        if(x==null||y==null){
            //不需要坐标查询，该数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis，按照距离排序，分页。结果：shopId,distance
        String key = SHOP_GEO_KEY + typeId;
        //在 Redis 中按地理坐标（x, y）查询距离当前用户位置 5000 米内的商店
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        //4.解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.1.截取从from到end部分  跳过前 from 个结果，实现分页
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //4.2.获取店铺id
            String shopIdStr=result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }
}
