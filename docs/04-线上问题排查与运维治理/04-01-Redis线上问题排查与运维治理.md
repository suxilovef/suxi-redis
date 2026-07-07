---
title: 04-01-Redis-线上问题排查与运维治理
date: 2026-07-06 20:07:05
description: Redis-线上问题排查与运维治理
tags:
  - Redis
categories:
  - Redis
realm: wujing
cover: /image/post_cover/redis-ops-troubleshoot-min.svg
rank: 75
top_img: false
---
# Redis 线上问题排查与运维治理

## 1. 这份文档解决什么问题

前三个阶段已经解决了 Redis 单机、Sentinel 高可用和 Cluster 集群的部署与基础架构问题。进入第 4 阶段后，学习重点要从“会搭 Redis”转向“能守 Redis”。

线上 Redis 的难点通常不是某个命令不会用，而是出现问题时能不能快速判断：

- 是 Redis 服务端真的慢，还是客户端线程池、连接池、网络链路慢。
- 是单个 key 太大，还是某个 key 被集中访问。
- 是命令本身慢，还是 fork、AOF rewrite、内存回收、系统抖动导致的延迟。
- 是 Redis 内存不足，还是配置的淘汰策略和业务预期不一致。
- 是连接数暴涨，还是客户端没有正确复用连接。
- 是集群某个节点有热点，还是整体容量规划不够。

本阶段的目标不是背命令，而是形成一套稳定的排查路径：

```text
发现现象
  -> 判断影响范围
  -> 定位问题方向
  -> 使用命令验证
  -> 临时止血
  -> 根因治理
  -> 复盘并补监控
```

完成本阶段后，你应该能写出 Redis 故障排查 SOP，并能独立处理 big key、hot key、慢查询、延迟抖动、内存打满、连接数异常等常见生产问题。

## 2. 环境建议

第 4 阶段可以复用前面任意一种 Redis 环境：

| 环境 | 适合内容 |
| --- | --- |
| 单机 Redis | big key、慢查询、延迟、内存淘汰、连接数实验 |
| Sentinel 环境 | 主从复制延迟、failover 后客户端问题 |
| Cluster 环境 | 热点节点、slot 倾斜、集群节点局部故障 |

如果只是学习排查方法，建议先使用单机 Redis，避免 Cluster 拓扑增加干扰。等单机排查路径熟练后，再放到 Cluster 环境中观察节点差异。

本文示例沿用前面阶段的基础约定：

```text
Redis 端口: 6379
Redis 密码: Redis@123456
Redis 配置: /etc/redis/redis-6379.conf
Redis 日志: /var/log/redis/redis-6379.log
Redis 数据目录: /data/redis/6379
systemd 服务: redis-6379
```

连接示例：

```bash
redis-cli -h 127.0.0.1 -p 6379 -a Redis@123456
```

