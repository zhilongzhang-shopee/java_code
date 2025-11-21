# Milvus Embedding 向量生成 - 快速参考指南

## 🎯 核心问题快速解答

### Q1: Milvus 存储的 Embedding 向量由哪些数据生成？

**A: 三类主要数据源**

| 数据类型 | 数据来源 | 具体字段 | 文本示例 |
|---------|--------|--------|--------|
| **Glossary (术语)** | business_glossary_tab | `glossaryName` `synonym` `desc` | "术语名称: 用户\n同义词: 客户,消费者\n描述: 使用产品的个人" |
| **Rules (规则)** | business_rules_tab | `ruleDesc` | "用户订单超过100元时获得10%折扣" |
| **Table (表)** | 表元数据 | `description` `businessDomain` `schema` 等 | 通过用户查询获取 |

---

### Q2: 如何通过 Compass 转化为向量？

**A: 简单三步**

```
第一步: 构建文本内容
       ↓
       术语: "术语名称: 用户\n同义词: 客户,消费者\n描述: ..."
       规则: "用户订单超过100元时获得10%折扣"

第二步: 调用 Compass Embedding API v3
       ↓
       POST /compass-api/v1/embeddings
       {
         "input": ["文本内容"],
         "model": "compass-embedding-v3",
         "dimensions": 384
       }

第三步: 获取向量结果
       ↓
       List<Double> embedding = [0.123, -0.456, ..., 0.789]
       (共 384 个浮点数)
```

---

### Q3: 向量存储在 Milvus 中的完整流程？

**A: 5 个关键步骤**

```
业务数据
   ↓ (1. 提取数据)
BusinessGlossaryDao { glossaryName, synonym, desc }
   ↓ (2. 构建文本)
String textContent = "术语名称: 用户\n同义词: 客户\n描述: ..."
   ↓ (3. 转换向量)
CompassEmbeddingManager.textToVector(textContent)
   → Compass API 处理
   → 返回 List<Double>
   ↓ (4. 类型转换)
List<Float> embedding = Double → Float
   ↓ (5. 存储到 Milvus)
milvusGlossaryManager.insertGlossaryToMilvus(topicId, glossary, embedding)
```

---

## 📊 数据来源详细表格

### 术语 (Glossary)

| 字段名 | 数据库表 | 字段类型 | 示例值 |
|--------|---------|---------|--------|
| glossaryName | business_glossary_tab | VARCHAR | 用户 |
| synonym | business_glossary_tab | VARCHAR | 客户,消费者 |
| desc | business_glossary_tab | TEXT | 使用产品或服务的个人或组织 |

**构建文本方法**:
```java
"术语名称: " + glossaryName + "\n" +
"同义词: " + synonym + "\n" +
"描述: " + desc
```

### 规则 (Rules)

| 字段名 | 数据库表 | 字段类型 | 示例值 |
|--------|---------|---------|--------|
| ruleDesc | business_rules_tab | TEXT | 用户订单超过100元时获得10%折扣 |

**构建文本方法**:
```java
ruleDesc  // 直接使用规则描述
```

### 表 (Table)

| 字段名 | 数据源 | 示例值 |
|--------|--------|--------|
| description | 表元数据 | 用户订单表，记录所有订单信息 |
| businessDomain | 表元数据 | 销售 |
| dataTopics | 表元数据 | 订单,交易 |
| schema | 表元数据 | 列名,类型,说明 |

---

## 🔄 向量维度和模型配置

```
模型: compass-embedding-v3
维度: 384
类型: Float 类型向量
范围: -1.0 到 1.0（通常）
缓存: 启用 API 缓存管理器
```

---

## 💡 常见操作速查

### 操作 1: 同步数据到 Milvus（术语示例）

```java
// 场景: 新增或更新一条术语

@Service
public class GlossarySyncService {
    @Autowired
    private MilvusGlossaryService milvusGlossaryService;
    
    public void syncNewGlossary(Long topicId, BusinessGlossaryDao glossary) {
        // 一行代码完成：提取数据 → 构建文本 → 调用 Compass → 转换向量 → 存储 Milvus
        milvusGlossaryService.syncGlossaryToMilvus(topicId, glossary);
    }
}
```

### 操作 2: 搜索相似的术语

```java
// 场景: 用户查询"用户是什么"

@Service
public class GlossarySearchService {
    @Autowired
    private MilvusGlossaryService milvusGlossaryService;
    
    public List<MilvusGlossaryDao> searchGlossary(String userQuery) {
        // 一行代码完成：查询文本 → 转换向量 → Milvus 搜索 → 返回结果
        return milvusGlossaryService.searchGlossariesByQuery(userQuery, 5, null);
        //                                                    ↑    ↑    ↑
        //                                                 查询  Top-K 过滤条件
    }
}
```

### 操作 3: 删除术语

```java
// 场景: 删除某个话题下的术语

@Service
public class GlossaryDeleteService {
    @Autowired
    private MilvusGlossaryService milvusGlossaryService;
    
    public void deleteGlossary(Long topicId, Long glossaryId) {
        // 从指定话题删除
        milvusGlossaryService.deleteGlossaryFromMilvus(topicId, glossaryId);
        
        // 或从所有话题删除
        milvusGlossaryService.deleteGlossaryFromMilvusAllTopics(glossaryId);
    }
}
```

### 操作 4: 搜索表

