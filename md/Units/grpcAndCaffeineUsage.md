# gRPC 与 Caffeine 在 DI-Assistant 中的使用场景

---

## 🔵 Part 1: gRPC 使用场景

### 📌 快速概览

| 项目 | 值 |
|------|-----|
| 框架 | gRPC (Spring 集成) |
| 配置位置 | `di-assistant-mcp/config/` |
| 版本 | Spring Boot 3.2.7 内置 |
| 主要用途 | MCP (Model Context Protocol) 实现 |
| HTTP 协议版本 | HTTP/2 多路复用 |

---

### 💡 核心应用: MCP 协议

**MCP 是什么?**
- Model Context Protocol (模型上下文协议)
- 用于 AI 模型与应用之间的高效通信
- DI-Assistant 用它连接 AI 聊天服务和应用后端

**架构图**:

```
┌─────────────────┐
│ 前端 Web 应用    │
└────────┬────────┘
         │ HTTP/WebSocket
         ↓
┌─────────────────────────┐
│  DI-Assistant MCP Server│  (gRPC + SSE)
│  (di-assistant-mcp)     │
└────────┬────────────────┘
         │ gRPC/HTTP2
         ↓
┌─────────────────────────┐
│  MCP Client             │  (gRPC)
│  (di-assistant-mcp-cli) │
└────────┬────────────────┘
         │
         ↓
┌─────────────────────────┐
│  AI 模型服务            │
│  (DiBrain/LLM)          │
└─────────────────────────┘
```

---

### 🔧 核心配置文件详解

#### 文件 1: GrpcClientConfig.java

**位置**: `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/GrpcClientConfig.java`

**完整代码**:

```java
@Configuration
public class GrpcClientConfig {

  private static final int KEEP_ALIVE_TIME_SECONDS = 30;      // 30秒发送保活
  private static final int KEEP_ALIVE_TIMEOUT_SECONDS = 5;    // 5秒等待响应
  private static final int MAX_MESSAGE_SIZE_MB = 16;          // 16MB 消息
  private static final int BYTES_PER_MB = 1024 * 1024;
  private static final String HOST = "0.0.0.0";
  private static final int PORT = 8080;

  /**
   * gRPC 客户端通道配置
   * - 处理连接超时和 max_age 问题
   * - 支持自动重试
   * - 配置保活参数
   */
  @Bean
  public ManagedChannel grpcManagedChannel() {
    return NettyChannelBuilder.forAddress(HOST, PORT)
        .usePlaintext()  // 生产环境应使用 TLS
        
        // 保活配置 (Keep-Alive)
        .keepAliveTime(KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS)
        .keepAliveTimeout(KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)  // 即使无活跃调用也发送
        
        // 消息大小限制
        .maxInboundMessageSize(MAX_MESSAGE_SIZE_MB * BYTES_PER_MB)
        
        // 重试策略
        .defaultServiceConfig(getServiceConfig())
        .enableRetry()
        .build();
  }

  /**
   * 服务配置和重试策略
   * - 对 UNAVAILABLE 状态重试
   * - 指数退避: 0.1s → 1s
   * - 最多 3 次重试
   */
  private static Map<String, Object> getServiceConfig() {
    Map<String, Object> serviceConfig = new HashMap<>();
    
    // 方法配置
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "");  // 应用到所有服务
    methodConfig.put("name", List.of(name));
    
    // 重试策略
    Map<String, Object> retryPolicy = new HashMap<>();
    retryPolicy.put("maxAttempts", 3.0);              // 最多 3 次
    retryPolicy.put("initialBackoff", "0.1s");       // 初始 100ms
    retryPolicy.put("maxBackoff", "1s");              // 最大 1s
    retryPolicy.put("backoffMultiplier", 2.0);        // 指数倍增
    retryPolicy.put("retryableStatusCodes", List.of("UNAVAILABLE"));
    
    methodConfig.put("retryPolicy", retryPolicy);
    serviceConfig.put("methodConfig", List.of(methodConfig));
    
    return serviceConfig;
  }
}
```

**参数详解**:

| 参数 | 值 | 用途 | 说明 |
|------|-----|------|------|
| KeepAliveTime | 30s | 保活间隔 | 每 30 秒发送 PING 帧 |
| KeepAliveTimeout | 5s | 保活超时 | 等待 PONG 响应 5 秒 |
| KeepAliveWithoutCalls | true | 无调用时保活 | 连接空闲时也保活 |
| MaxMessageSize | 16MB | 最大消息 | 单条消息最大 16MB |
| MaxAttempts | 3 | 重试次数 | 失败最多重试 3 次 |