如果是 Cluster 环境，客户端命令需要增加 `-c`：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456
```

部分实验会用 Python 批量写入测试数据。如果虚拟机没有安装 Redis Python 客户端，可以先准备：

```bash
sudo apt update
sudo apt install -y python3-pip
python3 -m pip install redis
```

如果系统提示不允许直接安装到全局 Python 环境，可以创建虚拟环境后再安装，或者改用 `redis-cli` 循环写入。

## 3. 线上排查总方法

### 3.1 先判断是不是 Redis 问题

业务反馈“Redis 慢”时，不要直接进入 Redis 服务器改配置。第一步要先确认慢在哪里。

常见可能性：

| 位置 | 典型原因 |
| --- | --- |
| 业务代码 | 线程池耗尽、同步等待、序列化慢、批量调用方式错误 |
| 客户端连接池 | 连接池太小、连接泄漏、频繁创建短连接 |
| 网络 | 跨机房访问、丢包、DNS、NAT、负载均衡异常 |
| Redis 服务端 | 慢命令、big key、hot key、内存、fork、持久化、连接数 |
| 操作系统 | CPU 抢占、内存压力、磁盘 IO、透明大页、文件描述符限制 |

基础判断命令：

```bash
redis-cli -a Redis@123456 ping
redis-cli -a Redis@123456 info server
redis-cli -a Redis@123456 info clients
redis-cli -a Redis@123456 info stats
redis-cli -a Redis@123456 info commandstats
redis-cli -a Redis@123456 info memory
redis-cli -a Redis@123456 info persistence
redis-cli -a Redis@123456 slowlog get 10
redis-cli -a Redis@123456 latency latest
```

系统侧同步观察：

```bash
top
free -h
df -h
vmstat 1
iostat -x 1
ss -antp | grep 6379
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
```

### 3.2 推荐排查顺序

生产环境中建议按下面顺序排查：

```text
1. 是否只有 Redis 慢，还是整个业务链路都慢
2. Redis ping 延迟是否异常
3. Redis CPU、内存、连接数是否异常
4. slowlog 是否出现慢命令
5. latency 是否记录延迟事件
6. 是否存在 big key 或 hot key
7. 是否正在 RDB、AOF rewrite、全量复制
8. 客户端连接池、超时配置、重试策略是否异常
9. Cluster 环境是否只有个别节点异常
10. 是否需要限流、降级、扩容或迁移热点
```

不要一上来就重启 Redis。重启可能暂时缓解连接和内存问题，但也可能触发数据加载、主从切换、缓存雪崩和业务超时。

## 4. big key 排查与治理

### 4.1 big key 是什么

big key 不是指 key 名字长，而是 value 太大，或者集合元素太多。

常见例子：

| 类型 | big key 示例 |
| --- | --- |
| string | 单个 value 几 MB 到几十 MB |
| hash | 一个 hash 中有几十万 field |
| list | 一个 list 中堆积几十万元素 |
| set | 一个 set 中有大量 member |
| zset | 一个 zset 中有大量 member |

big key 的风险：

- 读写耗时变长，阻塞 Redis 主线程。
- 删除时可能造成明显延迟。
- 主从复制和 AOF rewrite 成本变高。
- Cluster 迁槽时迁移成本变高。
- 客户端一次拉取大量数据，造成网络和内存压力。

### 4.2 发现 big key

快速扫描：

```bash
redis-cli -a Redis@123456 --bigkeys
```

查看单个 key 内存：

```bash
redis-cli -a Redis@123456 memory usage user:1001
```

查看集合长度：

```bash
redis-cli -a Redis@123456 strlen big:string:key
redis-cli -a Redis@123456 hlen big:hash:key
redis-cli -a Redis@123456 llen big:list:key
redis-cli -a Redis@123456 scard big:set:key
redis-cli -a Redis@123456 zcard big:zset:key
```

抽样扫描：

```bash
redis-cli -a Redis@123456 --scan --pattern "user:*" | head -n 100
```

注意：不要在线上使用 `KEYS *` 查找 big key。`KEYS` 会遍历整个 keyspace，在数据量大时会阻塞 Redis。

### 4.3 big key 实验

制造一个较大的 string：

```bash
python3 - <<'PY'
import redis
r = redis.Redis(host='127.0.0.1', port=6379, password='Redis@123456', decode_responses=False)
r.set('lab:big:string', b'a' * 1024 * 1024 * 10)
print(r.memory_usage('lab:big:string'))
PY
```

制造一个较大的 hash：

```bash
python3 - <<'PY'
import redis
r = redis.Redis(host='127.0.0.1', port=6379, password='Redis@123456', decode_responses=True)
pipe = r.pipeline()
for i in range(100000):
    pipe.hset('lab:big:hash', f'f{i}', f'v{i}')
    if i % 1000 == 0:
        pipe.execute()
pipe.execute()
print(r.hlen('lab:big:hash'))
print(r.memory_usage('lab:big:hash'))
PY
```

分析：

```bash
redis-cli -a Redis@123456 --bigkeys
redis-cli -a Redis@123456 memory usage lab:big:string
redis-cli -a Redis@123456 hlen lab:big:hash
redis-cli -a Redis@123456 memory usage lab:big:hash
```

清理实验数据：

```bash
redis-cli -a Redis@123456 unlink lab:big:string
redis-cli -a Redis@123456 unlink lab:big:hash
```

### 4.4 big key 治理

治理思路：

| 问题 | 处理方式 |
| --- | --- |
| 单个 value 太大 | 拆分为多个小 key |
| hash/list/set/zset 元素太多 | 按业务维度分片 |
| 一次读取太多 | 分页、游标、批量大小限制 |
| 删除阻塞 | 使用 `UNLINK` 替代 `DEL` |
| Cluster 迁槽慢 | 先治理 big key，再做迁槽 |

示例拆分：

```text
原始 key:
user:profile:all

