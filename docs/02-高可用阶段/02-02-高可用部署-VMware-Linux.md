---
title: 02-02-Redis-高可用部署-VMware-Linux
date: 2026-07-06 20:07:05
description: Redis-高可用部署-VMware-Linux
tags:
  - Redis
categories:
  - Redis
realm: wujing
cover: /image/post_cover/wujing-redis-ha-deploy.svg
rank: 84
top_img: false
---
# Redis 高可用部署：VMware + Ubuntu 22.04.5 + Sentinel

## 1. 部署目标

本文用于在 VMware 中部署一套 Redis 高可用学习环境：

```text
3 个 Redis 实例:
redis-ha-1:6379  初始 master
redis-ha-2:6379  初始 replica
redis-ha-3:6379  初始 replica

3 个 Sentinel 实例:
redis-ha-1:26379
redis-ha-2:26379
redis-ha-3:26379
```

最终目标：

- 三台 Redis 节点完成主从复制。
- 三个 Sentinel 共同监控同一个 master。
- master 停机后，Sentinel 能自动完成故障转移。
- 旧 master 恢复后，会作为 replica 加入新 master。
- 能通过命令和日志解释一次 failover 的完整过程。

本文默认你已经完成 `docs/01-单机认知/单机部署-VMware-Linux.md` 中的基础能力：Ubuntu 模板机、静态 IP、Redis 源码安装、`redis-svc` 用户、systemd 管理和基本排查。

## 2. 环境规划

### 2.1 网络规划

沿用单机阶段的 VMware NAT 网段：

```text
VMware Network: VMnet8 NAT
Subnet: 192.168.88.0/24
Gateway: 192.168.88.2
DNS: 223.5.5.5, 8.8.8.8
```

节点规划：

| 主机名 | IP | Redis 端口 | Sentinel 端口 | 初始角色 |
| --- | --- | --- | --- | --- |
| `redis-ha-1` | `192.168.88.111` | `6379` | `26379` | master |
| `redis-ha-2` | `192.168.88.112` | `6379` | `26379` | replica |
| `redis-ha-3` | `192.168.88.113` | `6379` | `26379` | replica |

Sentinel 监控名称统一使用：

```text
mymaster
```

### 2.2 端口说明

| 端口 | 用途 |
| --- | --- |
| `6379` | Redis 服务端口，客户端读写、主从复制都使用它 |
| `26379` | Sentinel 服务端口，Sentinel 之间通信和客户端发现 master 都使用它 |

学习环境中，宿主机和三台虚拟机都在 `192.168.88.0/24` 网段内。防火墙只需要允许这个网段访问 `6379` 和 `26379`。

### 2.3 目录规划

三台机器保持一致：

```text
/usr/local/redis/bin/redis-server
/usr/local/redis/bin/redis-cli

/etc/redis/redis-6379.conf
/etc/redis/sentinel-26379.conf

/data/redis/6379
/data/redis/sentinel

/var/log/redis/redis-6379.log
/var/log/redis/sentinel-26379.log

/etc/systemd/system/redis-6379.service
/etc/systemd/system/redis-sentinel-26379.service
```

目录职责：

| 目录或文件 | 作用 |
| --- | --- |
| `/etc/redis/redis-6379.conf` | Redis 实例配置 |
| `/etc/redis/sentinel-26379.conf` | Sentinel 配置，Sentinel 会自动重写 |
| `/data/redis/6379` | Redis RDB/AOF 数据目录 |
| `/data/redis/sentinel` | Sentinel 工作目录 |
| `/var/log/redis` | Redis 和 Sentinel 日志目录 |

## 3. 克隆和初始化三台虚拟机

### 3.1 克隆虚拟机

从单机阶段的 Ubuntu 模板机克隆 3 台虚拟机：

```text
ubuntu-2204-template
  -> Full clone -> redis-ha-1
  -> Full clone -> redis-ha-2
  -> Full clone -> redis-ha-3
```

建议使用完整克隆，三台机器互不依赖，后续排查更直观。

### 3.2 修改主机名

分别在三台机器执行。

`redis-ha-1`：

```bash
sudo hostnamectl set-hostname redis-ha-1
```

`redis-ha-2`：

