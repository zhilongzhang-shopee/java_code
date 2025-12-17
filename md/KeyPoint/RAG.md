# di-brain RAG & Text2SQL 关键流程详解

本文档详细分析 di-brain 项目中 RAG 检索、知识上下文组装、LLM 调用以及 SQL 生成与执行的完整流程。

---

## 目录

1. [RAG 结果何时送入 LLM](#1-rag-结果何时送入-llm)
2. [RAG 检索的完整流程](#2-rag-检索的完整流程)
3. [检索范围详解](#3-检索范围详解)
4. [检索产出的数据形态](#4-检索产出的数据形态)
5. [SQL 生成与执行](#5-sql-生成与执行)
6. [核心代码解析](#6-核心代码解析)

---

## 1. RAG 结果何时送入 LLM

RAG 检索出来的结果**不是直接传给 LLM**，而是被拼接进 `chat_history.msg_list` 的消息文本中，然后整个消息列表作为对话上下文发送给 LLM。

### 1.1 入口函数：`retrieve_knowledge_base_as_context`

**文件位置**：`di_brain/ask_data/graph.py`

这是 RAG 上下文检索的核心函数，负责：

1. 调用 `compose_kb_context()` 从多个知识源检索并组装上下文
2. 将检索结果写入 `SystemMessage` 或 `HumanMessage`
3. 更新 `chat_history.msg_list` 供后续 LLM 调用

```python
# di_brain/ask_data/graph.py (第 47-127 行)
def retrieve_knowledge_base_as_context(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["invoke_msg_with_llm"]]:
    logger.info("Starting retrieve_knowledge_base_as_context step")
    user_query = state.get("user_query")
    new_kb_list: list[str] = []
    new_msg_list: list[str] = []

    # 1. 处理 prefill_hive_table_ 前缀的 KB，将表名追加到用户问题中
    for kb in state.get("knowledge_base_list", []):
        if kb.startswith("prefill_hive_table_"):
            table_name = kb.replace("prefill_hive_table_", "")
            user_query = f"{user_query} \n (user mentioned table [{table_name}])"

    if state.get("knowledge_base_list"):
        # 2. 过滤出新的 KB（排除历史已检索的）
        historical_kb_list = state.get("chat_history", {}).get(
            "knowledge_base_list_history", []
        )
        new_kb_list = [
            kb for kb in state.get("knowledge_base_list", [])
            if kb not in historical_kb_list
        ]
        
        # 3. 核心检索：调用 compose_kb_context 组装上下文
        retrieve_context, related_glossaries, related_rules = (
            compose_kb_context(new_kb_list, user_query) if new_kb_list else ""
        )

        if state.get("chat_history"):
            # 4a. 有历史对话：追加 HumanMessage（RAG上下文 + 用户问题）
            new_msg_list = state.get("chat_history", {}).get("msg_list", [])
            new_msg_list.append(
                HumanMessage(content=retrieve_context + "\n" + user_query)
            )
        else:
            # 4b. 无历史对话：构造 SystemMessage（角色提示 + RAG上下文 + 搜索指令）
            new_msg_list = [
                SystemMessage(
                    content=role_prompt.format(
                        now_date=datetime.now().strftime("%Y-%m-%d"),
                        user_background_info=state.get("chat_context", {}).get(
                            "user_background_info", ""
                        ),
                    )
                    + retrieve_context
                    + search_instruct_prompt
                ),
                HumanMessage(content=user_query),
            ]
    
    # 5. 返回更新后的状态，跳转到 invoke_msg_with_llm
    return Command(
        goto="invoke_msg_with_llm",
        update={
            "user_query": user_query,
            "chat_history": AskDataHistoryInfo(
                msg_list=new_msg_list,
                knowledge_base_list_history=list(set(new_kb_list)),
            ),
            "related_docs": get_related_doc_by_kb(new_kb_list),
            "related_glossaries": related_glossaries,
            "related_rules": related_rules,
        },
    )
```

### 1.2 真正调用 LLM 的位置：`invoke_msg_with_llm`

**文件位置**：`di_brain/ask_data/graph.py`

```python
# di_brain/ask_data/graph.py (第 158-193 行)
def invoke_msg_with_llm(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["llm_res_router", "generate_final_resp"]]:
    logger.info("Starting invoke_msg_with_llm step")
    conf = Configuration.from_runnable_config(config)
    
    # 检查是否超过最大调用次数
    if (state.get("now_invoke_llm_times") 
        and state.get("now_invoke_llm_times") >= conf.max_llm_invoke):
        return Command(
            goto="generate_final_resp",
            update={
                "fail_answer_reason": "ExceedMaxInvokeTimes",
                "now_llm_answer": StructOutput(
                    data=DontKnow(reason="LLM reach max invoke times")
                ),
            },
        )
    
    # 1. 获取带结构化输出的 LLM
    structured_llm = GET_STRUCTURED_LLM(conf.model).with_structured_output(
        schema=StructOutput
    )
    
    # 2. 取出包含 RAG 上下文的消息列表
    msg_list = state.get("chat_history", {}).get("msg_list", [])
    
    # 3. ★★★ 真正调用 LLM 的地方 ★★★
    now_llm_answer = structured_llm.invoke(msg_list)
    
    # 4. 将 LLM 回答追加到消息历史
    msg_list.append(AIMessage(content=str(now_llm_answer)))
    
    return Command(
        goto="llm_res_router",
        update={
            "now_llm_answer": now_llm_answer,
            "now_invoke_llm_times": (state.get("now_invoke_llm_times") or 0) + 1,
            "chat_history": chat_history,
        },
    )
```

### 1.3 二次检索：`search_related_tables`

当 LLM 返回 `SearchMoreInfoThenAnswer` 类型的响应时，会触发二次检索，补充更多表详情：

```python
# di_brain/ask_data/graph.py (第 196-260 行)
def search_related_tables(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["invoke_msg_with_llm"]]:
    logger.info("Starting search_related_tables step")
    
    # 1. 获取合并后的 manifest
    manifest = get_merged_manifest(
        state.get("knowledge_base_list"), state.get("user_query")
    )
    
    # 2. 根据 LLM 要求搜索的表名，找到相似的表
    similar_table_manifest = manifest.find_similar_tables(
        state.get("now_llm_answer").data.search_tables
    )
    
    # 3. 获取表的详细信息
    table_details = get_table_details_by_full_table_names(
        similar_table_manifest.get_full_table_names()
    )
    
    # 4. 按用户区域偏好过滤
    user_hobby_tables = filter_table_details_by_region(
        state.get("user_hobby"),
        table_details,
    )

    # 5. 拼接表详情字符串
    res = "\n\n".join([table.to_str() for table in user_hobby_tables])
    
    # 6. 将表详情作为新的 HumanMessage 追加
    msg_list = state.get("chat_history", {}).get("msg_list", [])
    msg_list.append(
        HumanMessage(content=searched_table_detail_prompt.format(table_details=res))
    )
    
    # 7. 再次跳转到 invoke_msg_with_llm，用补充后的上下文再调用 LLM
    return Command(
        goto="invoke_msg_with_llm",
        update={
            "chat_history": chat_history,
            "related_table_manifest": related_manifest,
        },
    )
```

### 1.4 多 KB 汇总场景：`summarize`

**文件位置**：`di_brain/ask_data_global/graph.py`

当请求涉及多个 knowledge_base 时，会并行检索各个 KB，然后在 `summarize` 节点汇总：

```python
# di_brain/ask_data_global/graph.py (第 190-289 行)
def summarize(state: AskDataGlobalState, config: RunnableConfig) -> AskDataGlobalOutput:
    # 1. 获取所有搜索结果
    results = state.get("market_search_results", [])
    results = [result for result in results if result.get("has_result", False)]

    # 2. 汇总所有 KB 的检索结果
    all_tables: list[TableDetail] = []
    all_docs: list[RelatedDoc] = []
    all_glossaries: list[TopicGlossaryDto] = []
    all_rules: list[TopicRuleDto] = []

    for result in results:
        table_details = result.get("related_tables", [])
        if table_details and not is_topic_kb_name(result.get("kb_name", "")):
            table_details = filter_similar_tables(table_details)
            result["related_tables"] = table_details
        all_tables.extend(table_details)
        all_docs.extend(result.get("related_docs", []))
        all_glossaries.extend(result.get("related_glossaries", []))
        all_rules.extend(result.get("related_rules", []))
    
    # 3. 构建汇总 prompt
    prompt = f"""Please analyze and summarize the search results from different markets...
    User Query: {state["user_query"]}
    Search Results:
    {chr(10).join([
        f'''
        Knowledge Base: {result.get("market_name", "Unknown")}
        Result: {result.get("result_context", "")}
        Related Tables:
        {chr(10).join([f"- {table.idc_region}.{table.schema}.{table.table_name}" 
                       for table in result.get("related_tables", [])])}
        '''
        for result in results
    ])}
    ...
    """

    # 4. 调用 LLM 生成汇总报告
    structured_llm = GET_SPECIFIC_LLM("gpt-4.1-mini", extra_config={"disable_streaming": True})
    summary = structured_llm.invoke(prompt).content
    summary = format_table_names(summary, all_tables)

    # 5. 返回最终输出
    return Command(goto=END, update=AskDataGlobalOutput(
        related_tables=all_tables,
        related_docs=all_docs,
        related_glossaries=all_glossaries,
        related_rules=all_rules,
        result_context=summary,
        shared_agent_memory=AskDataGlobalHistoryInfo(recommend_tables=all_tables),
    ))
```

---

## 2. RAG 检索的完整流程

### 2.1 流程时序图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           RAG 检索完整流程                                    │
└─────────────────────────────────────────────────────────────────────────────┘

用户请求 (knowledge_base_list + user_query)
    │
    ▼
┌─────────────────────────────────────────┐
│  1. retrieve_knowledge_base_as_context  │
│     - 解析 knowledge_base_list          │
│     - 调用 compose_kb_context()         │
└──────────────────┬──────────────────────┘
                   │
    ┌──────────────┼──────────────┬────────────────┐
    ▼              ▼              ▼                ▼
┌────────┐  ┌────────────┐  ┌──────────┐  ┌──────────────┐
│ Mart   │  │ Table      │  │ Table    │  │ Glossary &   │
│ Doc    │  │ Manifest   │  │ Detail   │  │ Rule         │
│(MySQL) │  │ (MySQL)    │  │ (MySQL)  │  │ (KB Service) │
└────┬───┘  └─────┬──────┘  └────┬─────┘  └──────┬───────┘
     │            │               │               │
     └────────────┴───────────────┴───────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  full_context_prompt    │
              │  拼接为一段长文本        │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  写入 msg_list          │
              │  (SystemMessage /       │
              │   HumanMessage)         │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  2. invoke_msg_with_llm │
              │  structured_llm.invoke  │
              │  (msg_list)             │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  3. llm_res_router      │
              │  根据 LLM 响应类型路由   │
              └───────────┬─────────────┘
                          │
         ┌────────────────┼────────────────┐
         ▼                ▼                ▼
   DirectAnswer    SearchMoreInfo      DontKnow
   (直接回答)      (需要更多信息)       (无法回答)
         │                │                │
         ▼                ▼                ▼
  generate_final_resp  search_related_tables  generate_final_resp
         │                │                │
         │                ▼                │
         │       再次调用 LLM             │
         │       (补充表详情后)            │
         │                │                │
         └────────────────┴────────────────┘
                          │
                          ▼
                    最终输出结果
```

### 2.2 知识上下文组装：`compose_kb_context`

**文件位置**：`di_brain/ask_data/kb_context_composer.py`

这是 RAG 的核心组装函数，从四个来源聚合知识：

```python
# di_brain/ask_data/kb_context_composer.py (第 57-96 行)
def compose_kb_context(
    kb_list: List[str], user_query: str
) -> tuple[str, list[TopicGlossaryDto], list[TopicRuleDto]]:
    """Compose knowledge base context from multiple sources.

    This function combines context from three sources:
    1. Table manifests (merged manifest)
    2. Table details
    3. Documentation (mart docs)

    Args:
        kb_list: List of knowledge base names
        user_query: User's query string

    Returns:
        A concatenated string containing all context information
    """
    try:
        # 1. 获取表清单（Manifest）
        manifest_table_list = get_merged_manifest_as_context(kb_list, user_query)
        
        # 2. 获取表详情（Schema、字段、分区等）
        table_details_str_opt = get_table_details_as_context(kb_list)
        
        # 3. 获取术语表和业务规则（仅 Topic 类型 KB）
        glossary_and_rule_str, related_glossaries, related_rules = (
            get_glossary_and_rule_as_context(kb_list, user_query)
        )
        
        # 4. 获取 Mart 文档
        mart_doc_str = get_doc_as_context(kb_list)

        # 5. 使用模板拼接所有上下文
        return (
            full_context_prompt.format(
                manifest_table_list=manifest_table_list,
                table_details_str_opt=table_details_str_opt,
                glossary_and_rule_str_opt=glossary_and_rule_str,
                mart_doc_str=mart_doc_str,
            ),
            related_glossaries,
            related_rules,
        )
    except Exception as e:
        raise ValueError(f"Error composing knowledge base context: {e}")
```

### 2.3 上下文模板

```python
# di_brain/ask_data/kb_context_composer.py (第 19-40 行)
full_context_prompt = """
# Mart Doc
Here is the document can help you answer the question.

{mart_doc_str}


{glossary_and_rule_str_opt}


# Table Manifests
Here are some hive tables related to the data mart, you can search the table in the document to find the answer.

{manifest_table_list}

Note: For "Table Name" with "_{cid}" placeholder:
- If "Table Name" doesn't contain "_{cid}" placeholder, keep it unchanged.
- If "Table Name" contains "_{cid}" placeholder and user doesn't specify a region in the question, keep "_{cid}" unchanged.
- If "Table Name" contains "_{cid}" placeholder and user specifies a region in the question, replace "_{cid}" with the country code (e.g., _sg for Singapore, _br for Brazil).

{table_details_str_opt}
"""
```

---

## 3. 检索范围详解

### 3.1 MySQL 数据库：`shopee_di_rag_db`

#### 3.1.1 核心表：`knowledge_base_details_v1_5_0`

**配置位置**：`di_brain/config/default_config_json.py`

```python
"mysql_config": {
    "host": "master.e821f28ca694983e.mysql.cloud.test.shopee.io",
    "port": 6606,
    "user": "sg_di_test",
    "password": "WxLTBRO_M9rAzsL8dxHq",
    "database": "shopee_di_rag_db"
},
"ask_data": {
    "table_name": "knowledge_base_details_v1_5_0"
}
```

**数据模型**：`di_brain/ask_data/database/model.py`

```python
@dataclass
class KnowledgeBaseDetail:
    """Class representing a record from the knowledge_base_details table."""

    # Document types 文档类型常量
    TYPE_DATAMAP_TABLE_MANIFEST: ClassVar[str] = "datamap_table_manifest"   # 表清单
    TYPE_DATAMAP_TABLE_DETAIL: ClassVar[str] = "datamap_table_detail"       # 表详情(JSON)
    TYPE_DATAMAP: ClassVar[str] = "datamap"
    TYPE_CONFLUENCE: ClassVar[str] = "confluence"                            # Confluence 文档
    TYPE_GOOGLE_DOC: ClassVar[str] = "google_doc"                           # Google Doc 文档
    TYPE_DATAMART_DESC_DOC: ClassVar[str] = "datamart_desc_doc"             # Mart 描述文档
    TYPE_DATA_GROUP_DOC_SUMMARY: ClassVar[str] = "doc_summary"              # 文档摘要
    TYPE_DATAMART_SUMMARY: ClassVar[str] = "datamart_desc_doc_summary"      # Mart 摘要

    # Fields matching database columns 数据库字段
    id: Optional[int] = None
    knowledge_base_name: Optional[str] = None    # KB 名称，如 "prefill_hive_table_SG.db.table"
    source_url: str = ""                          # 原文链接
    document_type: str = ""                       # 文档类型
    title: str = ""                               # 文档标题
    index_info: str = ""                          # 索引信息
    text_content: Optional[str] = None            # ★ 核心内容字段（可能是纯文本或 JSON）
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
```

**各文档类型的用途**：

| document_type            | 用途                           | text_content 格式 |
| ------------------------ | ------------------------------ | ----------------- |
| `datamap_table_manifest` | 表清单，列出可用表及简要说明   | 纯文本            |
| `datamap_table_detail`   | 表详情（字段、分区、示例数据） | JSON              |
| `datamart_desc_doc`      | Mart 业务文档说明              | 纯文本            |
| `confluence`             | Confluence 落库的文档          | 纯文本            |
| `google_doc`             | Google Doc 落库的文档          | 纯文本            |

#### 3.1.2 辅助表：`mart_top_sql_tab`

存储各表的高频 SQL 示例，供 LLM 参考：

```python
"generate_sql": {
    "table_name": "mart_top_sql_tab"
}
```

### 3.2 Milvus 向量数据库

**配置位置**：`di_brain/hive_query.py`

#### 3.2.1 表级向量库：`di_rag_hive_table_with_ai_desc_v2`

```python
# di_brain/hive_query.py (第 135-158 行)
def get_table_retriever() -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_COLLECTION_NAME", "di_rag_hive_table_with_ai_desc_v2"
        ),
        embedding_function=get_embeddings_model(),
        vector_field="table_vector",      # 向量字段
        primary_field="uid",              # 主键：idc_region.schema.table_name
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": RETRIEVE_LIMIT,          # 100
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,
        },
    )
```

**Collection 字段**：

- `uid`：主键，格式 `idc_region.schema.table_name`
- `schema`：库名
- `table_group_name`：表分组
- `business_domain`：业务域
- `data_marts`：所属 Mart
- `description` / `ai_desc`：表描述（人工 + AI 生成）
- `table_vector`：向量字段（用于语义检索）

#### 3.2.2 表+列向量库：`di_rag_hive_table_with_columns_and_ai_desc_v2`

```python
# di_brain/hive_query.py (第 108-132 行)
def get_table_with_column_retriever() -> BaseRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_TABLE_SCHEMA_COLLECTION_NAME",
            "di_rag_hive_table_with_columns_and_ai_desc_v2",
        ),
        embedding_function=get_embeddings_model(),
        vector_field="table_vector",
        primary_field="uid",
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": RETRIEVE_LIMIT,
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,
        },
    )
