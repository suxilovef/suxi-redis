# Redis 集群阶段

## 1. 这份文档解决什么问题

单机阶段解决的是“一个 Redis 实例如何稳定运行”。高可用阶段解决的是“单主多从架构下，master 故障后如何自动切换”。集群阶段要解决的是另一个问题：当单个 Redis master 的内存、连接数、吞吐和单线程执行能力达到上限时，如何把数据拆分到多台 master 上。

本阶段重点理解：

- Redis Cluster 如何通过 16384 个 hash slot 完成分片。
- 客户端为什么必须使用 Cluster 模式，而不是普通单机连接或 Sentinel 模式。
- `MOVED`、`ASK`、`CROSSSLOT` 这些错误分别代表什么。
- 三主三从集群如何完成 master 故障转移。
- 扩容、缩容和迁槽为什么是 Redis Cluster 的核心运维动作。
- Cluster 能提升容量和吞吐，但不能替代数据库的一致性保障。

第三阶段建议使用 6 台虚拟机，搭建标准的三主三从 Redis Cluster。这个规模刚好能覆盖分片、复制、故障转移和迁槽实验。

## 2. 环境规划

### 2.1 节点规划

继续使用 VMware NAT 网段，但第三阶段重新规划 IP，避免和前两个阶段混用：

```text
VMware Network: VMnet8 NAT
Subnet: 192.168.88.0/24
Gateway: 192.168.88.2
DNS: 223.5.5.5, 8.8.8.8
```

六台机器规划：

| 主机名 | IP | Redis 端口 | Cluster bus 端口 | 初始角色 |
| --- | --- | --- | --- | --- |
| `redis-cluster-1` | `192.168.88.121` | `6379` | `16379` | master |
| `redis-cluster-2` | `192.168.88.122` | `6379` | `16379` | master |
| `redis-cluster-3` | `192.168.88.123` | `6379` | `16379` | master |
| `redis-cluster-4` | `192.168.88.124` | `6379` | `16379` | replica |
| `redis-cluster-5` | `192.168.88.125` | `6379` | `16379` | replica |
| `redis-cluster-6` | `192.168.88.126` | `6379` | `16379` | replica |

说明：

- `6379` 是客户端访问端口。
- `16379` 是 cluster bus 端口，默认等于 Redis 端口加 `10000`。
- Redis Cluster 不使用 Sentinel，集群内部自己完成节点发现、故障检测和 replica 提升。
- 初始主从关系由 `redis-cli --cluster create` 创建，实际配对以 `cluster nodes` 输出为准。

### 2.2 目录规划

继续复用前两阶段的安装目录和服务用户：

```text
/usr/local/redis/bin/redis-server
/usr/local/redis/bin/redis-cli

/etc/redis/redis-6379.conf
/data/redis/6379
/var/log/redis/redis-6379.log

/etc/systemd/system/redis-6379.service
```

Cluster 模式会额外生成节点状态文件：

```text
/data/redis/6379/nodes-6379.conf
```

这个文件不是手写配置文件。Redis 会自动维护本节点看到的集群拓扑、节点 ID、槽位和配置纪元。排查可以看，但不要手工编辑。

## 3. Cluster 核心认知

### 3.1 Cluster 解决什么

Redis Cluster 的核心目标是横向扩展：

- 把 key 分散到多个 master，突破单 master 内存上限。
- 把读写流量分散到多个 master，提升整体吞吐。
- 每个 master 配置 replica，master 故障后可以自动提升 replica。
- 支持在线迁槽，用于扩容、缩容和负载调整。

它不解决所有问题：

- 不保证强一致写入。
- 不支持跨 slot 的任意多 key 原子操作。
- 不负责代理流量，客户端仍然直接连接 Redis 节点。
- 迁槽期间客户端必须能处理 `ASK` 重定向。

### 3.2 16384 个 hash slot

Redis Cluster 不直接按节点数量对 key 取模，而是固定把 key 映射到 `0-16383` 共 16384 个槽：

```text
slot = CRC16(key) % 16384
```

集群再把这些槽分配给不同 master：

```text
master A: 0-5460
master B: 5461-10922
master C: 10923-16383
```

客户端写入一个 key 时，先计算 key 所属 slot，再访问负责该 slot 的 master。