```bash
sudo hostnamectl set-hostname redis-ha-2
```

`redis-ha-3`：

```bash
sudo hostnamectl set-hostname redis-ha-3
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

三台机器输出必须不同。

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

`redis-ha-1`：

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    ens33:
      dhcp4: false
      addresses:
        - 192.168.88.111/24
      routes:
        - to: default
          via: 192.168.88.2
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

`redis-ha-2` 把地址改成：

```yaml
      addresses:
        - 192.168.88.112/24
```

`redis-ha-3` 把地址改成：

```yaml
      addresses:
        - 192.168.88.113/24
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

三台机器都编辑：

```bash
sudo vim /etc/hosts
```

写入或补充：

```text
127.0.0.1 localhost

192.168.88.111 redis-ha-1
192.168.88.112 redis-ha-2
192.168.88.113 redis-ha-3
```

验证：

```bash
ping -c 3 redis-ha-1
ping -c 3 redis-ha-2
ping -c 3 redis-ha-3
```

## 4. Redis 前置系统配置

三台机器都执行。

### 4.1 overcommit_memory

查看：

```bash
cat /proc/sys/vm/overcommit_memory
```

永久设置：

```bash
echo "vm.overcommit_memory = 1" | sudo tee /etc/sysctl.d/99-redis.conf
sudo sysctl -p /etc/sysctl.d/99-redis.conf
```

原因：Redis 执行 RDB、AOF rewrite 或全量复制时会 fork 子进程，内存 overcommit 策略不合适可能导致 fork 失败。

### 4.2 TCP backlog 上限

查看：

```bash
sysctl net.core.somaxconn
```

如果你要模拟较高并发连接，可以追加：

```bash
echo "net.core.somaxconn = 1024" | sudo tee -a /etc/sysctl.d/99-redis.conf
sudo sysctl -p /etc/sysctl.d/99-redis.conf
```

Redis 的 `tcp-backlog` 会受到 `net.core.somaxconn` 上限影响。

### 4.3 关闭 Transparent Huge Pages

查看：

```bash
cat /sys/kernel/mm/transparent_hugepage/enabled
```

临时关闭：

```bash
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
```

学习环境先临时关闭即可。生产环境通常会写入 systemd 或启动脚本，确保重启后仍然关闭。

## 5. 安装 Redis

如果你是从已经安装 Redis 的节点克隆出来，可以只验证版本：

```bash
redis-server --version
redis-cli --version
```

如果三台机器还没有安装 Redis，按单机部署文档的源码安装方式执行：

```bash
sudo apt update
sudo apt install -y gcc make tcl pkg-config libsystemd-dev wget
cd /usr/local/src
sudo wget https://download.redis.io/releases/redis-7.2.5.tar.gz
sudo tar -zxvf redis-7.2.5.tar.gz
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

验证：

```bash
/usr/local/redis/bin/redis-server --version
/usr/local/redis/bin/redis-cli --version
```

## 6. 创建用户和目录

三台机器都执行：

```bash
id -u redis-svc >/dev/null 2>&1 || sudo useradd -r -s /usr/sbin/nologin redis-svc
sudo mkdir -p /etc/redis
sudo mkdir -p /data/redis/6379
sudo mkdir -p /data/redis/sentinel
sudo mkdir -p /var/log/redis
sudo chown -R redis-svc:redis-svc /data/redis
sudo chown -R redis-svc:redis-svc /var/log/redis
```

说明：

| 命令 | 作用 |
| --- | --- |
| `useradd -r -s /usr/sbin/nologin redis-svc` | 创建低权限服务用户 |
| `/data/redis/6379` | Redis 数据目录 |
| `/data/redis/sentinel` | Sentinel 工作目录 |
| `/var/log/redis` | 日志目录 |

## 7. 编写 Redis 配置

三台机器的 Redis 配置大部分相同，只有 `replicaof` 和 `replica-announce-ip` 不同。

创建配置：

```bash
sudo vim /etc/redis/redis-6379.conf
```

### 7.1 redis-ha-1 配置

`redis-ha-1` 初始作为 master：

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

databases 16
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
replica-announce-ip 192.168.88.111
replica-announce-port 6379
repl-backlog-size 64mb
repl-backlog-ttl 3600

min-replicas-to-write 1
min-replicas-max-lag 10
```