```java
// 场景: 用户查询"用户订单信息"

@Service
public class TableSearchService {
    @Autowired
    private MilvusTableService milvusTableService;
    
    public List<MilvusTableManifestDto> searchTables(String queryText) {
        // 一行代码完成：查询文本 → 转换向量 → Milvus 搜索 → 返回表信息
        return milvusTableService.textVectorSearch(queryText, 10, "region == 'SG'");
        //                                                      ↑    ↑
        //                                                   Top-K 过滤条件
    }
}
```

---

## 🏗️ 系统架构流程

### 数据写入流程

```
业务事件（新增/更新术语）
    ↓
MilvusGlossaryService.syncGlossaryToMilvus()
    ├─ buildGlossaryTextContent() → 构建文本
    ├─ compassEmbeddingManager.textToVector() → 转换向量
    │   └─ CompassApiClient.generateEmbeddings() → 调用 API
    ├─ Double → Float 类型转换
    └─ MilvusGlossaryManager.insertGlossaryToMilvus() → 存储
    
Milvus Collection: glossary_collection
{
  id, glossary_id, topic_id, glossary_name, synonym, 
  description, embedding(384维)
}
```

### 数据查询流程

```
用户查询输入："用户是什么"
    ↓
MilvusGlossaryService.searchGlossariesByQuery()
    ├─ compassEmbeddingManager.textToVector() → 转换查询向量
    │   └─ CompassApiClient.generateEmbeddings() → 调用 API
    ├─ Double → Float 类型转换
    └─ MilvusGlossaryManager.vectorSearchFromMilvus() → 相似度搜索
    
Milvus 相似度搜索（余弦相似度）
    → 返回 Top-K 相似的术语
    
返回结果给用户
[
  {glossaryId: 101, glossaryName: "用户", similarity: 0.95},
  {glossaryId: 102, glossaryName: "客户", similarity: 0.87},
  ...
]
```

---

## ⚙️ 配置参数一览

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `compass.api.base-url` | 环境变量 | Compass API 服务地址 |
| `compass.api.api-key` | 环境变量 | API 认证密钥 |
| `compass.api.default-model` | compass-embedding-v3 | 使用的嵌入模型 |
| `compass.api.default-dimensions` | 384 | 向量维度 |
| `milvus.host` | 环境变量 | Milvus 服务地址 |
| `milvus.port` | 19530 | Milvus 服务端口 |

---

## 🔍 关键代码位置

| 功能 | 文件路径 | 关键方法 |
|------|---------|--------|
| 文本转向量 | `CompassEmbeddingManager.java` | `textToVector(String)` |
| 术语同步 | `MilvusGlossaryService.java` | `syncGlossaryToMilvus()` |
| 术语搜索 | `MilvusGlossaryService.java` | `searchGlossariesByQuery()` |
| 规则同步 | `MilvusRulesService.java` | `syncRuleToMilvus()` |
| 规则搜索 | `MilvusRulesService.java` | `searchRulesByQuery()` |
| 表搜索 | `MilvusTableService.java` | `textVectorSearch()` |
| API 集成 | `CompassApiClient.java` | `generateEmbeddings()` |

---

## 📈 向量搜索的工作原理

### 相似度计算

```
用户查询: "用户是什么" → 查询向量 Q = [0.123, -0.456, ..., 0.789]

Milvus 中的存储:
  术语1: 向量 V1 = [0.120, -0.455, ..., 0.785]
  术语2: 向量 V2 = [0.100, -0.400, ..., 0.750]
  术语3: 向量 V3 = [0.050, -0.200, ..., 0.600]

余弦相似度 = dot(Q, V) / (|Q| * |V|)

计算结果:
  similarity(Q, V1) = 0.95  ← 最相似
  similarity(Q, V2) = 0.87
  similarity(Q, V3) = 0.72

返回 Top-5:
  1. 术语1 (相似度 0.95)
  2. 术语2 (相似度 0.87)
  3. ...
```

---

## 🚀 最佳实践

### 1. 文本内容构建

✅ **推荐**:
```java
// 包含多个维度的信息
StringBuilder sb = new StringBuilder();
sb.append("术语名称: ").append(glossaryName).append("\n");
sb.append("同义词: ").append(synonym).append("\n");
sb.append("描述: ").append(desc);
```

❌ **不推荐**:
```java
// 只用一个字段
return glossaryName;  // 信息不足
```

### 2. 向量数据管理

✅ **推荐**:
```java
// 数据变更时同时更新向量
dataChanged.subscribe(event -> {
    milvusGlossaryService.syncGlossaryToMilvus(event.topicId, event.glossary);
});
```

❌ **不推荐**:
```java
// 仅更新数据库，忘记更新向量
database.update(glossary);  // 向量不同步，搜索结果不准确
```

### 3. 搜索参数调优

```java
// topK 参数
searchGlossariesByQuery(query, 5, null);   // 返回 Top-5，快速响应
searchGlossariesByQuery(query, 20, null);  // 返回 Top-20，更全面

// expr 参数（过滤条件）
searchGlossariesByQuery(query, 5, "topic_id == 1001");  // 仅搜索特定话题
```

---

## 📚 相关文档

- **详细分析**: `Milvus-Embedding-Analysis.md`
- **代码示例**: `Milvus-Embedding-Code-Examples.md`
- **源代码**: Diana Knowledge Base 项目

