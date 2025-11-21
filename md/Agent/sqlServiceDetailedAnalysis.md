# SQL 服务详细流程与步骤分析

## 📌 快速概览

### 四个核心SQL服务

| 服务 | 功能 | 主方法 | API 端点 | 位置 |
|------|------|--------|---------|------|
| **SQLParserService** | 解析 SQL 表名 | `parseSQLSelectedTables()` | 内部使用 | `service/sql/` |
| **Text2SQLService** | 文本转 SQL | `generateSQLAndParse()` | `POST /sql/text2sql` | `service/sql/` |
| **ExplainSQLService** | 解释 SQL | `explainSQLInvoke()` | `POST /sql/explainsql` | `service/sql/` |
| **FixSQLService** | 修复 SQL | `fixSQLInvoke()` | `POST /sql/fixsql` | `service/sql/` |

### 关键特性

- 🔄 **AI 驱动**: 所有复杂功能由 DiBrain AI 服务实现
- ⏱️ **超时保护**: 所有 AI 调用都有超时机制
- 💾 **历史记录**: 支持多轮对话
- 🔍 **SQL 解析**: 双层解析（远程 DataMap API + 本地 JSQLParser）
- 🎯 **权限检查**: 所有操作验证用户权限

---

## 🟠 Part 1: SQLParserService 详解

### 1.1 核心功能

**作用**: 从 SQL 语句中提取表名

```
SQL 字符串
  ↓
SQLParserService
  ├─ 识别 SQL 方言 (Hive, MySQL 等)
  ├─ 调用 DataMap API 解析
  ├─ Fallback 到本地 JSQLParser
  └─ 返回表名集合 (Set<String>)
```

### 1.2 方法 1: parseSQLSelectedTables()

**位置**: Line 36-72

```java
public Set<String> parseSQLSelectedTables(String sql, SQLDialect dialect, String idcRegion) {
    // 1. 验证 SQL 不为空
    if (StringUtils.isBlank(sql)) {
        ExceptionUtils.throwServerException(..., "sql cannot be empty");
    }
    
    try {
        // 2. 检查方言
        if (dialect == SQLDialect.FLINK) {
            // Flink SQL 直接用本地解析器
            return parseSelectedTablesLocally(sql);
        } else {
            // 3. 其他方言使用 DataMap API
            SQLEngine sqlEngine = SQLEngine.fromSQLDialect(dialect);
            ParseTableRequestDTO req = ParseTableRequestDTO.builder()
                .sqlEngine(sqlEngine)              // SQL 引擎 (HIVE, MYSQL 等)
                .defaultSchema("default")
                .sql(sql)                          // SQL 语句
                .idcRegion(idcRegion)             // 地域
                .build();
            
            // 4. 调用 DataMap 服务
            ParseTableRespDTO respDTO = dataMapClient
                .parseSelectedTablesFromSQL(req);
            
            // 5. 检查响应
            if (!respDTO.isSuccess() || respDTO.getData() == null || 
                CollectionUtils.isEmpty(respDTO.getData().getReferencedTables())) {
                // Fallback: 本地解析
                return parseSelectedTablesLocally(sql);
            }
            
            // 6. 转换表名格式 (schema.table)
            return respDTO.getData().getReferencedTables().stream()
                .map(e -> String.format("%s.%s", e.getSchema(), e.getTableName()))
                .collect(Collectors.toSet());
        }
    } catch (JSQLParserException | UnsupportedOperationException e) {
        log.error("parse selected tables by java-sql-parser failed", e);
    } catch (FeignException e) {
        log.error("parse selected tables by data map failed", e);
        ExceptionUtils.throwServerException(..., "DataMap API Error", e);
    } catch (Exception e) {
        log.error("parse selected tables failed. with unknown exception", e);
        ExceptionUtils.throwServerException(..., "unknown", e);
    }
    return new HashSet<>();
}
```

**流程解析**:

