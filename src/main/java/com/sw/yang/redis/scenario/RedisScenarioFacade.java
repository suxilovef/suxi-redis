package com.sw.yang.redis.scenario;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sw.yang.redis.scenario.cache.CatalogCacheService;
import com.sw.yang.redis.scenario.cache.RedisCacheProtectionService;
import com.sw.yang.redis.scenario.cache.RedisCacheAsideService;
import com.sw.yang.redis.scenario.collection.RedisCollectionService;
import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.geo.RedisGeoService;
import com.sw.yang.redis.scenario.hash.RedisHashService;
import com.sw.yang.redis.scenario.idempotency.RedisIdempotencyService;
import com.sw.yang.redis.scenario.inventory.RedisInventoryService;
import com.sw.yang.redis.scenario.lock.RedisDistributedLockService;
import com.sw.yang.redis.scenario.model.ProductDetail;
import com.sw.yang.redis.scenario.model.StoreLocation;
import com.sw.yang.redis.scenario.model.UserProfile;
import com.sw.yang.redis.scenario.pipeline.RedisPipelineService;
import com.sw.yang.redis.scenario.probabilistic.RedisProbabilisticService;
import com.sw.yang.redis.scenario.pubsub.RedisPubSubService;
import com.sw.yang.redis.scenario.ratelimit.RedisRateLimiter;
import com.sw.yang.redis.scenario.stream.RedisStreamService;
import com.sw.yang.redis.scenario.string.RedisStringService;
import org.springframework.stereotype.Service;

@Service
public class RedisScenarioFacade {

    private final RedisStringService stringService;
    private final RedisHashService hashService;
    private final RedisCollectionService collectionService;
    private final RedisProbabilisticService probabilisticService;
    private final RedisGeoService geoService;
    private final RedisStreamService streamService;
    private final RedisDistributedLockService lockService;
    private final RedisRateLimiter rateLimiter;
    private final RedisIdempotencyService idempotencyService;
    private final RedisCacheAsideService cacheAsideService;
    private final RedisCacheProtectionService cacheProtectionService;
    private final CatalogCacheService catalogCacheService;
    private final RedisPipelineService pipelineService;
    private final RedisInventoryService inventoryService;
    private final RedisPubSubService pubSubService;

    public RedisScenarioFacade(
            RedisStringService stringService,
            RedisHashService hashService,
            RedisCollectionService collectionService,
            RedisProbabilisticService probabilisticService,
            RedisGeoService geoService,
            RedisStreamService streamService,
            RedisDistributedLockService lockService,
            RedisRateLimiter rateLimiter,
            RedisIdempotencyService idempotencyService,
            RedisCacheAsideService cacheAsideService,
            RedisCacheProtectionService cacheProtectionService,
            CatalogCacheService catalogCacheService,
            RedisPipelineService pipelineService,
            RedisInventoryService inventoryService,
            RedisPubSubService pubSubService) {
        this.stringService = stringService;
        this.hashService = hashService;
        this.collectionService = collectionService;
        this.probabilisticService = probabilisticService;
        this.geoService = geoService;
        this.streamService = streamService;
        this.lockService = lockService;
        this.rateLimiter = rateLimiter;
        this.idempotencyService = idempotencyService;
        this.cacheAsideService = cacheAsideService;
        this.cacheProtectionService = cacheProtectionService;
        this.catalogCacheService = catalogCacheService;
        this.pipelineService = pipelineService;
        this.inventoryService = inventoryService;
        this.pubSubService = pubSubService;
    }

    public RedisScenarioResponse runAll() {
        LocalDate today = LocalDate.now();
        long userId = 1001L;
        Map<String, Object> result = new LinkedHashMap<>();

        UserProfile profile = new UserProfile(userId, "yang", "yang@example.com");
        stringService.saveUserProfile(profile, Duration.ofMinutes(20));
        result.put("string", stringService.findUserProfile(userId).orElseThrow());
        result.put("counter", stringService.incrementPageView("home", Duration.ofDays(1)));

        hashService.saveSession(userId, Map.of("ip", "127.0.0.1", "device", "browser"), Duration.ofMinutes(30));
        result.put("hash", hashService.getSession(userId));

        collectionService.pushRecentUser(userId);
        collectionService.markOnline(userId);
        collectionService.scoreArticle("article-1001", 98.5);
        result.put("list", collectionService.recentUsers(5));
        result.put("set", collectionService.onlineUsers());
        result.put("zset", collectionService.topArticles(5));

        result.put("bitmapFirstSignIn", probabilisticService.recordSignIn(today, userId));
        result.put("bitmapSignedCount", probabilisticService.countSignedIn(today));
        probabilisticService.recordVisit(today, "visitor-1", "visitor-2", "visitor-1");
        result.put("hyperLogLogUv", probabilisticService.estimateVisitCount(today));

        geoService.addStore(new StoreLocation("store-shanghai", 121.4737, 31.2304));
        geoService.addStore(new StoreLocation("store-hangzhou", 120.1551, 30.2741));
        result.put("geo", geoService.nearbyStores(121.4737, 31.2304, 200));

        streamService.createConsumerGroupIfAbsent("order-workers");
        result.put("streamRecordId", streamService.appendOrderEvent(Map.of("orderId", "O1001", "status", "CREATED")));

        result.put("lock", lockService.executeWithLock("daily-report", Duration.ofSeconds(15), () -> "locked-and-done")
                .orElse("lock-not-acquired"));
        result.put("rateLimit", rateLimiter.allow("user:" + userId, 10, Duration.ofMinutes(1)));

        String requestId = "payment:" + userId + ':' + today;
        boolean firstRequest = idempotencyService.begin(requestId, Duration.ofMinutes(5));
        if (firstRequest) {
            idempotencyService.markSucceeded(requestId, Duration.ofHours(2));
        }
        result.put("idempotency", idempotencyService.currentState(requestId).orElse("NONE"));

        ProductDetail cachedProduct = cacheAsideService.getOrLoad(
                RedisKeys.cache("product", "sku-1001"),
                ProductDetail.class,
                Duration.ofMinutes(10),
                () -> new ProductDetail("sku-1001", "Cache Aside Product", BigDecimal.valueOf(129.00))
        );
        result.put("cacheAside", cachedProduct);

        ProductDetail saved = catalogCacheService.saveProduct(
                new ProductDetail("sku-2001", "Annotation Cache Product", BigDecimal.valueOf(199.00))
        );
        result.put("cacheAnnotation", catalogCacheService.getProduct(saved.sku()));
        result.put("cacheProtection", cacheProtectionService.runCacheProtectionDemo());

        result.put("pipelineWrite", pipelineService.writeSampleValues("stage5", 5, Duration.ofMinutes(5)));
        result.put("pipelineRead", pipelineService.readSampleValues("stage5", 5));

        inventoryService.initializeStock("sku-stock-1", 10, Duration.ofMinutes(10));
        result.put("inventoryDeductSuccess", inventoryService.deduct("sku-stock-1", 3));
        result.put("inventoryDeductInsufficient", inventoryService.deduct("sku-stock-1", 20));
        result.put("pubSubReceivers", pubSubService.publish("redis scenario broadcast"));

        return new RedisScenarioResponse(result);
    }

    public record RedisScenarioResponse(Map<String, Object> scenarios) {
    }
}
