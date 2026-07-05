package com.sw.yang.redis.scenario.model;

import java.time.Duration;

public record RateLimitResult(boolean allowed, String key, Duration window) {
}
