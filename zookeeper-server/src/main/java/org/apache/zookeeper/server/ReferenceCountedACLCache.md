## ReferenceCountedACLCache 属性使用场景

### 1. **`longKeyMap`** (Map<Long, List<ACL>>)
**作用**: 从 ACL ID (Long) 到 ACL 列表的映射

**使用场景**:
- **反向查找**: 当需要将 ACL ID 转换回实际的 ACL 列表时使用
- **节点读取**: 当客户端读取 znode 的 ACL 时，通过存储的 ACL ID 快速获取 ACL 详情
- **序列化/反序列化**: 在快照和事务日志恢复时重建 ACL 数据

**示例代码位置**:
```java
// 第 90 行 - convertLong 方法
List<ACL> acls = longKeyMap.get(longVal);
```


---

### 2. **`aclKeyMap`** (Map<List<ACL>, Long>)
**作用**: 从 ACL 列表到 ACL ID 的映射

**使用场景**:
- **去重检测**: 创建新节点或设置 ACL 时，检查相同的 ACL 是否已存在
- **ID 复用**: 如果 ACL 已存在，直接返回已有 ID，避免重复存储
- **内存优化**: 确保相同的 ACL 只存储一份，多个节点共享同一个 ACL ID

**示例代码位置**:
```java
// 第 65 行 - convertAcls 方法
Long ret = aclKeyMap.get(acls);
```


---

### 3. **`referenceCounter`** (Map<Long, AtomicLongWithEquals>)
**作用**: 记录每个 ACL ID 被多少个节点引用 (引用计数)

**使用场景**:
- **内存管理**: 跟踪 ACL 的使用情况，当引用计数为 0 时可以安全删除
- **并发安全**: 使用原子操作确保多线程环境下引用计数的准确性
- **清理无用 ACL**: 通过 `purgeUnused()` 方法定期清理不再使用的 ACL

**示例代码位置**:
```java
// 第 188-192 行 - addUsage 方法
AtomicLong count = referenceCounter.get(acl);
if (count == null) {
    referenceCounter.put(acl, new AtomicLongWithEquals(1));
} else {
    count.incrementAndGet();
}

// 第 206-211 行 - removeUsage 方法
long newCount = referenceCounter.get(acl).decrementAndGet();
if (newCount <= 0) {
    // 删除所有相关缓存
}
```


---

### 4. **`OPEN_UNSAFE_ACL_ID`** (static final long = -1L)
**作用**: 特殊 ACL ID 常量，表示开放的、不安全的 ACL

**使用场景**:
- **默认 ACL**: 当节点使用 `ZooDefs.Ids.OPEN_ACL_UNSAFE` 时，不实际存储，而是用这个特殊 ID 标识
- **空值处理**: 当 ACL 为 null 时返回此 ID
- **快速路径**: 避免为常见的开放 ACL 占用缓存空间

**示例代码位置**:
```java
// 第 60-61 行 - convertAcls 方法
if (acls == null) {
    return OPEN_UNSAFE_ACL_ID;
}

// 第 87-88 行 - convertLong 方法
if (longVal == OPEN_UNSAFE_ACL_ID) {
    return ZooDefs.Ids.OPEN_ACL_UNSAFE;
}
```


---

### 5. **`aclIndex`** (long)
**作用**: ACL ID 生成器，记录当前已分配的最大 ACL ID

**使用场景**:
- **唯一 ID 生成**: 每次创建新的 ACL 时递增，确保 ID 唯一性
- **反序列化恢复**: 从快照加载时，恢复正确的索引值以继续生成新 ID

**示例代码位置**:
```java
// 第 67 行 - convertAcls 方法
ret = incrementIndex();

// 第 98-100 行 - incrementIndex 方法
private long incrementIndex() {
    return ++aclIndex;
}

// 第 140-141 行 - deserialize 方法
if (aclIndex < val) {
    aclIndex = val;
}
```


---

## 整体工作流程示例

假设创建一个新节点 `/myNode`:

1. **创建节点时设置 ACL**:
    - 调用 `convertAcls(List<ACL>)` 将 ACL 转换为 ID
    - 通过 `aclKeyMap` 检查是否已存在相同 ACL
    - 如果不存在，`aclIndex++` 生成新 ID，存入两个 Map
    - `referenceCounter` 增加引用计数

2. **读取节点 ACL 时**:
    - 从 DataNode 获取存储的 ACL ID
    - 调用 `convertLong(Long)` 通过 `longKeyMap` 查找回完整的 ACL 列表

3. **删除节点时**:
    - 调用 `removeUsage(Long)` 减少对应 ACL 的引用计数
    - 如果计数归零，从所有 Map 中清除该 ACL

4. **快照序列化**:
    - 调用 `serialize()` 将所有 ACL 数据写入磁盘
    - 保存 `aclIndex` 以便恢复

5. **启动恢复**:
    - 调用 `deserialize()` 从磁盘加载 ACL 数据
    - 重建三个 Map 和 `aclIndex`

这种设计实现了 **ACL 数据的去重存储** 和 **自动垃圾回收**,有效节省了内存空间。