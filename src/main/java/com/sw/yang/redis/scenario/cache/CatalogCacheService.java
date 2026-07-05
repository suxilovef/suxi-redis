package com.sw.yang.redis.scenario.cache;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.sw.yang.redis.scenario.model.ProductDetail;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class CatalogCacheService {

    private final ConcurrentMap<String, ProductDetail> repository = new ConcurrentHashMap<>();

    @Cacheable(cacheNames = "catalog", key = "#sku", unless = "#result == null")
    public ProductDetail getProduct(String sku) {
        Assert.hasText(sku, "sku must not be blank");
        return repository.computeIfAbsent(sku, this::loadFromRepository);
    }

    @CachePut(cacheNames = "catalog", key = "#detail.sku")
    public ProductDetail saveProduct(ProductDetail detail) {
        Assert.notNull(detail, "detail must not be null");
        repository.put(detail.sku(), detail);
        return detail;
    }

    @CacheEvict(cacheNames = "catalog", key = "#sku")
    public void evictProduct(String sku) {
        Assert.hasText(sku, "sku must not be blank");
        repository.remove(sku);
    }

    private ProductDetail loadFromRepository(String sku) {
        return new ProductDetail(sku, "Redis Practice Product " + sku, BigDecimal.valueOf(99.90));
    }
}
