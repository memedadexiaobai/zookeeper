# 第七篇｜ZK 3.7 Leader选举（下）：源码级投票逻辑与过半确认机制
大家好，上一篇我们讲透了Leader选举的核心概念、投票数据结构和整体流程，这一篇直接扎进源码——逐行解析`FastLeaderElection`的核心方法`lookForLeader`，讲清楚投票比较、过半统计、选举结束的完整逻辑，让你不仅能看懂源码，还能回答面试中“ZK选举为什么要过半？”“如何避免脑裂？”等深度问题。

---

## 一、核心方法：lookForLeader（选举的主逻辑）
`lookForLeader`是`FastLeaderElection`的核心方法，所有选举逻辑都集中在这里，源码位置：`zookeeper-server/src/main/java/org/apache/zookeeper/server/quorum/FastLeaderElection.java`

### 1. 方法整体结构
```java
public Vote lookForLeader() throws InterruptedException {
    // 1. 初始化：设置本地投票为自己，清空接收的投票
    Vote myVote = new Vote(self.getId(), self.getLastLoggedZxid(), self.getCurrentEpoch());
    currentVote = myVote;
    recvVotes.clear();
    // 2. 向所有节点发送初始投票
    sendVote(myVote);
    
    // 3. 循环等待，直到选出Leader
    while (!finished) {
        // 3.1 接收其他节点的投票（阻塞，超时时间1000ms）
        Message msg = recvQueue.poll(1000, TimeUnit.MILLISECONDS);
        
        if (msg == null) {
            // 3.2 超时：重新发送本地投票，避免其他节点收不到
            sendVote(currentVote);
            continue;
        }
        
        // 3.3 处理收到的投票
        processMsg(msg);
        
        // 3.4 统计投票，判断是否过半
        Vote winner = getVoteWinner();
        if (winner != null) {
            // 3.5 选举成功，结束循环
            finished = true;
            return winner;
        }
    }
    
    // 选举失败（理论上不会走到这里）
    return null;
}
```

**关键解读**：
- 整个选举是一个“循环-接收-处理-统计”的过程，直到选出过半的Leader；
- 超时重发机制：如果1秒内没收到其他节点的投票，会重新发送本地投票，保证投票能被其他节点接收；
- `recvQueue`是投票接收队列，`WorkerReceiver`线程接收的投票会放入这个队列，由`lookForLeader`处理。

### 2. 核心子方法：processMsg（处理收到的投票）
```java
private void processMsg(Message msg) {
    long senderId = msg.getSenderId(); // 发送节点的myid
    Vote recvVote = msg.getVote();     // 收到的投票
    
    // 1. 如果发送节点不是LOOKING状态，跳过（已退出选举）
    if (msg.getType() != MessageType.VOTE) {
        return;
    }
    
    // 2. 比较本地投票和接收的投票，选择更优的
    if (recvVote.isBetterThan(currentVote)) {
        // 2.1 接收的投票更优，更新本地投票
        currentVote = recvVote;
        // 2.2 广播新的本地投票（让其他节点知道我的选择）
        sendVote(currentVote);
    }
    
    // 3. 记录该节点的投票（用于统计过半）
    recvVotes.put(senderId, recvVote);
}
```

**核心逻辑拆解**：
- 第一步：过滤非投票消息（如节点已选完Leader，发送的确认消息）；
- 第二步：用`isBetterThan`方法比较投票（先比epoch→再比zxid→最后比myid），如果收到的投票更优，就更新本地投票并重新广播；
- 第三步：记录每个节点的投票，为后续“过半统计”做准备。

### 3. 核心子方法：getVoteWinner（统计投票，判断过半）
```java
private Vote getVoteWinner() {
    // 1. 统计每个投票的支持数（key=投票，value=支持节点数）
    Map<Vote, Integer> voteCount = new HashMap<>();
    // 加入本地投票
    voteCount.put(currentVote, voteCount.getOrDefault(currentVote, 0) + 1);
    // 加入其他节点的投票
    for (Vote v : recvVotes.values()) {
        voteCount.put(v, voteCount.getOrDefault(v, 0) + 1);
    }
    
    // 2. 计算集群过半阈值（n/2 + 1）
    int quorumSize = self.getQuorumSize(); // 过半阈值，如3节点=2，5节点=3
    
    // 3. 遍历统计结果，找是否有投票达到过半
    for (Map.Entry<Vote, Integer> entry : voteCount.entrySet()) {
        if (entry.getValue() >= quorumSize) {
            // 3.1 过半，返回该投票（Leader）
            return entry.getKey();
        }
    }
    
    // 3.2 未过半，返回null，继续选举
    return null;
}
```

**关键解读**：
- `getQuorumSize()`是核心方法，返回值为`(节点数/2) + 1`（整数除法），比如3节点集群返回2，5节点返回3；
- 统计时会包含本地投票+其他节点的投票，只有支持数≥过半阈值，才会确定Leader；
- 这一步是“避免脑裂”的核心：即使网络分区，只有拿到过半投票的节点才能成为Leader，保证集群只有一个Leader。

---

