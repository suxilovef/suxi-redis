package com.sw.yang.redis.scenario.inventory;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.InventoryDeductionResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisInventoryService {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> redisDeductStockScript;

    public RedisInventoryService(
            StringRedisTemplate redisTemplate,
            @Qualifier("redisDeductStockScript") RedisScript<Long> redisDeductStockScript) {
        this.redisTemplate = redisTemplate;
        this.redisDeductStockScript = redisDeductStockScript;
    }

    public void initializeStock(String sku, long quantity, Duration ttl) {
        Assert.hasText(sku, "sku must not be blank");
        Assert.isTrue(quantity >= 0, "quantity must not be negative");
        Assert.notNull(ttl, "ttl must not be null");
        redisTemplate.opsForValue().set(RedisKeys.productStock(sku), String.valueOf(quantity), ttl);
    }

    public InventoryDeductionResult deduct(String sku, long amount) {
        Assert.hasText(sku, "sku must not be blank");

        Long result = redisTemplate.execute(
                redisDeductStockScript,
                List.of(RedisKeys.productStock(sku)),
                String.valueOf(amount)
        );
        if (result == null) {
            return new InventoryDeductionResult(sku, amount, null, InventoryDeductionResult.Status.UNKNOWN);
        }
        if (result >= 0) {
            return new InventoryDeductionResult(sku, amount, result, InventoryDeductionResult.Status.SUCCEEDED);
        }
        InventoryDeductionResult.Status status = switch (result.intValue()) {
            case -1 -> InventoryDeductionResult.Status.NOT_FOUND;
            case -2 -> InventoryDeductionResult.Status.INSUFFICIENT;
            case -3 -> InventoryDeductionResult.Status.INVALID_AMOUNT;
            default -> InventoryDeductionResult.Status.UNKNOWN;
        };
        return new InventoryDeductionResult(sku, amount, null, status);
    }

    public OptionalLong stock(String sku) {
        Assert.hasText(sku, "sku must not be blank");
        String value = redisTemplate.opsForValue().get(RedisKeys.productStock(sku));
        if (value == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Long.parseLong(value));
    }
}
