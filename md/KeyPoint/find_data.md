# DI-Brain Find Data 流程详解

## 一、核心概念

### 1. Find Data 是什么？
Find Data（数据查找/数据发现）是 DI-Brain 系统中的核心功能，用于帮助用户在海量数据表中快速定位所需的数据资源。它是一个**多层级、多策略的数据发现系统**，包含以下关键环节：

- **数据范围确认（Data Scope Clarification）**：确定用户查询涉及的 DataMart 或 DataTopic
- **表级别发现（Table Discovery）**：在确定的范围内进行表检索
- **元数据聚合（Metadata Aggregation）**：汇总表结构、列信息、描述等详细信息
- **结果总结（Result Summarization）**：生成用户友好的查询结果

---

## 二、完整流程架构

### 2.1 整体流程图

```
用户查询 
  ↓
[意图识别 & 问题预处理]
  ↓
[数据范围确认 - Data Scope Clarification]
  ├─ 如果明确指定 Mart/Topic → 直接使用
  ├─ 如果不明确 → LLM 分析 → 多选项确认或自动选择
  └─ 若仍未匹配 → Fallback 到 Layer2 表
  ↓
[检索 Knowledge Base 中的表]
  ├─ 向量搜索（Milvus）→ 基于 Embedding 的语义搜索
  ├─ 全文搜索（ES）→ 关键词匹配
  └─ 融合重排（Fusion Rerank）→ 综合多个排序维度
  ↓
[表级过滤 & 地域处理]
  ├─ 按用户地域偏好筛选（SG/USEast）
  ├─ 过滤相似表（去重）
  └─ 应用业务规则过滤
  ↓
[元数据聚合 & 总结]
  ├─ 获取表的详细信息（列、描述、PIC等）
  ├─ LLM 总结多个表的相关信息
  └─ 格式化输出
  ↓
用户最终结果
```

---

## 三、关键组件详解

### 3.1 数据范围确认（Data Scope Clarification）

**位置**: `di_brain/data_scope_agent/data_scope_clarification_agent.py`

#### 工作流程：

```python
# 1. 检索可用的数据范围（Scopes）
- 获取所有 DataMart（Order Mart, Item Mart, User Mart 等）
- 获取所有 DataTopic（ChatBI 主题）
- 构建范围描述文本供 LLM 分析

# 2. LLM 分析用户查询
- 使用 gpt-4.1-mini 进行结构化分析
- 输出三种结果类型：
  - scope_identified: 成功识别 1～4 个相关范围
  - insufficient_info: 信息不足，需要向用户询问
  - no_match: 无匹配范围，将 fallback 到 Layer2 表

# 3. 确认用户选择
- 若找到单个范围 → 自动确认，无需用户干预
- 若找到 2～3 个范围 → 
  - 使用 LLM_DECISION 策略：LLM 自动选择最相关的
  - 使用 USER_MAKE_SURE 策略：向用户提示选择
- 若找到 4 个及以上范围 → 必须向用户提示选择
- 若信息不足 → 生成澄清问题，ask_human
```

**关键特点**：
- 使用 **LLM 结构化输出** 确保分析的准确性
- 支持两种调度策略：LLM 自动决策 vs 用户确认
- 优先级规则：明确提及 Mart 名称 > 语义匹配 > 需要澄清
- 存在 **Fallback 机制**：若无匹配，自动降级到 Layer2 表（通用表层）

---

### 3.2 表级别检索系统

**位置**: `di_brain/ask_data/graph.py` 和 `di_brain/hive_query.py`

#### 检索策略对比

| 策略 | 实现方式 | 优势 | 劣势 |
|------|--------|------|------|
| **向量搜索** | Milvus + Embedding | 语义理解能力强，捕捉表意义 | 依赖 Embedding 质量，冷启动表检索效果一般 |
| **全文搜索** | Elasticsearch | 关键词匹配精准，快速 | 无法理解语义，易漏掉语义相关表 |
| **融合重排** | RRF（倒数排名融合）+ 多维规则 | 综合多个信号，结果更全面 | 复杂度高，计算成本增加 |

