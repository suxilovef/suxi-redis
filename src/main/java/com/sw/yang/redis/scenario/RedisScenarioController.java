package com.sw.yang.redis.scenario;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/redis/scenarios")
public class RedisScenarioController {

    private final RedisScenarioFacade facade;

    public RedisScenarioController(RedisScenarioFacade facade) {
        this.facade = facade;
    }

    @GetMapping
    public Map<String, List<String>> scenarios() {
        return Map.of("scenarios", List.of(
                "string/json value",
                "counter",
                "hash",
                "list",
                "set",
                "sorted set",
                "bitmap",
                "hyperloglog",
                "geo",
                "stream",
                "pub/sub",
                "distributed lock",
                "rate limit",
                "idempotency",
                "cache aside",
                "spring cache annotation",
                "cache penetration guard",
                "hot key mutex rebuild",
                "randomized ttl",
                "pipeline",
                "lua inventory deduction"
        ));
    }

    @PostMapping("/run-all")
    public RedisScenarioFacade.RedisScenarioResponse runAll() {
        return facade.runAll();
    }
}
