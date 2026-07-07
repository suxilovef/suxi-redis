---
title: Redis七阶段学习路线
date: 2026-07-06 20:07:05
description: Redis七阶段学习路线
tags:
  - Redis
categories:
  - Redis
realm: wujing
cover: /image/post_cover/redis-7stage-roadmap-min.svg
rank: 95
top_img: false
---
# Redis 七阶段学习路线

## 1. 路线定位

这份文档用于记录 Redis 从入门部署到生产实践、源码理解的完整学习路线。后续学习建议按阶段推进，不要跳着学。

学习目标不是“会敲 Redis 命令”，而是达到下面几个能力：

- 能独立部署 Redis 单机、Sentinel 和 Cluster。
- 能解释 Redis 核心机制，而不是只背配置。
- 能做故障演练，并根据日志和命令定位问题。
- 能把 Redis 正确接入 Java 业务系统。
- 能做容量评估、性能压测和生产运维方案。
- 能读懂关键源码机制，理解 Redis 为什么这样设计。

## 2. 总体阶段

| 阶段 | 名称 | 核心目标 | 完成后的水平 |
| --- | --- | --- | --- |
| 第 1 阶段 | 单机认知 | 理解 Redis 单实例部署、配置、持久化、内存和性能基础 | 会部署、会配置、会基础排查 |
| 第 2 阶段 | 高可用阶段 | 理解主从复制、Sentinel、自动故障转移 | 能搭建高可用 Redis |
| 第 3 阶段 | 集群阶段 | 理解 Redis Cluster、分片、slot、故障转移、迁槽 | 能搭建三主三从集群 |
| 第 4 阶段 | 线上问题排查与运维治理 | 掌握 big key、hot key、慢查询、延迟、内存、连接等生产问题 | 能守 Redis |
| 第 5 阶段 | Java 工程接入与缓存架构 | 掌握客户端配置、缓存一致性、分布式锁、缓存问题治理 | 能把 Redis 用对 |
| 第 6 阶段 | 容量评估与性能压测 | 掌握容量规划、压测、扩缩容、资源评估 | 能做 Redis 技术方案 |
| 第 7 阶段 | 源码与底层机制 | 理解 Redis 内部数据结构、事件模型、持久化、复制、Cluster 实现 | 接近专家级理解 |

完成前三个阶段后，可以认为具备 Redis 中级偏上的部署和架构基础。真正走向资深，需要继续补第 4 到第 7 阶段。

## 3. 第 1 阶段：单机认知

对应目录：

```text
docs/01-单机认知/
```

核心目标：

- 理解 Redis 是内存数据库。
- 掌握 Redis 单机部署、配置文件、日志、数据目录。
- 理解 RDB、AOF、混合持久化。
- 理解过期删除、内存淘汰、慢查询、大 key、热 key 的基础概念。
- 能使用 `redis-cli`、`info`、`slowlog`、`memory` 等命令排查基础问题。

必须掌握：

- `redis.conf` 关键配置。
- `requirepass`、`bind`、`protected-mode`。
- RDB 和 AOF 的区别。
- `maxmemory` 和 `maxmemory-policy`。
- Redis 为什么快，什么时候会慢。
- 单机 Redis 的容量和性能边界。

必做实验：

- 单机源码安装 Redis。
- 使用 systemd 管理 Redis。
- 开启 RDB 和 AOF，验证数据恢复。
- 制造慢查询并用 `slowlog` 查看。
- 制造大 key 并观察内存变化。
- 从宿主机连接虚拟机 Redis。

过关标准：

- 能独立部署一台 Redis。
- 能解释 `/etc/redis`、`/data/redis`、`/var/log/redis` 的作用。
- 能说明 RDB、AOF 的优缺点。
- 能排查 Redis 启动失败和远程连接失败。

## 4. 第 2 阶段：高可用阶段

对应目录：

```text
docs/02-高可用阶段/
```

核心目标：

- 理解主从复制。
- 理解 Sentinel 的故障检测和自动切换。
- 理解 Redis 高可用能降低不可用时间，但不能保证零丢失。
- 掌握客户端为什么要连接 Sentinel，而不是写死 master IP。

必须掌握：

