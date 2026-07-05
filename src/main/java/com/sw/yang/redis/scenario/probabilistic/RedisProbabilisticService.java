package com.sw.yang.redis.scenario.probabilistic;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisProbabilisticService {

    private final StringRedisTemplate redisTemplate;

    public RedisProbabilisticService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean recordSignIn(LocalDate day, long userId) {
        Assert.notNull(day, "day must not be null");
        Boolean previous = redisTemplate.opsForValue().setBit(RedisKeys.dailySign(day), userId, true);
        return !Boolean.TRUE.equals(previous);
    }

    public boolean hasSignedIn(LocalDate day, long userId) {
        Assert.notNull(day, "day must not be null");
        return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(RedisKeys.dailySign(day), userId));
    }

    public long countSignedIn(LocalDate day) {
        Assert.notNull(day, "day must not be null");
        byte[] key = RedisKeys.dailySign(day).getBytes(StandardCharsets.UTF_8);
        Long count = redisTemplate.execute((RedisCallback<Long>) connection -> connection.stringCommands().bitCount(key));
        return count == null ? 0L : count;
    }

    public long recordVisit(LocalDate day, String... visitorIds) {
        Assert.notNull(day, "day must not be null");
        Assert.notEmpty(visitorIds, "visitorIds must not be empty");
        Long added = redisTemplate.opsForHyperLogLog().add(RedisKeys.dailyUv(day), visitorIds);
        return added == null ? 0L : added;
    }

    public long estimateVisitCount(LocalDate day) {
        Assert.notNull(day, "day must not be null");
        Long size = redisTemplate.opsForHyperLogLog().size(RedisKeys.dailyUv(day));
        return size == null ? 0L : size;
    }

    public long mergeVisitCount(LocalDate targetDay, LocalDate... sourceDays) {
        Assert.notNull(targetDay, "targetDay must not be null");
        Assert.notEmpty(sourceDays, "sourceDays must not be empty");
        String[] sourceKeys = new String[sourceDays.length];
        for (int i = 0; i < sourceDays.length; i++) {
            sourceKeys[i] = RedisKeys.dailyUv(sourceDays[i]);
        }
        Long size = redisTemplate.opsForHyperLogLog().union(RedisKeys.dailyUv(targetDay), sourceKeys);
        return size == null ? 0L : size;
    }
}