查看某个 key 的槽位：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:1001
```

### 3.3 MOVED 和 ASK

普通 Redis 客户端如果访问了错误节点，会看到类似错误：

```text
MOVED 7365 192.168.88.122:6379
```

含义是：这个 key 对应的 slot 不归当前节点负责，应该去 `192.168.88.122:6379`。

`ASK` 一般出现在迁槽过程中，表示 slot 正在迁移，客户端需要临时向目标节点发送 `ASKING` 后再执行命令。工程上不要自己手写这些流程，应该使用支持 Cluster 的客户端驱动。

学习时使用 `redis-cli -c` 可以自动跟随重定向：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456
```

### 3.4 hash tag 和 CROSSSLOT

Redis Cluster 中，多 key 命令要求所有 key 落在同一个 slot。否则会报错：

```text
CROSSSLOT Keys in request don't hash to the same slot
```

例如下面两个 key 大概率不在同一个 slot：

```bash
mget user:1:name user:2:name
```

如果确实需要对多个 key 做同槽操作，可以使用 hash tag。Redis 只对 `{}` 内的内容计算槽位：

```bash
mget user:{1}:name user:{1}:age
```

工程侧常见用法：

```text
cart:{userId}:items
cart:{userId}:summary
lock:{orderId}
order:{orderId}:detail
```

不要滥用 hash tag。把大量热点 key 强行放进同一个 slot，会让分片失去意义。

### 3.5 Cluster bus 和节点发现

Redis Cluster 节点之间通过 cluster bus 通信。默认端口是 Redis 服务端口加 `10000`，本阶段就是 `16379`。

cluster bus 用于：

- 节点之间交换拓扑信息。
- 传播 slot 分配信息。
- 发送心跳，判断节点是否疑似下线。
- 广播 failover、配置纪元和主从关系变化。

所以防火墙必须同时放行 `6379` 和 `16379`。只放行 `6379` 时，客户端可能能连上 Redis，但集群节点之间无法握手。

### 3.6 故障转移

Redis Cluster 的故障转移由集群内部完成，不依赖 Sentinel。

大致流程：

```text
某个 master 不可达
  -> 其他 master 通过 cluster bus 判断 PFAIL
  -> 多个 master 确认后进入 FAIL
  -> 该 master 的 replica 发起选举
  -> 仍然存活的 master 投票
  -> 获胜 replica 提升为新 master
  -> 新 master 接管原 master 的 slot
```

注意几个边界：

- failover 需要多数 master 可用。
- 如果 master 和它的所有 replica 都不可用，对应 slot 无法服务。
- `cluster-require-full-coverage yes` 时，只要有 slot 不可用，整个集群会进入不可用状态。
- 故障转移期间会有短暂写失败，业务必须有超时、重试和降级策略。

### 3.7 Cluster 和 Sentinel 的区别

| 对比项 | Sentinel | Cluster |
| --- | --- | --- |
| 主要目标 | 单主多从自动故障转移 | 分片、扩容和集群内故障转移 |
| 数据分片 | 不支持 | 支持 16384 个 slot |
| 客户端连接 | Sentinel 模式发现 master | Cluster 模式发现 slot 拓扑 |
| 典型规模 | 1 master + 多 replica + 多 Sentinel | 多 master + 多 replica |
| 多 key 限制 | 无 slot 限制 | 多 key 必须同 slot |
| 扩容方式 | 增加 replica 主要提升读或冗余 | 增加 master 后迁槽 |

进入第三阶段后，客户端不要再使用 Sentinel 配置。Cluster 要配置 seed nodes，并开启客户端的集群拓扑刷新能力。

## 4. Cluster 配置要点

每台 Redis 都是普通 Redis 进程，只是打开了 Cluster 配置。

核心配置：

```conf
cluster-enabled yes
cluster-config-file nodes-6379.conf
cluster-node-timeout 5000
cluster-announce-ip 192.168.88.121
cluster-announce-port 6379
cluster-announce-bus-port 16379
cluster-require-full-coverage yes
```

说明：

