# 第十三篇｜ZK 3.7 ACL 权限控制：原理、配置与源码解析
大家好，上一篇我们吃透了临时节点的创建、存储、自动删除逻辑，这一篇聚焦 ZK 安全层面的核心——**ACL（Access Control List）权限控制**。

在生产环境中，ZK 作为分布式协调核心，节点数据的访问安全至关重要：比如分布式锁节点不能被随意删除、配置节点只能被指定服务读写。这一篇我从源码层面，讲清楚 ACL 的权限类型、鉴权流程、存储机制，以及生产中“权限配置不生效、鉴权失败、权限泄露”等问题的根源和解决方法。

---

## 一、先搞懂：ACL 是什么？核心设计理念
ACL（访问控制列表）是 ZK 对节点的**细粒度权限管控机制**，核心设计理念可总结为：
> **每个节点独立配置 ACL，权限与节点绑定，而非与用户/会话绑定**

### ACL 的核心组成（三元组）
ZK 的 ACL 不是简单的“读/写”，而是由 `scheme:id:permissions` 三元组构成：
| 组成部分 | 含义 | 示例 |
|----------|------|------|
| scheme（权限模式） | 定义“谁能访问”的规则 | world、auth、digest、ip、super |
| id（身份标识） | 符合 scheme 规则的具体身份 | world:anyone、ip:192.168.1.100、digest:user:passwd |
| permissions（权限集合） | 允许执行的操作 | READ、WRITE、CREATE、DELETE、ADMIN |

### 核心特性（面试必背）
1. **节点独立**：每个节点的 ACL 独立配置，父节点权限不继承给子节点；
2. **无递归**：修改父节点 ACL 不会影响子节点；
3. **权限最小化**：默认只有创建者和超级管理员有全部权限；
4. **动态修改**：节点创建后可随时修改 ACL，无需重启服务。

---

## 二、ACL 核心组件解析
### 1. 权限模式（scheme）：5 种常用类型
| 模式 | 作用 | 适用场景 |
|------|------|----------|
| world | 所有人可访问（默认） | 测试环境、无安全要求的节点 |
| auth | 已认证的用户可访问 | 集群内服务间访问 |
| digest | 用户名+密码鉴权 | 跨节点服务访问、需身份验证的场景 |
| ip | 指定 IP/IP 段可访问 | 限制特定机器/网段访问 |
| super | 超级管理员（最高权限） | 运维管理、紧急故障排查 |

### 2. 权限类型（permissions）：5 种核心权限
ZK 定义了 5 种基础权限，可组合使用：
```java
// 源码位置：org.apache.zookeeper.ZooDefs.Perms
public interface Perms {
    int READ = 1;    // 读节点数据、列出子节点
    int WRITE = 2;   // 修改节点数据
    int CREATE = 4;  // 创建子节点
    int DELETE = 8;  // 删除子节点
    int ADMIN = 16;  // 修改节点ACL、查看权限
    int ALL = 31;    // 所有权限（1+2+4+8+16）
}
```

### 3. 核心类：ACL 与 Id
```java
// 源码位置：org.apache.zookeeper.data.ACL
public class ACL implements Record {
    private int perms;       // 权限集合（如 READ+WRITE=3）
    private Id id;           // 身份标识
    
    // Id类：scheme + id
    public static class Id implements Record {
        private String scheme; // 权限模式
        private String id;     // 身份值
    }
}
```

---

## 三、源码解析 1：ACL 权限校验流程（核心）
所有客户端请求（读/写/创建/删除/修改ACL）都会经过 ACL 校验，核心入口在 `PrepRequestProcessor` 的 `checkACL` 方法：

```java
// 权限校验核心方法
private void checkACL(Request request) throws KeeperException {
    String path = request.getPath();
    int permRequired = getRequiredPerm(request.getType()); // 获取请求需要的权限
    
    // 1. 超级管理员跳过校验
    if (isSuperRequest(request)) {
        return;
    }
    
    // 2. 获取节点的ACL列表
    List<ACL> aclList = dataTree.getACL(path);
    if (aclList == null) {
        throw new KeeperException.NoAuthException();
    }
    
    // 3. 校验客户端身份是否匹配ACL
    boolean hasPerm = false;
    for (ACL acl : aclList) {
        // 调用对应scheme的校验器（如DigestACLProvider、IPACLProvider）
        if (aclProvider.checkACL(request, acl, permRequired)) {
            hasPerm = true;
            break;
        }
    }
    
    // 4. 无权限则抛异常
    if (!hasPerm) {
        throw new KeeperException.NoAuthException("No permission for path " + path);
    }
}
```