```
1️⃣ 验证 SQL
   ↓
2️⃣ 识别方言
   ├─ FLINK → 本地解析
   └─ 其他 → DataMap API
   ↓
3️⃣ 调用 DataMap 或本地
   ↓
4️⃣ 处理响应
   ├─ 成功 → 转换格式
   └─ 失败 → Fallback 本地解析
   ↓
5️⃣ 错误处理
   └─ 返回空集合或抛异常
```

### 1.3 方法 2: parseSelectedTablesLocally()

**位置**: Line 74-83

```java
private Set<String> parseSelectedTablesLocally(String sql) 
    throws JSQLParserException {
    
    // 1️⃣ 使用 CommonSQLParser 解析
    Set<String> ret = CommonSQLParser.parseSelectedTables(sql);
    if (CollectionUtils.isNotEmpty(ret)) {
        log.info("parse selected tables success by common parser: {}", ret);
        return ret;
    }
    
    // 2️⃣ Fallback 到 JSQLParser
    Set<String> javaParserResult = TablesNamesFinder.findTables(sql);
    log.info("parse selected tables by java-sql-parser: {}", javaParserResult);
    return javaParserResult;
}
```

**双层 Fallback**:
1. CommonSQLParser (自定义解析器)
2. JSQLParser (标准 SQL 解析库)

### 1.4 方法 3: parseSQLLocally()

**位置**: Line 85-97

```java
public String parseSQLLocally(String text) {
    // 1. 替换转义换行符
    text = text.replace("\\n", "\n");
    
    // 2. 使用正则表达式提取 SQL
    Matcher matcher = SQL_PATTERN.matcher(text);
    
    // 3. 查找 SQL
    if (matcher.find()) {
        String sql = matcher.group();
        
        // 4. 处理末尾双引号
        if (sql.endsWith("\"")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }
    return null;
}
```

**正则表达式**:
```
(?i)(SELECT|INSERT|UPDATE)\s+.*?\s+FROM\s+.*?(?=(;|```|\"{3})|$)
```

功能: 从文本中提取第一个完整的 SQL 语句

---

## 🟢 Part 2: Text2SQLService 详解

### 2.1 核心功能

**作用**: 将自然语言问题转换为 SQL 查询语句

```
用户自然语言问题
  ↓
DiBrain AI (文本转 SQL)
  ↓
SQL 语句 + 表列表
  ↓
SQLParserService (提取表名)
  ↓
对比分析 (使用的表 vs 其他表)
  ↓
