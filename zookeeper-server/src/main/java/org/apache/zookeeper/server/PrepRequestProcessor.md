## `traceMask` 作用与设计优势分析

### 🎯 **核心作用**

`traceMask` 是一个**位掩码（bitmask）**，用于**精细化控制不同类型的日志输出**，实现**按需调试**。

---

## 一、设计原理

### 1️⃣ **位掩码定义**

```java
// ZooTrace.java - 每种类型的日志一个独立的 bit
public static final long CLIENT_REQUEST_TRACE_MASK = 1 << 1;      // 0b00000010
public static final long CLIENT_DATA_PACKET_TRACE_MASK = 1 << 2;  // 0b00000100
public static final long CLIENT_PING_TRACE_MASK = 1 << 3;         // 0b00001000
public static final long SERVER_PACKET_TRACE_MASK = 1 << 4;       // 0b00010000
public static final long SESSION_TRACE_MASK = 1 << 5;             // 0b00100000
public static final long EVENT_DELIVERY_TRACE_MASK = 1 << 6;      // 0b01000000
public static final long SERVER_PING_TRACE_MASK = 1 << 7;         // 0b10000000
public static final long WARNING_TRACE_MASK = 1 << 8;             // 0b100000000
public static final long JMX_TRACE_MASK = 1 << 9;                 // 0b1000000000
```


### 2️⃣ **组合使用**

```java
// 默认启用的 trace 类型（通过按位或组合）
private static long traceMask = 
    CLIENT_REQUEST_TRACE_MASK |    // 客户端请求
    SERVER_PACKET_TRACE_MASK |     // 服务器数据包
    SESSION_TRACE_MASK |           // 会话相关
    WARNING_TRACE_MASK;            // 警告信息
```


---

## 二、工作流程

### 📊 **完整流程**

```java
// 1. PrepRequestProcessor 中设置 mask
long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
if (request.type == OpCode.ping) {
    traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;  // Ping 请求用不同的 mask
}

// 2. 检查是否应该输出日志
if (LOG.isTraceEnabled()) {
   ZooTrace.logRequest(LOG, traceMask, 'P', request, "");
}

// 3. logRequest 内部判断
public static void logRequest(Logger log, long mask, char rp, Request request, String header) {
   if (isTraceEnabled(log, mask)) {  // ← 关键判断
       log.trace(header + ":" + rp + request.toString());
    }
}

// 4. isTraceEnabled 核心逻辑
public static synchronized boolean isTraceEnabled(Logger log, long mask) {
   return log.isTraceEnabled() && (mask & traceMask) != 0;  // ← 按位与运算
}
```


---

## 三、设计优势

### ✅ **优势 1：精细化控制（Fine-grained Control）**

#### ❌ **传统方式（粗粒度）**
```properties
# log4j.properties
log4j.rootLogger=TRACE  # 要么全开，要么全关
```

**问题：**
- 所有 TRACE 日志都输出，信息过载
- 想只看 Ping 请求？不可能！
- 日志文件巨大，难以定位问题

#### ✅ **位掩码方式（细粒度）**
```bash
# 只查看客户端请求和警告
echo "set_trace_mask 0x3" | nc localhost 8080
# 0x3 = 0b000000011 = CLIENT_REQUEST | WARNING

# 只查看 Ping 请求
echo "set_trace_mask 0x8" | nc localhost 8080
# 0x8 = 0b000001000 = CLIENT_PING

# 查看所有类型
echo "set_trace_mask 0x3FE" | nc localhost 8080
# 0x3FE = 所有 bit 都设置
```


**好处：**
- ✅ 可以精确控制哪些类型的日志输出
- ✅ 生产环境可以只开启关键日志
- ✅ 调试时可以选择性开启特定模块

---

### ✅ **优势 2：运行时动态调整（Runtime Configuration）**

```java
// 通过四字命令（4-letter word command）动态修改
// 无需重启服务器！

// 场景 1：平时只记录警告
当前 traceMask = WARNING_TRACE_MASK (0x100)

// 场景 2：发现异常，临时开启请求追踪
管理员执行：echo "set_trace_mask 0x102" | nc localhost 8080
立即生效：WARNING + CLIENT_REQUEST

// 场景 3：问题复现后，关闭追踪
管理员执行：echo "set_trace_mask 0x100" | nc localhost 8080
恢复平静：只记录 WARNING
```