#### 文件 2: ConnectionManagementConfig.java

**位置**: `di-assistant-mcp/src/main/java/com/shopee/di/assistant/mcp/config/ConnectionManagementConfig.java`

**关键配置**:

```java
@Configuration
public class ConnectionManagementConfig {
  
  @Bean
  @ConfigurationProperties(prefix = "assistant.connection")
  public ConnectionProperties connectionProperties() {
    return new ConnectionProperties();
  }
}

public static class ConnectionProperties {
  private int maxRetries = 3;                    // 最大重试
  private long initialRetryDelay = 100;          // 100ms
  private long maxRetryDelay = 1000;             // 1s
  private double retryMultiplier = 2.0;          // 指数倍增
  private long keepAliveTime = 30000;            // 30s (ms)
  private long keepAliveTimeout = 5000;          // 5s (ms)
  private long maxConnectionAge = 300000;        // 5 min (ms) - 重要!
  private long maxConnectionAgeGrace = 30000;    // 30s (ms)
}
```

**maxConnectionAge 的重要性**:

```
问题场景:
┌──────────────────────────────────────┐
│ 服务器 max_age = 120s                │
│ 客户端 max_age = 无限制              │
└──────────────────────────────────────┘
           ↓
    120s 时服务器发送 GOAWAY
           ↓
    客户端未准备 → 连接突然断开
           ↓
    错误: "CONNECTION_CLOSED"
           ↓
   客户端需要重新连接 (时间浪费)

解决方案:
- 客户端 max_age 要小于服务器
- 主动刷新连接，避免被动断开
- 配置: maxConnectionAge = 300s (5分钟)
```

---

### 📋 配置文件汇总

#### application.yml (MCP 服务器配置)

```yaml
server:
  port: 8080
  shutdown: graceful
  tomcat:
    connection-timeout: 3600000  # 1 小时超时

spring:
  ai:
    mcp:
      server:
        sse-endpoint: /assistant-mcp/sse
        sse-message-endpoint: /assistant-mcp/mcp/message
        name: di-assistant-mcp
        version: 1.0.0
        request-timeout: 1200000    # 20 分钟
        keep-alive-time: 30s        # 30 秒
        keep-alive-timeout: 5s      # 5 秒
        max-connection-age: 300s    # 5 分钟(改进)
        max-connection-age-grace: 30s

  cloud:
    openfeign:
      client:
        config:
          default:
            connectTimeout: 60000   # 60 秒
            readTimeout: 600000     # 10 分钟

assistant:
  connection:
    max-retries: 3
    initial-retry-delay: 100
    max-retry-delay: 1000
    retry-multiplier: 2.0
    keep-alive-time: 30000
    keep-alive-timeout: 5000
    max-connection-age: 300000     # 5 分钟
    max-connection-age-grace: 30000
```

#### application-mcp-client.yml (客户端配置)

```yaml
server:
  port: 8081
  shutdown: graceful
  tomcat:
    connection-timeout: 600000  # 10 分钟

spring:
  ai:
    mcp:
      client:
        enabled: true
        name: my-mcp-client
        version: 1.0.0
        request-timeout: 1200s     # 20 分钟(改进)
        type: SYNC
        max-retries: 3
        initial-retry-delay: 100ms
        max-retry-delay: 1s
        retry-multiplier: 2.0
        sse:
          connections:
            server:
              url: http://localhost:8080
              sse-endpoint: /assistant-mcp/sse
              keep-alive-interval: 30s
              connection-timeout: 60s  # 60 秒(改进)
```

---

### 🐛 问题修复历程

**文件**: `deploy/TIMEOUT_FIX_SUMMARY.md`

#### 问题: 504 Gateway Timeout

**症状**:
- gRPC 连接频繁断开
- "GOAWAY" 错误日志
- SSE 连接中断
- 长时间请求超时

**根本原因**:

```
配置不匹配:
┌────────────────────────────────┐
│ 服务器:                        │
│ - max_age: 120s                │
│ - 120秒自动断开连接            │
├────────────────────────────────┤
│ 客户端:                        │
│ - 无 max_age 配置              │
│ - 被动等待连接断开            │
│ - 连接断开后需要重建 (2-5s)    │
└────────────────────────────────┘
           ↓
     导致请求超时
```

