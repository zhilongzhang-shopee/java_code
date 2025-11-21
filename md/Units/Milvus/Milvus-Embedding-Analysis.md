# Diana Knowledge Base - Milvus 存储的 Embedding 向量生成分析

## 📋 目录
1. [系统架构概述](#系统架构概述)
2. [Embedding 向量生成流程](#embedding-向量生成流程)
3. [数据源详解](#数据源详解)
4. [Compass API 集成](#compass-api-集成)
5. [向量搜索机制](#向量搜索机制)
6. [配置参数](#配置参数)
7. [数据流向图](#数据流向图)

---

## 系统架构概述

### 整体架构

```
业务数据（Glossary、Rules、Table）
        ↓
数据构建（buildTextContent）
        ↓
Compass Embedding API（转化为向量）
        ↓
Milvus 向量库（存储向量）
        ↓
向量搜索（用户查询 → 相似度匹配）
```

### 核心组件

| 组件 | 描述 | 职责 |
|------|------|------|
| **CompassEmbeddingManager** | Compass 集成管理器 | 将文本转换为向量 |
| **MilvusGlossaryService** | 术语同步服务 | 术语数据同步、向量生成、向量搜索 |
| **MilvusRulesService** | 规则同步服务 | 规则数据同步、向量生成、向量搜索 |
| **MilvusTableService** | 表同步服务 | 表数据同步、向量生成、向量搜索 |
| **CompassApiClient** | Compass API 客户端 | 调用 Compass API 生成向量 |

---

## Embedding 向量生成流程

### 1. 术语（Glossary）的 Embedding 生成

#### 数据构成

术语的 embedding 由以下数据组成：

```java
private String buildGlossaryTextContent(BusinessGlossaryDao glossary) {
    StringBuilder sb = new StringBuilder();
    sb.append("术语名称: ").append(glossary.getGlossaryName()).append("\n");
    sb.append("同义词: ").append(glossary.getSynonym()).append("\n");
    sb.append("描述: ").append(glossary.getDesc());
    return sb.toString();
}
```

**输入文本组成**：
- 🏷️ **术语名称** (`glossaryName`) - 术语的主要标识
- 🔄 **同义词** (`synonym`) - 术语的同义词表述
- 📝 **描述** (`desc`) - 术语的详细描述

**示例**：
```
术语名称: 用户
同义词: 客户,消费者
描述: 使用产品或服务的个人或组织
```

#### 流程图

```
BusinessGlossaryDao
    ↓ (buildGlossaryTextContent)
文本内容: "术语名称: ...\n同义词: ...\n描述: ..."
    ↓ (compassEmbeddingManager.textToVector)
Compass API → 向量 (384维)
    ↓ (Double → Float 转换)
List<Float> embedding (384维)
    ↓ (milvusGlossaryManager.insertGlossaryToMilvus)
Milvus 向量库存储
    ↓
向量搜索时使用
```

### 2. 规则（Rules）的 Embedding 生成

#### 数据构成

规则的 embedding 由以下数据组成：

```java
private String buildRuleTextContent(BusinessRulesDao rule) {
    return rule.getRuleDesc();
}
```

**输入文本组成**：
- 📋 **规则描述** (`ruleDesc`) - 规则的完整描述文本

**特点**：
- 相比术语，规则的向量生成更简洁
- 仅使用规则描述，不包含可变的状态标志
- 规则描述通常包含完整的业务逻辑信息

### 3. 表（Table）的 Embedding 生成

#### 数据构成

表的 embedding 由表的结构和元数据生成，具体包括：

```
表元数据内容：
- 表名 (tableName)
- 表描述 (description)
- 业务域 (businessDomain)
- 数据主题 (dataTopics)
- Schema 信息 (schema)
- 其他元数据
```

**处理流程**：
1. 用户输入查询文本
2. 查询文本通过 Compass API 转换为向量
3. 使用该向量与 Milvus 中存储的表向量进行相似性搜索
4. 返回相似度最高的表信息

---

## 数据源详解

### 1. Glossary（术语）数据源

| 字段 | 来源表 | 类型 | 作用 |
|------|--------|------|------|
| **glossaryName** | `business_glossary_tab` | String | 术语主标题 |
| **synonym** | `business_glossary_tab` | String | 同义词表述 |
| **desc** | `business_glossary_tab` | String | 详细描述 |

#### 数据获取方式

```java
public void syncGlossaryToMilvus(Long topicId, BusinessGlossaryDao glossary) {
    // 1. 从业务数据库获取 BusinessGlossaryDao
    // 2. 构建文本内容
    String textContent = buildGlossaryTextContent(glossary);
    
    // 3. 通过 Compass 转换为向量
    List<Double> embeddingDoubles = compassEmbeddingManager.textToVector(textContent);
    
    // 4. 插入到 Milvus
    milvusGlossaryManager.insertGlossaryToMilvus(topicId, glossary, embedding);
}
```

### 2. Rules（规则）数据源

| 字段 | 来源表 | 类型 | 作用 |
|------|--------|------|------|
| **ruleDesc** | `business_rules_tab` | String | 规则描述 |

#### 数据获取方式

```java
public void syncRuleToMilvus(Long topicId, BusinessRulesDao rule) {
    // 1. 从业务数据库获取 BusinessRulesDao
    // 2. 构建文本内容（仅规则描述）
    String textContent = buildRuleTextContent(rule);
    
    // 3. 通过 Compass 转换为向量
    List<Double> embeddingDoubles = compassEmbeddingManager.textToVector(textContent);
    
    // 4. 插入到 Milvus
    milvusRulesManager.insertRuleToMilvus(topicId, rule, embedding);
}
```

### 3. Table（表）数据源

| 字段 | 来源 | 类型 | 作用 |
|------|------|------|------|
| **description** | 表元数据 | String | 表描述 |
| **businessDomain** | 表元数据 | String | 业务域 |
| **dataTopics** | 表元数据 | String | 数据主题 |
| **schema** | 表元数据 | String | 表结构 |
| **tableGroupName** | 表元数据 | String | 表分组 |
| **updateFrequency** | 表元数据 | String | 更新频率 |

#### 数据获取方式

```java
public List<MilvusTableManifestDto> textVectorSearch(String queryText, int topK, String expr) {
    // 1. 查询文本
    // 2. 通过 Compass 转换为向量
    List<Double> queryVectorDouble = compassEmbeddingManager.textToVector(queryText);
    
    // 3. 在 Milvus 中搜索相似的表向量
    return vectorSearch(queryVector, topK, expr);
}
```

---

## Compass API 集成

### API 端点

```
POST /compass-api/v1/embeddings
```

### 请求结构

```java
@Data
@Builder
public class CompassEmbeddingRequest {
    /**
     * 文本列表，支持批量处理
     */
    private List<String> input;
    
    /**
     * 模型名称，默认 compass-embedding-v3
     */
    private String model;
    
    /**
     * 向量维度，默认 384
     */
    private Integer dimensions;
}
```

### 响应结构

```java
@Data
public class CompassEmbeddingResponse {
    /**
     * 向量数据列表
     */
    private List<EmbeddingData> data;
    
    /**
     * 使用统计
     */
    private Usage usage;
}

@Data
public class EmbeddingData {
    /**
     * 向量值数组
     */
    private List<Double> embedding;
    
    /**
     * 输入文本的索引
     */
    private Integer index;
    
    /**
     * 对象类型
     */
    private String object;
}
```

### 转换流程

```java
public List<Double> textToVector(String text) {
    // 1. 验证输入
    if (!StringUtils.hasText(text)) {
        throw new IllegalArgumentException("Input text cannot be null or empty");
    }

    // 2. 构建请求
    List<String> inputs = List.of(text);
    CompassEmbeddingRequest request = CompassEmbeddingRequest.builder()
        .input(inputs)
        .model(compassApiProperties.getDefaultModel())           // compass-embedding-v3
        .dimensions(compassApiProperties.getDefaultDimensions()) // 384
        .build();

    // 3. 调用 API
    CompassEmbeddingResponse response = compassApiClient.generateEmbeddings(request);

    // 4. 提取向量
    return response.getData().get(0).getEmbedding();
}
```

---

## 向量搜索机制

### 搜索流程

```
用户查询文本
    ↓
Compass API 转换为向量
    ↓
Milvus 向量相似度搜索
    ↓
返回 TopK 最相似的结果
```

### 术语搜索

```java
public List<MilvusGlossaryDao> searchGlossariesByQuery(String userQuery, int topK, String expr) {
    // 1. 将用户查询转换为向量
    List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
    List<Float> queryVector = queryVectorDoubles.stream()
        .map(Double::floatValue)
        .toList();

    // 2. 执行向量搜索
    return milvusGlossaryManager.vectorSearchFromMilvus(queryVector, topK, expr);
}
```

### 规则搜索

```java
public List<MilvusRulesDao> searchRulesByQuery(String userQuery, int topK, String expr) {
    // 1. 将用户查询转换为向量
    List<Double> queryVectorDoubles = compassEmbeddingManager.textToVector(userQuery);
    List<Float> queryVector = queryVectorDoubles.stream()
        .map(Double::floatValue)
        .toList();

    // 2. 执行向量搜索
    return milvusRulesManager.vectorSearchFromMilvus(queryVector, topK, expr);
}
```

### 表搜索

```java
public List<MilvusTableManifestDto> textVectorSearch(String queryText, int topK, String expr) {
    // 1. 将查询文本转换为向量
    List<Double> queryVectorDouble = compassEmbeddingManager.textToVector(queryText);
    
    // 2. 转换为 Float 类型（Milvus 需要）
    List<Float> queryVector = queryVectorDouble.stream()
        .map(Double::floatValue)
        .toList();
    
    // 3. 执行向量搜索
    return vectorSearch(queryVector, topK, expr);
}
```

---

## 配置参数

### Compass API 配置

```yaml
compass:
  api:
    base-url: <compass-api-url>
    api-key: <api-key>
    default-model: compass-embedding-v3
    default-dimensions: 384
```

### 配置说明

| 参数 | 值 | 说明 |
|------|-----|------|
| **base-url** | 变量 | Compass API 服务地址 |
| **api-key** | 变量 | API 认证密钥 |
| **default-model** | compass-embedding-v3 | 使用的嵌入模型 |
| **default-dimensions** | 384 | 向量维度 |

### 向量特性

- **维度**: 384 维
- **模型**: Compass Embedding v3
- **类型**: Float 类型向量
- **缓存**: 启用 API 缓存管理器

---

## 数据流向图

### 完整的数据流向

```
┌─────────────────────────────────────────────────────────────┐
│                    业务数据源                                │
│  ┌──────────────┐  ┌──────────┐  ┌─────────────────┐       │
│  │ Glossary DB  │  │ Rules DB │  │ Table Manifest  │       │
│  └──────────────┘  └──────────┘  └─────────────────┘       │
└────────────┬──────────────────────────┬────────────────────┘
             │                          │
             ↓                          ↓
    ┌────────────────┐          ┌──────────────┐
    │ 构建文本内容    │          │ 构建查询文本  │
    │ buildText...   │          │ 用户输入查询  │
    └────────────────┘          └──────────────┘
             │                          │
             └──────────┬───────────────┘
                        ↓
        ┌───────────────────────────┐
        │   CompassEmbeddingManager  │
        │   textToVector(String)     │
        └─────────────┬─────────────┘
                      ↓
        ┌───────────────────────────┐
        │   Compass Embedding API    │
        │   /compass-api/v1/         │
        │   embeddings               │
        └─────────────┬─────────────┘
                      ↓
        ┌───────────────────────────┐
        │   CompassEmbeddingResponse │
        │   List<EmbeddingData>      │
        └─────────────┬─────────────┘
                      ↓
        ┌───────────────────────────┐
        │   Double → Float 转换      │
        │   List<Float> embedding    │
        └─────────────┬─────────────┘
                      ↓
    ┌──────────────────────────────────┐
    │   Milvus 向量库存储/搜索          │
    │ ┌──────────────────────────────┐ │
    │ │ 1. 存储阶段                  │ │
    │ │    insertGlossaryToMilvus    │ │
    │ │    insertRuleToMilvus        │ │
    │ │    insertTableToMilvus       │ │
    │ │                              │ │
    │ │ 2. 搜索阶段                  │ │
    │ │    vectorSearchFromMilvus    │ │
    │ └──────────────────────────────┘ │
    └────────────┬─────────────────────┘
                 ↓
    ┌──────────────────────────────────┐
    │   返回相似搜索结果                │
    │ ┌──────────────────────────────┐ │
    │ │ List<MilvusGlossaryDao>      │ │
    │ │ List<MilvusRulesDao>         │ │
    │ │ List<MilvusTableManifestDao> │ │
    │ └──────────────────────────────┘ │
    └────────────┬─────────────────────┘
                 ↓
    ┌──────────────────────────────────┐
    │   转换为 DTO 并返回给客户端       │
    └──────────────────────────────────┘
```

---

## 总结表格

### Embedding 来源汇总

| 数据类型 | 来源表 | 文本组成 | 维度 | 用途 |
|---------|--------|--------|------|------|
| **Glossary** | business_glossary_tab | 名称+同义词+描述 | 384 | 术语搜索 |
| **Rules** | business_rules_tab | 规则描述 | 384 | 规则搜索 |
| **Table** | 表元数据 | 描述+域+主题+Schema | 384 | 表搜索 |
| **Query** | 用户输入 | 查询文本 | 384 | 搜索查询 |

### Compass API 参数

| 参数 | 值 |
|------|-----|
| 模型 | compass-embedding-v3 |
| 维度 | 384 |
| 缓存 | 启用 |

### 转换流程

1. **提取数据** - 从业务表或用户输入获取文本
2. **构建文本** - 组合相关字段形成完整描述
3. **调用 API** - 使用 Compass Embedding v3 转换为向量
4. **类型转换** - Double → Float（Milvus 格式）
5. **存储/搜索** - 在 Milvus 中存储或执行相似性搜索