### 关键解读：
1. **权限按需校验**：不同请求需要不同权限（如读请求需 READ，修改ACL需 ADMIN）；
2. **超级管理员豁免**：配置了 super 权限的用户跳过所有 ACL 校验；
3. **多ACL匹配**：只要匹配任意一个 ACL 即可通过校验（“或”逻辑）；
4. **Scheme 插件化**：不同 scheme 对应不同的校验器，可扩展自定义 scheme。

---

## 四、源码解析 2：常用 Scheme 校验逻辑
### 1. world 模式（所有人可访问）
```java
// WorldACLProvider 校验逻辑
public boolean checkACL(Request request, ACL acl, int permRequired) {
    // world模式下，id固定为"anyone"，直接放行
    return acl.getId().getScheme().equals("world") 
        && acl.getId().getId().equals("anyone")
        && (acl.getPerms() & permRequired) == permRequired;
}
```
**核心**：无身份校验，所有请求都通过，是 ZK 默认权限（创建节点时未指定ACL则用此模式）。

### 2. digest 模式（用户名+密码鉴权）
```java
// DigestACLProvider 校验逻辑
public boolean checkACL(Request request, ACL acl, int permRequired) {
    // 1. 获取客户端携带的认证信息
    Map<String, String> authInfo = request.getAuthInfo();
    String clientDigest = authInfo.get("digest");
    if (clientDigest == null) {
        return false;
    }
    
    // 2. 对比服务端存储的digest（用户名:密码的MD5值）
    String serverDigest = acl.getId().getId();
    return clientDigest.equals(serverDigest) 
        && (acl.getPerms() & permRequired) == permRequired;
}
```
**核心**：客户端需先通过 `addAuthInfo` 提交用户名密码，服务端存储的是 MD5 加密后的 digest，而非明文密码。

### 3. ip 模式（IP 白名单）
```java
// IPACLProvider 校验逻辑
public boolean checkACL(Request request, ACL acl, int permRequired) {
    // 1. 获取客户端IP
    String clientIp = request.getCnxn().getRemoteAddress().getAddress().getHostAddress();
    
    // 2. 匹配IP/IP段（如192.168.1.0/24）
    String ipRule = acl.getId().getId();
    if (ipRule.contains("/")) {
        // 处理网段匹配
        return matchIpSegment(clientIp, ipRule) 
            && (acl.getPerms() & permRequired) == permRequired;
    } else {
        // 处理单个IP匹配
        return clientIp.equals(ipRule) 
            && (acl.getPerms() & permRequired) == permRequired;
    }
}
```
**核心**：基于客户端连接的 IP 地址校验，支持单个 IP 和 CIDR 网段。

---

## 五、源码解析 3：ACL 的存储与持久化
ZK 的 ACL 与节点元数据一起存储，分为“内存”和“磁盘”两部分：

### 1. 内存存储（DataTree）
```java
// 源码位置：org.apache.zookeeper.server.DataTree
// key：节点路径，value：ACL列表
private final ConcurrentHashMap<String, List<ACL>> aclCache = new ConcurrentHashMap<>();

// 设置节点ACL
public void setACL(String path, List<ACL> acl, int version) throws KeeperException {
    // 1. 校验版本（乐观锁）
    NodeData node = getNode(path);
    if (node.aclVersion != version && version != -1) {
        throw new KeeperException.BadVersionException();
    }
    
    // 2. 更新内存ACL缓存
    aclCache.put(path, acl);
    // 3. 更新节点ACL版本
    node.aclVersion++;
}

// 获取节点ACL
public List<ACL> getACL(String path) {
    // 无ACL则返回默认（world:anyone:ALL）
    return aclCache.getOrDefault(path, ZooDefs.Ids.OPEN_ACL_UNSAFE);
}
```

### 2. 磁盘持久化（事务日志）
ACL 的修改会作为事务写入日志，源码在 `SyncRequestProcessor`：
```java
public void processRequest(Request request) {
    if (request.getType() == OpCode.setACL) {
        // 将ACL修改写入事务日志
        TxnHeader hdr = new TxnHeader(request.getSessionId(), request.getCxid(), 
                                      request.getType(), request.getZxid(), Time.currentWallTime());
        SetACLTxn txn = new SetACLTxn(request.getPath(), request.getACL(), request.getVersion());
        zkServer.getTxnLogFactory().append(hdr, txn);
    }
}
```
**关键**：服务端重启后，会从事务日志恢复所有节点的 ACL 配置，保证权限不丢失。

