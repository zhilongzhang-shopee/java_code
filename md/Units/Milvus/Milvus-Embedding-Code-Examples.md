# Milvus Embedding 向量生成 - 代码示例详解

## 📌 核心代码示例

### 1. CompassEmbeddingManager - 核心转换器

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CompassEmbeddingManager {

  private final CompassApiClient compassApiClient;
  private final CompassApiProperties compassApiProperties;

  /**
   * 将单个文本转换为向量
   * 
   * 输入: "术语名称: 用户\n同义词: 客户,消费者\n描述: 使用产品或服务的个人"
   * 处理:
   *   1. 验证输入不为空
   *   2. 构建 CompassEmbeddingRequest
   *   3. 调用 Compass API
   *   4. 提取第一个向量结果
   * 输出: [0.123, -0.456, ..., 0.789] (384维)
   */
  public List<Double> textToVector(String text) {
    if (!StringUtils.hasText(text)) {
      throw new IllegalArgumentException("Input text cannot be null or empty");
    }

    List<String> inputs = List.of(text);
    CompassEmbeddingResponse response = callCompassApi(inputs);

    if (response.getData() == null || response.getData().isEmpty()) {
      throw new DataRetrievalFailureException("No embedding data returned from Compass API");
    }

    // 返回第一个（也是唯一一个）向量
    return response.getData().get(0).getEmbedding();
  }

  /**
   * 调用 Compass API 生成 embedding
   * 
   * 请求体示例:
   * {
   *   "input": ["术语名称: 用户\n同义词: 客户,消费者\n描述: ..."],
   *   "model": "compass-embedding-v3",
   *   "dimensions": 384
   * }
   * 
   * 响应体示例:
   * {
   *   "data": [
   *     {
   *       "embedding": [0.123, -0.456, ..., 0.789],
   *       "index": 0,
   *       "object": "embedding"
   *     }
   *   ],
   *   "usage": {
   *     "prompt_tokens": 12,
   *     "total_tokens": 12
   *   }
   * }
   */
  private CompassEmbeddingResponse callCompassApi(List<String> texts) {
    try {
      CompassEmbeddingRequest request = CompassEmbeddingRequest.builder()
          .input(texts)
          .model(compassApiProperties.getDefaultModel())           // "compass-embedding-v3"
          .dimensions(compassApiProperties.getDefaultDimensions()) // 384
          .build();

      log.info("Generating embeddings for {} texts using model: {}",
          texts.size(), compassApiProperties.getDefaultModel());

      CompassEmbeddingResponse response = compassApiClient.generateEmbeddings(request);

      log.info("Successfully generated embeddings for {} texts", texts.size());
      return response;

    } catch (Exception e) {
      log.error("Failed to generate embeddings: {}", e.getMessage(), e);
      throw new DataRetrievalFailureException("Failed to generate embeddings", e);
    }
  }
}
```

---

### 2. MilvusGlossaryService - 术语同步与搜索

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusGlossaryService {

  private final CompassEmbeddingManager compassEmbeddingManager;
  private final MilvusGlossaryManager milvusGlossaryManager;

  /**
   * 同步术语到 Milvus（数据同步流程）
   * 
   * 输入参数:
   *   topicId: 1001
   *   glossary: BusinessGlossaryDao {
   *     id: 101,
   *     glossaryName: "用户",
   *     synonym: "客户,消费者",
   *     desc: "使用产品或服务的个人或组织"
   *   }
   * 
   * 流程:
   *   1. buildGlossaryTextContent(glossary) → 文本
   *   2. compassEmbeddingManager.textToVector(文本) → 向量
   *   3. Double → Float 类型转换
   *   4. milvusGlossaryManager.insertGlossaryToMilvus() → 存储
   * 
   * Milvus 存储内容:
   *   {
   *     "glossary_id": 101,
   *     "topic_id": 1001,
   *     "glossary_name": "用户",
   *     "synonym": "客户,消费者",
   *     "description": "使用产品或服务...",
   *     "embedding": [0.123, -0.456, ..., 0.789]  (384维)
   *   }
   */
  public void syncGlossaryToMilvus(Long topicId, BusinessGlossaryDao glossary) {
    log.info("Syncing glossary {} to Milvus for topic {}", glossary.getId(), topicId);

    try {
      // 第一步: 构建用于生成向量的文本内容
      String textContent = buildGlossaryTextContent(glossary);
      log.debug("Glossary text content for embedding: {}", textContent);

      // 第二步: 生成向量 (调用 Compass API)
      List<Double> embeddingDoubles = compassEmbeddingManager.textToVector(textContent);
      
      // 第三步: 类型转换 (Double → Float)
      List<Float> embedding = embeddingDoubles.stream()
          .map(Double::floatValue)
          .toList();

      // 第四步: 插入数据到 Milvus（包含 topic_id）
      milvusGlossaryManager.insertGlossaryToMilvus(topicId, glossary, embedding);

      log.info("Successfully synced glossary {} to Milvus for topic {}",
          glossary.getId(), topicId);
    } catch (Exception e) {
      log.error("Failed to sync glossary {} to Milvus: {}", glossary.getId(), e.getMessage(), e);
      throw new DataAccessResourceFailureException(
          "Failed to sync glossary to Milvus: " + e.getMessage(), e);
    }
  }

  /**
   * 基于用户查询文本进行向量搜索（查询流程）
   * 
   * 输入:
   *   userQuery: "用户数据是什么"
   *   topK: 5
   *   expr: null (可选的过滤条件，如 "topic_id == 1001")
   * 
   * 流程:
   *   1. compassEmbeddingManager.textToVector(userQuery) → 查询向量
   *   2. Double → Float 类型转换
   *   3. milvusGlossaryManager.vectorSearchFromMilvus() → 相似度搜索
   * 
   * 输出:
   *   List<MilvusGlossaryDao> [
   *     MilvusGlossaryDao {
   *       id: 101,
   *       glossaryName: "用户",
   *       similarity_score: 0.95  // 相似度分数
   *     },
   *     MilvusGlossaryDao {
   *       id: 102,
   *       glossaryName: "客户",
   *       similarity_score: 0.87
   *     }
   *   ]
   */
  public List<MilvusGlossaryDao> searchGlossariesByQuery(String userQuery, int topK, String expr) {
    log.info("Searching glossaries by query text: {}, topK: {}, expr: {}", userQuery, topK, expr);

    try {
      // 第一步: 将用户查询文本转换为向量
      List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
      
      // 第二步: 类型转换 (Double → Float)
      List<Float> queryVector = queryVectorDoubles.stream()
          .map(Double::floatValue)
          .toList();

      log.debug("Query vector generated, dimension: {}", queryVector.size());

      // 第三步: 调用向量搜索
      return milvusGlossaryManager.vectorSearchFromMilvus(queryVector, topK, expr);
    } catch (Exception e) {
      log.error("Failed to search glossaries by query '{}': {}", userQuery, e.getMessage(), e);
      throw new DataAccessResourceFailureException(
          "Failed to search glossaries by query: " + e.getMessage(), e);
    }
  }

  /**
   * 构建术语的文本内容用于生成向量
   * 
   * 输入 BusinessGlossaryDao:
   *   {
   *     "glossaryName": "用户",
   *     "synonym": "客户,消费者",
   *     "desc": "使用产品或服务的个人或组织"
   *   }
   * 
   * 输出文本:
   *   "术语名称: 用户
   *    同义词: 客户,消费者
   *    描述: 使用产品或服务的个人或组织"
   * 
   * 用途:
   *   这个文本会被发送到 Compass API 进行向量化处理
   */
  private String buildGlossaryTextContent(BusinessGlossaryDao glossary) {
    StringBuilder sb = new StringBuilder();
    sb.append("术语名称: ").append(glossary.getGlossaryName()).append("\n");
    sb.append("同义词: ").append(glossary.getSynonym()).append("\n");
    sb.append("描述: ").append(glossary.getDesc());
    return sb.toString();
  }
}
```