### 7.2 redis-ha-2 配置

`redis-ha-2` 初始作为 replica。配置和 `redis-ha-1` 基本一致，但修改 `replica-announce-ip`，并增加 `replicaof`：

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

databases 16
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

replicaof 192.168.88.111 6379
replica-read-only yes
replica-announce-ip 192.168.88.112
replica-announce-port 6379
repl-backlog-size 64mb
repl-backlog-ttl 3600

min-replicas-to-write 1
min-replicas-max-lag 10
```

### 7.3 redis-ha-3 配置

`redis-ha-3` 初始作为 replica。配置和 `redis-ha-2` 基本一致，只改 `replica-announce-ip`：

```conf
replicaof 192.168.88.111 6379
replica-announce-ip 192.168.88.113
```

其他配置保持和 `redis-ha-2` 一致。

### 7.4 配置文件权限

三台机器都执行：

```bash
sudo chown redis-svc:redis-svc /etc/redis/redis-6379.conf
sudo chmod 640 /etc/redis/redis-6379.conf
```

配置文件里包含密码，不能给普通用户开放读取权限。

## 8. 配置 Redis systemd 服务

三台机器都创建：

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

三台机器都执行。

查看 ufw：

```bash
sudo ufw status
```

如果启用了 ufw，放行 Redis 和 Sentinel 端口：

```bash
sudo ufw allow from 192.168.88.0/24 to any port 6379 proto tcp
sudo ufw allow from 192.168.88.0/24 to any port 26379 proto tcp
sudo ufw reload
```

说明：

| 端口 | 为什么放行 |
| --- | --- |
| `6379` | Redis 客户端连接、主从复制、Sentinel 健康检查 |
| `26379` | Sentinel 之间通信、客户端通过 Sentinel 发现 master |

生产环境不要直接对公网开放 Redis 和 Sentinel。

## 10. 验证 Redis 主从复制

### 10.1 查看监听端口

三台机器都执行：

```bash
sudo ss -lntp | grep 6379
```

正常应看到 Redis 监听 `0.0.0.0:6379`。

### 10.2 查看 master 状态

在 `redis-ha-1` 执行：

```bash
redis-cli -a Redis@123456 info replication
```

正常应看到：

```text
role:master
connected_slaves:2
```

重点看：

```text
slave0:ip=192.168.88.112,port=6379,...
slave1:ip=192.168.88.113,port=6379,...
```

如果这里看到 `127.0.0.1` 或错误 IP，检查各 replica 的 `replica-announce-ip`。

### 10.3 查看 replica 状态

在 `redis-ha-2` 和 `redis-ha-3` 执行：

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

Redis 命令输出里仍可能使用 `slave` 字段名，这是历史兼容，不影响理解。

### 10.4 写入和读取验证

在 master 写入：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 set ha:test ok
```

在两个 replica 读取：

```bash
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 get ha:test
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 get ha:test
```

预期返回：

```text
ok
```

### 10.5 replica 只读验证

在 replica 上执行：

```bash
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 set readonly:test 1
```

预期返回：

```text
READONLY You can't write against a read only replica.
```

## 11. 编写 Sentinel 配置

三台机器都创建：

```bash
sudo vim /etc/redis/sentinel-26379.conf
```

### 11.1 redis-ha-1 Sentinel 配置

```conf
bind 0.0.0.0
protected-mode no
port 26379

daemonize no
supervised systemd
pidfile /run/redis/sentinel-26379.pid
loglevel notice
logfile /var/log/redis/sentinel-26379.log
dir /data/redis/sentinel

sentinel announce-ip 192.168.88.111
sentinel announce-port 26379

sentinel monitor mymaster 192.168.88.111 6379 2
sentinel auth-pass mymaster Redis@123456
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 60000
sentinel parallel-syncs mymaster 1
```

### 11.2 redis-ha-2 Sentinel 配置

把 announce IP 改成当前机器 IP：

```conf
sentinel announce-ip 192.168.88.112
```

其他配置保持一致。

### 11.3 redis-ha-3 Sentinel 配置

