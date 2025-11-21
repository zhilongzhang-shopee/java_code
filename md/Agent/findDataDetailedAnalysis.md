# FindData 详细流程与步骤分析

## 📌 快速概览

### 两个核心服务

| 服务 | 位置 | 功能 | API 端点 |
|------|------|------|---------|
| **FindDataService** | `service/table/` | 查找 Hive 表 | `/hive/finddata` |
| **FindAndUnderstandDataService** | `service/table/` | 查找并理解数据 | `/hive/findandunderstand` |

### 关键特点

- 🎯 **基于自然语言**: 用户用中文/英文提问，系统理解并查找相关数据表
- 🧠 **AI 驱动**: 利用 DiBrain AI 服务进行语义理解
- 💬 **会话管理**: 支持多轮对话历史
- ⏱️ **超时控制**: 防止长时间阻塞
- 🔐 **权限检查**: 验证用户访问权限

---

## 🟠 Part 1: FindDataService 详解

### 1.1 核心流程

```
API 请求 (POST /hive/finddata)
   ↓
RetrieveHiveRequestVO
   ├─ sessionId: 会话 ID
   ├─ question: 用户问题 (中文)
   ├─ translateText: 翻译后的文本
   ├─ tableUidList: 指定的表 (可选)
   ├─ martList: 业务域列表
   └─ schemaList: schema 列表

   ↓
┌─────────────────────────────────────┐
│ retrieveHiveAndFormat()             │
│ (Line 67-95)                        │
└─────────────────────────────────────┘

   ├─ 1️⃣ 验证会话和权限
   ├─ 2️⃣ 处理"再问一遍"逻辑
   ├─ 3️⃣ 获取聊天历史
   ├─ 4️⃣ 创建用户提问消息
   ├─ 5️⃣ 构建 DiBrain 请求
   ├─ 6️⃣ 调用 AI 查找表
   ├─ 7️⃣ 转换并保存结果
   └─ 8️⃣ 返回格式化响应

   ↓
RetrieveHiveResponseVO
   ├─ tableList: 查找到的表列表 [HiveTableVO]
   ├─ prefixText: 前缀提示文本
   ├─ chatId: 保存的聊天 ID
   └─ 元数据信息
```

### 1.2 详细步骤

#### 步骤 1️⃣: 验证会话和权限

```java
// Line 68-69
SessionDetailDTO session = sessionService.getSession(retrieveHiveReq.getSessionId());
sessionService.checkAuth(retrieveHiveReq.getCommonInfo().getUser(), session);
```

**作用**: 
- 检查会话是否存在
- 验证用户对会话的访问权限
- 抛出异常如果权限不足

#### 步骤 2️⃣: 处理"再问一遍"逻辑

```java
// Line 70-72
if (retrieveHiveReq.isAskAgain()) {
    chatService.deleteLastTwoChatMessage(retrieveHiveReq.getSessionId());
}
```

**作用**: 
- `isAskAgain=true` 时，删除最后的一对消息（用户提问 + AI 回复）
- 允许用户修改提问后重新提问

#### 步骤 3️⃣: 获取聊天历史

```java
// Line 73
List<String> history = chatService.getChatMessageHistory(
    retrieveHiveReq.getSessionId(), 
    ChatMessageType.QUESTION  // 只获取问题类型
);
```

**作用**: 
- 获取该会话中所有的历史问题
- 将其转换为 DiBrain 理解的格式
- 用于上下文理解

#### 步骤 4️⃣: 创建用户提问消息

```java
// Line 74-75
ChatCreateRequestDTO chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
    retrieveHiveReq);
chatService.createChatMessage(chatCreateRequestDTO);
```

**作用**: 
- 保存用户的提问到数据库
- 记录聊天历史
- 为后续回复做准备

#### 步骤 5️⃣-6️⃣: 构建请求并调用 AI

