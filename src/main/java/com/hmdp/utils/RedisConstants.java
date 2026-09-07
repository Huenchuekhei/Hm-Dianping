package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    public static final String SHOP_TYPE_KEY = "shop_type:";
    public static final Long SHOP_TYPE_LONG=10L;

    // ==================== 魔改升级新增 ====================
    /** 动态秒杀路径 */
    public static final String SECKILL_PATH_KEY = "seckill:path:";
    /** 店铺浏览 UV（按天） */
    public static final String SHOP_UV_KEY = "uv:shop:";
    /** 店铺浏览热度榜 */
    public static final String SHOP_HOT_KEY = "hot:shop";
    /** 本地缓存失效广播频道 */
    public static final String CACHE_EVICT_TOPIC = "cache:evict:shop";
    /** 滑动窗口限流 key 前缀 */
    public static final String RATE_LIMIT_KEY = "rate_limit:";
    /** 订单超时未支付时长（分钟），与延迟队列 TTL 保持一致 */
    public static final long ORDER_UNPAID_TTL_MINUTES = 15L;
}