拆分后:
user:profile:1001
user:profile:1002
user:profile:1003
```

对于集合类数据，可以按时间、用户 ID、业务分区拆分：

```text
order:list:2026-07-05
order:list:2026-07-06

user:{1001}:messages:0
user:{1001}:messages:1
```

## 5. hot key 排查与治理

### 5.1 hot key 是什么

hot key 指某个 key 被大量请求集中访问。它不一定大，但访问频率极高。

典型场景：

- 秒杀商品库存。
- 热门文章、热门视频、热门商品详情。
- 全局配置 key。
- 首页推荐结果。
- 某个接口把所有请求都打到同一个计数 key。

hot key 的风险：

- 单个 Redis 实例 CPU 飙高。
- Cluster 中某个 master 负载远高于其他节点。
- 客户端超时、连接池排队。
- 业务重试进一步放大流量。

### 5.2 发现 hot key

Redis 4.0+ 在 LFU 淘汰策略下可以使用：

```bash
redis-cli -a Redis@123456 --hotkeys
```

使用前需要配置近似 LFU 策略，例如：

```conf
maxmemory-policy allkeys-lfu
```

查看命令统计：

```bash
redis-cli -a Redis@123456 info commandstats
```

观察整体吞吐：

```bash
redis-cli -a Redis@123456 info stats | grep instantaneous
```

使用 `MONITOR` 可以看到实时命令，但生产环境要谨慎：

```bash
redis-cli -a Redis@123456 monitor
```

`MONITOR` 会显著增加 Redis 输出压力，只适合短时间、低峰期、明确授权后使用。

Cluster 环境中要逐个节点看：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 info stats
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 info stats
redis-cli -h 192.168.88.123 -p 6379 -a Redis@123456 info stats
```

如果只有某个 master 的 QPS、CPU、网络明显偏高，很可能存在热点 key 或 slot 倾斜。

### 5.3 hot key 实验

准备 key：

```bash
redis-cli -a Redis@123456 set lab:hot:key 1
```

使用 `redis-benchmark` 模拟大量访问：

```bash
redis-benchmark -h 127.0.0.1 -p 6379 -a Redis@123456 -n 100000 -c 100 get lab:hot:key
```

同时观察：

```bash
redis-cli -a Redis@123456 info stats | grep instantaneous
redis-cli -a Redis@123456 info commandstats | grep cmdstat_get
top -p $(pidof redis-server)
```

### 5.4 hot key 治理

常见治理方式：

| 场景 | 治理方式 |
| --- | --- |
| 读热点 | 本地缓存、多级缓存、短 TTL |
| 单 key 读压力过大 | key 副本拆分，例如 `hot:key:0` 到 `hot:key:9` |
| 写热点 | 合并写、异步写、分片计数 |
| Cluster 单 slot 热 | hash tag 设计调整，避免所有热点落同槽 |
| 秒杀库存 | 预扣、限流、队列化、Lua 控制原子逻辑 |

读热点副本拆分示例：

```text
hot:product:1001:0
hot:product:1001:1
hot:product:1001:2
...
hot:product:1001:9
```

客户端随机读其中一个副本。更新时需要同步更新多个副本，或者允许短时间不一致。

计数类写热点可以拆分：

```text
counter:article:1001:0
counter:article:1001:1
counter:article:1001:2
```

读取总数时再汇总多个分片。

## 6. 慢查询和阻塞命令

### 6.1 slowlog 的作用

`SLOWLOG` 记录的是 Redis 命令执行耗时，不包含网络传输时间和客户端排队时间。

查看配置：

```bash
redis-cli -a Redis@123456 config get slowlog-log-slower-than
redis-cli -a Redis@123456 config get slowlog-max-len
```

学习环境可以临时降低阈值：

```bash
redis-cli -a Redis@123456 config set slowlog-log-slower-than 10000
redis-cli -a Redis@123456 config set slowlog-max-len 256
```

含义：

| 配置 | 说明 |
| --- | --- |
| `slowlog-log-slower-than` | 超过多少微秒记录慢查询 |
| `slowlog-max-len` | 最多保留多少条慢查询 |

查看慢查询：

```bash
redis-cli -a Redis@123456 slowlog len
redis-cli -a Redis@123456 slowlog get 10
```

清理慢查询：

```bash
redis-cli -a Redis@123456 slowlog reset
```

### 6.2 常见慢命令

高风险命令：

