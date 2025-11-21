# CommonChatService & ChatBIService 快速参考指南

## 📌 一页总结

```
CommonChatService (1074 行)
├─ 非流式: commonChatInvoke()       → 一次性返回文本
├─ 流式: commonChatStreamSse()      → SSE 逐步推送
└─ 支持多 Agent (Router)

ChatBIService (656 行)
├─ Flux 模式: textToBIChartV2()     → 返回 Flux<String>
├─ SSE 模式: textToBIChartV2()      → 推送到 SseEmitter
├─ 数据下载: downloadChatBIData()  → CSV/StarRocks/Scheduler
└─ 图表 + 数据
```

---

## 🎯 CommonChatService 速记

### 两个入口方法

```java
// 1. 非流式 (1 个方法)
public CommonChatResponseVO commonChatInvoke(
    CommonChatRequestVO requestVO)
    // Line 133
    // ↓ 8 步
    // ↓ 返回完整响应

// 2. 流式 (SSE)
public void commonChatStreamSse(
    CommonChatRequestVO requestVO, 
    SseEmitter sseEmitter)
    // Line 451
    // ↓ 13 步
    // ↓ 推送到 SseEmitter
```

### 8 步非流式流程

```
1️⃣ 验证权限
2️⃣ 处理"再问一遍"
3️⃣ 获取聊天历史
4️⃣ 提取特殊信息 (ThreadId, Dataset)
5️⃣ 创建用户提问消息
6️⃣ 构建 AI 请求 (超时保护)
7️⃣ 创建响应消息
8️⃣ 返回结果
```

### 13 步流式流程

```
1-4️⃣ 同非流式 (验证、历史、信息)
5️⃣ 创建 Sink (反压缓冲)
6️⃣ 调用 /router/stream API
7️⃣ concatMap 处理 Flux
8️⃣ mergeWith 心跳信号
9️⃣ map 超时检查
🔟 takeUntil 流结束条件
1️⃣1️⃣ doFinally 流结束回调
1️⃣2️⃣ subscribe 订阅流
1️⃣3️⃣ SSE 回调 (timeout/complete/error)
```

### 关键 API

| 方法 | 位置 | 功能 |
|------|------|------|
| `commonChatInvoke()` | Line 133 | 非流式聊天 |
| `commonChatStreamSse()` | Line 451 | 流式 SSE 聊天 |
| `getCommonChatResult()` | Line 192 | 核心 AI 调用逻辑 |
| `toDiBrainChatHistory()` | Line 325 | 历史转换 |
| `processCommonChatEventWithTracker()` | Line 712 | 事件处理 |
| `saveTrackerResultToDatabase()` | Line 792 | 流式结果保存 |

---

## 🎯 ChatBIService 速记

### 两个入口方法

```java
// 1. Flux 模式 (WebFlux)
public Flux<String> textToBIChartV2(
    ChatBIRequestVO biRequestVO)
    // Line 156
    // ↓ 返回 Flux
    
// 2. SSE 模式 (长连接)
public void textToBIChartV2(
    ChatBIRequestVO biRequestVO, 
    SseEmitter sseEmitter)
    // Line 207
    // ↓ 推送到 SseEmitter
    
// 3. 数据下载
public void downloadChatBIData(
    long chatId, String user, 
    OutputStreamWriter outputStreamWriter)
    // Line 507
```

### 流程速记

```
1️⃣ 验证权限
2️⃣ 保存用户提问
3️⃣ 构建 BI 请求
4️⃣ 创建 Sink (反压缓冲)
5️⃣ 调用 /chat_bi/stream API (4 分钟超时)
6️⃣ preProcessEvent: 提取 RunID 到 ThreadLocal
7️⃣ eventFilter: 只要 data 和 error 事件
8️⃣ processChatBIEvent: 处理事件
   ├─ ERROR: 错误处理
   ├─ FAILED: 失败处理
   └─ SUCCESS: ✅ 保存到数据库
9️⃣ doFinally: 清理 ThreadLocal
🔟 subscribe: 推送结果
```

### 关键 API

| 方法 | 位置 | 功能 |
|------|------|------|
| `textToBIChartV2(Flux)` | Line 156 | Flux 模式 |
| `textToBIChartV2(SSE)` | Line 207 | SSE 模式 |
| `preProcessEvent()` | Line 281 | 提取元数据 |
| `eventFilter()` | Line 296 | 过滤事件 |
| `processChatBIEvent()` | Line 302 | 处理事件 |
| `downloadChatBIData()` | Line 507 | 数据下载 |
| `createChatBIRequest()` | Line 416 | 构建请求 |

---

## 🔍 关键概念

### StreamResponseTracker (流式追踪)

```java
StreamResponseTracker tracker = new StreamResponseTracker();
tracker.setStartTime(...)           // 记录开始时间
tracker.setDataScope(...)           // 记录数据范围
tracker.startNewStage(name)         // 开始新阶段
tracker.endStage(name, response)    // 结束阶段
tracker.setFinalResponse(response)  // 设置最终响应
tracker.setCompleted(true)          // 标记完成
tracker.setCanceled(true)           // 标记取消
```

### 事件类型对比

| CommonChatService | ChatBIService | 含义 |
|------------------|---------------|------|
| StreamStatusType.START | - | 开始 |
| StreamStatusType.MESSAGE | - | 中间消息 |
| StreamStatusType.END | - | 结束 |
| StreamStatusType.ERROR | EVENT_ERROR | 错误 |
| - | METADATA | 元数据 (runId) |
| - | SUCCESS_EVENT | 成功 |
| - | FAILED_EVENT | 失败 |

### 反压缓冲 (Backpressure Buffering)