把 announce IP 改成当前机器 IP：

```conf
sentinel announce-ip 192.168.88.113
```

其他配置保持一致。

### 11.4 Sentinel 配置说明

| 配置 | 作用 |
| --- | --- |
| `protected-mode no` | 学习环境允许其他机器访问 Sentinel；生产环境要通过网络隔离和认证保护 |
| `sentinel announce-ip` | 明确告诉其他 Sentinel 和客户端当前 Sentinel 的可访问 IP |
| `sentinel monitor mymaster 192.168.88.111 6379 2` | 监控初始 master，quorum 为 2 |
| `sentinel auth-pass mymaster Redis@123456` | Sentinel 访问 Redis 时使用的密码 |
| `down-after-milliseconds mymaster 5000` | 5 秒无有效响应后标记主观下线 |
| `failover-timeout mymaster 60000` | 一次故障转移最大 60 秒 |
| `parallel-syncs mymaster 1` | 新 master 出现后，每次只允许 1 个 replica 重新同步 |

Sentinel 会自动重写 `sentinel-26379.conf`，把发现的 replica、其他 Sentinel、epoch 等状态写进去，所以配置文件必须允许 `redis-svc` 写入：

```bash
sudo chown redis-svc:redis-svc /etc/redis/sentinel-26379.conf
sudo chmod 640 /etc/redis/sentinel-26379.conf
```

## 12. 配置 Sentinel systemd 服务

三台机器都创建：

```bash
sudo vim /etc/systemd/system/redis-sentinel-26379.service
```

写入：

```ini
[Unit]
Description=Redis Sentinel 26379
After=network.target redis-6379.service

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
ReadWritePaths=/etc/redis /data/redis/sentinel /var/log/redis /run/redis

[Install]
WantedBy=multi-user.target
```

注意：`ReadWritePaths` 必须包含 `/etc/redis`，否则 Sentinel 可能无法重写自己的配置文件。

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable redis-sentinel-26379
sudo systemctl start redis-sentinel-26379
sudo systemctl status redis-sentinel-26379
```

如果启动失败：

```bash
sudo journalctl -u redis-sentinel-26379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/sentinel-26379.log
```

## 13. 验证 Sentinel 集群

任意一台机器执行。

### 13.1 查看 Sentinel 监听

```bash
sudo ss -lntp | grep 26379
```

### 13.2 查看 master

```bash
redis-cli -p 26379 sentinel master mymaster
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

初始预期：

```text
192.168.88.111
6379
```

### 13.3 查看 replica

```bash
redis-cli -p 26379 sentinel replicas mymaster
```

应能看到 `192.168.88.112:6379` 和 `192.168.88.113:6379`。

### 13.4 查看其他 Sentinel

```bash
redis-cli -p 26379 sentinel sentinels mymaster
```

在任意一个 Sentinel 上，应能看到另外两个 Sentinel。

如果看不到，优先检查：

```bash
sudo ufw status
sudo ss -lntp | grep 26379
grep announce-ip /etc/redis/sentinel-26379.conf
```

### 13.5 检查 quorum

```bash
redis-cli -p 26379 sentinel ckquorum mymaster
```

正常结果类似：

```text
OK 3 usable Sentinels. Quorum and failover authorization can be reached
```

如果这里不是 OK，说明当前 Sentinel 数量或连通性不足以可靠完成 failover。

## 14. 自动故障转移实验

### 14.1 确认当前 master

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

预期：

```text
192.168.88.111
6379
```

### 14.2 写入测试数据

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 set failover:before old-master
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 get failover:before
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 get failover:before
```

确认两个 replica 都已经同步到数据。

### 14.3 停止 master

在 `redis-ha-1` 执行：

```bash
sudo systemctl stop redis-6379
```

观察 Sentinel 日志：

```bash
sudo journalctl -u redis-sentinel-26379 -f
```

或：

```bash
sudo tail -f /var/log/redis/sentinel-26379.log
```

重点观察这些事件：

```text
+sdown
+odown
+try-failover
+elected-leader
+selected-slave
+promoted-slave
+failover-end
+switch-master
```

### 14.4 查询新 master

等待 10 到 30 秒后执行：

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```

可能返回：