---

### 3. MilvusRulesService - 规则同步与搜索

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusRulesService {

  private final CompassEmbeddingManager compassEmbeddingManager;
  private final MilvusRulesManager milvusRulesManager;

  /**
   * 同步规则到 Milvus collection（指定话题）
   * 
   * 输入参数:
   *   topicId: 1001
   *   rule: BusinessRulesDao {
   *     id: 201,
   *     ruleDesc: "用户订单金额超过100元时，可获得10%的折扣"
   *   }
   * 
   * 流程:
   *   1. buildRuleTextContent(rule) → 规则描述文本
   *   2. compassEmbeddingManager.textToVector() → 转换为向量
   *   3. 类型转换 (Double → Float)
   *   4. milvusRulesManager.insertRuleToMilvus() → 存储
   * 
   * 特点:
   *   - 规则只使用 ruleDesc，不包含状态信息
   *   - 相比术语更简洁，避免可变字段影响向量
   */
  public void syncRuleToMilvus(Long topicId, BusinessRulesDao rule) {
    log.info("Syncing rule {} to Milvus for topic {}", rule.getId(), topicId);

    try {
      // 构建用于生成向量的文本内容
      String textContent = buildRuleTextContent(rule);
      log.debug("Rule text content for embedding: {}", textContent);

      // 生成向量
      List<Double> embeddingDoubles = compassEmbeddingManager.textToVector(textContent);
      List<Float> embedding = embeddingDoubles.stream()
          .map(Double::floatValue)
          .toList();

      // 插入数据到 Milvus（包含 topic_id）
      milvusRulesManager.insertRuleToMilvus(topicId, rule, embedding);

      log.info("Successfully synced rule {} to Milvus for topic {}",
          rule.getId(), topicId);
    } catch (Exception e) {
      log.error("Failed to sync rule {} to Milvus: {}", rule.getId(), e.getMessage(), e);
      throw new DataAccessResourceFailureException(
          "Failed to sync rule to Milvus: " + e.getMessage(), e);
    }
  }

  /**
   * 基于用户查询文本进行向量搜索
   * 
   * 查询流程与术语搜索类似，但返回规则相关数据
   */
  public List<MilvusRulesDao> searchRulesByQuery(String userQuery, int topK, String expr) {
    log.info("Searching rules by query text: {}, topK: {}, expr: {}", userQuery, topK, expr);

    try {
      // 将用户查询文本转换为向量
      List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
      List<Float> queryVector = queryVectorDoubles.stream()
          .map(Double::floatValue)
          .toList();

      log.debug("Query vector generated, dimension: {}", queryVector.size());

      // 调用向量搜索
      return milvusRulesManager.vectorSearchFromMilvus(queryVector, topK, expr);
    } catch (Exception e) {
      log.error("Failed to search rules by query '{}': {}", userQuery, e.getMessage(), e);
      throw new DataAccessResourceFailureException(
          "Failed to search rules by query: " + e.getMessage(), e);
    }
  }

  /**
   * 构建规则的文本内容用于生成向量
   * 
   * 特点: 只使用规则描述，不包含可变的状态标志
   * 
   * 输入 BusinessRulesDao:
   *   {
   *     "ruleDesc": "用户订单金额超过100元时，可获得10%的折扣"
   *   }
   * 
   * 输出:
   *   "用户订单金额超过100元时，可获得10%的折扣"
   */
  private String buildRuleTextContent(BusinessRulesDao rule) {
    return rule.getRuleDesc();
  }
}
```

---

### 4. MilvusTableService - 表搜索

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusTableService {

  private final MilvusTableManager milvusTableManager;
  private final CompassEmbeddingManager compassEmbeddingManager;

  /**
   * 文本向量搜索（组合服务）
   * 将文本转换为向量后执行向量搜索
   * 
   * 输入:
   *   queryText: "用户订单信息"
   *   topK: 10
   *   expr: null (可选的过滤条件)
   * 
   * 流程:
   *   1. 将查询文本转换为向量
   *   2. 类型转换 (Double → Float)
   *   3. 执行向量搜索
   * 
   * 输出:
   *   List<MilvusTableManifestDto> [
   *     {
   *       tableName: "user_orders_tab",
   *       description: "用户订单表，记录所有用户订单信息",
   *       ...
   *     }
   *   ]
   */
  public List<MilvusTableManifestDto> textVectorSearch(String queryText, int topK, String expr) {
    log.info("Performing text vector search with query: {}, topK: {}, expr: {}", 
        queryText, topK, expr);

    // 1. 将查询文本转换为向量
    log.debug("Converting query text to vector...");
    List<Double> queryVectorDouble = compassEmbeddingManager.textToVector(queryText);
    
    // 2. 转换为 Float 类型（Milvus 需要 Float 类型）
    List<Float> queryVector = queryVectorDouble.stream()
        .map(Double::floatValue)
        .toList();
    
    log.debug("Successfully converted text to {}-dimensional vector", queryVector.size());

    // 3. 执行向量搜索
    return vectorSearch(queryVector, topK, expr);
  }

  /**
   * 向量相似性搜索
   * 
   * 输入:
   *   vector: [0.123, -0.456, ..., 0.789]  (384维)
   *   topK: 10
   *   expr: "market_region == 'SG'"
   * 
   * 处理:
   *   1. 调用 milvusTableManager 执行搜索
   *   2. 转换为 DTO
   *   3. 返回结果
   */
  public List<MilvusTableManifestDto> vectorSearch(List<Float> vector, int topK, String expr) {
    log.info("Performing vector search with topK: {}, expr: {}", topK, expr);

    List<MilvusTableManifestDao> entities = milvusTableManager.vectorSearchFromMilvus(vector, topK, expr);

    return convertToDto(entities);
  }

  /**
   * 根据条件查询表信息
   * 
   * 使用 Milvus 的条件查询功能而非向量搜索
   */
  public List<MilvusTableManifestDto> queryByCondition(String expr, Integer limit) {
    log.info("Querying tables with expr: {}, limit: {}", expr, limit);

    List<MilvusTableManifestDao> entities = milvusTableManager.queryFromMilvusByCondition(expr, limit);

    return convertToDto(entities);
  }

  /**
   * 转换Entity列表到DTO列表
   */
  private List<MilvusTableManifestDto> convertToDto(List<MilvusTableManifestDao> entities) {
    return entities.stream()
        .map(this::convertToDto)
        .toList();
  }

  /**
   * 转换单个Entity到DTO
   * 
   * 注意: tableVector 通常不对外暴露
   */
  private MilvusTableManifestDto convertToDto(MilvusTableManifestDao entity) {
    MilvusTableManifestDto dto = new MilvusTableManifestDto();
    dto.setUid(entity.getUid());
    dto.setSchema(entity.getSchema());
    dto.setTableGroupName(entity.getTableGroupName());
    dto.setTableName(entity.getTableName());
    dto.setMarketRegion(entity.getMarketRegion());
    dto.setBusinessDomain(entity.getBusinessDomain());
    dto.setDataMarts(entity.getDataMarts());
    dto.setDataTopics(entity.getDataTopics());
    dto.setDescription(entity.getDescription());
    dto.setUpdateFrequency(entity.getUpdateFrequency());
    dto.setBusinessPic(entity.getBusinessPic());
    dto.setTechnicalPic(entity.getTechnicalPic());
    dto.setDwLayer(entity.getDwLayer());
    dto.setLast7DayQueryCount(entity.getLast7DayQueryCount());
    dto.setLast30DayQueryCount(entity.getLast30DayQueryCount());
    dto.setUpstreamTableFullName(entity.getUpstreamTableFullName());
    dto.setIdcRegion(entity.getIdcRegion());
    dto.setRegion(entity.getRegion());
    // 注意: tableVector一般不对外暴露
    // dto.setTableVector(entity.getTableVector());
    return dto;
  }
}
```

