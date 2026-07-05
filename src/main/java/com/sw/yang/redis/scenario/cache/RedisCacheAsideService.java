package com.sw.yang.redis.scenario.cache;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisCacheAsideService {

    private static final String NULL_SENTINEL = "__NULL__";
    private static final Duration NULL_TTL = Duration.ofSeconds(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisCacheAsideService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public <T> T getOrLoad(String key, Class<T> valueType, Duration ttl, Supplier<T> loader) {
        Assert.hasText(key, "key must not be blank");
        Assert.notNull(valueType, "valueType must not be null");
        Assert.notNull(ttl, "ttl must not be null");
        Assert.notNull(loader, "loader must not be null");

        Object cached = redisTemplate.opsForValue().get(key);
        if (NULL_SENTINEL.equals(cached)) {
            return null;
        }
        if (valueType.isInstance(cached)) {
            return valueType.cast(cached);
        }

        T loaded = loader.get();
        if (loaded == null) {
            redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
        } else {
            redisTemplate.opsForValue().set(key, loaded, ttl);
        }
        return loaded;
    }

    public void invalidate(String key) {
        Assert.hasText(key, "key must not be blank");
        redisTemplate.delete(key);
    }
}
