# Redis Java 工程接入与缓存架构

## 1. 这份文档解决什么问题

前四个阶段已经解决了 Redis 的单机部署、高可用、集群和线上排查问题。第 5 阶段开始，重点从“Redis 服务端怎么搭、怎么守”转向“Java 业务系统怎么正确使用 Redis”。

很多 Redis 生产事故不是 Redis 本身坏了，而是业务接入方式不正确：

- 客户端连接池配置不合理，连接不够用或把 Redis 压垮。
- 超时时间太长，Redis 抖动时拖垮业务线程。
- 重试策略失控，故障期间把 Redis 和数据库一起打满。
- Sentinel 或 Cluster 环境下仍然写死单个 Redis 地址。
- 缓存穿透、击穿、雪崩没有治理，数据库被流量打穿。
- 缓存一致性方案不清晰，出现脏数据或长期旧数据。
- 分布式锁被当成万能一致性工具，实际没有保护住业务。
- Pipeline、Lua、批量操作使用不当，造成阻塞或跨 slot 问题。

本阶段目标不是会调用 `RedisTemplate.opsForValue().set()`，而是能从工程角度回答：

```text
这个 Redis 用法在故障、并发、超时、扩容、数据不一致时是否还能成立？
```

完成本阶段后，你应该能把 Redis 正确接入 Java 服务，并能为缓存、锁、限流、幂等、排行榜、库存扣减等常见场景设计可解释的方案。

## 2. 本项目实验入口

当前项目已经是 Spring Boot + Spring Data Redis 项目，实验入口集中在：

```text
src/main/java/com/sw/yang/redis/scenario/
```

统一运行入口：

```text
POST /api/redis/scenarios/run-all
```

已有和本阶段相关的代码：

| 能力 | 代码位置 |
| --- | --- |
| RedisTemplate 序列化、缓存管理 | `config/RedisConfiguration.java` |
| Lua 脚本 Bean | `config/RedisLuaConfiguration.java` |
| Cache Aside | `scenario/cache/RedisCacheAsideService.java` |
| Spring Cache 注解 | `scenario/cache/CatalogCacheService.java` |
| 分布式锁 | `scenario/lock/RedisDistributedLockService.java` |
| 限流 | `scenario/ratelimit/RedisRateLimiter.java` |
| 幂等 | `scenario/idempotency/RedisIdempotencyService.java` |
| 排行榜 | `scenario/collection/RedisCollectionService.java` |
| Stream / PubSub | `scenario/stream`、`scenario/pubsub` |

第 5 阶段会继续补充：

- 缓存穿透保护。
- 热点 key 互斥重建。
- TTL 随机化。
- Pipeline 批量操作。
- Lua 原子扣减库存。
- 单机、Sentinel、Cluster 的配置示例和接入边界。

## 3. Java 客户端怎么选

### 3.1 Lettuce

Spring Boot 默认使用 Lettuce。它基于 Netty，线程安全，支持单机、Sentinel、Cluster，适合大多数 Spring 项目。

优点：

- Spring Data Redis 默认集成。
- 支持异步、响应式和连接复用。
- Cluster 拓扑刷新能力较成熟。
- 使用成本低，适合本项目学习。

注意点：

- 高并发阻塞式调用下仍然要配置连接池。
- Cluster 环境要关注拓扑刷新。
- 超时和重试要明确，不要使用默认配置糊弄生产环境。

### 3.2 Jedis

Jedis 是老牌客户端，API 接近 Redis 命令本身。早期版本每个连接不是线程安全的，需要连接池管理。新版本能力已经改善，但在 Spring Boot 项目中通常不是默认选择。

适合场景：

- 想用接近原生命令的 API 学习 Redis。
- 历史项目已经使用 Jedis。

### 3.3 Redisson

Redisson 更像 Redis 上层工具箱，封装了分布式锁、延迟队列、信号量、布隆过滤器等结构。

适合场景：

- 生产中需要较成熟的分布式锁实现。
- 希望减少自己维护锁续期、释放、重入等复杂逻辑。

边界：

- Redisson 不能把 Redis 分布式锁变成强一致事务。
- 锁只保护临界区，不保护外部系统失败、锁过期后业务仍在执行等问题。
- 关键金融、库存强一致场景仍然要以数据库事务、唯一约束、状态机或消息最终一致性为主。

## 4. 三种连接模式

### 4.1 单机模式

适合本地学习、缓存量小、可以接受单点风险的场景。

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      timeout: 2s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 2s
```

工程要点：

- 必须配置超时。
- 明确连接池大小。
- 本地学习可以用单机，生产要评估高可用和容量。

### 4.2 Sentinel 模式

适合单主多从自动故障转移。客户端不应该写死 master IP，而是通过 Sentinel 发现当前 master。

```yaml
spring:
  data:
    redis:
      password: Redis@123456
      sentinel:
        master: mymaster
        nodes:
          - 192.168.88.111:26379
          - 192.168.88.112:26379
          - 192.168.88.113:26379
      timeout: 2s
      lettuce:
        pool:
          max-active: 32
          max-idle: 16
          min-idle: 4
          max-wait: 2s