| 配置 | 作用 |
| --- | --- |
| `cluster-enabled yes` | 开启 Cluster 模式 |
| `cluster-config-file` | Redis 自动维护的集群节点状态文件 |
| `cluster-node-timeout` | 节点超时判断时间，影响故障检测速度 |
| `cluster-announce-ip` | 当前节点对外通告的 IP，VMware 环境必须写本机静态 IP |
| `cluster-announce-port` | 当前节点对外通告的 Redis 服务端口 |
| `cluster-announce-bus-port` | 当前节点对外通告的 cluster bus 端口 |
| `cluster-require-full-coverage` | 有 slot 不可用时是否让整个集群停止服务 |

不要在 Cluster 配置里手工写 `replicaof`。Cluster 的主从关系由集群创建命令和后续 failover 维护。

密码配置仍然要保留：

```conf
requirepass Redis@123456
masterauth Redis@123456
```

`requirepass` 用于客户端访问当前节点，`masterauth` 用于当前节点作为 replica 连接 master。

## 5. 集群创建流程

Redis Cluster 的创建分两步：

1. 先启动 6 个开启 Cluster 模式的空 Redis 节点。
2. 再用 `redis-cli --cluster create` 分配 slot 和 replica。

创建命令示例：

```bash
redis-cli --cluster create \
  192.168.88.121:6379 \
  192.168.88.122:6379 \
  192.168.88.123:6379 \
  192.168.88.124:6379 \
  192.168.88.125:6379 \
  192.168.88.126:6379 \
  --cluster-replicas 1 \
  -a Redis@123456
```

`--cluster-replicas 1` 表示每个 master 配 1 个 replica。6 个节点会形成 3 个 master 和 3 个 replica。

创建后验证：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

正常状态应看到：

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
```

## 6. 必做实验

### 6.1 key 分片实验

目标：理解 key 如何分配到不同 slot。

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 set user:1 tom
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 set user:2 jerry
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:1
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:2
```

再查看 slot 属于哪个 master：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
```

### 6.2 MOVED 重定向实验

不用 `-c` 连接任意节点：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 get user:2
```

如果 `user:2` 不属于 `redis-cluster-1`，会看到 `MOVED`。再使用 `-c`：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 get user:2
```

`redis-cli -c` 会自动跳转到正确节点。

### 6.3 CROSSSLOT 和 hash tag 实验

先执行可能失败的多 key 命令：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mset user:1:name tom user:2:name jerry
```

如果两个 key 不在同一个 slot，会返回 `CROSSSLOT`。

再用 hash tag：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mset user:{1}:name tom user:{1}:age 18
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mget user:{1}:name user:{1}:age
```

这两个 key 都使用 `{1}` 计算槽位，所以可以被同一个多 key 命令处理。

### 6.4 master 故障转移实验

先确认当前 master 和 replica：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

选择一个 master 停止 Redis，例如在 `redis-cluster-1` 上执行：

```bash
sudo systemctl stop redis-6379
```

在其他节点观察：

```bash
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster nodes
```

等待 failover 完成后，应看到原 master 的某个 replica 被提升为 master，并接管原来的 slot。

恢复旧 master：

```bash
sudo systemctl start redis-6379
```

旧 master 正常情况下会作为 replica 回到集群。

### 6.5 手动 failover 实验

手动 failover 要在某个 replica 上执行：

```bash
redis-cli -h 192.168.88.124 -p 6379 -a Redis@123456 cluster failover
```

执行前先用 `cluster nodes` 确认 `192.168.88.124` 当前确实是 replica。这个实验适合模拟维护切换。

### 6.6 迁槽实验

迁槽是 Cluster 运维的核心能力。

先查看节点 ID：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

执行交互式迁槽：

```bash
redis-cli --cluster reshard 192.168.88.121:6379 -a Redis@123456
```

学习时可以迁移少量 slot，例如 `100` 个。迁槽完成后检查：

```bash
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
```

## 7. 常用排查命令

### 7.1 集群状态

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

重点看：

| 指标 | 说明 |
| --- | --- |
| `cluster_state` | 集群整体是否 `ok` |
| `cluster_slots_assigned` | 是否分配了 16384 个 slot |
| `cluster_slots_fail` | 是否存在不可用 slot |
| `myself,master` | 当前节点是否为 master |
| `slave` / `replica` | 当前节点是否为 replica，命令输出可能仍使用 `slave` |
| `fail?` / `fail` | 节点是否疑似故障或已确认故障 |

### 7.2 节点日志

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
```

