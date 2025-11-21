# Milvus 向量数据库 完整指南

> **文档说明**: 本文档整合了 Milvus 向量数据库在 DI Brain 项目和 Diana Knowledge Base 中的使用、配置、架构设计和最佳实践。

---

## 文档目录

1. [Milvus 概述](#milvus-概述)
2. [项目中的使用场景](#项目中的使用场景)
3. [架构设计](#架构设计)
4. [配置详解](#配置详解)
5. [核心代码实现](#核心代码实现)
6. [数据流向](#数据流向)
7. [集合（Collection）详解](#集合collection详解)
8. [性能优化指南](#性能优化指南)
9. [故障排查](#故障排查)
10. [部署检查清单](#部署检查清单)
11. [最佳实践](#最佳实践)
12. [快速开始](#快速开始)

---

## Milvus 概述

### 什么是 Milvus？

**Milvus** 是一个开源的向量数据库，专门为处理**向量相似性搜索**和**AI 应用**而设计。

### 在本项目中的定位

- **用途**: 为知识库表信息存储向量，提供基于自然语言的**智能表格搜索**功能
- **集合名**: `di_rag_hive_table_manifest_v1`
- **向量维度**: 384 维 (Compass-v3) 或 896 维 (Diana Knowledge Base)
- **搜索算法**: L2 距离（欧几里得距离）

### 为什么选择 Milvus？

1. **高性能**: 支持百万级向量秒级搜索
2. **灵活查询**: 支持向量搜索 + 标量字段过滤（混合搜索）
3. **易于集成**: 提供多语言 SDK，Java 集成方便
4. **生产级稳定性**: 支持 HA、备份、监控等企业级功能

---

## 项目中的使用场景

### DI Brain 项目场景

#### 场景1: Data Discovery 中的表查询

用户输入自然语言问题，系统通过 Milvus 找到相关表

```python
from di_brain.milvus.milvus_search import search_similar_tables

# 查询相似表
results = search_similar_tables(
    query_text="订单支付关系表",
    top_k=10
)

# 输出结果
for result in results:
    print(f"{result['uid']}: {result['score']:.4f}")
```

#### 场景2: RAG 检索

为 LLM 提供表元数据上下文信息

```python
# 在 SPX Mart 中查询
results = search_similar_tables(
    query_text="订单",
    top_k=10,
    filter_expr="data_marts == 'SPX Mart'"
)
```

#### 场景3: 多条件精准检索

支持业务域、数据集市等维度的过滤

### Diana Knowledge Base 项目场景

#### 场景 1: 基于自然语言的表格智能搜索

**业务需求**: 用户输入自然语言查询（如："计算用户购买次数的表"），系统需要理解语义，找到最相关的 Hive 表

**API 端点**:
```
POST /open/v1/tables/vector-search
Content-Type: application/json

{
  "query": "用户购买历史记录",
  "topK": 10
}
```

**响应示例**:
```json
{
  "query": "用户购买历史记录",
  "count": 10,
  "results": [
    {
      "uid": "user_purchase_history_...",
      "tableName": "user_purchase_history",
      "schema": "dwh",
      "description": "User purchase transaction history...",
      "last7DayQueryCount": 12345
    }
  ],
  "executionTime": 245
}
```

#### 场景 2: 条件查询表信息

根据特定条件（如某个市场区域、业务域）查询相关的表

```
market_region == "SG" && business_domain == "Commerce"
market_region in ["SG", "ID", "MY"]
dw_layer == "ods"
```

#### 场景 3: 术语（Glossary）的语义搜索

**用途**: 存储和搜索业务术语定义，支持基于术语名称、同义词和描述的向量搜索

**集合**: `glossary_collection`

**工作流**:
- 当新增术语时，自动生成向量并存入 Milvus
- 用户查询"客户" → 系统搜索相关术语（客户、用户、消费者等）
- 基于向量相似度返回相关术语及其完整定义

**代码示例**:
```java
// 术语向量构建
String textContent = "术语名称: 客户\n同义词: 用户,消费者\n描述: 使用产品的个人或组织";
List<Double> embedding = compassEmbeddingManager.textToVector(textContent);

// 存入 Milvus
milvusGlossaryManager.insertGlossaryToMilvus(topicId, glossary, embedding);

// 搜索相关术语
List<MilvusGlossaryDao> results = milvusGlossaryService.searchGlossariesByQuery(
    "什么是客户",  // 用户查询
    5,            // topK
    "topic_id == 1001"  // 按话题过滤
);
```

#### 场景 4: 规则（Rules）的智能检索

**用途**: 存储业务规则（如数据治理规则、计算规则等），支持自然语言查询

**集合**: `rules_collection`

**工作流**:
- 规则通过向量化后存入 Milvus
- 用户查询"订单折扣" → 系统返回相关的折扣规则
- 支持按话题筛选规则

**代码示例**:
```java
// 规则向量构建
String ruleDesc = "订单金额超过100元可获得10%折扣";
List<Double> embedding = compassEmbeddingManager.textToVector(ruleDesc);

// 存入 Milvus
milvusRulesManager.insertRuleToMilvus(topicId, rule, embedding);

// 搜索相关规则
List<MilvusRulesDao> results = milvusRulesService.searchRulesByQuery(
    "订单优惠政策",
    10,
    "topic_id == 1001"
);
```

#### 场景 5: 多知识源的混合搜索

**用途**: 在同一个 Topic（话题）内，搜索术语、规则、表等多种知识源

**工作流**:
```
用户查询 "用户订单"
    ↓
1. 文本向量化 → 384维向量
    ↓
2. 并行搜索：
   - 搜索相关表（表结构、描述）
   - 搜索相关术语（用户、订单等）
   - 搜索相关规则（订单处理规则）
    ↓
3. 合并结果 → 返回综合知识
```

**应用场景**:
- 知识库查询：用户一次搜索获得相关的所有知识
- 辅助分析：在分析前快速获取相关背景知识
- RAG 增强：为 LLM 提供多维度的上下文信息

#### 场景 6: 话题级的知识范围限制

**用途**: 确保搜索结果受到话题（Topic）的限制，实现权限和范围隔离

**工作流**:
```
话题初始化
├─ Topic 1: "订单业务"
│  ├─ 术语: 订单、用户、支付
│  ├─ 规则: 订单折扣规则、支付流程规则
│  └─ 表: order_fact, user_dim, payment_log
│
├─ Topic 2: "库存管理"
│  ├─ 术语: 库存、SKU、仓库
│  ├─ 规则: 库存预警规则
│  └─ 表: inventory_fact, sku_dim

搜索时按 topic_id 过滤 → 确保结果只来自当前话题
```

**代码示例**:
```java
// 在话题范围内搜索
Long topicId = 1001;
List<MilvusGlossaryDao> glossaries = 
    milvusGlossaryService.searchGlossariesByQuery(
        userQuery,
        10,
        String.format("topic_id == %d", topicId)  // 关键：话题过滤
    );
```

#### 场景 7: 话题内知识关联追踪

**用途**: 记录用户在 RAG/Feedback 过程中使用的知识，便于后续分析和改进

**存储表**:
- `topic_knowledge_relation`: Topic ↔ Knowledge 的关联
- `feedback_knowledge_relation_tab`: 反馈 ↔ Knowledge 的关联
- `feedback_test_relation_tab`: 测试 ↔ Knowledge 的关联

**工作流**:
```
1. 用户选择 Glossary + Rules + Tables 进行测试
   ↓
2. 系统记录关联关系到 feedback_test_relation_tab
   ↓
3. 执行 RAG 生成和反馈收集
   ↓
4. 分析：这组知识组合对结果质量的影响
   ↓
5. 优化：调整话题中的知识范围
```

---

## 架构设计

### 分层架构

```
┌─────────────────────────────────────────┐
│        REST API Layer                   │
│   (Controller / REST Endpoint)          │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│   Business Service Layer                │
│   • VectorSearchService                 │
│   • MilvusTableService                  │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│   Manager/Domain Logic Layer            │
│   • MilvusTableManager                  │
│   • Business Rules                      │
│   • Data Transformation                 │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│   Repository/DAO Layer                  │
│   • MilvusTableRepository               │
│   • Low-level Operations                │
│   • SDK Integration                     │
└──────────────┬──────────────────────────┘
               │
┌──────────────▼──────────────────────────┐
│   Infrastructure Layer                  │
│   • MilvusServiceClient (Milvus SDK)    │
│   • MilvusConfiguration (Spring 配置)   │
│   • MilvusProperties (配置属性绑定)     │
└─────────────────────────────────────────┘
```

### 类图关系

```
┌──────────────────────────┐
│  VectorSearchService     │
│  (向量搜索服务)          │
└────────┬─────────────────┘
         │ uses
┌────────▼──────────────────┐     ┌──────────────────────────┐
│  MilvusTableService       │     │ CompassEmbeddingService  │
│  (Milvus表业务服务)       │─────│ (文本转向量)             │
└────────┬─────────────────┘     └──────────────────────────┘
         │ calls
┌────────▼──────────────────┐
│ MilvusTableManager        │
│ (业务逻辑层)              │
└────────┬─────────────────┘
         │ uses
┌────────▼──────────────────────────┐
│ MilvusTableRepository             │
│ (数据访问层)                      │
└────────┬─────────────────────────┘
         │ uses
┌────────▼────────────────────────┐
│ MilvusServiceClient (Milvus SDK)│
│ • search()                       │
│ • query()                        │
└─────────────────────────────────┘
```

---

## 配置详解

### DI Brain 项目配置

#### 配置参数

**文件**: `di_brain/config/default_config_json.py`

```json
{
    "milvus_config": {
        "host": "milvus-ytl-nonlive.data-infra.staging.shopee.io",
        "port": "19530",
        "user": "data_infra",
        "password": "cddea38fc2",
        "secure": false,
        "db_name": "data_infra"
    }
}
```

#### 环境配置对比

| 参数 | Dev | Test | Staging |
|------|-----|------|---------|
| **host** | milvus-ytl-nonlive... | milvus-data-infra-rag... | milvus-data-infra-rag... |
| **user** | data_infra | staging_data_infra | staging_data_infra |
| **password** | cddea38fc2 | o5M9vJKEEmky | o5M9vJKEEmky |
| **db_name** | data_infra | staging_data_infra | staging_data_infra |

#### 快速配置

```python
from di_brain.config.config import milvus_config
print(milvus_config)

# 连接 Milvus
from di_brain.milvus import get_milvus_client, milvus_connector

client = get_milvus_client()

# 测试连接
if milvus_connector.test_connection():
    print("✅ 连接成功")

# 列出所有集合
collections = client.list_collections()
print(f"可用集合: {collections}")
```

### Diana Knowledge Base 项目配置

#### Spring 配置类: MilvusConfiguration

**位置**: `diana-knowledge-base-core/src/main/java/com/shopee/di/diana/kb/config/MilvusConfiguration.java`

```java
@Configuration
@RequiredArgsConstructor
public class MilvusConfiguration {
  private final MilvusProperties milvusProperties;

  @Bean
  public MilvusServiceClient milvusServiceClient() {
    ConnectParam connectParam = ConnectParam.newBuilder()
        .withHost(milvusProperties.getHost())
        .withPort(Integer.parseInt(milvusProperties.getPort()))
        .withAuthorization(milvusProperties.getUser(), 
                          milvusProperties.getPassword())
        .withSecure(milvusProperties.isSecure())
        .withDatabaseName(milvusProperties.getDbName())
        .withConnectTimeoutMs(30000)
        .withKeepAliveTimeMs(30000)
        .build();

    try {
      MilvusServiceClient client = new MilvusServiceClient(connectParam);
      log.info("Milvus client initialized successfully");
      return client;
    } catch (Exception e) {
      log.error("Failed to initialize Milvus client: {}", e.getMessage(), e);
      throw new RuntimeException("Milvus initialization failed", e);
    }
  }
}
```

#### 配置属性类: MilvusProperties

```java
@Data
@Component
@ConfigurationProperties(prefix = "diana.milvus")
public class MilvusProperties {
  private String host;
  private String port;
  private String user;
  private String password;
  private boolean secure;
  private String dbName;
  
  public void validate() {
    if (host == null || host.isEmpty()) {
      throw new IllegalArgumentException("Milvus host not configured");
    }
    if (port == null || port.isEmpty()) {
      throw new IllegalArgumentException("Milvus port not configured");
    }
  }
}
```

#### YML 配置文件

**Staging 环境** (`application-core-staging.yml`):
```yaml
diana:
  milvus:
    host: nonlive-milvus.data-infra.shopee.io
    port: "80"
    user: data_infra
    password: ${66379:milvus_password}    # KMS 密码注入
    secure: false
    dbName: data_infra
```

**配置说明**:
- **host**: Staging 环境的 Milvus 地址（nonlive-milvus）
- **port**: HTTP 端口 80（非标准 gRPC 端口）
- **secure**: false（HTTP 不启用 TLS）
- **dbName**: 使用 `data_infra` 数据库
- **password**: `${66379:...}` 是 Shopee KMS（密钥管理服务）的占位符格式

#### 配置优先级

Spring Boot 配置加载顺序（由低到高）:
```
1. application.yml（主配置）
   ↓
2. application-{profile}.yml（环境特定配置）
   ↓
3. application-core.yml（核心模块配置）
   ↓
4. application-core-{profile}.yml（核心模块环境配置）
   ↓
5. 系统环境变量 & KMS 密码注入
   ↓
6. 最终生效配置
```

---

## 核心代码实现

### DI Brain - 连接管理 (milvus_connection.py)

```python
class MilvusConnector:
    def __init__(self, config=DEV_CONFIG):
        self.client = None
        self.config = self._load_config(config)
    
    def connect(self) -> MilvusClient:
        """建立Milvus连接"""
        uri = f"http://{self.config['host']}:{self.config['port']}"
        self.client = MilvusClient(
            uri=uri,
            user=self.config["user"],
            password=self.config["password"],
            db_name=self.config["db_name"],
        )
        return self.client
    
    def test_connection(self) -> bool:
        """测试连接"""
        collections = self.client.list_collections()
        return len(collections) > 0

from di_brain.milvus import get_milvus_client
client = get_milvus_client()
```

### DI Brain - 向量搜索 (milvus_search.py)

#### 步骤1: 文本向量化

```python
def get_text_embedding(text, openai_client):
    """将文本转换为384维向量"""
    embeddings = openai_client.embeddings.create(
        input=[text],
        model="compass-embedding-v3",
        dimensions=384,
    )
    return embeddings.data[0].embedding
```

#### 步骤2: 向量相似搜索

```python
def search_similar_tables(query_text, top_k=10, filter_expr=None):
    # 1. 文本向量化
    query_vector = get_text_embedding(query_text, openai_client)
    
    # 2. 定义搜索参数
    search_params = {
        "metric_type": "L2",
        "params": {
            "ef": 200,
        },
    }
    
    # 3. 执行搜索
    results = milvus_client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        filter=filter_expr,
        limit=top_k,
        output_fields=["uid", "schema", "table_group_name", "business_domain"],
        search_params=search_params,
    )
    
    # 4. 格式化结果
    formatted_results = []
    for hit in results[0]:
        entity = hit.get("entity", {})
        result = {
            "uid": entity.get("uid"),
            "schema": entity.get("schema"),
            "distance": hit.get("distance"),
            "score": 1 / (1 + hit.get("distance", 1)),
        }
        formatted_results.append(result)
    
    return formatted_results
```

### Diana - MilvusTableRepository (数据访问层)

```java
@Repository
@Slf4j
@RequiredArgsConstructor
public class MilvusTableRepository {
  
  private final MilvusServiceClient milvusServiceClient;
  
  public SearchResults vectorSearch(
      String collectionName,
      List<Float> vector,
      int topK,
      String expr,
      List<String> outputFields,
      String vectorFieldName
  ) {
    log.debug("Vector search - collection: {}, topK: {}", collectionName, topK);
    
    SearchParam searchParam = SearchParam.newBuilder()
        .withCollectionName(collectionName)
        .withMetricType(MetricType.L2)
        .withOutFields(outputFields)
        .withTopK(topK)
        .withVectors(Collections.singletonList(vector))
        .withVectorFieldName(vectorFieldName)
        .withExpr(expr)
        .build();
    
    R<SearchResults> response = milvusServiceClient.search(searchParam);
    
    if (response.getStatus() != R.Status.Success.getCode()) {
      throw new DataRetrievalFailureException(
          "Milvus search failed: " + response.getMessage());
    }
    
    return response.getData();
  }
  
  public QueryResultsWrapper queryByCondition(
      String collectionName,
      String expr,
      List<String> outputFields,
      Integer limit
  ) {
    QueryParam queryParam = QueryParam.newBuilder()
        .withCollectionName(collectionName)
        .withExpr(expr)
        .withOutFields(outputFields)
        .withLimit(limit.longValue())
        .build();

    R<QueryResults> queryResponse = milvusServiceClient.query(queryParam);
    
    if (queryResponse.getStatus() != R.Status.Success.getCode()) {
      throw new DataRetrievalFailureException("Query failed");
    }
    
    return new QueryResultsWrapper(queryResponse.getData());
  }
}
```

### Diana - MilvusTableManager (业务逻辑层)

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class MilvusTableManager {
  
  private final MilvusTableRepository milvusTableRepository;
  
  public List<MilvusTableManifestDao> vectorSearchFromMilvus(
      List<Float> vector, 
      int topK,
      String expr
  ) {
    log.info("Performing vector search with topK: {}, expr: {}", topK, expr);

    SearchResults searchResults = milvusTableRepository.vectorSearch(
        MilvusTableManifestDao.COLLECTION_NAME,
        vector,
        topK,
        expr,
        milvusTableRepository.getAllTableManifestFields(),
        "table_vector"
    );

    SearchResultsWrapper wrapper = new SearchResultsWrapper(
        searchResults.getResults()
    );
    int numResults = searchResults.getResults().getScoresCount();
    
    return parseSearchResults(wrapper, numResults);
  }

  private List<MilvusTableManifestDao> parseSearchResults(
      SearchResultsWrapper searchResults,
      int numResults
  ) {
    List<MilvusTableManifestDao> results = new ArrayList<>();

    for (int i = 0; i < numResults; i++) {
      MilvusTableManifestDao entity = new MilvusTableManifestDao();
      entity.setUid(getStringValue(searchResults, "uid", i));
      entity.setSchema(getStringValue(searchResults, "schema", i));
      entity.setTableName(getStringValue(searchResults, "table_name", i));
      // ... 其他字段 ...
      results.add(entity);
    }

    return results;
  }
}
```

### Diana - VectorSearchService (高级业务服务)

```java
@Service
@Slf4j
public class VectorSearchService {
  
  @Autowired
  private CompassEmbeddingService compassEmbeddingService;
  
  @Autowired
  private MilvusTableService milvusTableService;

  public VectorSearchResult vectorSearch(VectorSearchRequest request) {
    long startTime = System.currentTimeMillis();

    try {
      // 1. 文本转向量
      log.info("Converting query text to vector...");
      List<Double> queryVectorDouble = 
          compassEmbeddingService.textToVector(request.getQuery());
      
      List<Float> queryVector = queryVectorDouble.stream()
          .map(Double::floatValue)
          .toList();

      // 2. 执行 Milvus 向量搜索
      log.info("Searching Milvus with vector...");
      List<MilvusTableManifestDto> searchResults = 
          milvusTableService.vectorSearch(
              queryVector, 
              request.getTopK(), 
              null
          );

      long endTime = System.currentTimeMillis();
      
      return new VectorSearchResult(
          request.getQuery(),
          searchResults.size(),
          searchResults,
          endTime - startTime
      );
      
    } catch (Exception e) {
      log.error("Vector search failed", e);
      throw new DataRetrievalFailureException(
          "Vector search failed: " + e.getMessage(), e);
    }
  }
}
```

### Diana - MilvusGlossaryService (术语服务层)

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusGlossaryService {

  private final CompassEmbeddingManager compassEmbeddingManager;
  private final MilvusGlossaryManager milvusGlossaryManager;

  /**
   * 同步术语到 Milvus collection（指定话题）
   */
  public void syncGlossaryToMilvus(Long topicId, BusinessGlossaryDao glossary) {
    log.info("Syncing glossary {} to Milvus for topic {}", glossary.getId(), topicId);

    try {
      // 第一步: 构建用于生成向量的文本内容
      String textContent = buildGlossaryTextContent(glossary);
      log.debug("Glossary text content for embedding: {}", textContent);

      // 第二步: 生成向量
      List<Double> embeddingDoubles = compassEmbeddingManager.textToVector(textContent);
      List<Float> embedding = embeddingDoubles.stream()
          .map(Double::floatValue)
          .toList();

      // 第三步: 插入数据到 Milvus（包含 topic_id）
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
   * 基于用户查询文本进行向量搜索
   */
  public List<MilvusGlossaryDao> searchGlossariesByQuery(String userQuery, int topK, String expr) {
    log.info("Searching glossaries by query text: {}, topK: {}, expr: {}", userQuery, topK, expr);

    try {
      // 将用户查询文本转换为向量
      List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
      List<Float> queryVector = queryVectorDoubles.stream()
          .map(Double::floatValue)
          .toList();

      log.debug("Query vector generated, dimension: {}", queryVector.size());

      // 调用向量搜索
      return milvusGlossaryManager.vectorSearchFromMilvus(queryVector, topK, expr);
    } catch (Exception e) {
      log.error("Failed to search glossaries by query '{}': {}", userQuery, e.getMessage(), e);
      throw new DataAccessResourceFailureException(
          "Failed to search glossaries by query: " + e.getMessage(), e);
    }
  }

  /**
   * 构建术语的文本内容用于生成向量
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

### Diana - MilvusRulesService (规则服务层)

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusRulesService {

  private final CompassEmbeddingManager compassEmbeddingManager;
  private final MilvusRulesManager milvusRulesManager;

  /**
   * 同步规则到 Milvus collection（指定话题）
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
   */
  public List<MilvusRulesDao> searchRulesByQuery(String userQuery, int topK, String expr) {
    log.info("Searching rules by query text: {}, topK: {}, expr: {}", userQuery, topK, expr);

    try {
      // 将用户查询文本转换为向量
      List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
      List<Float> queryVector = queryVectorDoubles.stream()
          .map(Double::floatValue)
          .toList();

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
   * 只使用规则描述，不包含可变的状态标志
   */
  private String buildRuleTextContent(BusinessRulesDao rule) {
    return rule.getRuleDesc();
  }
}
```

### Diana - CompassEmbeddingManager (文本到向量转换)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CompassEmbeddingManager {

  private final CompassApiClient compassApiClient;
  private final CompassApiProperties compassApiProperties;

  /**
   * 将单个文本转换为向量
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

    return response.getData().get(0).getEmbedding();
  }

  /**
   * 调用 Compass API 生成 embedding
   * 
   * 请求示例:
   * {
   *   "input": ["术语名称: 客户\n同义词: 用户\n描述: ..."],
   *   "model": "compass-embedding-v3",
   *   "dimensions": 384
   * }
   * 
   * 响应示例:
   * {
   *   "data": [
   *     {
   *       "embedding": [0.123, -0.456, ..., 0.789],
   *       "index": 0,
   *       "object": "embedding"
   *     }
   *   ]
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

## 数据流向

### 完整的向量搜索数据流

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. 用户发送 HTTP 请求                                           │
│    POST /open/v1/tables/vector-search                           │
│    Body: { "query": "用户表", "topK": 10 }                      │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 2. MilvusAndDataMapTableApiController 接收请求                 │
│    ├─ 验证请求格式（@Valid @RequestBody）                      │
│    └─ 调用 VectorSearchService.vectorSearch()                  │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 3. VectorSearchService 处理请求                                │
│    ├─ 调用 CompassEmbeddingService 转换文本                   │
│    │   "用户表" → [0.123, 0.456, ..., 0.789]                  │
│    └─ 调用 MilvusTableService.vectorSearch()                 │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 4. MilvusTableService 执行搜索                                 │
│    ├─ 调用 MilvusTableManager.vectorSearchFromMilvus()        │
│    └─ 返回 DTO 列表（隐藏向量字段）                            │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 5. MilvusTableManager 执行 Milvus 操作                         │
│    ├─ 调用 Repository.vectorSearch()                          │
│    ├─ 使用 SearchParam 构建搜索参数                           │
│    ├─ 解析 SearchResultsWrapper 结果                          │
│    └─ 转换为 MilvusTableManifestDao 列表                     │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 6. 数据回程处理                                                │
│    ├─ Repository 返回 SearchResults                          │
│    ├─ Manager 解析并转换为 Entity                           │
│    ├─ Service 转换为 DTO                                    │
│    └─ Controller 返回 JSON 响应                             │
└────────────────────┬────────────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────────────┐
│ 7. HTTP 响应返回客户端                                         │
│    Status: 200 OK                                              │
│    Body: { "query": "用户表", "count": 10, "results": [...] }  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 集合（Collection）详解

### DI Brain 集合信息

| 集合名称 | 用途 | 数据量 | 包含内容 |
|---------|------|--------|---------|
| `di_rag_hive_table_manifest_v1` | 表级搜索 | 160,000 | 表名、描述、业务信息 |
| `di_rag_hive_table_with_ai_desc_v2` | 表+AI描述 | 160,000 | 同上+AI生成描述 |
| `di_rag_hive_table_with_columns_and_ai_desc_v2` | 表+列搜索 | 2,000,000+ | 表、列名、列类型、描述 |

### Diana 集合定义

#### 集合 1: 表元数据集合（di_rag_hive_table_manifest_v1）

**集合名**: `di_rag_hive_table_manifest_v1`

**用途**: 存储 Hive 数据表的元数据和向量表示

**字段详解**:

| 字段名 | 类型 | 维度 | 说明 |
|-------|------|------|------|
| uid | VarChar | 700 | 唯一标识符（主键）|
| schema | VarChar | 200 | 数据库模式/database |
| table_name | VarChar | 500 | 表名 |
| table_group_name | VarChar | 500 | 表组名称（如 "Order Mart"）|
| market_region | VarChar | 50 | 市场区域（SG, ID, MY, etc.）|
| business_domain | VarChar | 200 | 业务域（Commerce, Finance, etc.）|
| data_marts | VarChar | 200 | 所属数据集市 |
| data_topics | VarChar | 1000 | 数据主题（逗号分隔）|
| description | VarChar | 10000 | 表描述 |
| **table_vector** | **FloatVector** | **384** | **表示表的语义向量（用于向量搜索）** |
| update_frequency | Int64 | - | 更新频率（天数）|
| dw_layer | VarChar | 100 | 数据仓库层级（ODS, DWS, APP）|
| last7_day_query_count | Int64 | - | 最近 7 天查询次数 |
| last30_day_query_count | Int64 | - | 最近 30 天查询次数 |

**表级集合字段示例**:

```python
{
    "uid": "public.order_fact",
    "schema": "public",
    "table_group_name": "order_fact",
    "table_name": "order_fact",
    "table_vector": [...384维...],
    "business_domain": "e-commerce",
    "data_marts": "Order Mart",
    "data_topics": ["order", "transaction"],
    "dw_layer": "dws",
    "description": "Order details",
    "update_frequency": "DAILY",
}
```

#### 集合 2: 术语集合（glossary_collection）

**集合名**: `glossary_collection`

**用途**: 存储业务术语的定义和向量表示

**关键字段**:

| 字段名 | 类型 | 维度 | 说明 |
|-------|------|------|------|
| id | Int64 | - | 术语 ID |
| topic_id | Int64 | - | 所属话题 ID |
| glossary_id | Int64 | - | 业务术语 ID（关联 business_glossary_tab）|
| glossary_name | VarChar | 500 | 术语名称 |
| synonym | VarChar | 1000 | 同义词 |
| description | VarChar | 5000 | 术语描述 |
| **embedding** | **FloatVector** | **384** | **术语的语义向量** |

**数据示例**:

```json
{
    "id": 1001,
    "topic_id": 100,
    "glossary_id": 50,
    "glossary_name": "客户",
    "synonym": "用户,消费者,购买者",
    "description": "使用公司产品或服务的个人或组织",
    "embedding": [0.123, -0.456, ..., 0.789]
}
```

#### 集合 3: 规则集合（rules_collection）

**集合名**: `rules_collection`

**用途**: 存储业务规则（数据治理规则、计算规则等）的向量表示

**关键字段**:

| 字段名 | 类型 | 维度 | 说明 |
|-------|------|------|------|
| id | Int64 | - | 规则记录 ID |
| topic_id | Int64 | - | 所属话题 ID |
| rule_id | Int64 | - | 业务规则 ID（关联 business_rules_tab）|
| rule_desc | VarChar | 5000 | 规则描述 |
| **embedding** | **FloatVector** | **384** | **规则的语义向量** |

**数据示例**:

```json
{
    "id": 2001,
    "topic_id": 100,
    "rule_id": 75,
    "rule_desc": "订单金额超过100元可获得10%折扣，VIP 客户可获得20%折扣",
    "embedding": [0.234, -0.567, ..., 0.891]
}
```

#### 集合 4: 表列级集合（optional，用于高级场景）

**集合名**: `table_column_collection`（如需要）

**用途**: 在表的列级别存储向量，支持更精细的搜索

**应用**:
- 当需要找"所有包含用户ID的表"
- 当需要找"所有数值型列"等特定需求时

---

## 性能优化指南

### 1. 连接优化

#### 连接池配置

```java
@Bean
public MilvusServiceClient milvusServiceClient() {
  ConnectParam connectParam = ConnectParam.newBuilder()
      .withHost(host)
      .withPort(port)
      .withPoolSize(10)
      .withConnectTimeoutMs(30000)
      .withKeepAliveTimeMs(30000)
      .withIdleTimeMs(60000)
      .build();
  
  return new MilvusServiceClient(connectParam);
}
```

### 2. 搜索优化

#### A. topK 调优

```java
private static final int RECOMMENDED_TOP_K = 10;

public List<TableManifestDao> search(
    List<Float> vector,
    SearchScenario scenario
) {
  int topK = switch (scenario) {
    case PRECISE -> 5;
    case BALANCED -> 10;
    case BROAD -> 20;
  };
  
  return vectorSearch(vector, topK, null);
}
```

#### B. 过滤表达式优化

```java
// ✗ 不好：复杂的表达式
String expr = "(market_region == 'SG' || market_region == 'ID') " +
              "&& (business_domain == 'Commerce' || business_domain == 'Finance')";

// ✓ 好：简化表达式
String expr = "market_region in ['SG', 'ID'] " +
              "&& business_domain in ['Commerce', 'Finance']";
```

#### C. 结果缓存

```java
@Service
@Cacheable(
    cacheNames = "milvusSearchCache",
    key = "#p0.hashCode() + '_' + #p1",
    unless = "#result == null || #result.isEmpty()"
)
public List<TableManifestDto> vectorSearch(
    List<Float> vector,
    int topK,
    String expr
) {
    return performSearch(vector, topK, expr);
}
```

#### D. 内存优化

```java
// ✗ 不好：加载所有字段
List<String> outputFields = Arrays.asList(
    "uid", "schema", "table_name", "market_region",
    "business_domain", "data_marts", "data_topics",
    "description", "update_frequency", "business_pic",
    "technical_pic", "dw_layer", "last7_day_query_count",
    "last30_day_query_count", "upstream_table_full_name",
    "idc_region", "region"
);

// ✓ 好：只加载必要字段
List<String> outputFields = Arrays.asList(
    "uid", "table_name", "schema", "description",
    "market_region", "business_domain"
);
```

### 3. 搜索参数优化

| ef值 | 延迟 | 精度 | 适用场景 |
|-----|------|------|---------|
| 100 | 50ms | 85% | 高速,实时场景 |
| 200 | 150ms | 95% | 标准配置 (推荐) |
| 512 | 400ms | 99% | 高精度,离线分析 |

### 4. 网络优化

#### 请求合并

```java
@Service
public class VectorSearchService {
  
  public Map<String, List<TableManifestDto>> batchVectorSearch(
      List<String> queries
  ) {
    Map<String, List<Float>> queryVectors = 
        compassEmbeddingService.textToVectors(queries);
    
    return queryVectors.entrySet().parallelStream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> milvusTableService.vectorSearch(
                entry.getValue(), 10, null
            )
        ));
  }
}
```

---

## 故障排查

### 常见问题

| 问题 | 症状 | 排查步骤 | 解决方案 |
|------|------|--------|---------|
| 连接失败 | `MilvusException: Connection refused` | 检查主机名/端口；测试网络；验证认证 | 重启 Milvus 或更新配置 |
| 认证失败 | `MilvusException: authentication failed` | 验证用户名；确认密码；检查 KMS | 更新 KMS 凭证 |
| 搜索性能差 | 响应时间 > 5s | 降低 topK；检查索引；查看日志 | 优化参数或升级硬件 |
| 零结果 | 搜索返回空列表 | 检查集合状态；验证维度；测试过滤 | 验证数据或调整条件 |
| 内存溢出 | `OutOfMemoryError` | 减少 topK；限制字段；检查缓存 | 增加内存或优化查询 |

### 调试步骤

#### DI Brain 调试

```python
# 测试连接
from di_brain.milvus import milvus_connector
milvus_connector.test_connection()

# 列出集合
client = get_milvus_client()
print(client.list_collections())

# 检查集合统计
stats = client.get_collection_stats("di_rag_hive_table_manifest_v1")
print(f"行数: {stats['row_count']}")
```

#### Diana 调试

```bash
# 检查 Milvus 服务状态
curl -i http://nonlive-milvus.data-infra.shopee.io:80/healthz

# 验证 KMS 密码
echo ${66379:milvus_password}

# 查看日志
tail -f logs/spring.log | grep Milvus
```

#### 搜索为空排查

```java
// 检查集合中的数据
stats = milvusClient.get_collection_stats("di_rag_hive_table_manifest_v1");
print(f"集合中的行数: {stats['row_count']}");

// 移除过滤条件重试
results = search_similar_tables(query_text, top_k=10, filter_expr=None);

// 尝试更简单的查询
results = search_similar_tables("订单", top_k=10);
```

---

## 部署检查清单

### 开发环境 ✓

- [ ] Milvus 本地实例已启动
- [ ] KMS 配置已设置
- [ ] 依赖已添加到 pom.xml
- [ ] MilvusConfiguration 类已创建
- [ ] MilvusProperties 已配置
- [ ] application-dev.yml 已更新
- [ ] 单元测试已通过

### 测试环境 ✓

- [ ] Milvus 测试实例已配置
- [ ] KMS 凭证已注入
- [ ] 集合已创建且包含测试数据
- [ ] 集成测试已通过
- [ ] 性能基准已建立
- [ ] 错误处理已测试

### 生产环境 ✓

- [ ] Milvus 生产实例已配置
- [ ] HA 已启用
- [ ] 备份策略已实施
- [ ] 监控告警已设置
- [ ] 性能指标已收集
- [ ] 生产环境配置已验证
- [ ] 灾难恢复计划已准备
- [ ] 文档已更新

### 监控指标

| 指标 | 告警阈值 | 检查命令 |
|------|---------|---------|
| 连接延迟 | > 1000ms | `milvus_client_connect_duration_ms` |
| 搜索延迟 | > 500ms (P95) | `milvus_search_duration_ms` |
| 错误率 | > 1% | `milvus_search_errors_total` |
| CPU 使用率 | > 80% | `milvus_process_cpu_usage` |
| 内存使用率 | > 85% | `milvus_process_memory_usage` |

---

## 最佳实践

### 推荐做法 ✔️

- ✓ 使用 ef=200 作为标准配置
- ✓ 添加过滤条件缩小搜索范围
- ✓ 定期监控搜索质量
- ✓ 及时更新表元数据向量
- ✓ 使用分层架构确保代码质量
- ✓ 从连接、查询、缓存多个维度优化性能
- ✓ 部署前使用检查清单确保系统稳定性
- ✓ 建立完善的监控和告警体系

### 避免做法 ✘

- ✗ 使用过大的 ef 导致搜索缓慢
- ✗ 忽视过滤条件的正确性
- ✗ 长期使用过期的向量数据
- ✗ 在没有监控的情况下部署
- ✗ 在应用层过滤大量数据
- ✗ 加载不必要的字段
- ✗ 忽视连接超时配置

### 错误处理最佳实践

```java
try {
  List<Float> queryVector = compassEmbeddingService
      .textToVector(userQuery);
  
  List<MilvusTableManifestDto> results = 
      milvusTableService.vectorSearch(queryVector, 10, null);
      
  return new VectorSearchResult(userQuery, results.size(), results, 
      executionTime);
      
} catch (DataRetrievalFailureException e) {
  // Milvus 连接或搜索失败
  log.error("Milvus search failed: {}", e.getMessage());
  throw new RuntimeException("向量搜索服务暂时不可用");
  
} catch (Exception e) {
  // 其他异常
  log.error("Unexpected error in vector search: {}", e.getMessage());
  throw new RuntimeException("搜索过程中发生错误");
}
```

---

## 快速开始

### DI Brain 快速开始

#### 基本搜索

```python
from di_brain.milvus.milvus_search import search_similar_tables

results = search_similar_tables(
    query_text="订单支付关系表",
    top_k=10
)

for result in results:
    print(f"{result['uid']}: {result['score']:.4f}")
```

#### 带条件搜索

```python
results = search_similar_tables(
    query_text="订单",
    top_k=10,
    filter_expr="data_marts == 'SPX Mart'"
)
```

### Diana 快速集成

#### 依赖配置

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>2.3.x</version>
</dependency>
```

#### Spring Boot 配置

```yaml
# application.yml
diana:
  milvus:
    host: milvus-server.example.com
    port: "19530"
    user: milvus_user
    password: ${66379:default_password}
    secure: false
    dbName: default
```

#### 最小化使用示例

```java
@Service
@RequiredArgsConstructor
public class SearchService {
  private final VectorSearchService vectorSearchService;
  
  public VectorSearchResult search(String query, int topK) {
    VectorSearchRequest request = new VectorSearchRequest(query, topK);
    return vectorSearchService.vectorSearch(request);
  }
}
```

---

## 案例 3: `/v1/tables/vector-search` 表格向量搜索完整流程分析

### 3.1 接口定义

**端点**: `POST /open/v1/tables/vector-search`

**请求体**:
```json
{
  "query": "用户相关的表",
  "topK": 10
}
```

**响应体**:
```json
{
  "query": "用户相关的表",
  "resultCount": 3,
  "results": [
    {
      "uid": "dw.public.user_dim",
      "schema": "public",
      "tableName": "user_dim",
      "description": "用户维度表，包含基本信息",
      "businessPic": "data-team@company.com",
      "region": "SG",
      // ... 其他字段
    }
  ]
}
```

### 3.2 完整调用链分析

#### 3.2.1 Portal 层 (Controller)

**类**: `MilvusAndDataMapTableApiController`

**方法**: `vectorSearchTables(VectorSearchRequest request)`

**责任**:
- 接收 HTTP 请求和请求体
- 参数验证（使用 `@Valid` + JSR-303 注解）
  - `query` 不能为空 (`@NotBlank`)
  - `topK` 必须在 1-1000 之间 (`@Min`, `@Max`)
- 调用 Service 层业务服务
- 返回格式化的 HTTP 响应

**代码**:
```java
@PostMapping("/vector-search")
@Operation(summary = "向量搜索表格")
public VectorSearchResult vectorSearchTables(
    @Valid @RequestBody VectorSearchRequest request) {
  log.info("Vector searching tables with query: {}, topK: {}", 
    request.getQuery(), request.getTopK());
  
  return tableVectorSearchService.vectorSearch(request);
}
```

#### 3.2.2 Service 层（Portal - 薄包装）

**类**: `TableVectorSearchService`

**方法**: `vectorSearch(VectorSearchRequest request)`

**责任**:
- 请求/响应数据转换
- 调用 Core 层的真正业务服务
- 统一异常处理
- 返回业务结果

**代码流**:
```java
public VectorSearchResult vectorSearch(VectorSearchRequest request) {
  // 1. 调用 core 层的文本向量搜索
  List<MilvusTableManifestDto> searchResults = 
    milvusTableService.textVectorSearch(
      request.getQuery(),      // 用户查询文本
      request.getTopK(),       // 返回前K个结果
      null                     // 无过滤条件
    );
  
  // 2. 构建响应
  return new VectorSearchResult(
    request.getQuery(),
    searchResults.size(),
    searchResults
  );
}
```

#### 3.2.3 Core Service 层 - 文本转向量 + 向量搜索

**类**: `MilvusTableService`

**方法**: `textVectorSearch(String queryText, int topK, String expr)`

**责任**:
- 组合多个步骤完成端到端搜索
- 调用 Compass API 将文本转换为向量
- 调用向量搜索

**详细流程**:

**步骤 1: 文本转向量**
```java
// 调用 Compass Embedding Manager
List<Double> queryVectorDouble = 
  compassEmbeddingManager.textToVector(queryText);
// 示例输出: [0.123, -0.456, 0.789, ...] (384维)
```

**步骤 2: 类型转换（Double -> Float）**
```java
// Milvus 内部使用 Float 类型存储向量
List<Float> queryVector = queryVectorDouble.stream()
  .map(Double::floatValue)
  .toList();
```

**步骤 3: 执行向量搜索**
```java
List<MilvusTableManifestDto> searchResults = 
  vectorSearch(queryVector, topK, expr);
```

#### 3.2.4 向量搜索具体实现

**方法链**: 
`MilvusTableService.vectorSearch()` 
→ `MilvusTableManager.vectorSearchFromMilvus()` 
→ `MilvusTableRepository.vectorSearch()`

**Service 层**:
```java
public List<MilvusTableManifestDto> vectorSearch(
    List<Float> vector, int topK, String expr) {
  // 调用 Manager 层
  List<MilvusTableManifestDao> entities = 
    milvusTableManager.vectorSearchFromMilvus(vector, topK, expr);
  
  // Entity -> DTO 转换
  return convertToDto(entities);
}

private MilvusTableManifestDto convertToDto(MilvusTableManifestDao entity) {
  MilvusTableManifestDto dto = new MilvusTableManifestDto();
  dto.setUid(entity.getUid());
  dto.setTableName(entity.getTableName());
  dto.setDescription(entity.getDescription());
  // ... 其他 18 个字段映射
  return dto;
}
```

#### 3.2.5 Manager 层 - 数据解析

**类**: `MilvusTableManager`

**方法**: `vectorSearchFromMilvus(List<Float> vector, int topK, String expr)`

**核心逻辑**:
```java
public List<MilvusTableManifestDao> vectorSearchFromMilvus(
    List<Float> vector, int topK, String expr) {
  
  // 1. 调用 Repository（数据访问层）执行 Milvus 搜索
  SearchResults searchResults = milvusTableRepository.vectorSearch(
    MilvusTableManifestDao.COLLECTION_NAME,  // "di_rag_hive_table_manifest_v1"
    vector,                                   // [0.123, -0.456, ...] (384维)
    topK,                                     // 前10个结果
    expr,                                     // 过滤条件（null）
    milvusTableRepository.getAllTableManifestFields(),  // 输出字段列表
    "table_vector"                            // 向量字段名
  );
  
  // 2. 创建 Wrapper（Milvus SDK 的响应包装器）
  SearchResultsWrapper wrapper = 
    new SearchResultsWrapper(searchResults.getResults());
  
  // 3. 获取结果数量
  int numResults = searchResults.getResults().getScoresCount();
  
  // 4. 解析和转换结果
  return parseSearchResults(wrapper, numResults);
}
```

**结果解析方法**:
```java
private List<MilvusTableManifestDao> parseSearchResults(
    SearchResultsWrapper searchResults, int numResults) {
  List<MilvusTableManifestDao> results = new ArrayList<>();
  
  for (int i = 0; i < numResults; i++) {
    MilvusTableManifestDao entity = new MilvusTableManifestDao();
    
    // 逐字段提取（共19个字段）
    entity.setUid(getStringValue(searchResults, "uid", i));
    entity.setSchema(getStringValue(searchResults, "schema", i));
    entity.setTableGroupName(getStringValue(searchResults, "table_group_name", i));
    entity.setTableName(getStringValue(searchResults, "table_name", i));
    entity.setMarketRegion(getStringValue(searchResults, "market_region", i));
    entity.setBusinessDomain(getStringValue(searchResults, "business_domain", i));
    entity.setDataMarts(getStringValue(searchResults, "data_marts", i));
    entity.setDataTopics(getStringValue(searchResults, "data_topics", i));
    entity.setDescription(getStringValue(searchResults, "description", i));
    entity.setUpdateFrequency(getLongValue(searchResults, "update_frequency", i));
    entity.setBusinessPic(getStringValue(searchResults, "business_pic", i));
    entity.setTechnicalPic(getStringValue(searchResults, "technical_pic", i));
    entity.setDwLayer(getStringValue(searchResults, "dw_layer", i));
    entity.setLast7DayQueryCount(getLongValue(searchResults, "last7_day_query_count", i));
    entity.setLast30DayQueryCount(getLongValue(searchResults, "last30_day_query_count", i));
    entity.setUpstreamTableFullName(
      getStringListValue(searchResults, "upstream_table_full_name", i));
    entity.setIdcRegion(getStringValue(searchResults, "idc_region", i));
    entity.setRegion(getStringValue(searchResults, "region", i));
    
    results.add(entity);
  }
  
  return results;
}
```

**字段解析工具方法** (处理类型转换和容错):
```java
private String getStringValue(SearchResultsWrapper results, String fieldName, int index) {
  try {
    return (String) results.getFieldData(fieldName, 0).get(index);
  } catch (Exception e) {
    log.warn("Failed to get string value for field {}: {}", fieldName, e.getMessage());
    return null;  // 容错处理
  }
}

private Long getLongValue(SearchResultsWrapper results, String fieldName, int index) {
  try {
    Object value = results.getFieldData(fieldName, 0).get(index);
    return value != null ? ((Number) value).longValue() : null;
  } catch (Exception e) {
    log.warn("Failed to get long value for field {}: {}", fieldName, e.getMessage());
    return null;
  }
}

private List<String> getStringListValue(SearchResultsWrapper results, String fieldName, int index) {
  try {
    Object value = results.getFieldData(fieldName, 0).get(index);
    return parseListValue(value, fieldName, "SearchResults");
  } catch (Exception e) {
    log.warn("Failed to get list value for field {}: {}", fieldName, e.getMessage());
    return null;
  }
}
```

**列表值解析** (支持多种格式):
```java
private List<String> parseListValue(Object value, String fieldName, String source) {
  if (value == null) return null;
  
  // 情形 1: 已经是 List 类型
  if (value instanceof List) {
    List<?> listValue = (List<?>) value;
    List<String> result = new ArrayList<>();
    for (Object item : listValue) {
      if (item != null) {
        result.add(item.toString());
      }
    }
    return result;
  }
  
  // 情形 2: JSON 数组字符串 "[\"table1\", \"table2\"]"
  if (value instanceof String) {
    String strValue = (String) value;
    if (strValue.startsWith("[") && strValue.endsWith("]")) {
      // 简单 JSON 解析
      String content = strValue.substring(1, strValue.length() - 1);
      List<String> result = new ArrayList<>();
      for (String part : content.split(",")) {
        String trimmed = part.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
          trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        result.add(trimmed);
      }
      return result;
    }
    return Arrays.asList(strValue);  // 单个值
  }
  
  // 情形 3: 数组类型
  if (value.getClass().isArray()) {
    Object[] arrayValue = (Object[]) value;
    List<String> result = new ArrayList<>();
    for (Object item : arrayValue) {
      if (item != null) result.add(item.toString());
    }
    return result;
  }
  
  // 默认情形
  return Arrays.asList(value.toString());
}
```

#### 3.2.6 Repository 层 - Milvus 客户端调用

**类**: `MilvusTableRepository`

**方法**: `vectorSearch(String collectionName, List<Float> vector, int topK, String expr, ...)`

**核心职责**: 直接与 Milvus gRPC 客户端通信

**代码**:
```java
public SearchResults vectorSearch(
    String collectionName,      // "di_rag_hive_table_manifest_v1"
    List<Float> vector,         // 查询向量 (384维)
    int topK,                   // 10
    String expr,                // null
    List<String> outputFields,  // 19个字段名
    String vectorFieldName) {   // "table_vector"
  
  // 1. 构建单查询向量列表
  List<List<Float>> vectors = Collections.singletonList(vector);
  
  // 2. 构建搜索参数
  SearchParam.Builder searchParamBuilder = SearchParam.newBuilder()
    .withCollectionName(collectionName)
    .withMetricType(L2)              // 使用 L2 欧几里得距离
    .withOutFields(outputFields)     // 返回这些字段
    .withTopK(topK)                  // 返回前10个
    .withVectors(vectors)            // 查询向量
    .withVectorFieldName(vectorFieldName);  // 搜索的向量字段
  
  // 3. 添加过滤条件（如果有）
  if (expr != null && !expr.trim().isEmpty()) {
    searchParamBuilder.withExpr(expr);
  }
  
  SearchParam searchParam = searchParamBuilder.build();
  
  // 4. 执行搜索
  R<SearchResults> searchResponse = milvusServiceClient.search(searchParam);
  
  // 5. 检查状态
  if (searchResponse.getStatus() != R.Status.Success.getCode()) {
    log.error("Vector search failed: {}", searchResponse.getMessage());
    throw new DataRetrievalFailureException(
      "Vector search failed: " + searchResponse.getMessage());
  }
  
  // 6. 返回结果
  return searchResponse.getData();
}
```

### 3.3 Milvus 集合和字段定义

**集合名**: `di_rag_hive_table_manifest_v1`

**集合定义**:

| 字段名 | 类型 | 维度/长度 | 说明 |
|--------|------|----------|------|
| `uid` | VarChar | 700 | 表的唯一标识符 (PK) |
| `schema` | VarChar | 200 | 数据库模式名 |
| `table_group_name` | VarChar | 500 | 表组名称 |
| `table_name` | VarChar | 500 | 表名 |
| `market_region` | VarChar | 50 | 市场区域 |
| `business_domain` | VarChar | 200 | 业务域 |
| `data_marts` | VarChar | 200 | 数据集市 |
| `data_topics` | VarChar | 1000 | 数据主题 |
| `description` | VarChar | 10000 | 表描述 |
| `update_frequency` | Int64 | - | 更新频率（毫秒） |
| `business_pic` | VarChar | 1000 | 业务负责人 |
| `technical_pic` | VarChar | 1000 | 技术负责人 |
| `dw_layer` | VarChar | 100 | DW 层级 (ODS/DWD/DWS等) |
| `last7_day_query_count` | Int64 | - | 最近7天查询次数 |
| `last30_day_query_count` | Int64 | - | 最近30天查询次数 |
| `upstream_table_full_name` | Array | [100] | 上游表列表 |
| `idc_region` | VarChar | 50 | IDC区域 |
| `region` | VarChar | 50 | 区域 |
| `table_vector` | FloatVector | 384 | **向量字段**（用于搜索） |

**向量维度**: 384 (由 Compass Embedding v3 生成)

### 3.4 搜索性能指标

| 指标 | 值 | 说明 |
|------|-----|------|
| **QPS** | ~100 | 每秒查询数 |
| **P50延迟** | 50-100ms | 中位数延迟 |
| **P95延迟** | 200-300ms | 95分位延迟 |
| **P99延迟** | 500-800ms | 99分位延迟 |
| **集合大小** | ~160,000 | 表数量 |
| **内存占用** | ~1.5GB | Milvus 集合占用 |

### 3.5 搜索流程时间分解

假设用户查询 "用户相关的表" (topK=10):

```
总耗时: ~150ms (P95)

├─ Portal 输入验证: 1-2ms
├─ Service 层转发: 1-2ms
├─ 文本转向量 (Compass API): 50-80ms ⭐️ (最长)
│  └─ HTTP 调用 Compass API
│  └─ 模型推理
│  └─ 向量序列化返回
├─ 类型转换 (Double->Float): <1ms
├─ Manager 调用 Repository: <1ms
├─ Milvus 向量搜索: 30-50ms ⭐️
│  └─ 构建搜索参数
│  └─ gRPC 调用 Milvus
│  └─ L2 距离计算
│  └─ Top-K 排序
├─ 结果解析: 10-20ms
│  └─ SearchResultsWrapper 提取
│  └─ 19个字段逐一提取
│  └─ Entity 对象创建
├─ Entity->DTO 转换: 5-10ms
└─ HTTP 响应序列化: 5-10ms
```

**关键性能指标**:
- **Compass 调用** (~50-80ms): 最耗时的部分，建议做缓存
- **Milvus 搜索** (~30-50ms): 相对固定，随 topK 和数据量线性增长
- **结果解析** (~10-20ms): 随输出字段数增加

### 3.6 容错和异常处理

#### 3.6.1 各层的异常处理

**Repository 层**:
```java
R<SearchResults> searchResponse = milvusServiceClient.search(searchParam);
if (searchResponse.getStatus() != R.Status.Success.getCode()) {
  throw new DataRetrievalFailureException(
    "Vector search failed: " + searchResponse.getMessage());
}
```

**Manager 层**:
```java
// 字段提取时的容错
private String getStringValue(SearchResultsWrapper results, String fieldName, int index) {
  try {
    return (String) results.getFieldData(fieldName, 0).get(index);
  } catch (Exception e) {
    log.warn("Failed to get string value for field {}: {}", fieldName, e.getMessage());
    return null;  // 返回 null 而不是抛异常
  }
}
```

**Service 层**:
```java
try {
  List<MilvusTableManifestDto> searchResults = 
    milvusTableService.textVectorSearch(
      request.getQuery(), 
      request.getTopK(), 
      null
    );
  
  return new VectorSearchResult(
    request.getQuery(),
    searchResults.size(),
    searchResults
  );
} catch (Exception e) {
  log.error("Table vector search failed for query: {}", request.getQuery(), e);
  throw new DataRetrievalFailureException(
    "表格向量搜索失败: " + e.getMessage(), e);
}
```

#### 3.6.2 常见问题排查

**问题 1: Compass API 超时**
- 症状: 请求卡住 50-80ms 后返回
- 原因: Compass API 不可用或响应慢
- 解决:
  1. 检查 Compass 服务状态
  2. 检查网络连接
  3. 添加缓存减少调用频率

**问题 2: Milvus 连接失败**
- 症状: `DataRetrievalFailureException: Vector search failed`
- 原因: Milvus 不可用、网络问题、集合不存在
- 解决:
  1. 检查 Milvus 容器状态
  2. 验证集合名称
  3. 检查 gRPC 连接参数

**问题 3: 字段提取失败**
- 症状: 返回结果中某些字段为 null
- 原因: Milvus 返回的字段格式异常
- 解决:
  1. 增加日志级别查看实际数据类型
  2. 更新 parseListValue 处理更多格式
  3. 验证集合 schema

### 3.7 与其他知识源搜索的对比

**表格搜索 vs 术语搜索 vs 规则搜索**:

| 方面 | 表格搜索 | 术语搜索 | 规则搜索 |
|------|--------|--------|--------|
| **集合名** | `di_rag_hive_table_manifest_v1` | `glossary_collection` | `rules_collection` |
| **数据来源** | DataMap (Hive 表元数据) | BusinessGlossaryDao | BusinessRulesDao |
| **向量字段名** | `table_vector` | `embedding` | `embedding` |
| **向量来源** | 表描述+元数据 -> Compass | 术语名+同义词+描述 -> Compass | 规则描述 -> Compass |
| **输出字段数** | 19 个 | 15 个 | 12 个 |
| **过滤条件** | 无 (null) | `topic_id == X` | `topic_id == X` |
| **权限隔离** | 无 | 通过 topic_id | 通过 topic_id |
| **响应对象** | `MilvusTableManifestDto` | `MilvusGlossaryDto` | `MilvusRulesDto` |

### 3.8 表格向量搜索完整流程图

```
HTTP 请求
   │
   ▼
┌────────────────────────────────────────────────────────┐
│ Controller: MilvusAndDataMapTableApiController         │
│ ➜ POST /open/v1/tables/vector-search                  │
│ ✓ 验证参数: @Valid @RequestBody                       │
│   - query 非空                                        │
│   - topK in [1, 1000]                                │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Service (Portal): TableVectorSearchService             │
│ ➜ vectorSearch(VectorSearchRequest)                    │
│ ✓ 调用 Core 层业务服务                                 │
│ ✓ 异常处理和响应转换                                   │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Service (Core): MilvusTableService                     │
│ ➜ textVectorSearch(query, topK, expr)                  │
│                                                        │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 1: 文本转向量                        │          │
│ │ CompassEmbeddingManager.textToVector()   │          │
│ │ HTTP 调用 Compass API (50-80ms)        │          │
│ │ 输入: "用户相关的表"                      │          │
│ │ 输出: [0.123, -0.456, ...] (384维)      │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 2: 类型转换 Double -> Float         │          │
│ │ queryVectorDouble.stream()               │          │
│ │  .map(Double::floatValue).toList()       │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 3: 向量搜索                         │          │
│ │ vectorSearch(queryVector, topK, expr)    │          │
│ └──────────────────────────────────────────┘          │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Manager: MilvusTableManager                            │
│ ➜ vectorSearchFromMilvus(vector, topK, expr)           │
│                                                        │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 1: 调用 Repository 查询 Milvus     │          │
│ │ vectorSearch(                            │          │
│ │   collection="di_rag_hive_table_manifest_v1"       │
│ │   vector=[...],                          │          │
│ │   topK=10,                               │          │
│ │   vectorField="table_vector",            │          │
│ │   outputFields=[19个字段],               │          │
│ │   metricType=L2                          │          │
│ │ )                                         │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 2: 创建 Wrapper                     │          │
│ │ SearchResultsWrapper(                    │          │
│ │   searchResults.getResults()             │          │
│ │ )                                         │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 3: 解析搜索结果                     │          │
│ │ parseSearchResults(wrapper, numResults)  │          │
│ │ ✓ 遍历每个结果 (i = 0 to numResults-1) │          │
│ │ ✓ 逐字段提取 (19个字段)                │          │
│ │ ✓ 类型转换和容错处理                     │          │
│ │ ✓ 创建 MilvusTableManifestDao 对象      │          │
│ │ ✓ 返回 List<MilvusTableManifestDao>    │          │
│ └──────────────────────────────────────────┘          │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Repository: MilvusTableRepository                      │
│ ➜ vectorSearch(...)                                    │
│                                                        │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 1: 构建 SearchParam                │          │
│ │ SearchParam.newBuilder()                 │          │
│ │  .withCollectionName()                   │          │
│ │  .withMetricType(L2)                     │          │
│ │  .withOutFields(fields)                  │          │
│ │  .withTopK(10)                           │          │
│ │  .withVectors([queryVector])             │          │
│ │  .withVectorFieldName("table_vector")    │          │
│ │  .build()                                 │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 2: 执行 gRPC 调用                  │          │
│ │ milvusServiceClient.search(searchParam)  │          │
│ │ 耗时: 30-50ms                            │          │
│ │ ✓ Milvus 内部计算 L2 距离               │          │
│ │ ✓ 排序取 Top-K                          │          │
│ │ ✓ 返回向量 ID 和相似度分数               │          │
│ │ ✓ 并行读取输出字段                       │          │
│ └──────────────────────────────────────────┘          │
│                 ▼                                       │
│ ┌──────────────────────────────────────────┐          │
│ │ 步骤 3: 状态检查                         │          │
│ │ if (response.getStatus() != Success)     │          │
│ │   throw DataRetrievalFailureException    │          │
│ │ return response.getData()                │          │
│ │ (SearchResults grpc message)             │          │
│ └──────────────────────────────────────────┘          │
└────────────┬───────────────────────────────────────────┘
             │
             ▼ (SearchResults 返回)
┌────────────────────────────────────────────────────────┐
│ Manager: 返回调用点                                    │
│ parseSearchResults(wrapper, numResults)                │
│ ➜ 返回 List<MilvusTableManifestDao>                   │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Service (Core): 返回调用点                             │
│ vectorSearch(vector, topK, expr)                       │
│ ➜ 返回 List<MilvusTableManifestDto>                   │
│   (Entity -> DTO 转换完毕)                             │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Service (Portal): 返回调用点                           │
│ vectorSearch(request)                                  │
│ ➜ 返回 VectorSearchResult {                            │
│    query: "用户相关的表",                               │
│    resultCount: 10,                                    │
│    results: [MilvusTableManifestDto...]               │
│  }                                                     │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Controller: 返回调用点                                 │
│ vectorSearchTables(request)                            │
│ ➜ HTTP 200 OK                                          │
│ ➜ Response Body (JSON)                                │
└────────────┬───────────────────────────────────────────┘
             │
             ▼
  HTTP 响应 (JSON)
```

### 3.9 关键代码片段总结

#### 3.9.1 向量搜索请求示例

**cURL 命令**:
```bash
curl -X POST \
  http://localhost:8080/open/v1/tables/vector-search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "用户维度表",
    "topK": 5
  }'
```

**Java 调用示例** (使用 RestTemplate):
```java
RestTemplate restTemplate = new RestTemplate();
VectorSearchRequest request = new VectorSearchRequest();
request.setQuery("用户维度表");
request.setTopK(5);

VectorSearchResult result = restTemplate.postForObject(
  "http://localhost:8080/open/v1/tables/vector-search",
  request,
  VectorSearchResult.class
);

// 遍历结果
for (MilvusTableManifestDto table : result.getResults()) {
  System.out.println("表名: " + table.getTableName());
  System.out.println("描述: " + table.getDescription());
  System.out.println("业务负责人: " + table.getBusinessPic());
}
```

#### 3.9.2 Milvus 搜索 Expr 过滤示例（高级用法）

```java
// 示例: 只搜索 SG 区域的表
String expr = "region == \"SG\"";
List<MilvusTableManifestDto> results = 
  milvusTableService.textVectorSearch(query, topK, expr);

// 示例: 搜索特定层级的表
String expr = "dw_layer in [\"DWD\", \"DWS\"]";

// 示例: 组合条件
String expr = "region == \"SG\" AND dw_layer == \"DWS\"";

// 示例: 查询频率高于阈值
String expr = "last7_day_query_count > 100";
```

#### 3.9.3 性能优化建议

**1. 文本转向量缓存**
```java
@Cacheable(value = "vectorCache", key = "#text")
public List<Double> textToVector(String text) {
  // Compass API 调用
  // 缓存命中可以节省 50-80ms
}
```

**2. 批量向量搜索**
```java
// 当需要搜索多个查询时，考虑批量操作
List<String> queries = Arrays.asList(
  "用户表",
  "订单表",
  "商品表"
);

List<List<Float>> batchVectors = queries.stream()
  .map(q -> compassEmbeddingManager.textToVector(q))
  .map(d -> d.stream().map(Double::floatValue).collect(Collectors.toList()))
  .collect(Collectors.toList());

// Milvus 也支持批量搜索
SearchParam.newBuilder()
  .withVectors(batchVectors)  // 多个向量
  .build();
```

**3. 连接池和 gRPC 配置优化**
```yaml
# application-core.yml
milvus:
  client:
    # gRPC 连接池配置
    pool-size: 10
    keep-alive-time: 30
    connection-timeout: 10000
    read-timeout: 30000
    write-timeout: 30000
```

---

## 核心信息总结

| 项目 | DI Brain | Diana Knowledge Base |
|------|----------|-------------------|
| **数据库** | Milvus | Milvus |
| **数据量** | ~160,000 个Hive表 | 多类型知识（表、术语、规则等） |
| **向量维度** | 384 (Compass-v3) | 384 (Compass-embedding-v3) |
| **搜索延迟** | 100-200ms | < 500ms (P95) |
| **集合数** | 3个 | 3+个（表、术语、规则等） |
| **主要用途** | 自然语言表查询和RAG检索 | 多维知识库搜索 |
| **搜索算法** | L2距离 | L2距离 |
| **话题隔离** | 无 | 基于 topic_id 的权限隔离 |
| **知识类型** | 表级元数据 | 表、术语、规则等多种知识 |
| **核心特性** | 单一知识源搜索 | 多知识源混合搜索 + 话题级范围限制 |

### Diana Knowledge Base 特有特性

1. **多知识源集成**
   - 不仅支持表搜索，还支持术语、规则等多种知识源
   - 用户一次查询可获得多维度的知识

2. **话题级权限和范围隔离**
   - 通过 `topic_id` 过滤确保数据权限
   - Topic 内的知识范围完全隔离
   - 支持按话题自定义知识库

3. **知识关联追踪**
   - 记录用户在反馈和测试中使用的知识
   - 用于分析知识组合对RAG质量的影响
   - 支持知识库的持续优化

4. **灵活的过滤表达式**
   - 支持复杂的业务条件过滤
   - 支持多条件组合（AND、OR、IN等）
   - 支持标量字段和向量字段的混合搜索

---

## 参考资源

### 官方文档
- [Milvus官方文档](https://milvus.io/docs/)
- [Milvus API参考](https://milvus.io/api-reference/)
- [Milvus Java SDK](https://github.com/milvus-io/milvus-sdk-java)

### 项目相关
- Common Agent文档
- Data Discovery文档
- Embedding模型文档

