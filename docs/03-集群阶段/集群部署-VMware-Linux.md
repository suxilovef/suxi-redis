# Redis Cluster 部署：VMware + Ubuntu 22.04.5 + 六节点集群

## 1. 部署目标

本文用于在 VMware 中部署一套 Redis Cluster 学习环境：

```text
6 个 Redis 实例:
redis-cluster-1:6379  初始 master
redis-cluster-2:6379  初始 master
redis-cluster-3:6379  初始 master
redis-cluster-4:6379  初始 replica
redis-cluster-5:6379  初始 replica
redis-cluster-6:6379  初始 replica

6 个 cluster bus 端口:
redis-cluster-1:16379
redis-cluster-2:16379
redis-cluster-3:16379
redis-cluster-4:16379
redis-cluster-5:16379
redis-cluster-6:16379
```

最终目标：

- 六台 Redis 节点组成三主三从 Cluster。
- 16384 个 slot 全部分配到 3 个 master。
- 每个 master 至少有 1 个 replica。
- 任意停止 1 个 master 后，对应 replica 能自动提升。
- 能通过命令解释 key 分片、MOVED 重定向、CROSSSLOT 和迁槽过程。

本文默认你已经完成 `docs/01-单机认知/单机部署-VMware-Linux.md` 中的基础能力：Ubuntu 模板机、静态 IP、Redis 源码安装、`redis-svc` 用户、systemd 管理和基本排查。

## 2. 环境规划

### 2.1 网络规划

继续使用 VMware NAT 网段，但第三阶段重新规划 IP：

```text
VMware Network: VMnet8 NAT
Subnet: 192.168.88.0/24
Gateway: 192.168.88.2
DNS: 223.5.5.5, 8.8.8.8
```

节点规划：

| 主机名 | IP | Redis 端口 | Cluster bus 端口 | 初始角色 |
| --- | --- | --- | --- | --- |
| `redis-cluster-1` | `192.168.88.121` | `6379` | `16379` | master |
| `redis-cluster-2` | `192.168.88.122` | `6379` | `16379` | master |
| `redis-cluster-3` | `192.168.88.123` | `6379` | `16379` | master |
| `redis-cluster-4` | `192.168.88.124` | `6379` | `16379` | replica |
| `redis-cluster-5` | `192.168.88.125` | `6379` | `16379` | replica |
| `redis-cluster-6` | `192.168.88.126` | `6379` | `16379` | replica |

注意：初始角色是创建集群时的目标状态，最终以 `cluster nodes` 输出为准。

### 2.2 端口说明

| 端口 | 用途 |
| --- | --- |
| `6379` | Redis 客户端访问端口，也用于节点间数据复制 |
| `16379` | Cluster bus 端口，用于节点发现、心跳、故障检测和拓扑传播 |

只开放 `6379` 不够。Cluster 节点之间必须能访问彼此的 `16379`。

### 2.3 目录规划

六台机器保持一致：

```text
/usr/local/redis/bin/redis-server
/usr/local/redis/bin/redis-cli

/etc/redis/redis-6379.conf

/data/redis/6379
/data/redis/6379/nodes-6379.conf

/var/log/redis/redis-6379.log

/etc/systemd/system/redis-6379.service
```

目录职责：

| 目录或文件 | 作用 |
| --- | --- |
| `/etc/redis/redis-6379.conf` | Redis 实例配置 |
| `/data/redis/6379` | RDB、AOF 和 Cluster 节点状态文件目录 |
| `/data/redis/6379/nodes-6379.conf` | Redis 自动维护的 Cluster 拓扑文件 |
| `/var/log/redis/redis-6379.log` | Redis 日志 |
| `/etc/systemd/system/redis-6379.service` | systemd 服务文件 |

`nodes-6379.conf` 不要手工编辑。需要重建集群时，按本文后面的实验环境重置步骤处理。

## 3. 克隆和初始化六台虚拟机

### 3.1 克隆虚拟机

从单机阶段的 Ubuntu 模板机克隆 6 台虚拟机：

```text
ubuntu-2204-template
  -> Full clone -> redis-cluster-1
  -> Full clone -> redis-cluster-2
  -> Full clone -> redis-cluster-3
  -> Full clone -> redis-cluster-4
  -> Full clone -> redis-cluster-5
  -> Full clone -> redis-cluster-6
```

建议使用完整克隆，六台机器互不依赖，后续排查更直观。

### 3.2 修改主机名

分别在六台机器执行。