```

#### 3.2.3 列级向量库：`di_rag_hive_column_info_v2`

```python
# di_brain/hive_query.py (第 161-183 行)
def get_hive_column_retriever(filter: str) -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_COLUMN_COLLECTION_NAME", "di_rag_hive_column_info_v2"
        ),
        embedding_function=get_embeddings_model(),
        vector_field="column_vector",
        primary_field="id",
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": 200,
            "expr": filter,               # 可按 table_uid 过滤
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
        },
    )
```

**Collection 字段**：

- `id`：列 ID
- `table_uid`：所属表的 uid
- `column_name`：列名
- `partition`：是否分区列
- `column_vector`：向量字段

### 3.3 Elasticsearch

#### 3.3.1 Index：`di-rag-hive-description`

```python
# di_brain/hive_query.py (第 93-105 行)
def get_es_table_retriever() -> BaseRetriever:
    bm25_retriever = ElasticsearchAdvanceRetriever.from_es_params(
        index_name="di-rag-hive-description",
        body_func=bm25_query,
        url=os.environ.get(
            "ES_HOST", "http://portal-regdi-es-717-general-test.data-infra.shopee.io:80"
        ),
        username="elastic",
        password="KgpcZdQkIhMI",
        document_mapper=es_hint_to_doc_mapper,
    )
    return bm25_retriever
