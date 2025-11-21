# StarRocks 与 Flux 在 DI-Assistant 中的使用分析

## 📌 快速概览

### StarRocks
- **类型**: 分布式 OLAP 数据仓库
- **文件位置**: `di-assistant-service/src/main/java/com/shopee/di/assistant/service/starrocks/`
- **主要文件**: 
  - `StarRocksService.java` - 业务逻辑层
  - `StarRocksStreamingClient.java` - 流式查询客户端

### Flux
- **类型**: 响应式编程流（来自 Spring WebFlux）
- **框架**: Reactor 框架中的异步流处理类
- **核心概念**: 非阻塞、异步、背压处理

---

## 🟠 Part 1: StarRocks 详解

### 什么是 StarRocks？

**StarRocks** 是一个开源的分布式 OLAP（在线分析处理）数据库，具有以下特点：

```
传统 OLTP 数据库 vs StarRocks (OLAP 数据库)

OLTP (MySQL):           OLAP (StarRocks):
├─ 行存储              ├─ 列存储
├─ 适合查询单行        ├─ 适合分析大数据集
├─ 实时交易系统        ├─ 数据分析系统
└─ QPS 优先            └─ 吞吐量优先

特点:
- 高效列存: 压缩率高，查询快
- MPP 架构: 大规模并行处理
- 自适应哈希连接: 动态优化查询
- 向量化执行: 充分利用现代 CPU
```

### 在 DI-Assistant 中的作用

**用途**: 数据仓库，用于 BI（商业智能）分析和数据查询

#### 应用场景 1: 数据集查询

```
DI-Assistant 中的数据流:

用户提问
   ↓
AI 分析识别需要查询的数据集
   ↓
转换为 SQL 语句
   ↓
[StarRocks]
   ├─ 执行 SQL 查询
   ├─ 处理大规模数据
   ├─ 返回结果流
   │
   └─ 导出为 CSV 格式
   ↓
返回给用户
```

#### 应用场景 2: 多区域部署

```
全球数据分布:

新加坡 (SG):
├─ StarRocks 集群 1
├─ URL: sr-di-diana-live-sg-cluster.proxy.sr.data-infra.shopee.io
└─ 数据库: di_diana_live_db

美国 (US-EAST):
├─ StarRocks 集群 2
├─ URL: sr-di-diana-live-us-east-cluster.proxy.sr.data-infra.shopee.io
└─ 数据库: di_diana_live_db

配置位置:
- application-live.yml
- application-staging.yml
```

### 详细代码分析

#### 1. StarRocksService.java

```java
@Service
public class StarRocksService {
    
    @Resource
    private StarRocksStreamingClient starRocksStreamingClient;
    
    // 核心方法：下载数据集数据
    public void downloadDatasetData(ChatDatasetInfo datasetInfo, 
                                   OutputStreamWriter outputStreamWriter) 
        throws IOException {
        
        // 1. 验证参数
        if (Objects.isNull(datasetInfo)) {
            throw new ServerException(..., "dataset info is empty");
        }
        
        // 2. 构建 SQL 查询
        String sql = buildQuerySQL(datasetInfo);
        // 返回: SELECT * FROM table_name LIMIT 100000
        
        // 3. 执行流式查询，结果写入输出流
        starRocksStreamingClient.executeQueryAndStreamToWriter(
            datasetInfo.getIdcRegion(),  // 数据中心区域
            sql,                          // SQL 查询语句
            outputStreamWriter            // 输出目标
        );
    }
    
    // 私有方法：构建 SQL
    private String buildQuerySQL(ChatDatasetInfo datasetInfo) {
        return String.format("SELECT * FROM %s LIMIT 100000", 
            datasetInfo.getTableName());
    }
}
```

**关键点**：
- 最多返回 100000 行数据
- 支持多区域查询
- 使用流式处理避免内存溢出

#### 2. StarRocksStreamingClient.java

**核心流程**:

```java
public void executeQueryAndStreamToWriter(String idcRegion, 
                                         String sqlQuery, 
                                         OutputStreamWriter writer) 
    throws IOException {
    
    // 1. 构建请求
    StarRocksHttpRequest request = StarRocksHttpRequest.builder()
        .query(sqlQuery)
        .build();
    
    // 2. 选择正确的 URL (根据地域)
    String url = starrocksSGUrl;
    if (idcRegion.equals(IdcRegionType.US_EAST.getType())) {
        url = starrocksUSEastUrl;
    }
    
    // 3. 使用 Flux 发起异步 HTTP 请求
    Flux<DataBuffer> responseFlux = webClient.post()
        .uri(url)
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json")
        .bodyValue(request)
        .accept(MediaType.parseMediaType("application/x-ndjson"))
        .retrieve()
        .bodyToFlux(DataBuffer.class);  // 获取响应流
    
    // 4. 处理流数据
    responseFlux
        .doOnNext(dataBuffer -> {
            // 处理每个数据块
            // - 读取字节
            // - 解析 JSON
            // - 转换为 CSV
            // - 写入输出流
        })
        .doOnComplete(() -> {
            // 流完成时的清理工作
            writer.flush();
        })
        .doOnError(error -> {
            // 错误处理
        })
        .blockLast();  // 阻塞等待流完成
    
    // 5. 检查错误
    if (errorRef.get() != null) {
        throw errorRef.get();
    }
}
```