`redis-cluster-1`：

```bash
sudo hostnamectl set-hostname redis-cluster-1
```

`redis-cluster-2`：

```bash
sudo hostnamectl set-hostname redis-cluster-2
```

`redis-cluster-3`：

```bash
sudo hostnamectl set-hostname redis-cluster-3
```

`redis-cluster-4`：

```bash
sudo hostnamectl set-hostname redis-cluster-4
```

`redis-cluster-5`：

```bash
sudo hostnamectl set-hostname redis-cluster-5
```

`redis-cluster-6`：

```bash
sudo hostnamectl set-hostname redis-cluster-6
```

重新登录后验证：

```bash
hostname
```

### 3.3 重新生成 machine-id

每台克隆机都执行：

```bash
sudo rm -f /etc/machine-id
sudo rm -f /var/lib/dbus/machine-id
sudo systemd-machine-id-setup
sudo reboot
```

重启后验证：

```bash
cat /etc/machine-id
```

六台机器输出必须不同。VMware 里也要确认六台机器的 MAC 地址不重复。

### 3.4 配置静态 IP

先查看网卡名：

```bash
ip addr
```

本文示例使用 `ens33`。如果你的网卡名不同，下面配置要替换成实际名称。

备份 netplan：

```bash
sudo mkdir -p /root/netplan-backup
sudo cp -a /etc/netplan/*.yaml /root/netplan-backup/
```

不要把备份文件放在 `/etc/netplan` 目录下并继续使用 `.yaml` 后缀，否则 netplan 会把备份文件也当成有效配置读取。

编辑：

```bash
sudo vim /etc/netplan/00-installer-config.yaml
```

`redis-cluster-1`：

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    ens33:
      dhcp4: false
      addresses:
        - 192.168.88.121/24
      routes:
        - to: default
          via: 192.168.88.2
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

其他节点只改地址：

```yaml
# redis-cluster-2
      addresses:
        - 192.168.88.122/24

# redis-cluster-3
      addresses:
        - 192.168.88.123/24

# redis-cluster-4
      addresses:
        - 192.168.88.124/24

# redis-cluster-5
      addresses:
        - 192.168.88.125/24

# redis-cluster-6
      addresses:
        - 192.168.88.126/24
```

应用配置：

```bash
sudo netplan generate
sudo netplan apply
```

验证：

```bash
ip addr
ip route
ping -c 3 192.168.88.2
ping -c 3 223.5.5.5
```

### 3.5 配置 hosts

六台机器都编辑：

```bash
sudo vim /etc/hosts
```

写入或补充：

```text
127.0.0.1 localhost

192.168.88.121 redis-cluster-1
192.168.88.122 redis-cluster-2
192.168.88.123 redis-cluster-3
192.168.88.124 redis-cluster-4
192.168.88.125 redis-cluster-5
192.168.88.126 redis-cluster-6
```

验证：

```bash
ping -c 3 redis-cluster-1
ping -c 3 redis-cluster-2
ping -c 3 redis-cluster-3
ping -c 3 redis-cluster-4
ping -c 3 redis-cluster-5
ping -c 3 redis-cluster-6
```

## 4. Redis 前置系统配置

六台机器都执行。

### 4.1 overcommit_memory

```bash
echo "vm.overcommit_memory = 1" | sudo tee /etc/sysctl.d/99-redis.conf
sudo sysctl -p /etc/sysctl.d/99-redis.conf
```

原因：Redis 执行 RDB、AOF rewrite、全量复制和迁槽时都可能 fork 子进程，内存 overcommit 策略不合适可能导致 fork 失败。

### 4.2 TCP backlog 上限

```bash
echo "net.core.somaxconn = 1024" | sudo tee -a /etc/sysctl.d/99-redis.conf
sudo sysctl -p /etc/sysctl.d/99-redis.conf
```

### 4.3 关闭 Transparent Huge Pages

临时关闭：

```bash
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
```

持久化可以沿用单机阶段的 systemd 配置方式。学习环境至少要知道 THP 可能导致 Redis 延迟抖动。

## 5. 安装 Redis

如果从已经安装 Redis 的模板机克隆出来，可以只验证版本：

```bash
/usr/local/redis/bin/redis-server --version
/usr/local/redis/bin/redis-cli --version
```

如果还没有安装，按单机阶段源码安装方式执行：

