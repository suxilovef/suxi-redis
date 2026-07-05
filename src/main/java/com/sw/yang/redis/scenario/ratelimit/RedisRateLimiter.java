package com.sw.yang.redis.scenario.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> redisFixedWindowRateLimitScript;
    private final Clock clock;

    @Autowired
    public RedisRateLimiter(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisFixedWindowRateLimitScript") RedisScript<Long> redisFixedWindowRateLimitScript) {
        this(redisTemplate, redisFixedWindowRateLimitScript, Clock.systemUTC());
    }

    RedisRateLimiter(
            StringRedisTemplate redisTemplate,
            RedisScript<Long> redisFixedWindowRateLimitScript,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.redisFixedWindowRateLimitScript = redisFixedWindowRateLimitScript;
        this.clock = clock;
    }

    public RateLimitResult allow(String subject, int limit, Duration window) {
        Assert.hasText(subject, "subject must not be blank");
        Assert.isTrue(limit > 0, "limit must be greater than zero");
        Assert.notNull(window, "window must not be null");
        Assert.isTrue(!window.isNegative() && !window.isZero(), "window must be positive");

        long windowIndex = clock.millis() / window.toMillis();
        String key = RedisKeys.rateLimit(subject, windowIndex);
        Long allowed = redisTemplate.execute(
                redisFixedWindowRateLimitScript,
                List.of(key),
                String.valueOf(window.toMillis()),
                String.valueOf(limit)
        );
        return new RateLimitResult(Long.valueOf(1L).equals(allowed), key, window);
    }
}