| 命令 | 风险 |
| --- | --- |
| `KEYS` | 全量遍历 keyspace |
| `HGETALL` | hash 很大时返回大量 field |
| `LRANGE key 0 -1` | list 很大时返回全部元素 |
| `SMEMBERS` | set 很大时返回全部 member |
| `ZRANGE` 大范围 | zset 很大时返回大量 member |
| `SORT` | CPU 和内存开销较高 |
| `DEL` big key | 同步释放大对象可能阻塞 |
| 复杂 Lua | Lua 脚本执行期间阻塞主线程 |

替代方式：

| 慢命令 | 替代方式 |
| --- | --- |
| `KEYS` | `SCAN` |
| `HGETALL` | `HSCAN` 或按 field 精确获取 |
| `SMEMBERS` | `SSCAN` |
| `LRANGE 0 -1` | 分页 `LRANGE start stop` |
| `DEL bigkey` | `UNLINK bigkey` |

### 6.3 慢查询实验

制造大量 key：

```bash
python3 - <<'PY'
import redis
r = redis.Redis(host='127.0.0.1', port=6379, password='Redis@123456', decode_responses=True)
pipe = r.pipeline()
for i in range(200000):
    pipe.set(f'lab:scan:{i}', i)
    if i % 1000 == 0:
        pipe.execute()
pipe.execute()
PY
```

降低 slowlog 阈值：

```bash
redis-cli -a Redis@123456 config set slowlog-log-slower-than 1000
redis-cli -a Redis@123456 slowlog reset
```

执行高风险命令：

```bash
redis-cli -a Redis@123456 keys "lab:scan:*" > /dev/null
redis-cli -a Redis@123456 slowlog get 5
```

使用 `SCAN` 替代：

```bash
redis-cli -a Redis@123456 --scan --pattern "lab:scan:*" | head
```

清理：

```bash
redis-cli -a Redis@123456 --scan --pattern "lab:scan:*" | xargs -r -n 100 redis-cli -a Redis@123456 unlink
```

生产环境清理大量 key 时，不要一次性 `xargs` 打爆 Redis，应该控制批次和速率。

## 7. 延迟抖动分析

### 7.1 延迟来源

Redis 延迟可能来自：

- 慢命令阻塞主线程。
- big key 删除或读取。
- RDB `BGSAVE` fork。
- AOF rewrite fork 和磁盘写入。
- 内存达到上限后频繁淘汰。
- Linux 内存压力和 swap。
- Transparent Huge Pages。
- 客户端连接数过多。
- 网络抖动或跨机房访问。

### 7.2 latency 命令

查看最新延迟事件：

```bash
redis-cli -a Redis@123456 latency latest
```

查看诊断建议：

```bash
redis-cli -a Redis@123456 latency doctor
```

查看某类事件历史：

```bash
redis-cli -a Redis@123456 latency history command
redis-cli -a Redis@123456 latency history fork
```

重置延迟统计：

```bash
redis-cli -a Redis@123456 latency reset
```

学习环境可以打开延迟监控：

```bash
redis-cli -a Redis@123456 config set latency-monitor-threshold 10
```

含义：超过 10 毫秒的延迟事件会被记录。生产环境阈值要结合业务 SLA 设置。

### 7.3 延迟排查路径

```text
业务超时
  -> redis-cli ping 是否慢
  -> slowlog 是否有慢命令
  -> latency latest 是否有事件
  -> info persistence 是否正在 fork 或 rewrite
  -> info memory 是否内存紧张
  -> info clients 是否连接异常
  -> 系统 CPU、内存、磁盘 IO 是否异常
```

常用命令：

```bash
redis-cli -a Redis@123456 --latency
redis-cli -a Redis@123456 --latency-history
redis-cli -a Redis@123456 --intrinsic-latency 100
```

说明：

| 命令 | 作用 |
| --- | --- |
| `--latency` | 测 Redis 请求响应延迟 |
| `--latency-history` | 持续输出延迟变化 |
| `--intrinsic-latency` | 测 Redis 所在机器的内在调度延迟 |

`--intrinsic-latency` 不经过网络，主要用于判断操作系统调度是否本身就有抖动。

## 8. 内存打满、淘汰和碎片率

### 8.1 关键内存指标

查看内存：

```bash
redis-cli -a Redis@123456 info memory
```

重点指标：