```bash
sudo apt update
sudo apt install -y build-essential tcl pkg-config libsystemd-dev wget ca-certificates

cd /usr/local/src
sudo wget https://download.redis.io/releases/redis-7.2.5.tar.gz
sudo tar -xzf redis-7.2.5.tar.gz
sudo chown -R $(id -u):$(id -g) redis-7.2.5

cd redis-7.2.5
make USE_SYSTEMD=yes -j2
make test
sudo make install PREFIX=/usr/local/redis
sudo ln -sf /usr/local/redis/bin/redis-server /usr/local/bin/redis-server
sudo ln -sf /usr/local/redis/bin/redis-cli /usr/local/bin/redis-cli
sudo ln -sf /usr/local/redis/bin/redis-benchmark /usr/local/bin/redis-benchmark
```

如果 `make test` 在 VMware 中因为延迟敏感用例失败，但 `src/redis-server --version` 和 `src/redis-cli --version` 正常，学习环境可以继续安装。生产交付环境应换稳定机器重新跑完整测试。

## 6. 创建用户和目录

六台机器都执行：

```bash
id -u redis-svc >/dev/null 2>&1 || sudo useradd -r -s /usr/sbin/nologin redis-svc
sudo mkdir -p /etc/redis
sudo mkdir -p /data/redis/6379
sudo mkdir -p /var/log/redis
sudo chown -R redis-svc:redis-svc /data/redis
sudo chown -R redis-svc:redis-svc /var/log/redis
```

## 7. 编写 Redis Cluster 配置

六台机器都创建：

```bash
sudo vim /etc/redis/redis-6379.conf
```

配置基本一致，只有 `cluster-announce-ip` 改成本机 IP。下面以 `redis-cluster-1` 为例：

```conf
bind 0.0.0.0
protected-mode yes
port 6379
tcp-backlog 511
timeout 0
tcp-keepalive 300

daemonize no
supervised systemd
pidfile /run/redis/redis-6379.pid
loglevel notice
logfile /var/log/redis/redis-6379.log

databases 1
always-show-logo no

dir /data/redis/6379
dbfilename dump.rdb

save 900 1
save 300 10
save 60 10000
stop-writes-on-bgsave-error yes
rdbcompression yes
rdbchecksum yes

appendonly yes
appendfilename "appendonly.aof"
appenddirname "appendonlydir"
appendfsync everysec
no-appendfsync-on-rewrite no
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
aof-use-rdb-preamble yes

maxmemory 512mb
maxmemory-policy allkeys-lru

slowlog-log-slower-than 10000
slowlog-max-len 128
latency-monitor-threshold 100

requirepass Redis@123456
masterauth Redis@123456

replica-read-only yes
replica-announce-ip 192.168.88.121
replica-announce-port 6379
repl-backlog-size 64mb
repl-backlog-ttl 3600

cluster-enabled yes
cluster-config-file nodes-6379.conf
cluster-node-timeout 5000
cluster-announce-ip 192.168.88.121
cluster-announce-port 6379
cluster-announce-bus-port 16379
cluster-require-full-coverage yes
cluster-migration-barrier 1
```

其他节点修改这两项：

| 节点 | `replica-announce-ip` | `cluster-announce-ip` |
| --- | --- | --- |
| `redis-cluster-1` | `192.168.88.121` | `192.168.88.121` |
| `redis-cluster-2` | `192.168.88.122` | `192.168.88.122` |
| `redis-cluster-3` | `192.168.88.123` | `192.168.88.123` |
| `redis-cluster-4` | `192.168.88.124` | `192.168.88.124` |
| `redis-cluster-5` | `192.168.88.125` | `192.168.88.125` |
| `redis-cluster-6` | `192.168.88.126` | `192.168.88.126` |

关键说明：

| 配置 | 说明 |
| --- | --- |
| `databases 1` | Cluster 只使用 DB 0，避免误以为可以用多 DB 隔离 |
| `requirepass` | 客户端访问 Redis 需要认证 |
| `masterauth` | 当前节点作为 replica 连接 master 时使用 |
| `cluster-enabled yes` | 开启 Cluster 模式 |
| `cluster-config-file nodes-6379.conf` | Redis 自动维护的集群拓扑文件 |
| `cluster-announce-ip` | 对其他节点和客户端通告的 IP，必须是可访问的静态 IP |
| `cluster-announce-bus-port` | 对外通告的 cluster bus 端口 |

不要在配置中写 `replicaof`。Cluster 的主从关系由 `redis-cli --cluster create` 管理。

配置权限：

