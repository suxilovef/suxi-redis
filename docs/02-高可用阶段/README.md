# Redis 高可用阶段

## 1. 这份文档解决什么问题

单机 Redis 解决的是“一个 Redis 进程如何稳定运行”的问题，高可用阶段要解决的是“主节点故障后，业务如何尽快恢复写入能力”的问题。

本阶段重点理解：

- 主从复制如何让数据从 master 同步到 replica。
- Redis 为什么不能只靠主从复制自动故障转移。
- Sentinel 如何发现故障、选举新主、通知客户端。
- Redis 高可用能降低不可用时间，但不能保证零丢失。
- 业务客户端应该如何连接 Sentinel，而不是写死单个 Redis IP。

高可用阶段是进入 Cluster 之前必须掌握的基础。Cluster 解决分片和横向扩展，Sentinel 解决单主多从架构下的自动故障转移。

## 2. 环境规划

### 2.1 节点规划

沿用单机阶段的 VMware NAT 网段：

```text
VMware Network: VMnet8 NAT
Subnet: 192.168.88.0/24
Gateway: 192.168.88.2
DNS: 223.5.5.5, 8.8.8.8
```

高可用阶段建议使用 3 台虚拟机：

```text
redis-ha-1  192.168.88.111  Redis 6379 + Sentinel 26379
redis-ha-2  192.168.88.112  Redis 6379 + Sentinel 26379
redis-ha-3  192.168.88.113  Redis 6379 + Sentinel 26379
```

初始角色：

```text
redis-ha-1: master
redis-ha-2: replica
redis-ha-3: replica
```

Sentinel 部署方式：

```text
3 台机器各部署 1 个 Sentinel
Sentinel 共同监控同一个 master: mymaster
```

为什么至少 3 个 Sentinel：

- 1 个 Sentinel 没有容错能力。
- 2 个 Sentinel 容易出现无法形成多数派的问题。
- 3 个 Sentinel 可以容忍 1 个 Sentinel 故障。

### 2.2 软件和目录规划

建议复用单机阶段的源码安装方式和目录规范：

```text
/usr/local/redis/bin/redis-server
/usr/local/redis/bin/redis-cli

/etc/redis/redis-6379.conf
/etc/redis/sentinel-26379.conf

/data/redis/6379
/var/log/redis/redis-6379.log
/var/log/redis/sentinel-26379.log
```

如果从单机模板克隆机器，需要分别确认：

```bash
hostname
ip addr
cat /etc/machine-id
```

多台机器的 hostname、静态 IP、machine-id 和 MAC 地址都不能重复。

## 3. 高可用核心认知

### 3.1 主从复制解决什么

Redis 主从复制的核心作用：

- 数据冗余：master 数据同步到 replica。
- 读扩展：部分读请求可以打到 replica。
- 故障恢复基础：master 故障后，可以提升某个 replica 为新 master。

但主从复制本身不负责自动切换。只有 master + replica 时，master 宕机后 replica 不会自动变成 master，需要人工或 Sentinel 介入。

### 3.2 Redis 复制是异步的

Redis 主从复制默认是异步复制。master 执行写命令后，会把命令传播给 replica，但不会等待所有 replica 确认后再返回客户端。

这意味着：

- master 刚写入成功但还没复制到 replica 时宕机，数据可能丢失。
- replica 网络延迟越高，故障切换时潜在丢失窗口越大。
- Sentinel 能自动切换主节点，但不能把异步复制变成强一致复制。

可以用下面命令观察复制偏移量：

```bash
redis-cli -a Redis@123456 info replication
```

重点看：

| 指标 | 说明 |
| --- | --- |
| `role` | 当前节点是 master 还是 slave/replica |
| `connected_slaves` | master 当前连接的 replica 数量 |
| `master_replid` | 当前复制历史 ID |
| `master_repl_offset` | master 当前复制偏移量 |
| `slave_repl_offset` | replica 已复制到的偏移量 |
| `master_link_status` | replica 到 master 的复制链路状态 |
| `master_last_io_seconds_ago` | replica 距离上次收到 master 数据的时间 |

