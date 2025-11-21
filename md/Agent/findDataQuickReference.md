# FindData 快速参考指南

## 🎯 两个核心方法对比

### FindDataService (查找表)

| 特性 | 说明 |
|------|------|
| **API** | `POST /hive/finddata` |
| **功能** | 根据自然语言查找 Hive 表 |
| **主方法** | `retrieveHiveAndFormat()` |
| **AI 调用** | `diBrainClient.findDataByText()` |
| **返回类型** | `RetrieveHiveResponseVO` |
| **超时保护** | ❌ 无 |
| **知识库** | ❌ 无 |
| **耗时** | 快 (1-2 秒) |

### FindAndUnderstandDataService (查找并理解表)

| 特性 | 说明 |
|------|------|
| **API** | `POST /hive/findandunderstand` |
| **功能** | 查找表并理解其含义 |
| **主方法** | `findAndUnderstandData()` |
| **AI 调用** | `diBrainClient.findAndUnderstandDataByText()` |
| **返回类型** | `FindAndUnderstandResponseVO` |
| **超时保护** | ✅ 有 (GlobalTimeOutHandler) |
| **知识库** | ✅ 有 (KnowledgeBaseService) |
| **耗时** | 中等 (3-5 秒) |

---

## 🔄 流程对比

### FindDataService 流程 (8 步)

```
1. 验证会话和权限
   ↓
2. 处理"再问一遍"逻辑 (删除最后消息)
   ↓
3. 获取聊天历史 (QUESTION 类型)
   ↓
4. 创建用户提问消息
   ↓
5. 构建 DiBrain 请求
   ↓
6. 调用 AI 查找表
   ↓
7. 转换结果 (TableDTO → HiveTableVO)
   ↓
8. 保存 AI 回复，返回响应
```

### FindAndUnderstandDataService 流程 (8 步)

```
1. 验证会话和权限
   ↓
2. 处理"再问一遍"逻辑
   ↓
3. 获取聊天历史 (RESPONSE 类型)
   ↓
4. 创建用户提问消息
   ↓
5. 获取知识库列表 + 超时保护包装
   ↓
6. 调用 AI 查找表并理解
   ↓
7. 从知识库获取表详细描述
   ↓
8. 保存 AI 回复，返回响应
```

---

## 📝 核心代码模板

### FindDataService.retrieveHiveAndFormat()

```java
@Transactional(rollbackFor = Exception.class)
public RetrieveHiveResponseVO retrieveHiveAndFormat(RetrieveHiveRequestVO req) {
    // 1. 验证
    SessionDetailDTO session = sessionService.getSession(req.getSessionId());
    sessionService.checkAuth(req.getCommonInfo().getUser(), session);
    
    // 2. 删除消息 (如果重新提问)
    if (req.isAskAgain()) {
        chatService.deleteLastTwoChatMessage(req.getSessionId());
    }
    
    // 3. 获取历史
    List<String> history = chatService.getChatMessageHistory(
        req.getSessionId(), ChatMessageType.QUESTION);
    
    // 4. 创建提问消息
    ChatCreateRequestDTO chatCreateDTO = convertor
        .convertMessageVOToChatCreateDto(req);
    chatService.createChatMessage(chatCreateDTO);
    
    // 5-6. AI 调用
    CommonResponse<RetrieveHiveResponseVO> resp = retrieveHiveTables(
        req.getCommonInfo(),
        GetQuestionUtils.getQuestion(req.getTranslateText(), req.getQuestion()),
        req.getTableUidList(),
        toDiBrainChatHistory(history),
        session.getModel(),
        req.getIdcRegion(),
        req.getMartList(),
        req.getSchemaList());
    
    // 7-8. 保存回复
    chatCreateDTO = convertor.convertMessageVOToChatCreateDto(
        resp.getResponseVO(),
        req.getCommonInfo(), 
        req.getSessionId(), 
        resp.getTraceId());
    Long chatId = chatService.createChatMessage(chatCreateDTO);
    resp.getResponseVO().setChatId(chatId);
    
    return resp.getResponseVO();
}
```

### FindAndUnderstandDataService.findAndUnderstandData()