```bash
sudo chown redis-svc:redis-svc /etc/redis/redis-6379.conf
sudo chmod 640 /etc/redis/redis-6379.conf
```

## 8. 配置 Redis systemd 服务

六台机器都创建：

```bash
sudo vim /etc/systemd/system/redis-6379.service
```

写入：

```ini
[Unit]
Description=Redis Server 6379
After=network.target

[Service]
Type=notify
User=redis-svc
Group=redis-svc
RuntimeDirectory=redis
RuntimeDirectoryMode=0755
Environment=REDISCLI_AUTH=Redis@123456
ExecStart=/usr/local/redis/bin/redis-server /etc/redis/redis-6379.conf --supervised systemd --daemonize no
ExecStop=/usr/local/redis/bin/redis-cli --no-auth-warning -p 6379 shutdown
Restart=always
RestartSec=3
LimitNOFILE=100000
PrivateTmp=true
PrivateDevices=true
ProtectHome=true
ProtectSystem=full
ReadWritePaths=/data/redis/6379 /var/log/redis /run/redis

[Install]
WantedBy=multi-user.target
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable redis-6379
sudo systemctl start redis-6379
sudo systemctl status redis-6379
```

如果启动失败：

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
```

## 9. 配置防火墙

六台机器都执行。

查看 ufw：

```bash
sudo ufw status
```

如果启用了 ufw，放行 Redis 和 cluster bus：

```bash
sudo ufw allow from 192.168.88.0/24 to any port 6379 proto tcp
sudo ufw allow from 192.168.88.0/24 to any port 16379 proto tcp
sudo ufw reload
```

说明：

| 端口 | 为什么放行 |
| --- | --- |
| `6379` | 客户端访问、节点间复制、`redis-cli --cluster` 管理 |
| `16379` | cluster bus，负责节点发现、心跳、failover 和拓扑传播 |

生产环境不要直接对公网开放 Redis。

## 10. 启动后基础验证

六台机器都检查监听：

```bash
sudo ss -lntp | grep -E '6379|16379'
```

正常应看到 Redis 监听 `0.0.0.0:6379` 和 cluster bus 监听 `0.0.0.0:16379`。

查看单节点 Cluster 状态：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
```

在创建集群之前，可能看到：

```text
cluster_state:fail
cluster_slots_assigned:0
```

这是正常的。此时只是 6 个开启了 Cluster 模式的空节点，还没有分配 slot。

## 11. 创建三主三从集群

在任意一台机器执行，建议在 `redis-cluster-1` 上执行：

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

命令会打印 master、replica 和 slot 分配方案，并询问是否确认。学习时建议先看懂输出后再输入：

```text
yes
```

如果你已经确认规划无误，也可以增加 `--cluster-yes` 跳过交互确认。

创建成功后，通常会看到类似信息：

```text
[OK] All 16384 slots covered.
```

## 12. 验证集群状态

### 12.1 查看 cluster info

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
```

正常应看到：

```text
cluster_state:ok
cluster_slots_assigned:16384
cluster_slots_ok:16384
cluster_known_nodes:6
cluster_size:3
```

### 12.2 查看节点拓扑

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

重点看：

- 3 个节点带 `master` 标记。
- 3 个节点带 `slave` 标记。Redis 命令输出里可能仍使用 `slave` 字段名。
- 每个 master 后面有 slot 范围。
- replica 行里会显示它复制的 master 节点 ID。

### 12.3 使用 redis-cli 检查集群

```bash
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

正常输出应包含：

```text
[OK] All nodes agree about slots configuration.
[OK] All 16384 slots covered.
```

### 12.4 查看 slot 分布

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
```

你应该能看到 3 段 slot 范围，每段对应 1 个 master 和它的 replica。

## 13. 写入和分片验证

### 13.1 使用 Cluster 模式客户端

写入：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 set cluster:test ok
```

读取：

```bash
redis-cli -c -h 192.168.88.122 -p 6379 -a Redis@123456 get cluster:test
```

即使连接的是不同节点，`-c` 也会根据 MOVED 自动跳转。

### 13.2 查看 key 所属 slot

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot cluster:test
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:1001
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:1002
```

### 13.3 观察 MOVED

不加 `-c` 执行：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 get cluster:test
```

如果这个 key 不属于 `redis-cluster-1` 负责的 slot，会看到类似：

```text
MOVED 6857 192.168.88.122:6379
```

这不是 Redis 错误，而是告诉客户端应该去哪个节点。

### 13.4 验证 CROSSSLOT

