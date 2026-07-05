package com.sw.yang.redis.scenario.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.lock.RedisDistributedLockService;
import com.sw.yang.redis.scenario.model.ProductDetail;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisCacheProtectionService {

    private static final String NULL_SENTINEL = "__NULL__";
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final Duration HOT_REBUILD_LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration HOT_RETRY_WAIT = Duration.ofMillis(50);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisDistributedLockService lockService;
    private final ConcurrentMap<String, ProductDetail> repository = new ConcurrentHashMap<>();

    public RedisCacheProtectionService(
            RedisTemplate<String, Object> redisTemplate,
            RedisDistributedLockService lockService) {
        this.redisTemplate = redisTemplate;
        this.lockService = lockService;
        repository.put("sku-3001", new ProductDetail("sku-3001", "Null Cache Demo Product", BigDecimal.valueOf(59.90)));
        repository.put("sku-hot-1", new ProductDetail("sku-hot-1", "Hot Product", BigDecimal.valueOf(299.00)));
    }

    public Optional<ProductDetail> getProductWithNullCache(String sku, Duration ttl) {
        Assert.hasText(sku, "sku must not be blank");
        Assert.notNull(ttl, "ttl must not be null");

        String key = RedisKeys.cache("product-null-guard", sku);
        Object cached = redisTemplate.opsForValue().get(key);
        if (NULL_SENTINEL.equals(cached)) {
            return Optional.empty();
        }
        if (cached instanceof ProductDetail detail) {
            return Optional.of(detail);
        }

        ProductDetail loaded = repository.get(sku);
        if (loaded == null) {
            redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
            return Optional.empty();
        }
        redisTemplate.opsForValue().set(key, loaded, ttl);
        return Optional.of(loaded);
    }

    public Optional<ProductDetail> getHotProductWithMutex(String sku, Duration ttl) {
        Assert.hasText(sku, "sku must not be blank");
        Assert.notNull(ttl, "ttl must not be null");

        String key = RedisKeys.hotProduct(sku);
        Optional<ProductDetail> cached = readProduct(key);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<RedisDistributedLockService.LockToken> token =
                lockService.tryLock("hot-product-rebuild:" + sku, HOT_REBUILD_LOCK_TTL);
        if (token.isPresent()) {
            try {
                Optional<ProductDetail> rechecked = readProduct(key);
                if (rechecked.isPresent()) {
                    return rechecked;
                }
                ProductDetail loaded = repository.get(sku);
                if (loaded == null) {
                    redisTemplate.opsForValue().set(key, NULL_SENTINEL, NULL_TTL);
                    return Optional.empty();
                }
                redisTemplate.opsForValue().set(key, loaded, ttl);
                return Optional.of(loaded);
            } finally {
                lockService.unlock(token.get());
            }
        }

        LockSupport.parkNanos(HOT_RETRY_WAIT.toNanos());
        return readProduct(key).or(() -> Optional.ofNullable(repository.get(sku)));
    }

    public Duration cacheWithRandomTtl(ProductDetail detail, Duration baseTtl, Duration maxJitter) {
        Assert.notNull(detail, "detail must not be null");
        Duration ttl = randomizeTtl(baseTtl, maxJitter);
        redisTemplate.opsForValue().set(RedisKeys.cache("product-random-ttl", detail.sku()), detail, ttl);
        return ttl;
    }

    public Map<String, Object> runCacheProtectionDemo() {
        Optional<ProductDetail> existing = getProductWithNullCache("sku-3001", Duration.ofMinutes(10));
        Optional<ProductDetail> missing = getProductWithNullCache("missing-sku", Duration.ofMinutes(10));
        Optional<ProductDetail> hot = getHotProductWithMutex("sku-hot-1", Duration.ofMinutes(5));
        Duration randomizedTtl = cacheWithRandomTtl(
                new ProductDetail("sku-random-ttl", "Random TTL Product", BigDecimal.valueOf(39.90)),
                Duration.ofMinutes(10),
                Duration.ofMinutes(5)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nullCacheExisting", existing.orElse(null));
        result.put("nullCacheMissing", missing.map(ProductDetail::sku).orElse("cached-null"));
        result.put("hotProduct", hot.orElse(null));
        result.put("randomizedTtlSeconds", randomizedTtl.toSeconds());
        return result;
    }

    private Optional<ProductDetail> readProduct(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        if (NULL_SENTINEL.equals(value)) {
            return Optional.empty();
        }
        return value instanceof ProductDetail detail ? Optional.of(detail) : Optional.empty();
    }

    private Duration randomizeTtl(Duration baseTtl, Duration maxJitter) {
        Assert.notNull(baseTtl, "baseTtl must not be null");
        Assert.notNull(maxJitter, "maxJitter must not be null");
        Assert.isTrue(!baseTtl.isNegative() && !baseTtl.isZero(), "baseTtl must be positive");
        Assert.isTrue(!maxJitter.isNegative(), "maxJitter must not be negative");
        long jitterMillis = maxJitter.toMillis();
        if (jitterMillis == 0) {
            return baseTtl;
        }
        return baseTtl.plusMillis(ThreadLocalRandom.current().nextLong(jitterMillis + 1));
    }
}