### 3.3 全量复制和部分复制

replica 第一次连接 master，或复制积压缓冲区无法覆盖断线期间的数据时，会触发全量复制。

全量复制大致流程：

```text
replica 连接 master
  -> master fork 子进程生成 RDB
  -> master 把 RDB 发送给 replica
  -> replica 清空旧数据并加载 RDB
  -> master 继续把复制期间的新写命令发送给 replica
```

部分复制依赖：

- `repl-backlog-size`：复制积压缓冲区大小。
- `master_replid`：复制历史 ID。
- `repl_offset`：复制偏移量。

如果 replica 只是短暂断线，且 master 的 backlog 里还保留这段增量数据，就可以部分复制，避免全量同步。

### 3.4 Sentinel 解决什么

Sentinel 主要负责：

- 监控 master 和 replica 是否可达。
- 判断 master 是否主观下线和客观下线。
- 在 master 故障后选择一个 replica 提升为新 master。
- 通知其他 replica 改为复制新 master。
- 给支持 Sentinel 的客户端返回当前 master 地址。

Sentinel 不负责代理业务流量。业务客户端仍然直接连接 Redis 节点，只是通过 Sentinel 发现当前 master。

### 3.5 主观下线和客观下线

Sentinel 有两个重要判断：

| 状态 | 含义 |
| --- | --- |
| S_DOWN | 单个 Sentinel 认为某个 master 不可达，属于主观下线 |
| O_DOWN | 足够数量 Sentinel 都认为 master 不可达，属于客观下线 |

`sentinel monitor mymaster 192.168.88.111 6379 2` 里的最后一个 `2` 就是 quorum。它表示至少 2 个 Sentinel 认为 master 主观下线，才会形成客观下线。

注意：quorum 不等于完成故障转移所需的全部条件。真正执行 failover 时，Sentinel 还需要在 Sentinel 集群中完成 leader 选举，通常需要多数派可用。

## 4. Redis 主从配置

### 4.1 master 配置要点

`redis-ha-1` 初始作为 master，配置文件 `/etc/redis/redis-6379.conf` 至少包含：

```conf
bind 0.0.0.0
protected-mode yes
port 6379

daemonize no
supervised systemd
pidfile /run/redis/redis-6379.pid
logfile /var/log/redis/redis-6379.log
dir /data/redis/6379

requirepass Redis@123456

appendonly yes
appenddirname "appendonlydir"
appendfsync everysec

replica-read-only yes
repl-backlog-size 64mb
repl-backlog-ttl 3600

min-replicas-to-write 1
min-replicas-max-lag 10
```

关键说明：

| 配置 | 说明 |
| --- | --- |
| `requirepass` | 客户端、replica、Sentinel 访问 Redis 都需要认证 |
| `replica-read-only yes` | replica 默认只读，避免误写 |
| `repl-backlog-size` | 部分复制依赖的积压缓冲区，太小容易触发全量复制 |
| `min-replicas-to-write` | 至少有多少个延迟可接受的 replica 时 master 才继续接受写入 |
| `min-replicas-max-lag` | replica 最大允许延迟秒数 |

`min-replicas-to-write` 不是强一致保证。它只能在 replica 数量或延迟明显异常时拒绝写入，减少极端情况下的数据丢失窗口。

### 4.2 replica 配置要点

`redis-ha-2` 和 `redis-ha-3` 初始作为 replica，配置文件在 master 基础上增加：

```conf
replicaof 192.168.88.111 6379
masterauth Redis@123456
replica-read-only yes
```

说明：

| 配置 | 说明 |
| --- | --- |
| `replicaof 192.168.88.111 6379` | 指定当前节点复制 `redis-ha-1` |
| `masterauth Redis@123456` | replica 连接 master 时使用的密码 |
| `replica-read-only yes` | 保持 replica 只读 |