```

**BM25 查询构建**：

```python
# di_brain/hive_query.py (第 50-85 行)
def bm25_query(search_query: str, metadata: Dict) -> Dict:
    search_body = {
        "query": {
            "bool": {
                "must": {"match": {"text": {"query": search_query, "fuzziness": "0"}}}
            }
        },
        "size": RETRIEVE_LIMIT,  # 100
    }

    # 支持按 data_marts / table_schemas 过滤
    filter_condition = metadata.get("retrieve_filters")
    if filter_condition:
        mart_cond = filter_condition.get("data_marts")
        schema_cond = filter_condition.get("table_schemas")
        
        if mart_cond:
            mart_cond_dict = gen_should_condition("data_marts", mart_cond)
        if schema_cond:
            schema_cond_dict = gen_should_condition("schema", schema_cond)
        
        if final_cond_dict:
            search_body["query"]["bool"]["filter"] = final_cond_dict

    return search_body
```

**ES 结果映射**：

```python
# di_brain/hive_query.py (第 38-47 行)
def es_hint_to_doc_mapper(hit: Mapping[str, Any]) -> Document:
    content = hit["_source"].pop("text")          # 文本内容
    other_properties = hit["_source"]             # 其它字段作为 metadata
    other_properties["_score"] = hit["_score"]
    other_properties["uid"] = "%s.%s.%s" % (
        other_properties["idc_region"],
        other_properties["schema"],
        other_properties["table_name"],
    )
    return Document(page_content=content, metadata=other_properties)
```

### 3.4 检索范围总结表

| 存储类型 | 位置                                             | 粒度       | 主要内容                  | RAG 用途         |
| -------- | ------------------------------------------------ | ---------- | ------------------------- | ---------------- |
| MySQL    | `shopee_di_rag_db.knowledge_base_details_v1_5_0` | KB 记录    | 表清单、表详情、Mart 文档 | 结构化 KB 上下文 |
| MySQL    | `shopee_di_rag_db.mart_top_sql_tab`              | 表级       | 高频 SQL 示例             | SQL 示例参考     |
| Milvus   | `di_rag_hive_table_with_ai_desc_v2`              | 表级       | 表描述向量                | 语义检索表       |
| Milvus   | `di_rag_hive_table_with_columns_and_ai_desc_v2`  | 表级(带列) | 表+列综合向量             | Schema 上下文    |
| Milvus   | `di_rag_hive_column_info_v2`                     | 列级       | 列描述向量                | 字段检索         |
| ES       | `di-rag-hive-description`                        | 表级       | 表描述文本                | BM25 文本检索    |

---

## 4. 检索产出的数据形态

### 4.1 表清单（Manifest）

```
# Table Manifests
Here are some hive tables related to the data mart:

| Table Name | Description | Business Domain |
|------------|-------------|-----------------|
| SG.dwd.order_fact | 订单事实表 | Order |
| SG.dim.user_dim | 用户维度表 | User |
```

### 4.2 表详情（TableDetail）

JSON 格式，包含：

- `table_name`：表名
- `schema`：库名
- `idc_region`：区域
- `table_desc`：表描述
- `columns`：字段列表（字段名、类型、是否分区、描述）
- `sample_data`：示例数据
- `integrity_score`：完整性评分

### 4.3 Mart 文档

```
doc name: Order Mart Introduction
doc content: Order Mart 包含所有订单相关的事实表和维度表...
```

### 4.4 Glossary & Rule（仅 Topic KB）

```python
# di_brain/ask_data/kb_context_composer.py (第 147-186 行)
def get_glossary_and_rule_as_context(
    kb_list: List[str], user_query: str
) -> tuple[str, list[TopicGlossaryDto], list[TopicRuleDto]]:
    if kb_list and len(kb_list) > 0:
        if is_topic_kb_name(kb_list[0]):
            topic_id = from_kb_name_to_topic_id(kb_list[0])
            glossaries = kb_topic_client.get_topic_glossaries(int(topic_id), user_query)
            rules = kb_topic_client.get_topic_rules(int(topic_id), user_query)

            glossary_str = ""
            if glossaries:
                for glossary in glossaries:
                    synonym_str = (
                        f" (synonym: {glossary.synonym})" if glossary.synonym else ""
                    )
                    glossary_str += f"Glossary: {glossary.glossary_name}{synonym_str} - {glossary.desc}\n"

            rule_str = ""
            if rules:
                for rule in rules:
                    rule_str += f"Rule: {rule.rule_desc}\n"

            return (
                glossary_and_rule_prompt.format(
                    glossary_str=glossary_str, rule_str=rule_str
                ),
                glossaries,
                rules,
            )

    return "", [], []
```

---

## 5. SQL 生成与执行

### 5.1 SQL 生成流程

**核心文件**：`di_brain/text2sql/text2sql_step.py`

SQL 生成使用 Compass 模型（专门针对 SQL 场景优化的内部模型），支持多参数并行尝试：

```python
# di_brain/text2sql/text2sql_step.py (第 72-73 行)
llm = GET_SPECIFIC_LLM("gemini-2.5-flash", extra_config={"disable_streaming": True})
llm_compass = GET_SPECIFIC_LLM("codecompass-sql")

# SQL 生成的多组参数配置（用于并行尝试）
SQL_GENERATION_COMPASS_CONFIGS = [
    {"temperature": 0.9, "topP": 0.8, "topK": 20, "repetitionPenalty": 1},
    {"temperature": 0.65, "topP": 0.84, "topK": 8, "repetitionPenalty": 1},
    {"temperature": 0.95, "topP": 0.78, "topK": 20, "repetitionPenalty": 1.02},
    {"temperature": 0.3, "topP": 0.82, "topK": 10, "repetitionPenalty": 1},
]
```

**生成节点**：`generate_sql_compass`

```python
# di_brain/text2sql/text2sql_step.py (第 1044-1095 行)
def generate_sql_compass(state: Text2SQLAskHumanState, config: RunnableConfig) -> dict:
    """Node for SQL generation with optimized parallel execution"""
    
    iteration_configs = SQL_GENERATION_COMPASS_CONFIGS

    # Step 1: 先用默认参数尝试
    logger.info("Starting SQL generation with default parameters...")
    default_result = call_compass_with_params(
        state, None, "sql_compass", True, config=config
    )

    # 如果默认 SQL 有效，直接返回
    if default_result["sql_validated"]:
        logger.info("Default SQL is valid, returning immediately")
        return {
            "sql": default_result["sql"],
            "llm_output": default_result["llm_output"],
            "sql_validated": default_result["sql_validated"],
            "error": default_result["error"],
        }

    # Step 2: 默认无效，并行尝试其他参数
    logger.info("Default SQL is invalid, submitting alternative parameters in parallel...")
    
    with ThreadPoolExecutor(max_workers=len(iteration_configs)) as executor:
        future_to_params = {
            executor.submit(
                call_compass_with_params,
                state, params, "sql_compass", True, config=config,
            ): params
            for params in iteration_configs
        }

        for future in as_completed(future_to_params):
            result = future.result()
            if result["sql_validated"]:
                # 找到有效 SQL，取消其他任务并返回
                for remaining_future in future_to_params:
                    if not remaining_future.done():
                        remaining_future.cancel()
                return result