## 二、过半机制的底层逻辑（面试必答）
很多面试会问：“ZK选举为什么要过半？”“为什么集群节点数要设为奇数（3/5/7）？”，核心原因有两个：

### 1. 避免脑裂（核心目的）
假设3节点集群（A、B、C），网络分区成两组：A一组，B+C一组。
- 如果没有过半机制：A可能选自己为Leader，B+C也可能选B为Leader，出现两个Leader（脑裂），写请求会被分流，导致数据不一致；
- 有过半机制：A只有1票（不足2票），无法成为Leader；B+C有2票（过半），选B为Leader，集群只有一个合法Leader。

### 2. 保证性能与容错的平衡
| 集群节点数 | 过半阈值 | 容错数（最多挂几个节点） | 选举效率 |
|------------|----------|--------------------------|----------|
| 1（单机）  | 1        | 0（挂了就不可用）| 最快     |
| 3          | 2        | 1                        | 较快     |
| 5          | 3        | 2                        | 中等     |
| 7          | 4        | 3                        | 较慢     |

- 奇数节点的优势：容错数相同的情况下，奇数节点比偶数节点更高效（如4节点和3节点的容错数都是1，但4节点的过半阈值是3，选举更慢）；
- 生产建议：中小集群用3节点，大集群用5节点，7节点以上建议拆分（选举耗时过长）。

---

## 三、选举结束后的状态切换
当`lookForLeader`返回winner后，节点会根据自己是否是Leader切换状态：

```java
// 选举结束后，QuorumPeer的状态切换逻辑
private void setPeerState(Vote winner) {
    if (winner.getId() == self.getId()) {
        // 自己是Leader，切换为LEADING状态
        self.setPeerState(QuorumPeer.ServerState.LEADING);
        // 启动Leader相关线程（广播线程、同步线程）
        startLeaderServices();
    } else {
        // 自己是Follower，切换为FOLLOWING状态
        self.setPeerState(QuorumPeer.ServerState.FOLLOWING);
        // 连接Leader，同步数据
        connectToLeader(winner.getId());
    }
}
```

**关键解读**：
- Leader节点启动后，会监听2888端口（数据同步）和3888端口（选举备用），并启动广播线程处理写请求；
- Follower节点会主动连接Leader的2888端口，同步Leader的事务日志，保证数据一致性。

---

## 四、源码级调优（生产实战）
基于选举源码，可针对性优化选举效率，解决生产中“选举耗时过长”的问题：

| 优化点 | 源码对应逻辑 | 优化方法 |
|--------|--------------|----------|
| 投票超时时间 | `lookForLeader`中的1000ms超时 | 生产环境可调小至500ms（加快超时重发），但不宜过小（避免网络抖动导致频繁重发） |
| 投票发送频率 | `WorkerSender`的1秒休眠 | 选举期间临时改为500ms发送一次，选举结束后恢复 |
| zxid读取速度 | 初始化投票时读取`lastLoggedZxid` | 将事务日志目录放在SSD，减少zxid读取耗时 |

---

## 五、常见面试题（源码级答案）
结合本篇源码，给你面试中高频问题的标准答案：

### 问题1：ZK选举的比较规则是什么？
答：核心规则是“先比epoch（选举轮次），再比zxid（数据最新），最后比myid（节点ID）”。源码中通过`Vote.isBetterThan`方法实现，epoch越大表示选举轮次越新，zxid越大表示数据越全，myid是最后兜底的比较项。

### 问题2：为什么ZK选举需要过半确认？
答：核心是为了避免脑裂，保证集群只有一个合法Leader。源码中通过`getQuorumSize()`计算过半阈值（n/2+1），只有支持数≥阈值的投票才会被选为Leader，即使网络分区，也只会有一个分区满足过半条件，避免多个Leader出现。

### 问题3：3节点集群，挂了1个节点，还能选举吗？还能写数据吗？
答：可以。3节点的过半阈值是2，挂了1个还剩2个节点，能选出Leader；Leader节点能收到1个Follower的确认（自己+1个Follower=2票），满足过半写机制，所以能处理写请求。

---

## 六、写在最后
这两篇我们彻底啃透了ZK的Leader选举——从核心概念到源码逻辑，从过半机制到生产调优。下一篇我们会进入ZK分布式一致性的核心：**ZAB协议**，讲清楚Leader如何通过ZAB协议保证数据同步、处理崩溃恢复，这是ZK能保证强一致性的底层逻辑。

如果这篇内容帮你理清了选举的源码逻辑，欢迎点赞、收藏、关注，后续我们会一步步啃透ZK的每一个核心模块！

### CSDN专属标签
#ZooKeeper3.7 #ZK选举源码 #过半机制 #FastLeaderElection #分布式一致性 #中间件面试

---

### 小预告
下一篇内容：《第八篇｜ZK 3.7 ZAB协议（上）：崩溃恢复阶段（源码解析）》，会重点讲解ZAB协议的两个阶段、崩溃恢复的核心逻辑、epoch机制，提前可以先在IDEA中打开`Leader`、`Follower`类熟悉一下~