**对比传统方式：**
| 特性 | 传统 log4j | traceMask 设计 |
|------|-----------|---------------|
| **修改配置** | 编辑配置文件 | 发送命令 |
| **生效方式** | 需要重启或 reload | 立即生效 |
| **影响范围** | 所有日志 | 精确控制 |
| **运维成本** | 高 | 低 |

---

### ✅ **优势 3：性能优化（Performance）**

```java
// 即使 log4j 开启了 TRACE 级别，仍然可以通过 mask 过滤
if (LOG.isTraceEnabled() && (mask & traceMask) != 0) {
    // 只有 mask 匹配的才执行日志构造和输出
   log.trace("详细的请求信息：" + request.toString());
}

// 如果 mask 不匹配，直接跳过，避免字符串拼接的开销
```


**性能对比：**

| 场景 | 传统方式 | traceMask 方式 |
|------|---------|---------------|
| **全量 TRACE** | 100% 日志输出，CPU 100% | 可选择只输出 1%，CPU 1% |
| **字符串构造** | 每次都构造 | 只在需要时构造 |
| **IO 开销** | 大量磁盘写入 | 可控的磁盘写入 |

---

### ✅ **优势 4：模块化调试（Modular Debugging）**

```java
// 不同模块使用不同的 mask
PrepRequestProcessor:
  - 普通请求：CLIENT_REQUEST_TRACE_MASK
  - Ping 请求：CLIENT_PING_TRACE_MASK

SessionTrackerImpl:
  - 会话管理：SESSION_TRACE_MASK

ServerCnxn:
  - 数据包：SERVER_PACKET_TRACE_MASK
  - Ping: SERVER_PING_TRACE_MASK

QuorumPeer:
  - 事件投递：EVENT_DELIVERY_TRACE_MASK
  - JMX 操作：JMX_TRACE_MASK
```


**实际应用场景：**

```
问题：客户端报告会话超时

传统方式：
  1. 开启所有 TRACE 日志
  2. 产生 GB 级日志文件
  3. 在海量日志中找线索
  
traceMask 方式：
  1. echo "set_trace_mask 0x20" | nc localhost 8080  # 只开 SESSION
  2. 复现问题
  3. 日志只包含会话相关信息
  4. 快速定位问题
  5. echo "set_trace_mask 0x100" | nc localhost 8080  # 恢复
```


---

### ✅ **优势 5：向后兼容（Backward Compatibility）**

```java
// 第一层判断：log4j 的级别控制（传统方式）
if (LOG.isTraceEnabled()) {  
    // 第二层判断：traceMask 精细控制（新增方式）
   if ((mask & traceMask) != 0) {
       log.trace(...);
    }
}
```


**兼容性体现：**
- ✅ 如果 log4j 没开 TRACE，完全不输出（兼容旧配置）
- ✅ 如果 log4j 开了 TRACE，由 traceMask 决定输出什么（新功能）
- ✅ 不影响现有系统的日志框架

---

## 四、实际应用示例

### 📊 **场景 1：生产环境默认配置**

```bash
# 默认只开启警告和关键请求
traceMask = 0x102  # WARNING | CLIENT_REQUEST

# 日志输出：
[WARN] ...  # 警告信息 ✓
[TRACE] P sessionId=0x123 type=create path=/node  # 关键请求 ✓
[TRACE] P sessionId=0x123 type=ping  # Ping 请求 ✗ (被过滤)
[TRACE] S session timeout check  # 会话检查 ✗ (被过滤)
```


### 📊 **场景 2：调试会话问题**

```bash
# 管理员发现会话频繁断开
# 临时开启会话追踪
echo "set_trace_mask 0x322" | nc localhost 8080
# 0x322 = WARNING | SESSION | CLIENT_REQUEST

# 日志输出：
[WARN] ...  # 警告 ✓
[TRACE] P sessionId=0x123 type=create  # 请求 ✓
[TRACE] S Session 0x123 timeout=30000  # 会话 ✓
[TRACE] S Touching session 0x123  # 会话心跳 ✓
```


### 📊 **场景 3：性能调优**

```bash
# 发现系统响应慢，怀疑是 Ping 处理问题
echo "set_trace_mask 0x88" | nc localhost 8080
# 0x88 = CLIENT_PING | SERVER_PING

# 只输出 Ping 相关日志，其他全部过滤
# 最小化日志开销，专注性能问题
```


---

## 五、技术亮点总结