#### 检索细节

```python
# 1. 向量搜索（Milvus）
search_params = {
    "metric_type": "L2",  # 欧氏距离
    "params": {
        "ef": 200,         # 搜索集合大小，影响精度和速度
        "nprobe": 1200,    # 搜索的聚类数量
        "reorder_k": 200   # 重排的候选数量
    }
}
score_threshold = 600  # 相似度分数阈值，过高会漏表，过低会引入噪音

# 2. 全文搜索
# 使用 BM25 算法进行关键词匹配

# 3. 融合重排（Reciprocal Rank Fusion）
# RRF 公式：score = Σ(1 / (rank_i + k))
# k = 60（平衡参数）
```

#### 多维度重排规则

重排维度按优先级排序：

1. **向量搜索得分**（L2 距离转相似度）
2. **查询热度**（过去 30 天查询次数）- query_count_30d
3. **用户地域匹配**（表的市场地域与用户地域一致度）
4. **表激活状态**（active > inactive）
5. **DataMart 归属**（有 DataMart 信息 > 无）
6. **表可见性**（visibility=true > false）
7. **Schema 匹配**（如果提取了 Schema 列表）
8. **用户最近 7 天查询次数**（个性化排序）

**融合策略**：
```python
fusion_score[table_id] = Σ(1.0 / (fusion_k_list[i] + rank_in_list_i))
# 多个排序列表的分数相加，权重为 1/(k + rank)
```

---

### 3.3 问题预处理

**位置**: `di_brain/ask_data/graph.py` - `retrieve_knowledge_base_as_context` 函数

```python
# 1. 如果用户已明确指定表
if "@mention_table" in user_question:
    # 自动转换为 knowledge_base_list，供下游使用
    # 格式："prefill_hive_table_SG.schema.table_name"

# 2. 构建知识库检索指令
retrieve_context = compose_kb_context(knowledge_base_list, user_query)
# 包含：
#   - 表的详细元数据
#   - 业务定义（Glossary）
#   - 业务规则（Rules）

# 3. 准备 LLM 消息
messages = [
    SystemMessage(role_prompt + retrieve_context + search_instruct_prompt),
    HumanMessage(user_query)
]
```

**关键作用**：
- 将用户输入转换为结构化的知识库查询
- 将已获取的元数据注入 LLM 上下文，提高后续生成质量

---

### 3.4 全局数据发现管道

**位置**: `di_brain/ask_data_global/graph.py`

#### 设计特点：

```python
# 并行处理多个 Knowledge Base
# 特例：Hive Table KB 可聚集处理

tasks = []

# 1. 分离 Hive Table 知识库与其他知识库
hive_table_kbs = [kb for kb in knowledge_base_list if "prefill_hive_table" in kb]
other_kbs = [kb for kb in knowledge_base_list if "prefill_hive_table" not in kb]

# 2. Hive Table 知识库打包为一个任务
if hive_table_kbs:
    Send("search_subgraph", {
        "knowledge_base_list": hive_table_kbs,
        ...
    })

# 3. 其他知识库各自为一个任务
for kb in other_kbs:
    Send("search_subgraph", {
        "knowledge_base_list": [kb],
        ...
    })

# 4. 结果聚合与总结
# - 合并所有表结果
# - 使用 LLM 生成统一摘要
# - 格式化输出为 Markdown
```

**优势**：
- 并行处理多个数据源，提高效率
- Hive Table 聚合处理，减少冗余搜索
- 最终统一总结，保证结果的一致性

---

### 3.5 结果聚合与总结

**位置**: `di_brain/ask_data_global/graph.py` - `summarize` 函数