```text
192.168.88.112
6379
```

也可能返回：

```text
192.168.88.113
6379
```

Sentinel 会根据 replica 状态、优先级、复制偏移量等因素选择新 master，不要假设一定是哪一台。

### 14.5 验证新 master 可写

假设新 master 是 `192.168.88.112`：

```bash
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 set failover:after new-master
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 get failover:after
```

查看角色：

```bash
redis-cli -h 192.168.88.112 -p 6379 -a Redis@123456 info replication
redis-cli -h 192.168.88.113 -p 6379 -a Redis@123456 info replication
```

应看到一个节点是 master，另一个节点是它的 replica。

### 14.6 恢复旧 master

在 `redis-ha-1` 执行：

```bash
sudo systemctl start redis-6379
```

等待 Sentinel 重新配置旧 master，然后查看：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 info replication
```

正常情况下，旧 master 会变成 replica：

```text
role:slave
master_host:新 master IP
master_link_status:up
```

再查看 Sentinel：

```bash
redis-cli -p 26379 sentinel replicas mymaster
```

应能看到旧 master 已加入 replica 列表。

注意：Sentinel 故障转移主要改变的是 Redis 节点的运行时复制关系。学习环境中，如果后续频繁重启节点，静态配置文件里的初始 `replicaof` 可能和当前 Sentinel 拓扑不一致，Sentinel 会再次尝试纠正。生产环境通常会通过自动化配置管理、发布系统或明确的运维流程同步最终拓扑配置，避免节点重启后短暂回到旧拓扑。

## 15. 手动触发 Sentinel 切换实验

除了停机触发故障转移，也可以让 Sentinel 主动发起一次切换：

```bash
redis-cli -p 26379 sentinel failover mymaster
```

这个命令适合学习和演练，也适合某些维护场景。

执行后观察：

```bash
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
redis-cli -p 26379 sentinel master mymaster
```

注意：手动 failover 也会造成短暂不可写。业务侧必须具备重试或降级能力。

## 16. 客户端连接方式

错误方式是写死 Redis master：

```text
redis.host=192.168.88.111
redis.port=6379
```

这种方式在 failover 后仍会连旧 master，业务无法自动恢复。

正确方式是配置 Sentinel：

```text
sentinel.nodes=192.168.88.111:26379,192.168.88.112:26379,192.168.88.113:26379
sentinel.master=mymaster
redis.password=Redis@123456
```

客户端启动时通过 Sentinel 查询当前 master。failover 后，客户端驱动会刷新 master 地址并重连。

工程注意：

- 至少配置 3 个 Sentinel 地址。
- 不要只配置一个 Sentinel，否则 Sentinel 自身故障会影响发现能力。
- failover 期间写请求可能短暂失败，业务要有超时、重试和降级策略。
- 如果读 replica，要接受复制延迟带来的旧数据风险。

## 17. 常用管理命令

### 17.1 Redis 服务

```bash
sudo systemctl start redis-6379
sudo systemctl stop redis-6379
sudo systemctl restart redis-6379
sudo systemctl status redis-6379
```

### 17.2 Sentinel 服务

```bash
sudo systemctl start redis-sentinel-26379
sudo systemctl stop redis-sentinel-26379
sudo systemctl restart redis-sentinel-26379
sudo systemctl status redis-sentinel-26379
```

### 17.3 Redis 状态

```bash
redis-cli -a Redis@123456 info replication
redis-cli -a Redis@123456 role
redis-cli -a Redis@123456 info persistence
redis-cli -a Redis@123456 info memory
```

### 17.4 Sentinel 状态

```bash
redis-cli -p 26379 sentinel masters
redis-cli -p 26379 sentinel master mymaster
redis-cli -p 26379 sentinel replicas mymaster
redis-cli -p 26379 sentinel sentinels mymaster
redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
redis-cli -p 26379 sentinel ckquorum mymaster
```

### 17.5 日志

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo journalctl -u redis-sentinel-26379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
sudo tail -n 100 /var/log/redis/sentinel-26379.log
```

## 18. 常见问题

### 18.1 replica 连不上 master

现象：

```text
master_link_status:down
```

排查：

