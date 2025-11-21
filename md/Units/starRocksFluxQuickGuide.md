# StarRocks & Flux 快速参考指南

## 🎯 一句话总结

| 技术 | 定义 | 作用 |
|------|------|------|
| **StarRocks** | 分布式 OLAP 数据仓库 | 存储和查询大规模分析数据 |
| **Flux** | 异步响应式数据流 | 非阻塞处理 HTTP 流式响应 |

---

## 📚 详细对比

### StarRocks vs 普通 MySQL

| 特性 | MySQL | StarRocks |
|------|-------|-----------|
| **存储方式** | 行存储 | 列存储 |
| **适用场景** | 交易系统 | 分析系统 |
| **查询方式** | 单行或少量行 | 大规模分析 |
| **压缩率** | 1x | 10-50x |
| **查询速度** | 中等 | 非常快 |
| **吞吐量** | 中等 | 极高 |
| **并发** | 有限 | 高并发 |

### Flux vs 传统阻塞 IO

| 特性 | 阻塞 IO | Flux (非阻塞) |
|------|--------|--------------|
| **线程占用** | 1 个线程/请求 | 共享线程池 |
| **内存** | ~10MB/线程 | ~100KB/流 |
| **响应延迟** | 高 | 低 |
| **并发能力** | 100-200 | 10,000+ |
| **编程模型** | 命令式 | 声明式 |
| **背压处理** | 手动 | 自动 |

---

## 🗂️ 文件位置

### StarRocks 相关

```
di-assistant-service/
├── src/main/java/com/shopee/di/assistant/
│   ├── service/starrocks/
│   │   ├── StarRocksService.java          ← 业务逻辑
│   │   └── StarRocksStreamingClient.java  ← 流式客户端
│   ├── rest/client/
│   │   └── FluxWebClientConfig.java       ← WebClient 配置
│   └── common/model/
│       └── starrocks/
│           └── StarRocksHttpRequest.java  ← 请求模型
└── src/main/resources/
    ├── application-live.yml               ← 生产环境
    ├── application-staging.yml            ← 预发环境
    └── application-local.yml              ← 本地环境
```

### Flux 使用位置

```
di-assistant-service/
├── service/
│   ├── starrocks/
│   │   └── StarRocksStreamingClient.java  ← Flux.doOnNext()
│   ├── common/
│   │   └── CommonChatService.java         ← Flux.concatMap()
│   └── bi/
│       └── ChatBIService.java             ← Flux.create()
└── rest/client/
    └── FluxWebClientConfig.java           ← WebClient Bean
```

---

## 💻 核心代码片段

### StarRocks 查询流程

```java
// 1. 调用服务
starRocksService.downloadDatasetData(datasetInfo, outputWriter);

// 2. 构建 SQL
String sql = "SELECT * FROM table_name LIMIT 100000";

// 3. 流式执行
starRocksStreamingClient.executeQueryAndStreamToWriter(
    "SG",          // 区域
    sql,           // 查询
    outputWriter   // 输出
);

// 4. 内部使用 Flux 处理
Flux<DataBuffer> flux = webClient.post()
    .uri(url)
    .retrieve()
    .bodyToFlux(DataBuffer.class);

flux.doOnNext(...)       // 处理数据块
    .doOnComplete(...)   // 流完成
    .doOnError(...)      // 错误处理
    .blockLast();        // 等待完成
```

### Flux 操作符

```java
// 1. 创建流
Flux<String> flux = Flux.just("a", "b", "c");
Flux<String> flux = Flux.fromIterable(list);
Flux<String> flux = Flux.create(sink -> {...});

// 2. 转换
flux.map(item -> item.toUpperCase())
    .filter(item -> item.length() > 1)
    .flatMap(item -> getDetails(item));

// 3. 组合
flux.concatWith(otherFlux)
    .mergeWith(anotherFlux)
    .concat(flux1, flux2, flux3);

// 4. 时间操作
Flux.interval(Duration.ofSeconds(1))    // 定时发出
    .delay(Duration.ofSeconds(2));      // 延迟

// 5. 处理事件
flux.doOnNext(item -> log.info("Item: {}", item))
    .doOnError(err -> log.error("Error", err))
    .doOnComplete(() -> log.info("Done"));

// 6. 订阅
flux.subscribe(
    item -> {},           // onNext
    error -> {},          // onError
    () -> {}              // onComplete
);

// 7. 阻塞等待
flux.blockLast();        // 等待最后一个元素
flux.blockFirst();       // 等待第一个元素
```

