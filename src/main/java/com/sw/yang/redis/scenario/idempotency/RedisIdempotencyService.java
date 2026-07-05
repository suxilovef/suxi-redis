package com.sw.yang.redis.scenario.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisIdempotencyService {

    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCEEDED = "SUCCEEDED";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> redisCompareAndDeleteScript;

    public RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisCompareAndDeleteScript") RedisScript<Long> redisCompareAndDeleteScript) {
        this.redisTemplate = redisTemplate;
        this.redisCompareAndDeleteScript = redisCompareAndDeleteScript;
    }

    public boolean begin(String businessKey, Duration processingTtl) {
        Assert.hasText(businessKey, "businessKey must not be blank");
        Assert.notNull(processingTtl, "processingTtl must not be null");
        Boolean stored = redisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.idempotency(businessKey), PROCESSING, processingTtl);
        return Boolean.TRUE.equals(stored);
    }

    public void markSucceeded(String businessKey, Duration resultTtl) {
        Assert.hasText(businessKey, "businessKey must not be blank");
        Assert.notNull(resultTtl, "resultTtl must not be null");
        redisTemplate.opsForValue().set(RedisKeys.idempotency(businessKey), SUCCEEDED, resultTtl);
    }

    public boolean releaseProcessing(String businessKey) {
        Assert.hasText(businessKey, "businessKey must not be blank");
        Long released = redisTemplate.execute(
                redisCompareAndDeleteScript,
                List.of(RedisKeys.idempotency(businessKey)),
                PROCESSING
        );
        return Long.valueOf(1L).equals(released);
    }

    public Optional<String> currentState(String businessKey) {
        Assert.hasText(businessKey, "businessKey must not be blank");
        return Optional.ofNullable(redisTemplate.opsForValue().get(RedisKeys.idempotency(businessKey)));
    }
}