| 特性 | 实现方式 | 价值 |
|------|---------|------|
| **位运算高效** | `(mask & traceMask) != 0` | CPU 周期极少 |
| **类型安全** | 预定义常量 | 避免魔法数字 |
| **可扩展** | `1 << n` 模式 | 轻松添加新类型 |
| **线程安全** | `synchronized` 方法 | 并发修改安全 |
| **可观测** | 四字命令查询 | 运维友好 |

---

## 六、与其他方案对比

### 🆚 **vs SLF4J Marker**

```java
// SLF4J Marker 方式
Marker clientMarker = MarkerFactory.getMarker("CLIENT");
logger.trace(clientMarker, "message");

// ZooKeeper traceMask 方式
logger.trace(CLIENT_REQUEST_TRACE_MASK, "message");
```


| 维度 | SLF4J Marker | traceMask |
|------|-------------|-----------|
| **性能** | 创建 Marker 对象 | 纯位运算 |
| **配置** | 代码中硬编码 | 运行时动态 |
| **组合** | 需要手动合并 | 按位或即可 |
| **内存** | 每个 Marker 占对象 | 单个 long 变量 |

### 🆚 **vs Logback Filter**

```xml
<!-- Logback XML 配置 -->
<filter>
  <expression>loggerName ~ ".*PrepRequestProcessor.*"</expression>
</filter>
```


| 维度 | Logback Filter | traceMask |
|------|---------------|-----------|
| **灵活性** | 基于表达式 | 基于位掩码 |
| **性能** | 正则匹配 | 位运算 |
| **动态性** | 需重载配置 | 即时生效 |
| **学习成本** | 需要学语法 | 简单直观 |

---

## 总结

### 🎓 **一句话评价**

`traceMask` 是一个**经典的工程实践**，用最简单的**位运算**实现了**高性能、高灵活性、动态可配置**的日志分级控制系统。

### 🔑 **核心价值**

1. **精细化**：可以精确到具体操作类型（Ping、请求、会话等）
2. **动态性**：无需重启，运行时即时调整
3. **高性能**：位运算开销极小，几乎无性能损失
4. **可扩展**：添加新类型只需增加一个 bit
5. **易运维**：通过四字命令即可查询和修改

这种设计在分布式系统中特别有价值，因为它允许运维人员在**不影响系统性能**的前提下，**按需获取调试信息**，是**生产级系统**的典范设计！

## 为什么要判断 `isThrottled()` 标记

### 🎯 **核心原因**

这个判断是为了**区分被限流的请求和正常请求**，对被限流的请求**跳过复杂的预处理逻辑**，直接传递给下一个处理器，从而**减少服务器负载**。

---

## 一、完整流程分析

### 📊 **请求处理链路**

```
客户端请求
    ↓
ServerCnxn (接收请求)
    ↓
RequestThrottler(限流器) ← 第一道关卡
    ├─ 检查系统负载
    ├─ 如果负载过高 → 标记 isThrottled=true
    └─ 提交到 PrepRequestProcessor
         ↓
PrepRequestProcessor (预处理)
    ├─ pRequest() 方法
    │   └─ if (!request.isThrottled())  ← 关键判断
    │       ├─ true:  执行 pRequestHelper() (复杂处理)
    │       └─ false: 跳过预处理 (快速路径)
    │
    └─ nextProcessor.processRequest() (继续传递)
         ↓
SyncRequestProcessor (同步写入磁盘)
         ↓
FinalRequestProcessor (最终处理)
```


---

## 二、`isThrottled` 标记的设置时机

### 🔍 **在哪里设置？**

```java
// RequestThrottler.java - 第 186-190 行
final long elapsedTime = Time.currentElapsedTime() - request.requestThrottleQueueTime;
ServerMetrics.getMetrics().REQUEST_THROTTLE_QUEUE_TIME.add(elapsedTime);

if (shouldThrottleOp(request, elapsedTime)) {
    // 标记这个请求被限流了
   request.setIsThrottled(true);  // ← 设置标记
    ServerMetrics.getMetrics().THROTTLED_OPS.add(1);
}
zks.submitRequestNow(request);
```


### ⚙️ **判断条件（shouldThrottleOp）**

```java
// RequestThrottler.java - 第 102-106 行
protected boolean shouldThrottleOp(Request request, long elapsedTime) {
   return request.isThrottlable()           // 1. 是可限流的操作类型
            && ZooKeeperServer.getThrottledOpWaitTime() > 0  // 2. 限流功能已开启
            && elapsedTime > ZooKeeperServer.getThrottledOpWaitTime(); // 3. 等待时间超过阈值
}
```