```

### 5.2 SQL 执行

**核心文件**：`di_brain/chat_bi/starrocks_client.py`

SQL 执行由 `StarRocksClient` 负责，支持 StarRocks 和 MySQL 协议：

```python
# di_brain/chat_bi/starrocks_client.py (第 108-141 行)
def execute_sql_mysql(
    self,
    sql: str,
    catalog: str = None,
    fetch_result: bool = True,
    idc_region: str = "SG",
) -> Optional[List[Tuple]]:
    """
    通过 MySQL 协议执行 SQL 语句
    
    ✅ 支持所有 SQL 语句: CREATE, DROP, ALTER, SELECT, INSERT 等
    """
    if catalog is None:
        catalog = self.catalog

    try:
        with self.get_mysql_connection(idc_region) as conn:
            with conn.cursor() as cursor:
                cursor.execute(f"SET CATALOG {catalog};")
                cursor.execute("set sql_dialect = 'trino';")
                cursor.execute(sql)
                if fetch_result:
                    return cursor.fetchall()
                return None
    except Exception as e:
        logger.info(f"execute error: {e}")
        raise
```

### 5.3 执行触发条件

SQL 执行不是自动触发的，需要满足以下条件：

```python
# di_brain/router/tool_router.py (第 418-429 行)
final_intent: Annotated[
    Literal[
        "data_discovery",
        "generate_sql",
        "fix_sql",
        "execute_sql_and_analyze_result",   # ★ 只有这个意图才会执行
        "search_log",
    ],
    "The final, high-level intent or goal of the user's question. "
    "If the user's problem involves data search or analysis, the ultimate goal must be to Execute SQL and Analyze Results.",
]
```

---

## 6. 核心代码解析

### 6.1 LangGraph 状态流转

**ask_data 图结构**：

```
START
  │
  ▼
retrieve_knowledge_base_as_context  ──→  invoke_msg_with_llm
                                              │
                                              ▼
                                        llm_res_router
                                              │
                      ┌───────────────────────┼───────────────────────┐
                      ▼                       ▼                       ▼
              DirectAnswer           SearchMoreInfoThenAnswer     DontKnow
                      │                       │                       │
                      ▼                       ▼                       ▼
             generate_final_resp     search_related_tables    generate_final_resp
                      │                       │                       │
                      │                       ▼                       │
                      │              invoke_msg_with_llm              │
                      │                       │                       │
                      └───────────────────────┴───────────────────────┘
                                              │
                                              ▼
                                             END
```

### 6.2 LLM 结构化输出

```python
# di_brain/ask_data/state.py (第 54-80 行)
class DirectAnswer(BaseModel):
    answer: str = Field(..., description="The answer to the question")
    related_tables: list[str] = Field(
        ..., description="The tables related to the question"
    )

class SearchMoreInfoThenAnswer(BaseModel):
    search_tables: list[str] = Field(
        ..., description="The tables to search for more information"
    )

class DontKnow(BaseModel):
    reason: str = Field(..., description="The reason why the answer is not found")

class StructOutput(BaseModel):
    data: Union[DirectAnswer, SearchMoreInfoThenAnswer, DontKnow] = Field(
        ...,
        description="""
    1. if you can find the answer in the document, just answer, the data should be DirectAnswer type
    2. if you can't find the answer in the document, the data should be SearchMoreInfoThenAnswer type
    3. if the question is not related to the document, the data should be DontKnow type
    """,
    )
```

### 6.3 关键常量

```python
# di_brain/hive_query.py
RETRIEVE_LIMIT = 100                    # 单次检索最多返回 100 条
FINAL_RETURN_LIMIT = 10                 # 最终返回 10 条
TABLE_COLUMN_ORIGIN_RETRIVE_LIMIT = 22  # 列信息检索 22 条

# Milvus 向量搜索参数
"search_kwargs": {
    "k": RETRIEVE_LIMIT,
    "param": {
        "metric_type": "L2",
        "params": {"nprobe": 1200, "reorder_k": 200},
    },
    "score_threshold": 600
}
```

---

## 7. 向量数据库 Milvus 详解

### 7.1 为什么 RAG 需要向量数据库？

#### 7.1.1 核心问题：语义理解

传统的关键词检索（如 MySQL `LIKE`、ES BM25）基于**词汇匹配**，无法理解语义：

```
用户问题："哪个表存储了用户的购买记录？"

关键词检索的问题：
- 搜索 "购买记录" 可能找不到 "order_fact"（订单事实表）
- 因为 "购买" ≠ "order"，"记录" ≠ "fact"
- 即使表描述里写的是 "stores customer transactions"，也无法匹配
```

#### 7.1.2 向量检索的解决方案

向量数据库通过 **Embedding（向量嵌入）** 将文本转换为高维向量，捕捉语义信息：

```python
# di_brain/milvus/milvus_search.py (第 73-84 行)
def get_text_embedding(text, openai_client):
    """将文本转换为embedding向量"""
    # 限制文本长度
    if len(text) > 7000:
        text = text[:7000]

    embeddings = openai_client.embeddings.create(
        input=[text],
        model=COMPASS_EMBEDDING_MODEL,           # compass-embedding-v3
        dimensions=COMPASS_EMBEDDING_DIMENSIONS,  # 384 维
    )
    return embeddings.data[0].embedding
```

**工作原理**：

1. 将用户问题转换为 384 维向量
2. 在向量空间中，语义相近的文本距离更近
3. 通过 L2 距离（欧氏距离）找到最相似的表/列

```
"购买记录" → [0.12, -0.34, 0.56, ...]  (384维)
"order transactions" → [0.11, -0.32, 0.58, ...]  (384维)

两个向量在语义空间中非常接近，即使词汇完全不同！
```

#### 7.1.3 项目中的实际应用

```python
# di_brain/hive_query.py (第 135-158 行)
def get_table_retriever() -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name="di_rag_hive_table_with_ai_desc_v2",
        embedding_function=get_embeddings_model(),  # 使用 Embedding 模型
        vector_field="table_vector",
        primary_field="uid",
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": 100,                    # 返回前 100 个最相似的结果
            "param": {
                "metric_type": "L2",     # 使用 L2 距离度量
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,      # 距离阈值过滤
        },
    )
```

### 7.2 没有向量数据库会有什么问题？

#### 7.2.1 检索质量下降

| 场景                       | 只用关键词检索                | 加上向量检索                                  |
| -------------------------- | ----------------------------- | --------------------------------------------- |
| 用户问"用户画像表"         | 可能找不到 `user_profile_dim` | ✅ 语义匹配找到                                |
| 用户问"GMV 怎么算"         | 只能匹配包含 "GMV" 的文档     | ✅ 还能找到 "gross merchandise value" 相关内容 |
| 用户用中文问，表描述是英文 | ❌ 完全无法匹配                | ✅ 跨语言语义匹配                              |
| 用户表述不精确             | ❌ 召回率低                    | ✅ 模糊语义匹配                                |

#### 7.2.2 具体影响

1. **Text2SQL 准确率下降**：找不到正确的表，生成的 SQL 就会用错表
2. **数据发现失败**：用户问"有没有订单相关的表"，可能漏掉关键表
3. **多语言支持差**：中文问题无法匹配英文描述的表

#### 7.2.3 项目中的多引擎融合策略

项目采用**向量检索 + 全文检索**双引擎融合，互相补充：

```
┌─────────────────────────────────────────────────────────────┐
│                      用户问题                                │
└──────────────────────┬──────────────────────────────────────┘
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
┌─────────────────┐         ┌─────────────────┐
│  Milvus 向量检索  │         │   ES BM25 检索   │
│  (语义相似度)     │         │  (关键词匹配)     │
└────────┬────────┘         └────────┬────────┘
         │                           │
         └─────────────┬─────────────┘
                       ▼
              ┌───────────────┐
              │   结果融合     │
              │  (Rerank)     │
              └───────────────┘