Redis 5 之前常见命令叫 `slaveof`，Redis 5 之后推荐使用 `replicaof`。

### 4.3 启动后验证复制

在 master 上执行：

```bash
redis-cli -a Redis@123456 info replication
```

正常应看到：

```text
role:master
connected_slaves:2
```

在 replica 上执行：

```bash
redis-cli -a Redis@123456 info replication
```

正常应看到：

```text
role:slave
master_host:192.168.88.111
master_port:6379
master_link_status:up
```

写入验证：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 set ha:test 1
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 get ha:test
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 get ha:test
```

预期两个 replica 都能读到 `1`。

## 5. Sentinel 配置

### 5.1 Sentinel 配置文件

3 台机器都创建 `/etc/redis/sentinel-26379.conf`。

初始配置可以保持一致：

```conf
bind 0.0.0.0
protected-mode no
port 26379
daemonize no
supervised systemd
pidfile /run/redis/sentinel-26379.pid
logfile /var/log/redis/sentinel-26379.log
dir /data/redis/6379

sentinel monitor mymaster 192.168.88.111 6379 2
sentinel auth-pass mymaster Redis@123456
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 60000
sentinel parallel-syncs mymaster 1
```

关键说明：

| 配置 | 说明 |
| --- | --- |
| `port 26379` | Sentinel 默认端口 |
| `sentinel monitor mymaster ... 2` | 监控名为 `mymaster` 的 master，quorum 为 2 |
| `sentinel auth-pass mymaster ...` | Sentinel 访问被监控 Redis 时使用的密码 |
| `down-after-milliseconds` | Sentinel 多久收不到有效响应后认为主观下线 |
| `failover-timeout` | 一次故障转移的超时时间 |
| `parallel-syncs` | 新 master 出现后，同时允许几个 replica 重新同步 |

学习环境为了让宿主机和其他虚拟机能访问 Sentinel，示例使用 `bind 0.0.0.0` 和 `protected-mode no`。生产环境不能裸奔暴露 Sentinel，需要通过防火墙、内网隔离和访问控制收敛风险。

Sentinel 会自动改写自己的配置文件，记录发现的 replica、其他 Sentinel、当前 epoch 等信息。因此 Sentinel 配置文件必须允许 Sentinel 进程写入。

### 5.2 Sentinel systemd 服务

可以创建 `/etc/systemd/system/redis-sentinel-26379.service`：

```ini
[Unit]
Description=Redis Sentinel 26379
After=network.target

[Service]
Type=notify
User=redis-svc
Group=redis-svc
RuntimeDirectory=redis
RuntimeDirectoryMode=0755
ExecStart=/usr/local/redis/bin/redis-server /etc/redis/sentinel-26379.conf --sentinel --supervised systemd --daemonize no
Restart=always
RestartSec=3
LimitNOFILE=100000
PrivateTmp=true
ProtectHome=true
ProtectSystem=full
ReadWritePaths=/etc/redis /data/redis/6379 /var/log/redis /run/redis

[Install]
WantedBy=multi-user.target
```

注意：`ReadWritePaths` 需要包含 `/etc/redis`，因为 Sentinel 会重写 `sentinel-26379.conf`。如果 systemd 限制了配置目录写入，Sentinel 可能无法保存状态。

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable redis-sentinel-26379
sudo systemctl start redis-sentinel-26379
sudo systemctl status redis-sentinel-26379
```

### 5.3 验证 Sentinel 状态

任意 Sentinel 节点执行：

```bash
redis-cli -p 26379 sentinel masters
redis-cli -p 26379 sentinel replicas mymaster
redis-cli -p 26379 sentinel sentinels mymaster
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

重点看：

| 命令 | 用途 |
| --- | --- |
| `sentinel masters` | 查看 Sentinel 监控的 master 列表和状态 |
| `sentinel replicas mymaster` | 查看当前 master 下的 replica |
| `sentinel sentinels mymaster` | 查看其他 Sentinel 是否被发现 |
| `sentinel get-master-addr-by-name mymaster` | 获取当前 master 地址，客户端发现主节点依赖这个能力 |

正常情况下，`get-master-addr-by-name mymaster` 初始应返回：

```text
192.168.88.111
6379
```

## 6. 必做实验

### 6.1 主从复制实验

目标：确认 master 写入后，replica 能读取。

步骤：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 set repl:test ok
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 get repl:test
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 get repl:test
```

