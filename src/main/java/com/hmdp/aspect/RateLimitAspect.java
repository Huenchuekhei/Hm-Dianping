package com.hmdp.aspect;

import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.apache.rocketmq.util.cache.LockManager.buildKey;

@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("ratelimit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable{
        // 1. 生成分层限流Key
        String key = buildKey(pjp, rateLimit);
        long now = System.currentTimeMillis();
        // 2. 执行Lua限流脚本（原子校验）
        Long allowed = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(rateLimit.windowSeconds() * 1000L), // 时间窗口(毫秒)
                String.valueOf(rateLimit.maxCount())); // 窗口最大请求数
        // 3. 限流判断：0/空 = 超限拦截
        if (allowed == null || allowed == 0) {
            log.warn("[限流] 已拦截，key={}", key);
            throw new RuntimeException("请求过于火爆，请稍后再试");
        }
        // 4. 校验通过，放行执行目标接口
        return pjp.proceed();
    }

    private String buildKey(ProceedingJoinPoint pjp, RateLimit rateLimit) {
        String method = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        switch (rateLimit.limitType()) {
            case USER:
                UserDTO user = UserHolder.getUser();
                return RedisConstants.RATE_LIMIT_KEY + method + ":u:"
                        + (user == null ? "anonymous" : user.getId());
            case IP:
                HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder
                        .currentRequestAttributes()).getRequest();
                return RedisConstants.RATE_LIMIT_KEY + method + ":ip:" + request.getRemoteAddr();
            default:
                return RedisConstants.RATE_LIMIT_KEY + method;
        }
    }
}