```java
// Line 77-87
String question = GetQuestionUtils.getQuestion(
    retrieveHiveReq.getTranslateText(), 
    retrieveHiveReq.getQuestion());

CommonResponse<RetrieveHiveResponseVO> resp = retrieveHiveTables(
    retrieveHiveReq.getCommonInfo(),
    question,
    retrieveHiveReq.getTableUidList(),
    toDiBrainChatHistory(history),
    session.getModel(),
    retrieveHiveReq.getIdcRegion(),
    retrieveHiveReq.getMartList(),
    retrieveHiveReq.getSchemaList());
```

**详见 1.3 的 `retrieveHiveTables()` 分析**

#### 步骤 7️⃣: 保存 AI 回复

```java
// Line 89-92
chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
    resp.getResponseVO(),
    retrieveHiveReq.getCommonInfo(), 
    retrieveHiveReq.getSessionId(), 
    resp.getTraceId());
Long chatId = chatService.createChatMessage(chatCreateRequestDTO);
resp.getResponseVO().setChatId(chatId);
```

**作用**: 
- 保存 AI 的查询结果（表列表）
- 保存 Trace ID 用于追踪
- 返回 Chat ID 给前端

### 1.3 关键方法: retrieveHiveTables()

**位置**: Line 97-173

#### 请求构建

```java
// 1. 构建过滤条件
CommonRetrieveFilterDTO retrieveFilterDTO = CommonRetrieveFilterDTO
    .builder()
    .martList(martList)           // 业务域过滤
    .schemaList(schemaList)       // schema 过滤
    .build();

// 2. 构建配置信息
CommonConfigDTO configDTO = CommonConfigDTO
    .builder()
    .configurable(ConfigurableDTO.builder()
        .llm(model)               // LLM 模型
        .build())
    .metadata(CommonReqMetadataDTO.builder()
        .reg(idcRegion)           // 地域
        .retrieveFilterDTO(retrieveFilterDTO)
        .build())
    .build();

// 3. 构建聊天上下文
ChatContextDTO chatContextDTO = ChatContextDTO
    .builder()
    .region(commonInfo.getRegion())
    .user(commonInfo.getUser())
    .userEmail(commonInfo.getUserEmail())
    .businessDomain(commonInfo.getBusinessDomain())
    .build();

// 4. 构建输入参数
CommonInputDTO inputDTO = CommonInputDTO.builder()
    .chatContext(chatContextDTO)
    .chatHistory(chatHistory)      // 会话历史
    .question(question)            // 用户问题
    .tableContext(tableContextDTO)  // 指定表上下文
    .build();
```

#### 调用 AI 服务

```java
// Line 144
FindDataDTO retrieveTableResp = diBrainClient.findDataByText(reqDTO);
```

**DiBrainClient 接口**:
```java
@PostMapping(value = "/hive/search/invoke")
FindDataDTO findDataByText(@RequestBody CommonRequestDTO commonRequestDTO);
```

**返回结果**:
- `FindDataDTO` 包含查询结果
- `metadata.runId` - 追踪 ID
- `output` - 表列表 (List<TableDTO>)

#### 结果转换

```java
// Line 146-149
List<HiveTableVO> hiveTableLists = Lists.newArrayList();
for (TableDTO table: retrieveTableResp.getOutput()) {
    hiveTableLists.add(convertToHiveTableInfo(table));
}
```

**关键转换**: TableDTO → HiveTableVO

#### 前缀消息选择

```java
// Line 151-157
String message = MessageConstants.FIND_DATA_PREFIX_TEXT;  // 默认
if (CollectionUtils.isNotEmpty(tableUidList)) {
    message = MessageConstants.FIND_DATA_HAVE_TABLE_PREFIX_TEXT;  // 有指定表
}
if (CollectionUtils.isEmpty(hiveTableLists)) {
    message = MessageConstants.FIND_DATA_NOT_FOUND_TEXT;  // 未找到
}
```

#### 构建返回对象