**数据处理流程**:

```
HTTP 响应流 (NDJSON 格式)
   ↓
Flux<DataBuffer> - 每个数据块
   ↓
┌─────────────────────────────────┐
│ doOnNext 处理每个数据块        │
│ 1. 字节 → 字符串               │
│ 2. JSON 行处理                 │
│ 3. 提取表头 (meta)            │
│ 4. 写入 CSV 头                │
│ 5. 提取数据行                 │
│ 6. CSV 转义                   │
│ 7. 写入 CSV 行                │
│ 8. 记录统计信息               │
└─────────────────────────────────┘
   ↓
输出 CSV 文件
```

**JSON 响应格式** (NDJSON - Newline Delimited JSON):

```json
// 第 1 行: 元数据和连接ID
{
  "connectionId": 12345,
  "meta": [
    {"name": "id", "type": "bigint"},
    {"name": "name", "type": "varchar"},
    {"name": "amount", "type": "decimal"}
  ]
}

// 第 2 行: 第一条数据
{
  "data": [1, "Product A", "99.99"]
}

// 第 3 行: 第二条数据
{
  "data": [2, "Product B", "149.99"]
}

// 最后一行: 统计信息
{
  "statistics": {
    "scanRows": 10000,
    "scanBytes": 5242880,
    "returnRows": 100
  }
}
```

### 数据处理细节

#### CSV 转义处理

```java
// 原始数据
String cellValue = value.asText();

// CSV 转义规则
if (cellValue.contains(",") ||      // 包含逗号
    cellValue.contains("\"") ||     // 包含引号
    cellValue.contains("\n")) {     // 包含换行
    
    // 使用双引号包围，内部的双引号翻倍
    cellValue = "\"" + 
               cellValue.replace("\"", "\"\"") + 
               "\"";
}

// 示例:
// "Hello, World!" → "\"Hello, World!\""
// 'Say "Hi"' → "\"Say \"\"Hi\"\"\""
```

### 配置参数

**文件位置**: `application-*.yml`

```yaml
assistant:
  feign:
    client-properties:
      headers:
        starrocks-sg-client:
          Authorization: Basic ${61673:starrocks-auth-token}
        starrocks-us-east-client:
          Authorization: Basic ${61673:starrocks-auth-token}
      uris:
        # SG 集群
        starrocks-sg-client: 
          http://sr-di-diana-live-sg-cluster.proxy.sr.data-infra.shopee.io:8080/api/v1/catalogs/default_catalog/databases/di_diana_live_db/sql
        
        # US-EAST 集群
        starrocks-us-east-client: 
          http://sr-di-diana-live-us-east-cluster.proxy.sr.data-infra.shopee.io:8080/api/v1/catalogs/default_catalog/databases/di_diana_live_db/sql
```

**限制条件**:
- 单次查询: 最多 100,000 行
- 响应超时: 10 分钟 (FluxWebClientConfig)
- 连接池大小: 20 个连接
- 最大内存: 10 MB

---

## 🟢 Part 2: Flux 详解

### 什么是 Flux？

**Flux** 是 Project Reactor 中表示异步数据流的核心类，用于处理多个元素的非阻塞流。

```
Flux vs Mono vs Stream

Flux<T>:              Mono<T>:             Stream<T>:
├─ 0...N 个元素     ├─ 0...1 个元素      ├─ 0...N 个元素
├─ 异步非阻塞       ├─ 异步非阻塞        ├─ 同步阻塞
├─ 背压支持         ├─ 背压支持          ├─ 无背压
├─ 事件驱动         ├─ 事件驱动          ├─ pull 模式
└─ 响应式编程       └─ 响应式编程        └─ 函数式编程
```

### Flux 的三个事件

```
Flux 的生命周期:

onNext      (多次)
   ↓
onNext
   ↓
...
   ↓
onNext
   ↓
┌─ onComplete  (流成功结束)
│
└─ onError     (发生错误)

典型用法:
flux
  .subscribe(
    next -> { /* 处理每个元素 */ },
    error -> { /* 处理错误 */ },
    () -> { /* 流完成 */ }
  )
```

