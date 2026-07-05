package com.sw.yang.redis.scenario.model;

public record InventoryDeductionResult(String sku, long amount, Long remainingStock, Status status) {

    public enum Status {
        SUCCEEDED,
        NOT_FOUND,
        INSUFFICIENT,
        INVALID_AMOUNT,
        UNKNOWN
    }
}