```java
// Line 159-167
RetrieveHiveResponseVO retrieveHiveResponseVO = RetrieveHiveResponseVO.builder()
    .tableList(hiveTableLists)           // 查询结果
    .prefixText(message)                 // 前缀提示
    .tableUidList(tableUidList)          // 原始参数
    .idcRegion(idcRegion)
    .martList(martList)
    .schemaList(schemaList)
    .build();
```

### 1.4 数据转换详解

**文件**: `DTOConverter.java`

#### convertToHiveTableInfo() 转换

```java
public static HiveTableVO convertToHiveTableInfo(TableDTO tableDTO) {
    TableMetadataDTO tableMetadataDTO = tableDTO.getMetadata();
    
    HiveTableVO.HiveTableVOBuilder builder = HiveTableVO.builder()
        .idcRegion(tableMetadataDTO.getIdcRegion())    // 地域
        .tableName(tableMetadataDTO.getTableName())    // 表名
        .schema(tableMetadataDTO.getSchema())          // schema
        .description(tableDTO.getPageContent())         // 描述
        .aiDescription(tableMetadataDTO.getAiDescription())  // AI 生成的描述
        .datamapDescription(tableMetadataDTO.getDatamapDescription());  // DataMap 描述

    // 转换列信息
    if (CollectionUtils.isNotEmpty(tableDTO.getMetadata().getColumns())) {
        List<TableColumnVO> columnInfos = Lists.newArrayList();
        for (ColumnDTO columnDTO : tableDTO.getMetadata().getColumns()) {
            columnInfos.add(convertToHiveColumnInfo(columnDTO));
        }
        builder.columns(columnInfos);
    }

    return builder.build();
}
```

**转换内容**:
- TableDTO (DiBrain 返回) → HiveTableVO (前端需要)
- 包含表元数据（名称、schema、地域）
- 包含列信息（列名、数据类型、描述等）

---

## 🟢 Part 2: FindAndUnderstandDataService 详解

### 2.1 核心流程

```
API 请求 (POST /hive/findandunderstand)
   ↓
FindAndUnderstandRequestVO
   ├─ sessionId: 会话 ID
   ├─ question: 用户问题
   ├─ queryTable: 查询表名
   ├─ tableUidList: 指定表 UID
   ├─ martList: 业务域
   └─ schemaList: schema

   ↓
┌──────────────────────────────────────┐
│ findAndUnderstandData()              │
│ (Line 70-113)                        │
└──────────────────────────────────────┘

   ├─ 1️⃣ 验证会话权限
   ├─ 2️⃣ 创建用户提问
   ├─ 3️⃣ 获取聊天历史
   ├─ 4️⃣ 获取知识库列表
   ├─ 5️⃣ 构建超时处理
   ├─ 6️⃣ 调用 AI 查找并理解
   ├─ 7️⃣ 获取表描述
   └─ 8️⃣ 返回完整结果

   ↓
FindAndUnderstandResponseVO
   ├─ resultContext: 理解结果
   ├─ relatedHiveTables: 相关表
   ├─ relatedDocs: 相关文档
   ├─ chatHistory: 聊天历史
   └─ failReason: 失败原因 (如有)
```

### 2.2 详细步骤

#### 步骤 1️⃣-4️⃣: 前置准备

```java
// Line 71-84
SessionDetailDTO session = sessionService.getSession(
    findAndUnderstandRequestVO.getSessionId());
sessionService.checkAuth(
    findAndUnderstandRequestVO.getCommonInfo().getUser(), session);

if (findAndUnderstandRequestVO.isAskAgain()) {
    chatService.deleteLastTwoChatMessage(
        findAndUnderstandRequestVO.getSessionId());
}

ChatCreateRequestDTO chatCreateRequestDTO = convertor
    .convertMessageVOToChatCreateDto(findAndUnderstandRequestVO);
chatService.createChatMessage(chatCreateRequestDTO);

// 获取响应历史
List<String> responseList = chatService.getChatMessageHistory(
    findAndUnderstandRequestVO.getSessionId(), 
    ChatMessageType.RESPONSE);
Map<String, Object> history = getChatHistory(responseList);

// 获取知识库列表
String question = GetQuestionUtils.getQuestion(
    findAndUnderstandRequestVO.getTranslateText(), 
    findAndUnderstandRequestVO.getQuestion());
List<String> knowledgeBaseList = getKnowledgeBaseList(
    findAndUnderstandRequestVO.getQueryTable(),
    findAndUnderstandRequestVO.getMartList(),
    findAndUnderstandRequestVO.getSchemaList(),
    findAndUnderstandRequestVO.getTableUidList());
```