---

## 🔌 配置参数

### StarRocks 连接

```yaml
assistant:
  feign:
    client-properties:
      # SG 环境
      uris:
        starrocks-sg-client: 
          http://sr-di-diana-live-sg-cluster.proxy.sr.data-infra.shopee.io:8080/api/v1/catalogs/default_catalog/databases/di_diana_live_db/sql
      
      # US-EAST 环境
      headers:
        starrocks-us-east-client:
          Authorization: Basic ${61673:starrocks-auth-token}
```

### WebClient 配置

```java
// 连接池
DEFAULT_CONNECTION_POOL_SIZE = 20        // 20 个连接
DEFAULT_CONNECT_TIMEOUT_MS = 600000      // 600 秒连接超时

// 响应处理
RESPONSE_CONNECT_TIMEOUT_MINUTES = 10    // 10 分钟响应超时
MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024    // 10 MB 内存限制
```

---

## 🔄 StarRocks 数据流

```
请求:
POST http://starrocks-cluster/api/v1/catalogs/.../sql
Content-Type: application/json
Authorization: Basic <token>

{
  "query": "SELECT * FROM table_name LIMIT 100000"
}

响应 (NDJSON):
{"connectionId": 12345, "meta": [...]}
{"data": [value1, value2, ...]}
{"data": [value1, value2, ...]}
{"statistics": {...}}
```

---

## 📊 性能指标

### 查询性能

| 数据量 | 传统方式 | StarRocks | 改进 |
|--------|---------|-----------|------|
| 10K 行 | 100ms | 10ms | 10x |
| 100K 行 | 1000ms | 50ms | 20x |
| 1M 行 | 10s+ | 200ms | 50x+ |

### 并发性能

| 场景 | 线程方式 | Flux 方式 | 改进 |
|------|---------|----------|------|
| 100 并发 | 占用 100 线程 | 占用 2-4 线程 | 25-50x |
| 内存占用 | ~1GB | ~50MB | 20x |
| 响应延迟 | 500ms | 50ms | 10x |

---

## ❌ 常见错误

### 错误 1: 忘记调用 blockLast()

```java
// ❌ 错误: Flux 没有被消费，不会执行
Flux<DataBuffer> flux = webClient.post(...)
    .retrieve()
    .bodyToFlux(DataBuffer.class);
// 流从未执行！

// ✅ 正确
Flux<DataBuffer> flux = webClient.post(...)
    .retrieve()
    .bodyToFlux(DataBuffer.class);
flux.blockLast();  // 阻塞等待流完成
```

### 错误 2: 在 doOnNext 中阻塞操作

```java
// ❌ 错误: 会阻塞非阻塞流，降低性能
flux.doOnNext(item -> {
    Thread.sleep(1000);  // 这会导致性能问题
});

// ✅ 正确: 使用 flatMap 处理异步操作
flux.flatMap(item -> 
    Mono.fromCallable(() -> heavyOperation(item))
        .delayElement(Duration.ofSeconds(1))
);
```

### 错误 3: 不处理背压

```java
// ❌ 错误: 可能导致内存溢出
sink.onNext(largeList);  // 发出大量元素

// ✅ 正确: 使用 request() 控制背压
subscription.request(100);  // 每次请求 100 个元素
```

---

## 🎓 使用场景速查

| 场景 | 是否使用 StarRocks | 是否使用 Flux | 原因 |
|------|---|---|---|
| BI 数据分析 | ✅ | ✅ | 大数据 + 流处理 |
| 大数据导出 | ✅ | ✅ | 百万级数据 + 流式 |
| 实时交易 | ❌ | ❌ | 需要 MySQL |
| 实时推送 | ❌ | ✅ | 流式响应 |
| 聊天消息 | ❌ | ✅ | 流式 SSE |

---

## 📝 总结

**StarRocks**: 选择它当你需要...
- 查询大规模数据 (百万+)
- 执行复杂分析
- 需要高速查询 (<100ms)
- BI/分析用途

**Flux**: 选择它当你需要...
- 处理 HTTP 流式响应
- 高并发请求 (1000+)
- 节省线程和内存
- 非阻塞异步处理

**一起使用**: 当需要...
- 流式导出大量数据
- BI 数据查询和展示
- 在线分析处理 (OLAP)