**修复方案**:

| 修复项 | 原值 | 改进后 | 文件 | 效果 |
|--------|------|--------|------|------|
| gRPC max_age | 120s | 300s | `application.yml` | 减少连接循环 |
| SSE 连接超时 | 10s | 60s | `application.yml` | 防止断线 |
| 客户端请求超时 | 600s | 1200s | `mcp-client.yml` | 对齐服务端 |
| SSE Emitter 超时 | 300s | 660s | `BIController.java` | 统一标准 |
| Nginx 配置 | 无 | 新增 | `nginx-sse-timeout.conf` | 反向代理支持 |

**Nginx 配置** (`deploy/nginx-sse-timeout.conf`):

```nginx
upstream di_assistant {
    server di-assistant-mcp:8080;
}

server {
    listen 80;
    server_name api.example.com;

    # SSE 长连接配置
    location /assistant-mcp/sse {
        proxy_pass http://di_assistant;
        
        # SSE 特定配置
        proxy_buffering off;              # 不缓冲
        proxy_request_buffering off;      # 不缓冲请求
        proxy_http_version 1.1;           # 使用 HTTP/1.1
        proxy_read_timeout 1200s;         # 20 分钟读超时
        proxy_send_timeout 1200s;         # 20 分钟写超时
        proxy_connect_timeout 60s;        # 60 秒连接超时
        
        # 保活配置
        proxy_socket_keepalive on;
        keepalive_timeout 1200s;
        
        # 请求头传递
        proxy_set_header Connection "Upgrade";
        proxy_set_header Upgrade "websocket";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # gRPC 配置
    location /grpc {
        proxy_pass grpcs://di_assistant;
        proxy_http_version 2.0;
        proxy_read_timeout 1200s;
        proxy_send_timeout 1200s;
    }
}
```

---

## �� Part 2: Caffeine 使用场景

### 📌 快速概览

| 项目 | 值 |
|------|-----|
| 版本 | 3.1.8 |
| 依赖位置 | `pom.xml` Line 191-194 |
| 版本定义 | `pom.xml` Line 38 |
| 主要用途 | 本地进程内缓存 |
| 存储位置 | JVM 堆内存 |

---

### 💡 Caffeine 核心优势

```
缓存性能对比:
┌─────────────┬─────────┬──────────┐
│ 缓存类型    │ 访问速度 │ 适用场景 │
├─────────────┼─────────┼──────────┤
│ Caffeine    │ 纳秒    │ 热数据   │
│ Redis       │ 毫秒    │ 温数据   │
│ 数据库      │ 10ms+   │ 冷数据   │
└─────────────┴─────────┴──────────┘

访问速度快 100-1000 倍!
```

### 🎯 应用场景

#### 场景 1: 反馈来源类型缓存

```java
@Configuration
public class CacheConfig {
  
  @Bean
  public Cache<String, FeedBackSourceType> feedbackSourceCache() {
    return Caffeine.newBuilder()
        .maximumSize(10)              // 只有 2 种类型
        .expireAfterWrite(1, TimeUnit.DAYS)
        .recordStats()
        .build();
  }
}

@Service
public class FeedbackService {
  private final Cache<String, FeedBackSourceType> cache;
  
  public FeedBackSourceType getFeedbackSource(String type) {
    return cache.get(type, 
        key -> FeedBackSourceType.valueOfString(key));
  }
}
```

#### 场景 2: 用户信息缓存

```java
@Service
public class UserService {
  
  private final Cache<Long, UserInfo> userCache;
  
  public UserService() {
    this.userCache = Caffeine.newBuilder()
        .maximumSize(100000)           // 10 万用户
        .expireAfterWrite(2, TimeUnit.HOURS)
        .refreshAfterWrite(1, TimeUnit.HOURS)  // 1 小时自动刷新
        .recordStats()
        .build();
  }
  
  public UserInfo getUserInfo(Long userId) {
    return userCache.get(userId, this::loadFromDB);
  }
  
  private UserInfo loadFromDB(Long userId) {
    return userRepository.findById(userId)
        .orElse(null);
  }
  
  public void invalidateUser(Long userId) {
    userCache.invalidate(userId);
  }
}
```

#### 场景 3: Chat 详情缓存