```

如果去掉 Milvus：

- 只剩 ES BM25，语义理解能力大幅下降
- 召回率（Recall）可能从 90%+ 降到 60% 以下
- 用户体验显著变差

### 7.3 其他向量数据库对比

#### 7.3.1 主流向量数据库

| 向量数据库 | 开源 | 分布式 | 云原生 | 混合查询 | 主要用户           |
| ---------- | ---- | ------ | ------ | -------- | ------------------ |
| **Milvus** | ✅    | ✅      | ✅      | ✅        | 阿里、腾讯、Shopee |
| Pinecone   | ❌    | ✅      | ✅      | ✅        | OpenAI、Notion     |
| Weaviate   | ✅    | ✅      | ✅      | ✅        | Vectara            |
| Qdrant     | ✅    | ✅      | ✅      | ✅        | 初创公司           |
| Chroma     | ✅    | ❌      | ❌      | ❌        | 原型开发           |
| pgvector   | ✅    | ❌      | ❌      | ✅        | PostgreSQL 用户    |
| FAISS      | ✅    | ❌      | ❌      | ❌        | Meta、研究机构     |

#### 7.3.2 各数据库特点

**Pinecone**：

- 全托管服务，无需运维
- 按用量付费，成本可控
- 但是**闭源**，数据在第三方

**Weaviate**：

- 内置 ML 模块，支持自动向量化
- GraphQL API，查询灵活
- 社区相对较小

**Qdrant**：

- Rust 编写，性能优秀
- 支持过滤器优化
- 较新，生态不如 Milvus 成熟

**Chroma**：

- 轻量级，适合原型开发
- 单机版，不适合生产环境

**pgvector**：

- PostgreSQL 扩展，运维简单
- 性能有限，10 万级数据量适用

**FAISS**：

- Facebook 开源，算法层面优秀
- 只是库，不是数据库，无持久化/分布式支持

### 7.4 为什么选择 Milvus？

#### 7.4.1 项目需求分析

di-brain 的向量检索需求：

1. **数据规模**：16 万+ 张 Hive 表，每张表有多列，总向量数 100 万+
2. **查询性能**：毫秒级响应，支持并发
3. **混合查询**：需要同时支持向量检索 + 标量过滤（按 schema、data_mart 过滤）
4. **高可用**：生产环境，不能单点故障
5. **成本**：开源优先，减少云服务依赖

#### 7.4.2 Milvus 优势

**1. 专为大规模向量设计**

```python
# di_brain/milvus/milvus_search.py (第 107-128 行)
search_params = {
    "metric_type": "L2",
    "params": {
        "ef": 200,  # 对于16万数据量，200是一个比较平衡的值
    },
}

results = milvus_client.search(
    collection_name=COLLECTION_NAME,
    data=[query_vector],
    filter=filter_expr,      # ✅ 支持标量过滤
    limit=top_k,
    output_fields=[          # ✅ 支持返回额外字段
        "uid", "schema", "table_group_name",
        "business_domain", "data_marts", "description",
    ],
    search_params=search_params,
)
```

**2. 混合查询能力**

```python
# 向量检索 + 标量过滤（项目中的实际用法）
# di_brain/hive_query.py (第 161-183 行)
def get_hive_column_retriever(filter: str) -> MilvusWithSimilarityRetriever:
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": 200,
            "expr": filter,  # ✅ 例如 "table_uid in ['SG.dwd.order_fact']"
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
        },
    )
```

**3. 丰富的索引类型**

| 索引类型 | 适用场景         | 项目使用   |
| -------- | ---------------- | ---------- |
| HNSW     | 高精度、内存充足 | ✅ 主要使用 |
| IVF_FLAT | 平衡精度和速度   | -          |
| IVF_PQ   | 大规模、内存有限 | -          |

**4. LangChain 官方支持**

```python
# 项目直接使用 LangChain 的 Milvus 集成
from langchain_community.vectorstores import Milvus

class MilvusWithQuery(Milvus):  # 继承并扩展
    def query_by_expresion(self, expr, timeout, **kwargs):
        # 自定义查询逻辑
        ...
```

**5. 成熟的生态系统**

- LF AI & Data 基金会毕业项目（与 Linux Foundation 同级）
- 腾讯、阿里、Shopee 等大厂生产环境验证
- 活跃的社区和完善的文档

#### 7.4.3 项目中的 Milvus 配置

```python
# di_brain/config/default_config_json.py
"milvus_config": {
    "host": "milvus-sg.data-infra.shopee.io",
    "port": "19530",
    "user": "root",
    "password": "Milvus",
    "secure": false
}

# 实际使用的 Collection
COLLECTION_NAME = "di_rag_hive_table_manifest_v1"      # 表清单向量
# 或
COLLECTION_NAME = "di_rag_hive_table_with_ai_desc_v2"  # 表描述向量
COLLECTION_NAME = "di_rag_hive_column_info_v2"         # 列描述向量
```

#### 7.4.4 为什么不选其他？

| 备选方案 | 不选择的原因                                         |
| -------- | ---------------------------------------------------- |
| Pinecone | 闭源、数据出境合规问题、按量付费成本高               |
| pgvector | 16 万+ 表规模下性能不足                              |
| Chroma   | 单机版，无法支撑生产环境                             |
| FAISS    | 只是算法库，需要自建持久化、分布式等基础设施         |
| Weaviate | 生态不如 Milvus 成熟，LangChain 集成不如 Milvus 完善 |

### 7.5 向量检索的完整流程

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        向量检索完整流程                                    │
└──────────────────────────────────────────────────────────────────────────┘

1. 离线阶段（数据入库）
   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
   │ Hive 表元数据 │ → │ Embedding   │ → │ 写入 Milvus  │
   │ (描述、字段)  │    │ 模型转向量   │    │ Collection  │
   └─────────────┘    └─────────────┘    └─────────────┘
   
2. 在线阶段（查询检索）
   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
   │ 用户问题     │ → │ Embedding   │ → │  Milvus     │
   │ "订单表"     │    │ 模型转向量   │    │  向量检索    │
   └─────────────┘    └─────────────┘    └─────────────┘
                                                │
                                                ▼
                                        ┌─────────────┐
                                        │ Top-K 结果   │
                                        │ 按相似度排序  │
                                        └─────────────┘
```

**关键代码**：

```python
# di_brain/milvus/milvus_search.py (第 87-150 行)
def search_similar_tables(query_text, top_k=10, filter_expr=None):
    """通过文本查询搜索相似的表信息"""
    
    # 1. 将查询文本转换为向量
    query_vector = get_text_embedding(query_text, openai_client)

    # 2. 执行向量搜索
    search_params = {
        "metric_type": "L2",
        "params": {"ef": 200},
    }

    results = milvus_client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        filter=filter_expr,  # 可选的标量过滤
        limit=top_k,
        output_fields=["uid", "schema", "description", ...],
        search_params=search_params,
    )

    # 3. 格式化结果
    formatted_results = []
    for hit in results[0]:
        entity = hit.get("entity", {})
        result = {
            "uid": entity.get("uid"),
            "schema": entity.get("schema"),
            "description": entity.get("description"),
            "distance": hit.get("distance"),
            "score": 1 / (1 + hit.get("distance", 1)),  # 转换为相似度分数
        }
        formatted_results.append(result)

    return formatted_results
```

---

## 8. 样例 SQL 检索

### 8.1 概述

样例 SQL 检索从 MySQL 数据库中获取各表的高频 SQL 示例，供 LLM 在生成 SQL 时参考，帮助生成更符合实际使用习惯的查询语句。

### 8.2 数据源

**MySQL 表**：`mart_top_sql_tab`

```python
# di_brain/config/default_config_json.py
"generate_sql": {
    "table_name": "mart_top_sql_tab"
}
```

**表结构**：

| 字段 | 说明 |
|------|------|
| `tbl_name` | 表名 |
| `sql_content` | SQL 内容 |
| `usage_count` | 使用次数 |

### 8.3 核心代码

**文件位置**：`di_brain/ask_data/database/query.py`

```python
# di_brain/ask_data/database/query.py (第 360-423 行)
def get_table_top_sql_by_name_list(
    table_names: List[str],
) -> dict:
    """
    Fetch the top SQL queries for the given table names.

    Args:
        table_names (List[str]): A list of table names to query. 
                                 Could be idc_region.schema.table_name, 
                                 schema.table_name, table_name.

    Returns:
        dict: A dictionary of table names and their corresponding SQL content 
              strings ordered by usage count.
    """
    # 移除 IDC 区域前缀
    table_names = [_remove_prefix(name) for name in table_names]
    
    results = {}
    connection = get_connection()
    
    with connection.cursor() as cursor:
        for table_name in table_names:
            table_parts = table_name.split(".")
            
            # 查询每个表的 TOP 3 高频 SQL
            sql = f"""
                SELECT sql_content, usage_count
                FROM {TABLE_TOP_SQL_TABLE_NAME}
                WHERE tbl_name = %s
                ORDER BY usage_count DESC
                LIMIT 3
            """
            cursor.execute(sql, (table_parts[1],))
            rows = cursor.fetchall()
            
            # 提取 SQL 内容
            results[table_name] = [row["sql_content"] for row in rows]
    
    return results
```

### 8.4 调用时机

在 Text2SQL 流程的 `process_context_and_table_samples` 节点中调用：

```python
# di_brain/text2sql/text2sql_step.py (第 669-678 行)
if not chat_bi_follow_up:
    # Step 2: Fetch sample SQL
    try:
        table_sample_sql = get_table_top_sql_by_name_list(table_titles)
        if isinstance(table_sample_sql, str):
            logger.warning(f"Error fetching sample SQL: {table_sample_sql}")
            table_sample_sql = "No sample SQL available."
        state["sample_sql"] = table_sample_sql
    except Exception as error:
        logger.error(f"Error fetching sample SQL: {error}")
```

