# 四个聊天服务详细分析

## 🎯 核心结论

您的理解**部分正确**，这四个服务的关系并不是简单的"用户输入→LLM→输出"。它们是**分层的数据访问和业务流程管理服务**。

### 快速回答

| 服务 | 职责 | 用户消息? | LLM 回复? |
|------|------|----------|---------|
| **ChatService** | ✅ **消息持久化层** | ✅ 保存 | ✅ 保存 |
| **ChatBotService** | 🤖 DiRobot 集成层 | ❌ 不直接保存 | ❌ 不直接保存 |
| **CommonChatService** | 🔀 通用路由层 | ❌ 不直接保存 | ❌ 不直接保存 |
| **ChatBIService** | 📊 BI 数据分析层 | ❌ 不直接保存 | ❌ 不直接保存 |

---

## 📊 架构图

```
用户请求 (HTTP)
    ↓
┌─────────────────────────────────────────────────┐
│ 控制层 (Web Controllers)                         │
│ ├─ DIChatBotController    (/chatbot/msg)       │
│ ├─ CommonChatController   (/common/chat)       │
│ └─ BIController           (/bi/chat/st/flux)   │
└────────────┬──────────────────────────────────┬─┘
             ↓                                  ↓
    ┌──────────────────┐            ┌──────────────────┐
    │ ChatBotService   │            │ CommonChatService│
    │ (业务逻辑)        │            │ (业务逻辑)        │
    └────────┬─────────┘            └────────┬─────────┘
             ↓                               ↓
    ┌──────────────────┐            ┌──────────────────┐
    │   DiRobot API    │            │  DiBrain API     │
    │  (外部服务)      │            │ (外部服务)       │
    └────────┬─────────┘            └────────┬─────────┘
             │                               │
             └───────────┬───────────────────┘
                         ↓
            ┌────────────────────────────┐
            │   ChatService              │
            │ (消息持久化层)              │
            │ ✅ 保存所有消息            │
            │ ✅ 管理问题+回复对         │
            │ ✅ 支持修改和删除          │
            └────────────┬───────────────┘
                         ↓
            ┌────────────────────────────┐
            │  ChatMessageTabServiceImpl  │
            │  (数据访问层)               │
            │  与 chat_message_tab 表交互│
            └────────────────────────────┘
```

---

## 🔍 详细分析

### 1️⃣ ChatService - 消息持久化层

**位置**: `di-assistant-service/src/main/java/com/shopee/di/assistant/service/chat/ChatService.java`

**核心职责**: 所有消息的增删改查

```java
// 🔑 关键方法

// 1. 创建消息 (问题 + 回复都用这个)
public Long createChatMessage(ChatCreateRequestDTO chatCreateRequestDTO)
    ↓
    将消息保存到 ChatMessageTab 表

// 2. 获取消息历史 (支持多种查询方式)
public List<String> getChatMessageHistory(Long sessionId, ChatMessageType type)
public List<ChatMessageTab> getCommonChatMessageHistory(Long sessionId)
public BaseTypeListDTO<ChatDetailDTO> getChatMessageList(...)

// 3. 修改消息 (编辑问题或回复)
public Boolean modifyChatContent(Long chatId, String chatContent)

// 4. 删除消息
public Boolean deleteChatMessage(Long chatId)
public void deleteLastTwoChatMessage(Long sessionId)  // "再问一遍" 用

// 5. 与反馈关联
// 在 getChatMessageList 时，会关联 feedback 信息
```

**处理的消息类型** (`ChatMessageType`):
- `QUESTION`: 用户提问
- `RESPONSE`: AI/Bot 回复
- `GREETING`: 欢迎语

**NOT一个 Converter/API 调用层** ❌

---

### 2️⃣ ChatBotService - DiRobot 集成层

**位置**: `di-assistant-service/src/main/java/com/shopee/di/assistant/service/chatbot/ChatBotService.java`

**核心职责**: 与 DiRobot 机器人服务的集成

```java
public ChatBotResponseDTO createChatBotMessage(ChatBotRequestDTO chatBotRequestDTO) {
    
    // 第1步: 删除上一轮对话 (如果"再问一遍")
    if (chatBotRequestDTO.isAskAgain()) {
        chatService.deleteLastTwoChatMessage(sessionId);
    }
    
    // 第2步: 读取历史消息 (用 ChatService)
    List<ChatDetailDTO> history = chatService.getChatMessageList(...);
    
    // 第3步: 保存用户提问 (用 ChatService)
    Long chatId = chatService.createChatMessage(
        ChatMessageType.QUESTION
    );
    
    // 第4步: 调用 DiRobot 外部 API (✅ 只有这里调外部 API)
    AskQuestionResponseDTO botResponse = diRobotClientWrapper.askQuestion(request);
    
    // 第5步: 保存 Bot 回复 (用 ChatService)
    Long responseChatId = chatService.createChatMessage(
        botResponse,
        ChatMessageType.RESPONSE
    );
    
    // 第6步: 返回
    return convertToDTO(botResponse, responseChatId);
}
```