#### 步骤 5️⃣: 超时处理包装

```java
// Line 86-105
Supplier<CommonResponse<FindAndUnderstandResponseVO>> responseSupplier = () -> 
    getFindAndUnderstandDataInfo(...);

CommonResponse<FindAndUnderstandResponseVO> resp = 
    GlobalTimeOutHandler.executeTaskWithTimeout(
        responseSupplier, 
        assistantGlobalConfig.getFindAndUnderstandDataTimeout(),
        timeoutReturn(...));  // 超时回调
```

**作用**: 
- 使用 Supplier 包装 AI 调用
- 设置超时时间 (从配置读取)
- 如果超时，返回默认的超时响应

**超时回调**:
```java
private CommonResponse<FindAndUnderstandResponseVO> timeoutReturn(...) {
    return CommonResponse.<FindAndUnderstandResponseVO>builder()
        .responseVO(FindAndUnderstandResponseVO.builder()
            .resultContext(MessageConstants.COMMON_TIMEOUT_PREFIX_TEXT)
            .failReason(CommonConstants.REQUEST_TIMEOUT_REASON)
            .build())
        .traceId(CommonConstants.BLANK_STRING)
        .build();
}
```

#### 步骤 6️⃣-8️⃣: 调用 AI 并保存结果

```java
// Line 107-112
chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
    resp.getResponseVO(),
    findAndUnderstandRequestVO.getCommonInfo(), 
    findAndUnderstandRequestVO.getSessionId(), 
    resp.getTraceId());
Long chatId = chatService.createChatMessage(chatCreateRequestDTO);
resp.getResponseVO().setChatId(chatId);

return resp.getResponseVO();
```

### 2.3 关键方法: getFindAndUnderstandDataInfo()

**位置**: Line 115-215

#### 请求构建

```java
// Line 118-149
FindAndUnderStandDataRequestDTO.FindAndUnderStandDataRequestDTOBuilder req = 
    FindAndUnderStandDataRequestDTO.builder();

CommonConfigDTO configDTO = CommonConfigDTO.builder()
    .configurable(ConfigurableDTO.builder()
        .model(model)  // 注意: 这里是 model，不是 llm
        .build())
    .build();
req.config(configDTO);

// 构建输入
FindAndUnderStandDataInputDTO.FindAndUnderStandDataInputDTOBuilder inputBuilder = 
    FindAndUnderStandDataInputDTO.builder();

ChatContextDTO chatContextDTO = ChatContextDTO.builder()
    .region(commonInfo.getRegion())
    .user(commonInfo.getUser())
    .userEmail(commonInfo.getUserEmail())
    .businessDomain(commonInfo.getBusinessDomain())
    .build();

inputBuilder
    .chatContext(chatContextDTO)
    .userQuery(question)
    .knowledgeBaseList(knowledgeBaseList)  // 知识库
    .userHobby(UserHobbyDTO.builder()
        .userRegion(commonInfo.getRegion())
        .build())
    .chatHistory(chatHistory);
```

#### 调用 AI 服务

```java
// Line 156
FindAndUnderStandDataDTO resp = diBrainClient
    .findAndUnderstandDataByText(reqDTO);
```

**DiBrainClient 接口**:
```java
@PostMapping(value = "/ask_data/invoke")
FindAndUnderStandDataDTO findAndUnderstandDataByText(
    @RequestBody FindAndUnderStandDataRequestDTO findAndUnderStandDataRequestDTO);
```

#### 获取表描述