- `replicaof` 和 `masterauth`。
- 全量复制和部分复制。
- 复制延迟和数据丢失窗口。
- Sentinel 的 S_DOWN、O_DOWN、quorum、多数派。
- failover 期间业务为什么会短暂失败。

必做实验：

- 搭建 1 master + 2 replica。
- 搭建 3 个 Sentinel。
- 停止 master，观察 Sentinel 自动切换。
- 恢复旧 master，观察它重新变成 replica。
- 使用 `sentinel get-master-addr-by-name` 查询当前 master。

过关标准：

- 能独立部署 Redis Sentinel 高可用环境。
- 能解释主从复制和 Sentinel 的职责边界。
- 能通过日志说明一次 failover 的完整过程。
- 能说明 Java 客户端为什么不能写死 Redis master IP。

## 5. 第 3 阶段：集群阶段

对应目录：

```text
docs/03-集群阶段/
```

核心目标：

- 理解 Redis Cluster 的分片机制。
- 掌握 16384 个 slot 和 key 映射关系。
- 理解 MOVED、ASK、CROSSSLOT。
- 掌握三主三从集群部署。
- 掌握 master 故障转移和迁槽。

必须掌握：

- `cluster-enabled yes`。
- `cluster-announce-ip`。
- `6379` 和 `16379` 的区别。
- `redis-cli --cluster create`。
- `cluster info`、`cluster nodes`、`cluster slots`。
- hash tag 的正确使用方式。

必做实验：

- 使用 6 台机器搭建三主三从集群。
- 写入 key，观察 slot 分布。
- 不加 `-c` 观察 MOVED。
- 制造 CROSSSLOT，并用 hash tag 解决。
- 停止一个 master，观察 replica 自动提升。
- 执行少量 slot 迁移。

过关标准：

- 能独立部署 Redis Cluster。
- 能解释 Cluster 和 Sentinel 的区别。
- 能说明客户端为什么必须使用 Cluster 模式。
- 能完成故障转移和迁槽实验。

## 6. 第 4 阶段：线上问题排查与运维治理

建议目录：

```text
docs/04-线上问题排查与运维治理/
```

核心目标：

- 从“会搭 Redis”进入“能守 Redis”。
- 掌握生产 Redis 常见问题的发现、定位和处理。
- 建立监控、告警、巡检、备份、恢复和故障演练意识。

必须掌握：

- big key 发现与治理。
- hot key 发现与治理。
- 慢查询和阻塞命令排查。
- 延迟抖动分析。
- 内存打满、淘汰、碎片率分析。
- AOF rewrite、RDB fork、COW 对性能的影响。
- 连接数暴涨和客户端超时排查。
- Redis 监控指标和告警规则。

必做实验：

- 制造 big key，并用 `--bigkeys`、`memory usage` 分析。
- 制造 hot key，观察单节点压力。
- 制造慢查询并分析 `slowlog`。
- 使用 `latency doctor` 分析延迟。
- 配置 maxmemory，观察淘汰策略。
- 模拟连接数暴涨。
- 制定一份 Redis 巡检清单。

过关标准：

- 能定位 Redis 慢是服务端问题、网络问题还是客户端问题。
- 能处理 big key、hot key、内存打满、连接数异常。
- 能设计基本监控告警项。
- 能写出 Redis 故障排查 SOP。

## 7. 第 5 阶段：Java 工程接入与缓存架构

建议目录：

```text
docs/05-Java工程接入与缓存架构/
```

核心目标：

- 从“Redis 会部署”进入“Redis 能被业务正确使用”。
- 掌握 Java 客户端配置、连接池、超时、重试和拓扑刷新。
- 掌握缓存架构中的典型问题和解决方案。

必须掌握：

- Lettuce、Redisson、Jedis 的定位和差异。
- 单机、Sentinel、Cluster 三种连接模式。
- 连接池、超时、重试、读写分离。
- 缓存穿透、击穿、雪崩。
- 缓存一致性和 Cache Aside。
- 分布式锁的正确使用边界。
- Lua 脚本和 Pipeline。
- Redis 在排行榜、计数器、限流、会话、幂等等场景中的设计方式。

必做实验：

- Java 连接单机 Redis。
- Java 连接 Sentinel。
- Java 连接 Cluster。
- 模拟 Redis failover，观察客户端恢复过程。
- 实现缓存穿透保护。
- 实现热点 key 互斥重建。
- 实现一个 Redisson 分布式锁案例。
- 实现 Lua 原子扣减库存实验。