```java
@Transactional(rollbackFor = Exception.class)
public FindAndUnderstandResponseVO findAndUnderstandData(
    FindAndUnderstandRequestVO req) {
    // 1. 验证
    SessionDetailDTO session = sessionService.getSession(req.getSessionId());
    sessionService.checkAuth(req.getCommonInfo().getUser(), session);
    
    // 2. 删除消息
    if (req.isAskAgain()) {
        chatService.deleteLastTwoChatMessage(req.getSessionId());
    }
    
    // 3. 获取历史 + 创建提问
    List<String> responseList = chatService.getChatMessageHistory(
        req.getSessionId(), ChatMessageType.RESPONSE);
    Map<String, Object> history = getChatHistory(responseList);
    
    ChatCreateRequestDTO chatCreateDTO = convertor
        .convertMessageVOToChatCreateDto(req);
    chatService.createChatMessage(chatCreateDTO);
    
    // 4. 准备数据
    String question = GetQuestionUtils.getQuestion(
        req.getTranslateText(), req.getQuestion());
    List<String> knowledgeBaseList = getKnowledgeBaseList(
        req.getQueryTable(), req.getMartList(), 
        req.getSchemaList(), req.getTableUidList());
    
    // 5-6. AI 调用 + 超时保护
    Supplier<CommonResponse<FindAndUnderstandResponseVO>> supplier = 
        () -> getFindAndUnderstandDataInfo(
            req.getCommonInfo(), question, req.getQueryTable(),
            knowledgeBaseList, history, session.getModel(),
            req.getIdcRegion(), req.getMartList(),
            req.getSchemaList(), req.getTableUidList());
    
    CommonResponse<FindAndUnderstandResponseVO> resp = 
        GlobalTimeOutHandler.executeTaskWithTimeout(
            supplier,
            assistantGlobalConfig.getFindAndUnderstandDataTimeout(),
            timeoutReturn(question, req.getQueryTable(), history,
                req.getIdcRegion(), req.getMartList(),
                req.getSchemaList(), req.getTableUidList()));
    
    // 7-8. 保存回复
    chatCreateDTO = convertor.convertMessageVOToChatCreateDto(
        resp.getResponseVO(),
        req.getCommonInfo(), 
        req.getSessionId(), 
        resp.getTraceId());
    Long chatId = chatService.createChatMessage(chatCreateDTO);
    resp.getResponseVO().setChatId(chatId);
    
    return resp.getResponseVO();
}
```

---

## 🔍 关键代码解析

### 1. 权限检查

```java
sessionService.checkAuth(commonInfo.getUser(), session);
```

**检查内容**:
- 用户是否属于会话
- 会话是否有效

### 2. 删除消息逻辑

```java
if (req.isAskAgain()) {
    chatService.deleteLastTwoChatMessage(req.getSessionId());
}
```

**场景**: 用户修改问题后想重新提问
**效果**: 删除最后的问题和回复对

### 3. 问题选择

```java
String question = GetQuestionUtils.getQuestion(
    req.getTranslateText(),  // 优先
    req.getQuestion());       // 后备
```

**优先级**:
1. 翻译后的文本 (中文 → 英文)
2. 原始问题

### 4. 超时处理

```java
Supplier<...> supplier = () -> getFindAndUnderstandDataInfo(...);

CommonResponse<...> resp = GlobalTimeOutHandler.executeTaskWithTimeout(
    supplier,
    timeout,
    timeoutReturn(...));  // 超时回调
```

**优势**:
- 防止 AI 调用卡住
- 返回友好的超时消息
- 不中断用户体验

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

**效果**: 
- AI 返回表 → 知识库补充描述
- 提供更完整的表信息

### 6. 错误处理

```java
try {
    FindAndUnderStandDataDTO resp = diBrainClient
        .findAndUnderstandDataByText(reqDTO);
    // ...
} catch (FeignException e) {
    // 返回失败响应
    findAndUnderstandResponseVO = ...
        .failReason(CommonConstants.REQUEST_FAIL_REASON)
        .build();
}
```

---

## 📊 数据模型速查

### 请求模型

**RetrieveHiveRequestVO**
```
sessionId           Long        会话 ID
commonInfo          CommonInfo  用户信息 (user, email, region)
question            String      原始问题
translateText       String      翻译后的问题
tableUidList        List        指定的表 UID
martList            List        业务域
schemaList          List        schema
idcRegion           String      地域 (SG, US-EAST)
isAskAgain          Boolean     是否重新提问
```

**FindAndUnderstandRequestVO** (上面所有字段 + 以下)
```
queryTable          String      特定的查询表
```

### 响应模型

**RetrieveHiveResponseVO**
```
tableList           List<HiveTableVO>    查找到的表
prefixText          String               前缀消息
chatId              Long                 保存的聊天 ID
idcRegion           String
martList            List
schemaList          List
tableUidList        List
```

