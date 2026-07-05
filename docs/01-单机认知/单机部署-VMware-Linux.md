# Redis 单机部署：VMware + Ubuntu 22.04.5

## 1. 部署目标

本文用于在 VMware 中基于 `ubuntu-22.04.5-live-server-amd64.iso` 部署一台单机 Redis。

这台机器既用于学习 Redis 单机部署，也作为后续主从复制、Sentinel 和 Cluster 的基础节点模板。

最终目标：

- 使用 VMware NAT 网络，和你之前 K8s 学习环境保持一致。
- 先创建一台 Ubuntu 模板机，后续通过克隆生成 Redis 节点。
- Redis 使用独立用户、独立配置目录、独立数据目录和独立日志目录。
- Redis 由 `systemd` 管理，支持开机自启、重启和日志排查。
- 宿主机可以通过固定 IP 访问虚拟机中的 Redis。
- 能验证 RDB、AOF、慢查询、日志和基础状态。

## 2. 环境规划

### 2.1 ISO 版本

本文统一使用：

```text
ubuntu-22.04.5-live-server-amd64.iso
```

不要混用 Rocky Linux、CentOS 或其他 Ubuntu 版本。先把这一套跑通，后面再扩展其他环境。

### 2.2 VMware 网络规划

本文默认沿用你之前 K8s 虚拟机环境的 VMware NAT 网段：

```text
VMware Network: VMnet8 NAT
Subnet: 192.168.88.0/24
Gateway: 192.168.88.2
DNS: 223.5.5.5, 8.8.8.8
```

需要先在 Windows 宿主机确认：

```text
VMware Workstation
  -> Edit
  -> Virtual Network Editor
  -> VMnet8
  -> NAT Settings
```

重点确认：

- `Subnet IP` 是否是 `192.168.88.0`
- `Subnet mask` 是否是 `255.255.255.0`
- `Gateway IP` 是否是 `192.168.88.2`

如果你的 VMware 配置不同，本文所有 `192.168.88.x` 都要替换成你的实际网段。

### 2.3 节点规划

建议先做一台模板机，再克隆出 Redis 节点。

```text
模板机:
ubuntu-2204-template

单机阶段:
redis-single-1  192.168.88.101

高可用阶段:
redis-ha-1  192.168.88.111
redis-ha-2  192.168.88.112
redis-ha-3  192.168.88.113

Cluster 阶段:
redis-cluster-1  192.168.88.121  6379, 6380
redis-cluster-2  192.168.88.122  6379, 6380
redis-cluster-3  192.168.88.123  6379, 6380
```

这里按阶段分配不同虚拟机，不复用前面阶段的机器。这样单机、高可用、Cluster 三套环境可以同时保留，后面排查、复盘或重做实验时互不影响。

Cluster 阶段这里写端口，是因为 Redis Cluster 需要的是多个 Redis 实例，不是单纯多台虚拟机。

学习环境为了节省资源，采用：

```text
3 台虚拟机 * 每台 2 个 Redis 实例 = 6 个 Redis 实例
```

展开后是：

```text
redis-cluster-1  192.168.88.121
  Redis 实例 1: 192.168.88.121:6379
  Redis 实例 2: 192.168.88.121:6380

redis-cluster-2  192.168.88.122
  Redis 实例 3: 192.168.88.122:6379
  Redis 实例 4: 192.168.88.122:6380

redis-cluster-3  192.168.88.123
  Redis 实例 5: 192.168.88.123:6379
  Redis 实例 6: 192.168.88.123:6380
```

Redis Cluster 最小推荐是 3 主 3 从，也就是 6 个 Redis 实例。生产环境更推荐 6 台机器每台 1 个 Redis 实例；学习环境用 3 台机器每台 2 个 Redis 实例，也能完整练习槽位分配、主从关系、故障转移、扩容缩容和迁槽。

一台虚拟机可以运行多个 Redis 进程，但每个实例必须使用不同端口、不同配置文件、不同数据目录和不同日志文件。例如 `redis-cluster-1` 上后续会有：

```text
/etc/redis/redis-6379.conf
/etc/redis/redis-6380.conf

/data/redis/6379
/data/redis/6380

/var/log/redis/redis-6379.log
/var/log/redis/redis-6380.log
```

单机阶段先只创建：

```text
hostname: redis-single-1
ip: 192.168.88.101/24
gateway: 192.168.88.2
redis port: 6379
```

## 3. 是否需要模板机

需要。

原因很直接：后面你一定会用到 3 台虚拟机做主从、Sentinel 和 Cluster。如果每次从 ISO 重新安装 Ubuntu，会浪费大量时间，而且容易出现基础环境不一致。

模板机只做 Ubuntu 基础初始化，不安装 Redis。

模板机应该完成：

- 安装 Ubuntu Server 22.04.5。
- 安装 SSH。
- 配置 apt 源和基础工具。
- 设置时区。
- 完成系统更新。
- 关机，作为克隆源。

模板机不应该做：

- 不要设置最终节点主机名，比如 `redis-single-1`。
- 不要配置最终静态 IP，比如 `192.168.88.101`。
- 不要安装 Redis。
- 不要创建 Redis 数据目录。
- 不要创建 Redis systemd 服务。

## 4. 创建 Ubuntu 模板机

### 4.1 VMware 虚拟机配置

模板机建议配置：

| 项目 | 建议 |
| --- | --- |
| CPU | 1-2 核 |
| 内存 | 2 GB |
| 磁盘 | 20 GB |
| 系统 | Ubuntu Server 22.04.5 |
| 网络 | NAT，使用 VMnet8 |

安装 Ubuntu 时建议：

- 勾选安装 `OpenSSH server`。
- 网络可以先使用 DHCP。
- 用户名可以统一使用你自己的学习用户，例如 `redisadmin`。
- 磁盘使用默认 LVM 或普通分区都可以，学习环境不强制。

### 4.2 模板机基础初始化

登录模板机后执行：

```bash
sudo apt update
sudo apt upgrade -y
sudo apt install -y vim curl wget net-tools lsof tar gcc make tcl pkg-config openssh-server ca-certificates gnupg lsb-release libsystemd-dev
sudo systemctl enable --now ssh
sudo timedatectl set-timezone Asia/Shanghai
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo apt update` | 刷新 Ubuntu 软件源索引，让系统知道当前可安装、可升级的软件版本。 | 只更新本地软件包索引，不会安装或升级软件。 |
| `sudo apt upgrade -y` | 把已安装的软件升级到当前软件源中的最新补丁版本，减少基础系统问题。 | 会升级系统软件包；`-y` 表示自动确认。执行时间取决于网络和更新数量。 |
| `sudo apt install -y ...` | 安装 Redis 编译、测试、下载、排障、SSH 远程登录所需的基础工具。 | 会新增这些软件包；其中 `gcc/make/tcl/pkg-config/libsystemd-dev` 主要服务于后面源码编译 Redis。 |
| `sudo systemctl enable --now ssh` | 启用并立即启动 SSH，方便从 Windows 终端或其他机器远程登录虚拟机。 | SSH 服务立刻启动，并设置为开机自启。 |
| `sudo timedatectl set-timezone Asia/Shanghai` | 设置系统时区，避免日志时间和本地时间对不上。 | 系统时区变为上海时间，后续 Redis 日志、systemd 日志都会按该时区显示。 |

验证：