### 8.5 在 Prompt 中的使用

样例 SQL 会被注入到 LLM Prompt 的 `<context>` 部分：

```python
# di_brain/text2sql/text2sql_prompt.py
"""
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 
"""
```

### 8.6 输出示例

```python
{
    "dwd.order_fact": [
        "SELECT order_id, user_id, gmv FROM dwd.order_fact WHERE grass_date = '2024-01-01'",
        "SELECT COUNT(*) FROM dwd.order_fact WHERE status = 'completed'",
        "SELECT SUM(gmv) FROM dwd.order_fact GROUP BY region"
    ],
    "dim.user_dim": [
        "SELECT * FROM dim.user_dim WHERE user_id = 12345",
        "SELECT user_name, register_date FROM dim.user_dim LIMIT 100"
    ]
}
```

---

## 9. 样例数据检索

### 9.1 概述

样例数据检索从 KB Service API 获取各表的预览数据（每列的样例值），帮助 LLM 理解数据的实际格式和取值范围，从而生成更准确的 SQL（如日期格式、枚举值等）。

### 9.2 数据源

**KB Service API**：

```python
# di_brain/kb/kb_client.py (第 334-382 行)
def get_sample_data(self, schema: str, table_name: str) -> List[Dict[str, Any]]:
    """
    获取样本数据

    获取指定Hive表的样本数据，包括列信息和预览数据
    优先从本地数据库获取，如果不存在则调用DataMap API获取实时数据

    Args:
        schema: 数据库schema名称
        table_name: 表名

    Returns:
        样本列数据列表（原始JSON数据）
    """
    # 构建API端点URL
    endpoint = f"{self.base_url}/sample-data/{schema}/{table_name}"
    
    response = requests.get(endpoint, headers=self.headers, timeout=60)
    response.raise_for_status()
    
    resp_json = response.json()
    result = resp_json.get("data", [])
    
    return result
```

### 9.3 核心代码

**文件位置**：`di_brain/tools/datamap_table_sample_tool.py`

#### 9.3.1 获取原始样例数据

```python
# di_brain/tools/datamap_table_sample_tool.py (第 23-106 行)
def get_table_sample_data(
    table_full_names: Union[str, List[str]],
    hadoop_account: str,
) -> Union[str, List[Dict]]:
    """
    Fetch sample data for the given list of table full names.

    Args:
        table_full_names: A list of table full names or a comma-separated string.
                         Table name could be idc_region.schema.table_name 
                         or schema.table_name

    Returns:
        A list of dictionaries containing table names and their sample data.
    """
    # 移除 IDC 区域前缀
    req_tables = [_remove_prefix(item) for item in table_full_names]

    def fetch_table_data(table):
        table_parts = table.split(".")
        if len(table_parts) != 2:
            return None
        
        # 使用 kb_client 获取样本数据
        sample_data = kb_client.get_sample_data(table_parts[0], table_parts[1])
        return {table: sample_data}

    # 并行获取多个表的样例数据
    ret = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=100) as executor:
        future_to_table = {}
        for table in req_tables:
            future = executor.submit(fetch_table_data, table)
            future_to_table[future] = table
            time.sleep(DATAMAP_REQUEST_INTERVAL)  # 避免 QPS 限制
        
        for future in future_to_table:
            result = future.result()
            if result:
                ret.append(result)

    return ret
```

#### 9.3.2 格式化样例数据

```python
# di_brain/tools/datamap_table_sample_tool.py (第 109-156 行)
def extract_table_sample_data(
    sample_data_info: any,
    limit_preview_data: bool = False,
) -> Union[str, List[Dict]]:
    """
    Extract and format table sample data.

    Returns:
        A formatted representation of the table sample data.
    """
    if isinstance(sample_data_info, list):
        ret = []
        for item in sample_data_info:
            for table_name, columns in item.items():
                table_data = {"table_name": table_name, "columns": []}

                for column in columns:
                    column_name = column.get("name", "unknown_column")
                    column_type = column.get("dataType", "unknown_type")
                    column_preview_data = column.get("previewData", [])
                    
                    # 限制预览数据条数
                    if limit_preview_data and len(column_preview_data) > 3:
                        column_preview_data = column_preview_data[:3]

                    table_data["columns"].append({
                        "column_name": column_name,
                        "column_type": column_type,
                        "preview_data": column_preview_data,
                    })

                ret.append(table_data)
        return ret

    return str(sample_data_info)
```

#### 9.3.3 完整调用链

```python
# di_brain/tools/datamap_table_sample_tool.py (第 159-166 行)
def get_table_sample_data_generate_sql(
    table_list: Union[str, List[str]],
    hadoop_account: str,
    limit_preview_data: bool = False,
) -> Union[str, List[Dict]]:
    """获取样例数据并格式化，用于 SQL 生成"""
    raw_result = get_table_sample_data(table_list, hadoop_account)
    processed_result = extract_table_sample_data(raw_result, limit_preview_data)
    return processed_result
```

### 9.4 调用时机

在 Text2SQL 流程的 `process_context_and_table_samples` 节点中调用：

```python
# di_brain/text2sql/text2sql_step.py (第 680-698 行)
# Step 3: Fetch sample data
if state.get("use_compass", False):
    state["sample_data"] = []  # Compass 模型不使用样例数据
else:
    try:
        start_time = time.time()
        table_sample_data = get_table_sample_data_generate_sql(
            table_titles, hadoop_account, LIMIT_PROMPT_TOKEN
        )
        end_time = time.time()
        logger.info(
            f"Text2SQL graph: get table sample data took {end_time - start_time:.2f} seconds"
        )
        # 应用 Token 限制
        table_sample_data = apply_prompt_token_limit(
            table_sample_data, MAX_SAMPLE_DATA_TOKENS
        )
        state["sample_data"] = table_sample_data
    except Exception as error:
        logger.error(f"Error fetching sample data by API: {error}")
```

### 9.5 输出示例

```python
[
    {
        "table_name": "dwd.order_fact",
        "columns": [
            {
                "column_name": "order_id",
                "column_type": "BIGINT",
                "preview_data": [1001, 1002, 1003]
            },
            {
                "column_name": "grass_date",
                "column_type": "STRING",
                "preview_data": ["2024-01-01", "2024-01-02", "2024-01-03"]
            },
            {
                "column_name": "status",
                "column_type": "STRING",
                "preview_data": ["completed", "pending", "cancelled"]
            },
            {
                "column_name": "gmv",
                "column_type": "DOUBLE",
                "preview_data": [99.99, 199.50, 50.00]
            }
        ]
    },
    {
        "table_name": "dim.user_dim",
        "columns": [
            {
                "column_name": "user_id",
                "column_type": "BIGINT",
                "preview_data": [12345, 12346, 12347]
            },
            {
                "column_name": "user_name",
                "column_type": "STRING",
                "preview_data": ["Alice", "Bob", "Charlie"]
            }
        ]
    }
]
```

### 9.6 在 Prompt 中的作用

样例数据帮助 LLM 理解：

| 信息类型 | 作用 | 示例 |
|---------|------|------|
| **日期格式** | 正确写日期条件 | `grass_date = '2024-01-01'` 而非 `'20240101'` |
| **枚举值** | 正确写枚举过滤 | `status = 'completed'` 而非 `status = 'done'` |
| **数据类型** | 避免类型转换错误 | 知道 `order_id` 是 BIGINT，不会写 `order_id = '1001'` |
| **取值范围** | 合理设置条件 | 知道 GMV 范围后，不会写 `gmv > 1000000` |

---

## 10. 样例 SQL 与样例数据的流程图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   样例 SQL 与样例数据检索流程                                  │
└─────────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────────────┐
                    │   find_data_docs        │
                    │   (相关表列表)           │
                    └───────────┬─────────────┘
                                │
                                ▼
             ┌──────────────────────────────────────┐
             │   process_context_and_table_samples   │
             │   (上下文处理与样例获取)               │
             └───────────┬────────────┬─────────────┘
                         │            │
           ┌─────────────┘            └─────────────┐
           ▼                                        ▼