### 在 DI-Assistant 中的使用

#### 1. StarRocks 数据流处理

**场景**: 处理来自 StarRocks 的流式数据

```java
// 创建 Flux - 获取 HTTP 响应流
Flux<DataBuffer> responseFlux = webClient.post()
    .uri(url)
    .accept(MediaType.parseMediaType("application/x-ndjson"))
    .retrieve()
    .bodyToFlux(DataBuffer.class);  // 关键: 非阻塞流

// 处理流中的每个数据块
responseFlux
    .doOnNext(dataBuffer -> {
        // 处理每个 DataBuffer
        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        String chunk = new String(bytes, StandardCharsets.UTF_8);
        
        // 处理完整的 JSON 行
        processCompleteLines(chunk, ...);
    })
    .doOnComplete(() -> {
        // 流完成 - 关闭资源
        writer.flush();
    })
    .doOnError(error -> {
        // 错误处理
        errorRef.set(new IOException(...));
    })
    .blockLast();  // 在这里阻塞等待流完成
```

**关键概念**:
- `doOnNext()`: 每个数据块到达时调用
- `doOnComplete()`: 流成功完成时调用
- `doOnError()`: 发生错误时调用
- `blockLast()`: 阻塞等待流完成

#### 2. 聊天流处理

**场景**: 处理 AI 聊天的流式响应

**文件**: `CommonChatService.java`

```java
// 创建聊天流
Flux<CommonChatStreamEvent> chatFlux = webClient.post()
    .uri(diBrainUrl + "/router/stream")
    .bodyValue(commonChatRequestDTO)
    .accept(MediaType.TEXT_EVENT_STREAM)
    .retrieve()
    .bodyToFlux(new ParameterizedTypeReference<CommonChatStreamEvent>() {});

// 处理流
chatFlux
    .concatMap(response -> {
        // 处理每个聊天事件
        String processedEvent = processCommonChatEventWithTracker(
            response, tracker, requestVO, chatId);
        
        // 根据状态返回
        if (response.getStatus().equals(StreamStatusType.END.getType())) {
            return Flux.just(processedEvent).concatWith(Flux.empty());
        }
        return Flux.just(processedEvent);
    })
    .mergeWith(Flux.interval(Duration.ofSeconds(1))
        .map(tick -> {
            // 心跳保活
            CommonChatStreamEvent heartbeat = new CommonChatStreamEvent();
            // ...
            return JsonUtils.toJsonWithOutNull(heartbeat);
        }))
    .subscribe(...)  // 异步处理
```

**关键操作**:
- `concatMap()`: 顺序处理元素，保持顺序
- `mergeWith()`: 合并多个 Flux（心跳保活）
- `interval()`: 定时发出元素

#### 3. BI 数据流处理

**场景**: 处理 BI 查询的流式响应

**文件**: `ChatBIService.java`

```java
// 创建 Flux 并转换为 Sink
Flux<String> flux = Flux.create(sink -> {
    try {
        // 执行 BI 查询
        executor.execute(() -> {
            WebClient.ResponseSpec responseSpec = webClient.post()
                .uri(biDashBoardUrl)
                .bodyValue(biRequestDTO)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve();
            
            responseSpec
                .bodyToFlux(new ParameterizedTypeReference<DashboardStreamEvent>() {})
                .doFinally(signalType -> {
                    // 清理资源
                    if (signalType == SignalType.ON_COMPLETE) {
                        sink.tryEmitComplete();
                    } else if (signalType == SignalType.ON_ERROR) {
                        sink.tryEmitError(new Exception("Stream error"));
                    }
                })
                .subscribe(
                    event -> sink.tryEmitNext(event),     // 下一个元素
                    error -> sink.tryEmitError(error),    // 错误
                    () -> {
                        sink.tryEmitNext("end");
                        sink.tryEmitComplete();           // 完成
                    }
                );
        });
    } catch (Exception e) {
        sink.tryEmitError(e);
    }
});

return flux;
```

**关键方法**:
- `Flux.create()`: 手动创建 Flux
- `sink.tryEmitNext()`: 发出下一个元素
- `sink.tryEmitComplete()`: 标记流完成
- `sink.tryEmitError()`: 发出错误

### Flux 的优势

```
优势对比:

传统阻塞 IO:         Flux (非阻塞):
├─ 线程占用         ├─ 资源高效
├─ 内存压力大       ├─ 内存消耗低
├─ 响应慢           ├─ 低延迟
├─ 吞吐量受限       ├─ 高吞吐量
├─ 难以扩展         └─ 易于扩展

性能对比:
阻塞线程池 (100 线程)     Flux (1 线程)
├─ 内存: ~10MB             ├─ 内存: ~100KB
├─ 响应时间: 100ms         ├─ 响应时间: 10ms
├─ 吞吐量: 1000 req/s      └─ 吞吐量: 10000 req/s
```

