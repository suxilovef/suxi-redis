package com.sw.yang.redis.scenario.model;

import java.util.Map;

public record StreamEntry(String id, Map<String, String> body) {
}
