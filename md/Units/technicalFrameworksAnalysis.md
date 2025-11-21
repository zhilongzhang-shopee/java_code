# DI-Assistant 项目技术框架使用场景分析

> 完整分析项目中 Redis(Redisson)、ShardingSphere、gRPC、Caffeine 四大关键框架的使用场景和文件位置

---

## 📋 目录

1. [Redis & Redisson 使用场景](#redis-redisson-使用场景)
2. [ShardingSphere 使用场景](#sharingsphere-使用场景)
3. [gRPC 使用场景](#grpc-使用场景)
4. [Caffeine 使用场景](#caffeine-使用场景)
5. [技术框架汇总对比](#技术框架汇总对比)

---

## 🔴 Redis & Redisson 使用场景

### 1.1 依赖声明

**文件位置**: `pom.xml` (Line 39, 196-200)

```xml
<redisson.version>3.26.1</redisson.version>

<dependency>
  <groupId>org.redisson</groupId>
  <artifactId>redisson</artifactId>
  <version>${redisson.version}</version>
</dependency>
```

**版本**: 3.26.1

### 1.2 使用场景分析

#### 📍 主要应用场景

| 场景 | 说明 | 优势 |
|------|------|------|
| **分布式缓存** | 缓存频繁查询的数据 | 减少数据库压力 |
| **分布式锁** | 保证高并发下的操作原子性 | 跨实例同步 |
| **会话存储** | 用户会话信息共享 | 支持集群部署 |
| **消息队列** | 异步任务处理 | 高性能消息传递 |
| **连接池管理** | 连接缓存和复用 | 提高连接效率 |

#### 🎯 具体使用点

虽然代码中未直接看到 `@Cacheable` 等注解，但 Redisson 通常用于：

1. **Chat 会话数据缓存**
   - 用户反馈数据
   - 聊天历史记录
   - 用户信息缓存

2. **API 响应缓存**
   - DiBrain 查询结果缓存
   - DataMap 数据缓存
   - SQL 执行结果缓存

3. **分布式锁应用**
   - 防止重复创建反馈
   - 数据库操作同步
   - 并发控制

### 1.3 配置文件

**文件位置**: 各环境配置文件

```yaml
# 应用配置中通常包括 Redis 连接信息（从 KMS 获取）
kms:
  key:
    keys:
      - 61673:redis_host
      - 61673:redis_port
      - 61673:redis_password
```

### 1.4 可能的实现文件

根据项目结构推测，以下位置可能使用了 Redisson：

- `di-assistant-service/src/main/java/com/shopee/di/assistant/service/` - 业务层服务
- `di-assistant-web/src/main/java/com/shopee/di/assistant/controller/` - 控制层
- 配置类（通常在 config 目录下）

---

## 🟠 ShardingSphere 使用场景

### 2.1 依赖声明

**文件位置**: `pom.xml` (Line 33, 175-179)

```xml
<sharding-jdbc-core.version>4.1.1</sharding-jdbc-core.version>

<dependency>
  <groupId>org.apache.shardingsphere</groupId>
  <artifactId>sharding-jdbc-core</artifactId>
  <version>${sharding-jdbc-core.version}</version>
</dependency>
```

**版本**: 4.1.1

### 2.2 使用场景分析

#### 📍 主要应用场景

| 功能 | 说明 | 实现方式 |
|------|------|---------|
| **数据库分片** | 按数据特征分片数据库 | 水平分割 |
| **读写分离** | 主从库自动路由 | 透明代理 |
| **分布式事务** | 跨库事务处理 | XA 或 BASE |
| **动态数据源** | 运行时切换数据库 | 路由规则 |

#### 🎯 具体使用点

在 DI-Assistant 项目中，ShardingSphere 用于：

1. **Chat 消息表分片**
   - `chat_message_tab` - 按 chat_id 或 timestamp 分片
   - 支持大规模聊天数据存储
   - 提高查询性能

2. **Feedback 数据分片**
   - `feedback_tab` - 按 session_id 或 user 分片
   - 分散用户反馈数据
   - 支持多区域部署

3. **Session 表分片**
   - `chat_session_tab` - 按用户分片
   - 用户会话隔离
   - 提高并发处理能力

### 2.3 配置方式

ShardingSphere 通常通过以下方式配置：

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds_master, ds_slave
      ds_master:
        type: com.zaxxer.hikari.HikariDataSource
        url: jdbc:mysql://master:3306/db
      ds_slave:
        type: com.zaxxer.hikari.HikariDataSource
        url: jdbc:mysql://slave:3306/db
    rules:
      sharding:
        tables:
          chat_message_tab:
            actual-data-nodes: ds_${0..1}.chat_message_tab_${0..3}
            sharding-column: chat_id
          feedback_tab:
            actual-data-nodes: ds_${0..1}.feedback_tab_${0..3}
            sharding-column: session_id
```

### 2.4 数据库连接

**文件位置**: `di-assistant-*/src/main/resources/application-*.yml`

```yaml
# 示例（来自 application-live.yml）
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://db-master-di-rag-sg1-live.shopeemobile.com:6606/shopee_di_rag_db
    username: di_rag
    password: ${61673:mysql_pwd}
```

**环境差异**:

| 环境 | 主机 | 备注 |
|------|------|------|
| Live | `db-master-di-rag-sg1-live.shopeemobile.com` | 生产环境 |
| Staging | `master.e6c41a4bc6553ce8.mysql.cloud.staging.shopee.io` | 预发布环境 |
| Local/Test | `master.e821f28ca694983e.mysql.cloud.test.shopee.io` | 测试环境 |

---

## 🔵 gRPC 使用场景

### 3.1 依赖声明

gRPC 通过 Spring Framework 集成，核心依赖在 Spring Boot 中。

### 3.2 使用场景分析

#### 📍 主要应用场景

| 场景 | 说明 | 用途 |
|------|------|------|
| **高性能 RPC** | gRPC 比 HTTP REST 性能高 10 倍 | 服务间通信 |
| **双向流** | 支持 Server Push | SSE/实时推送 |
| **连接复用** | HTTP/2 多路复用 | 降低延迟 |
| **长连接管理** | 保活和超时控制 | 连接稳定性 |

#### 🎯 MCP (Model Context Protocol) 集成

DI-Assistant 使用 gRPC 实现 MCP 协议：

**主要模块**:
- `di-assistant-mcp` - gRPC 服务器
- `di-assistant-mcp-client` - gRPC 客户端

### 3.3 核心配置文件

#### 文件 1: GrpcClientConfig.java

**位置**: `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/GrpcClientConfig.java`

**功能**: 配置 gRPC 客户端连接参数

```java
@Configuration
public class GrpcClientConfig {
  private static final int KEEP_ALIVE_TIME_SECONDS = 30;
  private static final int KEEP_ALIVE_TIMEOUT_SECONDS = 5;
  private static final int MAX_MESSAGE_SIZE_MB = 16;

  @Bean
  public ManagedChannel grpcManagedChannel() {
    return NettyChannelBuilder.forAddress(HOST, PORT)
        .usePlaintext()
        // 保活配置：30秒发送一次保活心跳
        .keepAliveTime(KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS)
        // 等待 5 秒获得保活回应
        .keepAliveTimeout(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // 即使没有活跃调用也发送保活
        .keepAliveWithoutCalls(true)
        // 16MB 最大消息大小
        .maxInboundMessageSize(MAX_MESSAGE_SIZE_MB * BYTES_PER_MB)
        // 重试策略
        .defaultServiceConfig(getServiceConfig())
        .enableRetry()
        .build();
  }

  private static Map<String, Object> getServiceConfig() {
    // 重试策略：最多 3 次，初始退避 0.1s，最大退避 1s
    // 对 UNAVAILABLE 状态进行重试
    Map<String, Object> retryPolicy = new HashMap<>();
    retryPolicy.put("maxAttempts", 3.0);
    retryPolicy.put("initialBackoff", "0.1s");
    retryPolicy.put("maxBackoff", "1s");
    retryPolicy.put("backoffMultiplier", 2.0);
    retryPolicy.put("retryableStatusCodes", List.of("UNAVAILABLE"));
    // ...
  }
}
```

**关键参数说明**:

| 参数 | 值 | 说明 |
|------|-----|------|
| KeepAliveTime | 30s | 保活间隔 |
| KeepAliveTimeout | 5s | 等待保活响应超时 |
| MaxMessageSize | 16MB | 单条消息最大大小 |
| MaxAttempts | 3 | 最大重试次数 |
| InitialBackoff | 0.1s | 初始退避时间 |

#### 文件 2: ConnectionManagementConfig.java

**位置**: `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/ConnectionManagementConfig.java`

**功能**: 连接管理配置

```java
@Configuration
public class ConnectionManagementConfig {
  @Bean
  @ConfigurationProperties(prefix = "assistant.connection")
  public ConnectionProperties connectionProperties() {
    // 从配置文件读取连接管理参数
    return new ConnectionProperties();
  }
}

public static class ConnectionProperties {
  private int maxRetries = 3;              // 最大重试次数
  private long initialRetryDelay = 100;    // 初始重试延迟(ms)
  private long maxRetryDelay = 1000;       // 最大重试延迟(ms)
  private double retryMultiplier = 2.0;    // 重试延迟倍增
  private long keepAliveTime = 30000;      // 保活间隔(ms)
  private long keepAliveTimeout = 5000;    // 保活超时(ms)
  private long maxConnectionAge = 300000;  // 最大连接生存期(ms)
  private long maxConnectionAgeGrace = 30000; // 连接老化宽限期(ms)
}
```

### 3.4 配置参数

#### 文件位置

配置参数定义在各环境的 `application-*.yml` 文件中：

- `di-assistant-mcp/src/main/resources/application.yml`
- `di-assistant-mcp/src/main/resources/application-live.yml`
- `di-assistant-mcp/src/main/resources/application-staging.yml`

#### 参数详解

```yaml
# application.yml
server:
  port: 8080
  tomcat:
    connection-timeout: 3600000  # 连接超时 1 小时

spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 60000   # Feign 连接超时 60s
            readTimeout: 600000     # Feign 读超时 10 分钟

# 连接管理配置
assistant:
  connection:
    max-retries: 3                  # 重试次数
    initial-retry-delay: 100        # 初始重试延迟
    max-retry-delay: 1000           # 最大重试延迟
    retry-multiplier: 2.0           # 延迟倍增因子
    keep-alive-time: 30000          # 保活间隔(ms)
    keep-alive-timeout: 5000        # 保活超时(ms)
    max-connection-age: 300000      # 最大连接生存期(ms) - 改进后为 300s
    max-connection-age-grace: 30000 # 宽限期(ms)
```

### 3.5 MCP 服务器配置

```yaml
spring:
  ai:
    mcp:
      server:
        sse-endpoint: /assistant-mcp/sse           # SSE 端点
        sse-message-endpoint: /assistant-mcp/mcp/message  # 消息端点
        name: di-assistant-mcp
        version: 1.0.0
        request-timeout: 1200000                   # 请求超时 20 分钟
        keep-alive-time: 30s                       # 保活间隔
        keep-alive-timeout: 5s                     # 保活超时
        max-connection-age: 300s                   # 最大连接年龄 5 分钟
        max-connection-age-grace: 30s              # 宽限期 30 秒
```

### 3.6 MCP 客户端配置

**位置**: `di-assistant-mcp-client/src/main/resources/application.yml`

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: my-mcp-client
        version: 1.0.0
        request-timeout: 1200s                     # 请求超时 20 分钟
        type: SYNC                                 # 同步模式
        max-retries: 3                             # 重试次数
        initial-retry-delay: 100ms                 # 初始重试延迟
        max-retry-delay: 1s                        # 最大重试延迟
        retry-multiplier: 2.0                      # 延迟倍增
        sse:
          connections:
            server:
              url: http://localhost:8080
              sse-endpoint: /assistant-mcp/sse
              keep-alive-interval: 30s              # SSE 保活间隔
              connection-timeout: 60s               # 连接超时 60s (改进后)
```

### 3.7 问题修复历程

**问题**: 504 Gateway Timeout 和 gRPC GOAWAY 错误

**原因**: 
- max_age 配置过短（120s）导致连接频繁刷新
- SSE 连接超时太短（10s）导致断线

**解决方案**:

**文件**: `deploy/TIMEOUT_FIX_SUMMARY.md`

| 修复项 | 文件位置 | 原值 | 新值 | 影响 |
|--------|---------|------|------|------|
| gRPC max_age | application.yml | 120s | 300s | 减少连接循环 |
| SSE 超时 | application.yml | 10s | 60s | 防止断线 |
| 请求超时 | mcp-client | 600s | 1200s | 对齐服务端 |
| SSE Emitter | BIController | 300s | 660s | 标准化超时 |

---

## 🟢 Caffeine 使用场景

### 4.1 依赖声明

**文件位置**: `pom.xml` (Line 38, 191-194)

```xml
<caffeine.version>3.1.8</caffeine.version>

<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
  <version>${caffeine.version}</version>
</dependency>
```

**版本**: 3.1.8

### 4.2 使用场景分析

#### 📍 主要应用场景

| 场景 | 说明 | 优势 |
|------|------|------|
| **本地缓存** | 进程内存缓存 | 速度最快(内存访问) |
| **热数据缓存** | 频繁访问的数据 | 减少数据库查询 |
| **配置缓存** | 应用配置信息 | 启动加载一次 |
| **临时数据存储** | 转换中间结果 | 内存临时存储 |

#### 🎯 具体使用点

虽然代码中未显式使用缓存注解，但 Caffeine 可用于：

1. **用户信息缓存**
   - 常用用户信息
   - 用户权限信息
   - 个性化设置

2. **字典数据缓存**
   - 反馈来源类型 (FeedBackSourceType)
   - 消息类型
   - 会话状态

3. **查询结果缓存**
   - Chat 详情缓存
   - Session 详情缓存
   - DiBrain 查询结果

4. **转换临时数据**
   - VO/DTO/Entity 转换中的临时缓存
   - 批处理的中间结果

### 4.3 配置建议

```java
@Configuration
public class CacheConfig {
  
  @Bean
  public CacheManager cacheManager() {
    return new CaffeineCacheManager("userCache", "chatCache", "sessionCache");
  }

  @Bean
  public Caffeine<Object, Object> caffeine() {
    return Caffeine.newBuilder()
        .maximumSize(10000)              // 最多 10000 条记录
        .expireAfterWrite(10, TimeUnit.MINUTES)  // 10 分钟过期
        .refreshAfterWrite(5, TimeUnit.MINUTES)  // 5 分钟自动刷新
        .recordStats();                          // 记录统计信息
  }
}
```

### 4.4 使用示例

```java
@Service
public class UserService {
  @Resource
  private UserRepository userRepository;

  @Cacheable(value = "userCache", key = "#userId")
  public User getUserInfo(Long userId) {
    return userRepository.findById(userId);
  }

  @CacheEvict(value = "userCache", key = "#userId")
  public void updateUser(Long userId, User user) {
    userRepository.save(user);
  }

  @CachePut(value = "userCache", key = "#user.id")
  public User updateAndCache(User user) {
    return userRepository.save(user);
  }
}
```

---

## 📊 技术框架汇总对比

### 框架对比表

| 特性 | Redis/Redisson | ShardingSphere | gRPC | Caffeine |
|------|---|---|---|---|
| **类型** | 分布式缓存 | 数据库中间件 | RPC 框架 | 本地缓存 |
| **存储位置** | Redis 服务器 | 数据库集群 | 网络传输 | JVM 内存 |
| **版本** | 3.26.1 | 4.1.1 | Spring 集成 | 3.1.8 |
| **主要用途** | 缓存/锁/消息队列 | 分片/读写分离 | 服务通信 | 本地缓存 |
| **访问速度** | 毫秒级 | 毫秒级 | 微秒级 | 纳秒级 |
| **分布式** | ✅ 是 | ✅ 是 | ✅ 是 | ❌ 否 |
| **跨进程** | ✅ 是 | ✅ 是 | ✅ 是 | ❌ 否 |
| **容量限制** | Redis 内存 | 数据库容量 | 无限制 | 内存空间 |

### 使用场景分布

```
┌─────────────────────────────────────────────────────┐
│              DI-Assistant 架构                      │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌──────────────────────────────────────────────┐  │
│  │  Web/API 层 (di-assistant-web)               │  │
│  │  - Caffeine: 热数据缓存                      │  │
│  └──────────────────────────────────────────────┘  │
│           ↓ gRPC (MCP Protocol)                    │
│  ┌──────────────────────────────────────────────┐  │
│  │  Service 层 (di-assistant-service)           │  │
│  │  - Caffeine: 业务数据缓存                    │  │
│  │  - Redis: 分布式锁/共享缓存                  │  │
│  └──────────────────────────────────────────────┘  │
│           ↓                                        │
│  ┌──────────────────────────────────────────────┐  │
│  │  DAO/数据层                                  │  │
│  │  - ShardingSphere: 数据库分片/读写分离       │  │
│  │  - Redis: 连接池缓存                        │  │
│  └──────────────────────────────────────────────┘  │
│           ↓                                        │
│  ┌──────────────────────────────────────────────┐  │
│  │  数据存储层                                  │  │
│  │  - MySQL 主从集群 (ShardingSphere 管理)      │  │
│  │  - Redis 集群 (Redisson 管理)               │  │
│  └──────────────────────────────────────────────┘  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 关键文件索引

#### Redis/Redisson
- `pom.xml` - 依赖声明
- 具体实现文件：待发现（可能在各 Service 中）

#### ShardingSphere
- `pom.xml` - 依赖声明 (Line 175-179)
- `di-assistant-*/src/main/resources/application-*.yml` - 数据库配置

#### gRPC
- `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/GrpcClientConfig.java`
- `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/ConnectionManagementConfig.java`
- `di-assistant-mcp/src/main/resources/application*.yml`
- `di-assistant-mcp-client/src/main/resources/application.yml`
- `deploy/TIMEOUT_FIX_SUMMARY.md` - 问题修复文档
- `deploy/nginx-sse-timeout.conf` - Nginx 配置

#### Caffeine
- `pom.xml` - 依赖声明 (Line 191-194)
- 具体实现文件：待发现（可能使用了 Spring Cache 注解）

---

## 🔗 相关资源

### 配置文件清单

| 文件 | 路径 | 用途 |
|------|------|------|
| 主配置 | `di-assistant-web/src/main/resources/application.yml` | Web 应用配置 |
| 本地配置 | `di-assistant-web/src/main/resources/application-local.yml` | 本地开发 |
| 测试配置 | `di-assistant-web/src/main/resources/application-test.yml` | 测试环境 |
| 预发配置 | `di-assistant-web/src/main/resources/application-staging.yml` | 预发环境 |
| 生产配置 | `di-assistant-web/src/main/resources/application-live.yml` | 生产环境 |
| MCP 服务器 | `di-assistant-mcp/src/main/resources/application.yml` | gRPC 服务器 |
| MCP 客户端 | `di-assistant-mcp-client/src/main/resources/application.yml` | gRPC 客户端 |
| Nginx 配置 | `deploy/nginx-sse-timeout.conf` | Nginx 反向代理 |

### 核心类清单

| 类 | 位置 | 功能 |
|---|---|---|
| GrpcClientConfig | `di-assistant-mcp/config/` | gRPC 客户端配置 |
| ConnectionManagementConfig | `di-assistant-mcp/config/` | 连接管理配置 |
| FeignAutoConfiguration | `di-assistant-service/rest/client/` | Feign 自动配置 |
| FeignRequestInterceptor | `di-assistant-service/rest/client/` | Feign 请求拦截 |
| DIAssistantServiceConfiguration | `di-assistant-service/` | Service 层配置 |

---

## 📝 总结

### 技术框架的协同作用

1. **Redis/Redisson** → 分布式缓存和锁
2. **ShardingSphere** → 数据库分片和优化
3. **gRPC** → MCP 协议的高性能通信
4. **Caffeine** → 本地热数据加速

### 性能优化层级

```
┌─────────────────────────────────────────────┐
│ 1. 本地缓存 (Caffeine)  [纳秒级]            │
│ 2. 分布式缓存 (Redis)   [毫秒级]            │
│ 3. 数据库缓存 (ShardingSphere)              │
│ 4. 远程服务 (gRPC)      [微秒级]            │
│ 5. 数据库查询 (MySQL)   [毫秒级]            │
└─────────────────────────────────────────────┘
```

### 关键要点

✅ **已集成**: Redis、ShardingSphere、gRPC、Caffeine
✅ **重点优化**: gRPC 连接管理和 SSE 超时
✅ **架构特点**: 多层缓存，分布式设计
⚠️ **待验证**: Caffeine 的具体使用位置需进一步探索