```python
def summarize(state: AskDataGlobalState):
    # 1. 过滤相似表
    if is_hive_table:
        table_details = filter_similar_tables(table_details)
    
    # 2. 收集所有表信息
    all_tables = []
    all_docs = []
    all_glossaries = []
    all_rules = []
    
    for result in market_search_results:
        all_tables.extend(result["related_tables"])
        all_docs.extend(result["related_docs"])
        ...
    
    # 3. LLM 生成总结
    prompt = f"""
    请分析搜索结果，生成用户友好的摘要。
    
    搜索结果包含：
    - 相关表：{all_tables}
    - 业务文档：{all_docs}
    - 业务术语：{all_glossaries}
    - 业务规则：{all_rules}
    
    生成要求：
    - Markdown 格式
    - 表名使用 *idc_region.schema.table* 格式
    - 简洁清晰，不超过 2000 tokens
    """
    
    summary = gpt_4_mini.invoke(prompt)
    
    # 4. 格式化输出
    summary = format_table_names(summary, all_tables)
    
    return {
        "result_context": summary,
        "related_tables": all_tables,
        "related_docs": all_docs,
        "related_glossaries": all_glossaries,
        "related_rules": all_rules
    }
```

**关键机制**：
- **相似表去重**：移除表名只在地区代码上不同的表（如 table_br vs table_sg）
- **LLM 总结**：确保输出的专业性和准确性
- **格式标准化**：统一表名格式，避免混乱

---

## 四、提升 Find Data 准确率的方法

### 4.1 数据范围确认层面

#### 当前改进：
1. **明确名称优先级**
   - 当用户明确提及 Mart/Topic 名称时，立即返回该范围
   - 不再进行其他语义匹配

2. **相似 Mart 区分**
   - User Mart vs Seller Mart
   - Chatbot Mart vs Customer Service Mart
   - 当不确定时，同时返回两者供用户选择

3. **Fallback 策略**
   - 若无匹配 → 自动降级到 Layer2 表（通用表层）
   - 而非直接告诉用户"无相关数据"

#### 可优化方向：
- [ ] **多轮对话记忆**：保留前一轮对话的选择，当新查询相似时自动应用
- [ ] **用户偏好学习**：记录用户常用的 Mart，自动优先推荐
- [ ] **上下文感知**：分析对话历史，推断用户当前关注的业务域
- [ ] **同义词匹配**：建立 Mart 别名库（如"交易"→"Order Mart"）
- [ ] **行业知识库**：为不同部门用户定制 Mart 推荐列表

---

### 4.2 向量检索层面

#### 当前配置：
```python
# Milvus 搜索参数
ef = 200          # 搜索集合大小，当前为通用值
nprobe = 1200     # 搜索聚类数，较大确保覆盖率
reorder_k = 200   # 重排候选数，保留更多重新排序空间
score_threshold = 600  # 相似度分数阈值

# Embedding 模型
使用 Compass Embedding v3 或类似高质量模型
```

#### 可优化方向：

**1. Embedding 模型选择与优化**
- [ ] **使用专用的表 Embedding 模型**：当前 Embedding 是通用模型，可训练数据表专用 Embedding
- [ ] **多模态 Embedding**：结合表名、列名、描述、AI 生成概要的多源信息
- [ ] **动态 Embedding 更新**：定期重新计算 Embedding，保持新表的检索效果
- [ ] **Embedding 微调**：用已标注的（查询, 相关表）对进行微调

**2. 搜索参数动态调整**
```python
# 根据用户地域、查询特征动态调整
if user_region == "regional":
    ef = 150  # 地域通用表，搜索空间可缩小
    score_threshold = 500
else:
    ef = 250  # 特定地域，需要更细致搜索
    score_threshold = 550
    
if len(query_tokens) > 20:  # 长查询，更可能需要精细搜索
    nprobe = 1500
    reorder_k = 300
```

**3. 混合检索优化**
```python
# 当前：RRF 融合得分

# 可改进：
# - 权重学习：使用 LambdaMART 等学习排序算法优化权重
# - 多阶段检索：
#   - 第一阶段：快速召回（ES + 粗粒度向量搜索）
#   - 第二阶段：精排（微调向量 + BM25）
#   - 第三阶段：LLM 重排（基于语义相关性和用户意图）
```