### 📋 **哪些操作可被限流？**

```java
// Request.java - 第 125-129 行
public boolean isThrottlable() {
   return this.type != OpCode.ping        // ❌ Ping 不可限流
            && this.type != OpCode.closeSession  // ❌ 关闭会话不可限流
            && this.type != OpCode.createSession;  // ❌ 创建会话不可限流
}
```


**设计意义：**
- ✅ **Ping**：心跳包，必须快速响应，检测连接状态
- ✅ **CreateSession/CloseSession**：会话管理，优先级高

---

## 三、`pRequestHelper` 做了什么？

### 🔧 **预处理的核心逻辑**

```java
// PrepRequestProcessor.java - 第 788 行开始
private void pRequestHelper(Request request) throws RequestProcessorException {
    switch (request.type) {
   case OpCode.create:
   case OpCode.create2:
   case OpCode.delete:
   case OpCode.setData:
   case OpCode.setACL:
   case OpCode.reconfig:
        // 1. 反序列化请求数据
        // 2. 参数校验
        // 3. 权限检查 (ACL)
        // 4. 路径验证
        // 5. 生成 TxnHeader 和 Txn
        // 6. 创建 ChangeRecord (内存变更预演)
        // 7. 计算数据摘要 (digest)
        pRequest2Txn(request.type, zks.getNextZxid(), request, ..., true);
        break;
    
   case OpCode.multi:
        // 复杂事务处理
        // 对每个子操作执行上述所有步骤
        // 还需要事务回滚准备
        break;
    
   case OpCode.createSession:
   case OpCode.closeSession:
        // 会话相关处理
        break;
    
    default:
        // 只检查会话有效性
       zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
        break;
    }
}
```


### 💥 **性能开销大的原因**

| 操作 | 开销来源 | CPU | 内存 | IO |
|------|---------|-----|------|-----|
| **反序列化** | ByteBuffer → Record | ⭐⭐ | ⭐ | - |
| **权限检查** | ACL 匹配 | ⭐⭐⭐ | ⭐⭐ | - |
| **路径验证** | 字符串操作 | ⭐⭐ | ⭐ | - |
| **创建 Txn** | 对象分配 | ⭐⭐ | ⭐⭐⭐ | - |
| **ChangeRecord** | 内存预演 | ⭐⭐⭐ | ⭐⭐⭐⭐ | - |
| **Digest 计算** | 哈希计算 | ⭐⭐⭐⭐ | ⭐ | - |

**结论：** 写操作的预处理非常消耗资源！

---

## 四、为什么要跳过预处理？

### 🎯 **核心目的：快速失败 + 减轻负载**

#### **场景 1：系统过载时的自我保护**

```
前提：maxRequests=1000, 当前 inProcess=1200 (超载 20%)

┌──────────────────────────────────────────────┐
│          正常请求处理流程                     │
├──────────────────────────────────────────────┤
│ 1. RequestThrottler 发现超载                 │
│    → 让请求在队列中等待                      │
│    → 等待时间超过阈值                        │
│    → 标记 isThrottled=true                   │
│                                              │
│ 2. PrepRequestProcessor                      │
│    → if (!request.isThrottled()) ✗ FALSE     │
│    → 跳过 pRequestHelper()                   │
│    → 直接传递给下一个处理器                  │
│                                              │
│ 3. SyncRequestProcessor                      │
│    → 发现没有 TxnHeader 和 Txn               │
│    → 直接丢弃或返回错误                      │
│                                              │
│ 结果：                                       │
│ ✅ 节省了预处理的 CPU 和内存开销              │
│ ✅ 快速拒绝请求，释放客户端连接               │
│ ✅ 避免系统进一步恶化                        │
└──────────────────────────────────────────────┘
```


#### **对比：不跳过的后果**

```
❌ 如果不判断 isThrottled：

系统已经超载 → 请求排队 → 终于轮到处理
→ 花费大量 CPU 做预处理
→ 创建各种临时对象
→ 最后还是要被拒绝（因为系统忙不过来）

结果：
❌ 浪费了宝贵的 CPU 资源
❌ 占用了内存
❌ 增加了延迟
❌ 可能导致 OOM
```


---

## 五、实际工作流程示例

### 📊 **正常请求 vs 被限流请求**