| 指标 | 含义 |
| --- | --- |
| `used_memory_human` | Redis 分配器使用的内存 |
| `used_memory_rss_human` | 操作系统看到的 Redis 常驻内存 |
| `maxmemory_human` | Redis 配置的最大内存 |
| `mem_fragmentation_ratio` | 内存碎片率 |
| `evicted_keys` | 被淘汰的 key 数量 |
| `expired_keys` | 过期删除的 key 数量 |

查看淘汰统计：

```bash
redis-cli -a Redis@123456 info stats | grep evicted_keys
redis-cli -a Redis@123456 config get maxmemory
redis-cli -a Redis@123456 config get maxmemory-policy
```

### 8.2 maxmemory 和淘汰策略

常见策略：

| 策略 | 说明 |
| --- | --- |
| `noeviction` | 不淘汰，写入时报错 |
| `allkeys-lru` | 在所有 key 中按 LRU 近似淘汰 |
| `volatile-lru` | 只在设置了过期时间的 key 中按 LRU 近似淘汰 |
| `allkeys-lfu` | 在所有 key 中按 LFU 近似淘汰 |
| `volatile-lfu` | 只在设置了过期时间的 key 中按 LFU 近似淘汰 |
| `allkeys-random` | 在所有 key 中随机淘汰 |
| `volatile-ttl` | 优先淘汰 TTL 更小的 key |

缓存场景常用：

```conf
maxmemory-policy allkeys-lru
```

热点访问明显的缓存场景可以考虑：

```conf
maxmemory-policy allkeys-lfu
```

如果 Redis 存放的是不能丢的数据，不应该依赖淘汰策略兜底，而应该做容量规划、拆分或迁移。

### 8.3 内存淘汰实验

学习环境临时配置最大内存：

```bash
redis-cli -a Redis@123456 config set maxmemory 100mb
redis-cli -a Redis@123456 config set maxmemory-policy allkeys-lru
```

持续写入数据：

```bash
python3 - <<'PY'
import redis
r = redis.Redis(host='127.0.0.1', port=6379, password='Redis@123456', decode_responses=False)
value = b'x' * 1024
for i in range(200000):
    r.set(f'lab:mem:{i}', value)
    if i % 10000 == 0:
        print(i, r.info('stats')['evicted_keys'], r.info('memory')['used_memory_human'])
PY
```

观察：

```bash
redis-cli -a Redis@123456 info memory
redis-cli -a Redis@123456 info stats | grep evicted_keys
```

恢复配置：

```bash
redis-cli -a Redis@123456 config set maxmemory 0
redis-cli -a Redis@123456 config set maxmemory-policy noeviction
```

清理：

```bash
redis-cli -a Redis@123456 --scan --pattern "lab:mem:*" | xargs -r -n 100 redis-cli -a Redis@123456 unlink
```

### 8.4 碎片率治理

`mem_fragmentation_ratio` 过高时，说明 Redis 申请的内存和实际使用情况出现较多碎片。

判断参考：

| 碎片率 | 判断 |
| --- | --- |
| 1.0 到 1.5 | 通常可以接受 |
| 1.5 到 2.0 | 需要观察 |
| 大于 2.0 | 需要结合内存和业务波动分析 |

治理方式：

- 避免频繁创建和删除大量不同大小的对象。
- 使用合理的数据结构，避免超大集合频繁变更。
- 评估是否开启主动碎片整理。
- 低峰期重启从节点，再做主从切换释放碎片。

主动碎片整理配置示例：

```conf
activedefrag yes
```

开启前要评估 CPU 影响。碎片整理会消耗额外 CPU，不适合在业务高峰期盲目打开。

## 9. RDB、AOF rewrite、fork 和 COW

### 9.1 为什么持久化会影响延迟

Redis 执行 RDB `BGSAVE`、AOF rewrite、主从全量复制时，通常会 fork 子进程。

fork 的风险：

- Redis 主进程内存越大，fork 成本越高。
- fork 期间可能出现短暂阻塞。
- fork 后如果主进程持续写入，会触发 COW，占用额外内存。
- 磁盘 IO 压力可能影响 AOF fsync 和 rewrite。

查看持久化状态：

```bash
redis-cli -a Redis@123456 info persistence
```

重点指标：