┌─────────────────────────┐            ┌─────────────────────────┐
│  get_table_top_sql_by   │            │  get_table_sample_data  │
│  _name_list             │            │  _generate_sql          │
│                         │            │                         │
│  数据源: MySQL          │            │  数据源: KB Service API │
│  mart_top_sql_tab       │            │  /sample-data/{s}/{t}   │
└───────────┬─────────────┘            └───────────┬─────────────┘
            │                                      │
            │  ┌────────────────────┐              │  ┌────────────────────┐
            │  │ {                  │              │  │ [{                 │
            │  │   "dwd.order":     │              │  │   "table_name":    │
            │  │   ["SELECT ...",   │              │  │   "dwd.order",     │
            │  │    "SELECT ..."]   │              │  │   "columns": [     │
            │  │ }                  │              │  │     {"column_name":│
            │  └────────────────────┘              │  │      "grass_date", │
            │                                      │  │      "preview_data"│
            │                                      │  │      ["2024-01-01"]│
            │                                      │  │     }              │
            │                                      │  │   ]                │
            │                                      │  │ }]                 │
            │                                      │  └────────────────────┘
            │                                      │
            └──────────────┬───────────────────────┘
                           │
                           ▼
            ┌──────────────────────────────────┐
            │         state["sample_sql"]       │
            │         state["sample_data"]      │
            └───────────────┬──────────────────┘
                            │
                            ▼
            ┌──────────────────────────────────┐
            │      注入到 LLM Prompt           │
            │                                  │
            │  <context>                       │
            │    {context}                     │
            │    Sample SQLs: {sample_sql}     │
            │    Sample Data: {sample_data}    │
            │  <context/>                      │
            └───────────────┬──────────────────┘
                            │
                            ▼
            ┌──────────────────────────────────┐
            │          LLM 生成 SQL            │
            │   参考样例 SQL 和样例数据         │
            └──────────────────────────────────┘
```

---

## 11. RAG 检索的详细机制

### 11.1 检索目标

**RAG 检索的目标不是用户的请求本身，而是通过用户的问题作为"查询"，去检索相关的知识上下文**。

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         RAG 检索目标架构                                          │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  用户问题（Query）                                                               │
│  "查询最近7天各区域的GMV"                                                        │
│           │                                                                      │
│           ▼                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                        检索目标（Retrieval Targets）                      │   │
│  ├─────────────────────────────────────────────────────────────────────────┤   │
│  │                                                                          │   │
│  │  1. 相关表格元数据（Table Metadata）                                     │   │
│  │     - 表名、表描述、字段列表、分区信息                                    │   │
│  │     - 例：dwd.order_fact, dwd.region_dim                                │   │
│  │                                                                          │   │
│  │  2. 业务文档（Business Documents）                                       │   │
│  │     - Confluence 文档、Google Doc                                       │   │
│  │     - Mart 描述文档、数据字典                                            │   │
│  │                                                                          │   │
│  │  3. 术语表（Glossary）                                                   │   │
│  │     - GMV = Gross Merchandise Value = 交易总额                          │   │
│  │     - 同义词映射                                                         │   │
│  │                                                                          │   │
│  │  4. 业务规则（Business Rules）                                           │   │
│  │     - GMV 计算规则：不包含取消订单                                       │   │
│  │     - 日期过滤规则：使用 grass_date 而非 create_time                     │   │
│  │                                                                          │   │
│  │  5. 样例 SQL（Sample SQL）                                               │   │
│  │     - 历史高频 SQL 查询示例                                              │   │
│  │                                                                          │   │
│  │  6. 样例数据（Sample Data）                                              │   │
│  │     - 各列的预览值，帮助理解数据格式                                      │   │
│  │                                                                          │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│           │                                                                      │
│           ▼                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │                     组装后的上下文（Context）                             │   │
│  │  传入 LLM，辅助生成 SQL 或回答问题                                       │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 11.2 完整检索流程架构

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          RAG 检索完整流程                                        │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  用户请求："查询最近7天各区域的GMV"                                             │
│           │                                                                      │
│           ▼                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ Step 1: data_discovery_tool / find_data                                  │   │
│  │         触发数据发现流程                                                  │   │
│  └───────────────────────────────────────────────────────────────────────┬─┘   │
│                                                                           │      │
│  ┌────────────────────────────────────────────────────────────────────────┼─┐   │
│  │                         多路并行检索                                    │ │   │
│  │  ┌───────────────────────────────────────────────────────────────────┐ │ │   │
│  │  │                                                                    │ │ │   │
│  │  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐    │ │ │   │
│  │  │  │  Milvus 向量检索  │  │   ES BM25 检索   │  │   MySQL 直接查   │    │ │ │   │
│  │  │  │  (语义相似度)     │  │  (关键词匹配)     │  │  (KB 详情)       │    │ │ │   │
│  │  │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘    │ │ │   │
│  │  │           │                     │                    │             │ │ │   │
│  │  │           │   表级向量库         │   表描述索引        │  knowledge_  │ │ │   │
│  │  │           │   列级向量库         │   di-rag-hive-     │  base_details│ │ │   │
│  │  │           │                     │   description      │             │ │ │   │
│  │  │           │                     │                    │             │ │ │   │
│  │  └───────────┴─────────────────────┴────────────────────┴─────────────┘ │ │   │
│  │                                    │                                      │ │   │
│  └────────────────────────────────────┼──────────────────────────────────────┘ │   │
│                                       │                                          │   │
│                                       ▼                                          │   │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ Step 2: 结果融合与重排序                                                  │   │
│  │         - 去重、按相关度排序                                              │   │
│  │         - 返回 Top-K 结果                                                │   │
│  └───────────────────────────────────────────────────────────────────────┬─┘   │
│                                                                           │      │
│                                       ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ Step 3: compose_kb_context                                               │   │
│  │         组装知识上下文                                                    │   │
│  │                                                                          │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │   │
│  │  │ Table        │  │ Table        │  │ Glossary &   │  │ Mart         │ │   │
│  │  │ Manifest     │  │ Details      │  │ Rules        │  │ Documents    │ │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘ │   │
│  │         └─────────────────┴─────────────────┴─────────────────┘         │   │
│  │                                    │                                     │   │
│  │                                    ▼                                     │   │
│  │                          full_context_prompt                            │   │
│  └───────────────────────────────────────────────────────────────────────┬─┘   │
│                                                                           │      │
│                                       ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ Step 4: process_context_and_table_samples                                │   │
│  │         (Text2SQL 流程) 获取样例 SQL 和样例数据                          │   │
│  │                                                                          │   │
│  │         sample_sql  ← mart_top_sql_tab (MySQL)                          │   │
│  │         sample_data ← KB Service API                                    │   │
│  └───────────────────────────────────────────────────────────────────────┬─┘   │
│                                                                           │      │
│                                       ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────────┐   │
│  │ Step 5: 写入 msg_list，传给 LLM                                          │   │
│  └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 11.3 各检索引擎的具体实现

#### 11.3.1 Milvus 向量检索

**用途**：通过语义相似度找到与用户问题相关的表和列

```python
# di_brain/hive_query.py (第 135-158 行)
def get_table_retriever() -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name="di_rag_hive_table_with_ai_desc_v2",  # 表描述向量库
        embedding_function=get_embeddings_model(),            # compass-embedding-v3
        vector_field="table_vector",
        primary_field="uid",  # idc_region.schema.table_name
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": 100,                          # 返回 Top 100
            "param": {
                "metric_type": "L2",           # 欧氏距离
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,            # 距离阈值
        },
    )
```

**检索过程**：

```
用户问题 "查询GMV"
    │
    ▼
┌─────────────────────────────────────────────┐
│  Embedding 模型（compass-embedding-v3）      │
│  将文本转换为 384 维向量                      │
│  "查询GMV" → [0.12, -0.34, 0.56, ...]        │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│  Milvus 向量检索                              │
│  在 di_rag_hive_table_with_ai_desc_v2 中     │
│  找到向量距离最近的 Top 100 条记录            │
└─────────────────────────────────────────────┘
    │
    ▼
返回结果：
- SG.dwd.order_gmv_fact (距离: 0.15)
- SG.dwd.order_detail (距离: 0.28)
- ...
```

#### 11.3.2 Elasticsearch BM25 检索

**用途**：通过关键词匹配找到包含特定词汇的表描述

```python
# di_brain/hive_query.py (第 93-105 行)
def get_es_table_retriever() -> BaseRetriever:
    bm25_retriever = ElasticsearchAdvanceRetriever.from_es_params(
        index_name="di-rag-hive-description",
        body_func=bm25_query,  # BM25 查询构建函数
        url="http://portal-regdi-es-717-general-test.data-infra.shopee.io:80",
        username="elastic",
        password="***",
        document_mapper=es_hint_to_doc_mapper,
    )
    return bm25_retriever
```

**BM25 查询构建**：

```python
# di_brain/hive_query.py (第 50-85 行)
def bm25_query(search_query: str, metadata: Dict) -> Dict:
    search_body = {
        "query": {
            "bool": {
                "must": {
                    "match": {
                        "text": {
                            "query": search_query,
                            "fuzziness": "0"  # 精确匹配
                        }
                    }
                }
            }
        },
        "size": 100,  # 返回 Top 100
    }
    
    # 支持按 data_marts / schema 过滤
    filter_condition = metadata.get("retrieve_filters")
    if filter_condition:
        # 添加过滤条件...
        pass
    
    return search_body