过关标准：

- 能正确配置 Java Redis 客户端。
- 能解释连接池过大或过小的风险。
- 能设计缓存一致性方案。
- 能说明分布式锁不能解决哪些问题。
- 能避免把 Redis 当数据库滥用。

## 8. 第 6 阶段：容量评估与性能压测

建议目录：

```text
docs/06-容量评估与性能压测/
```

核心目标：

- 从“会用 Redis”进入“能做 Redis 技术方案”。
- 掌握容量规划、节点规划、压测方法和扩缩容策略。

必须掌握：

- key 数量和 value 大小估算。
- 内存容量、安全水位和增长趋势。
- QPS、RT、P95、P99、带宽、CPU 的关系。
- `redis-benchmark` 的使用边界。
- Pipeline 对吞吐和延迟的影响。
- Cluster 节点数规划。
- replica 数量规划。
- 持久化策略对性能的影响。
- 扩容、缩容、迁槽和数据迁移。

必做实验：

- 用 `redis-benchmark` 压测不同 value 大小。
- 对比普通写入和 Pipeline 写入。
- 观察 CPU、内存、网络带宽变化。
- 估算 1000 万 key 的内存占用。
- 给一个业务场景设计 Redis 节点规模。
- 模拟 Cluster 扩容和迁槽。

过关标准：

- 能根据业务 QPS 和数据量估算 Redis 资源。
- 能设计 Redis Cluster 节点规模。
- 能说明压测数据和真实业务流量的差距。
- 能写出 Redis 容量评估文档。

## 9. 第 7 阶段：源码与底层机制

建议目录：

```text
docs/07-源码与底层机制/
```

核心目标：

- 从“会部署、会排查、会设计”进入“理解 Redis 为什么这样实现”。
- 重点看核心路径，不追求一开始读完整个源码。

必须掌握：

- SDS。
- dict。
- listpack。
- skiplist。
- quicklist。
- 事件循环。
- IO 多路复用。
- 命令执行流程。
- RDB 和 AOF 实现。
- AOF rewrite。
- 主从复制 PSYNC。
- Sentinel 故障判断。
- Cluster gossip、slot、failover。
- 过期删除和内存淘汰。
- lazyfree。

建议阅读顺序：

```text
数据结构
  -> 命令执行流程
  -> 网络事件模型
  -> 持久化
  -> 复制
  -> Sentinel
  -> Cluster
  -> 内存管理和淘汰
```

必做实验：

- 对照源码理解 String、Hash、ZSet 的底层结构。
- 跟踪一次 Redis 命令执行流程。
- 跟踪一次 RDB 生成流程。
- 跟踪一次 AOF rewrite 流程。
- 跟踪一次主从复制握手流程。
- 跟踪一次 Cluster MOVED 返回流程。

过关标准：

- 能从源码角度解释 Redis 为什么快。
- 能解释 Redis 单线程模型的真实含义。
- 能解释 RDB、AOF、复制、Cluster 的核心实现流程。
- 遇到复杂问题时，知道应该去源码哪个模块找答案。

## 10. 推荐学习顺序

建议严格按下面顺序推进：

```text
01 单机认知
  -> 02 高可用阶段
  -> 03 集群阶段
  -> 04 线上问题排查与运维治理
  -> 05 Java 工程接入与缓存架构
  -> 06 容量评估与性能压测
  -> 07 源码与底层机制
```

不要过早进入源码。前三阶段解决部署和架构基础，第四到第六阶段解决生产实践，第七阶段再回到源码，理解会更稳。

## 11. 最终能力定位

如果只完成前三阶段：

```text
Redis 中级偏上
具备部署、基础架构和实验环境排查能力
```

如果完成前五阶段：

```text
Redis 高级应用能力
能在 Java 业务系统中正确设计和使用 Redis
```

如果完成前六阶段：

```text
Redis 生产方案能力
能做容量评估、压测、扩缩容和生产治理
```

如果完成全部七阶段：

```text
Redis 高级工程师到专家入门水平
具备部署、排查、架构、容量、客户端和源码综合能力
```

真正的专家水平还需要持续积累线上故障经验、业务架构经验和源码级问题定位经验。