| 指标 | 含义 |
| --- | --- |
| `rdb_bgsave_in_progress` | 是否正在 RDB 后台保存 |
| `rdb_last_bgsave_status` | 上次 RDB 是否成功 |
| `rdb_last_cow_size` | 上次 RDB COW 内存大小 |
| `aof_rewrite_in_progress` | 是否正在 AOF rewrite |
| `aof_last_bgrewrite_status` | 上次 AOF rewrite 是否成功 |
| `aof_last_cow_size` | 上次 AOF rewrite COW 内存大小 |
| `latest_fork_usec` | 最近一次 fork 耗时 |

### 9.2 排查持久化导致的抖动

查看延迟：

```bash
redis-cli -a Redis@123456 latency latest
redis-cli -a Redis@123456 latency history fork
```

查看日志：

```bash
sudo tail -n 200 /var/log/redis/redis-6379.log
```

查看磁盘：

```bash
iostat -x 1
df -h
du -sh /data/redis/6379
```

常见日志现象：

```text
Background saving started by pid ...
DB saved on disk
Background AOF rewrite started by pid ...
Background AOF rewrite terminated with success
```

### 9.3 治理建议

- 给 Redis 预留足够内存，不要让机器内存刚好等于 Redis 数据量。
- 大实例要谨慎设置 RDB 频率和 AOF rewrite 阈值。
- 避免业务高峰期触发 rewrite。
- 监控 `latest_fork_usec`、COW 大小、AOF 文件大小。
- 大规模写入、导入、删除前要关注持久化影响。
- Cluster 可以通过分片降低单实例内存，从而降低 fork 成本。

## 10. 连接数暴涨和客户端超时

### 10.1 服务端连接指标

查看客户端：

```bash
redis-cli -a Redis@123456 info clients
redis-cli -a Redis@123456 client list
```

重点指标：

| 指标 | 含义 |
| --- | --- |
| `connected_clients` | 当前客户端连接数 |
| `blocked_clients` | 阻塞等待的客户端数 |
| `maxclients` | 最大客户端连接数 |
| `client_recent_max_input_buffer` | 最近客户端输入缓冲峰值 |
| `client_recent_max_output_buffer` | 最近客户端输出缓冲峰值 |

查看最大连接配置：

```bash
redis-cli -a Redis@123456 config get maxclients
ulimit -n
```

查看系统连接：

```bash
ss -antp | grep 6379 | wc -l
ss -antp | grep 6379 | awk '{print $1}' | sort | uniq -c
```

### 10.2 常见原因

| 现象 | 可能原因 |
| --- | --- |
| 连接数持续上涨 | 客户端连接泄漏 |
| 大量短连接 | 每次请求都新建连接 |
| `blocked_clients` 增加 | 阻塞命令、BLPOP 等等待 |
| 连接池等待超时 | 池太小或 Redis 响应慢 |
| Redis CPU 不高但业务超时 | 客户端线程池、网络或连接池排队 |

### 10.3 连接数实验

使用 redis-benchmark 模拟并发连接：

```bash
redis-benchmark -h 127.0.0.1 -p 6379 -a Redis@123456 -c 1000 -n 100000 ping
```

观察：

```bash
redis-cli -a Redis@123456 info clients
ss -antp | grep 6379 | wc -l
```

如果要模拟更高连接数，需要同时确认 Linux 文件描述符限制和 Redis `maxclients`。

### 10.4 治理建议

- Java 客户端必须复用连接，不要每次请求创建连接。
- 合理设置连接池最大连接数、最小空闲连接数、获取连接超时。
- 设置命令超时，避免无限等待。
- 对重试策略做限制，避免 Redis 抖动时业务疯狂重试。
- 监控 `connected_clients`、`blocked_clients`、连接池等待时间。
- 对批量任务限速，避免瞬间打满连接。

## 11. Cluster 环境下的运维排查

Cluster 环境中不能只看一个节点，要按节点维度排查。

### 11.1 集群状态

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

重点关注：

| 指标 | 含义 |
| --- | --- |
| `cluster_state` | 是否为 `ok` |
| `cluster_slots_assigned` | slot 是否全部分配 |
| `cluster_slots_fail` | 是否有故障 slot |
| `cluster_known_nodes` | 已知节点数 |

### 11.2 节点差异

逐个 master 观察：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 info stats
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 info stats
redis-cli -h 192.168.88.123 -p 6379 -a Redis@123456 info stats

redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 info memory
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 info memory
redis-cli -h 192.168.88.123 -p 6379 -a Redis@123456 info memory
```

如果某个节点明显异常，要进一步判断：

- slot 是否分配过多。
- 是否存在 hot key。
- 是否存在 big key。
- 是否有业务 hash tag 把大量 key 固定到同一个 slot。
- 是否正在迁槽或故障恢复。

### 11.3 hash tag 风险

hash tag 可以让多个 key 落到同一个 slot，但滥用会造成热点。

风险示例：

```text
order:{global}:1001
order:{global}:1002
order:{global}:1003
```

这些 key 都会按照 `{global}` 计算 slot，容易集中到一个 master。

合理示例：

```text
user:{1001}:profile
user:{1001}:cart
user:{1001}:orders
```

同一个用户的数据同槽，便于多 key 操作，同时不同用户可以分散到不同 slot。

## 12. 监控指标和告警规则

### 12.1 必须监控的 Redis 指标

| 分类 | 指标 |
| --- | --- |
| 存活 | `PING`、进程状态、端口状态 |
| 性能 | QPS、命令耗时、slowlog 数量、延迟 |
| 内存 | used memory、maxmemory、碎片率、evicted keys |
| 连接 | connected clients、blocked clients、rejected connections |
| 持久化 | RDB/AOF 状态、fork 耗时、COW 大小、AOF rewrite |
| 复制 | master/replica 状态、复制延迟、复制断开次数 |
| Cluster | cluster_state、slot 状态、fail 节点 |
| 系统 | CPU、内存、磁盘 IO、网络、文件描述符 |

### 12.2 建议告警项

| 告警项 | 建议规则 |
| --- | --- |
| Redis 不可用 | 连续多次 PING 失败 |
| 延迟过高 | P99 或探测延迟超过业务阈值 |
| 慢查询增加 | slowlog 持续出现高耗时命令 |
| 内存接近上限 | used_memory / maxmemory 超过 80% |
| 发生淘汰 | evicted_keys 持续增加 |
| 碎片率过高 | mem_fragmentation_ratio 长时间大于 1.5 或 2 |
| 连接数过高 | connected_clients 接近 maxclients |
| 被拒绝连接 | rejected_connections 增加 |
| fork 过慢 | latest_fork_usec 明显升高 |
| AOF/RDB 失败 | last status 不是 ok |
| 主从断开 | master_link_status 为 down |
| Cluster 异常 | cluster_state 不是 ok |

阈值不要照抄，要根据业务压测、日常基线和 SLA 调整。

## 13. 日常巡检清单

每天或每周巡检可以按下面顺序执行。

基础状态：

```bash
redis-cli -a Redis@123456 ping
redis-cli -a Redis@123456 info server
redis-cli -a Redis@123456 info stats
```

内存：

```bash
redis-cli -a Redis@123456 info memory
redis-cli -a Redis@123456 info stats | grep evicted_keys
```

慢查询和延迟：

```bash
redis-cli -a Redis@123456 slowlog len
redis-cli -a Redis@123456 slowlog get 10
redis-cli -a Redis@123456 latency latest
```

连接：

```bash
redis-cli -a Redis@123456 info clients
redis-cli -a Redis@123456 client list | head
```

持久化：

```bash
redis-cli -a Redis@123456 info persistence
```

复制：

```bash
redis-cli -a Redis@123456 info replication
```

Cluster：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

系统：

```bash
top
free -h
df -h
vmstat 1 5
iostat -x 1 5
ss -antp | grep 6379 | wc -l
```

巡检结论至少要记录：

- 当前 Redis 是否可用。
- 内存是否接近上限。
- 是否发生淘汰。
- 是否有慢查询。
- 是否有延迟事件。
- 连接数是否异常。
- 持久化是否成功。
- 主从或 Cluster 是否健康。
- 是否需要容量治理或业务整改。

## 14. 故障排查 SOP

### 14.1 Redis 变慢 SOP

```text
1. 确认影响范围
   - 单个接口慢，还是所有接口慢
   - 单个 Redis 节点慢，还是全部节点慢

2. 检查 Redis 基础状态
   - ping
   - info stats
   - info clients
   - info memory

3. 检查慢查询
   - slowlog len
   - slowlog get 10

4. 检查延迟事件
   - latency latest
   - latency doctor

5. 检查持久化和 fork
   - info persistence
   - Redis 日志

6. 检查 big key 和 hot key
   - --bigkeys
   - --hotkeys
   - commandstats

7. 检查客户端
   - 连接池等待
   - 超时和重试
   - 是否有大批量命令

8. 临时止血
   - 降级非核心功能
   - 限流批量任务
   - 清理或拆分问题 key
   - 迁移热点或扩容

