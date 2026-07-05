package com.sw.yang.redis.scenario.hash;

import java.time.Duration;
import java.util.Map;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisHashService {

    private final StringRedisTemplate redisTemplate;

    public RedisHashService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveSession(long userId, Map<String, String> attributes, Duration ttl) {
        Assert.notEmpty(attributes, "attributes must not be empty");
        String key = RedisKeys.userSession(userId);
        hash().putAll(key, attributes);
        redisTemplate.expire(key, ttl);
    }

    public void updateSessionField(long userId, String field, String value, Duration ttl) {
        Assert.hasText(field, "field must not be blank");
        Assert.notNull(value, "value must not be null");
        String key = RedisKeys.userSession(userId);
        hash().put(key, field, value);
        redisTemplate.expire(key, ttl);
    }

    public Map<String, String> getSession(long userId) {
        return hash().entries(RedisKeys.userSession(userId));
    }

    public void deleteSession(long userId) {
        redisTemplate.delete(RedisKeys.userSession(userId));
    }

    private HashOperations<String, String, String> hash() {
        return redisTemplate.opsForHash();
    }
}
