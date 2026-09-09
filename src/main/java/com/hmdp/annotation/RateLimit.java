package com.hmdp.annotation;

import java.lang.annotation.*;

/**
 * 滑动窗口限流注解（Redis + Lua）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 时间窗口（秒） */
    int windowSeconds() default 1;

    /** 窗口内最大请求数 */
    int maxCount() default 100;

    /** 限流维度 */
    LimitType limitType() default LimitType.GLOBAL;

    enum LimitType { GLOBAL, USER, IP }
}