**4. 查询扩展（Query Expansion）**
```python
# 原始查询："How to find buyer demographics"
# 扩展查询：
#   - "buyer demographics user attributes"
#   - "customer profile gender age location"
#   - "user segmentation buyer characteristics"
# 分别搜索后融合结果，提高覆盖率
```

---

### 4.3 重排策略优化

#### 当前重排维度（8 个）：
1. 向量搜索得分 ✓
2. 查询热度 ✓
3. 用户地域匹配 ✓
4. 表激活状态 ✓
5. DataMart 归属 ✓
6. 表可见性 ✓
7. Schema 匹配 ✓
8. 用户最近查询 ✓

#### 可添加的新维度：
- [ ] **表更新频率**：最近更新的表优先（提示数据新鲜度）
- [ ] **数据质量评分**：表级别的数据质量评分（来自 Data Governance）
- [ ] **用户访问权限**：过滤掉用户无权访问的表
- [ ] **表级别/分层**：ODS/DWD/DWS/ADS 层级匹配（用户查询特征匹配）
- [ ] **表成熟度评分**：新表 vs 成熟表的评分权重
- [ ] **上下文关联度**：基于对话历史，计算与前序表的关联度
- [ ] **人工标注信号**：收集用户点击、反馈数据，进行监督学习排序

#### 权重优化方向：
```python
# 当前：固定权重 1/(k+rank)

# 可改进：
# 使用 LambdaMART 等学习排序算法，基于标注数据优化权重
# 为不同类型的查询学习不同的权重组合

# 例如：
# - 查询类型 = "table_discovery" → 权重偏向热度 + 质量
# - 查询类型 = "column_search" → 权重偏向 Schema 匹配
# - 查询类型 = "semantic_search" → 权重偏向向量相似度
```

---

### 4.4 LLM 分析优化

#### 当前配置：
```python
# 数据范围确认
model: "gpt-4.1-mini"
temperature: 0.1  # 低温度，确保确定性输出

# 结果总结
model: "gpt-4.1-mini"
temperature: 0.1

# 提示词质量
使用结构化的 Few-shot 例子指导 LLM
```

#### 可优化方向：

**1. 选择更强的模型**
```python
# 当前模型：gpt-4.1-mini（快速、成本低）

# 可选择：
# - gpt-4.1（更强推理能力，成本增加 2-3 倍）
# - o3-mini（突破性推理能力，成本增加 5 倍，但准确率显著提升）
# - 混合策略：
#   - 简单查询用 gpt-4.1-mini
#   - 复杂查询自动升级到 gpt-4.1
#   - 关键场景用 o3-mini
```

**2. 提示词工程**
```python
# 优化数据范围确认提示词

# 当前：字面意思匹配

# 可改进：
# - 添加反例（"这 NOT User Mart"）
# - 添加业务场景示例
# - 明确歧义处理方法
# - 添加确信度评分指引

example_prompt = """
...
示例 1：
查询："用户的购买力如何"
Mart 候选：User Mart, Order Mart, Item Mart
分析：购买力涉及订单金额（Order Mart）和用户属性（User Mart），
      但由于询问的是"用户的"购买力，核心是用户维度分析，
      应优先选择 User Mart，辅助使用 Order Mart。
结果：["User Mart", "Order Mart"]

示例 2：
查询："最近 7 天的销售额趋势"
分析：销售额 = 订单金额，趋势分析，明确的 Order Mart 特征
结果：["Order Mart"]
...
"""
```

**3. 结果总结优化**
```python
# 优化摘要生成质量

# 方向 1：引入表排序信息
# 当前总结：随机列举表
# 改进：按相关性排序，最相关的表优先展示

# 方向 2：生成多层级摘要
# - 快速摘要（1-2 句，最核心的 1-2 张表）
# - 详细摘要（完整列表）
# - 专家摘要（包含表间关系、数据血缘）

# 方向 3：引入表推荐理由
# 当前：仅列表
# 改进：
#   "## 推荐使用 order_mart.dws_gmv_1d
#    - 原因 1：完全匹配您的"GMV"关键词（相似度 0.95）
#    - 原因 2：过去 30 天被查询 1,200+ 次（热度高）
#    - 原因 3：数据质量评分 9.2/10（成熟度高）"
```