故障转移时重点观察：

```text
FAIL
Failover election
configEpoch
Start of election delayed
Failover auth granted
```

### 7.3 网络和端口

```bash
sudo ss -lntp | grep -E '6379|16379'
sudo ufw status
ping -c 3 redis-cluster-1
ping -c 3 redis-cluster-2
```

如果 `6379` 正常但 `16379` 不通，集群会无法稳定握手。

## 8. 常见问题

### 8.1 一直 Waiting for the cluster to join

常见原因：

- `16379` cluster bus 端口没有放行。
- `cluster-announce-ip` 写成了 `127.0.0.1` 或错误 IP。
- 多台克隆机的 IP、hostname、machine-id 或 MAC 地址重复。
- 某些 Redis 节点没有真正开启 `cluster-enabled yes`。

排查：

```bash
sudo ss -lntp | grep -E '6379|16379'
grep cluster-announce-ip /etc/redis/redis-6379.conf
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
```

### 8.2 连接时报 MOVED

原因通常是客户端没有开启 Cluster 模式。

学习环境使用：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456
```

Java 工程要使用 Lettuce、Redisson 或 Jedis 的 Cluster 配置，不能按单机 Redis 客户端连接。

### 8.3 多 key 命令报 CROSSSLOT

原因是多个 key 不在同一个 slot。

处理方式：

- 避免跨 slot 多 key 命令。
- 使用 hash tag 让强相关 key 落到同一个 slot。
- 重新设计数据结构，把原子性要求放回数据库或单 key Lua 逻辑中。

### 8.4 CLUSTERDOWN Hash slot not served

常见原因：

- 有 master 和它的 replica 都不可用。
- 集群创建没有成功，16384 个 slot 没有全部分配。
- 迁槽中断导致 slot 处于异常状态。

排查：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

### 8.5 创建集群时报 Node is not empty

Redis Cluster 创建要求节点是空节点。常见原因是节点里已经有数据，或者残留旧的 `nodes-6379.conf`。

学习环境可以清理后重建。正式环境不要直接清理，应先确认数据归属和迁移方案。

## 9. Java 客户端接入要点

Java 工程连接 Redis Cluster 时要关注：

| 项目 | 要点 |
| --- | --- |
| 连接模式 | 使用 Cluster 模式，不使用 Sentinel 模式 |
| seed nodes | 配置多个节点地址，例如 `192.168.88.121:6379` 到 `192.168.88.126:6379` |
| 密码 | 所有节点密码保持一致 |
| 拓扑刷新 | 开启定期刷新和 MOVED/ASK 触发刷新 |
| 超时和重试 | failover 和迁槽期间要允许短暂失败 |
| 多 key 命令 | 同一命令里的 key 必须同 slot |
| hash tag | 只给确实需要同槽的业务 key 使用 |
| 读 replica | 可以提升读吞吐，但要接受复制延迟 |

工程上不要把所有 key 都包成同一个 hash tag。这样会把流量打到一个 slot，等于绕开了 Cluster 分片能力。

## 10. 本阶段过关标准

完成集群阶段后，你应该能做到：

1. 能独立部署 6 台 Redis Cluster 节点。
2. 能解释 `6379` 和 `16379` 两个端口分别做什么。
3. 能解释 16384 个 slot 和 key 分片关系。
4. 能用 `redis-cli --cluster create` 创建三主三从集群。
5. 能用 `cluster info`、`cluster nodes`、`cluster slots` 判断集群健康状态。
6. 能解释 `MOVED`、`ASK` 和 `CROSSSLOT`。
7. 能使用 hash tag 让多个相关 key 落到同一个 slot。
8. 能停止一个 master 并观察 replica 自动提升。
9. 能执行一次少量 slot 的 reshard，并解释迁槽期间客户端为什么要处理 `ASK`。
10. 能说清楚 Java 客户端连接 Cluster 和连接 Sentinel 的区别。

如果这些问题都能讲清楚，并且能独立完成创建集群、写入验证、故障转移和迁槽实验，就可以继续进入 Redis 运维治理、容量评估、性能压测和生产实践阶段。
