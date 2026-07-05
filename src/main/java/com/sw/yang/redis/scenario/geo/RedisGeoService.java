package com.sw.yang.redis.scenario.geo;

import java.util.List;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.StoreDistance;
import com.sw.yang.redis.scenario.model.StoreLocation;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisGeoService {

    private final StringRedisTemplate redisTemplate;

    public RedisGeoService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void addStore(StoreLocation location) {
        Assert.notNull(location, "location must not be null");
        redisTemplate.opsForGeo().add(
                RedisKeys.storesGeo(),
                new Point(location.longitude(), location.latitude()),
                location.storeId()
        );
    }

    public List<StoreDistance> nearbyStores(double longitude, double latitude, double radiusKm) {
        Circle circle = new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS));
        RedisGeoCommands.GeoRadiusCommandArgs args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                .includeDistance()
                .sortAscending();
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                .radius(RedisKeys.storesGeo(), circle, args);
        if (results == null) {
            return List.of();
        }
        return results.getContent().stream()
                .map(this::toStoreDistance)
                .toList();
    }

    public void removeStore(String storeId) {
        redisTemplate.opsForZSet().remove(RedisKeys.storesGeo(), storeId);
    }

    private StoreDistance toStoreDistance(GeoResult<RedisGeoCommands.GeoLocation<String>> result) {
        return new StoreDistance(result.getContent().getName(), result.getDistance().getValue());
    }
}
