package com.sw.yang.redis.scenario.stream;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.sw.yang.redis.scenario.common.RedisKeys;
import com.sw.yang.redis.scenario.model.StreamEntry;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
public class RedisStreamService {

    private static final String BUSY_GROUP = "BUSYGROUP";

    private final StringRedisTemplate redisTemplate;

    public RedisStreamService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String appendOrderEvent(Map<String, String> body) {
        Assert.notEmpty(body, "body must not be empty");
        MapRecord<String, String, String> record = StreamRecords.mapBacked(body)
                .withStreamKey(RedisKeys.orderStream());
        RecordId recordId = redisTemplate.opsForStream().add(record);
        return recordId == null ? "" : recordId.getValue();
    }

    public void createConsumerGroupIfAbsent(String group) {
        Assert.hasText(group, "group must not be blank");
        ensureStreamExists();
        try {
            redisTemplate.opsForStream().createGroup(RedisKeys.orderStream(), ReadOffset.from("0-0"), group);
        } catch (RedisSystemException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains(BUSY_GROUP)) {
                throw ex;
            }
        }
    }

    public List<StreamEntry> readFromGroup(String group, String consumer, int count, Duration block) {
        Assert.hasText(group, "group must not be blank");
        Assert.hasText(consumer, "consumer must not be blank");
        if (count <= 0) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count).block(block),
                StreamOffset.create(RedisKeys.orderStream(), ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(this::toEntry).toList();
    }

    public long acknowledge(String group, String... recordIds) {
        Assert.hasText(group, "group must not be blank");
        if (recordIds == null || recordIds.length == 0) {
            return 0L;
        }
        RecordId[] ids = Arrays.stream(recordIds)
                .map(RecordId::of)
                .toArray(RecordId[]::new);
        Long acknowledged = redisTemplate.opsForStream().acknowledge(RedisKeys.orderStream(), group, ids);
        return acknowledged == null ? 0L : acknowledged;
    }

    private void ensureStreamExists() {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.orderStream()))) {
            appendOrderEvent(Map.of("type", "bootstrap"));
        }
    }

    private StreamEntry toEntry(MapRecord<String, Object, Object> record) {
        Map<String, String> body = record.getValue().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> String.valueOf(entry.getKey()),
                        entry -> String.valueOf(entry.getValue())
                ));
        return new StreamEntry(record.getId().getValue(), body);
    }
}