---

## 📊 数据转换示例

### 术语转换示例

```
输入数据 (BusinessGlossaryDao):
┌──────────────────────────────┐
│ glossaryName: "用户"         │
│ synonym: "客户,消费者"       │
│ desc: "使用产品或服务的个人" │
└──────────────────────────────┘
         ↓ buildGlossaryTextContent()
┌──────────────────────────────────────────────────┐
│ "术语名称: 用户                                  │
│  同义词: 客户,消费者                             │
│  描述: 使用产品或服务的个人"                     │
└──────────────────────────────────────────────────┘
         ↓ compassEmbeddingManager.textToVector()
┌──────────────────────────────────────────────────┐
│ List<Double> [384个浮点数]                       │
│ [-0.0234, 0.1456, -0.0892, ..., 0.2341]        │
└──────────────────────────────────────────────────┘
         ↓ Double → Float 转换
┌──────────────────────────────────────────────────┐
│ List<Float> [384维]                              │
│ [-0.0234f, 0.1456f, -0.0892f, ..., 0.2341f]    │
└──────────────────────────────────────────────────┘
         ↓ milvusGlossaryManager.insertGlossaryToMilvus()
┌──────────────────────────────────────────────────┐
│ Milvus Collection: glossary_collection           │
│ {                                                │
│   "glossary_id": 101,                           │
│   "topic_id": 1001,                             │
│   "glossary_name": "用户",                       │
│   "synonym": "客户,消费者",                      │
│   "desc": "使用产品或服务的个人",               │
│   "embedding": [-0.0234, 0.1456, ...]          │
│ }                                                │
└──────────────────────────────────────────────────┘
```

