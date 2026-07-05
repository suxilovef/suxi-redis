package com.sw.yang.redis.scenario.pubsub;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RedisBroadcastSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisBroadcastSubscriber.class);

    private final AtomicReference<String> lastMessage = new AtomicReference<>();

    public void handleMessage(String message) {
        lastMessage.set(message);
        log.info("Received redis broadcast message: {}", message);
    }

    public Optional<String> lastMessage() {
        return Optional.ofNullable(lastMessage.get());
    }
}
