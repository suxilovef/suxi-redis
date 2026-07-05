package com.sw.yang.redis.scenario.pubsub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisPubSubService {

    private final StringRedisTemplate redisTemplate;
    private final String channel;

    public RedisPubSubService(
            StringRedisTemplate redisTemplate,
            @Value("${app.redis-examples.pub-sub.channel}") String channel) {
        this.redisTemplate = redisTemplate;
        this.channel = channel;
    }

    public long publish(String message) {
        Assert.hasText(message, "message must not be blank");
        Long receivers = redisTemplate.convertAndSend(channel, message);
        return receivers == null ? 0L : receivers;
    }
}