**流程**:
```
用户问题
    ↓
ChatBotService 读取历史
    ↓
调用 DiRobot API → 得到答案
    ↓
ChatService 同时保存两部分:
  ├─ 用户问题 (QUESTION)
  └─ Bot 答案 (RESPONSE)
    ↓
返回给前端
```

**关键点**:
- ✅ 使用 `ChatService` 来保存消息
- ✅ 专注于 DiRobot 集成逻辑
- ❌ 不是消息保存层
- ❌ 不直接操作数据库

---

### 3️⃣ CommonChatService - 通用路由层

**位置**: `di-assistant-service/src/main/java/com/shopee/di/assistant/service/common/CommonChatService.java`

**核心职责**: 通用聊天，支持多种 Agent (工具)

```java
// 非流式模式
@Transactional
public CommonChatResponseVO commonChatInvoke(CommonChatRequestVO requestVO) {
    
    // 第1步: 获取历史
    List<ChatMessageTab> history = chatService.getCommonChatMessageHistory(sessionId);
    
    // 第2步: 保存用户提问
    Long chatId = chatService.createChatMessage(
        ChatMessageType.QUESTION
    );
    
    // 第3步: 调用 DiBrain AI (支持多种 Agent)
    CommonChatResponseDTO response = diBrainClient.commonChat(request);
    
    // 第4步: 保存 AI 回复
    chatService.createChatMessage(
        response,
        ChatMessageType.RESPONSE
    );
    
    return response;
}

// 流式模式 (SSE)
@Transactional
public void commonChatStreamSse(CommonChatRequestVO requestVO, SseEmitter sseEmitter) {
    
    // 同样流程，但支持流式推送
    webClient.post()
        .uri(diBrainUrl + "/router/stream")  // 调用 DiBrain Router
        .bodyValue(request)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(...)
        .subscribe(...)  // 流式处理每个事件
        ↓
        消息分阶段推送给前端
}
```

**特点**:
- 🔀 **路由器**: 根据 `tool` 参数选择不同的处理策略
- 🎯 **多 Agent 支持**: 可以调用不同的 AI 服务
- 📊 **支持数据集**: 可以指定要查询的表
- 🌊 **流式支持**: 支持 SSE 实时推送

**支持的 Tool 类型**:
```java
if (ChatSessionType.DASHBOARD_AGENT.equals(tool)) {
    // 调用 Dashboard 服务
    commonChatDashboardStreamSse(requestVO, sseEmitter);
} else {
    // 默认调用 DiBrain Router
    webClient.post().uri(diBrainUrl + "/router/stream")
}
```

---

### 4️⃣ ChatBIService - BI 数据分析层

**位置**: `di-assistant-service/src/main/java/com/shopee/di/assistant/service/bi/ChatBIService.java`

**核心职责**: BI 图表生成和数据分析

```java
// 流式 Flux API
public Flux<String> textToBIChartV2(ChatBIRequestVO biRequestVO) {
    
    // 第1步: 保存用户提问
    chatService.createChatMessage(
        ChatMessageType.QUESTION
    );
    
    // 第2步: 构建 DiBrain BI 请求
    CommonRequestDTO request = createChatBIRequest(biRequestVO);
    
    // 第3步: 调用 DiBrain BI 流式 API
    Flux<ServerSentEvent<String>> response = webClient.post()
        .uri(diBrainUrl + "/chat_bi/stream")  // ✅ BI 专用 API
        .bodyValue(request)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(ServerSentEvent.class)
    
    // 第4步: 处理事件流
    response
        .map(this::preProcessEvent)          // 提取 metadata
        .filter(this::eventFilter)            // 过滤事件
        .map(e -> processChatBIEvent(...))    // 处理事件
        .subscribe()
    
    // 第5步: 保存成功的 BI 回复
    if (isSuccessEvent(event)) {
        chatService.createChatMessage(
            response,
            ChatMessageType.RESPONSE
        );
    }
}

// SSE 模式
public void textToBIChartV2(ChatBIRequestVO biRequestVO, SseEmitter sseEmitter) {
    // 类似上面，但最后推送到 SseEmitter
}
```

**特点**:
- 📊 **数据可视化**: 生成图表而不是文本
- 🌊 **流式处理**: 分阶段推送图表数据
- 📥 **数据下载**: 支持 CSV 导出 (`downloadChatBIData`)
- 🔐 **权限检查**: 检查用户是否有数据访问权限

