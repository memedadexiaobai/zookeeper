## AdHash 类的场景和原理

### 📋 **使用场景**

`AdHash`（Additive Hash，增量哈希）在 ZooKeeper 中主要用于**快速验证数据树的同步状态**。具体应用场景包括：

1. **数据一致性校验**：在分布式环境中，快速判断多个节点的数据树是否一致
2. **增量更新检测**：当数据树发生增删改操作时，能够快速计算出新的哈希值，无需重新遍历整棵树
3. **同步状态验证**：用于 Leader 和 Follower 之间验证数据是否同步完成

### 🔬 **工作原理**

基于论文《A New Paradigm for collision-free hashing: Incrementality at reduced cost》实现的**增量哈希算法**：

#### 核心思想：
```
总哈希 = 所有节点 digest 的累加和
```


#### 操作方式：

1. **添加节点**：`addDigest(digest)`
    - 将新节点的 digest **加到**总哈希中
    - `hash += digest`

2. **删除节点**：`removeDigest(digest)`
    - 从总哈希中**减去**被删除节点的 digest
    - `hash -= digest`

3. **修改节点**：先 `removeDigest(旧值)`，再 `addDigest(新值)`

#### 关键特性：

✅ **交换律**：加法顺序不影响最终结果（测试代码第 64-72 行证明了这一点）
```java
bucket1 + bucket2 == bucket2 + bucket1
```


✅ **可逆性**：可以高效地添加和移除 digest
```java
总哈希 - bucket1 - bucket2 - bucket3 = 0 (空树)
```


✅ **高效性**：使用 64 位 `long` 类型，支持快速的加减运算

### 💡 **实际应用示例**

在 `NodeHashMapImpl` 中的使用：

```java
// 当添加一个新节点时
public DataNode put(String path, DataNode node) {
    DataNode oldNode = nodes.put(path, node);
    addDigest(path, node);           // 加上新节点的 digest
    if (oldNode != null) {
        removeDigest(path, oldNode); // 如果是更新，减去旧节点的 digest
    }
    return oldNode;
}

// 当修改节点前
public void preChange(String path, DataNode node) {
    removeDigest(path, node);  // 先减去旧的 digest
}

// 当修改节点后
public void postChange(String path, DataNode node) {
    addDigest(path, node);     // 再加上新的 digest
}
```


### 🎯 **为什么需要 AdHash？**

想象一下 ZooKeeper 内存中有成千上万个节点，如果要验证两台服务器的数据是否一致：

- ❌ **传统方法**：遍历所有节点，逐个比较 → O(n) 复杂度
- ✅ **AdHash 方法**：直接比较两个 long 值 → O(1) 复杂度

只要两个数据树的 AdHash 值相同，就可以高度确信它们的数据是一致的！