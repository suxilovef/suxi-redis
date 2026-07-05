package com.sw.yang.redis.scenario.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisDistributedLockService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> redisCompareAndDeleteScript;

    public RedisDistributedLockService(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisCompareAndDeleteScript") RedisScript<Long> redisCompareAndDeleteScript) {
        this.redisTemplate = redisTemplate;
        this.redisCompareAndDeleteScript = redisCompareAndDeleteScript;
    }

    public Optional<LockToken> tryLock(String resource, Duration ttl) {
        Assert.hasText(resource, "resource must not be blank");
        Assert.notNull(ttl, "ttl must not be null");
        String key = RedisKeys.lock(resource);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.of(new LockToken(key, token, ttl));
        }
        return Optional.empty();
    }

    public boolean unlock(LockToken token) {
        Assert.notNull(token, "token must not be null");
        Long released = redisTemplate.execute(redisCompareAndDeleteScript, List.of(token.key()), token.token());
        return Long.valueOf(1L).equals(released);
    }

    public <T> Optional<T> executeWithLock(String resource, Duration ttl, Supplier<T> action) {
        Assert.notNull(action, "action must not be null");
        Optional<LockToken> token = tryLock(resource, ttl);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            unlock(token.get());
        }
    }

    public record LockToken(String key, String token, Duration ttl) {
    }
}