```

#### 11.3.3 MySQL 直接查询

**用途**：根据 knowledge_base_name 直接获取 KB 详情

```python
# di_brain/ask_data/database/query.py
def get_kb_details_by_type(
    kb_name: str, 
    doc_types: List[str]
) -> List[KnowledgeBaseDetail]:
    """
    从 knowledge_base_details_v1_5_0 表查询指定类型的文档
    
    doc_types 可以是:
    - datamap_table_manifest  # 表清单
    - datamap_table_detail    # 表详情 (JSON)
    - datamart_desc_doc       # Mart 文档
    - confluence              # Confluence 文档
    """
    sql = f"""
        SELECT * FROM {TABLE_NAME}
        WHERE knowledge_base_name = %s
          AND document_type IN ({','.join(['%s'] * len(doc_types))})
    """
    # 执行查询...
```

### 11.4 多路检索融合机制

#### 11.4.1 检索触发入口

```python
# di_brain/router/tool_router.py (第 2390-2460 行)
def data_discovery_tool(
    user_query: str,
    knowledge_base_list: List[str],
    hadoop_account: str,
) -> dict:
    """数据发现工具 - RAG 检索的核心入口"""
    
    # 1. 调用 ask_data_global 图
    result = ask_data_global_graph.invoke({
        "user_query": user_query,
        "knowledge_base_list": knowledge_base_list,
    })
    
    # 2. 提取检索结果
    return {
        "related_tables": result.get("related_tables", []),      # 相关表列表
        "related_docs": result.get("related_docs", []),          # 相关文档
        "related_glossaries": result.get("related_glossaries", []),  # 术语表
        "related_rules": result.get("related_rules", []),        # 业务规则
        "result_context": result.get("result_context", ""),      # 检索结果摘要
    }
```

#### 11.4.2 ask_data_global 图结构

```python
# di_brain/ask_data_global/graph.py (第 30-100 行)
def build_ask_data_global_graph():
    """构建全局数据发现图"""
    
    graph = StateGraph(AskDataGlobalState)
    
    # 节点 1: 并行检索各个 Knowledge Base
    graph.add_node("parallel_search_markets", parallel_search_markets)
    
    # 节点 2: 汇总检索结果
    graph.add_node("summarize", summarize)
    
    # 流程: START → parallel_search_markets → summarize → END
    graph.add_edge(START, "parallel_search_markets")
    graph.add_edge("parallel_search_markets", "summarize")
    graph.add_edge("summarize", END)
    
    return graph.compile()
```

#### 11.4.3 parallel_search_markets 实现

```python
# di_brain/ask_data_global/graph.py (第 105-188 行)
def parallel_search_markets(
    state: AskDataGlobalState, 
    config: RunnableConfig
) -> dict:
    """并行检索多个 Knowledge Base"""
    
    kb_list = state.get("knowledge_base_list", [])
    user_query = state.get("user_query")
    
    results = []
    
    # 并行调用每个 KB 的检索
    with ThreadPoolExecutor(max_workers=10) as executor:
        future_to_kb = {
            executor.submit(
                search_single_market,  # 单个 KB 的检索函数
                kb_name,
                user_query,
            ): kb_name
            for kb_name in kb_list
        }
        
        for future in as_completed(future_to_kb):
            result = future.result()
            results.append(result)
    
    return {"market_search_results": results}
```

#### 11.4.4 单个 KB 的检索流程

```python
# di_brain/ask_data_global/graph.py (内部函数)
def search_single_market(kb_name: str, user_query: str) -> dict:
    """单个 Knowledge Base 的检索"""
    
    # 1. 获取表清单（Manifest）
    manifest = get_merged_manifest([kb_name], user_query)
    
    # 2. 语义检索相关表
    #    内部调用 Milvus + ES 双引擎
    related_tables = manifest.search_similar_tables(user_query)
    
    # 3. 获取表详情
    table_details = get_table_details_by_full_table_names(
        [t.full_name for t in related_tables]
    )
    
    # 4. 获取相关文档
    related_docs = get_related_doc_by_kb([kb_name])
    
    # 5. 获取术语和规则（仅 Topic KB）
    glossaries, rules = get_glossary_and_rule_by_kb(kb_name, user_query)
    
    return {
        "kb_name": kb_name,
        "related_tables": table_details,
        "related_docs": related_docs,
        "related_glossaries": glossaries,
        "related_rules": rules,
        "has_result": len(table_details) > 0,
    }
```

### 11.5 向量检索的核心实现

#### 11.5.1 MilvusWithSimilarityRetriever

```python
# di_brain/vectorstores/milvus_retriever.py
class MilvusWithSimilarityRetriever(BaseRetriever):
    """带相似度分数的 Milvus 检索器"""
    
    vectorstore: Milvus
    search_kwargs: dict = {}
    
    def _get_relevant_documents(
        self, 
        query: str, 
        *, 
        run_manager: CallbackManagerForRetrieverRun
    ) -> List[Document]:
        """执行检索"""
        
        # 1. 调用 Milvus 的相似度搜索
        docs_and_scores = self.vectorstore.similarity_search_with_score(
            query, 
            **self.search_kwargs
        )
        
        # 2. 过滤低分结果
        threshold = self.search_kwargs.get("score_threshold", float("inf"))
        filtered_docs = [
            doc for doc, score in docs_and_scores 
            if score <= threshold
        ]
        
        # 3. 将分数写入 metadata
        for doc, score in docs_and_scores:
            doc.metadata["_score"] = score
        
        return filtered_docs
```

#### 11.5.2 Embedding 模型

```python
# di_brain/llms/embedding.py
def get_embeddings_model():
    """获取 Embedding 模型"""
    return OpenAIEmbeddings(
        model="compass-embedding-v3",       # Shopee 内部 Embedding 模型
        openai_api_key=COMPASS_API_KEY,
        openai_api_base=COMPASS_API_BASE,
        dimensions=384,                      # 384 维向量
    )
```

### 11.6 检索结果的去重与排序

```python
# di_brain/ask_data_global/graph.py (第 220-240 行)
def filter_similar_tables(table_details: List[TableDetail]) -> List[TableDetail]:
    """
    去重和过滤相似表
    - 按表名去重
    - 按相关度分数排序
    - 返回 Top 10
    """
    seen = set()
    unique_tables = []
    
    for table in table_details:
        table_key = f"{table.schema}.{table.table_name}"
        if table_key not in seen:
            seen.add(table_key)
            unique_tables.append(table)
    
    # 按分数排序
    unique_tables.sort(key=lambda t: t.metadata.get("_score", 0))
    
    return unique_tables[:10]  # 返回 Top 10
```

### 11.7 检索结果示例

```python
# 输入
user_query = "查询最近7天各区域的GMV"
knowledge_base_list = ["topic_order_analysis"]

# 输出
{
    "related_tables": [
        TableDetail(
            schema="dwd",
            table_name="order_gmv_fact",
            idc_region="SG",
            table_desc="订单 GMV 事实表，按天聚合",
            columns=[
                {"name": "grass_date", "type": "STRING", "partition": True},
                {"name": "region", "type": "STRING"},
                {"name": "gmv", "type": "DOUBLE"},
            ],
            _score=0.15,
        ),
        TableDetail(
            schema="dim",
            table_name="region_dim",
            idc_region="SG",
            table_desc="区域维度表",
            columns=[
                {"name": "region_code", "type": "STRING"},
                {"name": "region_name", "type": "STRING"},
            ],
            _score=0.32,
        ),
    ],
    "related_docs": [
        RelatedDoc(
            title="Order Mart User Guide",
            content="GMV 计算规则：不包含取消订单...",
        ),
    ],
    "related_glossaries": [
        TopicGlossaryDto(
            glossary_name="GMV",
            desc="Gross Merchandise Value，交易总额",
            synonym="交易额,销售额",
        ),
    ],
    "related_rules": [
        TopicRuleDto(
            rule_desc="GMV 计算时需排除 status='cancelled' 的订单",
        ),
    ],
}
```

---

## 总结

1. **RAG 检索**：通过 `compose_kb_context` 从 MySQL KB 表、Milvus 向量库、ES 全文索引聚合知识
2. **上下文注入**：检索结果被拼接进 `SystemMessage/HumanMessage`，写入 `chat_history.msg_list`
3. **LLM 调用**：`invoke_msg_with_llm` 将整个 `msg_list` 传给 LLM，LLM 返回结构化响应
4. **迭代检索**：若 LLM 需要更多信息，触发 `search_related_tables` 补充表详情后再次调用
5. **SQL 生成**：使用 Compass 模型，支持多参数并行尝试，确保生成有效 SQL
6. **SQL 执行**：由 `StarRocksClient` 执行，需用户显式意图 `execute_sql_and_analyze_result` 才触发
7. **向量数据库**：Milvus 提供语义检索能力，是 RAG 系统实现"理解用户意图"的关键组件
8. **样例 SQL 检索**：从 `mart_top_sql_tab` 表获取高频 SQL，帮助 LLM 学习实际查询模式
9. **样例数据检索**：从 KB Service 获取列预览数据，帮助 LLM 理解数据格式和枚举值
10. **多路检索融合**：Milvus（语义） + ES（关键词） + MySQL（结构化）三路并行检索，结果融合后传给 LLM
