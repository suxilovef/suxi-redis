package com.sw.yang.redis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisLuaConfiguration {

    @Bean
    public RedisScript<Long> redisCompareAndDeleteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """);
        return script;
    }

    @Bean
    public RedisScript<Long> redisFixedWindowRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local current = redis.call('incr', KEYS[1])
                if current == 1 then
                    redis.call('pexpire', KEYS[1], ARGV[1])
                end
                if current > tonumber(ARGV[2]) then
                    return 0
                end
                return 1
                """);
        return script;
    }

    @Bean
    public RedisScript<Long> redisDeductStockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local current = redis.call('get', KEYS[1])
                if current == false then
                    return -1
                end
                local stock = tonumber(current)
                local amount = tonumber(ARGV[1])
                if amount == nil or amount <= 0 then
                    return -3
                end
                if stock < amount then
                    return -2
                end
                return redis.call('decrby', KEYS[1], amount)
                """);
        return script;
    }
}