```bash
cat /etc/os-release
hostname
ip addr
timedatectl
systemctl status ssh
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `cat /etc/os-release` | 查看当前 Linux 发行版和版本。 | 应看到 Ubuntu 22.04.x。 |
| `hostname` | 查看当前主机名。 | 模板机阶段可以还是安装时设置的名称。 |
| `ip addr` | 查看网卡、IP 地址和网络状态。 | 至少能看到一块正在使用的网卡。 |
| `timedatectl` | 查看系统时间、时区和时间同步状态。 | 时区应为 `Asia/Shanghai`。 |
| `systemctl status ssh` | 查看 SSH 服务是否正常运行。 | 状态应为 `active (running)`。 |

### 4.3 清理模板机痕迹

关机前可以清理 apt 缓存：

```bash
sudo apt clean
sudo cloud-init clean 2>/dev/null || true
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo apt clean` | 清理 apt 下载的软件包缓存，减小模板机磁盘占用。 | 删除 apt 的安装包缓存，不会卸载软件。 |
| `sudo cloud-init clean 2>/dev/null || true` | 清理 cloud-init 的初始化记录，避免克隆机继承模板机状态。 | 如果安装了 `cloud-init`，会清理它的状态；如果没有安装或执行失败，错误会被忽略，不影响继续操作。 |

如果没有安装 `cloud-init`，第二条命令报错可以忽略。

然后关机：

```bash
sudo shutdown now
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo shutdown now` | 立即关闭模板机，方便在 VMware 中把它作为干净的克隆源。 | 虚拟机会关机；后续不要再随意启动并修改模板机，否则克隆出的节点可能不一致。 |

在 VMware 中给这台机器命名为：

```text
ubuntu-2204-template
```

后续通过它克隆 Redis 节点。

## 5. 克隆 Redis 单机节点

### 5.1 克隆方式

建议使用 VMware 的完整克隆：

```text
ubuntu-2204-template
  -> Manage
  -> Clone
  -> Full clone
  -> redis-single-1
```

学习环境建议使用完整克隆，机器之间互不依赖，后续排查更直观。

### 5.2 启动克隆机后修改主机名

在 `redis-single-1` 上执行：

```bash
sudo hostnamectl set-hostname redis-single-1
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo hostnamectl set-hostname redis-single-1` | 把克隆出来的机器改成当前 Redis 单机节点的主机名，便于日志、SSH、排障时识别。 | 修改系统主机名；重新登录后终端提示符通常会显示新主机名。 |

重新登录后验证：

```bash
hostname
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `hostname` | 查看当前机器主机名是否已经改好。 | 应输出 `redis-single-1`。 |

### 5.3 重新生成 machine-id

克隆虚拟机后建议重新生成 `machine-id`，避免后续多节点实验出现标识重复。

```bash
sudo rm -f /etc/machine-id
sudo rm -f /var/lib/dbus/machine-id
sudo systemd-machine-id-setup
sudo reboot
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo rm -f /etc/machine-id` | 删除克隆自模板机的系统唯一标识。 | 该文件会被删除；`-f` 表示文件不存在也不报错。 |
| `sudo rm -f /var/lib/dbus/machine-id` | 删除 D-Bus 使用的机器标识，避免和 `/etc/machine-id` 不一致。 | 该文件会被删除；后续会重新生成。 |
| `sudo systemd-machine-id-setup` | 让 systemd 重新生成当前虚拟机自己的 `machine-id`。 | 当前机器获得新的唯一标识。 |
| `sudo reboot` | 重启系统，让依赖 `machine-id` 的服务重新按新标识启动。 | 虚拟机会重启，SSH 连接会断开，需要重新登录。 |

重启后验证：

```bash
cat /etc/machine-id
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `cat /etc/machine-id` | 查看当前机器新的唯一标识。 | 应输出一串十六进制字符；多台克隆机之间不能相同。 |

后面创建高可用阶段和 Cluster 阶段的新虚拟机时，也要分别执行类似操作。

### 5.4 确认 MAC 地址唯一

VMware 克隆时通常会生成新的 MAC 地址。仍然建议确认：

```bash
ip link
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `ip link` | 查看网卡名称、状态和 MAC 地址。 | 每台虚拟机的网卡 MAC 地址应不同。 |

如果后续多台机器 MAC 地址重复，需要在 VMware 虚拟机设置里重新生成 MAC。

## 6. 配置静态 IP

Ubuntu Server 22.04 使用 `netplan` 管理网络。

### 6.1 查看网卡名