### 查询搜索示例

```
用户输入:
┌──────────────────────────────┐
│ "用户是什么"                 │
└──────────────────────────────┘
         ↓ compassEmbeddingManager.textToVector()
┌──────────────────────────────────────────────────┐
│ List<Float> [384维]                              │
│ [-0.0198, 0.1523, -0.0821, ..., 0.2412]        │
└──────────────────────────────────────────────────┘
         ↓ milvusGlossaryManager.vectorSearchFromMilvus(topK=5)
┌──────────────────────────────────────────────────┐
│ Milvus 余弦相似度搜索结果:                       │
│ [                                                │
│   {glossary_id: 101, similarity: 0.95},         │
│   {glossary_id: 102, similarity: 0.87},         │
│   {glossary_id: 103, similarity: 0.82},         │
│   {glossary_id: 104, similarity: 0.78},         │
│   {glossary_id: 105, similarity: 0.75}          │
│ ]                                                │
└──────────────────────────────────────────────────┘
         ↓ 转换为 DTO 返回给用户
┌──────────────────────────────────────────────────┐
│ List<MilvusGlossaryDao>                          │
│ [                                                │
│   {                                              │
│     "glossaryId": 101,                           │
│     "glossaryName": "用户",                      │
│     "synonym": "客户,消费者",                    │
│     "similarity": 0.95                           │
│   },                                             │
│   ...                                            │
│ ]                                                │
└──────────────────────────────────────────────────┘
```

---

## 🔧 配置和初始化

### CompassApiProperties 配置

```java
@Data
@Component
@ConfigurationProperties(prefix = "compass.api")
public class CompassApiProperties {

    /**
     * Compass API base URL
     */
    private String baseUrl;

    /**
     * API Key for authentication
     */
    private String apiKey;

    /**
     * 模型名称: compass-embedding-v3
     */
    private String defaultModel;

    /**
     * 向量维度: 384
     */
    private Integer defaultDimensions;

    public String getAuthorizationHeader() {
        return "Bearer " + apiKey;
    }
}
```

### YAML 配置示例

```yaml
compass:
  api:
    base-url: https://compass-api.company.com
    api-key: sk-xxx-yyy-zzz
    default-model: compass-embedding-v3
    default-dimensions: 384

milvus:
  host: milvus.company.com
  port: 19530
  database: knowledge_base
  collection:
    glossary: glossary_collection
    rules: rules_collection
    table: table_manifest_collection
```

