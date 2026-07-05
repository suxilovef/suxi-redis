package com.sw.yang.redis.scenario.string;

import java.time.Duration;
import java.util.Optional;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.UserProfile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisStringService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public RedisStringService(RedisTemplate<String, Object> redisTemplate, StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void saveUserProfile(UserProfile profile, Duration ttl) {
        Assert.notNull(profile, "profile must not be null");
        Assert.notNull(ttl, "ttl must not be null");
        redisTemplate.opsForValue().set(RedisKeys.userProfile(profile.userId()), profile, ttl);
    }

    public Optional<UserProfile> findUserProfile(long userId) {
        Object value = redisTemplate.opsForValue().get(RedisKeys.userProfile(userId));
        return value instanceof UserProfile profile ? Optional.of(profile) : Optional.empty();
    }

    public long incrementPageView(String pageId, Duration ttl) {
        Assert.notNull(ttl, "ttl must not be null");
        String key = RedisKeys.pageView(pageId);
        Long value = stringRedisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(value)) {
            stringRedisTemplate.expire(key, ttl);
        }
        return value == null ? 0L : value;
    }

    public Optional<String> getRawString(String key) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(key));
    }
}
