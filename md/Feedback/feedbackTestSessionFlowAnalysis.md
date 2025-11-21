# 测试反馈会话流式聊天详细分析文档

**时间**: 2025-11-13  
**项目**: di-assistant  
**主要模块**: CommonChatService、ChatService  
**核心流程**: 反馈测试会话 (Test Session In Feedback) 流式响应生成

---

## 目录
1. [整体流程架构](#整体流程架构)
2. [核心数据模型](#核心数据模型)
3. [逐步流程分析](#逐步流程分析)
   - [tracker的核心价值](#-tracker-的核心价值---为什么需要这个对象)
   - [tracker和previousTracker详解](#-tracker-和-previoustracker-的详细作用说明)
4. [数据流转详解](#数据流转详解)
5. [关键函数说明](#关键函数说明)
6. [异常处理机制](#异常处理机制)
7. [测试反馈会话特定标记](#测试反馈会话特定标记)
8. [性能优化考虑](#性能优化考虑)
9. [总结](#总结)

---

## 📊 快速参考：tracker vs previousTracker

| 维度 | tracker | previousTracker |
|------|--------|-----------------|
| **角色** | 当前流的主追踪器 | 流状态的备份快照 |
| **初始值** | 空对象 | 空对象 |
| **更新频率** | 每个事件都更新 | 每个事件处理前备份一次 |
| **数据来源** | DiBrain流事件 + 本地处理 | tracker的备份 |
| **关键字段** | chatId, stageHistory, finalResponse, isCompleted | chatId, stageHistory, currentStage |
| **保存时机** | 流完成/CANCEL时保存 | 不直接保存，仅用于恢复 |
| **使用场景** | 1. 累积流数据 2. 发送给前端 3. 最终保存 | 仅在CANCEL信号时恢复tracker |
| **对stageHistory的处理** | 逐步追加新stage | 指向同一List（非深拷贝） |
| **在正常完成中的作用** | ⭐⭐⭐ 关键 | ⭐ 无作用 |
| **在CANCEL中的作用** | ⭐ 被恢复对象 | ⭐⭐⭐ 关键 |

---

## 整体流程架构

### 高层调用流程图

```
前端请求 (API: /common/chat/stream/feedback)
    ↓
testChatByFeedBack()  [第1129-1198行]
    ↓
├─ 获取历史消息 → getCommonChatMessageHistoryByFeedBack()
├─ 转换为DiBrain格式 → toDiBrainChatHistory()
├─ 创建Question记录到数据库
├─ 创建初始Response记录到数据库
└─ 创建流式订阅
    ↓
createCommonChatStreamSubscription()  [第1038-1127行]
    ↓
POST请求到 DiBrain服务: /router/stream
    ↓
接收Flux<CommonChatStreamEvent>流
    ↓
concatMap() → 处理每个流事件
    ↓
processCommonChatEventWithTracker()  [第673-751行]
    ↓
| 事件处理 | 数据变换 | 返回JSON字符串 |
    ↓
mergeWith() → 心跳机制 (1秒间隔)
    ↓
map() → 超时检测
    ↓
takeUntil() → 等待END或ERROR状态
    ↓
doFinally() → 流结束处理
    ↓
subscribe() → 三个阶段回调
    ├─ onNext(e) → 发送给前端 via SseEmitter
    ├─ onError(err) → 错误处理并发送
    └─ onComplete() → 完成处理
    ↓
saveTrackerResultToDatabase()  [第753-767行]
    ↓
最终数据保存到 chat_message_tab 表
```

---

## 核心数据模型

### 1. StreamResponseTracker（流响应追踪器）

**类路径**: `com.shopee.di.assistant.service.stream.StreamResponseTracker`

**用途**: 多阶段流式响应跟踪和累积

**关键字段**:

| 字段名 | 类型 | 说明 | 初始化时机 |
|-------|------|------|---------|
| `chatId` | Long | 聊天消息ID | createChatMessage后 |
| `currentStage` | String | 当前处理阶段 | "Understanding your question" |
| `finalResponse` | CommonChatResponseVO | 最终响应对象 | 流END时设置 |
| `midState` | Object | 中间状态数据 | 流过程中更新 |
| `stageHistory` | List<StreamStage> | 阶段历史列表 | 流过程中累积 |
| `isCompleted` | boolean | 是否完成 | false → true (END时) |
| `isCanceled` | boolean | 是否被取消 | false → true (CANCEL时) |
| `startTime` | Long | 开始时间戳 | 订阅前设置 |
| `endTime` | Long | 结束时间戳 | 流END时设置 |
| `traceId` | String | 请求追踪ID | 流START事件中获取 |
| `dataScope` | DataScope | 数据范围信息 | 订阅前设置 |
| `questionContent` | RequestVO | 问题信息 | 订阅前设置 |
| `sessionType` | String | 会话类型 | "TEST_SESSION" |

**嵌套类 StreamStage**:

```java
{
  stageName: String,      // 阶段名称，如 "UNDERSTAND_MESSAGE", "GENERATE_SQL"
  status: String,         // 状态: start, message, end
  data: Object,          // 该阶段的返回数据
  startTime: Long,       // 阶段开始时间
  endTime: Long,         // 阶段结束时间
  extraFields: Map       // 额外字段
}
```

### 2. CommonChatStreamEvent（流事件模型）

**类路径**: `com.shopee.di.assistant.common.model.commonchat.stream.CommonChatStreamEvent`

**用途**: Server-Sent Events传输的事件容器

**关键字段**:

| 字段名 | 类型 | 取值 | 说明 |
|-------|------|------|------|
| `event` | CommonChatStreamEventInfo | - | 事件信息（名称、状态） |
| `status` | String | start/message/end/error | 流整体状态 |
| `data` | Object | CommonChatResponseDTO/StreamResponseTracker | 数据负载 |

**event字段结构**:

```java
{
  name: String,        // 事件名称，如 "UNDERSTAND_MESSAGE", "ping"
  status: String       // 事件状态: start, end
}
```

### 3. CommonChatResponseVO（通用聊天响应）

**类路径**: `com.shopee.di.assistant.common.model.commonchat.CommonChatResponseVO`

**用途**: 聊天最终响应结果

**关键字段**:

| 字段名 | 类型 | 说明 |
|-------|------|------|
| `chatId` | Long | 聊天消息ID |
| `tool` | String | 代理类型，如 "common_agent", "error", "dashboard_agent" |
| `resultData` | Object | 结果数据（多态：可能是任意Agent的响应） |
| `tableUidList` | List<String> | 表UID列表 |
| `extendContext` | String | 扩展上下文 |
| `askHuman` | Boolean | 是否需要人工确认 |
| `llmResponse` | String | LLM原始响应文本 |
| `subAgentResponse` | Object | 子代理响应 |
| `midState` | Object | 中间状态 |
| `finalIntent` | String | 最终意图 |

### 4. CommonChatRequestVO（通用聊天请求）

**来源**: 前端API请求

**关键字段**:

| 字段名 | 类型 | 说明 |
|-------|------|------|
| `commonInfo` | CommonInfo | 用户信息 |
| `question` | String | 用户提问 |
| `sessionId` | Long | 会话ID |
| `dataScope` | DataScope | 数据范围（表、数据集等） |
| `extendContext` | String | 扩展上下文 |
| `tool` | String | 指定工具/代理 |
| `originalSql` | String | 原始SQL（如果有） |
| `errorMessage` | String | 错误信息（反馈测试场景） |

---

## 逐步流程分析

### 第一步：入口方法 - testChatByFeedBack()

**位置**: CommonChatService.java 1129-1198行

**函数签名**:
```java
@Transactional(rollbackFor = Exception.class)
public void testChatByFeedBack(
    Long questionId,                    // 原始问题ID
    CommonChatRequestVO requestVO,      // 新的测试请求
    SseEmitter sseEmitter              // SSE发送器
)
```

**执行步骤**:

#### 1.1 初始化追踪器
```java
StreamResponseTracker tracker = new StreamResponseTracker();           // 当前流追踪
StreamResponseTracker previousTracker = new StreamResponseTracker();   // 上一次流追踪（用于恢复）
```

**数据来源**: 构造函数创建新实例  
**数据流向**: 本地变量，后续逐步填充

##### 🎯 tracker 的核心价值 - 为什么需要这个对象？

**一句话概括**: tracker = 流式处理的"进度条" + "数据收集器"

**三大使命**:

```
1️⃣ 收集 → 把流过程中产生的所有中间结果和最终结果聚集在一个对象
2️⃣ 实时 → 不断更新这个对象，让前端能看到实时进展
3️⃣ 持久 → 流完成后把完整的对象保存到数据库
```

**没有 tracker 会有什么问题？**

| 问题 | 后果 | 影响 |
|------|------|------|
| 数据散落各地 | 无法统一管理 | 🔴 流程复杂、容易出错 |
| 无法实时反馈 | 前端看不到进度 | 🔴 用户体验差 |
| 流结束数据分散 | 需要查询多个地方 | 🔴 性能低、难维护 |
| 无法恢复状态 | 中断后数据丢失 | 🔴 无法实现CANCEL恢复 |

**tracker 能做到什么？**

```
Event流进来 → tracker收集 → 返回JSON给前端 → 流结束保存DB
            (所有数据)    (实时进度显示)    (完整记录)
```

**具体场景示例**:

1. **实时进度显示**
   ```
   前端看到的内容：
   ├─ 理解问题... ✓ 完成 (5秒)
   ├─ 生成SQL... ⏳ 进行中 (3秒)
   └─ 验证SQL... ⏳ 等待中
   
   这就是tracker逐步更新后发送给前端的结果
   ```

2. **流被中断时的恢复**
   ```
   用户关闭浏览器
   → 流CANCEL
   → previousTracker恢复tracker
   → 数据库保存最后的完整进度
   → 用户重新打开时看到中断前的进度
   ```

3. **历史查询**
   ```
   点击历史记录查看过去的某次处理：
   ├─ 处理步骤: understand(5s) → sql_gen(10s) → verify(3s)
   ├─ 最终结果: 生成的SQL语句
   ├─ 处理者: DI助手
   └─ 花费时间: 18秒
   
   这些信息都来自保存的tracker对象
   ```

**tracker 包含的完整信息**:

```java
StreamResponseTracker {
    // 基本标识
    chatId: 1002,
    sessionType: "test_session",
    
    // 追踪信息（用于问题排查）
    traceId: "abc123",
    
    // 进度信息（用于显示处理步骤）
    stageHistory: [
        {stageName: "understand", status: "end", startTime: X, endTime: Y},
        {stageName: "sql_generation", status: "end", ...},
        ...
    ],
    
    // 时间信息
    startTime: 1234567800,
    endTime: 1234567900,
    
    // 结果信息
    finalResponse: {
        tool: "sql_agent",
        resultData: {...完整的SQL结果...},
        llmResponse: "...",
        askHuman: false
    },
    
    // 状态信息
    isCompleted: true,
    isCanceled: false,
    midState: {...}
}
```

**tracker 的数据流向**:

```
Event1(START)
  └─ tracker.setTraceId()
     └─ 返回tracker JSON → 前端显示"开始处理"

Event2-N(阶段事件)
  └─ tracker.startNewStage/updateStage/endStage()
     └─ 返回tracker JSON → 前端显示"XX步骤进行中"

EventN(END)
  └─ tracker.setFinalResponse()
  └─ tracker.setCompleted(true)
     └─ 返回tracker JSON → 前端显示"完成！"

流完成(doFinally)
  └─ saveTrackerResultToDatabase(tracker)
     └─ 序列化为JSON保存到数据库
        └─ 用户查看历史时读取这个tracker
```

**为什么需要 tracker？总结**:

| 需求 | 没有tracker | 有tracker | 关键意义 |
|------|-----------|---------|---------|
| 实时显示进度 | ❌ 困难 | ✅ 直接发JSON | 集中收集数据 |
| 显示处理历史 | ❌ 无法做到 | ✅ stageHistory | 完整记录过程 |
| 流结束保存 | ❌ 多次查询拼接 | ✅ 直接保存 | 一个完整对象 |
| CANCEL恢复 | ❌ 无法恢复 | ✅ previousTracker辅助 | 备份稳定状态 |
| 故障排查 | ❌ 信息不全 | ✅ traceId+完整日志 | 追踪能力 |

**核心价值**: tracker 是流式处理系统的**"信息中枢"**
- 集中收集 → 所有信息汇聚到一个对象
- 实时共享 → 每更新一次就发送给前端  
- 完整保存 → 流结束时保存完整历史
- 故障恢复 → CANCEL时能恢复状态
- 问题排查 → 保留完整的追踪信息

---

##### 📌 tracker 和 previousTracker 的详细作用说明

**tracker（当前流追踪器）**:

| 特性 | 描述 |
|------|------|
| **生命周期** | 整个流处理生命周期内持续存在 |
| **初始状态** | 新创建的空对象 |
| **作用** | 累积收集整个流过程中的所有数据和状态 |
| **数据更新** | 流的每个事件都会更新此对象 |
| **保存位置** | 流完成时保存到数据库 chat_message_tab |
| **参与阶段** | 1. 初始化 → 2. 流处理（多次更新） → 3. 最终保存 |

**tracker 在流过程中的更新轨迹**:

```
tracker初始化 (空对象)
    ↓
设置基本信息 (chatId, sessionType, startTime, dataScope)
    ↓
处理Event 1 (START)
    ├─ setTraceId() ← 获取DiBrain请求ID
    ├─ 返回tracker给前端 (JSON)
    ↓
处理Event 2 (阶段事件)
    ├─ startNewStage() ← 记录阶段开始
    ├─ updateStage() ← 更新阶段状态
    ├─ endStage() ← 记录阶段结束
    ├─ 返回tracker给前端 (JSON)
    ↓
处理Event N (END)
    ├─ setFinalResponse() ← 最终响应
    ├─ setMidState() ← 中间状态
    ├─ setCompleted(true)
    ├─ setEndTime() ← 流完成时间
    ├─ 返回tracker给前端 (JSON)
    ↓
流结束 (doFinally)
    ├─ saveTrackerResultToDatabase()
    ├─ 序列化tracker为JSON
    ├─ 保存到数据库
```

**tracker 的关键字段演化**:

| 阶段 | 字段 | 状态 |
|------|------|------|
| 初始化 | chatId, sessionType, startTime | ✅ 已设置 |
| START事件 | traceId | ✅ 已设置 |
| 阶段事件 | stageHistory | ✅ 逐步累积 |
| END事件 | finalResponse, midState, endTime | ✅ 已设置 |
| 流完成 | isCompleted | ✅ true |

---

**previousTracker（上一状态快照）**:

| 特性 | 描述 |
|------|------|
| **生命周期** | 整个流处理过程中持续存在 |
| **初始状态** | 新创建的空对象 |
| **主要作用** | 在流被取消时，保存tracker的上一个稳定状态用于恢复 |
| **更新时机** | 每次处理事件时都备份一次tracker状态 |
| **触发恢复** | 仅在流CANCEL信号时才使用 |

**previousTracker 的生命周期**:

```
concatMap() 阶段  [流开始]
    ↓
每个事件处理前：
    ├─ 第1个事件到达
    │   ├─ previousTracker.setStreamResponseTracker(tracker)
    │   │  // 备份tracker到previousTracker
    │   ├─ 处理当前tracker
    │   ├─ tracker状态改变
    │   ↓
    ├─ 第2个事件到达
    │   ├─ previousTracker.setStreamResponseTracker(tracker)
    │   │  // 再次备份(更新)previousTracker
    │   ├─ 处理当前tracker
    │   ├─ tracker状态改变
    │   ↓
    ├─ 第N个事件到达
    │   ├─ previousTracker.setStreamResponseTracker(tracker)
    │   │  // 最后一次备份previousTracker
    │   ↓
doFinally() - CANCEL信号
    ├─ tracker.setStreamResponseTracker(previousTracker)
    │  // 用备份的previousTracker恢复tracker
    ├─ tracker.setCanceled(true)
    ├─ saveTrackerResultToDatabase(tracker)
    │  // 保存恢复后的tracker到数据库
```

---

**tracker 和 previousTracker 的交互机制**:

```javascript
// 代码位置: CommonChatService.java 1050行和1096-1098行

// 交互点1: 每次事件处理前 (concatMap阶段)
.concatMap(response -> {
    previousTracker.setStreamResponseTracker(tracker);  // 备份
    String processedEvent = processCommonChatEventWithTracker(response, tracker, ...);
    // tracker在此方法内被修改
    // processedEvent返回含最新tracker的JSON
})

// 交互点2: 流被取消时 (doFinally-CANCEL)
.doFinally(signalType -> {
    if (signalType == SignalType.CANCEL) {
        tracker.setStreamResponseTracker(previousTracker);  // 恢复
        tracker.setCanceled(true);
        saveTrackerResultToDatabase(tracker, requestVO);
    }
})
```

---

**具体应用场景说明**:

**场景1: 正常流完成**

```
Event 1: START
  ├─ previousTracker备份(空状态)
  ├─ tracker.setTraceId()
  ├─ 返回tracker JSON

Event 2: UNDERSTAND_MESSAGE START
  ├─ previousTracker备份(含traceId)
  ├─ tracker.startNewStage("understand")
  ├─ 返回tracker JSON

Event 3: SQL_GENERATION END
  ├─ previousTracker备份(含understand stage)
  ├─ tracker.endStage("sql_generation", data)
  ├─ 返回tracker JSON

Event 4: FINAL END
  ├─ previousTracker备份(含前面所有stages)
  ├─ tracker.setFinalResponse(response)
  ├─ tracker.setCompleted(true)
  ├─ 返回tracker JSON

流完成 (ON_COMPLETE)
  ├─ tracker已完整，不需要previousTracker
  ├─ 保存tracker到数据库
```

**场景2: 流被用户取消**

```
Event 1-3: 正常处理
  ├─ 多次备份previousTracker
  ├─ tracker逐步更新

Event 4中途: 用户关闭连接或超时
  ├─ Flux.takeUntil()条件未满足
  ├─ Disposable.dispose()被调用
  ↓
流取消 (SignalType.CANCEL)
  ├─ doFinally(CANCEL)触发
  ├─ 此时tracker可能处于不完整状态
  ├─ tracker.setStreamResponseTracker(previousTracker)
  │  // 恢复到上一个完整状态
  ├─ tracker.setCanceled(true)
  ├─ 保存已恢复的tracker到数据库
  
结果: 用户看到最后一个完整的阶段信息
```

**场景3: 流处理异常**

```
Event 1-N: 正常处理
  ├─ 多次备份previousTracker

Event处理时发生异常
  ├─ processCommonChatEventWithTracker()中throw Exception
  ├─ onError()捕获
  ├─ 此时tracker状态可能不一致
  ├─ previousTracker保持上一个稳定状态
  ├─ 构建错误响应
  ├─ 不使用previousTracker恢复（异常处理不涉及恢复）
  
结果: tracker按当前状态保存，记录异常信息
```

---

**setStreamResponseTracker()方法的含义**:

```java
// StreamResponseTracker.java 65-77行
public void setStreamResponseTracker(StreamResponseTracker tracker) {
    this.chatId = tracker.getChatId();
    this.currentStage = tracker.getCurrentStage();
    this.finalResponse = tracker.getFinalResponse();
    this.midState = tracker.getMidState();
    this.stageHistory = tracker.getStageHistory();           // ⚠️ 引用赋值
    this.isCompleted = tracker.isCompleted();
    this.isCanceled = tracker.isCanceled();
    this.endTime = tracker.getEndTime();
    this.startTime = tracker.getStartTime();
    this.traceId = tracker.getTraceId();
    this.extendContext = tracker.getExtendContext();
}
```

**关键注意**: stageHistory是**引用赋值**，不是深拷贝！这意味着：

| 操作 | 结果 |
|------|------|
| `previousTracker.setStreamResponseTracker(tracker)` | previousTracker和tracker的stageHistory指向同一个List对象 |
| 后续tracker修改stageHistory | previousTracker的stageHistory也会被修改（因为指向同一对象） |
| 流CANCEL时恢复 | previousTracker的其他字段值被恢复，但stageHistory仍是最新的 |

**设计意图**: 
- 备份非List字段的值
- 保持stageHistory的最新累积状态
- 这样恢复后得到的tracker包含流CANCEL前的所有处理步骤

#### 1.2 获取消息历史 (第1134行)
```java
List<ChatMessageTab> messageHistory = 
    chatService.getCommonChatMessageHistoryByFeedBack(
        requestVO.getSessionId(), 
        questionId
    );
```

**数据来源**: 数据库 `chat_message_tab` 表  
**函数说明**: 从指定问题ID开始，获取该问题及之前的聊天历史  
**返回数据类型**: `List<ChatMessageTab>` - 聊天记录实体列表  
**数据流向**: 第1135行历史转换

#### 1.3 转换为DiBrain格式 (第1135行)
```java
List<Map<String, String>> history = toDiBrainChatHistory(messageHistory);
```

**函数说明**: 见[关键函数说明](#关键函数说明)  
**数据转换逻辑**:
- QUESTION类型消息 → `{user: question, selected_tables: "...", selected_table_groups: "..."}`
- RESPONSE类型消息 → `{di_assistant: responseJson}`
  - 如果是StreamResponseTracker → 提取`getFinalResponse()`
  - 否则直接使用CommonChatResponseVO

**返回数据类型**: `List<Map<String, String>>`  
**数据流向**: 第1165行构建DiBrain请求

#### 1.4 获取Thread ID (第1136行)
```java
String threadId = getThreadId(messageHistory);
```

**用途**: Logify Bot的会话标识  
**数据来源**: 历史消息中SESSION_TYPE为LOGIFY_BOT的响应  
**返回数据类型**: String或null  
**数据流向**: 第1166行构建DiBrain请求

#### 1.5 校验数据集 (第1137行)
```java
checkDataset(requestVO, messageHistory);
```

**用途**: 验证选中的数据集是否在有效期内  
**异常**: 若校验失败抛出ServerException

#### 1.6 创建Question记录 (第1142-1144行)

**第一次数据库写入**:

```java
ChatCreateRequestDTO chatCreateRequestDTO = 
    convertor.convertMessageVOToChatCreateDto(requestVO, requestRelation);
Long nowTime = System.currentTimeMillis();
Long chatId = chatService.createChatMessageByTime(chatCreateRequestDTO, nowTime);
```

**写入表**: `chat_message_tab`  
**消息类型**: QUESTION  
**数据内容**: `requestVO.question` + `requestVO.dataScope`  
**返回**: Question记录的ID  
**数据流向**: 第1144行保存，第1151-1152行构建响应记录时使用

#### 1.7 创建Response初始记录 (第1151-1153行)

**第二次数据库写入**:

```java
ChatCreateRequestDTO responseCreateDTO = 
    convertor.convertStreamMessageVOToChatCreateDto(
        tracker,                                  // 空的追踪器
        AgentUtils.buildDiAssistantCommonInfo(), // DI助手身份
        requestVO.getSessionId(),
        null,                                     // traceId未知
        ChatSessionType.COMMON_CHAT.getType(),
        null,                                     // midState
        messageExtraInfo                          // {stream: true, userSetting}
    );
Long responseChatId = chatService.createChatMessage(responseCreateDTO);
```

**写入表**: `chat_message_tab`  
**消息类型**: RESPONSE  
**初始数据**: 空的StreamResponseTracker  
**sessionType**: "common_chat" 或测试指定的类型  
**返回**: Response记录的ID  
**数据流向**: 
- 第1162行设置 `tracker.setChatId(responseChatId)`
- 后续流处理中会逐步更新此记录

#### 1.8 设置追踪器初始数据 (第1154-1163行)

```java
tracker.setQuestionContent(RequestVO.builder()
    .chatId(chatId)
    .question(requestVO.getQuestion())
    .user(requestVO.getCommonInfo().getUser())
    .userEmail(requestVO.getCommonInfo().getUserEmail())
    .region(requestVO.getCommonInfo().getRegion())
    .createTime(nowTime)
    .build());
tracker.setChatId(responseChatId);
tracker.setSessionType(ChatSessionType.TEST_SESSION.getType());  // 关键标记
```

**追踪器状态**:
- `questionContent`: 原始问题信息
- `chatId`: Response记录ID
- `sessionType`: "test_session"

#### 1.9 构建DiBrain请求 (第1165-1166行)

```java
CommonChatRequestDTO commonChatRequestDTO = 
    createCommonChatStreamRequest(
        requestVO,                          // 用户请求
        ModelType.gpt_4_1.getType(),       // 固定使用GPT-4.1模型
        history,                            // 历史记录
        threadId,                           // Logify Thread ID
        chatId,                             // Question消息ID
        userSettingDetailVO                 // 用户设置
    );
```

**函数说明**: 见[createCommonChatStreamRequest](#createcommonchatsreamrequest)

**返回数据类型**: `CommonChatRequestDTO`  
**数据流向**: 第1170-1171行POST请求

#### 1.10 设置追踪器时间戳 (第1168-1169行)

```java
tracker.setStartTime(System.currentTimeMillis());
tracker.setDataScope(requestVO.getDataScope());
```

**用途**: 记录流开始时间，用于后续超时检测

#### 1.11 创建流订阅 (第1170-1171行)

```java
Disposable subscription = createCommonChatStreamSubscription(
    commonChatRequestDTO, 
    tracker, 
    previousTracker, 
    requestVO, 
    chatId, 
    sseEmitter
);
```

**函数说明**: 见[createCommonChatStreamSubscription](#createcommonchatstreamsubscription)

**返回数据类型**: `Disposable` - Reactor响应式订阅  
**用途**: 管理流生命周期

#### 1.12 注册SSE事件回调 (第1173-1187行)

```java
// 超时回调
sseEmitter.onTimeout(() -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();  // 停止Flux流
    }
});

// 完成回调
sseEmitter.onCompletion(() -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();
    }
});

// 错误回调
sseEmitter.onError((throwable) -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();
    }
});
```

**机制**: 当SSE连接超时/完成/错误时，停止Reactor流

#### 1.13 异常处理 (第1188-1196行)

```java
catch (Exception e) {
    log.error("Error in CommonChat SSE stream processing", e);
    String errStr = buildCommonChatFailedResponse(
        tracker, 
        tracker.getCurrentStage(), 
        MessageConstants.COMMON_CHAT_ERROR_MESSAGE
    );
    try {
        sseEmitter.send(errStr);
        sseEmitter.complete();
    } catch (IOException ex) {
        sseEmitter.completeWithError(ex);
    }
}
```

**错误处理流程**:
1. 捕获任何异常
2. 生成错误响应JSON
3. 发送给前端
4. 关闭SSE连接

---

### 第二步：构建请求 - createCommonChatStreamRequest()

**位置**: CommonChatService.java 551-671行

**函数签名**:
```java
private CommonChatRequestDTO createCommonChatStreamRequest(
    CommonChatRequestVO requestVO,
    String model,                    // 模型名称
    List<Map<String, String>> history,
    String threadId,
    Long chatId,
    UserSettingDetailVO userSettingDetailVO
)
```

**主要职责**: 将前端请求转换为DiBrain API的CommonChatRequestDTO

**返回数据结构**:

```java
CommonChatRequestDTO {
  config: {
    configurable: {
      llm: "gpt-4.1"           // 固定模型
    },
    metadata: {
      sqlDialect: "PRESTO",    // SQL方言
      supportSkipAuth: true
    }
  },
  input: {
    chatContext: {
      // 包含用户信息、数据库凭证、队列等
    },
    question: String,            // 用户问题
    sessionId: Long,
    chatId: Long,
    logStoreId: Long,
    agentName: String,           // Agent类型
    threadId: String,            // Logify线程ID
    originalSql: String,         // 原始SQL
    errorMessage: String,        // 错误信息
    chatHistory: List,           // 历史记录
    selectedTable: List,         // 选中的表
    selectedTableGroup: List,    // 选中的表组
    tableContext: {
      hiveTables: List
    }
  }
}
```

**数据来源与流向**:

| 来源 | 字段 | 流向 |
|------|------|------|
| requestVO | question, dataScope, tool | input字段 |
| userSettingDetailVO | 用户设置、权限 | chatContext字段 |
| ramClient调用 | 用户信息、队列 | chatContext字段 |
| history参数 | 聊天历史 | input.chatHistory |
| requestVO.dataScope | 表、数据集 | input.selectedTable/selectedTableGroup |

**关键处理**:
1. 从RAM获取用户信息和Presto队列
2. 转换DataScope中的表信息为TableEntity
3. 转换ChatBITopicEntityVO和DataMart为TableGroupEntity
4. 构建完整的ChatContext

**返回数据类型**: `CommonChatRequestDTO`  
**数据流向**: 第1170行POST到DiBrain

---

### 第三步：建立流连接 - createCommonChatStreamSubscription()

**位置**: CommonChatService.java 1038-1127行

**函数签名**:
```java
private Disposable createCommonChatStreamSubscription(
    CommonChatRequestDTO commonChatRequestDTO,
    StreamResponseTracker tracker,
    StreamResponseTracker previousTracker,
    CommonChatRequestVO requestVO,
    Long chatId,
    SseEmitter sseEmitter
)
```

**核心职责**: 建立WebClient流连接，处理每个流事件，并发送给前端

#### 3.1 POST请求阶段 (第1044-1049行)

```java
return webClient.post()
    .uri(diBrainUrl + "/router/stream")
    .bodyValue(commonChatRequestDTO)
    .accept(MediaType.TEXT_EVENT_STREAM)
    .retrieve()
    .bodyToFlux(new ParameterizedTypeReference<CommonChatStreamEvent>() { });
```

**请求目标**: POST `{diBrainUrl}/router/stream`  
**请求体**: 序列化的`CommonChatRequestDTO`  
**响应类型**: `Flux<CommonChatStreamEvent>` - 无限流  
**数据来源**: DiBrain服务  
**返回数据类型**: Reactor Flux发布者

#### 3.2 事件处理 - concatMap阶段 (第1050-1060行)

```java
.concatMap(response -> {
    previousTracker.setStreamResponseTracker(tracker);
    String processedEvent = processCommonChatEventWithTracker(
        response,      // CommonChatStreamEvent
        tracker,       // 累积追踪器
        requestVO,
        chatId
    );
    if (processedEvent == null) {
        return Flux.empty();  // 跳过此事件
    }
    if (Objects.nonNull(response) && Objects.nonNull(response.getStatus())
        && (Objects.equals(StreamStatusType.END.getType(), response.getStatus()) 
            || Objects.equals(StreamStatusType.ERROR.getType(), response.getStatus()))) {
        return Flux.just(processedEvent).concatWith(Flux.empty());  // 流结束
    }
    return Flux.just(processedEvent);  // 继续流
})
```

**处理逻辑**:

| 条件 | 处理 | 说明 |
|------|------|------|
| processedEvent == null | 返回 Flux.empty() | 跳过该事件（如重复的UNDERSTAND_MESSAGE） |
| status == END/ERROR | 发出事件后结束流 | Flux.just(...).concatWith(Flux.empty()) |
| 其他 | 继续流 | Flux.just(processedEvent) |

**函数调用**: 见[processCommonChatEventWithTracker](#processcommonchasteventwithtracker)

**数据流向**: 合并处理后的事件JSON字符串

#### 3.3 心跳机制 - mergeWith阶段 (第1061-1068行)

```java
.mergeWith(Flux.interval(Duration.ofSeconds(1))
    .map(tick -> {
        CommonChatStreamEvent heartbeat = new CommonChatStreamEvent();
        heartbeat.setEvent(CommonChatStreamEventInfo.builder()
            .name("ping")
            .build());
        return JsonUtils.toJsonWithOutNull(heartbeat);
    })
)
```

**用途**: 保持SSE连接活跃，防止超时

**发送间隔**: 每1秒发送一个心跳

**心跳格式**: JSON `{"event":{"name":"ping"}}`

**数据来源**: 定时器  
**数据流向**: 与业务事件合并

#### 3.4 超时检测 - map阶段 (第1070-1076行)

```java
.map(event -> {
    long currentTime = System.currentTimeMillis();
    long timeoutMs = assistantGlobalConfig.getCommonChatTimeout() * 1000L;
    if (currentTime - tracker.getStartTime() > timeoutMs) {
        throw new ServerException(ResponseCodeEnum.STREAM_TIMEOUT_ERROR);
    }
    return event;
})
```

**机制**: 检查是否超过配置的超时时间

**超时触发**: 如果超时则抛出异常，被错误回调捕获

**配置来源**: `assistantGlobalConfig.getCommonChatTimeout()` (秒)

#### 3.5 流终止条件 - takeUntil阶段 (第1078-1086行)

```java
.takeUntil(event -> {
    if (event instanceof String) {
        CommonChatStreamEvent streamEvent = JsonUtils.toObject(event, CommonChatStreamEvent.class);
        return Objects.nonNull(streamEvent)
            && Objects.nonNull(streamEvent.getStatus())
            && (Objects.equals(StreamStatusType.END.getType(), streamEvent.getStatus())
            || Objects.equals(StreamStatusType.ERROR.getType(), streamEvent.getStatus()));
    }
    return false;
})
```

**机制**: 当收到END或ERROR状态的事件时，立即停止流

**条件判断**:
1. 事件转换为JSON字符串
2. 反序列化为CommonChatStreamEvent
3. 检查status是否为END或ERROR
4. 是则返回true（停止流）

#### 3.6 流结束处理 - doFinally阶段 (第1088-1100行)

```java
.doFinally(signalType -> {
    log.info("CommonChat SSE stream ended with signal: {}", signalType);
    if (signalType == SignalType.ON_COMPLETE) {
        log.info("CommonChat SSE stream completed normally.");
        tracker.setCompleted(true);
    } else if (signalType == SignalType.ON_ERROR) {
        log.info("CommonChat SSE stream terminated due to an error.");
    } else if (signalType == SignalType.CANCEL) {
        log.info("CommonChat SSE stream was cancelled.");
        tracker.setStreamResponseTracker(previousTracker);
        tracker.setCanceled(true);
    }
    saveTrackerResultToDatabase(tracker, requestVO);
})
```

**触发时机**: 流完全结束（任何原因）

**处理逻辑**:

| 结束信号 | 处理 |
|---------|------|
| ON_COMPLETE | 标记`isCompleted = true` |
| ON_ERROR | 记录错误（已在onError回调处理） |
| CANCEL | 恢复previousTracker，标记`isCanceled = true` |

**关键操作**: `saveTrackerResultToDatabase()` - 保存最终结果

##### 🔄 doFinally - CANCEL信号的详细处理

**CANCEL信号产生的情况**:

1. **用户主动取消**: 前端关闭SSE连接
2. **超时取消**: 通过`subscription.dispose()`被调用
3. **Disposable生命周期结束**: 订阅被处置

**CANCEL处理的特殊性**:

```
场景：用户在第3个阶段中途取消

正常应该有5个阶段的流程：
  Event 1: START
  Event 2: UNDERSTAND_MESSAGE (START → END)
  Event 3: SQL_GENERATION (START → 处理中)
  Event 4: EXECUTE (START → END)     // 这些未到达
  Event 5: FINAL (END)               // 这些未到达

用户在Event 3中途取消：
  ├─ Event 1已处理完整
  ├─ Event 2已处理完整，已保存到stageHistory
  ├─ Event 3处于PROCESSING状态
  │
CANCEL信号触发：
  ├─ tracker当前状态：
  │   ├─ stageHistory: [START stage, UNDERSTAND stage, SQL_GENERATION stage]
  │   ├─ currentStage: "SQL_GENERATION"
  │   ├─ finalResponse: null (未到达)
  │   ├─ isCompleted: false
  │
  ├─ previousTracker状态 (Event 2之后的备份)：
  │   ├─ stageHistory: [START stage, UNDERSTAND stage, SQL_GENERATION stage]
  │   ├─ currentStage: "SQL_GENERATION"
  │   ├─ finalResponse: null
  │   ├─ isCompleted: false
  │
处理逻辑:
  ├─ tracker.setStreamResponseTracker(previousTracker)
  │   // 这会恢复：currentStage, finalResponse, isCompleted等字段
  │   // 但stageHistory因为是引用，仍保持最新值
  │
恢复后的tracker状态：
  ├─ stageHistory: [START stage, UNDERSTAND stage, SQL_GENERATION stage]
  │  (保持最新，包含已处理的所有阶段)
  ├─ currentStage: "SQL_GENERATION" (恢复的值)
  ├─ finalResponse: null (恢复的值)
  ├─ isCanceled: true (新设置)
  │
结果：用户看到前面完整的两个阶段 + 中断标记
```

**为什么要使用previousTracker?**

| 不使用previousTracker的问题 | 使用previousTracker的好处 |
|---------------------------|----------------------|
| 流被中断时，tracker状态可能不一致 | 恢复到最后一个稳定的"完整阶段" |
| 某些字段可能是中间态 | 避免前端显示不完整的数据 |
| 数据库保存的数据可能是脏数据 | 保存的是用户看到的最后完整信息 |

**具体对比**:

```
不使用恢复：
  tracker状态 {
    stageHistory: [START, UNDERSTAND, SQL_GENERATION(processing)],
    finalResponse: null,
    currentStage: "SQL_GENERATION",
    isCompleted: false,
    isCanceled: false  // ⚠️ 没有标记为已取消
  }
  问题：用户看到SQL_GENERATION还在处理，但实际已取消

使用恢复：
  tracker状态 {
    stageHistory: [START, UNDERSTAND, SQL_GENERATION(processing)],
    finalResponse: null,
    currentStage: "SQL_GENERATION",  // 恢复的值
    isCompleted: false,
    isCanceled: true  // ✅ 明确标记为已取消
  }
  好处：用户看到最后完整处理的信息，明确知道后续被取消了
```

#### 3.7 订阅回调 - subscribe阶段 (第1101-1126行)

```java
.subscribe(
    // onNext 回调：处理每个事件
    e -> {
        try {
            sseEmitter.send(e);  // 发送事件给前端
        } catch (IOException ex) {
            sseEmitter.completeWithError(ex);
        }
    },
    // onError 回调：处理流异常
    err -> {
        String error;
        boolean isTimeout = (err instanceof java.util.concurrent.TimeoutException)
            || (err instanceof ServerException 
                && ((ServerException) err).getResponseCodeEnum()
                    .equals(ResponseCodeEnum.STREAM_TIMEOUT_ERROR));
        if (isTimeout) {
            error = buildCommonChatFailedResponse(
                tracker, 
                tracker.getCurrentStage(), 
                MessageConstants.COMMON_TIMEOUT_PREFIX_TEXT
            );
        } else {
            error = buildCommonChatFailedResponse(
                tracker, 
                tracker.getCurrentStage(), 
                MessageConstants.COMMON_CHAT_ERROR_MESSAGE
            );
        }
        try {
            sseEmitter.send(error);
        } catch (IOException e) {
            log.error("Failed to send error response", e);
        }
        sseEmitter.completeWithError(err);
    },
    // onComplete 回调：流完成
    () -> sseEmitter.complete()
);
```

**三个回调**:

1. **onNext(e)**: 
   - 将事件JSON发送给前端
   - 前端通过EventSource接收显示

2. **onError(err)**:
   - 区分超时与其他异常
   - 构建错误响应
   - 发送给前端
   - 关闭SSE连接

3. **onComplete()**:
   - 正常完成SSE连接

**返回数据类型**: Disposable  
**用途**: 控制订阅生命周期

---

### 第四步：处理流事件 - processCommonChatEventWithTracker()

**位置**: CommonChatService.java 673-751行

**函数签名**:
```java
private String processCommonChatEventWithTracker(
    CommonChatStreamEvent response,     // 来自DiBrain的事件
    StreamResponseTracker tracker,      // 累积追踪器
    CommonChatRequestVO requestVO,
    Long requestId                      // 问题消息ID
)
```

**核心职责**: 解析流事件，更新追踪器，返回前端JSON

**返回数据类型**: String (JSON格式事件) 或 null (跳过)

#### 4.1 错误状态处理 (第674-677行)

```java
if (Objects.equals(StreamStatusType.ERROR.getType(), response.getStatus())) {
    log.error("CommonChat stream error: {}", response.getData());
    return buildCommonChatFailedResponse(
        tracker, 
        response.getEvent().getName(), 
        MessageConstants.COMMON_CHAT_ERROR_MESSAGE
    );
}
```

**触发条件**: 整体流状态为ERROR  
**处理**: 生成错误响应JSON  
**返回**: JSON字符串

#### 4.2 START状态处理 (第689-694行)

**流状态**: `status == START`

```java
if (Objects.equals(status, StreamStatusType.START)) {
    CommonChatResponseDTO responseDTO = 
        JsonUtils.convertObjectToClass(data, CommonChatResponseDTO.class);
    tracker.setTraceId(responseDTO.getMetadata().getRunId());
    response.setData(tracker);  // 替换data为追踪器
    return JsonUtils.toJsonWithOutNull(response);
}
```

**数据转换**:

| 来源 | 处理 | 流向 |
|------|------|------|
| response.data | 反序列化为CommonChatResponseDTO | 获取runId |
| responseDTO.getMetadata().getRunId() | 保存为traceId | tracker.traceId |
| tracker | 替换response.data | 返回的JSON中 |

**返回**: 包含tracker的完整JSON

#### 4.3 END状态处理 (第696-712行)

**流状态**: `status == END` （最终结果）

```java
if (Objects.equals(status, StreamStatusType.END)) {
    CommonChatResponseDTO responseDTO = 
        JsonUtils.convertObjectToClass(data, CommonChatResponseDTO.class);
    
    // 转换为最终响应对象
    CommonChatResponseVO commonChatResponseVO = 
        chatProcessor.convertCommonChat(
            responseDTO.getOutput().getAskHuman(),
            requestVO.getDataScope(),
            responseDTO.getOutput().getLlmRawResponse(),
            responseDTO.getOutput().getSubAgentResponse(),
            AgentType.valueOfString(responseDTO.getOutput().getResponseAgent())
                .getCorrespondingSessionType(),
            requestVO.getLogStoreId(),
            requestVO.getTool(),
            requestVO.getOriginalSql(),
            requestId
        );
    
    // 设置响应字段
    commonChatResponseVO.setLlmResponse(responseDTO.getOutput().getLlmRawResponse());
    commonChatResponseVO.setAskHuman(responseDTO.getOutput().getAskHuman());
    commonChatResponseVO.setSubAgentResponse(responseDTO.getOutput().getSubAgentResponse());
    commonChatResponseVO.setExtendContext(requestVO.getExtendContext());
    commonChatResponseVO.setFinalIntent(responseDTO.getOutput().getFinalIntent());
    
    // 更新追踪器
    tracker.setEndTime(System.currentTimeMillis());
    tracker.setMidState(responseDTO.getOutput().getMidState());
    tracker.setFinalResponse(commonChatResponseVO);
    tracker.setCompleted(true);
    
    response.setData(tracker);
    return JsonUtils.toJsonWithOutNull(response);
}
```

**数据流转**:

```
DiBrain返回的CommonChatResponseDTO
    ↓
chatProcessor.convertCommonChat()
    ↓
CommonChatResponseVO (多态，可能是多种Agent的结果)
    ↓
设置各种字段 (llmResponse, askHuman, etc.)
    ↓
保存到 tracker.finalResponse
    ↓
tracker.isCompleted = true
    ↓
返回含tracker的JSON
```

**关键更新**:
- `tracker.endTime`: 流结束时间
- `tracker.midState`: 中间状态数据
- `tracker.finalResponse`: 最终业务结果
- `tracker.isCompleted`: 完成标记

#### 4.4 重复事件过滤 (第714-716行)

```java
if (Objects.equals(eventName, CommonConstants.UNDERSTAND_MESSAGE) 
    && Objects.equals(tracker.isUnderstand(), true)) {
    return null;  // 跳过重复的理解消息
}
```

**机制**: 避免重复发送UNDERSTAND_MESSAGE事件

#### 4.5 事件状态处理 - switch语句 (第718-742行)

**流事件状态** (不同于整体流status)

```java
switch (eventStatus) {
    case START:  // 事件开始
        tracker.startNewStage(eventName);
        break;
    
    case END:    // 事件结束
        if (Objects.equals(eventName, CommonConstants.UNDERSTAND_MESSAGE)) {
            tracker.setUnderstand(true);
        }
        CommonChatResponseDTO responseDTO = 
            JsonUtils.convertObjectToClass(data, CommonChatResponseDTO.class);
        if (Objects.nonNull(responseDTO.getOutput())) {
            CommonChatResponseVO commonChatResponseVO = 
                chatProcessor.convertCommonChat(...);
            commonChatResponseVO.setAskHuman(...);
            commonChatResponseVO.setSubAgentResponse(...);
            commonChatResponseVO.setExtendContext(...);
            commonChatResponseVO.setFinalIntent(...);
            tracker.endStage(response.getEvent().getName(), commonChatResponseVO);
        } else {
            tracker.endStage(response.getEvent().getName(), null);
        }
        return null;  // 中间阶段不返回JSON
    
    default:  // 事件进行中 (message)
        tracker.updateStage(
            response.getEvent().getName(), 
            response.getEvent().getStatus(), 
            null
        );
        break;
}
```

**处理逻辑**:

| 事件状态 | 处理 | 返回值 |
|---------|------|-------|
| START | startNewStage() 记录阶段开始 | null |
| END | endStage() 记录阶段结束和数据 | null |
| MESSAGE | updateStage() 更新阶段信息 | 继续处理 |

**返回阶段JSON** (第743-746行):

```java
response.setData(tracker);  // 替换为追踪器
log.debug("Processing CommonChat event data: {}", response);
return JsonUtils.toJsonWithOutNull(response);  // 返回JSON
```

**异常处理** (第747-750行):

```java
catch (Exception e) {
    log.error("Error processing CommonChat event with tracker", e);
    return buildCommonChatFailedResponse(
        tracker, 
        response.getEvent().getName(), 
        MessageConstants.COMMON_CHAT_ERROR_MESSAGE
    );
}
```

---

### 第五步：保存结果 - saveTrackerResultToDatabase()

**位置**: CommonChatService.java 753-767行

**函数签名**:
```java
private void saveTrackerResultToDatabase(
    StreamResponseTracker tracker,
    CommonChatRequestVO requestVO
)
```

**执行时机**: 流完全结束时（doFinally阶段）

**执行步骤**:

#### 5.1 构建保存请求 (第754-764行)

```java
ChatCreateRequestDTO chatCreateRequestDTO;
MessageExtraInfo messageExtraInfo = MessageExtraInfo.builder()
    .stream(true)
    .build();

if (Objects.nonNull(tracker.getFinalResponse())) {
    chatCreateRequestDTO = convertor.convertStreamMessageVOToChatCreateDto(
        tracker,
        AgentUtils.buildDiAssistantCommonInfo(),
        requestVO.getSessionId(),
        Optional.ofNullable(tracker.getTraceId()).orElse(CommonConstants.BLANK_STRING),
        tracker.getFinalResponse().getTool(),  // 响应agent类型
        tracker.getMidState(),
        messageExtraInfo
    );
} else {
    chatCreateRequestDTO = convertor.convertStreamMessageVOToChatCreateDto(
        tracker,
        AgentUtils.buildDiAssistantCommonInfo(),
        requestVO.getSessionId(),
        Optional.ofNullable(tracker.getTraceId()).orElse(CommonConstants.BLANK_STRING),
        ChatSessionType.COMMON_CHAT.getType(),
        tracker.getMidState(),
        messageExtraInfo
    );
}
```

**数据来源**:
- `tracker`: 流过程中累积的完整数据
- `getFinalResponse()`: 最终响应VO
- `getTraceId()`: DiBrain返回的请求ID
- `getMidState()`: 中间状态

**转换内容**: StreamResponseTracker → ChatMessageTab

#### 5.2 数据库保存 (第765-766行)

```java
chatService.rewriteChatMessage(tracker.getChatId(), chatCreateRequestDTO);
sessionService.updateSessionTime(chatCreateRequestDTO.getSessionId());
```

**第三次数据库写入**:
- **表**: `chat_message_tab`
- **操作**: UPDATE (对应初始化创建的Response记录)
- **更新字段**:
  - `chat_content`: 序列化的StreamResponseTracker JSON
  - `mid_state`: 中间状态
  - `session_type`: 对应的agent类型
  - `modify_time`: 当前时间

**同时更新**: `chat_session_tab` 的最后访问时间

---

## 数据流转详解

### 完整数据流向图

```
1. 用户反馈请求输入
   ↓
   CommonChatRequestVO {
       question: "修复这个SQL",
       sessionId: 123,
       dataScope: {...},
       errorMessage: "语法错误"
   }

2. 数据库操作1：创建Question记录
   ↓
   chat_message_tab (ID: 1001)
   {
       message_type: "QUESTION",
       chat_content: "{question: '修复...', dataScope: {...}}",
       session_id: 123
   }

3. 数据库操作2：创建初始Response记录
   ↓
   chat_message_tab (ID: 1002)
   {
       message_type: "RESPONSE",
       chat_content: "{}",  // 初始化空的StreamResponseTracker
       session_id: 123,
       session_type: "common_chat"
   }

4. 构建DiBrain请求
   ↓
   CommonChatRequestDTO {
       config: {...},
       input: {
           question: "修复这个SQL",
           chatHistory: [...],
           selectedTable: [...]
       }
   }

5. HTTP POST → DiBrain
   ↓
   POST {diBrainUrl}/router/stream
   Content-Type: application/json
   Accept: text/event-stream

6. 接收流事件
   ↓
   Event 1: {status: "start", data: {...}}
       ↓ processCommonChatEventWithTracker()
       ↓ tracker.traceId = "abc123"
       ↓ 返回JSON → sseEmitter.send()
       ↓ 前端接收

   Event 2: {event: {name: "understand", status: "start"}, ...}
       ↓ tracker.startNewStage("understand")
       ↓ 返回JSON

   Event 3: {event: {name: "sql_generation", status: "message"}, ...}
       ↓ tracker.updateStage(...)
       ↓ 返回JSON

   Event N: {status: "end", data: {...}}
       ↓ tracker.finalResponse = CommonChatResponseVO{...}
       ↓ tracker.isCompleted = true
       ↓ 返回JSON → sseEmitter.send()

7. 流结束 (doFinally)
   ↓
   saveTrackerResultToDatabase()

8. 数据库操作3：更新Response记录
   ↓
   chat_message_tab (ID: 1002) UPDATE
   {
       chat_content: {
           "chatId": 1002,
           "finalResponse": {...},
           "stageHistory": [...],
           "isCompleted": true,
           "startTime": 1699862400000,
           "endTime": 1699862425000,
           "traceId": "abc123"
       }
   }

9. 会话表更新
   ↓
   chat_session_tab UPDATE
   {
       last_message_time: now()
   }
```

### 数据类型转换链

```
CommonChatRequestVO (前端请求)
    ↓
CommonChatRequestDTO (DiBrain请求)
    ↓
Flux<CommonChatStreamEvent> (WebClient流)
    ↓ concatMap()
    ↓
processCommonChatEventWithTracker()
    ↓
CommonChatResponseDTO (DiBrain中间响应)
    ↓
CommonChatResponseVO (业务响应对象)
    ↓
StreamResponseTracker (累积追踪)
    ↓ JSON序列化
    ↓
String (前端展示JSON)
    ↓ SseEmitter.send()
    ↓
EventSource事件 (浏览器前端)
    ↓
StreamResponseTracker JSON → 存储
    ↓
chat_message_tab.chat_content (数据库)
```

---

## 关键函数说明

### toDiBrainChatHistory()

**位置**: 342-393行

**用途**: 将数据库聊天记录转换为DiBrain格式

**输入**: `List<ChatMessageTab>` - 消息历史

**转换逻辑**:

```
QUESTION消息
    ↓
{
  "user": "用户提问",
  "selected_tables": "[...]",
  "selected_table_groups": "[...]"
}

RESPONSE消息 (StreamResponseTracker格式)
    ↓ 提取 finalResponse
    ↓
{
  "di_assistant": {
    "responseAgent": "...",
    "askHuman": true/false,
    "llmRawResponse": "...",
    "subAgentResponse": {...}
  }
}

RESPONSE消息 (普通格式)
    ↓
{
  "di_assistant": "普通响应JSON"
}
```

**输出**: `List<Map<String, String>>` - 历史记录

### buildCommonChatFailedResponse()

**位置**: 769-781行

**用途**: 生成错误响应JSON

**参数**:
- `tracker`: 当前追踪器
- `name`: 事件名称
- `errorMessage`: 错误信息

**返回**: 错误事件JSON字符串

```java
{
  "event": {"name": "...", "status": "end"},
  "status": "error",
  "data": {
    "chatId": 1002,
    "finalResponse": {
      "tool": "error",
      "llmResponse": "错误信息",
      "askHuman": false
    },
    "stageHistory": [...]
  }
}
```

### ChatService.rewriteChatMessage()

**位置**: ChatService.java 185-201行

**用途**: 更新已存在的聊天消息

**操作**: UPDATE chat_message_tab

**字段更新**:
- `chat_content`: 新内容（序列化的StreamResponseTracker）
- `session_type`: Agent类型
- `model`: 模型名称
- `modify_time`: 当前时间

---

## 异常处理机制

### 1. 流超时处理

**触发条件**: 当前时间 - startTime > 配置的超时时间

**处理流程**:

```
map()检测超时
    ↓
throw ServerException(STREAM_TIMEOUT_ERROR)
    ↓
onError()捕获
    ↓
isTimeout判断
    ↓
构建超时错误响应
    ↓
sseEmitter.send(error)
    ↓
sseEmitter.completeWithError()
```

**返回给前端**: 超时错误JSON

### 2. 业务异常处理

**触发**: 业务逻辑异常

**处理流程**:

```
任何阶段的Exception
    ↓
catch块捕获
    ↓
buildCommonChatFailedResponse()
    ↓
sseEmitter.send(errStr)
    ↓
sseEmitter.complete()
```

### 3. SSE连接异常处理

**超时异常**:
```java
sseEmitter.onTimeout(() -> {
    subscription.dispose();  // 停止流
});
```

**完成异常**:
```java
sseEmitter.onCompletion(() -> {
    subscription.dispose();
});
```

**错误异常**:
```java
sseEmitter.onError((throwable) -> {
    subscription.dispose();
});
```

### 4. 反序列化异常

**发生位置**: JsonUtils转换过程

**处理**: 被上层catch块捕获，返回通用错误

---

## 测试反馈会话特定标记

### 关键标识

| 标识 | 值 | 用途 | 设置位置 |
|------|-----|------|---------|
| sessionType | "test_session" | 标记为测试反馈会话 | testChatByFeedBack 1163行 |
| model | "gpt-4.1" | 测试使用固定模型 | testChatByFeedBack 1166行 |
| questionId参数 | Long | 链接到原始问题 | testChatByFeedBack 参数 |

### 与普通聊天的区别

| 特性 | 普通聊天 | 测试反馈 |
|------|---------|---------|
| 历史获取 | 最近N条 | 从questionId开始 |
| 模型选择 | 用户或默认 | 固定GPT-4.1 |
| SessionType | common_chat等 | test_session |
| 用途 | 日常聊天 | 验证修复效果 |

---

## 性能优化考虑

### 1. 心跳机制

**作用**: 保持连接活跃

**开销**: 每秒额外消息

**优化**: 可配置间隔

### 2. StreamResponseTracker累积

**优势**: 完整的处理步骤信息

**劣势**: 消息体较大

**优化**: 生产环境可压缩不需要的字段

### 3. 多次数据库操作

**操作次序**:
1. 创建Question记录
2. 创建初始Response记录
3. 更新Response记录

**优化**: 可考虑减少为2次操作

---

---

## 🔄 StreamResponseTracker 实时进度反馈机制

### 核心机制

**tracker是如何实现实时进度反馈的？**

```
tracker被逐步更新 
    ↓
放入response对象的data字段
    ↓
转换为JSON字符串
    ↓
通过SSE发送给前端
    ↓
前端实时接收并显示进度
```

### 第1步：tracker在流处理中被逐步更新

**位置**：`createCommonChatStreamSubscription()` 第1050-1060行

```java
.concatMap(response -> {
    // ✅ 关键：在处理每个Event前，备份当前tracker状态
    previousTracker.setStreamResponseTracker(tracker);
    
    // ✅ 调用处理函数，这个函数会修改tracker
    String processedEvent = processCommonChatEventWithTracker(
        response,    // 来自DiBrain的Event
        tracker,     // ← tracker会在这个函数内被修改
        requestVO,
        chatId
    );
    
    if (processedEvent == null) {
        return Flux.empty();
    }
    return Flux.just(processedEvent);  // ← processedEvent中包含了更新后的tracker
})
```

**关键点**: `processCommonChatEventWithTracker()`函数会根据Event类型修改tracker的不同字段，然后返回包含更新后tracker的JSON字符串。

### 第2步：processCommonChatEventWithTracker()如何更新tracker

**位置**：`CommonChatService.java` 第673-751行

#### 处理START事件

```java
if (Objects.equals(status, StreamStatusType.START)) {
    CommonChatResponseDTO responseDTO = ...;
    tracker.setTraceId(responseDTO.getMetadata().getRunId());  // ✅ 更新1：设置追踪ID
    response.setData(tracker);  // ✅ 将更新后的tracker放入response
    return JsonUtils.toJsonWithOutNull(response);  // ✅ 返回含tracker的JSON
}
```

**更新内容**:
- `traceId` ← DiBrain请求ID

#### 处理阶段事件（START/END/MESSAGE）

```java
switch (eventStatus) {
    case START:
        tracker.startNewStage(eventName);  // ✅ 更新2：开始新阶段
        // 在tracker.stageHistory中新增一个StreamStage
        // 状态为"start"
        break;
        
    case END:
        tracker.endStage(eventName, data);  // ✅ 更新3：结束阶段
        // 找到最后的stage，设置状态为"end"，添加返回数据
        break;
        
    default:
        tracker.updateStage(...);  // ✅ 更新4：更新阶段信息
        // 更新当前stage的状态为"message"或其他
        break;
}
response.setData(tracker);  // ✅ 每次都将更新后的tracker放入response
return JsonUtils.toJsonWithOutNull(response);
```

**更新内容**:
- `stageHistory` ← 追加新的处理步骤
- `currentStage` ← 当前处理阶段名称

#### 处理END事件（最终结果）

```java
if (Objects.equals(status, StreamStatusType.END)) {
    CommonChatResponseDTO responseDTO = ...;
    
    // 转换为最终响应对象
    CommonChatResponseVO commonChatResponseVO = chatProcessor.convertCommonChat(...);
    
    // ✅ 更新最终结果相关字段
    tracker.setEndTime(System.currentTimeMillis());      // 更新5：流结束时间
    tracker.setMidState(responseDTO.getOutput().getMidState());  // 更新6：中间状态
    tracker.setFinalResponse(commonChatResponseVO);      // 更新7：最终响应
    tracker.setCompleted(true);  // 更新8：标记已完成
    
    response.setData(tracker);
    return JsonUtils.toJsonWithOutNull(response);
}
```

**更新内容**:
- `endTime` ← 流处理完成时间
- `finalResponse` ← 最终的业务结果
- `isCompleted` ← true

### 第3步：tracker被序列化为JSON并通过SSE发送

**位置**：`createCommonChatStreamSubscription()` 第1103-1107行

```java
.subscribe(
    // onNext回调：处理每个元素
    e -> {
        try {
            // ✅ e就是JSON字符串，包含了完整的tracker对象！
            log.info("发送SSE消息: {}", e);
            sseEmitter.send(e);  // ✅ 实时发送给前端
        } catch (IOException ex) {
            sseEmitter.completeWithError(ex);
        }
    },
    ...
)
```

**传输的内容**: 完整的tracker对象序列化后的JSON字符串

### 传输的具体格式

#### 完整的事件JSON示例

```json
{
  "event": {
    "name": "understand",
    "status": "end"
  },
  "status": "message",
  "data": {
    "chatId": 1002,
    "traceId": "abc123-xyz",
    "stageHistory": [
      {
        "stageName": "understand",
        "status": "end",
        "data": {
          "responseAgent": "sql_agent",
          "askHuman": false,
          "llmRawResponse": "用户要查询用户表中的所有记录"
        },
        "startTime": 1699862400000,
        "endTime": 1699862405000
      },
      {
        "stageName": "sql_generation",
        "status": "start",
        "data": null,
        "startTime": 1699862405000,
        "endTime": 0
      }
    ],
    "currentStage": "sql_generation",
    "isCompleted": false,
    "isCanceled": false,
    "startTime": 1699862400000,
    "endTime": null,
    "sessionType": "test_session",
    "finalResponse": null
  }
}
```

#### 每个字段的含义

| 字段 | 类型 | 说明 | 何时更新 | 用途 |
|------|------|------|---------|------|
| `traceId` | String | 请求追踪ID | Event1(START)时 | 问题排查和日志追踪 |
| `stageHistory` | List | 所有处理步骤的历史 | 每个阶段START/END时 | 显示完整处理过程 |
| `currentStage` | String | 当前正在处理的阶段 | 每个Event时 | 显示当前进度 |
| `isCompleted` | boolean | 是否处理完成 | Event(END)时 | 判断是否流结束 |
| `isCanceled` | boolean | 是否被用户取消 | 流CANCEL时 | 区分正常完成vs中断 |
| `startTime` | Long | 流开始时间戳 | 初始化时 | 计算耗时 |
| `endTime` | Long | 流结束时间戳 | Event(END)时 | 计算总耗时 |
| `finalResponse` | Object | 最终的业务结果 | Event(END)时 | 显示最终结果 |

### tracker演进时间线

```
时间点    DiBrain事件        tracker更新内容              前端接收到的JSON
─────────────────────────────────────────────────────────────────────

T0      Event1:START      traceId = "abc123"           {
                                                        "status":"start",
                                                        "data":{
                                                          "traceId":"abc123",
                                                          "stageHistory":[],
                                                          "isCompleted":false
                                                        }
                                                      }

T1      Event2:           startNewStage("understand")  {
        understand_START   stageHistory: [              "status":"message",
                             {name:"understand",       "data":{
                              status:"start"}           "stageHistory":[...],
                           ]                            "isCompleted":false
                                                      }
                                                    }

T2      Event3:           endStage("understand")       {
        understand_END     stageHistory: [              "status":"message",
                             {name:"understand",       "data":{
                              status:"end",            "stageHistory":[{...complete}],
                              data:{...}}              "isCompleted":false
                           ]                          }
                                                    }

T3      Event4:           startNewStage("sql_gen")     {
        sql_generation     stageHistory: [              "status":"message",
        _START             {...understand...},         "data":{
                             {name:"sql_gen",          "stageHistory":[..., {processing}],
                              status:"start"}          "isCompleted":false
                           ]                          }
                                                    }

T4      Event5:           setFinalResponse()           {
        FINAL_END          setCompleted(true)          "status":"end",
                           setEndTime()                "data":{
                           stageHistory: [all],        "stageHistory":[all complete],
                           finalResponse: {...}        "finalResponse":{...},
                                                        "isCompleted":true,
                                                        "endTime":1234567890
                                                      }
                                                    }
```

### 前端如何接收和显示进度

#### 前端JavaScript接收

```javascript
// 建立SSE连接
const eventSource = new EventSource('/api/common/chat/stream');

eventSource.addEventListener('simpleFluxEvent', (event) => {
    // ✅ 接收从后端推送的tracker信息
    const message = JSON.parse(event.data);
    console.log('收到Tracker更新:', message);
    
    // message.data就是完整的StreamResponseTracker对象
    const tracker = message.data;
    
    // ✅ 1. 显示每个处理步骤
    tracker.stageHistory.forEach((stage, index) => {
        console.log(`第${index+1}步: ${stage.stageName} - ${stage.status}`);
        if (stage.status === 'end' && stage.endTime) {
            const duration = stage.endTime - stage.startTime;
            console.log(`  耗时: ${duration}ms`);
        }
    });
    
    // ✅ 2. 计算进度百分比
    const totalStages = 5;  // 假设总共5个阶段
    const completedStages = tracker.stageHistory.filter(s => s.status === 'end').length;
    const progress = (completedStages / totalStages) * 100;
    updateProgressBar(progress);
    
    // ✅ 3. 实时更新UI
    updateProcessingSteps(tracker.stageHistory);
    updateCurrentStage(tracker.currentStage);
    
    // ✅ 4. 检查是否完成
    if (tracker.isCompleted) {
        console.log('✅ 处理完成！');
        console.log('最终结果:', tracker.finalResponse);
        console.log(`总耗时: ${tracker.endTime - tracker.startTime}ms`);
        eventSource.close();
    }
});
```

#### 用户看到的进度显示

```
初始化（收到第1个JSON - Event1:START）：
┌──────────────────────────┐
│ 💻 处理中...             │
│ Trace ID: abc123         │
└──────────────────────────┘

第2个JSON（understand阶段开始）：
┌──────────────────────────┐
│ 💻 理解问题中...         │
│ 进度: 20%                │
└──────────────────────────┘

第3个JSON（understand阶段完成）：
┌──────────────────────────┐
│ ✓ 理解问题 (2秒)         │
│ 💻 生成SQL中...          │
│ 进度: 40%                │
└──────────────────────────┘

第4个JSON（sql_generation阶段）：
┌──────────────────────────┐
│ ✓ 理解问题 (2秒)         │
│ 💻 生成SQL (3秒)         │
│ 💻 验证中...             │
│ 进度: 60%                │
└──────────────────────────┘

最终JSON（Event5:END）：
┌──────────────────────────┐
│ ✓ 理解问题 (2秒)         │
│ ✓ 生成SQL (3秒)          │
│ ✓ 验证完成 (1秒)         │
│ ✓ 总耗时: 6秒            │
│ ✅ 完成！                │
│ 结果: SELECT...          │
└──────────────────────────┘
```

**每一次UI更新都来自于一个新的SSE消息，其中包含更新后的tracker！**

### 关键设计精妙之处

#### 为什么要把tracker放在response.data中？

```java
// ❌ 不好的设计（只传输状态字符串）
response.status = "message";
response.message = "正在处理SQL生成...";
// 问题：
// - 前端不知道处理到第几步
// - 无法显示完整的处理历史
// - 无法计算进度百分比

// ✅ 好的设计（传输完整的tracker）
response.setData(tracker);
// 优点：
// - 前端获得完整的处理进度信息
// - 可以显示所有已完成的步骤
// - 可以计算精确的进度百分比
// - 流被中断后可以恢复（tracker包含完整状态）
// - 支持实时查看详细信息（耗时、中间结果等）
```

#### tracker的"累积"设计

```
每收到一个Event：
1. tracker被更新（新增一个stage或修改existing stage）
2. 整个tracker被放入response
3. tracker被序列化为JSON
4. JSON被发送给前端

结果：前端总是收到最新的、完整的tracker
      它包含了从流开始以来的所有处理步骤！
      
这就是"累积"的含义：
  Event1 → tracker有1个stage
  Event2 → tracker有2个stage
  Event3 → tracker有2个stage（第2个stage更新了）
  ...
  EventN → tracker有N个stage，全部是END状态
```

### 总结：实时进度反馈的完整链路

```
┌─────────────────────────────────────────────────────────┐
│ 后端 (CommonChatService)                              │
├─────────────────────────────────────────────────────────┤
│                                                        │
│ 1️⃣ Flux流到达Event                                   │
│    ↓                                                  │
│ 2️⃣ processCommonChatEventWithTracker()修改tracker    │
│    ↓                                                  │
│ 3️⃣ response.setData(tracker)                         │
│    ↓                                                  │
│ 4️⃣ JsonUtils.toJsonWithOutNull(response)             │
│    ↓                                                  │
│ 5️⃣ sseEmitter.send(JSON) ─────────────→ SSE推送     │
│    ↓                                    │             │
└────────────────────────────────────────┼─────────────┘
                                         │
┌────────────────────────────────────────┼─────────────┐
│ 前端 (浏览器 EventSource)              ↓            │
├─────────────────────────────────────────────────────┤
│                                                     │
│ 6️⃣ eventSource.onmessage接收JSON                   │
│    ↓                                                │
│ 7️⃣ JSON.parse(event.data)反序列化                   │
│    ↓                                                │
│ 8️⃣ const tracker = message.data                     │
│    ↓                                                │
│ 9️⃣ 显示进度：                                       │
│    - 更新进度条                                      │
│    - 显示当前阶段                                    │
│    - 列出已完成的步骤                                │
│    - 显示每步耗时                                    │
│                                                     │
│ 🔟 tracker.isCompleted === true时，显示最终结果     │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 🌐 Web层分析：CommonChatOpenApiController

### 接口定义

**位置**: `di-assistant-web/src/main/java/com/shopee/di/assistant/controller/openapi/CommonChatOpenApiController.java` (50-70行)

```java
@GetMapping("/feedback_test")
private SseEmitter testByFeedBack(
    @RequestParam(value = "questionId") Long questionId,
    @RequestParam(value = "responseId") Long responseId,
    @RequestParam(value = "sessionId") Long testSessionId)
```

**三个关键参数**:

| 参数 | 类型 | 说明 | 来源 |
|------|------|------|------|
| `questionId` | Long | 原始提问消息ID | 查询参数 |
| `responseId` | Long | 原始回复消息ID | 查询参数 |
| `testSessionId` | Long | 测试会话ID | 查询参数 |

### 执行步骤详解

#### 第1步：会话校验 (54-57行)

```java
SessionDetailDTO sessionDetailDTO = sessionService.getSession(testSessionId);
if (!Objects.equals(sessionDetailDTO.getSessionType(), ChatSessionType.TEST_SESSION)) {
    throw new ServerException(ResponseCodeEnum.PARAM_ILLEGAL, 
        "Only Support Test Session, The session {} is not a test session", testSessionId);
}
```

**校验逻辑**:
- ✅ 获取会话详情
- ✅ 检查会话类型是否为TEST_SESSION
- ❌ 非测试会话则抛出异常

**设计意图**: 确保只有测试会话才能使用反馈测试功能

#### 第2步：获取原始问题 (58-60行)

```java
ChatDetailDTO questionDetail = chatService.getChatDetail(questionId);
CommonChatRequestVO commonChatRequestVO = 
    JsonUtils.toObject(questionDetail.getChatContent(), CommonChatRequestVO.class);
commonChatRequestVO.setSessionId(testSessionId);
```

**数据流转**:
```
数据库查询
  ↓
ChatDetailDTO (数据库实体)
  ↓
JSON反序列化
  ↓
CommonChatRequestVO (业务对象)
  ↓
更新sessionId为testSessionId
```

**关键操作**: 将原始问题转换为业务对象，并更新sessionId用于测试

#### 第3步：获取用户设置 (61-62行)

```java
ChatDetailDTO responseDetail = chatService.getChatDetail(responseId);
UserSetting userSetting = responseDetail.getMessageExtraInfo().getUserSetting();
```

**获取内容**:
- SQL方言配置
- 队列配置
- 其他用户偏好设置

**用途**: 在流处理中使用相同的用户设置

#### 第4步：创建SSE发送器 (64行)

```java
SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT);
```

**参数**:
- `SSE_EMITTER_TIMEOUT = 660_000L` = 11分钟

**用途**: 创建SSE通道，用于推送数据给前端

#### 第5步：异步执行流处理 (66-68行)

```java
executor.execute(() -> {
    commonChatService.testChatByFeedBack(questionId, userSetting, commonChatRequestVO, emitter);
});
```

**关键设计**:
- 🔴 **在新线程中执行**（不阻塞HTTP响应）
- 🔴 **立即返回SseEmitter**
- 🔴 **后台线程处理Flux流**

**时序**:
```
T0: 创建SseEmitter
T0: 启动后台线程
T0: 返回HTTP 200
    ↓
前端收到HTTP 200，建立EventSource连接
    ↓
T0+: 后台线程开始处理Flux
T0+ → T0+: 逐个推送数据给前端
```

#### 第6步：返回SSE发送器 (69行)

```java
return emitter;
```

**HTTP响应**:
```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

data: {"status":"start",...}
data: {"status":"message",...}
data: {"status":"end",...}
```

### 容器级别的设置

```java
public static final long SSE_EMITTER_TIMEOUT = 660_000L;  // 11分钟
private final ExecutorService executor = Executors.newFixedThreadPool(10);  // 10个线程
```

**线程池设置**:
- 最大并发数: 10个SSE连接
- 线程复用: 提高性能
- 降低资源消耗

---

## 🔌 Flux和SSE核心函数详解

### Flux链式操作函数

#### 1. `webClient.post()` - HTTP POST请求

```java
webClient.post()
    .uri(diBrainUrl + "/router/stream")
    .bodyValue(commonChatRequestDTO)
    .accept(MediaType.TEXT_EVENT_STREAM)
    .retrieve()
```

**作用**:
- 创建POST请求
- 目标地址: DiBrain服务 `/router/stream`
- 请求体: 序列化的`CommonChatRequestDTO`
- 接收类型: `text/event-stream`

**返回**: `ResponseSpec`

---

#### 2. `.bodyToFlux()` - 转换为Flux流

```java
.bodyToFlux(new ParameterizedTypeReference<CommonChatStreamEvent>() { })
```

**作用**:
- 将HTTP响应体转换为Flux流
- 流中的每个元素: `CommonChatStreamEvent`
- 支持无限流（不需要预知数据量）

**返回**: `Flux<CommonChatStreamEvent>`

**关键特性**:
- ✅ 背压支持（自动处理速率）
- ✅ 非阻塞处理
- ✅ 内存高效（不将整个响应加载到内存）

---

#### 3. `.concatMap()` - 顺序处理并转换（核心串行调度器）

```java
.concatMap(response -> {
    // 在处理当前事件前，先备份当前tracker（用于CANCEL场景恢复）
    previousTracker.setStreamResponseTracker(tracker);

    // 调用核心处理函数：更新tracker并生成要发给前端的JSON字符串
    String processedEvent = processCommonChatEventWithTracker(
        response,    // CommonChatStreamEvent：来自DiBrain的单个事件
        tracker,     // StreamResponseTracker：会在这里被更新（阶段历史、最终结果等）
        requestVO,
        chatId
    );

    // 如果当前事件不需要向前端推送（例如被过滤的重复事件），返回空流
    if (processedEvent == null) {
        return Flux.empty();   // 1个输入事件 → 0个输出事件
    }

    // 如果本次事件是END/ERROR类型，则发出最后一个事件后“自然结束”当前分支流
    if (Objects.nonNull(response) && Objects.nonNull(response.getStatus())
        && (Objects.equals(StreamStatusType.END.getType(), response.getStatus())
        || Objects.equals(StreamStatusType.ERROR.getType(), response.getStatus()))) {
        // 1个输入事件 → 1个输出事件（最后一个）+ 空流（用于显式结束）
        return Flux.just(processedEvent).concatWith(Flux.empty());
    }

    // 普通事件：1个输入事件 → 1个输出事件
    return Flux.just(processedEvent);
})
```

**在本项目场景中的作用**（结合 `CommonChatService.createCommonChatStreamSubscription()`）:

- **输入流类型**: `Flux<CommonChatStreamEvent>`（`bodyToFlux()` 解析得到的DiBrain事件流）
- **输出流类型**: `Flux<String>`（每个元素是处理后的JSON字符串，内部包含最新的 `StreamResponseTracker`）
- **核心职责**: 把“**事件流**”转成“**前端可用的JSON流**”，同时**串行更新tracker**。

具体来说，对每个 `response`（事件）它会：

1. **备份当前tracker** 到 `previousTracker`（用于后续CANCEL恢复）
2. 调用 `processCommonChatEventWithTracker()`：
   - 根据事件类型（START / MESSAGE / END / ERROR）更新 `tracker`：
     - 更新 `stageHistory`、`currentStage`、`finalResponse`、`isCompleted` 等
   - 把更新后的 `tracker` 塞到 `response.data` 里
   - 把整个 `response` 序列化成JSON字符串 `processedEvent`
3. 根据返回值决定是否发给前端：
   - `processedEvent == null` → 返回 `Flux.empty()`（本事件不推给前端）
   - END/ERROR事件 → 返回 `Flux.just(processedEvent).concatWith(Flux.empty())`（发一次后显式结束当前分支）
   - 普通事件 → 返回 `Flux.just(processedEvent)`

**为什么一定要用 `concatMap` 而不是 `flatMap`？**

- `tracker` 是一个**共享可变对象**：
  - 里面有 `stageHistory`、`currentStage`、`finalResponse` 等字段
  - 多个事件同时修改同一个对象会导致状态错乱
- 流事件是**严格有序的业务流程**：
  - 必须保证顺序：`UNDERSTAND:START` → `UNDERSTAND:END` → `SQL_GEN:START` → ...
  - 不允许 `SQL_GEN:END` 出现在 `UNDERSTAND:END` 之前
- `concatMap` 的特点：
  - **严格顺序**：上一个元素完全处理完成后，才会处理下一个
  - 支持“1→0/1”的转换：1个输入事件可以对应0或1个输出事件（本项目中刚好符合）
  - 保证同一时刻只有一个事件在修改 `tracker`

**与其他操作的对比总结**:

- `map`:
  - 1个输入 → 1个输出（1→1）
  - 无法返回 `Flux`（只能返回值）
  - 不适合“可能不发（0个）”的场景，也无法控制异步子流
- `flatMap`:
  - 1个输入 → 0~N个输出（1→0..N）
  - 默认并发执行，**顺序不保证**
  - 在有共享可变状态（`tracker`）时容易产生并发问题
- `concatMap`:
  - 1个输入 → 0或1个输出（1→0/1）
  - 严格顺序执行
  - 非常适合：
    - 有顺序要求
    - 需要串行更新共享状态（如 `tracker`）
    - 每个输入事件最多产生一个输出事件

---

**类比 `.mergeWith()` 的简化示例：用时间线理解 `concatMap` 行为**

假设有一个基础流 `Flux.just(1, 2, 3)`，每个数字都要经历一个“模拟耗时处理”（500ms）：

```java
Flux<Integer> source = Flux.just(1, 2, 3);

// 使用 concatMap：严格顺序处理
source
    .concatMap(n ->
        Flux.just(n)
            .delayElements(Duration.ofMillis(500)) // 模拟耗时操作
            .doOnNext(x -> log.info("concatMap 处理: {}", x))
    )
    .subscribe();
```

**时间线（约）**：

```
T0       T0+500ms       T0+1000ms
 |          |              |
 1 -------->2------------->3

日志输出顺序必然是：
concatMap 处理: 1
concatMap 处理: 2
concatMap 处理: 3
```

如果把上面代码改成 `flatMap`：

```java
source
    .flatMap(n ->
        Flux.just(n)
            .delayElements(Duration.ofMillis(500))
            .doOnNext(x -> log.info("flatMap 处理: {}", x))
    )
    .subscribe();
```

**时间线可能变成**：

```
T0+500ms: flatMap 处理: 2
T0+500ms: flatMap 处理: 1
T0+500ms: flatMap 处理: 3
```

顺序不再可控——这对依赖顺序和共享状态（`tracker`）的场景是致命的。因此在 `CommonChatService` 中，**`concatMap` 是唯一合理的选择**。

---

#### 4. `.mergeWith()` - 合并多个Flux

```java
.mergeWith(Flux.interval(Duration.ofSeconds(1))
    .map(tick -> {
        CommonChatStreamEvent heartbeat = new CommonChatStreamEvent();
        heartbeat.setEvent(CommonChatStreamEventInfo.builder()
            .name("ping")
            .build());
        return JsonUtils.toJsonWithOutNull(heartbeat);
    })
)
```

**作用**:
- 合并两个Flux流
- 业务数据流 + 心跳流
- 按时间顺序交错发送

**时间线**:
```
业务流:     Event1 -------- Event2 -------- Event3 --------
                  (500ms)          (500ms)         (500ms)

心跳流:     ping - ping - ping - ping - ping - ping - ping -
           (1s)   (1s)  (1s)  (1s)  (1s)  (1s)  (1s)

合并流:    Event1-ping-ping-Event2-ping-ping-Event3-ping-...
```

**用途**: 保持SSE连接活跃，防止超时

---

#### 5. `.map()` - 转换元素

```java
.map(event -> {
    long currentTime = System.currentTimeMillis();
    long timeoutMs = assistantGlobalConfig.getCommonChatTimeout() * 1000L;
    if (currentTime - tracker.getStartTime() > timeoutMs) {
        throw new ServerException(ResponseCodeEnum.STREAM_TIMEOUT_ERROR);
    }
    return event;
})
```

**作用**:
- 对每个元素进行转换（1:1映射）
- 检查条件并抛出异常
- 或者进行其他处理

**返回**: 转换后的元素

**关键**: 可以在这里进行校验、日志、监控等

---

#### 6. `.takeUntil()` - 条件终止

```java
.takeUntil(event -> {
    if (event instanceof String) {
        CommonChatStreamEvent streamEvent = JsonUtils.toObject(event, CommonChatStreamEvent.class);
        return Objects.nonNull(streamEvent)
            && Objects.nonNull(streamEvent.getStatus())
            && (Objects.equals(StreamStatusType.END.getType(), streamEvent.getStatus())
            || Objects.equals(StreamStatusType.ERROR.getType(), streamEvent.getStatus()));
    }
    return false;
})
```

**作用**:
- 当条件为true时停止流
- 包含满足条件的最后一个元素
- 自动停止后续的流处理

**终止条件**: 
```
status == "end" 或 status == "error"
```

**时间线**:
```
Event1 → Event2 → Event3(END) ✗ ✗ ✗ (后续事件被忽略)
                         ↑
                    流在这里停止
```

---

#### 7. `.doFinally()` - 流结束处理

```java
.doFinally(signalType -> {
    log.info("CommonChat SSE stream ended with signal: {}", signalType);
    if (signalType == SignalType.ON_COMPLETE) {
        log.info("CommonChat SSE stream completed normally.");
        tracker.setCompleted(true);
    } else if (signalType == SignalType.ON_ERROR) {
        log.info("CommonChat SSE stream terminated due to an error.");
    } else if (signalType == SignalType.CANCEL) {
        log.info("CommonChat SSE stream was cancelled.");
        tracker.setStreamResponseTracker(previousTracker);
        tracker.setCanceled(true);
    }
    saveTrackerResultToDatabase(tracker, requestVO);
})
```

**作用**:
- 流完全结束时执行（必定执行）
- 处理所有的终止情况

**三种结束信号**:

| 信号 | 含义 | 处理 |
|------|------|------|
| `ON_COMPLETE` | 正常完成 | 标记isCompleted=true |
| `ON_ERROR` | 异常结束 | 记录错误 |
| `CANCEL` | 被取消 | 恢复previousTracker |

**关键**: 无论如何结束，最后都调用`saveTrackerResultToDatabase()`

---

#### 8. `.subscribe()` - 订阅处理

```java
.subscribe(
    // onNext: 处理每个元素
    e -> {
        try {
            sseEmitter.send(e);
        } catch (IOException ex) {
            sseEmitter.completeWithError(ex);
        }
    },
    // onError: 处理异常
    err -> {
        String error;
        boolean isTimeout = (err instanceof java.util.concurrent.TimeoutException)
            || (err instanceof ServerException && ...);
        if (isTimeout) {
            error = buildCommonChatFailedResponse(tracker, ..., MessageConstants.COMMON_TIMEOUT_PREFIX_TEXT);
        } else {
            error = buildCommonChatFailedResponse(tracker, ..., MessageConstants.COMMON_CHAT_ERROR_MESSAGE);
        }
        try {
            sseEmitter.send(error);
        } catch (IOException e) {
            log.error("Failed to send error response", e);
        }
        sseEmitter.completeWithError(err);
    },
    // onComplete: 流完成
    () -> sseEmitter.complete()
)
```

**三个回调函数**:

1. **onNext(e)**: 处理每个事件
   ```
   流中的元素 → JSON字符串 → 通过SseEmitter发送给前端
   ```

2. **onError(err)**: 处理异常
   ```
   异常发生 → 判断异常类型 → 构建错误响应 → 发送给前端 → 关闭连接
   ```

3. **onComplete()**: 流完成
   ```
   流正常结束 → 关闭SSE连接
   ```

**返回**: `Disposable` - 用于控制订阅生命周期

---

### SSE (Server-Sent Events) 函数

#### 1. `new SseEmitter(timeout)` - 创建SSE发送器

```java
SseEmitter emitter = new SseEmitter(660_000L);  // 11分钟
```

**作用**:
- 创建SSE连接的后端端点
- timeout参数: 多久没有数据就超时断开

**返回**: SseEmitter实例

---

#### 2. `sseEmitter.send()` - 发送事件

```java
sseEmitter.send(SseEmitter.event()
    .id(System.currentTimeMillis() + "")
    .name("simpleFluxEvent")
    .data(json)
    .reconnectTime(1000)
    .build());
```

**参数**:

| 参数 | 说明 |
|------|------|
| `id` | 事件ID（用于前端去重） |
| `name` | 事件类型名称 |
| `data` | 事件数据（JSON字符串） |
| `reconnectTime` | 连接断开时的重连等待时间（ms） |

**返回**: void

**异常**: IOException（连接已断开）

---

#### 3. `sseEmitter.complete()` - 正常关闭

```java
sseEmitter.complete();
```

**作用**:
- 主动关闭SSE连接
- 发送最后的完成信号
- 前端EventSource会触发close事件

---

#### 4. `sseEmitter.completeWithError()` - 异常关闭

```java
sseEmitter.completeWithError(error);
```

**作用**:
- 异常情况下关闭连接
- 发送错误信息给前端
- 前端EventSource会触发error事件

---

#### 5. `sseEmitter.onTimeout()` - 超时回调

```java
sseEmitter.onTimeout(() -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();
    }
});
```

**触发条件**: SSE连接超时（660秒无数据）

**处理**: 停止Flux订阅

---

#### 6. `sseEmitter.onCompletion()` - 完成回调

```java
sseEmitter.onCompletion(() -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();
    }
});
```

**触发条件**: 前端关闭EventSource连接

**处理**: 停止Flux订阅

---

#### 7. `sseEmitter.onError()` - 错误回调

```java
sseEmitter.onError((throwable) -> {
    if (!subscription.isDisposed()) {
        subscription.dispose();
    }
});
```

**触发条件**: 连接出错

**处理**: 停止Flux订阅

---

### Flux辅助函数

#### `Flux.range()` - 生成数字序列

```java
Flux.range(1, 10)  // 生成1到10
```

---

#### `Flux.interval()` - 定时发送

```java
Flux.interval(Duration.ofSeconds(1))  // 每秒发送一个数字
```

---

#### `Flux.empty()` - 空流

```java
return Flux.empty();  // 跳过此事件
```

---

#### `Flux.just()` - 单元素流

```java
return Flux.just(processedEvent);  // 返回单个元素
```

---

#### `Flux.concat()` - 串联流

```java
Flux.concat(flux1, flux2, flux3)  // 依次处理
```

---

### 函数调用链完整图

```
webClient.post()
    ↓
.bodyToFlux()               ← 获取流
    ↓
.concatMap()               ← 处理每个Event
    ↓ 
.mergeWith(Flux.interval()) ← 添加心跳
    ↓
.map()                     ← 超时检测
    ↓
.takeUntil()               ← 流终止条件
    ↓
.doFinally()               ← 流完成处理
    ↓
.subscribe(               ← 订阅并发送SSE
    onNext → sseEmitter.send(),
    onError → error处理,
    onComplete → sseEmitter.complete()
)
```

---

## 总结

测试反馈会话的流式生成流程是一个完整的端到端处理链：

1. **请求阶段**: 接收前端反馈测试请求
2. **准备阶段**: 获取历史、创建数据库记录、构建API请求
3. **流连接阶段**: 建立WebSocket连接到DiBrain
4. **事件处理阶段**: 逐个处理流事件，累积数据到追踪器
5. **前端推送阶段**: 每个事件JSON通过SSE发送给前端
6. **结束阶段**: 保存完整结果到数据库

整个流程利用Reactor框架的异步特性，实现了高效的流式处理和实时显示。

### 核心要点

- **Flux**: 响应式流的发行者，支持背压和非阻塞处理
- **SSE**: Server-Sent Events，用于服务器推送实时数据给客户端
- **异步执行**: 在独立线程中处理，不阻塞HTTP响应
- **tracker**: 累积收集流过程中的所有数据和状态
- **previousTracker**: 在流CANCEL时恢复到稳定状态