```

工程要点：

- 至少配置多个 Sentinel 地址。
- `master` 名称必须和 `sentinel monitor` 中的名称一致。
- failover 期间写入可能短暂失败，业务要允许有限重试或降级。
- 读 replica 时要接受复制延迟和旧数据风险。

### 4.3 Cluster 模式

适合容量和吞吐需要横向扩展的场景。客户端必须使用 Cluster 模式，能够处理 `MOVED`、`ASK` 和拓扑刷新。

```yaml
spring:
  data:
    redis:
      password: Redis@123456
      cluster:
        nodes:
          - 192.168.88.121:6379
          - 192.168.88.122:6379
          - 192.168.88.123:6379
          - 192.168.88.124:6379
          - 192.168.88.125:6379
          - 192.168.88.126:6379
        max-redirects: 5
      timeout: 2s
      lettuce:
        cluster:
          refresh:
            adaptive: true
            period: 30s
        pool:
          max-active: 64
          max-idle: 32
          min-idle: 8
          max-wait: 2s
```

工程要点：

- 配置多个 seed nodes，不要只配一个节点。
- 开启拓扑刷新，避免迁槽或 failover 后客户端长时间访问旧节点。
- 多 key 命令必须同 slot，必要时使用 hash tag。
- 不要把所有 key 都强行放进同一个 hash tag，否则 Cluster 分片失去意义。

## 5. 连接池、超时和重试

### 5.1 连接池不是越大越好

连接池太小：

- 业务线程等待连接。
- 请求排队，RT 增加。
- 容易误判成 Redis 服务端慢。

连接池太大：

- Redis 连接数暴涨。
- 上下文切换和网络开销增加。
- 故障时更多请求同时打向 Redis，放大抖动。

建议做法：

- 根据业务并发、Redis RT、实例承载能力估算连接数。
- 监控 `connected_clients`、客户端等待连接时间、Redis QPS 和 P99。
- 每个服务实例单独配置连接池，不要只看单个 JVM。

### 5.2 超时要服务于故障隔离

典型配置：

- 连接超时：1 到 3 秒。
- 命令超时：根据业务链路预算控制，常见 1 到 2 秒。
- 连接池等待：不要无限等待，建议明确 `max-wait`。

原则：

- Redis 是缓存时，超时后通常应该降级或查数据库，而不是一直等。
- Redis 是限流、幂等、锁时，超时要明确失败策略，不能默默放行。
- 不能把所有异常都无限重试。

### 5.3 重试要有限且带退避

适合重试：

- 短暂网络抖动。
- Sentinel failover 或 Cluster MOVED/ASK 刷新期间的瞬时失败。
- 幂等读请求。

不适合盲目重试：

- 非幂等写请求。
- Redis 已经内存打满。
- 连接池耗尽。
- 数据库已经被缓存穿透流量打满。

## 6. 缓存架构核心模式

### 6.1 Cache Aside

最常见模式：

```text
读请求:
  先读缓存
  -> 命中直接返回
  -> 未命中读数据库
  -> 写入缓存
  -> 返回

写请求:
  先写数据库
  -> 再删除缓存
```

为什么通常是删除缓存，而不是更新缓存：

- 更新缓存需要保证和数据库写入顺序一致，复杂度高。
- 复杂对象可能由多张表聚合，更新缓存容易遗漏。
- 删除后让下一次读重建，逻辑更简单。

边界：

- 删除缓存失败会产生旧数据，需要重试、消息补偿或短 TTL 兜底。
- 读写并发下仍可能短时间读到旧值。
- 强一致场景不要只靠缓存方案保证。

### 6.2 缓存穿透

现象：

```text
请求查询一个不存在的数据
  -> 缓存查不到
  -> 数据库也查不到
  -> 下次请求继续打数据库
```

治理：

- 参数校验，非法 ID 直接拒绝。
- 空值缓存，给不存在的数据设置短 TTL。
- 布隆过滤器，提前判断数据是否可能存在。

本项目实验：

```text
RedisCacheProtectionService#getProductWithNullCache
```

### 6.3 缓存击穿

现象：

```text
热点 key 过期
  -> 大量并发同时未命中
  -> 同时访问数据库
```

治理：

- 热点 key 使用互斥锁重建。
- 热点 key 逻辑过期，由后台异步刷新。
- 核心热点数据提前预热。

本项目实验：

```text
RedisCacheProtectionService#getHotProductWithMutex
```

### 6.4 缓存雪崩

现象：

```text
大量 key 在同一时间过期
  -> 数据库瞬间承压