返回完整响应
```

### 2.2 主方法: generateSQLAndParse()

**位置**: Line 84-113

```java
@Transactional(rollbackFor = Exception.class)
public GenerateSQLResponseVO generateSQLAndParse(GenerateSQLRequestVO req) {
    // 1️⃣ 验证会话和权限
    SessionDetailDTO session = sessionService.getSession(req.getSessionId());
    sessionService.checkAuth(req.getCommonInfo().getUser(), session);
    
    // 2️⃣ 处理"再问一遍"
    if (req.isAskAgain()) {
        chatService.deleteLastTwoChatMessage(req.getSessionId());
    }
    
    // 3️⃣ 获取聊天历史 (RESPONSE 类型)
    List<String> history = chatService.getChatMessageHistory(
        req.getSessionId(), ChatMessageType.RESPONSE);
    
    // 4️⃣ 创建用户提问消息
    ChatCreateRequestDTO chatCreateRequestDTO = convertor
        .convertMessageVOToChatCreateDto(req);
    chatService.createChatMessage(chatCreateRequestDTO);
    
    // 5️⃣ 构建 Supplier (延迟执行)
    Supplier<CommonResponse<GenerateSQLResponseVO>> generateSQLResponseVOSupplier = 
        () -> getGenerateSQLResponse(req, history, session.getModel());
    
    // 6️⃣ 超时保护执行
    CommonResponse<GenerateSQLResponseVO> generateSQLResponseVO = 
        GlobalTimeOutHandler.executeTaskWithTimeout(
            generateSQLResponseVOSupplier,
            assistantGlobalConfig.getCommonChatTimeout(),  // 超时时间
            timeoutReturn(req.getQuestion(), 
                         req.getTranslateText(), 
                         req.getLanguageType()));  // 超时回调
    
    // 7️⃣ 设置 Session ID
    generateSQLResponseVO.getResponseVO().setSessionId(
        session.getSessionId());
    
    // 8️⃣ 创建响应消息
    chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
        generateSQLResponseVO.getResponseVO(),
        AgentUtils.buildDiAssistantCommonInfo(),
        session.getSessionId(),
        generateSQLResponseVO.getTraceId());
    Long chatId = chatService.createChatMessage(chatCreateRequestDTO);
    generateSQLResponseVO.getResponseVO().setChatId(chatId);
    
    return generateSQLResponseVO.getResponseVO();
}
```

### 2.3 核心方法: getGenerateSQLResponse()

**位置**: Line 115-176

**流程**:
```
1. 识别 SQL 方言
2. 转换聊天历史
3. 调用 AI 生成 SQL (generateSQL)
4. 从响应提取 SQL (parseSQLFromOutput)
5. 解析 SQL 表名 (parseSQLSelectedTables)
6. 区分使用的表和其他表
7. 构建响应对象
```

**代码**:
```java
private CommonResponse<GenerateSQLResponseVO> getGenerateSQLResponse(
    GenerateSQLRequestVO req, List<String> history, String model) {
    
    // 1. SQL 方言
    SQLDialect sqlDialect = SQLDialect.getDialect(req.getDialect());
    
    // 2. 转换历史
    List<Map<String, String>> chatHistory = toDiBrainChatHistory(history);
    
    // 3. 初始化变量
    String generatedSQL = CommonConstants.BLANK_STRING;
    String message = MessageConstants.TEXT2SQL_PREFIX_TEXT;
    String traceId = CommonConstants.BLANK_STRING;
    List<HiveTableVO> usedHiveTable = new ArrayList<>();
    List<HiveTableVO> removeUsedTable = new ArrayList<>();

    String question = GetQuestionUtils.getQuestion(
        req.getTranslateText(), req.getQuestion());

    try {
        // 4. 调用 AI 生成 SQL
        Text2SQLV2DTO generatedInfo = generateSQL(
            req.getCommonInfo(),
            question,
            req.getDialect(),
            req.getTableUidList(),
            chatHistory,
            model,
            req.getIdcRegion(),
            req.getMartList(),
            req.getSchemaList());
        
        // 5. 提取 Trace ID
        Text2SQLOutputDTO outputDTO = generatedInfo.getOutput();
        if (generatedInfo.getMetadata() != null) {
            traceId = generatedInfo.getMetadata().getRunId();
        }
        
        // 6. 从输出提取 SQL
        generatedSQL = parseSQLFromOutput(outputDTO.getOutput(), traceId);

        // 7. 如果没有指定表，解析 SQL 中的表
        if (CollectionUtils.isEmpty(req.getTableUidList())) {
            Set<String> sqlTables = sqlParserService.parseSQLSelectedTables(
                generatedSQL, sqlDialect, req.getCommonInfo().getRegion());
            
            // 8. 区分表
            usedHiveTable = findUsedHiveTable(outputDTO.getTables(), sqlTables);
            removeUsedTable = removeUsedTable(outputDTO.getTables(), sqlTables);
        }

    } catch (ServerException | JSQLParserException e) {
        message = MessageConstants.TEXT2SQL_UN_GENERATE_PREFIX_TEXT;
        if (e instanceof ServerException exception &&
            exception.getResponseCodeEnum() == ResponseCodeEnum.SQL_PARSE_ERROR) {
            traceId = (String) exception.getData();
        }
    }

    // 9. 构建响应
    GenerateSQLResponseVO generateSQLResponseVO = 
        GenerateSQLResponseVO.builder()
            .generatedSQL(generatedSQL)
            .prefixText(message)
            .usedTableList(usedHiveTable)
            .otherTableList(removeUsedTable)
            .question(req.getQuestion())
            .translateText(req.getTranslateText())
            .languageType(req.getLanguageType())
            .idcRegion(req.getIdcRegion())
            .martList(req.getMartList())
            .schemaList(req.getSchemaList())
            .tableUidList(req.getTableUidList())
            .build();
    
    return new CommonResponse<>(generateSQLResponseVO, traceId);
}
```

### 2.4 SQL 提取方法: parseSQLFromOutput()

**位置**: Line 319-338

```java
private String parseSQLFromOutput(String rawOutput, String traceId) {
    // 1. 查找 <sql> 标记
    int startIdx = rawOutput.indexOf(SQL_START_TOKEN) + SQL_START_TOKEN.length();
    int endIdx = rawOutput.indexOf(SQL_END_TOKEN, startIdx);

    String generatedSQL;
    
    // 2. 如果找到标记，提取 SQL
    if (startIdx >= 0 && endIdx >= 0 && endIdx > startIdx) {
        generatedSQL = rawOutput.substring(startIdx, endIdx);
        log.info("find generated sql: {}", generatedSQL);
    } else {
        // 3. 否则使用正则表达式本地提取
        generatedSQL = sqlParserService.parseSQLLocally(rawOutput);
        
        // 4. 如果本地提取也失败，抛异常
        if (generatedSQL == null) {
            log.error("can't generated SQL: The result is {};", rawOutput);
            throw new ServerException(ResponseCodeEnum.SQL_PARSE_ERROR, 
                                    "can't generated SQL", traceId);
        }
        log.info("llm output: {} find generated sql using local method: {}", 
                rawOutput, generatedSQL);
    }
    
    // 5. 处理转义字符
    return generatedSQL
        .replace("\\n", "\n")      // 转义换行符
        .replace("\\\"", "\"");    // 转义双引号
}
```

---

## 🔵 Part 3: ExplainSQLService 详解

### 3.1 核心功能

**作用**: 解释 SQL 语句的含义和功能

```
SQL 语句
  ↓