需要回答：

- 写入应该打到 master 还是 replica？
- replica 默认能不能写？
- 如果 replica 短暂断线后恢复，是全量复制还是部分复制？

### 6.2 replica 只读实验

目标：理解 `replica-read-only yes` 的作用。

在 replica 上执行：

```bash
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 set replica:write 1
```

预期返回类似：

```text
READONLY You can't write against a read only replica.
```

### 6.3 手工切主实验

目标：在没有 Sentinel 的情况下理解主从角色切换。

停止旧 master：

```bash
sudo systemctl stop redis-6379
```

在 `redis-ha-2` 上手工提升：

```bash
redis-cli -a Redis@123456 replicaof no one
redis-cli -a Redis@123456 info replication
```

在 `redis-ha-3` 上改为复制新 master：

```bash
redis-cli -a Redis@123456 replicaof 192.168.88.112 6379
redis-cli -a Redis@123456 info replication
```

这个实验用于理解 Sentinel 自动故障转移背后做了什么。做完后建议恢复初始拓扑，或者重新启动整套高可用实验环境。

### 6.4 Sentinel 自动故障转移实验

目标：验证 master 故障后 Sentinel 能自动选择新 master。

先确认当前 master：

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

停止当前 master：

```bash
ssh redisadmin@192.168.88.111
sudo systemctl stop redis-6379
```

观察 Sentinel 日志：

```bash
sudo journalctl -u redis-sentinel-26379 -f
sudo tail -f /var/log/redis/sentinel-26379.log
```

等待 failover 完成后再次查询：

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 info replication
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 info replication
```

需要确认：

- 新 master 是哪台机器？
- 另一个 replica 是否已经改为复制新 master？
- 旧 master 恢复后，是不是会被改造成新 master 的 replica？

### 6.5 旧 master 恢复实验

目标：理解故障恢复后的角色变化。

重新启动旧 master：

```bash
ssh redisadmin@192.168.88.111
sudo systemctl start redis-6379
```

观察：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 info replication
redis-cli -p 26379 sentinel replicas mymaster
```

正常情况下，旧 master 不会自动夺回主角色，而是作为 replica 加入当前新 master。

## 7. 常用排查命令

### 7.1 Redis 复制状态

```bash
redis-cli -a Redis@123456 info replication
redis-cli -a Redis@123456 role
redis-cli -a Redis@123456 client list
```

重点看：

| 指标 | 说明 |
| --- | --- |
| `role` | 当前 Redis 角色 |
| `connected_slaves` | master 连接的 replica 数量 |
| `master_link_status` | replica 到 master 的连接状态 |
| `master_sync_in_progress` | 是否正在全量同步 |
| `slave_repl_offset` | replica 已复制偏移量 |
| `lag` | master 视角下 replica 延迟 |

### 7.2 Sentinel 状态

```bash
redis-cli -p 26379 sentinel masters
redis-cli -p 26379 sentinel master mymaster
redis-cli -p 26379 sentinel replicas mymaster
redis-cli -p 26379 sentinel sentinels mymaster
redis-cli -p 26379 sentinel ckquorum mymaster
```

`sentinel ckquorum mymaster` 很重要，它可以检查当前 Sentinel 数量是否足以完成故障转移。

### 7.3 日志排查

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo journalctl -u redis-sentinel-26379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
sudo tail -n 100 /var/log/redis/sentinel-26379.log
```

重点搜索：

```text
+sdown
+odown
+new-epoch
+try-failover
+elected-leader
+selected-slave
+promoted-slave
+failover-end
+switch-master
```

这些日志能串起一次 Sentinel 故障转移的完整过程。

## 8. 常见问题

### 8.1 replica 一直连不上 master

优先检查：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 ping
redis-cli -a Redis@123456 info replication
sudo tail -n 100 /var/log/redis/redis-6379.log
```