---

### 4.5 用户交互优化

#### 当前交互方式：
- 自动确认单个范围
- 用户选择 2-4 个范围（多选）
- 澄清问题（开放式）

#### 可优化方向：

**1. 智能澄清问题生成**
```python
# 当前：生成通用的澄清问题

# 改进：
# - 基于无法识别的关键词生成细化问题
# - 例如："我理解您在询问'流量'，这可能指：
#   (a) 网站访问量/点击量 → 推荐 Traffic Mart
#   (b) 物流配送流量 → 推荐 SPX Mart
#   (c) 订单支付流量 → 推荐 Order Mart
#   请选择您关注的'流量'类型"

# 用结构化选项替代开放式问题，降低用户认知负担
```

**2. 渐进式信息收集**
```python
# 当前：一次性问出所有不确定的信息

# 改进：分轮收集，每轮一个问题，基于用户回答不断调整
# - 第 1 轮：明确数据范围
# - 第 2 轮：明确时间维度（实时 vs 离线聚合）
# - 第 3 轮：明确具体指标或维度
```

**3. 反馈循环**
```python
# 添加用户反馈机制
# - 若用户认为推荐的表不相关 → 记录为负样本
# - 若用户点击了某个表 → 记录为正样本
# - 定期使用反馈数据微调模型
```

---

### 4.6 系统架构优化

#### 当前瓶颈：
1. **冷启动**：新用户、新查询无个性化信息
2. **延迟**：多步骤串联（Scope Clarify → Retrieve → Rank → Summarize）
3. **成本**：多次 LLM 调用

#### 优化方案：

**1. 缓存策略**
```python
# 添加多层缓存
缓存层 1：查询相似度缓存
  key = hash(normalized_query)
  value = {top_10_tables, related_docs, ...}
  TTL = 7 天

缓存层 2：用户上下文缓存
  key = user_id
  value = {preferred_marts, recent_tables, ...}
  TTL = 30 天

缓存层 3：表元数据缓存
  key = table_id
  value = {columns, description, PIC, ...}
  TTL = 1 天（定期更新）

# 对于 80% 的重复查询，可直接返回缓存结果，节省 50-70% 延迟
```

**2. 异步流水线**
```python
# 当前：串联处理
请求 → Scope Clarify → Retrieve → Rank → Summarize → 返回结果
(每步都需要等待前一步完成)

# 改进：并行化
- 初始 Scope Clarify 同时触发多个 Retriever（Milvus + ES）
- 获得初步结果后立即返回给用户（快速反馈）
- 后台异步完成重排和总结，通过推送更新完整结果
```

**3. 智能 LLM 调度**
```python
# 当前：所有操作统一使用 gpt-4.1-mini

# 改进：
task_type = infer_task_type(query)

if task_type == "simple_keyword_match":
    # 不调用 LLM，直接 ES 搜索
    result = es_search(query)
elif task_type == "clear_intent":
    # 快速模型 + 缓存
    result = gpt_4_mini_with_cache(query)
elif task_type == "ambiguous_intent":
    # 强模型 + 澄清
    result = gpt_4_with_clarification(query)
elif task_type == "complex_reasoning":
    # 推理模型
    result = o3_mini_inference(query)

# 根据查询复杂度自动选择，平衡成本和质量
```

---

## 五、关键配置参数对准确率的影响

### 5.1 向量搜索参数

