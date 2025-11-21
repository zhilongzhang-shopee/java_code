# SQL 服务快速参考指南

## 🎯 一页纸总结

```
四个 SQL 服务:

1. SQLParserService        → SQL 中提取表名
2. Text2SQLService         → 自然语言 → SQL
3. ExplainSQLService       → 解释 SQL 含义
4. FixSQLService           → 修复错误 SQL

所有 AI 调用都有:
✅ 超时保护      (GlobalTimeOutHandler)
✅ 权限检查      (sessionService.checkAuth)
✅ 历史记录      (ChatService 保存消息)
✅ 错误处理      (FeignException 捕获)
```

---

## 📊 四个服务对比

| 特性 | SQLParser | Text2SQL | ExplainSQL | FixSQL |
|------|-----------|----------|-----------|--------|
| **功能** | 提取表名 | 生成 SQL | 解释 SQL | 修复 SQL |
| **API** | 内部 | `/sql/text2sql` | `/sql/explainsql` | `/sql/fixsql` |
| **输入** | SQL 字符串 | 自然语言问题 | SQL + 方言 | 错误 SQL + 错误信息 |
| **输出** | Set<String> | SQL + 表列表 | 解释文本 | 修复 SQL + 解释 |
| **依赖** | DataMap API | DiBrain AI | DiBrain AI | DiBrain AI |
| **超时** | ❌ | ✅ | ✅ | ✅ |
| **行数** | 99 | 390 | 130 | 136 |

---

## 🔄 各服务流程

### SQLParserService

```
SQL (字符串)
  ↓
if (dialect == FLINK) → 本地解析
else → DataMap API 解析
  ↓
  ├─ 成功 → 返回表名集合
  └─ 失败 → Fallback 本地解析
  ↓
Set<String> (schema.table 格式)
```

### Text2SQLService

```
自然语言问题 + 历史
  ↓
1. 验证权限
2. 创建提问消息
3. 超时保护调用 AI
4. 提取生成的 SQL
5. 解析 SQL 表名
6. 区分使用的表和其他表
7. 创建响应消息
  ↓
GenerateSQLResponseVO
  ├─ generatedSQL: SQL 语句
  ├─ usedTableList: 使用的表
  └─ otherTableList: 其他表
```

### ExplainSQLService

```
SQL 语句
  ↓
1. 验证权限
2. 创建提问消息
3. 解析 SQL 表名 ← 关键
4. 超时保护调用 AI
5. 提取解释文本
6. 创建响应消息
  ↓
ExplainSQLResponseVO
  ├─ explanation: SQL 解释
  └─ prefixText: 前缀
```

### FixSQLService

```
错误 SQL + 错误信息
  ↓
1. 验证权限
2. 创建提问消息
3. 解析 SQL 表名
4. 超时保护调用 AI (包含修复策略参数)
5. 提取修复的 SQL 和解释
6. 验证修复是否成功
7. 创建响应消息
  ↓
FixSQLResponseVO
  ├─ fixedSQL: 修复后 SQL
  ├─ explanation: 解释
  └─ success: 是否成功
```

---

## 🔑 关键代码模式

### 1. 验证权限 (所有服务)
```java
SessionDetailDTO session = sessionService.getSession(sessionId);
sessionService.checkAuth(user, session);
```

### 2. 创建消息 (所有服务)
```java
ChatCreateRequestDTO dto = convertor.convertMessageVOToChatCreateDto(req);
chatService.createChatMessage(dto);
```

### 3. 超时保护 (AI 服务)
```java
Supplier<CommonResponse<...>> supplier = () -> getXxxInfo(...);
CommonResponse<...> resp = GlobalTimeOutHandler.executeTaskWithTimeout(
    supplier,
    assistantGlobalConfig.getTimeout(),
    timeoutReturn());  // 超时时返回
```

### 4. 解析表名 (Text2SQL, ExplainSQL, FixSQL)
```java
Set<String> tables = sqlParserService.parseSQLSelectedTables(
    sql, SQLDialect.getDialect(dialect), region);
```

### 5. SQL 提取 (Text2SQLService)
```java
// 优先级 1: 标记提取
String sql = rawOutput.substring(
    rawOutput.indexOf("<sql>") + 5,
    rawOutput.indexOf("</sql>"));

// 优先级 2: 正则提取
sql = sqlParserService.parseSQLLocally(rawOutput);

// 处理转义
sql = sql.replace("\\n", "\n").replace("\\\"", "\"");
```

### 6. 错误处理
```java
try {
    resp = diBrainClient.generateSQLByText(reqDTO);
} catch (FeignException e) {
    throw new ServerException(ResponseCodeEnum.SQL_PARSE_ERROR, "API Error", e);
}
```

---

## 📝 数据模型

### GenerateSQLRequestVO (Text2SQL 输入)
```
sessionId       Long            会话 ID
commonInfo      CommonInfo      用户信息
question        String          自然语言问题
translateText   String          翻译后的文本
dialect         String          SQL 方言 (HIVE, MYSQL)
tableUidList    List<String>    指定的表
martList        List<String>    业务域
schemaList      List<String>    schema
idcRegion       String          地域
languageType    String          语言类型
```

### GenerateSQLResponseVO (Text2SQL 输出)
```
generatedSQL    String                      生成的 SQL
usedTableList   List<HiveTableVO>          SQL 中使用的表
otherTableList  List<HiveTableVO>          AI 返回但未使用的表
prefixText      String                      前缀消息
sessionId       Long                        会话 ID
chatId          Long                        聊天消息 ID
question        String                      原始问题
translateText   String                      翻译后的问题
```

