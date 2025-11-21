# Diana Knowledge Base - Kafka 消费场景分析

## 目录
1. [项目概述](#项目概述)
2. [Kafka 使用场景](#kafka-使用场景)
3. [消息拉取流程](#消息拉取流程)
4. [消息消费机制](#消息消费机制)
5. [保证消息正常消费](#保证消息正常消费)
6. [故障诊断与解决](#故障诊断与解决)
7. [架构图](#架构图)

---

## 项目概述

**项目名称**: Diana Knowledge Base (Diana 知识库)  
**主要功能**: 基于 AI 的聊天反馈跟踪系统  
**消息系统**: 基于 Kafka + Spring Cloud Stream 框架

### 核心业务流程
```
用户提问 → ChatBot 回答 → 用户反馈 → 反馈追踪
  ↓           ↓           ↓           ↓
Chat消息事件  反馈事件   反馈事件   跟踪记录存储
```

---

## Kafka 使用场景

### 1. **场景一：Chat Message 事件消费**

#### 使用目的
从数据库变更事件流中捕获**用户提问和 AI 回答**的新增/更新事件，将其存储到项目内部的反馈追踪系统中。

#### 数据源
- **消息主题**: `di.shopee_di_rag_*_db__chat_message_tab__*`
- **数据来源**: Debezium CDC（Change Data Capture）从 MySQL `chat_message_tab` 表
- **事件类型**: insert, update, delete
- **消费组**: `di-diana-group-*` (如 liveish、live、staging 环境)

#### 消费器类
```
ChatMessageEventConsumer.java
- 处理 Chat Message 事件（Debezium CDC 格式）
- 只处理 update 类型事件
- 只处理 Response 类型消息（即 AI 的回答）
```

#### 核心字段映射
```
ChatMessageEvent (Kafka消息)
├── id: 消息ID（response_id）
├── sessionId: 会话ID
├── messageType: 消息类型（只处理 "Response"）
├── chatContent: 聊天内容（JSON格式，包含AI回答）
├── externalTraceId: 跟踪ID
└── _event: 事件元数据
    └── type: 事件类型（insert/update/delete）
        
↓ 转换 ↓

FeedbackCycleDao (项目数据库)
├── responseId: 对应 chatMessageEvent.id
├── sessionId: 会话ID
├── question: 用户问题（JSON格式）
├── answer: AI 回答（JSON格式）
├── status: 跟踪状态（默认为 "UnTracking"）
└── feedback: 用户反馈（初始为空，由 FeedbackEvent 补充）
```

### 2. **场景二：Feedback 事件消费**

#### 使用目的
捕获**用户对 AI 回答的反馈**（赞/踩/评论），更新反馈记录并补充 Chat Message 数据。

#### 数据源
- **消息主题**: `di.shopee_di_rag_*_db__feedback_tab__*`
- **数据来源**: Debezium CDC 从 MySQL `feedback_tab` 表
- **事件类型**: insert, update
- **消费组**: `di-diana-group-*`

#### 消费器类
```
FeedbackEventConsumer.java
- 处理 Feedback 事件（Debezium CDC 格式）
- 处理 insert 和 update 事件
- 关联到对应的 Chat Message 并更新反馈状态
```

#### 核心字段映射
```
FeedbackEvent (Kafka消息)
├── id: 反馈ID
├── chatId: 对应的 Chat Message ID（response_id）
├── sessionId: 会话ID
├── ratting: 评分（1-5）
├── comment: 反馈评论
└── _event: 事件元数据
    └── type: 事件类型（insert/update）
        
↓ 转换 ↓

FeedbackCycleDao (项目数据库)
├── feedbackId: 对应 feedbackEvent.id
├── responseId: 对应的 Chat Message ID
├── feedback: 反馈类型（根据评分转换）
├── feedbackReason: 反馈原因（用户评论）
└── status: 跟踪状态（继承或更新）
```

---

## 消息拉取流程

### 架构设计

```
Kafka Broker (消息队列)
        ↓
Spring Cloud Stream (消息绑定框架)
        ↓
Spring Cloud Stream Binder (Kafka 绑定器)
        ↓
Consumer Function (消费函数)
├── chatMessageEventHandler (Chat消息)
└── feedbackEventHandler (反馈消息)
        ↓
Event Consumer (事件消费器)
├── ChatMessageEventConsumer.java
└── FeedbackEventConsumer.java
        ↓
业务处理 (Transactional)
├── 校验数据
├── 解析 JSON
├── 存储/更新数据库
└── 记录日志
```

### 1. **配置加载**

#### 配置文件位置
```
diana-knowledge-base-core/src/main/resources/application-core.yml
```

#### 关键配置
```yaml
spring:
  cloud:
    function:
      definition: chatMessageEventHandler;feedbackEventHandler  # 定义消费函数
    stream:
      default-binder: feedback-kafka  # 默认绑定器
      binders:
        feedback-kafka:
          type: kafka  # 消息队列类型
          environment.spring.cloud.stream.kafka:
            binder:
              consumer-properties:
                client.id: di_diana_consumer  # 消费者ID
              configuration:
                security.protocol: SASL_PLAINTEXT  # 安全协议
                sasl.mechanism: PLAIN  # SASL认证机制
                sasl.jaas.config: "..."  # 认证凭证
                session.timeout.ms: 30000  # 会话超时
                heartbeat.interval.ms: 10000  # 心跳间隔
                max.poll.records: 100  # 每次拉取最多100条消息
                enable.auto.commit: false  # 禁用自动提交（手动提交）
              autoCreateTopics: false  # 不自动创建主题
      bindings:
        chatMessageEventHandler-in-0:
          binder: feedback-kafka
          contentType: application/json
          consumer:
            concurrency: 1  # 并发度为1（单线程消费）
            max-attempts: 3  # 最多重试3次
            back-off-initial-interval: 1000  # 初始退避间隔1秒
            back-off-multiplier: 2.0  # 每次退避时间x2
        feedbackEventHandler-in-0:
          binder: feedback-kafka
          contentType: application/json
          consumer:
            concurrency: 1
            max-attempts: 3
            back-off-initial-interval: 1000
            back-off-multiplier: 2.0
```

#### 环境特定配置

**staging 环境** (`application-core-staging.yml`)
```yaml
bindings:
  chatMessageEventHandler-in-0:
    destination: di.shopee_di_rag_staging_db__chat_message_tab__live
    group: di-diana-group-staging
  feedbackEventHandler-in-0:
    destination: di.shopee_di_rag_staging_db__feedback_tab__live
    group: di-diana-group-staging
```

**liveish 环境** (`application-core-liveish.yml`)
```yaml
bindings:
  chatMessageEventHandler-in-0:
    destination: di.shopee_di_rag_liveish_db__chat_message_tab__live
    group: di-diana-group-liveish
  feedbackEventHandler-in-0:
    destination: di.shopee_di_rag_liveish_db__feedback_tab__live
    group: di-diana-group-liveish
```

### 2. **消息拉取流程**

#### 流程步骤

```
1. 应用启动
   ↓
2. Spring Cloud Stream 初始化
   - 读取配置文件 (application-core-{env}.yml)
   - 根据 spring.cloud.function.definition 注册函数
   - 根据 spring.cloud.stream.bindings 配置绑定
   
3. 消费器连接
   - 创建 Kafka 消费者
   - 建立与 Kafka Broker 的连接
   - 通过 SASL 认证
   - 加入消费组 (consumer group)
   
4. 订阅主题
   - 订阅配置的主题 (destination)
   - 从消费组的偏移量开始消费
   
5. 拉取消息循环
   - 每秒拉取最多 100 条消息
   - 解析 JSON 为 ChatMessageEvent / FeedbackEvent 对象
   - 调用对应的消费器处理
   - 异常重试（最多3次，指数退避）
   - 提交偏移量
   
6. 处理消息
   - @Bean Consumer<Message<ChatMessageEvent>> chatMessageEventHandler()
   - @Bean Consumer<Message<FeedbackEvent>> feedbackEventHandler()
   - 事务处理 (@Transactional)
   - 数据库操作 (UPSERT)
   
7. 错误处理
   - 异常捕获
   - 重试
   - 日志记录
```

### 3. **消费函数注册**

#### Spring Cloud Function 集成

```java
// 在 ChatMessageEventConsumer.java 中
@Bean
public Consumer<Message<ChatMessageEvent>> chatMessageEventHandler() {
    return message -> {
        try {
            ChatMessageEvent event = message.getPayload();
            processMessage(event);  // 处理消息
        } catch (Exception e) {
            log.error("Error processing chat message event", e);
            throw e;  // 异常重新抛出以触发重试
        }
    };
}

// 在 FeedbackEventConsumer.java 中
@Bean
public Consumer<Message<FeedbackEvent>> feedbackEventHandler() {
    return message -> {
        try {
            FeedbackEvent event = message.getPayload();
            processFeedback(event);
        } catch (Exception e) {
            log.error("Error processing feedback event", e);
            throw e;
        }
    };
}
```

#### 函数名称映射

```yaml
spring.cloud.function.definition: chatMessageEventHandler;feedbackEventHandler
```

Spring Cloud Stream 会自动映射：
- `chatMessageEventHandler` → `chatMessageEventHandler-in-0` (输入绑定)
- `feedbackEventHandler` → `feedbackEventHandler-in-0` (输入绑定)

---

## 消息消费机制

### 1. **Chat Message 事件消费流程**

#### 消费器: `ChatMessageEventConsumer.java`

```
入站消息 (ChatMessageEvent)
    ↓
1. 消息反序列化
   - Spring Cloud Stream 从 Kafka 消息反序列化为 ChatMessageEvent 对象
   - 格式: Debezium CDC JSON 格式

2. 事件类型校验
   if (event.getEvent() == null || event.getEvent().getType() == null) {
       log.warn("Event type is null, skipping");
       return;  // 跳过
   }

3. 事件类型过滤
   if (!"update".equals(eventType)) {
       log.debug("Not an update event, skipping");
       return;  // 只处理 update 事件（确保数据完整）
   }

4. 消息类型过滤
   if (!"Response".equalsIgnoreCase(event.getMessageType())) {
       log.debug("Not an assistant message, skipping");
       return;  // 只处理 Response 类型（AI 回答）
   }

5. 必填字段校验
   if (event.getId() == null || event.getSessionId() == null) {
       log.error("Invalid chat message event, missing required fields");
       return;  // 跳过不完整数据
   }

6. 重复性检查
   Optional<FeedbackCycleDao> existingRecord = 
       feedbackCycleManager.findByResponseId(event.getId());
   if (existingRecord.isPresent()) {
       log.info("Chat message already processed, skipping duplicate");
       return;  // 避免重复处理
   }

7. 解析聊天内容
   ResponseMessageDTO responseMsg = parseChatContent(event.getChatContent());
   - 将 JSON 字符串解析为对象
   - 提取 question 和 answer 信息
   - 提取 dataScope（包含关联的话题列表）

8. 构建反馈周期数据
   FeedbackCycleDao feedbackCycle = FeedbackCycleDao.builder()
       .questionId(requestId)           // 问题 ID
       .responseId(event.getId())       // 回答 ID
       .sessionId(event.getSessionId()) // 会话 ID
       .question(question)              // 问题内容(JSON)
       .answer(answer)                  // 回答内容(JSON)
       .status("UnTracking")            // 初始状态
       .feedback("")                    // 待 Feedback 事件填充
       .feedbackReason("")              // 待 Feedback 事件填充
       .user(user)                      // 用户邮箱
       .messageCtime(messageCtime)      // 消息创建时间
       .build();

9. UPSERT 操作（并发安全）
   feedbackCycleManager.upsertFeedback(feedbackCycle);
   - 使用 INSERT ... ON DUPLICATE KEY UPDATE
   - 如果消息已存在则更新，否则插入
   - 确保并发环境下的数据一致性

10. 批量创建话题-消息关联
    if (!CollectionUtils.isEmpty(topicList)) {
        topicMessageRelationManager.batchCreateRelations(relations);
        // 为每个话题创建关联关系
    }

11. 事务处理
    - 所有操作都在 @Transactional 事务内
    - 异常时回滚所有更改
    - 异常被重新抛出，触发 Kafka 重试机制

12. 日志记录
    log.info("Successfully upserted feedback cycle for response: {}", event.getId());
```

### 2. **Feedback 事件消费流程**

#### 消费器: `FeedbackEventConsumer.java`

```
入站消息 (FeedbackEvent)
    ↓
1. 消息反序列化
   - Spring Cloud Stream 从 Kafka 消息反序列化为 FeedbackEvent 对象
   - 格式: Debezium CDC JSON 格式

2. 事件类型校验
   if (event.getEvent() == null || event.getEvent().getType() == null) {
       log.warn("Event type is null, skipping");
       return;
   }

3. 事件类型过滤
   if (!"insert".equals(eventType) && !"update".equals(eventType)) {
       log.debug("Not an insert or update event, skipping");
       return;  // 只处理 insert/update 事件
   }

4. 必填字段校验
   if (event.getChatId() == null || event.getSessionId() == null) {
       log.error("Invalid feedback event, missing required fields");
       return;
   }

5. 反馈类型转换
   String newFeedback = FeedbackType.getRattingTypeByScore(event.getRatting())
       .getDbValue();
   - 将评分（1-5）转换为反馈类型字符串
   - 如: 5分 → "like", 1分 → "dislike"

6. 判断是否为反馈更新
   if ("update".equals(eventType)) {
       isFeedbackChanged = true;
   }

7. 如果是反馈更新，执行软删除和新增
   if (isFeedbackChanged) {
       // a. 查询旧记录
       Optional<FeedbackCycleDao> oldRecordOpt = 
           feedbackCycleManager.findByResponseId(event.getChatId());
       
       if (oldRecordOpt.isPresent()) {
           // b. 软删除旧记录
           oldRecord.setDeletedAt(System.currentTimeMillis());
           feedbackCycleManager.update(oldRecord);
           
           // c. 创建新记录（包含新反馈）
           FeedbackCycleDao feedbackCycle = FeedbackCycleDao.builder()
               .questionId(oldRecord.getQuestionId())
               .responseId(oldRecord.getResponseId())
               .sessionId(oldRecord.getSessionId())
               .feedbackId(event.getId())
               .feedback(newFeedback)           // 新反馈
               .feedbackReason(event.getComment())  // 用户评论
               // ... 其他字段继承自旧记录
               .build();
           
           feedbackCycleManager.upsertFeedback(feedbackCycle);
       }
   } else {
       // 如果是新反馈，直接创建记录
       FeedbackCycleDao feedbackCycle = FeedbackCycleDao.builder()
           .responseId(event.getChatId())
           .sessionId(event.getSessionId())
           .feedbackId(event.getId())
           .feedback(newFeedback)
           .feedbackReason(event.getComment())
           // ... 其他字段
           .build();
       
       feedbackCycleManager.upsertFeedback(feedbackCycle);
   }

8. 事务处理和重试
   - 异常被捕获、记录和重新抛出
   - 触发 Spring Cloud Stream 的重试机制
```

### 3. **事务管理**

#### 事务配置

```java
@Transactional  // Spring 事务注解
public void processMessage(ChatMessageEvent event) {
    try {
        // 所有数据库操作都在一个事务内
        feedbackCycleManager.upsertFeedback(feedbackCycle);
        topicMessageRelationManager.batchCreateRelations(relations);
    } catch (Exception e) {
        log.error("Error processing chat message event", e);
        throw e;  // 异常时自动回滚
    }
}
```

#### 事务特性
- **ACID 保证**: 原子性、一致性、隔离性、持久性
- **自动回滚**: 异常时所有更改回滚
- **持久化**: 成功提交后数据持久化到数据库

---

## 保证消息正常消费

### 1. **Kafka 消费者级别**

#### 配置详解

| 配置项 | 值 | 作用 |
|-------|-----|------|
| `client.id` | `di_diana_consumer` | 消费者标识符，用于日志和监控 |
| `security.protocol` | `SASL_PLAINTEXT` | 使用 SASL 认证，明文传输 |
| `sasl.mechanism` | `PLAIN` | 使用用户名密码认证 |
| `session.timeout.ms` | `30000` | 30秒无心跳判定消费者宕机 |
| `heartbeat.interval.ms` | `10000` | 每10秒发送心跳包 |
| `max.poll.records` | `100` | 每次拉取最多100条消息 |
| `enable.auto.commit` | `false` | 禁用自动提交偏移量 |
| `autoCreateTopics` | `false` | 不自动创建主题 |

#### 消费者行为

```
消费者启动
  ↓
加入消费组 (di-diana-group-staging/liveish/live)
  ↓
订阅主题 (chat_message_tab, feedback_tab)
  ↓
从消费组偏移量开始消费
  ↓
每 poll() 调用拉取最多 100 条消息
  ↓
处理消息 (@Transactional)
  ↓
手动提交偏移量 (enable.auto.commit = false)
  ↓
继续 poll()
```

### 2. **Spring Cloud Stream 重试机制**

#### 重试配置

```yaml
consumer:
  concurrency: 1                    # 单个消费者的并发度
  max-attempts: 3                   # 最多重试3次
  back-off-initial-interval: 1000   # 初始退避间隔 1秒
  back-off-multiplier: 2.0          # 每次退避时间翻倍
```

#### 重试流程

```
消息处理失败（异常抛出）
  ↓
第1次重试：等待 1秒 后重试
  ↓ (如果还是失败)
第2次重试：等待 2秒 后重试
  ↓ (如果还是失败)
第3次重试：等待 4秒 后重试
  ↓ (如果还是失败)
放弃，消息进入死信队列或被丢弃（取决于配置）
```

#### 指数退避算法

```
retry_interval = back_off_initial_interval * (back_off_multiplier ^ (attempt - 1))

例如:
- 第1次: 1000ms = 1000 * (2.0 ^ 0)
- 第2次: 2000ms = 1000 * (2.0 ^ 1)
- 第3次: 4000ms = 1000 * (2.0 ^ 2)
```

### 3. **异常处理机制**

#### 异常捕获和重试

```java
@Bean
public Consumer<Message<ChatMessageEvent>> chatMessageEventHandler() {
    return message -> {
        try {
            ChatMessageEvent event = message.getPayload();
            processMessage(event);
        } catch (Exception e) {
            log.error("Error processing chat message event", e);
            throw e;  // ← 重新抛出异常
        }
    };
}
```

#### 异常处理流程

```
处理消息
  ↓
异常发生
  ↓ (记录日志)
catch 块捕获
  ↓ (re-throw)
Spring Cloud Stream 捕获异常
  ↓
判断重试次数
  ├─ 未超过 max-attempts
  │  └─ 等待 back-off 时间后重试
  └─ 已超过 max-attempts
     └─ 消息进入死信队列或被记录
```

### 4. **消费幂等性**

#### 重复性检查

```java
// ChatMessageEventConsumer.java 中的去重逻辑
Optional<FeedbackCycleDao> existingRecord = 
    feedbackCycleManager.findByResponseId(event.getId());
if (existingRecord.isPresent()) {
    log.info("Chat message already processed, skipping duplicate");
    return;
}
```

#### 幂等性设计
- **目标**: 同一条消息被处理多次也只产生一次结果
- **实现**: 检查 `responseId` 是否已存在
- **优势**: 消费者宕机重启时，不会产生重复数据

### 5. **消费组管理**

#### 消费组配置

```yaml
bindings:
  chatMessageEventHandler-in-0:
    destination: di.shopee_di_rag_staging_db__chat_message_tab__live
    group: di-diana-group-staging    # ← 消费组名称
  feedbackEventHandler-in-0:
    destination: di.shopee_di_rag_staging_db__feedback_tab__live
    group: di-diana-group-staging
```

#### 消费组作用
- **消费进度追踪**: 每个消费组独立维护消费偏移量
- **故障恢复**: 宕机重启时从上次偏移量继续消费
- **负载均衡**: 多个消费者可共享一个消费组

### 6. **事务一致性**

#### 事务保证

```java
@Transactional  // ← 事务开始
public void processMessage(ChatMessageEvent event) {
    // 1. 查询操作
    Optional<FeedbackCycleDao> existingRecord = 
        feedbackCycleManager.findByResponseId(event.getId());
    
    // 2. 构建对象
    FeedbackCycleDao feedbackCycle = FeedbackCycleDao.builder()...build();
    
    // 3. 保存操作
    feedbackCycleManager.upsertFeedback(feedbackCycle);
    
    // 4. 批量操作
    topicMessageRelationManager.batchCreateRelations(relations);
    
    // 异常时全部回滚，成功时全部提交 ← 事务结束
}
```

#### 事务级别
- **隔离级别**: 默认为 READ_COMMITTED（读提交）
- **事务超时**: 根据应用配置
- **回滚策略**: 异常时自动回滚

### 7. **监控和日志**

#### 关键日志点

```java
// 消息接收
log.info("Received chat message update event: id={}, sessionId={}, messageType={}",
    event.getId(), event.getSessionId(), event.getMessageType());

// 数据校验
log.warn("Event type is null, skipping");
log.error("Invalid chat message event, missing required fields: {}", event);

// 数据处理
log.info("Successfully upserted feedback cycle for response: {}, question: {}",
    event.getId(), requestId);

// 异常处理
log.error("Error processing chat message event", e);
```

#### 日志级别
- **INFO**: 正常处理流程
- **WARN**: 数据不完整、重复等警告
- **ERROR**: 异常错误、需要人工介入

---

## 故障诊断与解决

### 1. **常见问题及解决方案**

#### 问题一：消息消费延迟大

**现象**
- 数据库中的数据很久才出现
- Kafka 日志中消息处理时间长
- 消费者 lag 持续增长

**可能原因**

| 原因 | 症状 | 解决方案 |
|------|------|---------|
| 消息处理慢 | 单条消息处理时间 > 1秒 | 1. 检查数据库查询是否有 N+1 问题<br>2. 优化 JSON 解析<br>3. 增加数据库连接池大小 |
| 网络延迟 | Kafka 连接超时 | 1. 检查网络连接<br>2. 增加连接超时时间<br>3. 检查 Kafka broker 状态 |
| 消费者并发度低 | 只有1个消费线程 | 1. 增加 concurrency 配置<br>2. 增加消费者实例数<br>3. 检查 CPU 使用率 |
| 重试机制 | 大量重试日志 | 1. 检查是否频繁异常<br>2. 检查数据库连接<br>3. 检查磁盘空间 |

**诊断步骤**

```bash
# 1. 检查消费者 lag（使用 Kafka 命令行工具）
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --describe

# 输出示例
TOPIC                      PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG
chat_message_tab           0         12345          12500          155
feedback_tab               0         54321          54500          179

# 2. 检查消费者连接
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic chat_message_tab \
  --group di-diana-group-staging \
  --from-beginning \
  --max-messages 10

# 3. 查看应用日志
tail -f logs/diana-knowledge-base.log | grep -i "kafka\|consuming\|error"
```

**解决方案**

```yaml
# 增加消费并发度
spring:
  cloud:
    stream:
      bindings:
        chatMessageEventHandler-in-0:
          consumer:
            concurrency: 3  # 从1增加到3
            
# 增加每次拉取的消息数
          binder:
            configuration:
              max.poll.records: 500  # 从100增加到500
```

---

#### 问题二：消息丢失

**现象**
- Kafka 中有消息，但数据库中没有对应记录
- 消费日志中缺少某些消息的处理记录
- 消费 lag 突然减小

**可能原因**

| 原因 | 症状 | 解决方案 |
|------|------|---------|
| 异常导致消息跳过 | 日志中有 ERROR | 1. 检查异常信息<br>2. 检查输入数据格式<br>3. 增加错误日志详细度 |
| 自动偏移提交 | 消息处理中途宕机 | 1. 检查 enable.auto.commit 配置<br>2. 改用手动提交<br>3. 增加处理超时时间 |
| 消费组被重置 | 消费者重启后从头消费 | 1. 检查消费组配置<br>2. 检查是否有消费者下线<br>3. 查看消费者日志 |
| 主题分区重分配 | 消息分配到新消费者 | 1. 等待重分配完成<br>2. 检查消费者组状态<br>3. 手动重启消费者 |

**诊断步骤**

```bash
# 1. 检查是否有消息处理异常
grep -i "error\|exception" logs/diana-knowledge-base.log | head -20

# 2. 检查消费者组状态
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --describe --members

# 3. 检查消费者是否在消费
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --reset-offsets --to-earliest --dry-run

# 4. 检查 Kafka broker 日志
kafka-log-cleaner-checkpoint logs/
```

**解决方案**

```java
// 增加异常处理详细度
@Bean
public Consumer<Message<ChatMessageEvent>> chatMessageEventHandler() {
    return message -> {
        try {
            ChatMessageEvent event = message.getPayload();
            log.info("Processing event: {}", event);  // 增加日志
            processMessage(event);
            log.info("Event processed successfully: {}", event.getId());
        } catch (JsonProcessingException e) {
            log.error("JSON parsing error for message: {}", message, e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error processing message: {}", message, e);
            throw e;
        }
    };
}

// 配置手动偏移提交
spring:
  cloud:
    stream:
      kafka:
        binder:
          configuration:
            enable.auto.commit: false
            auto.commit.interval.ms: 5000
```

---

#### 问题三：消息重复消费

**现象**
- 数据库中出现重复记录
- 反馈周期表中 response_id 重复
- 日志中看到相同消息被处理多次

**可能原因**

| 原因 | 症状 | 解决方案 |
|------|------|---------|
| 缺少去重逻辑 | 相同 response_id 多条记录 | 1. 添加幂等性检查<br>2. 使用数据库唯一约束<br>3. 检查消费者重试 |
| 消费者宕机重启 | 消费同一批消息多次 | 1. 实现幂等性<br>2. 增加处理超时<br>3. 增加心跳间隔 |
| 消费组配置错误 | 多个消费者订阅同一主题 | 1. 确保消费组名称唯一<br>2. 检查消费者实例数<br>3. 检查主题分区数 |

**诊断步骤**

```bash
# 1. 检查数据库重复数据
SELECT response_id, COUNT(*) as cnt 
FROM feedback_cycle_tab 
GROUP BY response_id 
HAVING cnt > 1 
LIMIT 10;

# 2. 检查消费者重试日志
grep -i "retry\|attempt" logs/diana-knowledge-base.log | head -20

# 3. 检查消费者实例数
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --describe --members
```

**解决方案**

```java
// ChatMessageEventConsumer.java 中已实现的去重逻辑
Optional<FeedbackCycleDao> existingRecord = 
    feedbackCycleManager.findByResponseId(event.getId());
if (existingRecord.isPresent()) {
    log.info("Chat message already processed, skipping duplicate: responseId={}", 
        event.getId());
    return;  // 重复则跳过
}

// 确保数据库有唯一约束
// ALTER TABLE feedback_cycle_tab ADD UNIQUE KEY uk_response_id (response_id, deleted_at);
```

---

#### 问题四：消消费者宕机

**现象**
- 应用无法启动
- "Consumer is not in group" 错误
- Kafka 连接超时

**可能原因**

| 原因 | 症状 | 解决方案 |
|------|------|---------|
| Kafka broker 不可用 | 连接超时 | 1. 检查 Kafka 集群状态<br>2. 检查网络连接<br>3. 检查防火墙规则 |
| 认证失败 | 连接拒绝 | 1. 检查 SASL 凭证<br>2. 检查 KMS 密钥<br>3. 检查用户权限 |
| 内存不足 | OOM 异常 | 1. 增加 JVM 堆内存<br>2. 优化消息处理逻辑<br>3. 减少 max.poll.records |
| 数据库连接 | Connection pool 满 | 1. 增加数据库连接池大小<br>2. 优化 SQL 语句<br>3. 检查是否有长连接 |

**诊断步骤**

```bash
# 1. 检查 Kafka broker 连接
telnet kafka-broker:9093

# 2. 检查应用启动日志
tail -100 logs/diana-knowledge-base.log | grep -i "kafka\|error\|exception"

# 3. 检查 JVM 内存使用
jps -l  # 找到 Java 进程 ID
jstat -gc -h10 <pid> 1000  # 每秒输出一次 GC 统计

# 4. 检查数据库连接
mysql> SHOW PROCESSLIST;
mysql> SELECT * FROM information_schema.PROCESSLIST WHERE COMMAND != 'Sleep';
```

**解决方案**

```yaml
# 增加 JVM 堆内存 (应用启动参数)
JAVA_OPTS="-Xms2g -Xmx4g"

# 增加数据库连接池
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 30000

# 增加 Kafka 会话超时时间
spring:
  cloud:
    stream:
      kafka:
        binder:
          configuration:
            session.timeout.ms: 60000  # 从30秒增加到60秒
            heartbeat.interval.ms: 15000
```

---

### 2. **监控和告警**

#### 关键指标

| 指标 | 正常范围 | 告警阈值 | 检查方法 |
|------|---------|---------|---------|
| Consumer Lag | < 1000 | > 5000 | `kafka-consumer-groups` 命令 |
| 消息处理延迟 | < 1秒 | > 5秒 | 应用日志时间戳 |
| 错误率 | < 0.1% | > 1% | 日志 ERROR 计数 |
| 重试率 | < 5% | > 20% | 日志 "retry" 关键字 |
| 消费者可用性 | 100% | < 95% | 消费者状态检查 |

#### 监控实现

```bash
# 监控消费 lag（每分钟检查一次）
*/1 * * * * kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --describe | awk '{print $5}' | \
  awk '{sum+=$1} END {if (sum > 5000) print "HIGH LAG: " sum}' | \
  mail -s "Kafka Consumer Lag Alert" ops@company.com

# 监控错误率
*/5 * * * * grep "ERROR" logs/diana-knowledge-base.log | \
  wc -l | awk '{if ($1 > 100) print "HIGH ERROR RATE: " $1 "/5min"}' | \
  mail -s "Application Error Alert" ops@company.com
```

#### 告警规则

```
1. Consumer Lag 告警
   - 条件: lag > 5000 条消息
   - 持续时间: > 5 分钟
   - 动作: 发送告警，检查消费者是否宕机

2. 错误率告警
   - 条件: ERROR 日志 > 100/5分钟
   - 持续时间: > 2 次周期
   - 动作: 发送告警，检查异常原因

3. 消费者宕机告警
   - 条件: 消费者无心跳 > 30 秒
   - 动作: 自动重启消费者，发送告警

4. 内存告警
   - 条件: 堆内存使用率 > 90%
   - 动作: 发送告警，检查是否内存泄漏
```

---

### 3. **故障恢复步骤**

#### 恢复流程

```
发现故障
  ↓
① 初步诊断
   - 检查消费者日志
   - 检查 Kafka 集群状态
   - 检查数据库状态

  ↓
② 隔离故障
   - 确定是消费端还是 Kafka
   - 确定是数据库还是应用
   - 确定影响范围

  ↓
③ 紧急措施
   - 如果是消费者宕机: 重启应用
   - 如果是 Kafka 问题: 联系基础设施团队
   - 如果是数据库问题: 检查连接和空间

  ↓
④ 问题解决
   - 根据具体原因采取措施
   - 监控恢复过程
   - 记录变更日志

  ↓
⑤ 故障后处理
   - 重新消费未处理消息
   - 检查是否有数据不一致
   - 优化相关配置
```

#### 常见恢复命令

```bash
# 1. 重启消费者
systemctl restart diana-knowledge-base-portal

# 2. 手动重置消费组偏移（使用时谨慎）
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --reset-offsets --to-latest --topic chat_message_tab \
  --execute

# 3. 检查并修复消费延迟
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --reset-offsets --to-earliest --topic chat_message_tab \
  --dry-run

# 4. 查看消费者成员
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --describe --members

# 5. 删除消费组（高危操作，仅在必要时）
kafka-consumer-groups --bootstrap-server localhost:9092 \
  --group di-diana-group-staging \
  --delete
```

---

## 架构图

### 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                  Debezium CDC 数据源                         │
│  ┌─────────────────┐        ┌────────────────┐              │
│  │  MySQL 数据库    │        │   Kafka Broker  │             │
│  │ chat_message_tab│───────→│   feedback-kafka │            │
│  │ feedback_tab    │        │  bootstrap.urls │            │
│  └─────────────────┘        └────────────────┘              │
└─────────────────────────────────────────────────────────────┘
                                    ↓
        ┌───────────────────────────────────────────┐
        │   Spring Cloud Stream - Message Binding   │
        │                                           │
        │  ┌─────────────────────────────────────┐  │
        │  │  Kafka Binder (spring-cloud-stream) │  │
        │  │  - SASL 认证                        │  │
        │  │  - 消费者组管理                     │  │
        │  │  - 重试机制                        │  │
        │  │  - 偏移量管理                      │  │
        │  └─────────────────────────────────────┘  │
        └───────────────────────────────────────────┘
              ↓                              ↓
    ┌──────────────────┐        ┌──────────────────┐
    │ chatMessageEvent │        │ feedbackEventH   │
    │    Handler       │        │    andler        │
    │  (Function Bean) │        │  (Function Bean) │
    └──────────────────┘        └──────────────────┘
            ↓                            ↓
    ┌──────────────────┐        ┌──────────────────┐
    │ChatMessageEvent  │        │ FeedbackEvent    │
    │  Consumer        │        │   Consumer       │
    │ @Transactional   │        │ @Transactional   │
    └──────────────────┘        └──────────────────┘
            ↓                            ↓
    ┌──────────────────────────────────────────────┐
    │        业务逻辑处理                          │
    │  ┌──────────────────────────────────────────┐│
    │  │  - 数据校验                             ││
    │  │  - JSON 解析                           ││
    │  │  - 数据转换                            ││
    │  │  - 业务逻辑                            ││
    │  └──────────────────────────────────────────┘│
    └──────────────────────────────────────────────┘
            ↓
    ┌──────────────────────────────────────────────┐
    │        数据持久化层                          │
    │  ┌──────────────────┐    ┌────────────────┐ │
    │  │ FeedbackCycleDao │    │TopicMessageRel │ │
    │  │   Manager        │    │   Manager      │ │
    │  └──────────────────┘    └────────────────┘ │
    └──────────────────────────────────────────────┘
            ↓
    ┌──────────────────────────────────────────────┐
    │           MySQL 数据库                       │
    │  ┌───────────────────────────────────────┐  │
    │  │  feedback_cycle_tab                   │  │
    │  │  topic_message_relation_tab           │  │
    │  │  tracking_info_tab                    │  │
    │  └───────────────────────────────────────┘  │
    └──────────────────────────────────────────────┘
```

### 消息处理流程

```
消息进入 Kafka
    ↓
┌─────────────────────────────────────────────┐
│ Spring Cloud Stream 接收消息                │
│ - 从 Kafka Broker 拉取 (max 100 条)        │
│ - JSON 反序列化                            │
│ - 创建 Message<T> 对象                     │
└─────────────────────────────────────────────┘
    ↓
    ├─→ chatMessageEventHandler-in-0  ─→ ChatMessageEventConsumer
    │                                        ↓
    │                        ┌──────────────────────────────┐
    │                        │ 处理 Chat Message 事件       │
    │                        │ 1. 校验事件类型              │
    │                        │ 2. 过滤 Response 消息       │
    │                        │ 3. 解析 JSON 内容           │
    │                        │ 4. UPSERT FeedbackCycle    │
    │                        │ 5. 创建 Topic-Message 关系  │
    │                        │ 6. 事务提交                 │
    │                        └──────────────────────────────┘
    │
    └─→ feedbackEventHandler-in-0  ─→ FeedbackEventConsumer
                                        ↓
                        ┌──────────────────────────────┐
                        │ 处理 Feedback 事件           │
                        │ 1. 校验事件类型              │
                        │ 2. 转换反馈类型              │
                        │ 3. 查询旧记录                │
                        │ 4. 软删除旧记录              │
                        │ 5. 创建新反馈记录            │
                        │ 6. 事务提交                 │
                        └──────────────────────────────┘
    ↓
异常 → 异常捕获 ─→ 记录日志 ─→ 重新抛出异常
  ↓
成功 → 提交偏移量 ─→ 继续消费下一条消息
```

---

## 总结

| 方面 | 关键点 |
|------|--------|
| **消息来源** | Debezium CDC 从 MySQL 表变更事件 |
| **消费方式** | Spring Cloud Stream + Kafka Binder，函数式编程 |
| **消费者模式** | 消费组（consumer group），自动分区分配 |
| **事务保证** | @Transactional 确保 ACID，异常时回滚 |
| **重试机制** | 指数退避算法，最多3次重试 |
| **幂等性** | 通过检查数据库唯一键实现 |
| **监控告警** | 消费 lag、错误率、消费者可用性 |
| **故障恢复** | 消费者自动重试、手动重启、重置偏移量 |
| **并发处理** | 单线程消费，UPSERT 操作处理并发 |
| **数据安全** | 手动偏移提交，软删除，唯一约束 |