1. 解析表名
  ↓
2. 构建请求 (包含表信息)
  ↓
3. 调用 DiBrain AI
  ↓
4. 获取 SQL 解释
  ↓
返回解释文本
```

### 3.2 主方法: explainSQLInvoke()

**位置**: Line 46-77

```java
public ExplainSQLResponseVO explainSQLInvoke(ExplainSQLRequestVO req) {
    // 1️⃣ 验证权限
    SessionDetailDTO session = sessionService.getSession(req.getSessionId());
    sessionService.checkAuth(req.getCommonInfo().getUser(), session);
    
    // 2️⃣ 创建用户提问消息
    ChatCreateRequestDTO chatCreateRequestDTO = 
        convertor.convertMessageVOToChatCreateDto(req);
    chatService.createChatMessage(chatCreateRequestDTO);

    // 3️⃣ 超时保护的 AI 调用
    Supplier<CommonResponse<ExplainSQLResponseVO>> explainSQLResponseVOSupplier =
        () -> getExplainSQLInfo(
            req.getCommonInfo(),
            req.getQuestion(),      // SQL 语句
            req.getDialect(),
            req.getIdcRegion(),
            session.getModel());
    
    CommonResponse<ExplainSQLResponseVO> explainSQLResponseVO =
        GlobalTimeOutHandler.executeTaskWithTimeout(
            explainSQLResponseVOSupplier,
            assistantGlobalConfig.getExplainSQLTimeout(),
            timeoutReturn());

    // 4️⃣ 创建响应消息
    chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
        explainSQLResponseVO.getResponseVO(),
        AgentUtils.buildDiAssistantCommonInfo(),
        req.getSessionId(),
        explainSQLResponseVO.getTraceId());
    Long chatId = chatService.createChatMessage(chatCreateRequestDTO);
    explainSQLResponseVO.getResponseVO().setChatId(chatId);

    return explainSQLResponseVO.getResponseVO();
}
```

### 3.3 核心方法: getExplainSQLInfo()

**位置**: Line 79-115

```java
private CommonResponse<ExplainSQLResponseVO> getExplainSQLInfo(
    CommonInfo commonInfo, String sql, String dialect, 
    String idcRegion, String model) {
    
    CommonRequestDTO.CommonRequestDTOBuilder req = 
        CommonRequestDTO.builder();

    // 1. 构建配置
    CommonConfigDTO commonConfigDTO = CommonConfigDTO.builder()
        .configurable(ConfigurableDTO.builder().llm(model).build())
        .metadata(CommonReqMetadataDTO.builder()
            .reg(idcRegion)
            .dialect(dialect)
            .build())
        .build();
    req.config(commonConfigDTO);

    // 2. 解析 SQL 中的表
    Set<String> selectedTables = sqlParserService.parseSQLSelectedTables(
        sql, SQLDialect.getDialect(dialect), commonInfo.getRegion());

    // 3. 构建输入
    CommonInputDTO.CommonInputDTOBuilder inputBuilder =
        CommonInputDTO.builder()
            .chatContext(DiBrainUtils.buildChatContext(
                commonInfo, idcRegion))
            .question(sql)                    // SQL 作为问题
            .selectedTables(selectedTables);  // 关键：传入表名

    req.input(inputBuilder.build());

    // 4. 调用 AI
    CommonRequestDTO requestDTO = req.build();
    ExplainSQLDTO explainSQLDTO = diBrainClient
        .explainSQLByText(requestDTO);

    // 5. 构建响应
    ExplainSQLResponseVO explainSQLResponseVO =
        ExplainSQLResponseVO.builder()
            .prefixText(CommonConstants.BLANK_STRING)
            .explanation(explainSQLDTO.getOutput())
            .build();

    return CommonResponse.<ExplainSQLResponseVO>builder()
        .responseVO(explainSQLResponseVO)
        .traceId(explainSQLDTO.getMetadata().getRunId())
        .build();
}
```

**关键点**: 需要先解析 SQL 表名，然后传给 AI，帮助 AI 更好地理解上下文

---

## 🟡 Part 4: FixSQLService 详解

### 4.1 核心功能

**作用**: 修复有错误的 SQL 语句

```
错误 SQL + 错误信息
  ↓