```

治理：

- TTL 加随机抖动。
- 分批预热。
- 热点数据分级缓存。
- Redis 故障时业务限流和降级。

本项目实验：

```text
RedisCacheProtectionService#cacheWithRandomTtl
```

## 7. 分布式锁

Redis 分布式锁的最小正确形态：

```text
SET lockKey token NX PX ttl
执行业务
Lua 判断 token 后删除
```

为什么释放锁要用 Lua：

```text
线程 A 获得锁
线程 A 执行业务超时，锁过期
线程 B 获得同一个锁
线程 A 终于执行完，如果直接 DEL，会删掉线程 B 的锁
```

所以释放锁必须比较 token：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
return 0
```

本项目实验：

```text
RedisDistributedLockService
```

注意边界：

- 锁 TTL 要覆盖业务执行时间。
- 业务执行超过锁 TTL 时，锁已经失效。
- Redis 主从异步复制下，极端故障可能出现锁丢失。
- 分布式锁不能替代数据库唯一约束和事务。

## 8. Lua 和 Pipeline

### 8.1 Lua

Lua 适合把多个 Redis 命令合并成一个原子操作。例如库存扣减：

```text
检查库存是否存在
  -> 判断库存是否足够
  -> 扣减库存
```

如果用多个命令分开执行，并发下可能超卖。Lua 可以让这段逻辑在 Redis 内部一次执行完。

本项目实验：

```text
RedisInventoryService#deduct
```

边界：

- Lua 不要写复杂长循环。
- Lua 执行期间会阻塞 Redis 主线程。
- Cluster 下 Lua 使用的 key 必须同 slot。

### 8.2 Pipeline

Pipeline 用于减少大量命令的网络往返，不保证原子性。

适合：

- 批量写入简单 key。
- 批量读取多个互不依赖的 key。
- 数据预热。

不适合：

- 需要中间结果决定后续命令。
- 需要事务一致性的操作。
- 一次塞入过多命令，造成 Redis 输出缓冲区压力。

本项目实验：

```text
RedisPipelineService
```

## 9. 常见业务场景设计

| 场景 | 推荐结构 | 注意点 |
| --- | --- | --- |
| 对象缓存 | String + JSON | 控制 value 大小，设置 TTL |
| 计数器 | String + INCR | 首次设置过期时间 |
| 排行榜 | ZSet | 控制榜单长度，定期裁剪 |
| 限流 | String + Lua / ZSet | 明确窗口算法和失败策略 |
| 幂等 | String + SET NX | 状态要有 TTL，避免永久 PROCESSING |
| 分布式锁 | SET NX PX + Lua | token 校验释放，明确 TTL |
| 会话 | Hash / String | 不要无限期保存 |
| 附近门店 | GEO | 注意坐标和范围查询规模 |
| 消息流 | Stream | 处理 pending 消息和消费组积压 |
| 广播通知 | Pub/Sub | 不保证离线消息 |

## 10. 必做实验

| 实验 | 目标 | 代码入口 |
| --- | --- | --- |
| Java 连接单机 Redis | 掌握基础连接和序列化 | `application.yml`、`RedisConfiguration` |
| Java 连接 Sentinel | 理解客户端发现 master | 本文 Sentinel 配置 |
| Java 连接 Cluster | 理解 seed nodes 和拓扑刷新 | 本文 Cluster 配置 |
| Cache Aside | 掌握读写缓存基本模式 | `RedisCacheAsideService` |
| 缓存穿透保护 | 空值缓存 | `RedisCacheProtectionService` |
| 热点 key 互斥重建 | 防止击穿 | `RedisCacheProtectionService` |
| TTL 随机化 | 防止雪崩 | `RedisCacheProtectionService` |
| 分布式锁 | SET NX + Lua 解锁 | `RedisDistributedLockService` |
| Lua 扣减库存 | 原子判断和扣减 | `RedisInventoryService` |
| Pipeline | 批量命令减少网络往返 | `RedisPipelineService` |
| 限流 | 固定窗口 Lua 限流 | `RedisRateLimiter` |
| 幂等 | 防重复提交 | `RedisIdempotencyService` |

## 11. 过关标准

完成第 5 阶段后，你应该能回答：

1. Lettuce、Jedis、Redisson 的定位有什么区别？
2. 单机、Sentinel、Cluster 三种模式下 Java 配置有什么不同？
3. 为什么 Sentinel 环境不能写死 master IP？
4. 为什么 Cluster 环境客户端必须支持 MOVED 和 ASK？
5. 连接池过大或过小分别有什么风险？
6. Redis 命令超时时，业务应该重试、降级还是失败？
7. Cache Aside 为什么通常是“写数据库后删除缓存”？
8. 缓存穿透、击穿、雪崩分别是什么，怎么治理？
9. 为什么分布式锁释放时必须校验 token？
10. Redis 分布式锁不能解决哪些一致性问题？
11. Lua 适合解决什么问题，为什么不能写复杂脚本？
12. Pipeline 能提升什么，为什么不保证原子性？
13. Redis 作为缓存时，什么时候可以接受丢数据，什么时候不能？
14. 如何避免把 Redis 当数据库滥用？

如果这些问题能讲清楚，并且能跑通本项目的实验代码，就可以进入第 6 阶段：容量评估与性能压测。