```java
// Line 158-164
List<RelateHiveTableVO> hiveTableLists = Lists.newArrayList();
if (Objects.nonNull(resp.getOutput().getRelatedHiveTables())) {
    hiveTableLists = convertToRelateHiveTableVOList(
        resp.getOutput().getRelatedHiveTables());
}

// 获取每个表的详细描述
for (RelateHiveTableVO tableVO : hiveTableLists) {
    tableVO.setSummarize(knowledgeBaseService.getTableDescription(
        tableVO.getIdcRegion(), 
        tableVO.getSchemaName(), 
        tableVO.getTableName()));
}
```

**关键特性**: 
- 从 KnowledgeBase 查询表的详细描述
- 增强 AI 返回的表信息

#### 获取相关文档

```java
// Line 166-168
List<RelateDocumentVO> docsLists = Lists.newArrayList();
if (Objects.nonNull(resp.getOutput().getRelatedDocs())) {
    docsLists = convertToRelateDocumentVOList(
        resp.getOutput().getRelatedDocs());
}
```

#### 构建返回结果

```java
// Line 174-187
FindAndUnderstandResponseVO findAndUnderstandResponseVO = 
    FindAndUnderstandResponseVO.builder()
        .resultContext(resultContext)         // 理解结果
        .relatedDocs(docsLists)               // 相关文档
        .relatedHiveTables(hiveTableLists)    // 相关表
        .idcRegion(idcRegion)
        .martList(martList)
        .schemaList(schemaList)
        .tableUidList(tableUidList)
        .question(question)
        .chatHistory(resp.getOutput().getChatHistory())
        .queryTable(queryTable)
        .failReason(resp.getOutput().getFailAnswerReason())
        .build();
```

#### 错误处理

```java
// Line 194-209
try {
    FindAndUnderStandDataDTO resp = diBrainClient
        .findAndUnderstandDataByText(reqDTO);
    // ... 处理成功结果
} catch (FeignException e) {
    // Feign 调用失败时返回默认响应
    findAndUnderstandResponseVO = FindAndUnderstandResponseVO.builder()
        .resultContext(MessageConstants.FIND_AND_UNDERSTAND_DATA_FAIL_PREFIX_TEXT)
        .failReason(CommonConstants.REQUEST_FAIL_REASON)
        .build();
}
```

---

## 🔄 完整数据流程图

```
┌─────────────────────────────────────────────────────────────────┐
│ 用户请求                                                        │
│ POST /hive/finddata 或 /hive/findandunderstand                 │
└────────────────┬────────────────────────────────────────────────┘
                 ↓
        ┌───────────────────┐
        │ FindHiveTable     │
        │ Controller        │
        └────────┬──────────┘
                 ↓
    ┌────────────┴────────────┐
    ↓                         ↓
┌─────────────────┐   ┌──────────────────────────┐
│ FindDataService │   │ FindAndUnderstandData    │
│ .retrieve...()  │   │ Service.findAndUnderstand│
└────────┬────────┘   └──────────┬───────────────┘
         ↓                       ↓
    ┌────────────────┬──────────────────────┐
    │ 1. 验证权限    │ 1. 验证权限         │
    │ 2. 获取历史    │ 2. 获取历史         │
    │ 3. 创建消息    │ 3. 创建消息         │
    │ 4. 构建请求    │ 4. 获取知识库       │
    └────────┬───────┴────────────┬────────┘
             ↓                    ↓
        ┌─────────────────────────────────────┐
        │ DiBrainClient (Feign)               │
        │ .findDataByText() or               │
        │ .findAndUnderstandDataByText()     │
        └────────────┬────────────────────────┘
                     ↓
        ┌─────────────────────────────────────┐
        │ DiBrain AI 服务                     │
        │ /hive/search/invoke                │
        │ /ask_data/invoke                   │
        └────────────┬────────────────────────┘
                     ↓
        ┌─────────────────────────────────────┐
        │ DTOConverter 数据转换               │
        │ TableDTO → HiveTableVO             │
        └────────────┬────────────────────────┘
                     ↓
    ┌────────────────┴──────────────────────┐
    ↓                                       ↓
┌─────────────────────┐        ┌──────────────────────┐
│ 知识库查询          │        │ ChatService 保存     │
│ (如需)              │        │ 用户提问 + AI 回复   │
└────────────┬────────┘        └──────────┬───────────┘
             ↓                            ↓
        ┌──────────────────────────────────────┐
        │ 构建响应对象                        │
        │ RetrieveHiveResponseVO              │
        │ FindAndUnderstandResponseVO         │
        └──────────────┬─────────────────────┘
                       ↓
                  返回前端
```

