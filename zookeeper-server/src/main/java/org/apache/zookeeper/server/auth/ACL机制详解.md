# ZooKeeper ACL 机制详解

## 📚 **ACL 基础概念**

ZooKeeper 使用 **ACL (Access Control List)** 来实现节点的访问控制。每个 znode 都有自己独立的 ACL，用于控制客户端对该节点的操作权限。

---

## 🔑 **ACL 的组成结构**

### **1. ACL = Scheme + ID + Permission**

```java
public class ACL {
    int perms;           // 权限
    Id id;               // 身份标识
    
    public ACL(int perms, Id id) {
        this.perms = perms;
        this.id = id;
    }
}

public class Id {
    String scheme;       // 认证方案
    String id;           // 身份信息
}
```


**示例**:
```java
// 给用户 admin 授予所有权限
new ACL(Perms.ALL, new Id("digest", "admin:hash123"))

// 允许所有人读取
new ACL(Perms.READ, new Id("world", "anyone"))
```


---

## 🎯 **5 种内置 Scheme**

### **1️⃣ world - 任何人**

最简单的 scheme，只有一个 id：`anyone`

```java
// 完全开放的 ACL（默认）
Ids.OPEN_ACL_UNSAFE = [new ACL(Perms.ALL, new Id("world", "anyone"))]

// 只读开放
Ids.READ_ACL_UNSAFE = [new ACL(Perms.READ, new Id("world", "anyone"))]
```


**特点**:
- 无需认证
- 适用于公开数据

---

### **2️⃣ auth - 已认证的用户**

特殊 scheme，会自动替换为当前用户认证信息

```java
// 创建者独占 ACL
Ids.CREATOR_ALL_ACL = [new ACL(Perms.ALL, new Id("auth", ""))]

// 使用流程
zk.addAuthInfo("digest", "user:password".getBytes());
zk.create("/myNode", data, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
// ↓ 内部会展开为
// ACL(Perms.ALL, new Id("digest", "user:base64(SHA1(password))))
```


**特点**:
- 方便，不需要显式指定用户
- 自动关联当前登录用户
- 只有 `isAuthenticated()=true` 的认证才有效

---

### **3️⃣ digest - 用户名密码**

使用 `username:password` 的 SHA1/SHA256 哈希作为身份标识

```java
// 生成密码哈希
String digest = DigestAuthenticationProvider.generateDigest("admin:secret");
// 输出：admin:base64(SHA1("admin:secret"))

// 设置 ACL
List<ACL> acls = Arrays.asList(
    new ACL(Perms.ALL, new Id("digest", "admin:" + digest)),
    new ACL(Perms.READ, new Id("world", "anyone"))
);
zk.setACL("/node", acls, -1);

// 客户端认证
zk.addAuthInfo("digest", "admin:secret".getBytes());
```


**特点**:
- 密码以哈希形式存储
- 支持多个用户
- 可配置超级管理员 (`zookeeper.DigestAuthenticationProvider.superDigest`)

---

### **4️⃣ IP - IP 地址限制**

基于客户端 IP 地址进行访问控制

```java
// 单个 IP
new ACL(Perms.ALL, new Id("ip", "192.168.1.100"))

// IP 段 (CIDR)
new ACL(Perms.READ, new Id("ip", "192.168.1.0/24"))  // 192.168.1.* 
new ACL(Perms.READ, new Id("ip", "10.0.0.0/8"))      // 10.*.*.*

// 验证格式
prov.isValid("127.0.0.1")      // true
prov.isValid("127.0.0.1/32")   // true
prov.isValid("127.0.0.1/33")   // false (掩码过大)
```


**特点**:
- `isAuthenticated()=false` (IP 可能变化，不能作为创建者标识)
- 适用于内网隔离场景

---

### **5️⃣ x509 - X.509 证书**

使用 SSL/TLS 客户端证书的 X500 Principal 作为身份标识

```java
// 自动认证（启用 SSL 时）
new ACL(Perms.ALL, new Id("x509", "CN=Admin,O=Company,C=US"))

// 超级管理员配置
System.setProperty("zookeeper.X509AuthenticationProvider.superUser", "CN=SUPER");
```


**特点**:
- 高安全性
- 需要 SSL 配置
- 自动认证

---

## 🔐 **5 种权限类型 (Perms)**

```java
public interface Perms {
    int READ   = 1 << 0;   // 读取节点数据和子节点列表
    int WRITE  = 1 << 1;   // 设置节点数据
    int CREATE = 1 << 2;   // 创建子节点
    int DELETE = 1 << 3;   // 删除子节点
    int ADMIN  = 1 << 4;   // 设置 ACL
    int ALL    = READ | WRITE | CREATE | DELETE | ADMIN;
}
```