```java
// ========== 场景 A：正常请求 ==========
Request request1 = new Request(..., OpCode.create);

// RequestThrottler
if (shouldThrottleOp(request1, 10ms)) {  // 等待时间短
   request1.setIsThrottled(true);       // 不满足条件，不标记
}

// PrepRequestProcessor
if (!request1.isThrottled()) {           // true
    pRequestHelper(request1);            // ✅ 执行完整预处理
}
nextProcessor.processRequest(request1);  // 继续处理


// ========== 场景 B：被限流请求 ==========
Request request2 = new Request(..., OpCode.create);

// RequestThrottler (系统繁忙，排队 500ms)
if (shouldThrottleOp(request2, 500ms)) { // 等待时间超长
   request2.setIsThrottled(true);       // ✅ 标记为限流
}

// PrepRequestProcessor
if (!request2.isThrottled()) {           // false
    // ❌ 跳过预处理，节省资源
}
nextProcessor.processRequest(request2);  // 传递给下一环处理拒绝
```


---

## 六、设计优势总结

| 优势 | 说明 | 价值 |
|------|------|------|
| **快速路径** | 被限流请求走快速通道 | ⚡ 减少 CPU 消耗 |
| **资源保护** | 避免为将被拒绝的请求浪费资源 | 💾 节省内存 |
| **优雅降级** | 优先保证简单操作（Ping、读） | 🛡️ 维持基本服务 |
| **解耦设计** | 限流决策和执行分离 | 🔧 灵活调整策略 |
| **可观测性** | 通过 Metrics 统计限流数量 | 📊 监控友好 |

---

## 七、工程实践意义

### 🎓 **设计模式：Fast Fail（快速失败）**

```
传统方式：
  请求 → 完整处理 → 发现超时 → 拒绝
  ❌ 浪费资源做无用功

ZooKeeper 方式：
  请求 → 判断限流标记 → 跳过处理 → 拒绝
  ✅ 从一开始就避免浪费
```


### 🏗️ **架构思想：背压（Back Pressure）**

```
下游处理能力不足
    ↓
向上游传递压力信号（isThrottled 标记）
    ↓
上游减少工作量（跳过预处理）
    ↓
整体系统达到新的平衡
```


### ⚖️ **权衡取舍**

| 维度 | 不跳过 | 跳过 |
|------|-------|------|
| **CPU 使用** | 高（做无用功） | 低（及时止损） |
| **响应延迟** | 高（处理后才拒绝） | 低（快速拒绝） |
| **客户端体验** | 差（长时间等待后失败） | 较好（快速知道结果） |
| **系统稳定性** | 差（可能雪崩） | 好（及时保护） |

---

## 八、配置建议

### 🔧 **生产环境推荐配置**

```properties
# zoo.cfg

# 最大并发处理请求数（根据服务器能力调整）
zookeeper.maxRequestsInFlight = 1000

# 限流等待时间阈值（毫秒）
zookeeper.throttledOpWaitTime = 100

# 是否丢弃过期请求
zookeeper.request_throttle_drop_stale = true
```


### 📈 **监控指标**

```bash
# 查看限流统计
echo "stat" | nc localhost 2181 | grep throttled

# 关注指标：
# - throttled_ops: 被限流的请求数
# - stale_requests_dropped: 丢弃的过期请求
# - request_throttle_queue_time: 平均排队时间
```


---

## 总结

### 🎯 **一句话回答**

判断 `isThrottled()` 标记是为了**对被限流的请求跳过耗时的预处理逻辑**，直接走快速路径传递给后续处理器进行拒绝处理，从而**节省宝贵的系统资源**，实现**快速失败和自我保护**。

### 🔑 **核心价值**

1. **性能优化**：避免为注定被拒绝的请求浪费 CPU 和内存
2. **快速失败**：及时告知客户端请求被拒绝，减少等待时间
3. **系统保护**：防止过载情况下资源耗尽导致雪崩
4. **优雅降级**：优先保障关键操作（Ping、会话管理）
5. **解耦设计**：限流决策（RequestThrottler）与执行（PrepRequestProcessor）分离

这是 ZooKeeper 作为生产级分布式系统的**经典工程实践**，体现了**背压机制**和**快速失败**的设计哲学！

## 📌 **什么临时节点（ephemeral node）使用 `checkGlobalSession` 而持久节点使用 `checkSession`**

### **1️⃣ 会话类型差异**