---

## 六、面试高频题（源码级标准答案）
### 问题 1：ZK 的 ACL 权限是否继承？
答：不继承。每个节点的 ACL 独立配置，父节点的 ACL 不会影响子节点。源码中 `DataTree.getACL` 方法只会读取当前节点的 ACL 缓存，不会递归查找父节点。例如：/parent 配置了 ip 白名单，/parent/child 若未配置 ACL，则使用默认的 world:anyone:ALL。

### 问题 2：digest 模式的密码是明文存储吗？
答：不是。客户端提交的用户名密码会被 MD5 加密为 `user:md5(user:passwd)` 格式的 digest，服务端只存储 digest，不存储明文密码。源码中 `DigestACLProvider` 只对比 digest，无法反向推导明文密码。

### 问题 3：超级管理员（super）如何配置？
答：在 ZK 启动脚本中添加 JVM 参数：`-Dzookeeper.superDigest=user:digest`（digest 是 `user:passwd` 的 MD5 值）。源码中 `isSuperRequest` 方法会校验客户端提交的 digest 是否匹配该参数，匹配则跳过所有 ACL 校验。

### 问题 4：修改 ACL 需要什么权限？
答：需要节点的 ADMIN 权限（Perms.ADMIN=16）。源码中 `checkACL` 方法会为 setACL 请求校验 ADMIN 权限，无此权限则抛 NoAuthException。

---

## 七、生产常见问题与排查方法
| 问题现象 | 常见原因 | 排查方向 |
|----------|----------|----------|
| ACL 配置不生效 | 1. 父节点权限不继承，子节点未配置；2. ACL 版本冲突；3. 超级管理员覆盖了权限 | 1. 检查子节点 ACL（`getAcl /path`）；2. 查看日志中的 BadVersionException；3. 检查 super 配置 |
| 鉴权失败（NoAuth） | 1. 客户端未提交认证信息（addAuthInfo）；2. IP/密码错误；3. 权限不足 | 1. 确认客户端调用 addAuthInfo；2. 核对 IP 白名单/用户名密码；3. 检查节点 ACL 权限是否包含所需操作 |
| 权限泄露 | 1. 节点使用默认 ACL（world:anyone）；2. 超级管理员密码泄露；3. IP 网段配置过宽 | 1. 批量检查节点 ACL（`ls / && getAcl /path`）；2. 重置 super 密码；3. 缩窄 IP 白名单范围 |
| 修改 ACL 提示版本错误 | 1. 并发修改 ACL 导致版本不一致；2. 传入的版本号错误 | 1. 改用版本号 -1（忽略版本）；2. 先 getAcl 获取最新版本号再修改 |

---

## 八、生产最佳实践（基于源码）
1. **权限最小化**：避免使用默认的 `OPEN_ACL_UNSAFE`，根据场景配置最小权限（如读节点只给 READ 权限）；
2. **分层ACL策略**：根节点配置严格 ACL（IP+digest），业务节点按需配置，运维节点配置 super 权限；
3. **定期审计**：通过 `zkCli.sh` 批量检查节点 ACL，清理不必要的开放权限；
4. **加密存储密码**：digest 模式的密码避免明文配置，使用脚本生成 MD5 digest；
5. **监控ACL修改**：通过 ZK 监控指标（`zk_acl_changes_count`）监控 ACL 修改频率，及时发现异常修改。

---

## 九、写在最后
ACL 是 ZK 保障节点数据安全的核心机制，其“节点独立、插件化 scheme、细粒度权限”的设计，适配了不同场景的安全需求。理解了 ACL 的校验流程、存储机制和常见问题，你就能在生产中构建安全的 ZK 集群。

下一篇我们会聚焦 ZK 的**集群运维与调优**，解析 ZK 集群的部署架构、性能调优参数、故障排查工具，以及生产中常见的集群问题（如脑裂、同步延迟、选举失败）的解决方法。

### CSDN专属标签
#ZooKeeper3.7 #ACL权限控制 #鉴权流程 #分布式安全 #中间件源码 #ZK生产运维

---

### 小预告
下一篇内容：《第十四篇｜ZK 3.7 集群运维与调优：部署、监控、故障排查全攻略》，会重点讲解 ZK 集群部署架构、核心调优参数、监控指标、常见故障排查方法，提前可以先熟悉 `zoo.cfg` 配置项和 ZK 自带的运维命令（如 zkCli.sh、zkServer.sh）~