| 权限 | 说明 | 对应操作 |
|------|------|----------|
| **READ** | 读权限 | `getData`, `getChildren`, `getACL`, `exists` |
| **WRITE** | 写权限 | `setData` |
| **CREATE** | 创建权限 | `create` |
| **DELETE** | 删除权限 | `delete` |
| **ADMIN** | 管理权限 | `setACL`, `getACL`(查看完整 ACL) |

---

## 💻 **实际使用示例**

### **场景 1: 完全开放（开发环境）**
```java
// 任何人都可以执行任何操作
zk.create("/public", data, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
```


### **场景 2: 创建者独占**
```java
// 只有创建者能访问
zk.create("/private", data, Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
```


### **场景 3: 多用户权限分离**
```java
// 管理员有所有权限，普通用户只能读
List<ACL> acls = Arrays.asList(
    new ACL(Perms.ALL, new Id("digest", "admin:" + adminDigest)),
    new ACL(Perms.READ, new Id("digest", "user:" + userDigest)),
    new ACL(Perms.READ, new Id("world", "anyone"))
);
zk.setACL("/config", acls, -1);
```


### **场景 4: IP 白名单**
```java
// 只允许特定服务器修改
List<ACL> acls = Arrays.asList(
    new ACL(Perms.ALL, new Id("ip", "192.168.1.10")),   // 应用服务器
    new ACL(Perms.ALL, new Id("ip", "192.168.1.20")),   // 监控服务器
    new ACL(Perms.READ, new Id("world", "anyone"))      // 其他人只读
);
```


### **场景 5: 混合使用**
```java
List<ACL> acls = Arrays.asList(
    // 管理员通过 digest 认证
    new ACL(Perms.ALL, new Id("digest", "admin:xxxxx")),
    // 内部服务器通过 IP 认证
    new ACL(Perms.WRITE, new Id("ip", "10.0.0.0/8")),
    // 所有人可读
    new ACL(Perms.READ, new Id("world", "anyone"))
);
```


---

## ⚙️ **ACL 处理流程**

### **1. 创建节点时的 ACL**
```
客户端 create(path, data, acl, mode)
         ↓
PrepRequestProcessor.validateACL()
         ↓
fixupACL() - 展开 "auth" scheme
         ↓
检查每个 ACL 的 isValid()
         ↓
存储到 DataTree
```


### **2. 访问时的权限检查**
```
客户端请求操作
         ↓
PrepRequestProcessor.checkACL()
         ↓
获取客户端 authInfo (认证信息)
         ↓
遍历节点 ACL
         ↓
调用 matches() 检查是否匹配
         ↓
验证是否有对应权限
         ↓
允许/拒绝操作
```


---

## 🔧 **超级管理员配置**

### **Digest 超级管理员**
```bash
# 启动参数
-Dzookeeper.DigestAuthenticationProvider.superDigest=super:base64(SHA1("super:password"))

# Java 代码生成
DigestAuthenticationProvider.main(new String[]{"super:password"});
```


### **X509 超级管理员**
```bash
-Dzookeeper.X509AuthenticationProvider.superUser=CN=SUPER
```


**超级管理员特权**:
- 绕过 ACL 检查
- 可以执行任何操作
- 用于紧急救援

---

## ⚠️ **注意事项**

### **1. ACL 一旦设置无法撤销**
```java
// ❌ 危险！如果忘记添加其他 ACL，自己也可能被锁在外面
zk.setACL("/node", Collections.emptyList(), -1);

// ✅ 正确做法
List<ACL> acls = getExistingACL(node);
acls.add(newACL);
zk.setACL("/node", acls, -1);
```


### **2. 父节点 ACL 不影响子节点**
```java
// /parent 的 ACL 不会遗传给 /parent/child
// 每个节点必须单独设置 ACL
```


### **3. 认证信息在会话中保持**
```java
// 认证一次，整个会话有效
zk.addAuthInfo("digest", "user:pass".getBytes());
// 后续所有操作都会携带此认证信息
```


### **4. 性能考虑**
- ACL 检查会增加延迟
- 避免为每个节点设置过多 ACL
- 批量操作比单个操作更高效

---

## 📊 **ACL 最佳实践**

| 场景 | 推荐方案 |
|------|---------|
| **开发测试** | `OPEN_ACL_UNSAFE` |
| **生产配置中心** | `digest` + `ADMIN` 权限分离 |
| **服务注册发现** | `IP` scheme + `CREATE/DELETE` 权限 |
| **分布式锁** | `CREATOR_ALL_ACL` |
| **多租户系统** | 多个 `digest` 用户 + 不同权限组合 |

---

## 🎯 **总结**

ZooKeeper ACL 机制的核心特点：

1. **灵活性**: 5 种 scheme 满足不同安全需求
2. **细粒度**: 每个节点独立控制，5 种权限精确授权
3. **可扩展**: 支持自定义 AuthenticationProvider
4. **安全性**: 支持密码哈希、IP 限制、证书认证
5. **便利性**: `auth` scheme 自动关联用户

合理使用 ACL 可以确保 ZooKeeper 集群的数据安全和访问控制！