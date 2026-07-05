package com.sw.yang.redis.scenario.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisPipelineService {

    private final StringRedisTemplate redisTemplate;

    public RedisPipelineService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public PipelineBatchResult writeSampleValues(String batch, int count, Duration ttl) {
        Assert.hasText(batch, "batch must not be blank");
        Assert.isTrue(count > 0, "count must be greater than zero");
        Assert.notNull(ttl, "ttl must not be null");

        List<Object> replies = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (int i = 0; i < count; i++) {
                    operations.opsForValue().set(RedisKeys.pipelineValue(batch, i), "value-" + i, ttl);
                }
                return null;
            }
        });
        return new PipelineBatchResult(batch, count, replies == null ? 0 : replies.size());
    }

    public List<String> readSampleValues(String batch, int count) {
        Assert.hasText(batch, "batch must not be blank");
        Assert.isTrue(count > 0, "count must be greater than zero");

        List<Object> replies = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Object execute(RedisOperations operations) throws DataAccessException {
                for (int i = 0; i < count; i++) {
                    operations.opsForValue().get(RedisKeys.pipelineValue(batch, i));
                }
                return null;
            }
        });
        if (replies == null) {
            return List.of();
        }
        return replies.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    public record PipelineBatchResult(String batch, int commandCount, int replyCount) {
    }
}
