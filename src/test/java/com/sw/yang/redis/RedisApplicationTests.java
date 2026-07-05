package com.sw.yang.redis;

import com.sw.yang.redis.scenario.common.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RedisApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void redisKeysShouldBeNamespaced() {
        assertThat(RedisKeys.userProfile(1001)).isEqualTo("learn:redis:string:user-profile:1001");
        assertThat(RedisKeys.cache("product", "sku 1001")).isEqualTo("learn:redis:cache:product:sku-1001");
    }

    @Test
    void redisKeysShouldRejectBlankSegment() {
        assertThatThrownBy(() -> RedisKeys.cache("product", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