```bash
ip addr
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `ip addr` | 查看系统识别到的网卡名和当前 IP。 | 找到类似 `ens33`、`ens160` 的网卡名，后续 netplan 里必须使用实际网卡名。 |

常见网卡名：

```text
ens33
ens160
```

本文示例使用 `ens33`。如果你的机器不是这个名字，下面配置要替换成实际网卡名。

### 6.2 备份 netplan 配置

```bash
ls /etc/netplan
sudo mkdir -p /root/netplan-backup
sudo cp -a /etc/netplan/*.yaml /root/netplan-backup/
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `ls /etc/netplan` | 查看当前 netplan 配置文件名。 | 会列出一个或多个 `.yaml` 文件，确认要修改哪个文件。 |
| `sudo mkdir -p /root/netplan-backup` | 创建 netplan 备份目录。 | 备份文件不会继续留在 `/etc/netplan` 中被 netplan 当作有效配置读取。 |
| `sudo cp -a /etc/netplan/*.yaml /root/netplan-backup/` | 修改网络前先备份，防止配置错误导致断网后无法恢复。 | 在 `/root/netplan-backup` 下保留原始配置；如果网络配置写错，可以从这里恢复。 |

不要把备份文件放在 `/etc/netplan` 目录下并继续使用 `.yaml` 后缀。netplan 会读取该目录里的 YAML 文件，旧配置和新配置同时存在时容易造成路由、DNS 或网卡配置冲突。

### 6.3 修改 netplan

编辑配置：

```bash
sudo vim /etc/netplan/00-installer-config.yaml
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo vim /etc/netplan/00-installer-config.yaml` | 用管理员权限编辑 Ubuntu 的网络配置文件，把 DHCP 改成固定 IP。 | 保存后配置文件内容会改变；真正生效要等后面执行 `netplan apply`。 |

写入：

```yaml
network:
  version: 2
  renderer: networkd
  ethernets:
    ens33:
      dhcp4: false
      addresses:
        - 192.168.88.101/24
      routes:
        - to: default
          via: 192.168.88.2
      nameservers:
        addresses:
          - 223.5.5.5
          - 8.8.8.8
```

注意：

- `addresses` 是当前 Redis 节点 IP。
- `via` 是 VMware NAT 网关，不要随便写成 `.1`。
- 新版 Ubuntu 推荐使用 `routes` 配置默认路由，不使用旧的 `gateway4`。
- YAML 对缩进敏感，必须使用空格。

### 6.4 应用配置

建议先生成检查：

```bash
sudo netplan generate
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo netplan generate` | 先检查 netplan YAML 语法并生成底层网络配置。 | 不会立即改网络；如果 YAML 缩进或字段错误，会在这里报错，适合先做安全检查。 |

再应用：

```bash
sudo netplan apply
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo netplan apply` | 让新的静态 IP、网关、DNS 配置真正生效。 | 网络会短暂重载；如果 IP、网关或网卡名写错，SSH 可能断开，需要回到 VMware 控制台修复。 |

验证：

```bash
ip addr
ip route
ping -c 3 192.168.88.2
ping -c 3 223.5.5.5
ping -c 3 baidu.com
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `ip addr` | 查看静态 IP 是否已经绑定到正确网卡。 | 应看到 `192.168.88.101/24`。 |
| `ip route` | 查看默认路由是否指向 VMware NAT 网关。 | 默认路由应经由 `192.168.88.2`。 |
| `ping -c 3 192.168.88.2` | 测试虚拟机到 NAT 网关是否通。 | 能收到 3 次回复，说明本地网段和网关配置基本正常。 |
| `ping -c 3 223.5.5.5` | 测试是否能访问外部 IP。 | 能通说明网关/NAT 出网正常。 |
| `ping -c 3 baidu.com` | 测试 DNS 解析和外网访问。 | 能解析并 ping 通，说明 DNS 配置正常。 |

如果 `223.5.5.5` 能通但域名不通，通常是 DNS 配置问题。

### 6.5 配置 hosts

编辑：

```bash
sudo vim /etc/hosts
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo vim /etc/hosts` | 编辑本机 hosts 文件，让主机名能解析到规划好的 IP。 | 保存后，本机可以直接用 `redis-single-1` 等名称访问对应 IP；不会影响 DNS 服务器。 |

写入：

```text
127.0.0.1 localhost
192.168.88.101 redis-single-1

192.168.88.111 redis-ha-1
192.168.88.112 redis-ha-2
192.168.88.113 redis-ha-3

192.168.88.121 redis-cluster-1
192.168.88.122 redis-cluster-2
192.168.88.123 redis-cluster-3
```

单机阶段只用 `redis-single-1`。后续高可用和 Cluster 阶段使用新的虚拟机，提前规划名称和 IP 是为了避免地址冲突。

验证：

```bash
ping -c 3 redis-single-1
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `ping -c 3 redis-single-1` | 验证 `/etc/hosts` 中的主机名解析是否生效。 | 应解析到 `192.168.88.101` 并收到回复。 |

## 7. Redis 前置系统配置

这些配置不是为了让 Redis 勉强跑起来，而是为了让你从一开始就按接近生产的方式理解 Redis。

### 7.1 文件描述符

查看：

```bash
ulimit -n
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `ulimit -n` | 查看当前 shell 进程允许打开的最大文件描述符数量。 | 学习环境里数值可能不高；Redis 服务后面会通过 systemd 的 `LimitNOFILE` 单独提高。 |

Redis 服务最终会通过 `systemd` 的 `LimitNOFILE` 单独设置，不需要在 shell 里永久修改。

### 7.2 overcommit_memory

查看：

```bash
cat /proc/sys/vm/overcommit_memory
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `cat /proc/sys/vm/overcommit_memory` | 查看 Linux 内核当前的内存 overcommit 策略。 | Redis 推荐设置为 `1`；如果不是 `1`，后面会永久调整。 |

永久设置：

```bash
echo "vm.overcommit_memory = 1" | sudo tee /etc/sysctl.d/99-redis.conf
sudo sysctl -p /etc/sysctl.d/99-redis.conf
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `echo "vm.overcommit_memory = 1" | sudo tee /etc/sysctl.d/99-redis.conf` | 把 Redis 推荐的内存分配策略写入 sysctl 配置文件。 | 新增或覆盖 `/etc/sysctl.d/99-redis.conf`；这是永久配置，重启后仍会生效。 |
| `sudo sysctl -p /etc/sysctl.d/99-redis.conf` | 立即加载刚写入的内核参数，不等重启。 | 当前系统的 `vm.overcommit_memory` 立刻变为 `1`。 |

原因：Redis 执行 RDB 或 AOF rewrite 时会 `fork` 子进程，内存分配策略不合适可能导致 fork 失败。

### 7.3 关闭 Transparent Huge Pages

查看：

```bash
cat /sys/kernel/mm/transparent_hugepage/enabled
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `cat /sys/kernel/mm/transparent_hugepage/enabled` | 查看透明大页 THP 当前状态。 | 如果看到 `[always]` 或 `[madvise]`，说明当前启用了对应模式；Redis 通常建议关闭。 |

临时关闭：

```bash
echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `echo never | sudo tee /sys/kernel/mm/transparent_hugepage/enabled` | 临时关闭 THP，减少 Redis 延迟抖动风险。 | 立即生效，但重启后可能恢复；学习阶段先用它理解影响即可。 |

学习阶段可以先临时关闭。后面如果你要模拟生产部署，再把它写入 systemd 或启动脚本。

### 7.4 TCP backlog 上限

Redis 配置里的 `tcp-backlog` 会受到 Linux 内核 `net.core.somaxconn` 上限影响。学习环境可以先查看当前值：

```bash
sysctl net.core.somaxconn
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `sysctl net.core.somaxconn` | 查看系统允许的监听队列最大值。 | 应不小于 Redis 配置里的 `tcp-backlog`；如果小于该值，Redis 的实际 backlog 会被内核上限截断。 |

如果后续要模拟高并发连接，可以再通过 `/etc/sysctl.d/99-redis.conf` 调整它。单机入门阶段先理解它和 `tcp-backlog` 的关系即可。

## 8. 安装 Redis

学习部署建议使用源码安装，这样你能清楚 Redis 的二进制文件、配置文件、数据目录和日志目录分别在哪里。

### 8.1 下载源码

```bash
cd /usr/local/src
sudo wget https://download.redis.io/releases/redis-7.2.5.tar.gz
sudo tar -zxvf redis-7.2.5.tar.gz
sudo chown -R $(id -u):$(id -g) redis-7.2.5
cd redis-7.2.5
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `cd /usr/local/src` | 进入源码包存放目录，便于统一管理手工下载的软件源码。 | 当前 shell 工作目录切换到 `/usr/local/src`。 |
| `sudo wget https://download.redis.io/releases/redis-7.2.5.tar.gz` | 从 Redis 官方下载 7.2.5 源码压缩包。 | 在当前目录生成 `redis-7.2.5.tar.gz`；需要网络可访问。 |
| `sudo tar -zxvf redis-7.2.5.tar.gz` | 解压 Redis 源码包。 | 生成 `redis-7.2.5` 源码目录。 |
| `sudo chown -R $(id -u):$(id -g) redis-7.2.5` | 把源码目录所有者改成当前登录用户，避免后续普通用户编译时报权限错误。 | `redis-7.2.5` 目录及其文件归当前用户所有。 |
| `cd redis-7.2.5` | 进入 Redis 源码目录，准备编译。 | 当前 shell 工作目录切换到源码目录。 |

如果下载慢，可以提前在宿主机下载后上传到虚拟机。

### 8.2 编译安装

编译前先确认依赖已安装。因为本文使用 `supervised systemd`，Redis 编译时需要 `libsystemd-dev` 提供 systemd 头文件：

```bash
sudo apt update
sudo apt install -y gcc make tcl pkg-config libsystemd-dev
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo apt update` | 刷新软件源索引，确保安装依赖时拿到可用版本。 | 只更新本地索引，不安装软件。 |
| `sudo apt install -y gcc make tcl pkg-config libsystemd-dev` | 安装 Redis 源码编译、测试和 systemd 支持需要的依赖。 | 新增编译工具和 systemd 开发头文件；缺少 `libsystemd-dev` 时，后面的 `Type=notify` 服务容易启动超时。 |

```bash
make USE_SYSTEMD=yes -j2
make test
sudo make install PREFIX=/usr/local/redis
sudo ln -sf /usr/local/redis/bin/redis-server /usr/local/bin/redis-server
sudo ln -sf /usr/local/redis/bin/redis-cli /usr/local/bin/redis-cli
sudo ln -sf /usr/local/redis/bin/redis-benchmark /usr/local/bin/redis-benchmark
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `make USE_SYSTEMD=yes -j2` | 编译 Redis，并启用 systemd 通知支持；`-j2` 表示用 2 个并行任务编译。 | 在源码目录 `src` 下生成 Redis 二进制文件；不会安装到系统目录。 |
| `make test` | 运行 Redis 官方测试，确认当前机器上编译出的 Redis 基本可用。 | 会启动临时测试 Redis 进程并执行用例；学习环境建议跑，但它不是安装 Redis 的必要步骤。 |
| `sudo make install PREFIX=/usr/local/redis` | 把编译好的 Redis 程序安装到指定目录。 | 写入 `/usr/local/redis/bin`；需要 `sudo` 是因为 `/usr/local` 通常归 root 管理。 |
| `sudo ln -sf /usr/local/redis/bin/redis-server /usr/local/bin/redis-server` | 给 `redis-server` 创建全局软链接，方便直接执行。 | `/usr/local/bin/redis-server` 会指向真实安装文件；`-f` 会覆盖已有同名链接。 |
| `sudo ln -sf /usr/local/redis/bin/redis-cli /usr/local/bin/redis-cli` | 给 Redis 客户端创建全局软链接。 | 后续可以直接执行 `redis-cli`，不用写完整路径。 |
| `sudo ln -sf /usr/local/redis/bin/redis-benchmark /usr/local/bin/redis-benchmark` | 给 Redis 压测工具创建全局软链接。 | 后续可以直接执行 `redis-benchmark`。 |

如果 `make test` 在 VMware 中因为延迟敏感测试失败，先看 `src/redis-server --version` 是否正常；如果正常，可以继续执行 `sudo make install PREFIX=/usr/local/redis`。

验证：

```bash
/usr/local/redis/bin/redis-server --version
/usr/local/redis/bin/redis-cli --version
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `/usr/local/redis/bin/redis-server --version` | 查看 Redis 服务端二进制是否安装成功以及版本号。 | 应输出 Redis 版本、bits、jemalloc 等信息。 |
| `/usr/local/redis/bin/redis-cli --version` | 查看 Redis 命令行客户端是否安装成功以及版本号。 | 应输出 `redis-cli 7.2.5` 或对应版本。 |

安装后的关键文件：

| 文件 | 作用 |
| --- | --- |
| `/usr/local/redis/bin/redis-server` | Redis 服务端 |
| `/usr/local/redis/bin/redis-cli` | Redis 命令行客户端 |
| `/usr/local/redis/bin/redis-benchmark` | Redis 压测工具 |
| `/usr/local/redis/bin/redis-check-rdb` | RDB 检查工具 |
| `/usr/local/redis/bin/redis-check-aof` | AOF 检查工具 |

## 9. 创建 Redis 用户和目录

不要用 `root` 直接运行 Redis。

如果你安装 Ubuntu 时已经把登录用户名设置成了 `redis`，不要再创建同名服务用户。建议使用 `redis-svc` 作为 Redis 服务运行用户。

本文后续统一使用 `redis-svc` 作为 Redis 服务用户。

先把几个用户角色分清楚：

```text
root
  Linux 超级管理员，拥有所有权限。

redis
  你创建 Ubuntu 虚拟机时填写的登录用户。
  它是普通用户，可以通过 sudo 临时执行管理员操作。

redis-svc
  Redis 服务运行用户。
  它不能登录系统，只负责运行 redis-server。
```

学习环境中的职责划分：

```text
编译 Redis：用登录用户 redis
测试 Redis：用登录用户 redis
安装 Redis：用 sudo
运行 Redis 服务：用 redis-svc
```

生产环境的原则类似，但通常会换成更规范的用户和发布流程：

```text
编译 Redis：普通构建用户，例如 build、deploy、cicd
测试 Redis：普通构建用户
安装 Redis：root 权限或自动化发布工具
运行 Redis 服务：专用低权限用户，例如 redis、redis-svc
```

核心原则：

- 不要用 `root` 运行 Redis 服务。
- 不要用 `root` 编译和测试 Redis，避免源码目录出现权限污染。
- 安装阶段需要写入系统目录，所以可以使用 `sudo`。
- Redis 服务运行用户只应该拥有数据目录、日志目录和运行目录的必要权限。

```bash
id -u redis-svc >/dev/null 2>&1 || sudo useradd -r -s /usr/sbin/nologin redis-svc
sudo mkdir -p /etc/redis
sudo mkdir -p /data/redis/6379
sudo mkdir -p /var/log/redis
sudo chown -R redis-svc:redis-svc /data/redis
sudo chown -R redis-svc:redis-svc /var/log/redis
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `id -u redis-svc >/dev/null 2>&1 || sudo useradd -r -s /usr/sbin/nologin redis-svc` | 如果 `redis-svc` 不存在就创建一个系统服务用户；如果已存在就跳过。 | 新用户不能登录系统，只能被 systemd 用来运行 Redis；避免用 root 跑 Redis。 |
| `sudo mkdir -p /etc/redis` | 创建 Redis 配置目录。 | 后续 `redis-6379.conf` 放在这里；`-p` 表示目录已存在也不报错。 |
| `sudo mkdir -p /data/redis/6379` | 创建 6379 实例的数据目录。 | RDB、AOF 等持久化文件会写到这里。 |
| `sudo mkdir -p /var/log/redis` | 创建 Redis 日志目录。 | Redis 运行日志会写到这里。 |
| `sudo chown -R redis-svc:redis-svc /data/redis` | 把数据目录授权给 Redis 服务用户。 | `redis-svc` 可以写入 RDB/AOF；如果不授权，Redis 启动或持久化可能失败。 |
| `sudo chown -R redis-svc:redis-svc /var/log/redis` | 把日志目录授权给 Redis 服务用户。 | `redis-svc` 可以写日志；如果不授权，Redis 可能因无法打开日志文件而启动失败。 |

目录说明：

| 目录 | 用途 |
| --- | --- |
| `/usr/local/redis` | Redis 安装目录 |
| `/etc/redis` | Redis 配置目录 |
| `/data/redis/6379` | Redis 数据目录 |
| `/var/log/redis` | Redis 日志目录 |

## 10. 编写 redis.conf

创建配置文件：

```bash
sudo vim /etc/redis/redis-6379.conf
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo vim /etc/redis/redis-6379.conf` | 创建并编辑当前 Redis 6379 实例的配置文件。 | 保存后 Redis 会按这个文件启动；配置内容不会自动生效，需要启动或重启 Redis。 |

写入：

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
```

配置说明：

| 配置 | 作用 | 影响 |
| --- | --- | --- |
| `bind 0.0.0.0` | 监听所有网卡地址。 | 宿主机和其他虚拟机可以访问 Redis；必须配合密码和防火墙控制风险。 |
| `protected-mode yes` | 开启保护模式。 | Redis 会对不安全暴露场景做保护；本文同时设置了密码。 |
| `port 6379` | 指定 Redis 监听端口。 | 客户端要连接 `6379`；多实例时端口必须不同。 |
| `daemonize no` | 不让 Redis 自己转后台。 | Redis 前台运行，由 systemd 负责托管进程。 |
| `supervised systemd` | 启用 systemd 监督模式。 | Redis 启动完成后会通知 systemd；编译时必须带 `USE_SYSTEMD=yes`。 |
| `pidfile /run/redis/redis-6379.pid` | 指定 PID 文件位置。 | 便于记录 Redis 主进程 ID；运行目录由 systemd 创建。 |
| `logfile /var/log/redis/redis-6379.log` | 指定 Redis 日志文件。 | 日志落盘到该文件，排障时重点查看。 |
| `dir /data/redis/6379` | 指定数据目录。 | RDB/AOF 文件会写到这里；目录必须允许 `redis-svc` 写入。 |
| `save ...` | 配置 RDB 自动快照规则。 | 满足写入次数和时间条件后触发后台保存。 |
| `appendonly yes` | 开启 AOF 持久化。 | 每次写命令会进入 AOF 流程，提升数据恢复能力，但增加磁盘写入。 |
| `appenddirname "appendonlydir"` | 指定 Redis 7 多部分 AOF 目录名。 | AOF base、incremental、manifest 等文件会放在 `/data/redis/6379/appendonlydir` 下。 |
| `appendfsync everysec` | AOF 每秒刷盘。 | 性能和数据安全的折中；最多可能丢约 1 秒数据。 |
| `maxmemory 512mb` | 限制 Redis 最大可用内存。 | 防止学习虚拟机内存被 Redis 吃满。 |
| `maxmemory-policy allkeys-lru` | 内存满时按 LRU 淘汰键。 | 所有 key 都可能被淘汰，适合理解缓存型 Redis 的行为。 |
| `slowlog-log-slower-than 10000` | 慢查询阈值，单位微秒。 | 执行超过 10ms 的命令会进入慢查询日志。 |
| `latency-monitor-threshold 100` | 延迟监控阈值，单位毫秒。 | Redis 会记录超过阈值的延迟事件。 |
| `requirepass Redis@123456` | 设置访问密码。 | 客户端必须认证；学习环境可用，生产环境要换成强密码并避免明文暴露。 |

修改权限：

```bash
sudo chown redis-svc:redis-svc /etc/redis/redis-6379.conf
sudo chmod 640 /etc/redis/redis-6379.conf
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo chown redis-svc:redis-svc /etc/redis/redis-6379.conf` | 让 Redis 服务用户拥有配置文件。 | Redis 进程可以读取配置文件。 |
| `sudo chmod 640 /etc/redis/redis-6379.conf` | 收紧配置文件权限，因为里面包含密码。 | 文件所有者可读写，同组可读，其他用户无权限；降低密码泄露风险。 |

关键配置说明：

- `bind 0.0.0.0`：允许宿主机和其他虚拟机访问。
- `protected-mode yes`：保留保护模式，配合密码使用。
- `supervised systemd`：交给 systemd 管理。
- `appendonly yes`：打开 AOF，方便学习持久化。
- `maxmemory 512mb`：限制 Redis 最大内存，避免吃光虚拟机内存。
- `requirepass Redis@123456`：学习环境密码，后续生产环境必须换成强密码。

## 11. 配置 systemd 服务

创建服务文件：

```bash
sudo vim /etc/systemd/system/redis-6379.service
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo vim /etc/systemd/system/redis-6379.service` | 创建 Redis 的 systemd 服务单元文件。 | 保存后 systemd 才知道如何启动、停止、重启 Redis；需要执行 `daemon-reload` 才会加载新文件。 |

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

服务配置说明：

| 配置 | 作用 | 影响 |
| --- | --- | --- |
| `After=network.target` | 要求 Redis 在基础网络目标之后启动。 | 避免系统刚启动时网络还没好就启动 Redis。 |
| `Type=notify` | systemd 等 Redis 主动通知“我启动好了”。 | Redis 必须编译 systemd 支持，并配置 `supervised systemd`。 |
| `User=redis-svc` / `Group=redis-svc` | 用低权限用户运行 Redis。 | Redis 进程不具备 root 权限，降低误操作和漏洞风险。 |
| `RuntimeDirectory=redis` | 让 systemd 创建 `/run/redis`。 | PID 文件可以写入 `/run/redis/redis-6379.pid`。 |
| `Environment=REDISCLI_AUTH=...` | 给 `ExecStop` 使用的 `redis-cli` 提供认证密码。 | 避免把密码直接写在停止命令参数里；学习环境可用，生产环境应使用更安全的凭据管理方式。 |
| `ExecStart=...` | 定义 Redis 启动命令。 | systemd 会按这里的路径和配置文件启动 Redis。 |
| `ExecStop=... shutdown` | 定义 Redis 停止命令。 | 停止服务时通过 `redis-cli` 让 Redis 正常退出，尽量保证持久化收尾。 |
| `Restart=always` / `RestartSec=3` | Redis 异常退出后自动重启。 | 进程崩溃后 3 秒重启，适合服务托管。 |
| `LimitNOFILE=100000` | 提高 Redis 可打开文件描述符数量。 | 支持更多客户端连接和文件句柄。 |
| `PrivateTmp=true` / `PrivateDevices=true` | 限制 Redis 访问临时目录和设备。 | 属于 systemd 安全加固，减少 Redis 进程能看到的系统资源。 |
| `ProtectHome=true` / `ProtectSystem=full` | 限制访问用户家目录和系统目录写入。 | 防止 Redis 误写系统敏感路径。 |
| `ReadWritePaths=...` | 明确允许 Redis 写入数据、日志、运行目录。 | 即使开启了保护，Redis 仍能写入必要目录。 |
| `WantedBy=multi-user.target` | 定义开机自启挂载目标。 | 执行 `systemctl enable` 后，系统进入多用户模式会自动启动 Redis。 |

加载并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable redis-6379
sudo systemctl start redis-6379
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo systemctl daemon-reload` | 让 systemd 重新读取服务文件。 | 新增或修改的 `redis-6379.service` 被 systemd 识别。 |
| `sudo systemctl enable redis-6379` | 设置 Redis 开机自启。 | 会创建 systemd 自启链接；不会立即启动服务。 |
| `sudo systemctl start redis-6379` | 立即启动 Redis 服务。 | Redis 进程会启动并监听 6379；失败时看后面的日志命令。 |

查看状态：

```bash
sudo systemctl status redis-6379
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `sudo systemctl status redis-6379` | 查看 Redis 服务是否正在运行，以及最近的 systemd 日志。 | 应看到 `active (running)`；如果失败，会显示失败原因摘要。 |

如果启动失败：

```bash
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
```

命令说明：

| 命令 | 用来排查什么 | 重点看什么 |
| --- | --- | --- |
| `sudo journalctl -u redis-6379 -n 100 --no-pager` | 查看 systemd 记录的 Redis 服务日志。 | 启动超时、权限错误、退出码、systemd notify 问题。 |
| `sudo tail -n 100 /var/log/redis/redis-6379.log` | 查看 Redis 自己写的运行日志。 | 配置语法错误、端口占用、目录权限、持久化文件错误。 |

## 12. 防火墙配置

Ubuntu Server 默认不一定启用 `ufw`，先查看：

```bash
sudo ufw status
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `sudo ufw status` | 查看 Ubuntu 简易防火墙是否启用。 | 如果是 `inactive`，说明当前没有 ufw 拦截；如果是 `active`，需要放行 Redis 端口。 |

如果启用了 `ufw`，放行 Redis 端口：

```bash
sudo ufw allow from 192.168.88.0/24 to any port 6379 proto tcp
sudo ufw reload
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo ufw allow from 192.168.88.0/24 to any port 6379 proto tcp` | 只允许 VMware NAT 学习网段访问 Redis 的 TCP 6379 端口。 | ufw 规则中会新增来源网段受限的 6379/tcp 放行规则，比直接对所有来源开放更安全。 |
| `sudo ufw reload` | 重新加载 ufw 规则。 | 新增规则立即生效。 |

学习环境中也可以暂时关闭 `ufw`，但你要知道生产环境不能这么做：

```bash
sudo ufw disable
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo ufw disable` | 学习环境临时关闭防火墙，排除端口被拦截的干扰。 | ufw 会停止过滤流量；生产环境不要这样做，应按端口最小放行。 |

## 13. 验证 Redis

### 13.1 本机连接

```bash
/usr/local/redis/bin/redis-cli -h 127.0.0.1 -p 6379 -a Redis@123456
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `/usr/local/redis/bin/redis-cli -h 127.0.0.1 -p 6379 -a Redis@123456` | 用 Redis 客户端连接本机 6379 实例，并带上密码认证。 | 进入交互式 Redis 命令行；不会修改数据，后续输入 `set` 才会写数据。 |

执行：

```bash
ping
set hello redis
get hello
info server
info memory
info persistence
```

命令说明：

| 命令 | 验证什么 | 执行后的影响 |
| --- | --- | --- |
| `ping` | 验证 Redis 连接和认证是否正常。 | 正常返回 `PONG`，不修改数据。 |
| `set hello redis` | 验证写入命令是否可用。 | 写入 key `hello`，值为 `redis`。 |
| `get hello` | 验证读取命令是否可用。 | 读取 key `hello` 的值。 |
| `info server` | 查看 Redis 服务端版本、运行模式、进程等信息。 | 只读查询，不修改数据。 |
| `info memory` | 查看 Redis 内存使用情况。 | 只读查询，可用来确认 `maxmemory` 等信息。 |
| `info persistence` | 查看 RDB/AOF 持久化状态。 | 只读查询，可用来确认是否正在保存、AOF 是否开启。 |

预期：

```text
PONG
OK
"redis"
```

### 13.2 宿主机远程连接

在 Windows 宿主机或其他虚拟机上执行：

```bash
redis-cli -h 192.168.88.101 -p 6379 -a Redis@123456 ping
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `redis-cli -h 192.168.88.101 -p 6379 -a Redis@123456 ping` | 从宿主机或其他机器远程连接 Redis，验证静态 IP、Redis 监听、防火墙和密码是否都正确。 | 返回 `PONG`。 |

预期：

```text
PONG
```

如果宿主机没有 `redis-cli`，可以先在虚拟机内验证端口监听：

```bash
sudo ss -lntp | grep 6379
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `sudo ss -lntp | grep 6379` | 查看 Linux 上是否有进程监听 TCP 6379 端口。 | 应看到 `redis-server` 正在监听 `0.0.0.0:6379` 或对应地址。 |

也可以在 Windows PowerShell 中简单测试端口：

```powershell
Test-NetConnection 192.168.88.101 -Port 6379
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `Test-NetConnection 192.168.88.101 -Port 6379` | 在 Windows 上测试到虚拟机 Redis 端口的 TCP 连通性。 | `TcpTestSucceeded` 应为 `True`。 |

## 14. 验证持久化

### 14.1 验证 RDB

```bash
redis-cli -a Redis@123456 set rdb:test 1
redis-cli -a Redis@123456 bgsave
redis-cli -a Redis@123456 lastsave
ls -lh /data/redis/6379
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 set rdb:test 1` | 写入一条测试数据，后面用来验证 RDB 恢复。 | 新增或覆盖 key `rdb:test`。 |
| `redis-cli -a Redis@123456 bgsave` | 手动触发后台 RDB 快照。 | Redis fork 子进程生成 `dump.rdb`；数据量大时会有短暂资源开销。 |
| `redis-cli -a Redis@123456 lastsave` | 查看最近一次 RDB 保存时间。 | 只读查询，返回时间戳。 |
| `ls -lh /data/redis/6379` | 查看数据目录里是否生成持久化文件。 | 应能看到 `dump.rdb`，也可能看到 AOF 相关文件或目录。 |

应该能看到 `dump.rdb`。

重启 Redis：

```bash
sudo systemctl restart redis-6379
redis-cli -a Redis@123456 get rdb:test
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `sudo systemctl restart redis-6379` | 重启 Redis，模拟服务重启后的数据恢复。 | Redis 会停止再启动，连接会短暂中断。 |
| `redis-cli -a Redis@123456 get rdb:test` | 验证重启后测试 key 是否还在。 | 如果持久化生效，应返回 `1`。 |

### 14.2 验证 AOF

```bash
redis-cli -a Redis@123456 set aof:test 1
redis-cli -a Redis@123456 bgrewriteaof
redis-cli -a Redis@123456 info persistence
ls -lh /data/redis/6379
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 set aof:test 1` | 写入一条测试数据，后面用来验证 AOF。 | 新增或覆盖 key `aof:test`。 |
| `redis-cli -a Redis@123456 bgrewriteaof` | 手动触发 AOF 重写。 | Redis 后台生成更紧凑的 AOF 文件；会产生临时文件和磁盘 IO。 |
| `redis-cli -a Redis@123456 info persistence` | 查看 AOF 是否开启、重写是否成功、是否有错误。 | 只读查询，重点看 `aof_enabled`、`aof_rewrite_in_progress`、`aof_last_bgrewrite_status`。 |
| `ls -lh /data/redis/6379` | 查看 AOF 文件或目录是否生成。 | Redis 7 可能看到 `appendonlydir`。 |

Redis 7 的 AOF 可能会生成 `appendonlydir` 目录，这是正常现象。

## 15. 常用管理命令

服务管理：

```bash
sudo systemctl start redis-6379
sudo systemctl stop redis-6379
sudo systemctl restart redis-6379
sudo systemctl status redis-6379
sudo systemctl enable redis-6379
sudo systemctl disable redis-6379
```

命令说明：

| 命令 | 作用 | 执行后的影响 |
| --- | --- | --- |
| `sudo systemctl start redis-6379` | 启动 Redis 服务。 | Redis 进程启动并监听端口。 |
| `sudo systemctl stop redis-6379` | 停止 Redis 服务。 | Redis 进程退出，客户端无法连接。 |
| `sudo systemctl restart redis-6379` | 重启 Redis 服务。 | 先停止再启动，常用于配置修改后生效。 |
| `sudo systemctl status redis-6379` | 查看服务状态。 | 显示是否运行、进程号、最近日志。 |
| `sudo systemctl enable redis-6379` | 设置开机自启。 | 下次系统启动时自动启动 Redis。 |
| `sudo systemctl disable redis-6379` | 取消开机自启。 | 下次系统启动时不会自动启动 Redis，但不会停止当前正在运行的服务。 |

日志查看：

```bash
sudo journalctl -u redis-6379 -f
sudo tail -f /var/log/redis/redis-6379.log
```

命令说明：

| 命令 | 用来查看什么 | 使用场景 |
| --- | --- | --- |
| `sudo journalctl -u redis-6379 -f` | 实时跟踪 systemd 里的 Redis 服务日志。 | 看服务启动、停止、崩溃、重启等 systemd 层面的事件。 |
| `sudo tail -f /var/log/redis/redis-6379.log` | 实时跟踪 Redis 自己的日志文件。 | 看 Redis 配置、持久化、客户端、错误等 Redis 层面的信息。 |

基础状态：

```bash
redis-cli -a Redis@123456 info server
redis-cli -a Redis@123456 info clients
redis-cli -a Redis@123456 info memory
redis-cli -a Redis@123456 info persistence
redis-cli -a Redis@123456 info stats
```

命令说明：

| 命令 | 查看内容 | 用途 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 info server` | Redis 版本、运行模式、进程、系统信息。 | 确认服务端基础信息。 |
| `redis-cli -a Redis@123456 info clients` | 客户端连接数量和阻塞情况。 | 排查连接数、客户端积压。 |
| `redis-cli -a Redis@123456 info memory` | 内存使用、峰值、碎片率、淘汰策略。 | 排查内存是否接近 `maxmemory`。 |
| `redis-cli -a Redis@123456 info persistence` | RDB/AOF 状态和最近错误。 | 排查持久化是否成功。 |
| `redis-cli -a Redis@123456 info stats` | 命令数、连接数、命中率、网络流量等统计。 | 观察 Redis 使用情况。 |

慢查询：

```bash
redis-cli -a Redis@123456 slowlog get 10
redis-cli -a Redis@123456 slowlog len
```

命令说明：

| 命令 | 查看内容 | 用途 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 slowlog get 10` | 获取最近 10 条慢查询记录。 | 分析哪些命令执行时间过长。 |
| `redis-cli -a Redis@123456 slowlog len` | 查看当前慢查询日志条数。 | 判断是否持续产生慢查询。 |

延迟：

```bash
redis-cli -a Redis@123456 latency latest
redis-cli -a Redis@123456 latency doctor
```

命令说明：

| 命令 | 查看内容 | 用途 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 latency latest` | 查看最近记录到的延迟事件。 | 快速确认是否有延迟尖刺。 |
| `redis-cli -a Redis@123456 latency doctor` | 让 Redis 给出延迟诊断建议。 | 学习定位 fork、AOF、命令阻塞等延迟来源。 |

## 16. 建议做的单机实验

### 16.1 配置变更实验

修改 `/etc/redis/redis-6379.conf` 中的 `maxmemory`，然后重启：

```bash
sudo systemctl restart redis-6379
redis-cli -a Redis@123456 config get maxmemory
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `sudo systemctl restart redis-6379` | 让配置文件里的 `maxmemory` 修改重新加载。 | Redis 短暂重启。 |
| `redis-cli -a Redis@123456 config get maxmemory` | 查看 Redis 当前运行时实际生效的 `maxmemory`。 | 输出应和你修改后的配置一致。 |

目的：理解配置文件、启动参数和运行时配置的关系。

### 16.2 密码验证实验

不带密码执行：

```bash
redis-cli ping
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `redis-cli ping` | 故意不带密码访问，验证 Redis 认证是否生效。 | 应提示需要认证，不能返回正常 `PONG`。 |

带密码执行：

```bash
redis-cli -a Redis@123456 ping
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 ping` | 带密码访问 Redis，验证正确认证后可以执行命令。 | 返回 `PONG`。 |

目的：理解 Redis 认证机制。

### 16.3 数据恢复实验

写入数据后重启：

```bash
redis-cli -a Redis@123456 set recover:test ok
sudo systemctl restart redis-6379
redis-cli -a Redis@123456 get recover:test
```

命令说明：

| 命令 | 为什么执行 | 正常结果 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 set recover:test ok` | 写入恢复测试数据。 | 新增或覆盖 key `recover:test`。 |
| `sudo systemctl restart redis-6379` | 重启 Redis，模拟服务重启。 | Redis 重新从 RDB/AOF 加载数据。 |
| `redis-cli -a Redis@123456 get recover:test` | 查看重启后数据是否还存在。 | 应返回 `ok`。 |

目的：验证 RDB/AOF 生效。

### 16.4 慢查询实验

临时调整慢查询阈值：

```bash
redis-cli -a Redis@123456 config set slowlog-log-slower-than 0
redis-cli -a Redis@123456 set slowlog:test 1
redis-cli -a Redis@123456 slowlog get 5
redis-cli -a Redis@123456 config set slowlog-log-slower-than 10000
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `redis-cli -a Redis@123456 config set slowlog-log-slower-than 0` | 临时把慢查询阈值设为 0，让后续所有命令都进入慢查询日志。 | 只修改运行时配置，不会写回配置文件；用于实验。 |
| `redis-cli -a Redis@123456 set slowlog:test 1` | 执行一条普通写命令，用来制造慢查询记录。 | 写入 key `slowlog:test`，同时因为阈值为 0 会被记录。 |
| `redis-cli -a Redis@123456 slowlog get 5` | 查看最近 5 条慢查询记录。 | 应能看到刚才的 `set` 或相关命令。 |
| `redis-cli -a Redis@123456 config set slowlog-log-slower-than 10000` | 把慢查询阈值恢复为 10ms。 | 避免后续所有命令都进入慢查询日志，减少噪音。 |

目的：知道慢查询日志如何产生和查看。

## 17. 常见问题

### 17.1 netplan apply 后网络断了

先回到 VMware 控制台检查：

```bash
cat /etc/netplan/00-installer-config.yaml
ip addr
ip route
```

命令说明：

| 命令 | 排查什么 | 重点看什么 |
| --- | --- | --- |
| `cat /etc/netplan/00-installer-config.yaml` | 查看实际保存的 netplan 配置。 | 网卡名、IP、网关、DNS、YAML 缩进是否正确。 |
| `ip addr` | 查看当前系统实际拿到的 IP。 | 是否有 `192.168.88.101/24`，网卡是否是 `UP`。 |
| `ip route` | 查看当前路由表。 | 默认路由是否指向 `192.168.88.2`。 |

高频原因：

- 网卡名不是 `ens33`。
- `addresses` 不在 VMnet8 网段。
- `via` 不是 VMware NAT 实际网关。
- YAML 缩进错误。

### 17.2 宿主机连不上 Redis

按顺序排查：

```bash
ip addr
ping 192.168.88.101
sudo ss -lntp | grep 6379
sudo ufw status
sudo systemctl status redis-6379
```

命令说明：

| 命令 | 排查什么 | 重点看什么 |
| --- | --- | --- |
| `ip addr` | 虚拟机 IP 是否正确。 | 是否是 `192.168.88.101/24`，是否在 VMnet8 网段。 |
| `ping 192.168.88.101` | 从当前机器测试到 Redis 虚拟机 IP 是否能通。 | 如果不通，优先查 VMware 网络、静态 IP 和网关。 |
| `sudo ss -lntp | grep 6379` | Redis 是否监听 6379 端口。 | 是否监听 `0.0.0.0:6379`；如果只有 `127.0.0.1:6379`，宿主机连不上。 |
| `sudo ufw status` | 防火墙是否拦截 6379。 | `active` 时要确认是否有 `6379/tcp ALLOW`。 |
| `sudo systemctl status redis-6379` | Redis 服务是否正常运行。 | 是否 `active (running)`，失败时看错误摘要。 |

常见原因：

- 虚拟机没有接到 `VMnet8 NAT`。
- 虚拟机 IP 不是 `192.168.88.101`。
- Redis 只绑定了 `127.0.0.1`。
- `ufw` 没放行 `6379`。
- Redis 密码没带或密码错误。

### 17.3 Redis 启动失败

优先看：

```bash
sudo systemctl status redis-6379
sudo journalctl -u redis-6379 -n 100 --no-pager
sudo tail -n 100 /var/log/redis/redis-6379.log
```

命令说明：

| 命令 | 排查什么 | 重点看什么 |
| --- | --- | --- |
| `sudo systemctl status redis-6379` | 看 systemd 对 Redis 服务的状态判断。 | 是否启动失败、退出码是什么、是否超时。 |
| `sudo journalctl -u redis-6379 -n 100 --no-pager` | 看 systemd 记录的最近 100 行 Redis 服务日志。 | `Permission denied`、`timeout`、`Main process exited` 等信息。 |
| `sudo tail -n 100 /var/log/redis/redis-6379.log` | 看 Redis 自己输出的最近 100 行日志。 | 配置错误、端口占用、RDB/AOF 文件错误、目录权限错误。 |

常见原因：

- 配置文件语法错误。
- 数据目录权限不对。
- 日志目录权限不对。
- 端口被占用。
- `supervised` 配置和 systemd 服务类型不匹配。

### 17.4 redis-cli 提示 AUTH 警告

使用 `-a` 会提示密码暴露风险，这是正常警告。

可以使用环境变量减少命令行暴露：

```bash
export REDISCLI_AUTH='Redis@123456'
redis-cli ping
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `export REDISCLI_AUTH='Redis@123456'` | 把 Redis 密码放到当前 shell 环境变量中，避免每条命令都写 `-a`。 | 只对当前终端会话生效；后续 `redis-cli` 会自动使用这个密码。 |
| `redis-cli ping` | 不在命令行显式写密码，验证环境变量认证是否生效。 | 正常返回 `PONG`。 |

### 17.5 make test 出现 Active defrag 失败

如果执行 `make test` 时出现类似错误：

```text
*** [err]: Active defrag - AOF loading in tests/unit/memefficiency.tcl
Expected 66 <= 40
make[1]: *** [Makefile:462: test] Error 1
make: *** [Makefile:6: test] Error 2
```

这通常不是 Redis 编译失败，而是 Redis 自带测试里的延迟敏感用例在虚拟机中超时。VMware 学习环境 CPU、磁盘 IO、内存分配和宿主机负载都会影响这类测试。

先确认 Redis 二进制是否已经编译出来：

```bash
cd /usr/local/src/redis-7.2.5
src/redis-server --version
src/redis-cli --version
```

命令说明：

| 命令 | 排查什么 | 正常结果 |
| --- | --- | --- |
| `cd /usr/local/src/redis-7.2.5` | 进入 Redis 源码目录。 | 后续能访问 `src/redis-server` 和 `src/redis-cli`。 |
| `src/redis-server --version` | 确认服务端二进制是否已经编译出来。 | 能输出 Redis 版本，说明编译产物存在。 |
| `src/redis-cli --version` | 确认客户端二进制是否已经编译出来。 | 能输出 `redis-cli` 版本。 |

如果版本能正常输出，可以继续安装：

```bash
sudo make install PREFIX=/usr/local/redis
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo make install PREFIX=/usr/local/redis` | 在确认编译产物可用后，把 Redis 安装到正式目录。 | 更新 `/usr/local/redis/bin` 下的 Redis 程序。 |

安装后再验证：

```bash
/usr/local/redis/bin/redis-server --version
/usr/local/redis/bin/redis-cli --version
```

命令说明：

| 命令 | 用来确认什么 | 正常结果 |
| --- | --- | --- |
| `/usr/local/redis/bin/redis-server --version` | 确认正式安装目录里的 Redis 服务端版本。 | 输出版本正常。 |
| `/usr/local/redis/bin/redis-cli --version` | 确认正式安装目录里的 Redis 客户端版本。 | 输出版本正常。 |

处理原则：

- `make` 失败：不能继续，说明编译没有通过。
- `make test` 失败：先看失败原因；如果是虚拟机中的延迟测试失败，学习环境可以继续安装。
- 生产编译或正式交付环境：应该换更稳定的机器重新跑完整测试。

### 17.6 systemd 启动提示未编译 systemd 支持

如果执行启动命令时出现超时：

```text
Job for redis-6379.service failed because a timeout was exceeded.
```

同时 Redis 日志里出现类似信息：

```text
systemd supervision requested, but Redis was compiled without libsystemd support
```

说明 Redis 实际已经启动了，但 systemd 服务使用了 `Type=notify`，正在等待 Redis 发送启动完成通知。当前 Redis 编译时没有启用 systemd 支持，所以不会发送通知，最终 systemd 等待超时并终止 Redis。

这个问题通常由下面两处配置共同触发：

```conf
supervised systemd
```

```ini
Type=notify
```

处理方式是安装 `libsystemd-dev` 后重新编译：

```bash
sudo systemctl stop redis-6379
sudo systemctl reset-failed redis-6379
sudo apt update
sudo apt install -y libsystemd-dev
cd /usr/local/src
sudo chown -R $(id -u):$(id -g) redis-7.2.5
cd /usr/local/src/redis-7.2.5
make distclean
make USE_SYSTEMD=yes -j2
sudo make install PREFIX=/usr/local/redis
sudo ln -sf /usr/local/redis/bin/redis-server /usr/local/bin/redis-server
sudo ln -sf /usr/local/redis/bin/redis-cli /usr/local/bin/redis-cli
sudo ln -sf /usr/local/redis/bin/redis-benchmark /usr/local/bin/redis-benchmark
sudo systemctl daemon-reload
sudo systemctl restart redis-6379
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo systemctl stop redis-6379` | 先停止当前可能异常的 Redis 服务。 | Redis 停止监听端口。 |
| `sudo systemctl reset-failed redis-6379` | 清除 systemd 里该服务的失败状态。 | 状态计数重置，便于重新启动观察。 |
| `sudo apt update` | 刷新软件源索引。 | 不安装软件，只更新索引。 |
| `sudo apt install -y libsystemd-dev` | 安装 Redis 编译 systemd 支持所需头文件。 | 后续 `make USE_SYSTEMD=yes` 才能真正链接 systemd。 |
| `cd /usr/local/src` | 进入源码父目录。 | 准备处理源码目录权限。 |
| `sudo chown -R $(id -u):$(id -g) redis-7.2.5` | 修复源码目录所有权，避免普通用户重新编译失败。 | 当前登录用户获得源码目录写权限。 |
| `cd /usr/local/src/redis-7.2.5` | 进入 Redis 源码目录。 | 后续 `make` 命令在正确目录执行。 |
| `make distclean` | 清理旧编译产物和配置缓存。 | 源码目录回到更干净的编译状态；避免沿用之前未启用 systemd 的产物。 |
| `make USE_SYSTEMD=yes -j2` | 重新编译 Redis，并启用 systemd 支持。 | 生成带 libsystemd 支持的 Redis 二进制。 |
| `sudo make install PREFIX=/usr/local/redis` | 把重新编译后的 Redis 安装到正式目录。 | 覆盖 `/usr/local/redis/bin` 下的 Redis 程序。 |
| `sudo ln -sf ...` | 更新全局命令软链接。 | 确保直接执行的 `redis-server`、`redis-cli` 指向正式安装目录。 |
| `sudo systemctl daemon-reload` | 重新加载 systemd 服务定义。 | systemd 重新识别服务文件。 |
| `sudo systemctl restart redis-6379` | 用新二进制重启 Redis 服务。 | 如果 systemd 支持正确，服务应正常启动。 |

如果你确认已经重新编译过，但 systemd 仍然超时，按下面顺序验证到底是哪一个二进制有问题：

```bash
cd /usr/local/src/redis-7.2.5
ldd src/redis-server | grep systemd || echo "src/redis-server 未链接 libsystemd"
ldd /usr/local/redis/bin/redis-server | grep systemd || echo "/usr/local/redis/bin/redis-server 未链接 libsystemd"
sudo systemctl cat redis-6379
```

命令说明：

| 命令 | 排查什么 | 重点看什么 |
| --- | --- | --- |
| `cd /usr/local/src/redis-7.2.5` | 进入源码目录。 | 后续检查源码目录里的二进制。 |
| `ldd src/redis-server | grep systemd || echo "src/redis-server 未链接 libsystemd"` | 检查源码目录编译产物是否链接了 libsystemd。 | 如果输出包含 `libsystemd`，说明源码产物有 systemd 支持。 |
| `ldd /usr/local/redis/bin/redis-server | grep systemd || echo "/usr/local/redis/bin/redis-server 未链接 libsystemd"` | 检查正式安装目录里的 Redis 是否链接了 libsystemd。 | 如果源码有但安装目录没有，说明忘了重新 `make install`。 |
| `sudo systemctl cat redis-6379` | 查看 systemd 实际加载的服务文件内容。 | 确认 `ExecStart` 是否指向 `/usr/local/redis/bin/redis-server`。 |

判断方式：

- `src/redis-server` 没有 `libsystemd`：说明源码目录没有用 `make USE_SYSTEMD=yes` 成功编译。
- `src/redis-server` 有 `libsystemd`，但 `/usr/local/redis/bin/redis-server` 没有：说明只编译了源码目录，没有重新 `sudo make install PREFIX=/usr/local/redis`。
- 两个都有 `libsystemd`，但仍然超时：检查 `sudo systemctl cat redis-6379` 里的 `ExecStart` 是否指向 `/usr/local/redis/bin/redis-server`。

如果你已经按本文安装过 `libsystemd-dev` 并使用 `make USE_SYSTEMD=yes -j2` 编译，正常不会遇到这个问题。

### 17.7 make 出现 Permission denied

如果编译或测试时出现类似错误：

```text
sh: 1: cannot create foo.c: Permission denied
rm: cannot remove 'redis-server': Permission denied
can't create directory "./tests/tmp/server.xxxxx": permission denied
```

说明 Redis 源码目录或之前的编译产物属于 `root`，当前登录用户没有写权限。常见原因是之前执行过 `sudo tar`、`sudo make` 或用 `root` 编译过。

处理方式：

```bash
cd /usr/local/src
sudo chown -R $(id -u):$(id -g) redis-7.2.5
cd redis-7.2.5
make distclean
make USE_SYSTEMD=yes -j2
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `cd /usr/local/src` | 进入源码父目录。 | 准备修复源码目录权限。 |
| `sudo chown -R $(id -u):$(id -g) redis-7.2.5` | 把源码目录归还给当前登录用户。 | 当前用户可以清理、编译、测试源码。 |
| `cd redis-7.2.5` | 进入 Redis 源码目录。 | 后续 `make` 在正确目录执行。 |
| `make distclean` | 清理可能属于 root 的旧编译产物。 | 删除旧构建文件，减少权限污染和缓存影响。 |
| `make USE_SYSTEMD=yes -j2` | 用普通用户重新编译 Redis。 | 生成新的 Redis 编译产物。 |

如果还要重新执行测试：

```bash
make test
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `make test` | 用普通用户重新跑 Redis 自带测试。 | 会启动临时测试进程；如果仍然是虚拟机延迟敏感用例失败，可按前文原则判断是否继续。 |

测试通过或确认只是虚拟机延迟测试失败后，再安装：

```bash
sudo make install PREFIX=/usr/local/redis
```

命令说明：

| 命令 | 为什么执行 | 执行后的影响 |
| --- | --- | --- |
| `sudo make install PREFIX=/usr/local/redis` | 编译和测试确认后，把 Redis 安装到系统目录。 | 更新 `/usr/local/redis/bin`；安装阶段使用 `sudo` 是合理的。 |

原则：

- 源码编译和测试用普通登录用户执行。
- 只有安装到 `/usr/local/redis` 时才使用 `sudo make install`。
- 不建议使用 `sudo make` 或 `sudo make test`，否则容易留下 root-owned 编译产物。

## 18. 本阶段完成标准

完成本文后，你应该做到：

1. 能用 Ubuntu 22.04.5 创建模板机。
2. 能从模板机克隆出 `redis-single-1`。
3. 能给 `redis-single-1` 配置静态 IP `192.168.88.101/24`。
4. 能解释 `addresses` 和 `routes.via` 为什么这样配置。
5. 能从源码安装 Redis。
6. 能解释 `/etc/redis`、`/data/redis/6379`、`/var/log/redis` 分别做什么。
7. 能用 `systemctl` 管理 Redis。
8. 能从宿主机连接虚拟机 Redis。
9. 能验证 RDB 和 AOF 文件生成。
10. 能排查 Redis 启动失败和远程连接失败。

达到这个状态后，再进入主从复制和 Sentinel，会更顺。