执行：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mset user:1:name tom user:2:name jerry
```

如果两个 key 不在同一个 slot，会返回：

```text
CROSSSLOT Keys in request don't hash to the same slot
```

使用 hash tag：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mset user:{1}:name tom user:{1}:age 18
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 mget user:{1}:name user:{1}:age
```

预期返回：

```text
1) "tom"
2) "18"
```

## 14. master 自动故障转移实验

### 14.1 确认当前拓扑

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

确认 `redis-cluster-1` 是否为 master。如果不是 master，选择任意一个当前 master 做实验即可。

### 14.2 写入测试数据

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 set failover:before ok
redis-cli -c -h 192.168.88.122 -p 6379 -a Redis@123456 get failover:before
```

### 14.3 停止一个 master

假设 `redis-cluster-1` 是 master，在 `redis-cluster-1` 上执行：

```bash
sudo systemctl stop redis-6379
```

在其他节点观察：

```bash
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster nodes
```

也可以看日志：

```bash
sudo journalctl -u redis-6379 -f
```

重点观察：

```text
FAIL
Failover election
Failover auth granted
configEpoch
```

### 14.4 验证 failover 结果

等待 10 到 30 秒后：

```bash
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster nodes
```

正常情况下：

- `cluster_state` 恢复为 `ok`。
- 原 master 的某个 replica 变成 master。
- 新 master 接管原 master 的 slot。

继续写入验证：

```bash
redis-cli -c -h 192.168.88.122 -p 6379 -a Redis@123456 set failover:after ok
redis-cli -c -h 192.168.88.123 -p 6379 -a Redis@123456 get failover:after
```

### 14.5 恢复旧 master

在 `redis-cluster-1` 执行：

```bash
sudo systemctl start redis-6379
```

等待几秒后查看：

```bash
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster nodes
```

正常情况下，旧 master 会作为 replica 回到集群。

## 15. 手动 failover 实验

手动 failover 适合维护切换。先找到一个 replica：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

假设 `redis-cluster-4` 当前是 replica，在 `redis-cluster-4` 执行：

```bash
redis-cli -h 192.168.88.124 -p 6379 -a Redis@123456 cluster failover
```

然后查看拓扑：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

注意：

- 普通 `cluster failover` 要求 master 可达，用于平滑切换。
- `cluster failover force` 用于 master 不可达但仍希望强制提升，学习时谨慎使用。
- failover 期间业务写入可能短暂失败。

## 16. 迁槽实验

迁槽用于扩容、缩容和负载调整。学习环境先迁移少量 slot。

### 16.1 查看节点 ID

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
```

输出每行开头的长字符串就是节点 ID。

### 16.2 执行交互式 reshard

```bash
redis-cli --cluster reshard 192.168.88.121:6379 -a Redis@123456
```

交互时按提示输入：

```text
How many slots do you want to move: 100
What is the receiving node ID: 输入目标 master 的节点 ID
Please enter all the source node IDs: 输入源 master 的节点 ID，或者输入 all
Do you want to proceed with the proposed reshard plan: yes
```

### 16.3 验证迁槽结果

```bash
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
```

迁槽过程中，如果客户端访问正在迁移的 slot，可能遇到 `ASK`。支持 Cluster 的客户端会自动处理。

## 17. 常用管理命令

### 17.1 Redis 服务

```bash
sudo systemctl start redis-6379
sudo systemctl stop redis-6379
sudo systemctl restart redis-6379
sudo systemctl status redis-6379
```

### 17.2 集群状态

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster slots
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

### 17.3 key 和 slot

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 cluster keyslot user:1001
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456 get user:1001
```

### 17.4 日志

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo journalctl -u redis-6379 -f
sudo tail -n 100 /var/log/redis/redis-6379.log
sudo tail -f /var/log/redis/redis-6379.log
```

## 18. 实验环境重置集群

这一节只适用于学习环境。执行后会清理本机 Redis 数据和 Cluster 拓扑文件，正式环境不要照抄。

如果创建失败、迁槽中断或想重新开始，六台机器都执行：

```bash
sudo systemctl stop redis-6379
sudo rm -f /data/redis/6379/nodes-6379.conf
sudo rm -f /data/redis/6379/dump.rdb
sudo rm -rf /data/redis/6379/appendonlydir
sudo chown -R redis-svc:redis-svc /data/redis/6379
sudo systemctl start redis-6379
```

然后重新执行创建集群命令：

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

## 19. 常见问题

