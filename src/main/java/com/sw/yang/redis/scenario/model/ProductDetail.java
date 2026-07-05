package com.sw.yang.redis.scenario.model;

import java.math.BigDecimal;

public record ProductDetail(String sku, String name, BigDecimal price) {
}