ZooKeeper 在集群环境下有两种会话：
- **本地会话（Local Session）**：仅在创建它的服务器上有效
- **全局会话（Global Session）**：在整个集群的所有服务器上都有效

### **2️⃣ 临时节点的特殊性**

```java
if (createMode.isEphemeral()) {
    if (request.getException() != null) {
        throw request.getException();
    }
    // 临时节点需要全局会话
    zks.sessionTracker.checkGlobalSession(request.sessionId, request.getOwner());
} else {
    // 持久节点只需普通会话检查
    zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
}
```


### **3️⃣ 为什么临时节点需要全局会话？**

#### **原因一：会话失效检测更严格**
```java
// SessionTrackerImpl.java
public void checkGlobalSession(long sessionId, Object owner) 
        throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
    try {
        checkSession(sessionId, owner);
    } catch (KeeperException.UnknownSessionException e) {
        // 🔴 关键区别：未知会话直接视为过期
        throw new KeeperException.SessionExpiredException();
    }
}
```


- `checkSession` 抛出 `UnknownSessionException`（会话未知）
- `checkGlobalSession` 将 `UnknownSessionException` **转换为** `SessionExpiredException`（会话已过期）

#### **原因二：临时节点的生命周期依赖会话**

```java
// 从测试代码可以看出
zk.create(nodePrefix + i, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

// 当客户端断开连接后
zk.close();
// 临时节点会自动删除，因为会话结束了
```


**逻辑链条**：
1. 临时节点属于特定会话
2. 会话结束 → 临时节点必须删除
3. 如果会话是"本地会话"，其他服务器不知道它的存在
4. 客户端切换到其他服务器时，临时节点的状态会不一致

#### **原因三：集群一致性要求**

从 `LeaderSessionTracker` 的实现可以看出：

```java
// LeaderSessionTracker.java
public void checkSession(long sessionId, Object owner) {
    // 先检查本地会话
    if (localSessionTracker != null) {
        try {
            localSessionTracker.checkSession(sessionId, owner);
            if (!isGlobalSession(sessionId)) {
                return;  // 本地会话通过
            }
        } catch (UnknownSessionException e) {
            // 忽略，继续检查全局会话
        }
    }
    // 再检查全局会话
    try {
        globalSessionTracker.checkSession(sessionId, owner);
        return;
    } catch (UnknownSessionException e) {
        // 忽略
    }
    
    // 如果都不是，抛出异常
    if (!localSessionsEnabled || (getServerIdFromSessionId(sessionId) == serverId)) {
        throw new SessionExpiredException();
    }
}
```


### **4️⃣ 实际场景举例**

```
场景：客户端在 Follower A 上创建了临时节点

1️⃣ 如果是本地会话：
   - Follower A 知道这个会话
   - Leader 和其他 Follower 不知道
   - 客户端连接到 Follower B 时，B 认为会话不存在
   - ❌ 问题：临时节点应该删除吗？数据不一致！

2️⃣ 如果是全局会话：
   - 所有服务器都知道这个会话
   - 客户端可以连接到任意服务器
   - 会话结束时，所有服务器都能正确清理临时节点
   - ✅ 保证一致性
```


### **5️⃣ 会话升级机制**

从测试代码可以看到会话升级的过程：

```java
// SessionUpgradeTest.java
// 1. 客户端最初创建的是本地会话
DisconnectableZooKeeper zk = new DisconnectableZooKeeper(hostPorts[followerIdx], ...);

// 2. 创建临时节点时会触发会话升级
zk.create(nodePrefix + i, ..., CreateMode.EPHEMERAL);
// ↓
// 本地会话 → 全局会话（同步到所有服务器）

// 3. 之后可以连接到任意服务器
zk = new DisconnectableZooKeeper(hostPorts[otherFollowerIdx], ..., localSessionId, localSessionPwd);
watcher.waitForConnected(CONNECTION_TIMEOUT);  // ✅ 成功连接
```


## 🎯 **总结**

| 检查方法 | 适用场景 | 异常处理 | 目的 |
|---------|---------|---------|------|
| `checkSession` | 持久节点 | 抛出 `UnknownSessionException` | 验证会话是否存在 |
| `checkGlobalSession` | 临时节点 | 转换为 `SessionExpiredException` | **强制要求会话是全局的**，确保集群一致性 |

**根本原因**：临时节点的生命周期与会话绑定，必须在整个集群范围内保持一致性，因此要求会话必须是全局的。而持久节点不依赖会话状态，本地会话检查就足够了。