1. 解析表名
  ↓
2. 构建请求 (包含错误信息)
  ↓
3. 调用 DiBrain AI (修复策略)
  ↓
4. 获取修复后的 SQL + 解释
  ↓
返回修复结果
```

### 4.2 主方法: fixSQLInvoke()

**位置**: Line 53-69

```java
public FixSQLResponseVO fixSQLInvoke(FixSQLRequestVO req) {
    // 1️⃣ 验证权限
    SessionDetailDTO session = sessionService.getSession(req.getSessionId());
    sessionService.checkAuth(req.getCommonInfo().getUser(), session);
    
    // 2️⃣ 创建用户提问消息
    ChatCreateRequestDTO chatCreateRequestDTO = 
        convertor.convertMessageVOToChatCreateDto(req);
    chatService.createChatMessage(chatCreateRequestDTO);

    // 3️⃣ 超时保护的 AI 调用
    Supplier<CommonResponse<FixSQLResponseVO>> fixSQLResponseVOSupplier = 
        () -> getFixSQLInfo(
            req.getQuestion(),       // 错误 SQL
            req.getDialect(),
            req.getErrorMessage(),   // 错误信息
            req.getIdcRegion(),
            session.getModel());
    
    CommonResponse<FixSQLResponseVO> fixSQLResponseVO =
        GlobalTimeOutHandler.executeTaskWithTimeout(
            fixSQLResponseVOSupplier,
            assistantGlobalConfig.getFixSQLTimeout(),
            timeoutReturn());

    // 4️⃣ 创建响应消息
    chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(
        fixSQLResponseVO.getResponseVO(),
        AgentUtils.buildDiAssistantCommonInfo(),
        req.getSessionId(),
        fixSQLResponseVO.getTraceId());
    Long chatId = chatService.createChatMessage(chatCreateRequestDTO);
    fixSQLResponseVO.getResponseVO().setChatId(chatId);

    return fixSQLResponseVO.getResponseVO();
}
```

### 4.3 核心方法: getFixSQLInfo()

**位置**: Line 71-120

```java
private CommonResponse<FixSQLResponseVO> getFixSQLInfo(
    String errorSQL, String dialect, String errorMessage, 
    String idcRegion, String model) {
    
    // 1. 解析表名
    Set<String> selectedTables = sqlParserService.parseSQLSelectedTables(
        errorSQL, SQLDialect.getDialect(dialect), idcRegion);
    
    FixSQLRequestDTO.FixSQLRequestDTOBuilder req = 
        FixSQLRequestDTO.builder();

    // 2. 构建配置 (包含修复策略参数)
    CommonConfigDTO commonConfigDTO = CommonConfigDTO.builder()
        .metadata(CommonReqMetadataDTO.builder()
            .maxLLMInvoke(assistantGlobalConfig.getFixSQLMaxLLMInvoke())    // 最大 LLM 调用次数
            .maxExecutionSecond(assistantGlobalConfig.getFixSQLMaxExecutionSecond())  // 最大执行秒数
            .model(model)
            .reg(idcRegion)
            .dialect(dialect)
            .sqlError(errorMessage)  // 错误信息
            .build())
        .build();
    req.config(commonConfigDTO);

    // 3. 构建输入
    FixSQLInputDTO.FixSQLInputDTOBuilder inputBuilder =
        FixSQLInputDTO.builder()
            .errorSql(errorSQL)            // 错误 SQL
            .errorInfo(errorMessage)       // 错误详情
            .region(idcRegion)
            .dialect(dialect)
            .selectedTables(selectedTables);  // 表名
    req.input(inputBuilder.build());

    // 4. 调用 AI 修复
    FixSQLRequestDTO requestDTO = req.build();
    FixSQLDTO fixSQLResp = diBrainClient.fixSQLByText(requestDTO);
    
    // 5. 提取修复结果
    FixSQLOutputDTO fixSQLOutputDTO = fixSQLResp.getOutput();
    String sql = fixSQLOutputDTO.getFixedQuery();
    String explanation = fixSQLOutputDTO.getExplanation();

    // 6. 构建响应
    FixSQLResponseVO fixSQLResponseVO = FixSQLResponseVO.builder()
        .success(true)
        .prefixText(CommonConstants.BLANK_STRING)
        .fixedSQL(sql)
        .explanation(explanation)
        .build();

    // 7. 验证修复是否成功
    if (StringUtils.isBlank(fixSQLResponseVO.getFixedSQL())) {
        fixSQLResponseVO.setSuccess(false);
        fixSQLResponseVO.setFixedSQL(CommonConstants.BLANK_STRING);
        fixSQLResponseVO.setExplanation(CommonConstants.BLANK_STRING);
    }

    return CommonResponse.<FixSQLResponseVO>builder()
        .responseVO(fixSQLResponseVO)
        .traceId(fixSQLResp.getMetadata().getRunId())
        .build();
}
```

---

## 📊 完整数据流程

```
┌─────────────────────────────────────────────────────────┐
│ 用户请求                                               │
│ POST /sql/{text2sql|explainsql|fixsql}                 │
└────────────────┬────────────────────────────────────────┘
                 ↓
        ┌───────────────────┐
        │ SQLController     │
        └────────┬──────────┘
                 ↓
    ┌────────────┼────────────┐
    ↓            ↓            ↓