---

## 💾 关键数据模型

### FindDataService 的数据模型

```
请求:
RetrieveHiveRequestVO
  ├─ sessionId: Long              // 会话 ID
  ├─ commonInfo: CommonInfo       // 用户信息
  ├─ question: String             // 用户问题
  ├─ translateText: String        // 翻译后的文本
  ├─ tableUidList: List<String>   // 指定表 UID
  ├─ martList: List<String>       // 业务域
  ├─ schemaList: List<String>     // schema
  ├─ idcRegion: String            // 地域
  └─ isAskAgain: Boolean          // 是否重新提问

响应:
RetrieveHiveResponseVO
  ├─ tableList: List<HiveTableVO>  // 查询到的表
  ├─ prefixText: String            // 前缀消息
  ├─ chatId: Long                  // 保存的聊天 ID
  ├─ idcRegion: String
  ├─ martList: List<String>
  ├─ schemaList: List<String>
  └─ tableUidList: List<String>

中间层:
HiveTableVO
  ├─ tableName: String
  ├─ schema: String
  ├─ idcRegion: String
  ├─ description: String
  ├─ aiDescription: String
  ├─ datamapDescription: String
  └─ columns: List<TableColumnVO>

TableColumnVO
  ├─ columnName: String
  ├─ dataType: String
  ├─ description: String
  ├─ aiDescription: String
  ├─ partition: Boolean
  ├─ primaryKey: Boolean
  ├─ foreignKey: Boolean
  └─ ...更多属性
```

### FindAndUnderstandDataService 的数据模型

```
请求:
FindAndUnderstandRequestVO
  ├─ sessionId: Long
  ├─ commonInfo: CommonInfo
  ├─ question: String
  ├─ translateText: String
  ├─ queryTable: String          // 查询表
  ├─ tableUidList: List<String>
  ├─ martList: List<String>
  ├─ schemaList: List<String>
  ├─ idcRegion: String
  └─ isAskAgain: Boolean

响应:
FindAndUnderstandResponseVO
  ├─ resultContext: String            // 理解结果
  ├─ relatedHiveTables: List<RelateHiveTableVO>  // 相关表
  ├─ relatedDocs: List<RelateDocumentVO>  // 相关文档
  ├─ chatHistory: Map<String, Object>    // 聊天历史
  ├─ question: String
  ├─ queryTable: String
  ├─ failReason: String
  ├─ chatId: Long
  └─ ...更多字段

RelateHiveTableVO
  ├─ idcRegion: String
  ├─ schemaName: String
  ├─ tableName: String
  ├─ summarize: String            // 从知识库获取的描述
  └─ ...

RelateDocumentVO
  ├─ docName: String
  └─ url: String
```

---

## 🔑 关键代码片段

### 1. 权限检查

```java
sessionService.checkAuth(
    retrieveHiveReq.getCommonInfo().getUser(), 
    session);
```

**作用**: 确保用户有权限访问该会话

### 2. 聊天消息创建

```java
ChatCreateRequestDTO chatCreateRequestDTO = convertor
    .convertMessageVOToChatCreateDto(retrieveHiveReq);
chatService.createChatMessage(chatCreateRequestDTO);
```

**作用**: 保存用户提问到数据库

### 3. 问题处理

```java
String question = GetQuestionUtils.getQuestion(
    retrieveHiveReq.getTranslateText(), 
    retrieveHiveReq.getQuestion());
```