**事件类型**:
```
METADATA  → 初始化 (获取 runId)
DATA      → 处理中间数据
SUCCESS   → 成功完成
FAILED    → 失败
ERROR     → 异常
```

---

## 🔄 完整流程对比

### 流程 1: ChatBotService (DiRobot)

```
用户请求                        前端
    ↓
DIChatBotController
    ↓
ChatBotService.createChatBotMessage()
    │
    ├─ chatService.getChatMessageList()    ← 读取历史
    │
    ├─ chatService.createChatMessage()     ← 保存问题 ✅
    │   (QUESTION 类型)
    │
    ├─ diRobotClientWrapper.askQuestion()  ← 调用 DiRobot API
    │
    ├─ chatService.createChatMessage()     ← 保存回复 ✅
    │   (RESPONSE 类型)
    │
    └─ return response
        ↓
    前端显示
```

**消息保存**: ✅ ChatService
**消息来源**: DiRobot API

---

### 流程 2: CommonChatService (通用)

```
用户请求                        前端
    ↓
CommonChatController
    ↓
CommonChatService.commonChatInvoke() 或 commonChatStreamSse()
    │
    ├─ chatService.getCommonChatMessageHistory()  ← 读取历史
    │
    ├─ chatService.createChatMessage()           ← 保存问题 ✅
    │   (QUESTION 类型)
    │
    ├─ diBrainClient.commonChat() 或             ← 调用 DiBrain
    │  webClient.post(/router/stream)              (Router 或 Dashboard)
    │
    ├─ 处理响应事件
    │
    ├─ chatService.createChatMessage()           ← 保存回复 ✅
    │   (RESPONSE 类型)
    │
    └─ return response (或流式推送)
        ↓
    前端显示
```

**消息保存**: ✅ ChatService
**消息来源**: DiBrain API (或 Dashboard)

---

### 流程 3: ChatBIService (BI)

```
用户请求                        前端
    ↓
BIController
    ↓
ChatBIService.textToBIChartV2()
    │
    ├─ chatService.createChatMessage()       ← 保存问题 ✅
    │   (QUESTION 类型)
    │
    ├─ webClient.post(/chat_bi/stream)       ← 调用 DiBrain BI API
    │
    ├─ 处理流式事件
    │   ├─ METADATA: 提取 runId
    │   ├─ DATA: 处理中间结果
    │   └─ SUCCESS/FAILED: 最终结果
    │
    ├─ 如果成功:
    │   chatService.createChatMessage()      ← 保存回复 ✅
    │       (RESPONSE 类型)
    │
    └─ 流式推送给前端 (SSE 或 Flux)
        ↓
    前端显示图表
```

**消息保存**: ✅ ChatService
**消息来源**: DiBrain BI API (流式)

---

## ✅ 正确答案

### ChatService 是什么?

❌ **不是**: "user 发送给 llm 的消息"

✅ **是**: **消息持久化层** (Data Access Service)
  - 保存所有类型的消息 (问题+回复)
  - 管理消息的生命周期 (CRUD)
  - 不关心消息来自哪里或发到哪里
  - 被所有其他服务使用

---

### ChatBotService 是什么?

❌ **不是**: "llm 发送给 user 的回复"

✅ **是**: **DiRobot 机器人集成层**
  - 封装 DiRobot 调用逻辑
  - 管理问题+回复的完整流程
  - 使用 ChatService 来保存消息
  - 专注于 DiRobot 对接

---

### CommonChatService 是什么?

❌ **不是**: "特定的聊天功能"

✅ **是**: **通用聊天路由层**
  - 支持多种 AI Agent (DiRobot, DiBrain, Dashboard 等)
  - 支持非流式和流式两种模式
  - 处理数据集、权限等上下文信息
  - 使用 ChatService 来保存消息

---

### ChatBIService 是什么?

✅ **是**: **BI 数据分析专用层**
  - 生成数据可视化图表
  - 支持流式处理
  - 支持数据下载 (CSV)
  - 使用 ChatService 来保存消息

---

## 🎯 总结

```
分层架构:

业务逻辑层
├─ ChatBotService    (特定 Bot 逻辑)
├─ CommonChatService (通用逻辑)
└─ ChatBIService     (BI 逻辑)
        ↓ 都使用
数据持久化层
└─ ChatService       (消息 CRUD)
        ↓
数据访问层
└─ ChatMessageTabServiceImpl
        ↓
数据库
└─ chat_message_tab 表
```

**关键理解**:
1. **ChatService = 消息总管**: 所有消息都通过它保存
2. **其他 Service = 业务编排**: 负责流程，不负责存储
3. **问题和回复**: 都是消息，都通过 ChatService 保存
4. **消息类型区分**: 通过 `ChatMessageType` enum (QUESTION/RESPONSE)
5. **谁保存消息**: 业务 Service 调用 ChatService 来保存