Text2SQL   ExplainSQL   FixSQL
Service    Service      Service

    └────────────┼────────────┘
                 ↓
    ┌──────────────────────────────┐
    │ 1️⃣ 验证权限                 │
    │    sessionService.checkAuth()│
    └────────────┬─────────────────┘
                 ↓
    ┌──────────────────────────────┐
    │ 2️⃣ 创建用户消息              │
    │    chatService.createMessage()│
    └────────────┬─────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 3️⃣ SQLParserService (可选)               │
    │    解析 SQL 表名                         │
    │    - 调用 DataMap API                   │
    │    - Fallback JSQLParser                │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 4️⃣ 超时保护 (GlobalTimeOutHandler)       │
    │    executeTaskWithTimeout()              │
    │    - 设置超时时间                        │
    │    - 设置超时回调                        │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 5️⃣ DiBrainClient (Feign)                 │
    │    - .generateSQLByText()                │
    │    - .explainSQLByText()                 │
    │    - .fixSQLByText()                     │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 6️⃣ DiBrain AI 服务                       │
    │    /text2sql/invoke                      │
    │    /sql/explain/invoke                   │
    │    /sql/correct/invoke                   │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 7️⃣ 响应处理                              │
    │    - 提取结果                            │
    │    - 数据转换                            │
    │    - 错误处理                            │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 8️⃣ 创建响应消息                          │
    │    chatService.createMessage()           │
    │    (保存 AI 回复)                        │
    └────────────┬────────────────────────────┘
                 ↓
    ┌──────────────────────────────────────────┐
    │ 9️⃣ 返回最终响应                          │
    │    GenerateSQLResponseVO                 │
    │    ExplainSQLResponseVO                  │
    │    FixSQLResponseVO                      │
    └──────────────────────────────────────────┘