**逻辑**: 
- 优先使用翻译后的文本
- 如果没有，使用原始问题

### 4. 超时处理 (FindAndUnderstandDataService)

```java
Supplier<CommonResponse<FindAndUnderstandResponseVO>> responseSupplier = 
    () -> getFindAndUnderstandDataInfo(...);

CommonResponse<FindAndUnderstandResponseVO> resp = 
    GlobalTimeOutHandler.executeTaskWithTimeout(
        responseSupplier, 
        assistantGlobalConfig.getFindAndUnderstandDataTimeout(),
        timeoutReturn(...));
```

**优势**: 
- 防止 AI 调用超时卡住
- 返回合理的超时响应
- 用户体验更好

### 5. 表描述增强

```java
for (RelateHiveTableVO tableVO : hiveTableLists) {
    tableVO.setSummarize(
        knowledgeBaseService.getTableDescription(
            tableVO.getIdcRegion(), 
            tableVO.getSchemaName(), 
            tableVO.getTableName()));
}
```

**特性**: 
- 从知识库补充表的详细描述
- 提供更完整的表信息

### 6. 错误处理

```java
try {
    FindAndUnderStandDataDTO resp = diBrainClient
        .findAndUnderstandDataByText(reqDTO);
    // ...
} catch (FeignException e) {
    // 返回失败响应
    findAndUnderstandResponseVO = 
        FindAndUnderstandResponseVO.builder()
            .resultContext(
                MessageConstants
                    .FIND_AND_UNDERSTAND_DATA_FAIL_PREFIX_TEXT)
            .failReason(
                CommonConstants.REQUEST_FAIL_REASON)
            .build();
}
```

---

## 📊 调用关系图

```
FindHiveTableController
├─ POST /hive/finddata
│  └─ FindDataService.retrieveHiveAndFormat()
│     ├─ SessionService.getSession()
│     ├─ SessionService.checkAuth()
│     ├─ ChatService.deleteLastTwoChatMessage()
│     ├─ ChatService.getChatMessageHistory()
│     ├─ ChatService.createChatMessage()  (用户提问)
│     ├─ FindDataService.retrieveHiveTables()
│     │  ├─ DiBrainClient.findDataByText()  (AI 调用)
│     │  └─ DTOConverter.convertToHiveTableInfo()
│     └─ ChatService.createChatMessage()  (AI 回复)
│
└─ POST /hive/findandunderstand
   └─ FindAndUnderstandDataService.findAndUnderstandData()
      ├─ SessionService.getSession()
      ├─ SessionService.checkAuth()
      ├─ ChatService.deleteLastTwoChatMessage()
      ├─ ChatService.getChatMessageHistory()
      ├─ ChatService.createChatMessage()  (用户提问)
      ├─ GlobalTimeOutHandler.executeTaskWithTimeout()
      │  └─ FindAndUnderstandDataService
      │     .getFindAndUnderstandDataInfo()
      │     ├─ DiBrainClient.findAndUnderstandDataByText()
      │     ├─ KnowledgeBaseService.getTableDescription()
      │     └─ DTOConverter 转换
      └─ ChatService.createChatMessage()  (AI 回复)
```

---

## ✅ 总结

### FindDataService
- **功能**: 根据自然语言查找 Hive 表
- **流程**: 验证 → 历史 → 创建request → AI查询 → 转换 → 回复
- **特点**: 简单直接，快速查询

### FindAndUnderstandDataService
- **功能**: 根据自然语言查找表并理解其含义
- **流程**: 验证 → 历史 → 知识库 → AI查询 → 表描述增强 → 回复
- **特点**: 功能完整，有超时保护，返回更丰富的信息

### 关键优化点
1. **超时处理**: 防止 AI 调用超时
2. **知识库增强**: 补充表的详细描述
3. **错误处理**: FeignException 捕获
4. **聊天历史**: 支持多轮对话
5. **权限检查**: 确保数据安全