### ExplainSQLRequestVO (ExplainSQL 输入)
```
sessionId       Long            会话 ID
commonInfo      CommonInfo      用户信息
question        String          SQL 语句
dialect         String          SQL 方言
idcRegion       String          地域
```

### ExplainSQLResponseVO (ExplainSQL 输出)
```
explanation     String          SQL 解释
prefixText      String          前缀
chatId          Long            聊天消息 ID
```

### FixSQLRequestVO (FixSQL 输入)
```
sessionId       Long            会话 ID
commonInfo      CommonInfo      用户信息
question        String          错误 SQL
errorMessage    String          错误信息
dialect         String          SQL 方言
idcRegion       String          地域
```

### FixSQLResponseVO (FixSQL 输出)
```
fixedSQL        String          修复后的 SQL
explanation     String          修复说明
success         Boolean         是否成功修复
prefixText      String          前缀
chatId          Long            聊天消息 ID
```

---

## ⚙️ 超时配置

从 `AssistantGlobalConfig` 读取:

| 参数 | 配置项 | 用途 |
|------|--------|------|
| Text2SQL | `commonChatTimeout` | 生成 SQL 超时 |
| ExplainSQL | `explainSQLTimeout` | 解释 SQL 超时 |
| FixSQL | `fixSQLTimeout` | 修复 SQL 超时 |
| FixSQL | `fixSQLMaxLLMInvoke` | 最大 LLM 调用次数 |
| FixSQL | `fixSQLMaxExecutionSecond` | 最大执行秒数 |

---

## 📍 文件位置

```
控制器:
  └─ di-assistant-web/src/main/java/.../controller/sql/
     └─ SQLController.java

服务:
  └─ di-assistant-service/src/main/java/.../service/sql/
     ├─ SQLParserService.java (99 行)
     ├─ Text2SQLService.java (390 行)
     ├─ ExplainSQLService.java (130 行)
     └─ FixSQLService.java (136 行)

客户端:
  └─ di-assistant-service/src/main/java/.../rest/client/dibrain/
     └─ DiBrainClient.java (接口定义 API 调用)

测试:
  └─ di-assistant-service/src/test/java/.../service/sql/
     └─ ... (可能有测试文件)
```

---

## 🎯 使用场景

### 场景 1: 用户想自动生成 SQL

```
用户: "查询 2024 年 1 月的订单"
  ↓
POST /sql/text2sql
  {
    "sessionId": 123,
    "question": "查询 2024 年 1 月的订单",
    "dialect": "HIVE"
  }
  ↓
返回:
  {
    "generatedSQL": "SELECT * FROM orders WHERE year(create_time)=2024 AND month(create_time)=1",
    "usedTableList": [{"tableName": "orders", "schema": "warehouse"}],
    "otherTableList": []
  }
```

### 场景 2: 用户想理解复杂 SQL

```
用户: 上传一段 SQL，请解释
  ↓
POST /sql/explainsql
  {
    "sessionId": 123,
    "question": "SELECT ... FROM ... JOIN ...",
    "dialect": "HIVE"
  }
  ↓
返回:
  {
    "explanation": "这个 SQL 查询从订单表和客户表进行内连接，..."
  }
```

### 场景 3: 用户的 SQL 有错误

```
用户: "我的 SQL 报错，帮我修复"
  ↓
POST /sql/fixsql
  {
    "sessionId": 123,
    "question": "SELECT * FROM orders WHERE order_date='2024-01-01'",  // 错误
    "errorMessage": "Column 'order_date' doesn't exist"
  }
  ↓
返回:
  {
    "fixedSQL": "SELECT * FROM orders WHERE create_time='2024-01-01'",
    "explanation": "错误：表中没有 order_date 列，应该使用 create_time",
    "success": true
  }
```

---

## ✅ 关键要点

1. **SQLParserService 是基础**
   - 其他三个服务都依赖它
   - 双层 Fallback 策略（DataMap API → JSQLParser）

2. **所有 AI 调用都有超时保护**
   - 防止无限期等待
   - 返回友好的超时消息

3. **权限检查必须做**
   - 每个操作都要验证用户
   - 防止数据泄露

4. **聊天历史很重要**
   - 帮助 AI 理解上下文
   - Text2SQL 使用 RESPONSE 类型历史

5. **错误处理很完善**
   - FeignException 捕获
   - 数据验证（SQL 不为空等）

6. **表名解析关键**
   - ExplainSQL 和 FixSQL 都需要先解析表名
   - 帮助 AI 了解数据库结构

---

## 🚀 扩展可能

如果要添加新的 SQL 功能，模板是:

```java
@Service
public class NewSQLService {
    
    @Resource private DiBrainClient diBrainClient;
    @Resource private SQLParserService sqlParserService;
    @Resource private ChatService chatService;
    @Resource private SessionService sessionService;
    @Resource private AssistantGlobalConfig config;
    
    public ResponseVO invoke(RequestVO req) {
        // 1. 验证权限
        SessionDetailDTO session = sessionService.getSession(req.getSessionId());
        sessionService.checkAuth(req.getCommonInfo().getUser(), session);
        
        // 2. 创建提问消息
        chatService.createChatMessage(...);
        
        // 3. 超时保护
        CommonResponse<ResponseVO> resp = 
            GlobalTimeOutHandler.executeTaskWithTimeout(
                () -> getInfo(...),
                config.getTimeout(),
                timeoutReturn());
        
        // 4. 创建响应消息
        chatService.createChatMessage(...);
        
        return resp.getResponseVO();
    }
    
    private CommonResponse<ResponseVO> getInfo(...) {
        // 实现具体逻辑
    }
}
```

