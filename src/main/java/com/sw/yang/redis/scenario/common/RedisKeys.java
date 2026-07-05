package com.sw.yang.redis.scenario.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class RedisKeys {

    private static final String APP = "learn:redis";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private RedisKeys() {
    }

    public static String userProfile(long userId) {
        return APP + ":string:user-profile:" + userId;
    }

    public static String pageView(String pageId) {
        return APP + ":counter:page-view:" + clean(pageId);
    }

    public static String userSession(long userId) {
        return APP + ":hash:user-session:" + userId;
    }

    public static String recentUsers() {
        return APP + ":list:recent-users";
    }

    public static String onlineUsers() {
        return APP + ":set:online-users";
    }

    public static String hotArticles() {
        return APP + ":zset:hot-articles";
    }

    public static String dailySign(LocalDate day) {
        return APP + ":bitmap:daily-sign:" + BASIC_DATE.format(day);
    }

    public static String dailyUv(LocalDate day) {
        return APP + ":hll:daily-uv:" + BASIC_DATE.format(day);
    }

    public static String storesGeo() {
        return APP + ":geo:stores";
    }

    public static String orderStream() {
        return APP + ":stream:orders";
    }

    public static String lock(String resource) {
        return APP + ":lock:" + clean(resource);
    }

    public static String rateLimit(String subject, long windowIndex) {
        return APP + ":rate-limit:" + clean(subject) + ':' + windowIndex;
    }

    public static String idempotency(String businessKey) {
        return APP + ":idempotency:" + clean(businessKey);
    }

    public static String cache(String name, String id) {
        return APP + ":cache:" + clean(name) + ':' + clean(id);
    }

    public static String hotProduct(String sku) {
        return APP + ":cache:hot-product:" + clean(sku);
    }

    public static String productStock(String sku) {
        return APP + ":inventory:stock:" + clean(sku);
    }

    public static String pipelineValue(String batch, int index) {
        return APP + ":pipeline:" + clean(batch) + ':' + index;
    }

    private static String clean(String value) {
        String text = Objects.requireNonNull(value, "value must not be null").trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("redis key segment must not be blank");
        }
        return text.replaceAll("\\s+", "-");
    }
}