高频原因：

- master IP 或端口写错。
- `masterauth` 密码错误。
- 防火墙没有放行 6379。
- master 只绑定了 `127.0.0.1`。
- 虚拟机 IP 冲突或 VMware NAT 网络不通。

### 8.2 Sentinel 看不到其他 Sentinel

排查：

```bash
redis-cli -p 26379 sentinel sentinels mymaster
sudo ss -lntp | grep 26379
sudo ufw status
```

高频原因：

- Sentinel 26379 端口没放行。
- Sentinel 只绑定了本地地址。
- 多台机器时间、网络或主机名配置混乱。
- Sentinel 配置文件不可写，发现状态无法保存。

### 8.3 Sentinel 不自动切换

排查：

```bash
redis-cli -p 26379 sentinel master mymaster
redis-cli -p 26379 sentinel ckquorum mymaster
redis-cli -p 26379 sentinel sentinels mymaster
```

重点判断：

- 是否达到 quorum。
- 是否有多数派 Sentinel 可用。
- master 是否真的不可达，而不是客户端网络问题。
- replica 是否满足晋升条件。

### 8.4 业务仍然连旧 master

这是客户端接入方式问题。

错误方式：

```text
业务配置固定 Redis 地址: 192.168.88.111:6379
```

正确方式：

```text
业务配置 Sentinel 地址列表:
192.168.88.111:26379
192.168.88.112:26379
192.168.88.113:26379

master name:
mymaster
```

Java 客户端如 Lettuce、Redisson、Jedis 都有 Sentinel 模式。业务应该通过 Sentinel 查询当前 master，而不是写死某一台 Redis。

## 9. Java 客户端接入要点

高可用阶段，Java 工程侧至少要关注：

| 项目 | 说明 |
| --- | --- |
| Sentinel 地址列表 | 配置 3 个 Sentinel 地址，不要只配 1 个 |
| master name | 必须和 `sentinel monitor` 中的名字一致，例如 `mymaster` |
| Redis 密码 | 客户端连接 Redis master/replica 的认证 |
| Sentinel 密码 | 如果 Sentinel 本身开启认证，客户端还要配置 Sentinel 认证 |
| 连接池刷新 | failover 后客户端要能刷新 master 地址 |
| 写入重试 | failover 期间写入可能短暂失败，需要业务有重试或降级策略 |
| 读写分离 | 如果读 replica，要接受复制延迟和旧数据风险 |

工程原则：

- 强一致写入不要依赖 Redis Sentinel 保证。
- failover 期间要允许短暂错误和重试。
- 不要把所有异常都无限重试，避免故障期间压垮新 master。
- 读 replica 前要明确业务能否接受延迟数据。

## 10. 本阶段过关标准

完成高可用阶段后，你应该能回答这些问题：

1. Redis 主从复制解决什么问题，不能解决什么问题？
2. Redis 复制为什么可能丢数据？
3. 全量复制和部分复制分别什么时候发生？
4. `repl-backlog-size` 太小会带来什么影响？
5. Sentinel 是代理流量的吗？
6. S_DOWN 和 O_DOWN 有什么区别？
7. quorum 和多数派有什么区别？
8. master 故障后 Sentinel 大致做了哪些动作？
9. 旧 master 恢复后为什么通常不会自动变回 master？
10. Java 客户端为什么不能写死 Redis master IP？
11. 如何确认当前 Sentinel 是否有足够票数完成 failover？
12. 如何判断 replica 和 master 是否存在明显复制延迟？

如果这些问题能讲清楚，并能独立完成一次 master 停机、Sentinel 自动切换、旧 master 重新加入的实验，就可以进入第 3 个模块：Redis Cluster。