### 19.1 创建集群卡在 Waiting for the cluster to join

排查：

```bash
sudo ss -lntp | grep -E '6379|16379'
sudo ufw status
grep cluster-announce-ip /etc/redis/redis-6379.conf
grep cluster-announce-bus-port /etc/redis/redis-6379.conf
```

重点确认：

- 六台机器 `16379` 都能互通。
- `cluster-announce-ip` 是本机 `192.168.88.x` 静态 IP。
- 没有把 `cluster-announce-ip` 写成 `127.0.0.1`。
- 六台机器的 machine-id 和 MAC 地址不重复。

### 19.2 报 ERR This instance has cluster support disabled

说明当前节点没有开启 Cluster 模式。

检查：

```bash
grep cluster-enabled /etc/redis/redis-6379.conf
sudo systemctl restart redis-6379
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
```

### 19.3 报 Node is not empty

`redis-cli --cluster create` 要求节点为空。学习环境可以按第 18 节重置。正式环境必须先确认数据是否需要保留。

### 19.4 客户端报 MOVED

原因是客户端没有使用 Cluster 模式。

命令行使用：

```bash
redis-cli -c -h 192.168.88.121 -p 6379 -a Redis@123456
```

Java 客户端要配置 Redis Cluster，不能按单机 Redis 配置一个固定 IP。

### 19.5 多 key 命令报 CROSSSLOT

原因是多个 key 不在同一个 slot。

处理：

- 避免跨 slot 多 key 命令。
- 对确实需要同槽的 key 使用 hash tag。
- 不要为了省事把所有 key 都放进同一个 hash tag。

### 19.6 集群报 CLUSTERDOWN

排查：

```bash
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.121 -p 6379 -a Redis@123456 cluster nodes
redis-cli --cluster check 192.168.88.121:6379 -a Redis@123456
```

常见原因：

- 某个 master 和它的 replica 都不可用。
- 16384 个 slot 没有全部分配。
- 迁槽被中断，slot 处于 importing 或 migrating 异常状态。
- `cluster-require-full-coverage yes` 下，有 slot 不可用导致整个集群拒绝服务。

### 19.7 failover 后写入仍失败

排查方向：

- failover 是否完成，`cluster_state` 是否恢复 `ok`。
- 客户端是否使用 Cluster 模式并刷新拓扑。
- 停掉的 master 是否还有未恢复的 slot。
- 业务超时时间是否过短，是否没有重试。

命令：

```bash
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster info
redis-cli -h 192.168.88.122 -p 6379 -a Redis@123456 cluster nodes
redis-cli --cluster check 192.168.88.122:6379 -a Redis@123456
```

## 20. 客户端连接方式

错误方式是写死一个 Redis 节点，并按单机模式使用：

```text
redis.host=192.168.88.121
redis.port=6379
```

这种方式不能正确处理 `MOVED`、`ASK` 和拓扑变化。

正确方式是配置 Cluster seed nodes：

```text
cluster.nodes=192.168.88.121:6379,192.168.88.122:6379,192.168.88.123:6379,192.168.88.124:6379,192.168.88.125:6379,192.168.88.126:6379
redis.password=Redis@123456
```

工程注意：

- 至少配置 3 个 seed nodes，不要只配置 1 个。
- 开启拓扑定期刷新和 MOVED/ASK 触发刷新。
- failover、迁槽期间写请求可能短暂失败，业务要有超时和重试。
- 如果读取 replica，要接受复制延迟带来的旧数据风险。
- 多 key 命令必须同 slot，必要时使用 hash tag。

## 21. 本阶段完成标准

完成本文后，你应该做到：

1. 能克隆并初始化 6 台 Redis Cluster 虚拟机。
2. 能配置 `192.168.88.121` 到 `192.168.88.126` 静态 IP。
3. 能解释为什么 Cluster 要同时开放 `6379` 和 `16379`。
4. 能写出开启 Cluster 的 `redis-6379.conf`。
5. 能用 `redis-cli --cluster create` 创建三主三从集群。
6. 能确认 16384 个 slot 全部分配完成。
7. 能使用 `redis-cli -c` 验证写入、读取和 MOVED 重定向。
8. 能解释并演示 `CROSSSLOT` 和 hash tag。
9. 能停止一个 master 并观察 replica 自动提升。
10. 能执行一次少量 slot 迁移，并用 `--cluster check` 验证集群健康。

达到这个状态后，你就具备了 Redis Cluster 的核心部署、排查和工程接入基础。