| 参数 | 当前值 | 影响 | 调优建议 |
|------|--------|------|---------|
| `ef` (搜索集合大小) | 200 | 越大 → 覆盖率↑、延迟↑ | 通用 200，特定地域可 150-250 |
| `nprobe` (搜索聚类数) | 1200 | 越大 → 覆盖率↑、成本↑ | 对总表数的 0.5-1% |
| `reorder_k` (重排候选数) | 200 | 越大 → 精度↑、成本↑ | 保留初始结果的 2-5 倍 |
| `score_threshold` (分数阈值) | 600 | 越高 → 漏表风险↑ | 根据 AP/NDCG 指标调优 |

### 5.2 LLM 参数

| 参数 | 当前值 | 影响 | 调优建议 |
|------|--------|------|---------|
| `temperature` | 0.1 | 越低 → 输出确定性↑、多样性↓ | Scope Clarify: 0.0-0.1，总结: 0.1-0.3 |
| `model` | gpt-4.1-mini | 模型能力 | 根据准确率要求选择 |
| `top_p` | 默认(0.9) | 采样多样性 | 保持默认或调低到 0.8 |

### 5.3 重排权重

```python
# 当前权重分配（相对）
weights = {
    "embedding_score": 1.0,
    "query_count_30d": 1.0,
    "region_match": 1.0,
    "table_status": 1.0,
    "datamart_presence": 1.0,
    "visibility": 1.0,
    "schema_match": 1.0,
    "user_recent_7d": 1.0,
}

# 根据评估指标调整：
# 若发现热表被推荐优先级过高 → 降低 query_count 权重
# 若发现语义相关性差 → 提升 embedding_score 权重
# 若地域相关查询效果差 → 提升 region_match 权重
```

---

## 六、评估指标

### 6.1 主要指标

| 指标 | 定义 | 目标值 |
|------|------|--------|
| **MRR@10** | 相关表在前 10 中的平均排名倒数 | > 0.8 |
| **NDCG@5** | 前 5 个结果的 DCG 标准化值 | > 0.7 |
| **Hit Rate@1** | 第 1 个结果正确的概率 | > 60% |
| **Hit Rate@5** | 前 5 个结果包含正确表的概率 | > 85% |
| **Recall@10** | 在前 10 个结果中召回相关表的比例 | > 80% |

### 6.2 业务指标

| 指标 | 定义 | 目标值 |
|------|------|--------|
| **用户确认率** | 用户接受系统推荐的比例 | > 75% |
| **点击率** | 用户点击推荐表的比例 | > 40% |
| **澄清率** | 需要澄清的查询比例 | < 20% |
| **一轮成功率** | 一轮查询就找到目标表的比例 | > 70% |

---

## 七、实战案例与问题排查

### 7.1 常见问题场景

**场景 1**：用户查询"销售额"，但系统推荐了"Order Mart 的 GMV 表"

**原因分析**：
- Embedding 中文分词问题：可能分词为"销" "售" "额"
- Synonym 问题：GMV 与"销售额"没有被映射为同义词
- Prompt 问题：LLM 没有正确理解"销售额"的业务含义

**解决方案**：
```python
# 方案 1：改进 Embedding（中长期）
# - 使用支持中文的 Embedding 模型
# - 使用 BGE-M3（中文效果好的模型）

# 方案 2：建立同义词库（短期快速）
synonyms = {
    "销售额": ["gmv", "成交额", "销售额", "交易额"],
    "订单数": ["order_count", "订单数", "单量"],
    "用户数": ["user_count", "活跃用户", "新增用户"],
}

def expand_query(query):
    expanded = [query]
    for token in tokenize(query):
        if token in synonyms:
            expanded.extend(synonyms[token])
    return expanded

# 方案 3：改进 Prompt（立即可行）
prompt = """
...
用户查询可能用到这些同义词表达：
- "销售额" = "成交额" = "交易额" = "GMV"
- "订单数" = "单量"
- "用户数" = "活跃用户"

请根据同义词库匹配数据范围
...
"""
```

**场景 2**：多 Mart 查询时选择错误

**原因分析**：
- 用户隐含信息不足
- LLM 对业务域理解有偏差
- 没有考虑用户的部门背景