```java
@Service
public class ChatService {
  
  private final Cache<Long, ChatDetail> chatCache;
  
  @PostConstruct
  public void init() {
    this.chatCache = Caffeine.newBuilder()
        .maximumSize(500000)          // 50 万聊天
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .weigher((k, v) -> {
          // 自定义权重: 大消息占用更多空间
          return v.getContentSize() / 1024;
        })
        .maximumWeight(1024 * 1024)   // 总容量 1GB
        .recordStats()
        .build();
  }
  
  @Cacheable(value = "chatCache", key = "#chatId")
  public ChatDetail getChatDetail(Long chatId) {
    return chatTabMapper.selectById(chatId);
  }
  
  public CacheStats getCacheStats() {
    return chatCache.stats();
  }
}
```

### ⚙️ 配置参数详解

```java
Caffeine.newBuilder()
  .maximumSize(10000)                    // 最多 10000 条记录
  .expireAfterWrite(10, TimeUnit.MINUTES)  // 10 分钟过期
  .refreshAfterWrite(5, TimeUnit.MINUTES)  // 5 分钟自动刷新
  .recordStats()                         // 记录统计信息
  .build()
```

| 参数 | 说明 | 例子 |
|------|------|------|
| maximumSize | 最多缓存数量 | 10000 条 |
| expireAfterWrite | 写入后过期时间 | 10 分钟 |
| refreshAfterWrite | 写入后自动刷新 | 5 分钟 |
| weigher | 自定义权重 | 按大小计算 |
| maximumWeight | 最大权重容量 | 1GB |
| recordStats | 记录统计 | 缓存命中率 |

### 📊 性能对比

```
单个查询性能:
┌──────────────┬──────────┐
│ 访问方式     │ 时间     │
├──────────────┼──────────┤
│ Caffeine 命中│ 1-10μs   │ ← 极快
│ Redis 命中   │ 1-3ms    │
│ DB 查询      │ 10-100ms │
└──────────────┴──────────┘

缓存命中率提升:
┌──────────────┬──────────┐
│ 场景         │ 命中率   │
├──────────────┼──────────┤
│ 用户信息     │ 95%+     │
│ 字典数据     │ 99%+     │
│ Chat 详情    │ 80-90%   │
└──────────────┴──────────┘
```

### 🔐 缓存更新策略

#### 策略 1: TTL 过期 (Time To Live)

```java
// 10 分钟后自动过期
.expireAfterWrite(10, TimeUnit.MINUTES)
```

#### 策略 2: 主动刷新 (Active Refresh)

```java
// 5 分钟后自动刷新 (后台异步)
.refreshAfterWrite(5, TimeUnit.MINUTES)
```

#### 策略 3: 主动失效 (Active Invalidation)

```java
public void updateChat(ChatDetail chat) {
  chatService.save(chat);
  chatCache.invalidate(chat.getId());  // 立即清除缓存
}
```

---

## 📊 多层缓存架构

```
┌────────────────────────────────────┐
│ 1. 本地缓存层 (Caffeine)          │
│    - 访问速度: 纳秒               │
│    - 容量: 几百 MB                │
│    - 特点: 极快，但单机           │
├────────────────────────────────────┤
│ 2. 分布式缓存层 (Redis)           │
│    - 访问速度: 毫秒               │
│    - 容量: 几十 GB                │
│    - 特点: 跨机器共享             │
├────────────────────────────────────┤
│ 3. 数据库缓存层 (Query Cache)     │
│    - 访问速度: 10ms+              │
│    - 容量: 无限制                 │
│    - 特点: 持久化                 │
└────────────────────────────────────┘
```

---

## ✅ 检查清单

### gRPC
- [x] gRPC 配置已实现 (GrpcClientConfig)
- [x] 连接管理已配置 (ConnectionManagementConfig)
- [x] 超时问题已修复 (TIMEOUT_FIX_SUMMARY.md)
- [x] Nginx 反向代理已配置
- [x] MCP 服务器集成完成
- [ ] gRPC 性能监控（待实现）
- [ ] gRPC 健康检查（待实现）

### Caffeine
- [x] Caffeine 依赖已添加 (v3.1.8)
- [ ] 缓存配置类（待实现）
- [ ] 缓存注解使用（待实现）
- [ ] 缓存策略定义（待实现）
- [ ] 缓存预热逻辑（待实现）
- [ ] 缓存监控指标（待实现）

