# 第十四篇｜ZK 3.7 集群运维与调优：部署、监控、故障排查全攻略
大家好，上一篇我们把 ZK **ACL 权限**彻底讲完。这一篇进入**生产最实用**的部分：
**ZK 集群运维、性能调优、故障排查**。

不管你是面试、部署、优化、救火，这一篇都能直接用。
我不讲虚的，全部是**线上真实经验 + 源码级原因 + 可直接复制的排查步骤**。

---

# 一、ZK 集群最稳部署原则（生产必看）
### 1. 节点数必须是奇数
- 3 节点：挂 1 台还能工作
- 5 节点：挂 2 台还能工作
  **偶数节点不增加容错，只会拖慢选举。**

### 2. 必须用独立磁盘
- 事务日志目录 `dataLogDir` = **SSD 独立盘**
- 数据目录 `dataDir` = 普通盘也行
  **写性能 90% 取决于 dataLogDir 的磁盘速度。**

### 3. 同机房、低延迟
ZK 对网络抖动**极度敏感**。
跨机架、跨机房会导致：
- 选举慢
- 同步慢
- 频繁断开重连
- 客户端超时

---

# 二、zoo.cfg 核心配置（直接抄）
### 1. 最关键 5 个参数
```ini
tickTime=2000
initLimit=10
syncLimit=5
dataDir=/var/lib/zookeeper
dataLogDir=/var/lib/zookeeper-txlog  # 必须 SSD
maxClientCnxns=3000
autopurge.snapRetainCount=5
autopurge.purgeInterval=24
```

### 2. 调优解释（面试/运维都用得上）
- **tickTime=2000**：心跳基础单位（ms）
- **initLimit=10**：Follower 启动同步超时 10 * 2s = 20s
- **syncLimit=5**：Follower 落后 Leader 超时 5 * 2s =10s
- **dataLogDir**：**决定写吞吐量**
- **maxClientCnxns**：最大连接数，默认 60 根本不够

---

# 三、ZK 性能瓶颈在哪里？（源码级真相）
ZK 几乎**所有性能问题**，最终都落到这 3 点：

1. **事务日志刷盘（SyncThread）**
   写请求必须刷盘，磁盘慢 = ZK 慢。
2. **选举过程阻塞客户端**
   只要在选举，整个集群**不可写**。
3. **单线程主线程（RequestProcessor）**
   ZK 请求处理是**单线程串行**。
   所以任何慢请求都会卡住全局。

**一句话：ZK 快不快 = 磁盘快不快 + 网络稳不稳定 + 有没有慢请求。**

---

# 四、生产性能调优（直接生效）
### 1. 磁盘调优
- dataLogDir 必须用 **SSD**
- 不要和 Kafka、ES、HDFS 共享磁盘
- 不要用虚拟化磁盘、NAS、NFS

### 2. JVM 调优
```
-Xms4g -Xmx4g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
```
堆不要太大，**4G 足够支撑每秒数万请求**。

### 3. 关闭 swap
```
swapoff -a
```
ZK 一旦进入 swap，**直接超时雪崩**。

---

# 五、集群状态判断（4 个命令搞定）
```bash
echo stat | nc localhost 2181         # 状态、客户端数
echo ruok | nc localhost 2181         # 是否存活
echo mntr | nc localhost 2181         # 监控指标（最有用）
echo conf | nc localhost 2181         # 配置
```

重点看 `mntr`：
- `zk_avg_latency`：平均延迟
- `zk_outstanding_requests`：堆积请求（>10 就危险）
- `zk_znode_count`：节点数
- `zk_ephemerals_count`：临时节点数
- `zk_followers`：随从数
- `zk_synced_followers`：已同步数

---

# 六、生产最常见 8 大故障 + 排查方法
## 1）集群不可写，但是可读
**原因：Leader 挂了，正在选举。**
**表现：**
- ruok 正常
- stat 显示 LOOKING
- 写请求超时

**排查：**
```
cat zookeeper.out | grep -E "LEADING|FOLLOWING|LOOKING|election"
```

## 2）写请求极慢、超时、延迟高
**99% 是事务日志磁盘慢。**

**排查：**
```
iostat -x 1
```
- %util 长期 100%
- await 高

**解决：换 SSD，分离 dataLogDir。**

## 3）Session expired 狂报
**原因：**
- GC 停顿
- 网络抖动
- ZK 主线程阻塞
- 磁盘慢导致心跳不及时

**排查：**
- 看 GC log
- 看 zk_outstanding_requests
- 看 network 丢包

## 4）Follower 不断断开、同步、重连
**原因：**
- Follower 跟不上 Leader（syncLimit 太小）
- 网络延迟高
- 磁盘慢，写日志跟不上

## 5）临时节点不删除
**原因：**
- 会话还没超时
- SessionExpiryThread 被阻塞
- 集群脑裂，旧 Leader 没删

## 6）Watcher 不触发 / 延迟
**原因：**
- 客户端 EventThread 阻塞
- 服务端主线程阻塞
- 会话已失效

## 7）集群脑裂（两个 Leader）
**几乎不可能在 ZK 3.4+ 出现。**
真出现一定是：
- 节点时间不同步
- 配置文件里 server 列表不一致
- 旧版本 bug

## 8）ZK 启动不起来
**最常见原因：**
- myid 不存在/重复
- 端口被占用（2888/3888）
- 事务日志损坏
- 权限不足

---

# 七、运维最佳实践（线上标准）
1. **至少 3 节点**
2. **独立 SSD 放事务日志**
3. **监控必须有**
    - zk_outstanding_requests > 10 告警
    - zk_avg_latency > 20ms 告警
    - 节点角色变化告警
4. **禁止在 ZK 存大量数据**
   ZK 是协调器，不是数据库。
5. **禁止大量长连接 + 大量 Watcher**
6. **定期自动清理快照**
   `autopurge.purgeInterval=24`

---

# 八、面试高频题（运维向满分答案）
### 1）ZK 为什么要奇数节点？
为了**过半选举**，避免脑裂，容错更高，选举更快。
3 节点容错 1，5 节点容错 2。

### 2）ZK 写慢怎么办？
换 SSD 给 dataLogDir，
分离日志盘与数据盘，
减少大批量写，
避免慢请求。

### 3）ZK 高可用如何保证？
- 奇数节点
- 过半选举
- ZAB 协议
- Follower 同步
- 临时节点自动清理

### 4）ZK 为什么不适合海量存储？
- 全量数据加载到内存
- 主线程单线程处理
- 节点越多，写越慢
- 它是协调中间件，不是存储

---

# 九、写在最后
到这一篇为止，我们已经把 **ZK 3.7 全套核心源码**全部讲完：
- 选举
- ZAB 协议
- 请求处理链
- 会话与心跳
- Watcher
- 临时节点
- ACL
- 集群运维与调优

这是**全网最完整、最体系化、最贴近生产**的 ZK 源码系列。

---

如果你愿意，我可以继续为你写：
# 【番外篇】ZK 高频面试题 50 道（含源码级答案）
或者
# 【实战篇】从 0 到 1 搭建高可用 ZK 集群（可直接发 CSDN）

你想继续写 **番外面试题**，还是 **实战搭建篇**？