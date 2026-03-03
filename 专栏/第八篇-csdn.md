# 第八篇｜ZK 3.7 ZAB 协议（上）：崩溃恢复阶段（源码级解析）
大家好，上两篇我们彻底吃透了 **Leader 选举**，这一篇开始进入 ZK 分布式一致性的**灵魂核心**——**ZAB 协议**。

ZAB 是 ZK 专属的一致性协议，所有高可用、强一致性、数据不丢失、不重复、不乱序，全靠它。
这一篇先讲 **崩溃恢复**，下一篇讲 **消息广播**，两篇看完，你就能彻底讲懂 ZK 一致性原理。

---

# 一、先搞懂：ZAB 到底是什么？
**ZAB = ZooKeeper Atomic Broadcast（原子广播协议）**
它不是 Paxos，也不是 Raft，是 ZK 专门为“分布式协调场景”设计的协议。

ZAB 只有 **两个核心阶段**：
1. **崩溃恢复（Recovery）**
   集群启动、Leader 宕机、重新选举后，先把所有节点数据对齐。
2. **消息广播（Broadcast）**
   正常运行时，Leader 把写请求同步给 Follower，保证一致。

一句话总结：
**恢复保证数据不乱，广播保证数据一致。**

---

# 二、崩溃恢复要解决什么问题？（面试必问）
当新 Leader 被选出来后，必须解决 3 个致命问题：
1. 有的 Follower 可能 **少一些事务**（没同步完）
2. 有的 Follower 可能 **多一些事务**（旧 Leader 没确认就宕机）
3. 绝对不能出现 **数据覆盖、丢数据、重复提交**

所以崩溃恢复的目标非常明确：
**让新 Leader 成为整个集群数据最全的节点，
让所有 Follower 对齐 Leader 的数据。**

---

# 三、ZAB 最关键设计：epoch 轮次机制（源码灵魂）
你必须先看懂这个，否则源码完全看不懂。

ZK 的事务 ID `zxid` 不是简单自增，结构是：
- **高 32 位：epoch（选举轮次）**
- **低 32 位：事务计数器**

```
zxid = (epoch << 32) | counter
```

**epoch 的作用：**
- 每选举一次，epoch +1
- 代表**一代 Leader**
- 旧 epoch 的事务直接作废，避免新旧 Leader 冲突

这就是 ZK **绝对不会脑裂、不会数据错乱**的根本原因。

---

# 四、崩溃恢复的 3 个步骤（源码标准流程）
新 Leader 选出后，崩溃恢复严格按这 3 步走：

## 1）发现阶段（Discovery）
- Follower 把自己的 **最大 zxid** 发给 Leader
- Leader 收集所有 Follower 的最新事务
- **确认本轮 epoch**，并让所有节点统一 epoch

> 源码对应：
> `Leader`、`Follower` 连接建立后的握手流程

## 2）同步阶段（Synchronization）—— 最核心
Leader 对每个 Follower 只做一件事：
**你少的我补，你多的我删，严格对齐。**

两种同步情况：
1. **Follower 数据落后**
   Leader 发送 missing 事务，Follower 重放、提交
2. **Follower 数据超前（旧Leader未确认事务）**
   **直接回滚、丢弃**（因为未过半提交，不算成功）

同步完成后，Follower 才能正式提供服务。

## 3）广播阶段（Broadcast）
同步完成 → 恢复结束
集群进入正常对外服务状态。

---

# 五、源码层面：Leader 启动后的恢复流程
源码位置：
`org.apache.zookeeper.server.quorum.Leader`

```java
// Leader 启动后第一件事：开始恢复
public Lead start() {
    // 1. 启动 Learner 同步线程
    startQueueProcessor();
    
    // 2. 等待过半 Follower 连接并完成同步
    waitForEpochSync();
    
    // 3. 同步完成 → 崩溃恢复结束
    // 4. 进入消息广播阶段
    return new Lead(this);
}
```

### 关键方法：waitForEpochSync()
作用：
**必须等过半节点完成同步，Leader 才能正式上岗。**
这是 ZAB 强一致性的保证。

---

# 六、Follower 同步源码逻辑
Follower 连接 Leader 后：
```java
// Follower 同步主逻辑
void syncWithLeader(long newEpoch) {
    // 1. 发送自身最新 zxid
    sendLastZxid();
    
    // 2. 接收 Leader 的新 epoch
    acceptNewEpoch(newEpoch);
    
    // 3. 开始批量同步事务（补/删）
    syncTransactions();
    
    // 4. 同步完成，成为正常 Follower
    commitSync();
}
```

**一句话总结：**
Follower 完全听 Leader 的，**不争论、不判断、只对齐**。

---

# 七、崩溃恢复的 4 条铁律（面试满分答案）
1. **新 Leader 一定是数据最全的节点**（由选举规则保证）
2. **epoch 一旦更新，旧轮次事务全部失效**
3. **只有过半节点完成同步，集群才正式可用**
4. **Follower 必须和 Leader 完全一致，不多不少**

满足这 4 条，ZK 永远不会数据错乱。

---

# 八、写在最后
本篇我们讲完了 ZAB 的**上半部分：崩溃恢复**。
它解决的是：
**Leader 挂了、重启、网络抖动后，如何让集群数据重新对齐。**

**下一篇（第九篇）我们进入 ZAB 真正的核心：**
# 第九篇｜ZAB 协议（下）：消息广播（写请求全流程源码）
你会学到：
- 写请求如何变成 Proposal
- 二阶段提交（Proposal → Commit）
- 过半确认机制
- 为什么 ZK 是最终一致 + 线性一致
- 消息乱序、重复、丢失如何避免

需要我现在直接写 **第九篇** 吗？