9. 复盘治理
   - 增加监控
   - 增加告警
   - 修正客户端用法
   - 优化数据结构
```

### 14.2 内存打满 SOP

```text
1. info memory 确认 used_memory、maxmemory、碎片率
2. info stats 确认 evicted_keys 是否增长
3. config get maxmemory-policy 确认淘汰策略
4. --bigkeys 找大对象
5. 判断是正常增长、异常写入还是过期策略失效
6. 临时扩容或删除可丢弃缓存
7. 使用 UNLINK 清理大 key
8. 长期拆分 key、增加 TTL、扩容或迁移
```

### 14.3 连接数异常 SOP

```text
1. info clients 查看 connected_clients、blocked_clients
2. client list 查看来源 IP 和连接状态
3. ss 查看系统连接数
4. 判断是否短连接、连接泄漏、连接池配置错误
5. 检查 rejected_connections 是否增加
6. 临时限流异常客户端
7. 调整客户端连接池和超时配置
8. 补充连接数告警
```

### 14.4 Cluster 单节点热点 SOP

```text
1. cluster info 确认集群整体状态
2. cluster nodes 确认节点角色和 slot
3. 对比各 master 的 info stats、info memory、CPU
4. 查找热点节点上的 big key、hot key
5. 检查 hash tag 是否导致 key 集中
6. 临时限流热点业务
7. 拆分热点 key 或增加本地缓存
8. 必要时 reshard 迁移 slot
```

## 15. 必做实验

完成第 4 阶段前，至少做完下面实验：

1. 制造 big string，使用 `--bigkeys` 和 `MEMORY USAGE` 分析。
2. 制造 big hash，观察 `HLEN`、`MEMORY USAGE` 和删除影响。
3. 使用 `UNLINK` 删除 big key，对比 `DEL` 的风险。
4. 使用 `redis-benchmark` 制造 hot key 访问。
5. 配置 LFU 策略，尝试使用 `--hotkeys`。
6. 制造大量 key，执行 `KEYS` 并观察 slowlog。
7. 使用 `SCAN` 替代 `KEYS`。
8. 开启 latency monitor，使用 `latency latest` 和 `latency doctor`。
9. 配置 `maxmemory` 和淘汰策略，观察 `evicted_keys`。
10. 观察一次 `BGSAVE` 或 AOF rewrite 的 `info persistence` 变化。
11. 使用 `redis-benchmark -c` 模拟连接数上涨。
12. 写出一份自己的 Redis 巡检清单。
13. 写出一份 Redis 变慢故障排查 SOP。

## 16. 本阶段过关标准

完成本阶段后，你应该能回答这些问题：

1. Redis 慢时，如何判断是服务端慢、客户端慢还是网络慢？
2. `SLOWLOG` 记录的时间是否包含网络耗时？
3. big key 为什么危险？如何发现和治理？
4. hot key 为什么会让 Cluster 单节点压力过高？
5. 为什么生产环境不能随便使用 `KEYS *`？
6. `SCAN` 是否完全不会影响 Redis？
7. `DEL` big key 和 `UNLINK` 有什么区别？
8. `latency doctor` 能帮你判断哪些问题？
9. `used_memory` 和 `used_memory_rss` 有什么区别？
10. `mem_fragmentation_ratio` 过高应该如何处理？
11. Redis 内存达到 `maxmemory` 后会发生什么？
12. `noeviction` 和 `allkeys-lru` 适合什么场景？
13. RDB 和 AOF rewrite 为什么可能造成延迟抖动？
14. COW 为什么会让内存瞬间升高？
15. 连接数暴涨时应该先看哪些指标？
16. Java 客户端连接池配置不合理会怎样影响 Redis？
17. Cluster 中如何判断是否只有某个 master 有热点？
18. Redis 巡检应该包含哪些指标？
19. Redis 告警阈值为什么不能完全照抄网上模板？
20. 线上 Redis 故障处理完后，复盘应该补哪些监控和规范？

如果这些问题能讲清楚，并且能独立完成 big key、hot key、慢查询、延迟、内存淘汰和连接数实验，就可以认为已经具备 Redis 线上问题排查与基础运维治理能力。

下一阶段可以进入 Java 工程接入与缓存架构，重点学习客户端配置、缓存一致性、分布式锁、缓存穿透、缓存击穿、缓存雪崩和业务建模。
