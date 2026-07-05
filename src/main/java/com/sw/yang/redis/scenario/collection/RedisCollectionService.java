package com.sw.yang.redis.scenario.collection;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.ArticleScore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisCollectionService {

    private static final int MAX_RECENT_USERS = 100;

    private final StringRedisTemplate redisTemplate;

    public RedisCollectionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void pushRecentUser(long userId) {
        String key = RedisKeys.recentUsers();
        redisTemplate.opsForList().leftPush(key, String.valueOf(userId));
        redisTemplate.opsForList().trim(key, 0, MAX_RECENT_USERS - 1);
    }

    public List<String> recentUsers(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> values = redisTemplate.opsForList().range(RedisKeys.recentUsers(), 0, limit - 1L);
        return values == null ? List.of() : values;
    }

    public void markOnline(long userId) {
        redisTemplate.opsForSet().add(RedisKeys.onlineUsers(), String.valueOf(userId));
    }

    public void markOffline(long userId) {
        redisTemplate.opsForSet().remove(RedisKeys.onlineUsers(), String.valueOf(userId));
    }

    public boolean isOnline(long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisKeys.onlineUsers(), String.valueOf(userId)));
    }

    public Set<String> onlineUsers() {
        Set<String> members = redisTemplate.opsForSet().members(RedisKeys.onlineUsers());
        return members == null ? Set.of() : members;
    }

    public void scoreArticle(String articleId, double score) {
        Assert.hasText(articleId, "articleId must not be blank");
        redisTemplate.opsForZSet().add(RedisKeys.hotArticles(), articleId, score);
    }

    public List<ArticleScore> topArticles(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(RedisKeys.hotArticles(), 0, limit - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                .map(tuple -> new ArticleScore(Objects.requireNonNull(tuple.getValue()), tuple.getScore()))
                .toList();
    }
}