**FindAndUnderstandResponseVO**
```
resultContext       String                   理解结果文本
relatedHiveTables   List<RelateHiveTableVO> 相关表
relatedDocs         List<RelateDocumentVO>  相关文档
chatHistory         Map<String, Object>     聊天历史
question            String                  用户问题
queryTable          String                  查询表
failReason          String                  失败原因
chatId              Long                    聊天 ID
```

### HiveTableVO 结构

```
tableName           String              表名
schema              String              schema
idcRegion           String              地域
description         String              描述
aiDescription       String              AI 生成的描述
datamapDescription  String              DataMap 描述
columns             List<TableColumnVO> 列信息
```

---

## 🚀 使用流程

### 场景 1: 用户问"查找订单表"

```
请求:
  POST /hive/finddata
  {
    "sessionId": 123,
    "question": "查找订单表",
    "commonInfo": {"user": "alice@example.com"}
  }

流程:
  1. 验证 alice 的权限
  2. 获取会话 123 的历史
  3. 保存问题到数据库
  4. 调用 DiBrain: 查找相关表
  5. 获取返回的表列表 (订单表、订单详情表等)
  6. 转换数据格式
  7. 保存回复到数据库
  8. 返回表列表给前端

响应:
  {
    "tableList": [
      {
        "tableName": "order",
        "schema": "warehouse",
        "idcRegion": "SG",
        "description": "订单表",
        "columns": [...]
      },
      ...
    ],
    "prefixText": "找到以下表:",
    "chatId": 456
  }
```

### 场景 2: 用户问"理解客户表的含义"

```
请求:
  POST /hive/findandunderstand
  {
    "sessionId": 123,
    "question": "理解客户表的含义",
    "queryTable": "customer",
    "commonInfo": {"user": "alice@example.com"}
  }

流程:
  1. 验证权限
  2. 获取会话历史 (RESPONSE 类型)
  3. 保存问题
  4. 获取 customer 相关的知识库列表
  5. 调用 DiBrain with 超时保护
  6. 获取相关表和文档
  7. 从知识库查询表描述
  8. 返回完整的理解结果

响应:
  {
    "resultContext": "客户表包含客户基本信息...",
    "relatedHiveTables": [
      {
        "tableName": "customer",
        "schemaName": "warehouse",
        "summarize": "存储所有客户的基本信息...",
        ...
      }
    ],
    "relatedDocs": [
      {
        "docName": "数据字典",
        "url": "http://..."
      }
    ],
    "chatId": 789
  }
```

---

## ⚠️ 常见问题

### Q1: 为什么有两个不同的方法?

**A**: 
- `FindDataService`: 快速查找表，返回最相关的几个表
- `FindAndUnderstandDataService`: 深度分析，提供表的详细理解和相关文档

### Q2: 超时是多少?

**A**: 从 `assistantGlobalConfig.getFindAndUnderstandDataTimeout()` 读取
- 通常是 15-30 秒
- 防止 AI 调用无限期等待

### Q3: 知识库是什么?

**A**: 
- 包含每个表的详细描述
- 来自 KnowledgeBaseService
- 补充 AI 返回的信息

### Q4: 支持多语言吗?

**A**: 
- 支持中文提问 + 英文翻译
- `translateText` 字段存储翻译结果
- AI 使用翻译后的文本理解

### Q5: 删除消息的目的?

**A**: 
- 用户修改问题后想重新提问
- 需要删除旧的问题和回复
- 保持对话链的连贯性

---

## 📚 文件位置

```
控制器:
  └─ di-assistant-web/src/main/java/.../controller/table/
     └─ FindHiveTableController.java

服务:
  └─ di-assistant-service/src/main/java/.../service/table/
     ├─ FindDataService.java
     └─ FindAndUnderstandDataService.java

客户端:
  └─ di-assistant-service/src/main/java/.../rest/client/dibrain/
     └─ DiBrainClient.java

转换工具:
  └─ di-assistant-service/src/main/java/.../service/utils/
     └─ DTOConverter.java

测试:
  └─ di-assistant-service/src/test/java/.../service/utils/
     └─ DTOConverterTest.java
```

---

## 🎯 总结

**FindDataService**: 快速查询
- ✅ 快速 (1-2 秒)
- ✅ 简单
- ❌ 信息较少
- ❌ 无超时保护

**FindAndUnderstandDataService**: 深度分析
- ✅ 信息完整
- ✅ 超时保护
- ✅ 知识库支持
- ❌ 较慢 (3-5 秒)

**选择建议**:
- 用户只想快速查找表 → FindDataService
- 用户想理解表的含义 → FindAndUnderstandDataService