**解决方案**：
```python
# 方案 1：引入用户背景信息
context = {
    "user_email": "buyer_ops@shopee.com",
    "department": "Buyer Operations",  # 采购相关
    "user_background_info": "负责买家运营的数据分析"
}

# 在 Prompt 中使用：
# "用户所在部门为采购，常关注买家相关数据"
# → 优先推荐 User Mart, 其次 Order Mart

# 方案 2：保存用户偏好
user_preferences = {
    "frequent_marts": ["User Mart", "Order Mart"],  # 历史常用
    "preferred_time_grain": "daily",  # 偏好日粒度
    "preferred_region": "SG",  # 偏好新加坡数据
}

# 方案 3：多轮对话记忆
# 若用户在同一对话中连续查询，保留前一轮的 Mart 选择，作为当轮的默认值
```

**场景 3**：Embedding 性能下降（新表检索效果差）

**原因分析**：
- 新表的 Embedding 信息不够充分
- Embedding 模型过时，未及时更新
- 新表的描述文本质量低

**解决方案**：
```python
# 方案 1：丰富表信息
new_table = {
    "name": "fact_buyer_purchase_behavior",
    "description": "记录买家的购买行为，包括浏览、加购、购买等关键行为"
    "ai_summary": "该表通过多维度记录买家从浏览到最终购买的全过程行为..."  # AI 生成摘要
    "keywords": ["买家", "购买", "行为", "转化", "用户旅程"],
    "related_marts": ["User Mart", "Order Mart"],
    "sample_queries": [
        "分析买家购买转化率",
        "识别高价值买家行为",
        ...
    ]
}

# 方案 2：定期重新计算 Embedding
# - 每周全量更新一次所有表的 Embedding
# - 新表优先级更高，确保及时被计算

# 方案 3：混合检索回退
if embedding_result_quality < 0.5:
    # Embedding 效果差，使用 ES 全文搜索补充
    es_results = es_search(query)
    combined_results = fusion_rrf(embedding_results, es_results)
```

---

## 八、总结与建议

### 8.1 短期优化（1-2 周）

1. **完善同义词库**：手工维护常见业务术语的同义词映射
2. **改进提示词**：加入更多反例和业务背景说明
3. **添加用户反馈机制**：让用户标记推荐是否相关
4. **监控关键指标**：NDCG@5, Hit Rate@1, 澄清率

### 8.2 中期优化（1-2 月）

1. **收集训练数据**：(查询, 相关表) 对标注
2. **优化 Embedding**：尝试中文友好的模型或微调当前模型
3. **学习排序权重**：使用 LambdaMART 优化重排权重
4. **缓存系统**：实现多层缓存加速热查询
5. **A/B 测试框架**：对比不同策略的效果

### 8.3 长期优化（1 个季度+）

1. **多模态 Embedding**：结合表名、列名、业务领域的联合 Embedding
2. **个性化推荐**：基于用户/部门的查询历史和偏好
3. **表推荐理由生成**：为每个推荐生成可解释的理由
4. **智能 LLM 调度**：根据查询复杂度动态选择模型
5. **闭环反馈系统**：自动收集反馈，持续改进

---

## 九、关键代码文件索引

| 功能 | 文件路径 |
|------|--------|
| 数据范围确认 | `di_brain/data_scope_agent/data_scope_clarification_agent.py` |
| 表级检索 | `di_brain/ask_data/graph.py`, `di_brain/hive_query.py` |
| 全局管道 | `di_brain/ask_data_global/graph.py` |
| 向量搜索 | `di_brain/milvus/milvus_search.py` |
| 重排融合 | `di_brain/hive_query.py` → `generate_rerank_list`, `fusion_rerank_v2` |
| 提示词 | `di_brain/data_scope_agent/prompt.py`, `di_brain/router/common_agent_prompt.py` |
| 工具入口 | `di_brain/router/tool_router.py` → `find_data`, `data_discovery`, `detect_data_domain` |

---

**文档版本**: v1.0  
**最后更新**: 2024-12-12  
**作者**: AI 助手