```bash
redis-cli -h 192.168.88.111 -p 6379 -a Redis@123456 ping
redis-cli -a Redis@123456 info replication
sudo tail -n 100 /var/log/redis/redis-6379.log
```

常见原因：

- `replicaof` IP 或端口写错。
- `masterauth` 密码错误。
- master 没启动。
- 防火墙没放行 `6379`。
- Redis 只监听 `127.0.0.1`。

### 18.2 master 看不到 replica 的正确 IP

如果 master 上 `info replication` 里看到的 replica IP 不正确，检查 replica 配置：

```conf
replica-announce-ip 当前机器IP
replica-announce-port 6379
```

修改后重启对应 replica：

```bash
sudo systemctl restart redis-6379
```

### 18.3 Sentinel 看不到其他 Sentinel

排查：

```bash
redis-cli -p 26379 sentinel sentinels mymaster
sudo ss -lntp | grep 26379
sudo ufw status
grep announce-ip /etc/redis/sentinel-26379.conf
```

常见原因：

- 防火墙没放行 `26379`。
- `sentinel announce-ip` 写错。
- Sentinel 没启动。
- 三台机器不在同一 NAT 网段。

### 18.4 Sentinel 无法重写配置

现象可能包括：

```text
Failed rewriting config file
Permission denied
```

排查：

```bash
ls -l /etc/redis/sentinel-26379.conf
sudo systemctl cat redis-sentinel-26379
```

确认：

- `sentinel-26379.conf` 属主是 `redis-svc`。
- systemd 的 `ReadWritePaths` 包含 `/etc/redis`。

修复：

```bash
sudo chown redis-svc:redis-svc /etc/redis/sentinel-26379.conf
sudo chmod 640 /etc/redis/sentinel-26379.conf
sudo systemctl restart redis-sentinel-26379
```

### 18.5 Sentinel 不执行 failover

排查：

```bash
redis-cli -p 26379 sentinel master mymaster
redis-cli -p 26379 sentinel ckquorum mymaster
redis-cli -p 26379 sentinel sentinels mymaster
```

重点看：

- 是否达到 quorum。
- 是否有足够 Sentinel 多数派。
- master 是否真的不可达。
- replica 是否在线并且可被提升。

### 18.6 failover 后业务仍连接旧 master

原因通常是客户端写死了 `192.168.88.111:6379`。

修复方式：

- 客户端改用 Sentinel 模式。
- 配置 Sentinel 地址列表。
- 配置 master name `mymaster`。
- 配置 Redis 密码。
- 调整连接超时和重试策略。

### 18.7 写入提示 NOREPLICAS

如果 master 写入时报错类似：

```text
NOREPLICAS Not enough good replicas to write.
```

说明当前配置启用了：

```conf
min-replicas-to-write 1
min-replicas-max-lag 10
```

但 master 没有至少 1 个延迟小于 10 秒的健康 replica。

排查：

```bash
redis-cli -a Redis@123456 info replication
```

处理思路：

- 先修复 replica 到 master 的复制链路。
- 学习实验中如果只想验证单节点写入，可以临时降低或注释 `min-replicas-to-write` 后重启。
- 生产环境不要随意取消该保护，应该根据业务对数据丢失窗口的接受程度谨慎配置。

## 19. 本阶段完成标准

完成本文后，你应该做到：

1. 能部署 3 台 Redis 节点。
2. 能让 `redis-ha-1` 成为 master，`redis-ha-2` 和 `redis-ha-3` 成为 replica。
3. 能解释 `requirepass`、`masterauth`、`replicaof` 的作用。
4. 能部署 3 个 Sentinel。
5. 能用 `sentinel get-master-addr-by-name mymaster` 查询当前 master。
6. 能用 `sentinel ckquorum mymaster` 判断 Sentinel 是否具备切换条件。
7. 能停掉 master 并观察 Sentinel 自动故障转移。
8. 能解释旧 master 恢复后为什么会变成 replica。
9. 能通过日志找到 `+sdown`、`+odown`、`+switch-master` 等事件。
10. 能说明为什么 Java 客户端不能写死 Redis master IP。

达到这个状态后，再进入 Redis Cluster，会更容易理解主从、选主、故障转移和客户端拓扑发现之间的关系。