```

---

## 🔑 关键代码片段总结

### 1. 权限检查
```java
SessionDetailDTO session = sessionService.getSession(sessionId);
sessionService.checkAuth(user, session);
```

### 2. SQL 解析 (双层策略)
```java
// Layer 1: DataMap API
ParseTableRespDTO respDTO = dataMapClient.parseSelectedTablesFromSQL(req);

// Layer 2 Fallback: JSQLParser
Set<String> localResult = TablesNamesFinder.findTables(sql);
```

### 3. 超时保护
```java
GlobalTimeOutHandler.executeTaskWithTimeout(
    supplier,                    // AI 调用
    timeout,                     // 超时时间
    fallback);                   // 超时时返回
```

### 4. 错误处理
```java
try {
    resp = diBrainClient.generateSQLByText(reqDTO);
} catch (FeignException e) {
    throw new ServerException(ResponseCodeEnum.SQL_PARSE_ERROR, "API Error", e);
}
```

### 5. SQL 提取方法
```java
// 方法1: 标记提取 (优先)
int startIdx = rawOutput.indexOf("<sql>");
int endIdx = rawOutput.indexOf("</sql>");
sql = rawOutput.substring(startIdx + 5, endIdx);

// 方法2: 正则表达式提取 (Fallback)
sql = sqlParserService.parseSQLLocally(rawOutput);
```

---

## ✅ 总结

### 四个 SQL 服务的特点

| 服务 | 复杂度 | 依赖项 | 超时 |
|------|--------|--------|------|
| SQLParser | 低 | DataMap + JSQLParser | 无 |
| Text2SQL | 高 | DiBrain + SQLParser | 有 |
| ExplainSQL | 中 | DiBrain + SQLParser | 有 |
| FixSQL | 高 | DiBrain + SQLParser | 有 |

### 架构优点

✅ **分层设计**: SQLParser 独立，易于复用
✅ **容错机制**: 多层 Fallback（API → 本地解析）
✅ **超时保护**: 所有 AI 调用都有超时控制
✅ **权限检查**: 每个操作都验证用户权限
✅ **错误处理**: 完善的异常捕获和转换
✅ **历史记录**: 支持多轮对话，维护上下文

### 使用场景

1. **Text2SQL**: 用户问"查询今天的销售数据" → 自动生成 SQL
2. **ExplainSQL**: 用户上传 SQL，请求解释其含义
3. **FixSQL**: 用户提交有错的 SQL，自动修复并解释