```java
// 创建 Sink
Sinks.Many<String> sink = Sinks.many()
    .multicast()                    // 多个订阅者
    .onBackpressureBuffer();        // 处理背压

// 推送数据
sink.tryEmitNext(data);             // 推送单个数据
sink.tryEmitError(error);           // 推送错误
sink.tryEmitComplete();             // 完成流

// 转换为 Flux
return sink.asFlux();
```

---

## 🚀 常见场景

### 场景 1: 文本转 SQL (通用聊天)

```java
// 请求
CommonChatRequestVO request = new CommonChatRequestVO();
request.setTool("TEXT_2_SQL");  // Agent 类型
request.setQuestion("查询 2024 年销售数据");

// 调用
CommonChatResponseVO response = 
    commonChatService.commonChatInvoke(request);

// 结果
System.out.println(response.getLlmResponse());  // SQL 语句
```

### 场景 2: 流式聊天 (实时推送)

```java
// 请求
CommonChatRequestVO request = ...;

// 响应
SseEmitter emitter = new SseEmitter();

// 调用 (异步)
commonChatService.commonChatStreamSse(request, emitter);

// 前端收到事件流:
// event: {status: START}
// event: {status: MESSAGE, data: "正在分析..."}
// event: {status: END, data: {...}}
```

### 场景 3: 生成图表 (BI)

```java
// 请求
ChatBIRequestVO request = new ChatBIRequestVO();
request.setQuestion("销售趋势图");
request.setTableUidList(List.of("table1", "table2"));

// 调用 (Flux)
Flux<String> flux = 
    chatBIService.textToBIChartV2(request);

// 或调用 (SSE)
SseEmitter emitter = new SseEmitter();
chatBIService.textToBIChartV2(request, emitter);

// 结果包含: SQL + 图表 + 数据
```

### 场景 4: 下载数据 (BI)

```java
// 调用
chatBIService.downloadChatBIData(
    chatId,              // 聊天消息 ID
    user,                // 用户
    outputStreamWriter   // 输出流
);

// 支持三种来源:
// 1. AdhocCode → Scheduler
// 2. ChatDataset → StarRocks
// 3. Dataset → CSV
```

---

## �� 流程图对比

### CommonChatService - 非流式

```
用户请求
  ↓
验证权限 ✓
  ↓
获取历史 ✓
  ↓
保存问题 ✓
  ↓
构建请求 ✓
  ↓
✨ 调用 DiBrain (/commonChat)
  ↓
处理响应 ✓
  ↓
保存回复 ✓
  ↓
返回完整结果
```

### CommonChatService - 流式

```
用户请求
  ↓
验证权限 ✓
  ↓
创建 Sink (反压缓冲)
  ↓
保存问题 ✓
  ↓
✨ 调用 DiBrain (/router/stream)
  ↓
处理事件流:
├─ preProcess: 提取元数据
├─ filter: 过滤事件
├─ map: 处理每个事件
├─ mergeWith: 加入心跳
└─ subscribe: 推送给前端
  ↓
流结束
  ↓
保存结果 ✓
```

### ChatBIService

```
用户请求
  ↓
验证权限 ✓
  ↓
保存问题 ✓
  ↓
创建 Sink (反压缓冲)
  ↓
✨ 调用 DiBrain (/chat_bi/stream, 4 分钟超时)
  ↓
处理事件流:
├─ preProcess: 获取 RunID
├─ filter: 只要 data/error
├─ map: processChatBIEvent
│  ├─ ERROR: 生成错误响应
│  ├─ FAILED: 生成失败响应
│  └─ SUCCESS: ✅ 保存到 DB
└─ subscribe: 推送给前端
  ↓
流结束
  ↓
返回结果
```

---

## 🔧 配置参数

### 超时配置 (AssistantGlobalConfig)

| 参数 | 值 | 用途 |
|------|-----|------|
| `commonChatTimeout` | ? | CommonChatService 超时 |
| 特定超时检查 | Line 519 | 流式超时监控 |
| ChatBIService 超时 | 4 分钟 | BI API 调用超时 |

### 队列配置 (Presto)

```java
// 从 RAM 获取
prestoQueue = ramClient
    .getUserProjectPrestoQueueList(projectCode, idcRegion)
    .getData().getFirst().getQueueName();

// 默认值
prestoQueue = "datago-scheduled";  // SG 地域
prestoQueue = "regdi-scheduled";   // US-EAST 地域
prestoQueue = "regdi-adhoc";       // Debug 用
```

---

## 🎯 关键数据类

| 类 | 用途 |
|----|------|
| `StreamResponseTracker` | 流式响应追踪 |
| `CommonChatStreamEvent` | 流式事件 |
| `CommonChatRequestDTO` | AI 请求 DTO |
| `CommonChatResponseDTO` | AI 响应 DTO |
| `ChatBIResponseDTO` | BI 响应 DTO |
| `GenerateChartEvent` | 图表事件 |
| `StageInfo` | 处理阶段 |

---

## ⚡ 性能关键点

### 反压缓冲

```java
// 解决背压问题的关键
Sinks.many().multicast().onBackpressureBuffer()

// 优点:
// - 不阻塞 Flux 流
// - 缓冲数据直到消费
// - 支持多个订阅者
```

### 心跳信号

```java
// 防止超时断线
.mergeWith(Flux.interval(Duration.ofSeconds(1))
    .map(tick -> buildHeartbeat()))

// 作用:
// - 定期发送 ping 信号
// - 保持连接活跃
// - 前端不会误认为连接断开
```

### ThreadLocal 管理

```java
// 线程安全地传递 RunID
THREAD_LOCAL_RUN_ID.get().set(runId);  // 保存
THREAD_LOCAL_RUN_ID.get().get();       // 获取
THREAD_LOCAL_RUN_ID.get().set("");     // 清理
```