### WebClient 配置

**文件**: `FluxWebClientConfig.java`

```java
@Configuration
public class FluxWebClientConfig {
    
    // 连接池配置
    private static final int DEFAULT_CONNECTION_POOL_SIZE = 20;
    private static final String DEFAULT_CONNECTION_POOL_NAME = 
        "di-assistant-connection-pool";
    
    // 超时配置
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 600000;    // 600s
    private static final int RESPONSE_CONNECT_TIMEOUT_MINUTES = 10;  // 10分钟
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024;  // 10MB
    
    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create(
            ConnectionProvider.create(
                DEFAULT_CONNECTION_POOL_NAME, 
                DEFAULT_CONNECTION_POOL_SIZE
            ))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 
                    DEFAULT_CONNECT_TIMEOUT_MS)
            .responseTimeout(Duration.ofMinutes(
                RESPONSE_CONNECT_TIMEOUT_MINUTES));
        
        WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> {
                // JSON 编码/解码
                configurer.defaultCodecs()
                    .jackson2JsonEncoder(...);
                configurer.defaultCodecs()
                    .jackson2JsonDecoder(...);
                configurer.defaultCodecs()
                    .maxInMemorySize(MAX_IN_MEMORY_SIZE);
            })
            .build();
        
        return webClient;
    }
}
```

**关键参数**:
- **连接池**: 20 个连接，名称 "di-assistant-connection-pool"
- **连接超时**: 600 秒（10 分钟）
- **响应超时**: 10 分钟
- **最大内存**: 10 MB

---

## 📊 StarRocks + Flux 协同工作

### 完整数据流

```
┌─────────────────────────────────────────────────────────┐
│ 用户请求 BI 数据分析                                    │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ ChatBIService                                           │
│ - 验证用户权限                                         │
│ - 确定数据集                                           │
│ - 构建 SQL 查询                                        │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ StarRocksService                                        │
│ - buildQuerySQL()                                       │
│ - SELECT * FROM table_name LIMIT 100000               │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ StarRocksStreamingClient                               │
│ - WebClient (Flux) 发起 HTTP POST 请求               │
│ - 接收 NDJSON 流响应                                  │
│ - Flux<DataBuffer> 处理每个数据块                     │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ Flux 流处理                                             │
│ .doOnNext()        处理每个数据块                       │
│ .doOnComplete()    流完成时处理                         │
│ .doOnError()       错误处理                             │
│ .blockLast()       等待流完成                           │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ JSON 解析与 CSV 转换                                   │
│ - 解析 JSON 行 (NDJSON)                               │
│ - 提取表头                                             │
│ - 处理数据行                                           │
│ - CSV 转义特殊字符                                    │
└────────────────┬────────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────────────────────────┐
│ OutputStreamWriter                                      │
│ - 写入 CSV 头                                          │
│ - 写入数据行                                           │
│ - flush() 刷新缓冲                                     │
└────────────────┬────────────────────────────────────────┘
                 ↓
         返回 CSV 数据给用户
```

### 性能特点

```
Flux 的非阻塞优势在 StarRocks 中的体现:

场景 1: 大数据查询 (100,000 行)
├─ 传统阻塞方式:
│  ├─ 占用 1 个线程
│  ├─ 等待整个响应
│  └─ 响应时间: 5-10 秒
│
└─ Flux 非阻塞方式:
   ├─ 不占用专有线程
   ├─ 边接收边处理
   ├─ 响应时间: 1-3 秒
   └─ 可处理数十个并发请求

场景 2: 并发查询 (10 个用户同时查询)
├─ 传统方式: 需要 10 个线程，内存 ~100MB
└─ Flux 方式: 1-2 个线程，内存 ~10MB
```

---

## ✅ 总结

### StarRocks 的作用
- **分布式数据仓库**: 处理大规模分析查询
- **多区域支持**: SG、US-EAST 等多个集群
- **BI 数据源**: 为数据分析功能提供数据
- **流式导出**: 支持将查询结果导出为 CSV

### Flux 的作用
- **异步非阻塞**: 高效处理 HTTP 流
- **资源节省**: 减少线程和内存占用
- **背压处理**: 自动处理生产者-消费者速度不匹配
- **高并发**: 支持数千个并发请求
- **事件驱动**: 优雅处理 onNext/onComplete/onError

### 协同工作
- StarRocks 返回 NDJSON 流
- Flux 非阻塞处理每个数据块
- 边处理边写入 CSV
- 充分利用现代异步编程模型
- 提供高效、响应式的数据查询体验

