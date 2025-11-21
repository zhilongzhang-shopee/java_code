# DI-Brain 项目代码结构分析

本文档旨在分析 DI-Brain 项目的整体代码结构，解释各个模块的功能和关键代码实现。

## 项目根目录文件分析

项目根目录包含项目的基本配置、文档和依赖管理等文件。

- `pyproject.toml`: 定义了项目的依赖和元数据，使用 Poetry进行管理。
- `poetry.lock`: 锁定了项目依赖的精确版本，保证环境一致性。
- `Dockerfile`: 用于构建项目的 Docker 镜像。
- `.gitignore`: 指定了 Git 版本控制中需要忽略的文件和目录。
- `README.md`: 项目的说明文档。
- `Makefile`: 包含了一些常用的命令，如安装、测试等。
- `DEPLOYMENT.md`: 部署说明文档。
- `benchmark/`: 包含了一些基准测试脚本和数据。
- `di_brain/`: 项目的核心代码目录。

## `di_brain` 核心模块分析

`di_brain` 目录是项目的核心，包含了所有的业务逻辑和功能实现。

### 文件夹结构概览

以下是 `di_brain` 目录下的主要文件夹及其功能概览：

- `ask_data/`: 处理数据查询的核心逻辑。
- `ask_data_global/`: 全局数据查询的逻辑。
- `chat_bi/`: 商业智能聊天机器人相关功能。
- `client/`: 外部服务客户端，如 SSH。
- `config/`: 项目的配置文件。
- `data_scope_agent/`: 用于数据范围澄清的 Agent。
- `datamap/`: 数据地图相关的 API。
- `embeddings/`: 文本嵌入模型的实现。
- `es_retrievers/`: Elasticsearch 检索器。
- `fix_sql/`: SQL 修复和验证相关逻辑。
- `gen_intro/`: 生成表格介绍等功能的模块。
- `kb/`: 知识库相关的客户端和工具。
- `llms/`: 大语言模型的封装和实现。
- `milvus/`: 与 Milvus 向量数据库的交互。
- `monitor/`: 监控和指标收集。
- `router/`: 请求路由和工具选择。
- `text2sql/`: 将自然语言转换为 SQL 的核心模块。
- `tools/`: 项目中使用的各种工具。
- `trace/`: 请求链路追踪。
- `translator/`: 语言翻译功能。
- `utils/`: 通用工具函数。
- `vectorstores/`: 向量存储相关的功能。

接下来，我们将对每个模块进行详细的分析。

### `di_brain/ask_data/`

该目录是处理数据问答的核心模块，实现了一个基于知识库和大型语言模型的智能问答代理（Agent）。

#### 功能概述

此模块构建了一个使用 `langgraph` 的状态机，通过多轮对话和工具调用来回答用户关于数据仓库的问题。其核心流程是：

1.  **知识库检索**: 根据用户问题，从知识库中检索相关上下文。
2.  **LLM 调用**: 将用户问题和检索到的上下文发送给大型语言模型（LLM）。
3.  **智能路由**: LLM 会判断当前信息是否足以回答问题。
    -   如果信息充足，直接生成答案。
    -   如果信息不足，则指示系统搜索相关的表信息。
    -   如果问题与知识库无关，则回答"不知道"。
4.  **表信息检索**: 如果 LLM 要求，系统会搜索相关的表定义和元数据。
5.  **迭代优化**: 将检索到的表信息作为新的上下文，再次调用 LLM，形成一个循环，直到获得最终答案。
6.  **最终响应**: 生成并返回最终答案以及相关的表和文档。

#### 关键文件分析

-   `graph.py`: 定义了整个问答流程的状态图（StateGraph）。这是模块的核心编排文件。
-   `state.py`: 定义了状态图中传递的数据结构，如 `AskDataState`，以及 LLM 的结构化输出模型 `StructOutput`。
-   `prompt/prompts.py`: 存放了所有与 LLM 交互的提示模板。
-   `configuration.py`: 模块的配置文件。
-   `kb_context_composer.py`: 用于从知识库中组合上下文。
-   `database/query.py`: 包含了从数据库中查询表信息的函数。

#### 关键代码解释

##### 1. `graph.py` - 状态图定义

`graph.py` 使用 `langgraph` 构建了一个状态机来控制问答流程。

```python
builder = StateGraph(
    AskDataState, input=AskDataInput, output=AskDataOutput, config_schema=Configuration
)
builder.add_node(retrieve_knowledge_base_as_context)
builder.add_node(invoke_msg_with_llm)
builder.add_node(search_related_tables)
builder.add_node(generate_final_resp)
builder.add_node(llm_res_router)

# Add edges
builder.add_edge(START, "retrieve_knowledge_base_as_context")

ASK_DATA_RUN_NAME = "ask_data"
graph = builder.compile()
```

-   **StateGraph**: 定义了状态图的基本结构，包括状态对象 `AskDataState`、输入 `AskDataInput` 和输出 `AskDataOutput`。
-   **Nodes**: 添加了多个节点，每个节点都是流程中的一个步骤，如 `retrieve_knowledge_base_as_context`（检索知识）、`invoke_msg_with_llm`（调用 LLM）、`search_related_tables`（搜索相关表）等。
-   **Edges**: 定义了节点之间的跳转关系，构成了整个问答流程。

##### 2. `state.py` - LLM 结构化输出

`state.py` 文件中定义了 `StructOutput`，它强制 LLM 以固定的 JSON 格式返回结果，使得程序可以根据 LLM 的意图进行下一步操作。

```python
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

-   **`DirectAnswer`**: 当 LLM 认为可以直接回答时，使用此结构。
-   **`SearchMoreInfoThenAnswer`**: 当 LLM 认为需要更多关于表的信息才能回答时，使用此结构，并提供需要搜索的表名。
-   **`DontKnow`**: 当问题超出范围时，使用此结构。
-   **`StructOutput`**: 将以上三种结构包装起来，LLM 会选择其中一种作为 `data` 字段的值返回。

##### 3. `prompt/prompts.py` - 指示 LLM 如何行动

`prompts.py` 中的 `search_instruct_prompt` 是一个关键的提示，它通过 few-shot learning 的方式告诉 LLM 如何根据不同情况选择不同的输出结构。

```python
search_instruct_prompt = """
If you believe the current information provided is insufficient and these documents may contain the information you want, you can ask the user to provide them.
format is like below:

- Case(the question is not related to the document):
// ... (example cases) ...

- Case(you can't find the answer in the document):
User: 
How can I use the column website_visible of the table dim_spx_tracking_status_sg?
Response: 
data:
    search_tables: [dim_spx_tracking_status_sg]
// ... (example cases) ...

- Case(you can find the answer in the document, just answer):
User: 
How can I use table spx_mart.dwd_spx_fleet_order_tracking_ri_br
Response:
data:
  answer: Contains the latest SPX order transaction log for each order's process in Brazil. And it updated daily and represent the snapshot data for the previous day, which is used for the daily report.
  related_tables: [dwd_spx_fleet_order_tracking_ri_br]
// ... (example cases) ...

# RULE:
1. if you can find the answer in the document, just answer, the data should be DirectAnswer type
2. if you can't find the answer in the document, the data should be SearchMoreInfoThenAnswer type
3. if the question is not related to the document, the data should be DontKnow type
"""
```

这个 Prompt 清晰地定义了规则和示例，引导 LLM 做出正确的判断和响应，是整个 Agent 能够自主决策的核心。

### `di_brain/ask_data_global/`

该目录构建在 `ask_data` 模块之上，用于实现跨多个知识库（如不同的数据市场）的并行搜索和结果汇总。

#### 功能概述

当用户的查询可能涉及多个数据源时，此模块会介入。其核心流程如下：

1.  **并行分发**: 将用户的查询同时分发到多个 `ask_data` 子图（subgraph），每个子图负责一个特定的知识库。
2.  **并行搜索**: 每个 `ask_data` 子图独立执行其问答流程。
3.  **结果收集**: 收集所有并行子图的执行结果。
4.  **智能路由与回退**:
    -   `summary_router` 节点检查是否有任何一个子图找到了结果。
    -   如果至少有一个子图返回了有效结果，则进入 `summarize` 节点进行汇总。
    -   如果所有子图都没有找到结果，它会触发一个回退机制，在更广泛的默认知识库（`T2_MART`）中再次进行搜索。
    -   如果最终还是没有结果，则进入 `dontknow_summarize` 节点，生成一个友好的"未找到"提示。
5.  **结果汇总**: `summarize` 节点调用 LLM，将来自不同数据源的答案、相关表和文档整合成一个统一、连贯的报告。

#### 关键代码解释

##### `graph.py` - 并行搜索与结果汇总

```python
def search_subgraph_by_kb(state: AskDataGlobalState):
    # ... (code to prepare search tasks) ...
    
    # Add individual tasks for other KBs
    for kb in other_kbs:
        search_tasks.append(
            Send(
                "search_subgraph",
                {
                    "knowledge_base_list": [kb],
                    "user_query": state["user_query"],
                    "user_hobby": state["user_hobby"],
                },
            )
        )
    return search_tasks

# ... (graph definition) ...

# Re-use the ask_data_graph as a subgraph
search_in_kbs = ask_data_graph | RunnableLambda(collect_results)

builder.add_node("search_subgraph", search_in_kbs)
builder.add_conditional_edges(
    START, search_subgraph_by_kb, ["search_subgraph", "dontknow_summarize"]
)
builder.add_edge("search_subgraph", "summary_router")
```

-   **`search_subgraph_by_kb`**: 这个函数是实现并行搜索的关键。它为每个知识库创建一个 `Send` 指令，`langgraph` 会将这些指令并行地发送到名为 `"search_subgraph"` 的节点。
-   **`search_in_kbs`**: 这里体现了图的组合性。它将之前定义的 `ask_data_graph` 作为一个子模块，并在其后串联一个 `collect_results` 函数来格式化输出。
-   **`add_conditional_edges`**: 从图的 `START` 节点开始，通过 `search_subgraph_by_kb` 的输出来决定是进行并行搜索 (`"search_subgraph"`) 还是直接跳转到结束 (`"dontknow_summarize"`)。

这种设计模式使得系统能够高效地在多个隔离的数据环境中查找信息，并智能地汇总结果，极大地扩展了问答系统的覆盖范围。

### `di_brain/chain.py`

该文件是构建 Text-to-SQL 功能的核心，定义了多个使用 LangChain 表达式语言（LCEL）构建的处理链（Chain）。它整合了检索、生成、修正和解释 SQL 的完整流程。

#### 功能概述

`chain.py` 文件负责创建和编排各种 `Runnable` 实例，每个实例都对应一个特定的任务，如生成 SQL、解释 SQL 或修正 SQL。这些链通过组合不同的组件（如提示模板、语言模型、检索器和输出解析器）来实现复杂的功能。

#### 关键处理链分析

1.  **Text-to-SQL 主链 (`create_chain`)**:
    这是将自然语言问题转换为 SQL 查询的核心链。其工作流程如下：
    a.  **上下文检索**: 首先调用 `create_hive_meta_retriever_chain` 来检索与用户问题最相关的表信息。
    b.  **提示词构建**: 将检索到的表信息（上下文）、对话历史和用户问题整合到 `SQL_RESPONSE` 提示模板中。
    c.  **SQL 生成**: 将构建好的提示词发送给大型语言模型（LLM）以生成 SQL 查询。

2.  **Hive 元数据检索链 (`create_hive_meta_retriever_chain`)**:
    这是 Text-to-SQL 流程中至关重要的一环，因为它负责为 LLM 提供准确的上下文。这个链本身就是一个复杂的组合，执行以下步骤：
    a.  **问题重构**: 如果存在对话历史，则将后续问题重构成一个独立的、完整的查询。
    b.  **多路检索**: 并行使用多个检索器从不同来源查找相关表：
        -   `milvus_hive_table_retriever`: 基于向量相似度在 Milvus 中进行检索。
        -   `es_hive_table_retriever`: 基于关键字在 Elasticsearch 中进行检索。
        -   `milvus_hive_with_column_retriever`: 检索包含列信息的表。
    c.  **结果重排 (`create_rerank_chain`)**: 对来自多个检索器的结果进行融合和重排，以找出最相关的表。
    d.  **列信息获取**: 为重排后得分最高的表获取详细的列信息，并将其附加到上下文中。

3.  **SQL 解释链 (`create_sql_explain_chain`)**:
    该链用于解释一个给定的 SQL 查询的功能。它接收一个 SQL 查询，并使用专门的提示模板 (`gen_sql_explain_prompt_without_context`) 来让 LLM 生成对该 SQL 的自然语言解释。

4.  **SQL 修正链 (`create_sql_correct_chain`)**:
    当一个 SQL 查询执行失败时，此链用于修正错误。它接收原始的 SQL、错误信息和相关的表上下文，然后调用 LLM 来生成一个修正后的 SQL 版本。

#### 关键代码解释

##### `create_chain` - 构建 Text-to-SQL 主流程

```python
def create_chain(llm: LanguageModelLike, retriever_chain: Runnable) -> Runnable:
    context = (
        RunnablePassthrough.assign(
            docs=retriever_chain, dialect=RunnableLambda(extract_sql_dialect)
        )
        .assign(context=lambda x: format_docs_to_text(x["docs"]))
        .assign(dialect_syntax=lambda y: get_dialect_syntax_prompt(y["dialect"]))
        .with_config(run_name="RetrieveDocs")
    )
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", SQL_RESPONSE),
            MessagesPlaceholder(variable_name="chat_history"),
            ("human", "{question}"),
        ]
    )
    default_response_synthesizer = prompt | llm
    response_synthesizer = default_response_synthesizer | StrOutputParser()
    return (
        RunnablePassthrough.assign(chat_history=serialize_history)
        | context
        | response_synthesizer
    )
```

-   **LCEL 语法**: 这段代码完美地展示了 LCEL 的声明式和链式调用风格。`|` 符号将不同的处理单元（`Runnable`）连接起来，形成一个数据处理流水线。
-   **`RunnablePassthrough.assign`**: 这个函数非常关键，它允许在链中传递原始输入的同时，并行计算新的键值对并添加到结果中。例如，它首先执行 `retriever_chain` 得到 `docs`，然后基于 `docs` 计算出 `context`。
-   **模块化**: 整个链是高度模块化的，`retriever_chain` 作为一个独立的复杂模块被注入，使得代码清晰且易于维护。

##### `create_hive_meta_retriever_chain` - 复杂检索逻辑的实现

```python
def create_hive_meta_retriever_chain(
    milvus_table_retriever: BaseRetriever,
    es_table_retriever: BaseRetriever,
    milvus_table_with_column_retriever: BaseRetriever,
) -> Runnable:
    # ... (condense_question_chain) ...
    
    table_retrieval_branch_chain = create_hive_table_retriever_chain(
        milvus_table_retriever, es_table_retriever, milvus_table_with_column_retriever
    )

    # ... (conversation_chain and runnable_branch for history) ...
    
    # fusion rerank
    rerank_chain = {
        "table_docs": runnable_branch,
        "context": create_req_context_extract_chain(),
    } | create_rerank_chain()

    retrival_and_rerank = (
        RunnablePassthrough.assign(ranked_docs=rerank_chain)
        .assign(tables=lambda x: [y.metadata["uid"] for y in x["ranked_docs"]])
        .with_config(run_name="retrieve table meta and rerank wrapper")
    )

    hive_column_retriever_and_append = (
        create_column_retrieve_and_merge_chain().with_config(run_name="FindDocs")
    )

    return retrival_and_rerank | hive_column_retriever_and_append
```

-   **混合检索**: 该链通过 `create_hive_table_retriever_chain` 整合了多种检索策略，体现了混合搜索（Hybrid Search）的思想，以提高检索的准确性和覆盖面。
-   **重排（Rerank）**: 检索到的初步结果会经过一个 `rerank_chain`，这通常是一个更轻量级的模型或算法，用于对召回结果进行精排序，是提升 RAG 性能的关键步骤。
-   **分步信息获取**: 检索过程是分阶段的：先召回并排序相关的"表"，然后再为最优的表获取详细的"列"信息。这种分步策略可以有效控制上下文的长度和成本，避免一次性将所有信息注入 LLM。

### `di_brain/chat_bi/`

该目录是商业智能（BI）聊天功能的核心实现，它将 Text-to-SQL 的能力与数据可视化结合起来，为用户提供一个完整的从提问到图表呈现的端到端体验。

#### 功能概述

此模块的核心是 `ChatBIStreamRunnable`，它编排了一个完整的处理流水线，并通过流式（streaming）响应向前端实时更新状态。其主要流程如下：

1.  **SQL 生成**: 调用 `text2sql_graph_chain` 将用户的自然语言问题转换成 SQL 查询。（此步骤在 `text2sql` 模块中实现，在此处被调用）。
2.  **权限验证与 SQL 重写**:
    -   **行级权限 (Row-Access)**: 检查用户的数据权限，并根据权限策略重写 SQL，自动加入过滤条件。
    -   **表/列权限**: 验证用户是否有权限访问 SQL 中涉及的表和列。如果无权限，将提前终止流程并向用户返回申请权限的提示。
3.  **SQL 执行与缓存**:
    -   **物化视图缓存**: 尝试将 SQL 查询结果在 StarRocks 中创建为物化视图。这极大地提升了后续查询（如下钻、过滤等追问场景）的性能。
    -   **Presto 回退**: 如果物化视图创建失败，则回退到直接通过 Presto 执行查询。
4.  **结果分析与可视化**:
    -   将查询结果数据、原始问题和表结构信息等一并发送给 LLM。
    -   LLM 根据 `GEN_CHART_PROMPT` 的指示，对数据进行分析，并以结构化的形式返回：
        -   **推荐图表类型**: 如柱状图、折线图等。
        -   **图表配置**: 定义图表的维度、指标和分组。
        -   **数据洞察**: 生成对图表结果的自然语言解释。
        -   **推荐问题**: 自动生成几个相关的后续问题，引导用户进行探索式分析。
5.  **流式响应**: 整个过程中的每一步（如"正在生成 SQL"、"正在执行查询"）都会作为一个事件流式地发送给前端，提升了用户体验。

#### 关键代码解释

##### `chat_bi_stream_runnable.py` - 核心处理流程

`ChatBIStreamRunnable` 是一个 `RunnableSerializable` 的子类，通过 `astream` 方法实现了整个 BI 流程的异步流式处理。

```python
class ChatBIStreamRunnable(RunnableSerializable):
    # ... (other methods) ...
    async def astream(self, input, config=None, **kwargs):
        # ... (run_manager setup) ...
        
        # Detect if it's a follow-up question
        if input.get("is_followup"):
            async for event in self._handle_followup_scenario(input, config, run_manager):
                yield event
        else:
            async for event in self._handle_initial_scenario(input, config, run_manager):
                yield event

    async def _handle_initial_scenario(self, input, config, run_manager):
        # 1. SQL Generation (implicitly done before this runnable)
        # ... (update progress) ...

        # 2. Authentication and SQL Rewrite
        # ... (row access check) ...
        sql_rewrite_result = rewrite_sql_with_access_conditions(sql, access_conditions)
        sql = sql_rewrite_result.rewritten_sql
        # ... (table/column auth check) ...
        
        # 3. SQL Execution & Caching
        try:
            # Try to create and query a materialized view in StarRocks
            mv_info = await starrocks_client.cache_dataset(chat_id, sql, False, idc_region)
            mv_result = await starrocks_client.query_data_set(mv_info.view_name, ...)
            # ...
        except Exception as e:
            # Fallback to Presto execution
            result = await self.execute_sql(input, sql, config)
            # ...

        # 4. Result Analysis and Visualization
        analyzed_result = await self.result_analyze(
            input, result, sql, tables, related_glossaries, related_rules, child_config
        )
        
        # 5. Yield final success event
        yield self.build_success_event(...)

```

-   **场景区分**: `astream` 方法首先判断是初始提问还是追问（`is_followup`），并分发到不同的处理函数，展示了清晰的逻辑分支。
-   **阶段性状态更新**: 在每个关键步骤（如 SQL 生成后、权限验证后）都会调用 `update_process_event` 并 `yield` 一个事件，这是实现前端进度条的关键。
-   **错误处理与用户引导**: 在权限验证失败或 SQL 执行失败时，会调用 `build_failed_event` 并返回结构化的错误信息，清晰地告知用户失败原因并提供解决方案（如申请权限的链接），这是生产级应用的重要特征。
-   **缓存优先策略**: 优先使用 StarRocks 物化视图，失败后才回退到 Presto，体现了对性能和用户体验的考量。

##### `result_analyze` - LLM 驱动的数据可视化

```python
async def result_analyze(
    self,
    input,
    result,
    sql,
    tables,
    related_glossaries: list,
    related_rules: list,
    config=None,
):
    # ... (setup LLM with structured output) ...
    llm = GET_SPECIFIC_LLM(model_name)
    llm = llm.with_structured_output(ChoiceChartOutput, strict=True)

    prompt = PromptTemplate.from_template(GEN_CHART_PROMPT)
    gen_chart_chain = prompt | llm

    # ... (prepare data and context for the prompt) ...
    
    analyze_input = {
        "question": input.get("question"),
        "data": data_str,
        "table_context": table_ctx,
        "sql": sql,
        "related_glossaries": json.dumps(related_glossaries),
        "related_rules": json.dumps(related_rules),
    }

    gen_chart_result = gen_chart_chain.invoke(analyze_input)
    
    return self.process_result(gen_chart_result, result.get("headers"), original_data)
```

-   **结构化输出**: `with_structured_output(ChoiceChartOutput, strict=True)` 是一个非常强大的功能，它强制 LLM 的输出必须符合 `ChoiceChartOutput` Pydantic 模型的定义。这使得与 LLM 的交互变得非常可靠，避免了需要用正则表达式或字符串解析来提取信息的脆弱做法。
-   **上下文注入**: 传递给提示的 `analyze_input` 不仅包含了查询结果 (`data_str`)，还包含了原始问题、表信息、业务术语（`related_glossaries`）和计算规则（`related_rules`），为 LLM 提供了丰富的上下文以做出更准确的分析和推荐。
-   **结果后处理**: `process_result` 方法负责将 LLM 返回的逻辑上的图表配置（维度、指标等）转换成前端真正需要的数据结构，完成了从 AI 洞察到实际渲染的最后一公里。

### `di_brain/client/`

该目录提供了一个用于创建和管理 SSH 隧道的客户端。

#### 功能概述

在本地开发环境中，为了能够安全地访问位于远程服务器（如 Staging 或 Production 环境）上的数据库或其他内部服务，通常需要通过一个或多个跳板机（Jump Server）建立 SSH 隧道。此模块封装了 `sshtunnel` 库，简化了这一过程。

-   **配置驱动**: 隧道的配置信息（如跳板机地址、目标服务地址、本地端口等）存储在 `ssh_config.json` 文件中，实现了代码与配置的分离。
-   **多服务器重试**: 支持配置多个跳板机地址，并在连接失败时自动重试下一个地址，提高了连接的健壮性。
-   **环境感知**: 仅在开发环境（`ENV=dev`）下才会启动 SSH 隧道，避免在生产环境中执行不必要的操作。
-   **后台运行**: 隧道在独立的后台线程中启动和运行，不会阻塞主程序的执行。

#### 关键代码解释

##### `ssh_client.py` - SSH 隧道管理器

```python
def create_tunnel_with_retry(
    wan_jump_servers,
    remote_bind_address,
    local_bind_address,
    tunnel_name,
    max_retries=1,
):
    """创建SSH隧道，支持多地址重试"""
    SSH_KEY = os.path.expanduser("~/.ssh/id_rsa")
    user_name = get_current_username()

    for attempt in range(max_retries):
        for i, server in enumerate(wan_jump_servers):
            try:
                # ...
                tunnel = SSHTunnelForwarder(
                    (server, 18822),
                    ssh_username=user_name,
                    ssh_pkey=SSH_KEY,
                    remote_bind_address=remote_bind_address,
                    local_bind_address=local_bind_address,
                    # ...
                )
                tunnel.start()
                return tunnel
            except Exception as e:
                # ...
                continue
    raise Exception(f"❌ 无法建立{tunnel_name}隧道，已尝试所有服务器{max_retries}次")

def start_ssh_if_dev():
    """仅在开发环境启动SSH隧道"""
    if not _is_dev_environment():
        return None
    
    # ...
    # 在后台线程中启动SSH
    thread = threading.Thread(target=_ssh_worker, daemon=True)
    thread.start()
    return thread
```

-   **`create_tunnel_with_retry`**: 这是创建单个隧的核心函数。它会遍历 `wan_jump_servers` 列表，尝试连接每一个跳板机。一旦连接成功，就返回 `SSHTunnelForwarder` 的实例。如果所有服务器都尝试失败，则抛出异常。
-   **`start_ssh_if_dev`**: 这是模块的入口函数。它首先通过 `_is_dev_environment` 检查环境变量，如果确定是开发环境，它会在一个守护线程（`daemon=True`）中启动 `_ssh_worker`。守护线程的特性是当主程序退出时，它也会随之退出，避免了需要手动管理线程生命周期的麻烦。
-   **`_ssh_worker`**: 这个函数负责读取 `ssh_config.json`，并调用 `create_tunnel_with_retry` 来分别建立到 SG 和 USEast 集群的隧道。

### `di_brain/config/`

该目录负责项目的配置管理。

#### 功能概述

此模块通过一种灵活的方式加载和提供配置信息，支持不同环境（开发、测试、生产）的配置切换。

-   **环境感知加载**: `get_config` 函数会首先检查环境变量 `DI_BRAIN_CONFIG_DIR`。如果该变量存在，则加载其指向的 JSON 配置文件。如果不存在，则加载 `default_config_json.py` 中定义的默认开发配置。
-   **分层配置**: `config.py` 将从 JSON 文件中加载的巨大配置字典，按功能模块（如 `llm_config`, `milvus_config`, `mysql_config` 等）分解成多个独立的变量。这种做法使得在其他模块中可以按需导入特定的配置，提高了代码的可读性和模块化程度。
-   **动态枚举**: `create_enum_from_dict` 函数可以动态地根据 `llm_config` 字典中的键创建一个 `Enum` (枚举) 类型 `LLM_CHOICES`。这使得支持的 LLM 模型列表可以完全由配置文件驱动，而无需修改代码。
-   **默认值与初始化**: 提供了获取默认配置 (`get_default_config`) 和初始化运行时配置 (`init_config`) 的函数，确保了关键参数（如超时时间、最大调用次数）总是有合理的默认值。

#### 关键文件分析

-   `config.py`: 配置加载和分发的核心逻辑。
-   `default_config_json.py`: 包含了用于本地开发的默认配置，以 Python 字符串的形式存储 JSON 内容。
-   `staging_env.json`, `test_env.json`: 分别用于 Staging 和 Test 环境的配置文件（内容未展示，但根据文件名可推断其用途）。

#### 关键代码解释

##### `config.py` - 配置加载逻辑

```python
def get_config():
    config_dir = os.environ.get("DI_BRAIN_CONFIG_DIR")
    if config_dir:
        with open(config_dir, "r") as f:
            conf = json.load(f)
    else:
        conf = json.loads(DEV_CONFIG)
    return conf

llm_dict = get_config()["llm_config"]
embedding_dict = get_config()["embedding_config"]
ram_sql_parser_config = get_config()["sql_parser_config"]
# ... and so on for other configurations
```

-   **`get_config`**: 这个函数的实现是环境自适应配置的核心。通过检查环境变量，应用可以在不同的部署环境中无缝切换到对应的配置，这是现代应用开发的最佳实践。
-   **配置分发**: 在 `get_config` 被调用后，代码立即将返回的大字典按模块拆分。这避免了在整个项目中传递一个巨大的、不透明的 `config` 对象，而是让每个模块都能清晰地导入它所需要的具体配置部分，例如 `from di_brain.config import milvus_config`。

### `di_brain/data_scope_agent/`

该目录实现了一个专门的 Agent，用于在正式开始 Text-to-SQL 或数据问答之前，与用户进行交互，以明确用户查询的数据范围（Data Scope）。

#### 功能概述

当用户的查询比较模糊，可能涉及到多个数据域（DataMart 或 DataTopic）时，直接进行 Text-to-SQL 可能会因为上下文不准确而失败。此 Agent 的作用就是在这种情况下，先通过对话帮助用户缩小查询范围。

1.  **范围检索 (`retrieve_scopes`)**: 首先，从知识库中获取所有当前用户有权访问的数据域（DataMart 和 DataTopic）及其描述信息。
2.  **查询分析 (`analyze_query`)**: 将用户问题和所有可用的数据域描述信息一起发送给 LLM。LLM 会被要求分析问题与各个数据域的匹配程度，并以结构化的形式（`QueryAnalysisOutput`）返回分析结果，包括：
    -   `result_type`: 分析结果的类型（`SCOPE_IDENTIFIED` - 已识别范围，`INSUFFICIENT_INFO` - 信息不足，`NO_MATCH` - 无匹配）。
    -   `matched_scopes`: 匹配上的数据域列表。
    -   `confidence`: 匹配的置信度（高、中、低）。
    -   `additional_questions`: 当信息不足时，需要向用户追问的问题。
3.  **决策路由 (`route_decision`)**: 根据 LLM 的分析结果，图会路由到不同的分支：
    -   **信息不足**: 进入 `ask_for_clarification` 节点，将 LLM 生成的追问问题返回给用户。
    -   **范围已识别**: 进入 `confirm_scope` 节点。如果匹配到的范围多于一个，会向用户展示列表并要求用户选择；如果只有一个，则直接确认范围。
    -   **无匹配**: 自动回退，选择一个默认的、范围最广的数据域（`T2_MART`）作为上下文。

#### 关键代码解释

##### `data_scope_clarification_agent.py` - Agent 核心逻辑

```python
class DataScopeClarificationGraph:
    def __init__(self):
        self.llm = GET_STRUCTURED_LLM(...).with_structured_output(schema=QueryAnalysisOutput)
        self.graph = self._build_graph()

    def analyze_query(self, state: GraphState) -> GraphState:
        # ... (prepare scope_context for LLM) ...
        try:
            response = self.llm.invoke(
                analysis_prompt.format(
                    scope_context=scope_context, user_query=user_query
                )
            )
            # ... (process response) ...
            state["analysis_result"] = analysis
            state["result_type"] = response.result_type
            state["matched_scopes"] = matched_scopes
        except Exception as e:
            # ... (handle errors) ...
        return state

    def route_decision(self, state: GraphState) -> Literal[...]:
        result_type = state.get("result_type")
        if result_type == DataScopeClarificationResult.INSUFFICIENT_INFO:
            return "ask_for_clarification"
        elif result_type == DataScopeClarificationResult.SCOPE_IDENTIFIED:
            return "confirm_scope"
        else:
            return "fallback_to_layer_2_tables"

    def _build_graph(self) -> StateGraph:
        graph = StateGraph(GraphState)
        graph.add_node("retrieve_scopes", self.retrieve_scopes)
        graph.add_node("analyze_query", self.analyze_query)
        # ... (add other nodes) ...
        
        graph.add_edge(START, "retrieve_scopes")
        graph.add_edge("retrieve_scopes", "analyze_query")
        graph.add_conditional_edges(
            "analyze_query",
            self.route_decision,
            {...}
        )
        # ... (add other edges) ...
        return graph.compile()
```

-   **LLM 作为分析引擎**: `analyze_query` 节点是此 Agent 的大脑。它不要求 LLM 直接给出答案，而是要求 LLM 扮演一个"分析师"的角色，对输入信息进行评估，并给出结构化的分析报告。这是 Agent 设计中的一种常见且高效的模式。
-   **结构化输出驱动控制流**: `with_structured_output(schema=QueryAnalysisOutput)` 确保了 LLM 的输出是可预测和可解析的。`route_decision` 函数正是利用了 `QueryAnalysisOutput` 中的 `result_type` 字段，将程序的控制流导向了正确的处理逻辑。
-   **人机交互**: 当 LLM 判断信息不足或匹配范围不唯一时，图会进入需要用户输入的节点（`ask_for_clarification`, `confirm_scope`），并将 `ask_human` 标志位设为 `True`。这使得上层应用可以暂停执行，向用户展示问题，并等待用户的反馈，从而实现有效的人机协作。
-   **回退机制**: `fallback_to_layer_2_tables` 提供了一个默认的、安全的处理路径，确保了即使在最不确定的情况下，系统也能有一个合理的行为，而不是完全失败。

### `di_brain/datamap/`

该目录封装了与内部数据地图（DataMap）服务的 API 交互。

#### 功能概述

数据地图是一个元数据管理平台，存储了关于数据资产（如 Hive 表）的各种信息，包括谁在何时查询了哪些表。此模块的主要作用是调用数据地图的 API，来获取"一张表最近被哪些用户查询过"的信息。

这个功能被用于 Text-to-SQL 流程中的**结果重排（rerank）**阶段。其核心思想是：如果一个用户查询了某张表，那么这张表对他来说可能比其他表更重要。因此，在多个候选表中，系统会优先推荐那些当前用户最近查询过的表。

#### 关键代码解释

##### `datamap_api.py` - 调用数据地图 API

```python
datamap_query_count_url = (
    "https://open-api.datasuite.shopee.io/datamap/api/v3/system/hive/queryAccount/batch"
)

def query_count_batch(table_list: List, user_email: str) -> Dict:
    # ... (prepare request) ...
    response = requests.post(datamap_query_count_url, headers=common_header, json=req)
    # ... (error handling) ...
    
    response_json = response.json()
    
    table_score_dict = {}
    resp_data = response_json["data"]
    for item in resp_data:
        table_uid = format_table_uid(item)
        table_score_dict[table_uid] = last_7_day_query_user_score(
            item["last7DayQueryUsers"], user_email, max_limit
        )
    return table_score_dict

def last_7_day_query_user_score(
    user_list: List, user_email: str, max_limit: int
) -> int:
    if user_email in user_list:
        return 0
    else:
        return max_limit
```

-   **API 调用**: `query_count_batch` 函数向数据地图的批量查询接口发送一个 POST 请求，请求体中包含了需要查询的表列表。
-   **个性化评分**: `last_7_day_query_user_score` 函数是实现个性化重排的关键。它会检查返回的 `last7DayQueryUsers` 列表中是否包含当前用户的邮箱。
    -   如果**包含**，意味着用户最近查询过这张表，函数返回 `0`（最高优先级）。
    -   如果不包含，函数返回 `max_limit`（最低优先级）。
-   **结果用于排序**: `query_count_batch` 返回的 `table_score_dict` 字典最终会被 `rerank` 模块使用。在排序时，得分越低（如此处的 `0`）的表会被排在越前面，从而实现了对用户常用表的优先推荐。

### `di_brain/embeddings/`

该目录负责管理和提供文本嵌入（Text Embedding）模型。

#### 功能概述

文本嵌入是将文本（如用户问题、表描述）转换为数值向量的过程，这是实现语义搜索和 RAG（检索增强生成）的基础。此模块提供了一个统一的工厂函数 `get_embeddings_model`，用于根据配置实例化不同类型的嵌入模型客户端。

-   **配置驱动**: `embedding_dict` (来自 `di_brain/config`) 存储了所有可用嵌入模型的配置，包括模型名称、API 地址（`url`）以及端点类型（`endpoint_type`）。
-   **多模型支持**: 通过 `endpoint_type` 字段，工厂可以支持多种不同的嵌入服务，如 OpenAI、Infinity（一个开源的嵌入服务）以及自定义的 Hugging Face T5 嵌入推理服务（`HuggingfaceTEIEmbeddings`）。
-   **统一接口**: 所有返回的嵌入模型实例都遵循 LangChain 的 `Embeddings` 接口规范，这意味着它们都有 `embed_documents` 和 `embed_query` 方法。这使得上层应用（如 `chain.py` 中的检索器）可以无缝地替换和使用不同的嵌入模型，而无需修改代码。

#### 关键代码解释

##### `global_embedding.py` - 嵌入模型工厂

```python
def get_embeddings_model(model_name: Optional[str] = None) -> embeddings:
    if not model_name:
        model_name = DEFAULT_EMBEDDING_MODEL

    if model_name not in embedding_dict:
        raise ValueError(...)

    model_config = embedding_dict[model_name]
    endpoint_type = model_config["endpoint_type"]
    url = model_config["url"]
    
    embedding_classes = {
        "OpenAIEmbeddings": (OpenAIEmbeddings, "base_url"),
        "InfinityEmbeddings": (InfinityEmbeddings, "infinity_api_url"),
        "HuggingfaceTEIEmbeddings": (HuggingfaceTEIEmbeddings, "base_url"),
    }

    if endpoint_type in embedding_classes:
        embedding_class, url_param_name = embedding_classes[endpoint_type]
        init_kwargs = {url_param_name: url, "model": model_name}
        # ... (add api_key if present) ...
        return embedding_class(**init_kwargs)
    else:
        raise ValueError(...)
```

-   **工厂设计模式**: `get_embeddings_model` 是一个典型的工厂函数。它接收一个模型名称，然后查询 `embedding_dict` 配置来决定实例化的具体类和参数。
-   **动态分派**: `embedding_classes` 字典是实现动态分派的核心。它将配置文件中的 `endpoint_type` 字符串映射到具体的 LangChain `Embeddings` 子类和该类构造函数中表示 API 地址的参数名（如 `base_url` 或 `infinity_api_url`）。这种设计使得添加对新嵌入服务的支持变得非常简单，只需要在 `embedding_classes` 字典中增加一个条目即可。

##### `tei_embedding.py` - 自定义嵌入客户端

```python
class HuggingfaceTEIEmbeddings(BaseModel, Embeddings):
    """See <https://huggingface.github.io/text-embeddings-inquery_instructionference/>"""
    base_url: str
    model: str
    normalize: bool = True
    truncate: bool = True

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        response = requests.post(
            self.base_url + "/embed",
            json={
                "inputs": texts,
                "normalize": self.normalize,
                "truncate": self.truncate,
            },
        )
        return response.json()

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]
```

-   **接口实现**: 这个类展示了如何为一种新的嵌入服务（这里是 Hugging Face Text Embeddings Inference 服务）实现 LangChain 的 `Embeddings` 接口。它只需要继承 `Embeddings` 基类，并实现 `embed_documents` 和 `embed_query` 两个核心方法即可。
-   **HTTP 客户端**: `embed_documents` 方法的内部实现非常直接：它使用 `requests` 库向配置的 `/embed` API 端点发送一个 POST 请求，并将文本作为 JSON payload 发送。这体现了该模块作为外部服务客户端的本质。

### `di_brain/es_retrievers/`

该目录提供了一个自定义的 Elasticsearch 检索器，用于在 Text-to-SQL 流程中执行基于关键字的表检索。

#### 功能概述

虽然向量搜索（如 Milvus）擅长理解语义相似性，但在需要精确匹配关键字（如表名、列名）的场景下，传统的全文检索引擎如 Elasticsearch 通常表现更佳。此模块通过实现一个自定义的 `ElasticsearchAdvanceRetriever` 来补充向量检索的能力，是实现混合搜索（Hybrid Search）的关键组件之一。

-   **灵活性**: 与 LangChain 内置的 Elasticsearch 检索器不同，这个自定义版本将构建 Elasticsearch 查询语句（DSL）的逻辑委托给了一个外部函数 `body_func`。这提供了极大的灵活性，允许上层应用根据不同的需求（如不同的查询类型、过滤条件等）动态生成查询语句，而无需修改检索器本身。
-   **动态元数据注入**: 在执行检索时，它能够从 LangChain 的 `run_manager` 中获取运行时元数据，并将其传递给 `body_func`。这使得查询可以利用更丰富的上下文信息，例如用户信息、会话 ID 等，来实现更复杂的过滤或排序逻辑。
-   **标准化接口**: `ElasticsearchAdvanceRetriever` 继承自 LangChain 的 `BaseRetriever`，并实现了 `_get_relevant_documents` 方法，确保了它可以无缝地集成到任何 LangChain 的 RAG 链中。

#### 关键代码解释

##### `elasticsearch_retriever.py` - 高级 ES 检索器

```python
class ElasticsearchAdvanceRetriever(BaseRetriever):
    es_client: Elasticsearch
    index_name: Union[str, Sequence[str]]
    body_func: Callable[[str, Dict], Dict]
    # ... (other fields and methods) ...

    def _get_relevant_documents(
        self, query: str, *, run_manager: CallbackManagerForRetrieverRun
    ) -> List[Document]:
        if not self.es_client or not self.document_mapper:
            raise ValueError("faulty configuration")

        metadata = run_manager.metadata
        body = self.body_func(query, metadata)
        results = self.es_client.search(index=self.index_name, body=body)
        return [self.document_mapper(hit) for hit in results["hits"]["hits"]]
```

-   **`body_func`**: 这是该检索器设计的核心。它是一个回调函数，接收用户输入的查询字符串 `query` 和运行时的 `metadata` 字典。它的职责是返回一个完整的 Elasticsearch DSL 查询字典。这种设计将"如何查询"的逻辑与"执行查询"的逻辑解耦，非常灵活。
-   **`run_manager.metadata`**: LangChain 的 `run_manager` 会在整个调用链中传递元数据。通过在这里访问 `run_manager.metadata`，检索器可以获取到在链的上游设置的任何信息，从而实现上下文感知的检索。例如，可以从元数据中获取用户信息，然后在 Elasticsearch 查询中加入一个 `filter` 子句，只检索该用户有权访问的表。
-   **集成**: 在 `di_brain/chain.py` 的 `create_hive_meta_retriever_chain` 中，这个 `ElasticsearchAdvanceRetriever` 的实例（`es_hive_table_retriever`）与 Milvus 检索器一起被用于并行检索，共同构成了混合搜索的基础。

### `di_brain/fix_sql/`

该目录实现了一个用于自动修复 SQL 错误的复杂 Agent。

#### 功能概述

当 Text-to-SQL 生成的查询或用户手写的查询在执行时遇到语法错误，此模块会被调用。它构建了一个基于 `langgraph` 的状态机，通过一系列的"思考"步骤来诊断和修复 SQL。

1.  **预处理**:
    -   `replace_sql_variable`: 替换 SQL 中的非标变量（如 `${var}`）为标准占位符，避免解析器混淆。
2.  **上下文检索 (`retrieve_sql_selected_tables`)**:
    -   从错误的 SQL 中解析出所有涉及的表名。
    -   调用 `retrieve_selected_tables_context_from_datamap` 从数据地图获取这些表的 Schema 信息。
3.  **错误分析与修复提示生成**:
    -   **分支逻辑**:
        -   如果调用方**提供了**错误信息 (`error_info` 存在)，则进入 `generate_fix_sql_prompt_md` 节点。
        -   如果调用方**未提供**错误信息，则进入 `generate_error_info_then_fix_sql_prompt_md` 节点，该节点会先使用本地 SQL 解析器 (`validate_sql_with_dialect`) 尝试定位错误。
    -   **提示构建**: 将错误的 SQL、错误信息和检索到的表结构信息，全部格式化填入一个详细的提示模板 `FIX_SQL_ONE_SHOT_MD_PROMPT` 中。
4.  **LLM 调用 (`invoke_msg_with_llm`)**: 将构建好的提示发送给 LLM，要求它以 Markdown 格式返回修复后的 SQL 和解释。
5.  **结果解析 (`extract_md_info`)**: 从 LLM 返回的 Markdown 文本中，使用正则表达式提取出修复后的 SQL 代码块。
6.  **验证与迭代**:
    -   **语法验证 (`validate_sql`)**: 将提取出的 SQL 再次通过本地 SQL 解析器进行验证。
    -   **分支逻辑**:
        -   如果验证**通过**，并且没有非标变量需要还原，流程结束。
        -   如果验证**通过**，但存在非标变量，则进入 `replace_llm_result_with_variable` 节点将其还原，然后流程结束。
        -   如果验证**失败**，说明 LLM 生成的 SQL 仍然有误，则进入 `fix_generate_sql_prompt_md` 节点，构建一个新的提示告知 LLM 它上次的输出有误，并要求它重新生成。然后再次进入 LLM 调用节点，形成一个**修复循环**。

#### 关键代码解释

##### `fix_sql_md_based_prompt_graph.py` - SQL 修复状态图

```python
def build_graph_md():
    workflow = StateGraph(FixSQLState)
    # ... (add all nodes) ...

    workflow.set_entry_point("replace_sql_variable")
    workflow.add_edge("replace_sql_variable", "retrieve_sql_selected_tables")
    workflow.add_conditional_edges(
        "retrieve_sql_selected_tables",
        # --> if error_info is missing, generate it first
        # --> else, go directly to building the main prompt
    )
    # ...
    workflow.add_edge("invoke_msg_with_llm", "extract_md_info")
    workflow.add_conditional_edges(
        "extract_md_info",
        # --> if markdown parsing fails, ask LLM to fix its output format
        # --> else, go to validation
    )
    workflow.add_conditional_edges(
        "validate_sql",
        # --> if validation fails, loop back to LLM with feedback
        # --> else if variables need replacement, do it
        # --> else, END
    )
    # ... (self-correction loops) ...
    workflow.add_edge("fix_generate_sql_prompt_md", "invoke_msg_with_llm")
    
    return workflow.compile()
```

-   **自我修正循环 (Self-Correction Loop)**: 这个图最显著的特点是它的循环结构。`validate_sql` 节点是这个循环的关键决策点。如果验证失败，它不会直接结束，而是通过 `fix_generate_sql_prompt_md` 节点将错误信息反馈给 LLM，形成一个 `(LLM -> Parse -> Validate -> Feedback -> LLM)` 的循环。这种模式极大地提高了 Agent 的鲁棒性，使其能够从错误中学习并进行多次尝试。
-   **多层条件路由**: 图中使用了多个 `add_conditional_edges` 来处理各种情况，如错误信息是否存在、Markdown 解析是否成功、SQL 验证是否通过等。这展示了 `langgraph` 在构建复杂、有状态的 Agent 方面的强大能力。

##### `fix_sql_step.py` - 核心步骤实现

```python
def retrieve_sql_selected_tables(state: FixSQLState) -> FixSQLState:
    sql = state["error_sql"]
    idc_region = state.get("region", "SG")
    selected_tables = parse_selected_tables(sql)
    table_context = retrieve_selected_tables_context_from_datamap(
        selected_tables, idc_region
    )
    state["table_context"] = table_context
    return state

def validate_sql(state: FixSQLState) -> FixSQLState:
    state["error"] = None
    # ...
    sql_validated, error_info = validate_sql_with_dialect(
        state["fixed_query"], state["dialect"]
    )
    if not sql_validated:
        state["error"] = {
            "error_type": "bad_sql",
            "error_info": error_info.replace("\n", "").strip(),
        }
    return state
```

-   **工具调用**: `retrieve_sql_selected_tables` 节点中，`parse_selected_tables` (解析表名) 和 `retrieve_selected_tables_context_from_datamap` (从数据地图获取 Schema) 是典型的工具调用。Agent 通过这些工具与外部世界交互，获取完成任务所需的信息。
-   **本地验证**: `validate_sql` 节点使用了一个本地的、非 LLM 的 SQL 语法验证器 (`validate_sql_with_dialect`)。这是一种重要的设计模式：对于确定性的、有明确规则的任务（如语法检查），使用传统的、可靠的工具通常比依赖 LLM 更快、更便宜、更准确。LLM 则被用在需要"智能"和推理的任务上（如根据错误信息和 Schema 推断如何修复 SQL）。

### `di_brain/gen_intro/`

该目录包含了一个用于自动为数据域（DataMart 或 DataTopic）生成摘要性介绍文档的脚本。

#### 功能概述

为了让用户快速了解一个数据域的核心内容，通常需要一份高质量的介绍文档。手动编写和维护这些文档费时费力。此模块通过一个两阶段的 LLM 调用链，自动从数据域的原始文档（如 FAQ、设计文档等）中提取关键信息，并生成一份结构化的、易于阅读的摘要。

1.  **第一阶段：信息提取**:
    -   将从知识库中检索到的关于某个数据域的所有原始文档（`raw_docs`）打包成一个 XML 格式的字符串。
    -   将此 XML 字符串和一个详细的提示（`DOC_INTRO_EXTRACT_BY_LLM_PROMPT_V3`）发送给一个配置了结构化输出（`with_structured_output(schema=KeyDataGroupInfo)`）的 LLM。
    -   LLM 会被要求从原始文档中提取出关键信息，并填充到 `KeyDataGroupInfo` 模型定义的字段中，如 `business_domain`, `key_tables`, `key_metrics` 等。
2.  **第二阶段：摘要生成**:
    -   将第一阶段提取出的结构化 JSON 数据，注入到第二个提示模板 `GEN_INTRUCTION_PROMPT_V4` 中。
    -   调用另一个（通常是更轻量、更注重写作能力的）LLM，要求它基于这些结构化的关键信息，生成一段流畅、连贯的 Markdown 格式的介绍文档。

#### 关键代码解释

##### `generate_table_group_introduction.py` - 两阶段生成链

```python
def generate_introduction_by_llm(input: GenIntroInput) -> GenIntroOutput:
    # 1. Format raw_docs into an XML string
    table_group_xml_str = f'<data_group name="{input["table_group_id"]}">\n'
    # ... (loop to add docs) ...
    table_group_xml_str += "</data_group>\n"
    
    # 2. First LLM call: Extract key info into a structured object
    user_prompt = HumanMessage(content=table_group_xml_str)
    response = structured_llm.invoke([SYSTEM_PROMPT, user_prompt])
    mid_intro_json = response.model_dump_json(indent=4)

    # 3. Second LLM call: Generate a human-readable summary from the structured JSON
    final_prompt = GEN_INTRUCTION_PROMPT_V4.format(json_content=mid_intro_json)
    final_intro = final_gen_llm.invoke(final_prompt)

    return GenIntroOutput(introduction=final_intro.content)
```

-   **两阶段 LLM 调用 (Two-Stage LLM Chain)**: 这种"提取-再生成"（Extract-then-Generate）的设计模式非常强大。
    -   **第一阶段（提取）**专注于从大量、非结构化的文本中准确地抽取出需要的信息。通过强制 LLM 使用结构化输出，保证了这一步结果的可靠性和可预测性。
    -   **第二阶段（生成）**则专注于"写作"。它接收的是干净、结构化的关键信息，因此可以更专注于语言的流畅性、格式的美观性和内容的组织，而不会被原始文档中的噪声信息干扰。
-   **关注点分离**: 这种模式体现了"关注点分离"的设计原则。第一个 LLM 负责"理解和分析"，第二个 LLM 负责"表达和呈现"。这使得每个阶段的提示词可以更简单、更专注，从而提高了整体输出的质量和稳定性。

### `di_brain/kb/`

该目录封装了与后端知识库（Knowledge Base, KB）服务的 API 交互。

#### 功能概述

知识库是 RAG（检索增强生成）应用的核心组件，它存储了用于回答问题的上下文信息。此模块提供了一个 `KBClient` 类，作为访问后端知识库服务的 HTTP 客户端，统一了对不同类型知识（如表结构、文档、FAQ）的访问方式。

-   **统一客户端**: `KBClient` 类封装了所有对后端 KB API 的 HTTP 请求，包括设置认证头、构造请求体和解析响应。
-   **多类型数据检索**: 提供了多种方法来检索不同类型的数据：
    -   `get_table_details_by_full_table_names`: 根据完整的表名获取表的详细 Schema 信息。
    -   `get_details_by_knowledge_base_names`: 根据知识库名称（如 `prefill_datamart_Order Mart`）和文档类型（如 `datamart_desc_doc`）获取相关的文档。
    -   `get_table_list_by_user_query`: 根据用户的自然语言问题，通过向量搜索接口返回最相关的表列表。
    -   `get_sample_data`: 获取指定表的样本数据。
-   **命名空间管理**: 提供了一系列工具函数（如 `to_table_kb_name`, `from_kb_name_to_name`, `is_topic_kb_name`）来处理和转换知识库的命名空间。例如，一个普通的表名 `SG.mp_order.dws_buyer_gmv_td` 会被转换为知识库内部的唯一标识符 `prefill_hive_table_SG.mp_order.dws_buyer_gmv_td`。这种统一的命名约定使得系统可以清晰地管理和引用不同来源的知识。

#### 关键代码解释

##### `kb_client.py` - 知识库 API 客户端

```python
class KBClient:
    def __init__(self):
        self.url = kb_config["url"]
        self.token = kb_config["token"]
        self.base_url = f"{self.url}/v1"
        self.headers = {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
        }

    def get_table_details_by_full_table_names(
        self, full_table_names: List[str]
    ) -> List[KnowledgeBaseDetail]:
        # ... (constructs request and calls API) ...
        endpoint = f"{self.base_url}/tables/table-details"
        response = requests.post(
            endpoint, json=full_table_names, headers=self.headers, timeout=30
        )
        # ... (parses response into KnowledgeBaseDetail objects) ...
        return result

    def get_table_list_by_user_query(
        self, query: str, top_k: int = T2_MART_TOP_K
    ) -> VectorSearchResponse:
        # ... (constructs request and calls API) ...
        endpoint = f"{self.base_url}/tables/vector-search"
        request_data = {"query": query, "topK": top_k}
        response = requests.post(
            endpoint,
            json=request_data,
            headers=self.headers,
            timeout=30,
        )
        # ... (parses response into VectorSearchResponse object) ...
        return result

# Create a global singleton instance
kb_client = KBClient()
```

-   **客户端封装**: `KBClient` 类的实现遵循了标准的客户端设计模式。它在构造函数中从配置初始化 API 地址和认证令牌，并提供了多个方法，每个方法对应一个特定的后端 API 端点。这种封装将底层的 HTTP 请求细节（如 URL 构造、错误处理、JSON 解析）隐藏起来，为上层应用提供了清晰、易用的接口。
-   **单例模式**: 通过在模块级别创建一个全局实例 `kb_client`，应用中的所有部分都可以共享同一个客户端连接，避免了重复创建对象的开销。
-   **数据模型**: 客户端方法返回的不是原始的 JSON 字典，而是 Pydantic 模型（如 `KnowledgeBaseDetail`, `VectorSearchResponse`）。这提供了类型安全和自动数据验证的好处，使得代码更健壮、更易于维护。

### `di_brain/llms/`

该目录负责管理和提供对各种大型语言模型（LLM）的访问。

#### 功能概述

这是一个典型的 LLM 工厂模块，旨在为整个应用程序提供一个统一、灵活的方式来实例化和使用不同的 LLM。

-   **多提供商支持**: 通过 `provider` 字段在配置文件中区分，模块可以实例化来自不同提供商的 LLM 客户端，如 OpenAI (`ChatOpenAI`), Anthropic (`ChatAnthropic`), Google (`GoogleGeminiGenAI`), 以及自定义的 Compass (`CompassAI`)。
-   **配置驱动**: `llm_dict` (来自 `di_brain/config`) 集中管理了所有 LLM 的配置，包括模型名称、API 地址、API key、temperature 等参数。
-   **统一工厂函数**:
    -   `GET_SPECIFIC_LLM`: 根据指定的模型名称返回一个具体的 LLM 实例。
    -   `GET_STRUCTURED_LLM`: 返回一个专门配置过的、支持结构化输出（JSON mode 或 Function Calling）的 LLM 实例。
    -   `GET_CONFIGURABLE_LLM`: 返回一个支持在运行时动态切换模型的 LangChain `ConfigurableField` 实例。
-   **容错与回退 (`with_fallbacks`)**: `GET_CONFIGURABLE_LLM` 和 `GET_FALLBACK_LLM` 函数利用 LangChain 的 `with_fallbacks` 机制，创建了一个具有自动故障切换能力的 LLM 调用链。如果主模型调用失败（例如因为 API 超时或服务器错误），它会自动尝试调用备用模型列表中的下一个模型，直到成功为止。
-   **结构化输出增强**: 自定义的 `StructuredChatOpenAI` 等类重写了 `with_structured_output` 方法，使其能够根据配置文件自动选择最佳的结构化输出模式（如 `function_calling` 或 `json_mode`），简化了上层应用的代码。

#### 关键代码解释

##### `global_llm.py` - LLM 工厂与回退机制

```python
def _create_llm_instance(
    model: LLM_CHOICES,
    extra_config: Optional[dict] = None,
    openai_class=ChatOpenAI,
    # ... other classes
) -> ChatOpenAI | ChatAnthropic | GoogleGeminiGenAI | CompassAI:
    configs = _get_llm_config(model, extra_config)
    provider = configs.get("provider")
    if provider == "openai":
        return openai_class(**configs)
    # ... elif for other providers ...
    else:
        raise ValueError(f"Unsupported provider: {provider}")

def GET_SPECIFIC_LLM(
    model: LLM_CHOICES, extra_config: Optional[dict] = None
) -> ChatOpenAI | ChatAnthropic | GoogleGeminiGenAI | CompassAI:
    return _create_llm_instance(model, extra_config)

def GET_CONFIGURABLE_LLM():
    # ... (code to create a map 'm' of all configured LLM instances) ...

    # Create a fallback list of all available models
    fallback_list = []
    for key, value in llm_dict.items():
        cleaned_value = _clean_config_for_chatopen_ai(value)
        fallback_list.append(ChatOpenAI(**cleaned_value))

    main_config = _clean_config_for_chatopen_ai(_get_llm_config(DEFAULT_LLM_CHOICES))

    return (
        ChatOpenAI(**main_config)
        .configurable_alternatives(
            ConfigurableField(id="llm"), default_key="default_llm", **m
        )
        .with_fallbacks(fallback_list)
    )
```

-   **`_create_llm_instance`**: 这是所有工厂函数的核心。它根据配置文件中的 `provider` 字段，动态地选择并实例化正确的 LangChain LLM 客户端类。这种设计使得添加一个新的 LLM 提供商非常简单，只需增加一个 `elif` 分支即可。
-   **`GET_CONFIGURABLE_LLM`**: 这个函数展示了 LangChain 强大的可配置性和容错性。
    -   `.configurable_alternatives` 允许在运行时通过 `with_config` 方法动态选择使用哪个 LLM 实例。例如，可以根据用户的不同订阅级别或请求的复杂度来选择不同的模型。
    -   `.with_fallbacks(fallback_list)` 是实现高可用性的关键。它创建了一个责任链，如果主模型（`ChatOpenAI(**main_config)`）调用失败，程序会自动捕获异常并依次尝试 `fallback_list` 中的其他模型，大大降低了因单个模型服务不稳定而导致整个应用失败的风险。

### `di_brain/milvus/`

该目录负责与 Milvus 向量数据库的所有交互。

#### 功能概述

Milvus 是一个开源的向量数据库，专门用于存储和高效检索大规模的嵌入向量。在 RAG 应用中，它通常是实现"检索"这一环的核心组件。

-   **连接管理**: `milvus_connection.py` 提供了一个 `MilvusConnector` 类，用于管理到 Milvus 服务器的连接。它从配置文件中加载连接参数，并提供了一个全局的单例 `milvus_connector`，确保整个应用共享同一个连接池。
-   **向量搜索**: `milvus_search.py` 封装了执行向量搜索的核心逻辑。`search_similar_tables` 函数接收一个自然语言查询，执行以下步骤：
    1.  调用 `get_text_embedding` 将查询文本转换为一个向量。
    2.  调用 `milvus_client.search` 方法，将查询向量发送到 Milvus。
    3.  Milvus 会计算查询向量与数据库中所有存储向量的相似度（通常使用 L2 距离或余弦相似度），并返回最相似的 top-k 个结果。
    4.  函数将返回的结果格式化，并计算一个归一化的 `score`。
-   **数据摄入脚本**: `table_manifest/` 子目录中包含了一些 Python 脚本（如 `embedding_table_meta.py`），用于读取 Hive 表的元数据，将它们转换为向量，并批量插入（摄入）到 Milvus 中。这是构建检索知识库的"离线"步骤。

#### 关键代码解释

##### `milvus_connection.py` - 连接管理器

```python
class MilvusConnector:
    def __init__(self, config=DEV_CONFIG):
        self.client: Optional[MilvusClient] = None
        self.config = self._load_config(config)

    def connect(self) -> MilvusClient:
        if self.client is not None:
            return self.client
        try:
            uri = f"http://{self.config['host']}:{self.config['port']}"
            self.client = MilvusClient(
                uri=uri,
                user=self.config["user"],
                password=self.config["password"],
                db_name=self.config["db_name"],
            )
            return self.client
        except Exception as e:
            raise

# Create a global singleton instance
milvus_connector = MilvusConnector()

def get_milvus_client() -> MilvusClient:
    return milvus_connector.get_connection()
```

-   **单例模式**: 与 `kb_client` 类似，通过 `milvus_connector` 这个全局实例和 `get_milvus_client` 函数，实现了连接的全局共享和懒加载（lazy loading），即只有在第一次被请求时才会真正建立连接。

##### `milvus_search.py` - 向量搜索实现

```python
def search_similar_tables(query_text, top_k=10, filter_expr=None):
    # ... (initialize embedding client) ...

    # Convert query text to a vector
    query_vector = get_text_embedding(query_text, openai_client)

    # Define search parameters
    search_params = {
        "metric_type": "L2",
        "params": {"ef": 200},
    }

    # Execute vector search
    results = milvus_client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        filter=filter_expr,
        limit=top_k,
        output_fields=[...],
        search_params=search_params,
    )

    # ... (format and return results) ...
```

-   **搜索流程**: 这段代码清晰地展示了典型的向量搜索流程：文本 -> 向量 -> 数据库查询。
-   **混合搜索的另一半**: `filter=filter_expr` 参数是实现混合搜索的关键。它允许在进行向量相似度检索的同时，应用一个基于元数据的过滤条件（例如，`data_marts == 'SPX Mart'`）。这意味着系统可以首先通过元数据过滤将搜索范围缩小到一个特定的数据域，然后再在这个子集内进行语义相似度搜索。这种"先过滤，后搜索"的策略可以极大地提高大规模数据集下的搜索精度和效率。

### `di_brain/monitor/`

该目录负责定义应用的可观测性指标。

#### 功能概述

为了监控 LLM 应用在生产环境中的性能和健康状况，需要收集一系列关键指标。此模块使用 `prometheus_client` 库定义了多个 Prometheus 指标，用于量化 LLM 的调用情况。

-   **Counter (计数器)**:
    -   `llm_invoke_count`: 用于统计 LLM 的调用总次数。它有三个标签（label）：`llm` (模型名称), `status` (调用结果，如 'success', 'fail', 'timeout'), 和 `run_name` (调用链的名称)。通过这些标签，可以非常灵活地对数据进行聚合和查询，例如："查询 gpt-4.1 模型在 fix_sql 链中调用失败的总次数"。
-   **Histogram (直方图)**:
    -   `llm_invoke_latency_second`: 记录 LLM 调用的延迟分布。
    -   `llm_invoke_input_length`: 记录输入给 LLM 的 token 数量分布。
    -   `llm_invoke_output_length`: 记录 LLM 输出的 token 数量分布。

这些指标会在应用运行时被收集，并通过一个 Prometheus 端点暴露出来，然后由 Prometheus 服务器定期抓取并存储。之后，可以使用 Grafana 等工具对这些数据进行可视化，创建仪表盘来实时监控 LLM 服务的健康状况、成本和性能。

#### 关键代码解释

##### `metrics.py` - Prometheus 指标定义

```python
from prometheus_client import Counter, Histogram

llm_invoke_count = Counter(
    "llm_invoke_count", "llm_invoke_count", ["llm", "status", "run_name"]
)
llm_invoke_latency_second = Histogram(
    "llm_invoke_latency_second", "llm_invoke_latency_second", ["llm", "run_name"]
)
llm_invoke_input_length = Histogram(
    "llm_invoke_input_length", "llm_invoke_input_length", ["llm", "run_name"]
)
llm_invoke_output_length = Histogram(
    "llm_invoke_output_length", "llm_invoke_output_length", ["llm", "run_name"]
)
```

-   **指标定义**: 这段代码非常直观，它使用 `prometheus_client` 库定义了四个全局指标。这些指标对象在应用启动时创建一次。
-   **使用方式**: 在应用的其他地方（如 `di_brain/llms/global_llm.py` 中的 `invoke_msg_with_llm` 函数），可以通过导入这些指标对象并调用其方法来更新指标值。
    ```python
    # Example usage in another module
    from di_brain.monitor.metrics import llm_invoke_count
    
    # ... inside a function after an LLM call ...
    llm_invoke_count.labels(
        llm=state["model"], status="success", run_name=state["run_name"]
    ).inc()
    ```
    这种将指标定义和使用分离的方式，使得代码结构清晰，易于管理。

### `di_brain/monitor/`

`di_brain/monitor/` 目录用于实现对应用性能和状态的监控。它通过集成 Prometheus 客户端来定义和暴露一系列关键指标，从而可以追踪 LLM 的调用次数、延迟、输入输出长度等，为系统的可观测性提供了基础。

#### `metrics.py`

该文件定义了所有用于监控的 Prometheus 指标。这些指标覆盖了 LLM 调用的各个方面，包括：

-   `llm_invoke_count`: 一个计数器（Counter），用于统计 LLM 调用的总次数。通过标签（`llm`, `status`, `run_name`）可以区分不同模型、不同运行状态（成功/失败）和不同业务场景（`run_name`）的调用。
-   `llm_invoke_latency_second`: 一个直方图（Histogram），用于记录 LLM 调用的延迟。可以帮助识别性能瓶颈。
-   `llm_invoke_input_length` 和 `llm_invoke_output_length`: 两个直方图，分别用于记录 LLM 调用输入和输出的长度。这对于监控 token 使用量和成本分析非常有用。

通过这些标准化的指标，运维团队可以轻松地将 DI-Brain 集成到现有的监控告警平台（如 Grafana），实现对系统健康状况的实时监控和异常告警。

```python
from prometheus_client import Counter, Histogram

llm_invoke_count = Counter(
    "llm_invoke_count", "llm_invoke_count", ["llm", "status", "run_name"]
)
llm_invoke_latency_second = Histogram(
    "llm_invoke_latency_second", "llm_invoke_latency_second", ["llm", "run_name"]
)
llm_invoke_input_length = Histogram(
    "llm_invoke_input_length", "llm_invoke_input_length", ["llm", "run_name"]
)
llm_invoke_output_length = Histogram(
    "llm_invoke_output_length", "llm_invoke_output_length", ["llm", "run_name"]
)
```

### `di_brain/router/`

`di_brain/router/` 目录是整个 DI-Brain 应用的“大脑”或总指挥中心。它实现了一个基于 LangGraph 的复杂代理（Agent），负责接收用户的所有请求，理解其意图，并将其分发给下游的各个功能模块（如 Text-to-SQL、SQL 修复、BI 报表生成等）。这种中心化的路由设计使得系统具有高度的灵活性和可扩展性。

#### `tool_router.py`

这是路由模块的核心文件，定义了总代理（Common Agent）的工作流程（Graph）。这个代理的核心思想是利用一个强大的 LLM，通过工具调用（Tool Calling）的能力来决定执行哪个下游任务。

**关键组件和逻辑**：

1. **StateGraph 定义**: `tool_router.py` 通过 `StateGraph(CommonAgentState)` 定义了一个图，其状态由 `CommonAgentState` 维护。这个状态对象包含了对话的全流程信息。

2. **工具集（Tools）**: 代理被赋予了一系列工具，每个工具对应一个下游的核心功能。这些工具包括：

   *   `ask_human`: 用于在信息不足时向用户提问。
   *   `data_discovery`: 数据发现，可能用于调用数据范围澄清代理。
   *   `generate_sql`: 调用 Text-to-SQL 功能。
   *   `fix_sql`: 调用 SQL 修复功能。
   *   `explain_sql`: 调用 SQL 解释功能。
   *   `execute_sql_and_analyze_result`: 调用 Chat BI 功能执行 SQL 并生成图表。
   *   `datasuite_faq`: 用于回答关于数据平台的常见问题（FAQ），可能是一个 RAG 应用。
   *   `search_log`: 搜索日志。
   *   `data_suite_expert`: 一个专家系统，用于处理特定领域的问题。

   ```python
   tools = [
       ask_human,
       data_discovery,
       generate_sql,
       fix_sql,
       explain_sql,
       datasuite_faq,
       execute_sql_and_analyze_result,
       search_log,
       data_suite_expert,
   ]
   common_tool = CommonChatTools(ROUTER_MODEL, tools)
   ```

3. **核心路由逻辑**:

   *   图的入口点是 `intent_route`，它会根据初始输入判断是直接调用某个工具，还是需要通过 LLM 来决定下一步操作。
   *   `invoked_common_agent_node` 节点是主要决策节点。它会调用 LLM，让 LLM 根据当前对话状态和可用工具列表，生成一个或多个工具调用请求。
   *   `tool_call_node` 节点负责执行 LLM 请求的工具。
   *   `action_dispatch_node` 节点在工具执行后或 LLM 调用后进行逻辑分发，决定是结束流程、返回结果给用户，还是继续调用 LLM 进行下一步规划。

4. **图的构建**: 使用 `StateGraph` 将所有节点和边连接起来，构建一个可执行的工作流。条件边（`add_conditional_edges`）被广泛使用，以实现复杂的逻辑判断和路由。

   ```python
   def build_common_chat_graph():
       common_agent_workflow = StateGraph(CommonAgentState)
       common_agent_workflow.add_node("invoke_common_agent", invoked_common_agent_node)
       common_agent_workflow.add_node("tool_call_node", tool_call_node)
       common_agent_workflow.add_node("action_dispatch_node", action_dispatch_node)
       common_agent_workflow.add_node("direct_tool_node", direct_tool_node)
       common_agent_workflow.add_node("response_to_user_chain", response_to_user_chain)
   
       common_agent_workflow.add_conditional_edges(
           START, intent_route, {"invoke_common_agent", "direct_tool_node"}
       )
       common_agent_workflow.add_edge("invoke_common_agent", "action_dispatch_node")
       common_agent_workflow.add_edge("tool_call_node", "action_dispatch_node")
       common_agent_workflow.add_edge("response_to_user_chain", END)
   
       return common_agent_workflow.compile()
   ```

#### `common_agent_state.py`

该文件定义了路由代理的状态对象 `CommonAgentState`。这个状态对象在整个图的执行过程中传递，并且每个节点都可以读取或修改它。它包含了非常丰富的信息，例如：

-   用户的原始问题 (`question`)
-   对话历史 (`messages`)
-   会话 ID (`session_id`, `chat_id`)
-   用户选择的数据表 (`selected_tables`)
-   SQL 方言 (`sql_dialect`)
-   工具调用的输入输出信息 (`session_tool_call_info`, `chat_tool_call_info`)
-   内部错误信息 (`internal_error_message`)

这个全面的状态设计是代理能够处理复杂、多轮对话的关键。

```python
class CommonAgentState(BaseState):
    max_execute_second: Optional[float] = None
    start_unix_timestamp: Optional[float] = None
    ask_human: Optional[str] = None
    question: Optional[str] = None
    selected_tables: Optional[List[Dict[str, Any]]] = None
    chat_id: Optional[int] = None
    session_id: Optional[int] = None
    # ... more fields
    session_tool_call_info: Optional[ToolCallInfo] = None
    chat_tool_call_info: Optional[ToolCallInfo] = None
    has_internal_error: bool = False
    internal_error_message: Optional[str] = None
```

#### `common_agent_prompt.py`

这个文件包含了指导路由器 LLM 如何行动的提示（Prompt）。它通常会包含一个系统消息（System Message），向 LLM 解释它的角色、可用的工具以及如何根据用户问题选择合适的工具。这是塑造代理行为和能力的核心部分。

总结来说，`di_brain/router/` 模块通过一个强大的、基于工具的代理，将项目的所有子功能模块有机地串联起来，形成一个统一的、智能的对话式数据交互入口。

### `di_brain/text2sql/`

`di_brain/text2sql/` 是负责将自然语言问题转换为可执行 SQL 查询的核心模块。它采用了一个由 LangGraph 精心编排的、复杂的多步骤流程，以确保生成 SQL 的准确性和正确性。该流程涉及几个关键阶段：使用并行策略进行初始 SQL 生成、对生成的 SQL 进行验证，以及一个用于修复任何错误的自校正循环。

#### `text2sql_graph.py`

`text2sql_graph.py` 文件定义了此流程的主工作流。它充当一个监督图（supervisor graph），负责协调整个 Text-to-SQL 过程。该图的一个关键特性是其**自校正能力**。在生成初始 SQL 查询后，它会被传递到一个验证节点 (`node_validate_sql`)。如果验证失败，图会将状态路由到一个 `fix_sql` 节点，该节点会尝试纠正错误。这个循环会一直持续，直到 SQL 有效或达到超时。主图接收来自初始生成图 (`text2sql_basic_graph`) 的输出，并协调一个验证和校正的循环。

```python
def create_graph():
    graph_builder = StateGraph(Text2SQLState)
    graph_builder.add_node("validate_sql", node_validate_sql)
    graph_builder.add_node("fix_sql", node_fix_sql)
    graph_builder.add_node("post_fix_sql_process", node_post_fix_sql_process)

    graph_builder.add_edge(START, "validate_sql")
    graph_builder.add_conditional_edges(
        "validate_sql",
        condition_validate_sql,
        {"post_fix_sql_process": "post_fix_sql_process", "fix_sql": "fix_sql"},
    )
    graph_builder.add_edge("fix_sql", "validate_sql") # Self-correction loop
    graph_builder.add_edge("post_fix_sql_process", END)

    return graph_builder.compile()
```

#### `text2sql_basic_graph.py`

该文件负责 SQL 查询的**初始生成**。它实现了一种“分叉-连接”（fork-join）或并行执行的策略，以提高生成 SQL 的质量。图首先检索必要的上下文（表结构、文档等）。然后，它**并行运行两种不同的 SQL 生成方法**：

1.  **规划与反思 (Plan and Reflect)** (`generate_sql_plan_and_reflect_single_call`): 此方法首先创建一个“计划”，概述如何构建 SQL，然后根据该计划生成 SQL。
2.  **分而治之 (Divide and Conquer)** (`generate_sql_divide_and_conquer_single_call`): 此方法将用户复杂的问题分解为更简单的子问题，为每个子问题生成 SQL，然后将它们组装成最终的查询。

两种方法都生成 SQL 查询后，一个 `choose_better_sql` 节点会使用 LLM 来判断哪个查询更好。这种竞争性方法增加了生成高质量初始查询的可能性。

```python
def build_text2sql_basic_graph() -> StateGraph:
    workflow = StateGraph(Text2SQLAskHumanState, ...)
    # ... (Add nodes for context retrieval) ...
    workflow.add_node("generate_sql_plan_and_reflect_single_call", ...)
    workflow.add_node("generate_sql_divide_and_conquer_single_call", ...)
    workflow.add_node("choose_better_sql", choose_better_sql)

    # ... (Add edges for context retrieval) ...
    
    # Run both SQL generation methods in parallel
    workflow.add_edge("process_context", "generate_sql_plan_and_reflect_single_call")
    workflow.add_edge("process_context", "generate_sql_divide_and_conquer_single_call")
    
    # Choose better SQL from both SQL generation methods
    workflow.add_edge("generate_sql_plan_and_reflect_single_call", "choose_better_sql")
    workflow.add_edge("generate_sql_divide_and_conquer_single_call", "choose_better_sql")
    
    workflow.add_edge("choose_better_sql", "validate_sql")
    # ... (Add edges for validation and fixing loop) ...
    
    return workflow.compile()
```

#### `text2sql_prompt.py`

这个文件包含了用于指导 LLM 生成 SQL 的所有提示。

-   对于“规划与反思”方法，`SQL_RESPONSE_GENERATESQL_PLANNER_SINGLE_CALL` 指导 LLM 创建详细的计划，而 `SQL_RESPONSE_GENERATESQL` 则使用该计划来编写最终的 SQL。
-   对于“分而治之”方法，`SQL_RESPONSE_DIVIDE_PROMPT` 用于分解问题，`SQL_RESPONSE_CONQUER_PROMPT` 为子问题生成伪 SQL，最后 `SQL_RESPONSE_ASSEMBLE_PROMPT_SINGLE_CALL` 将它们组合成最终的 SQL。

#### `state.py` 和 `text2sql_step.py`

-   `state.py`: 定义了在整个工作流中传递的状态对象 (`Text2SQLState`)，用于跟踪问题、上下文、生成的 SQL 以及任何错误。
-   `text2sql_step.py`: 实现了图中的各个具体步骤（节点），例如检索文档、处理上下文以及调用 LLM。

総じて言えば、`text2sql`モジュールは、並列生成戦略と自己修正ループを活用して、自然言語を確実にSQLに変換する、堅牢で多段階のパイプラインです。

### `di_brain/tools/`

`di_brain/tools/` 目录提供了一系列核心的基础工具，这些工具被上层的 Agent 或 Chain 用来执行具体任务，例如执行 SQL、进行权限验证等。

#### SQL 执行

-   `common_tool.py`: 定义了一个抽象基类 `SQLExecutor`，为不同类型的 SQL 执行器提供了一个统一的接口。
-   `presto_executor.py`: 实现了 `SQLExecutor`，专门用于在 Presto 引擎上执行 SQL。它通过调用一个调度器服务来异步提交查询任务，并轮询任务状态直到任务完成或失败。

#### 权限验证与行级访问控制 (Row-Access Control)

这是该目录下一个非常关键的功能，确保了数据查询的安全性。

- `ram_sql_auth_tool.py`: 该工具负责**列级别**的权限校验。它会调用 RAM（Row Access Management）服务的 API，检查用户是否有权限访问 SQL 查询中涉及的特定列。

- `row_access/ram_row_access_tool.py`: 此工具更进一步，负责获取**行级别**的数据访问策略。它同样调用 RAM API，但返回的是需要应用到表上的具体过滤条件（例如，`region = 'SG'`）。

- `row_access/sql_rewrite.py`: 这是实现行级访问控制的核心。它接收 `ram_row_access_tool.py` 获取到的过滤条件，然后利用 ANTLR 解析器（见下文）对原始 SQL 进行解析，并将这些过滤条件**动态地注入**到 SQL 查询的 `WHERE` 子句中。这样，即使用户查询 `SELECT * FROM orders`，系统也能自动将其改写为 `SELECT * FROM orders WHERE region = 'SG'`，从而实现了行级数据的隔离。

  ```python
  def rewrite_sql_with_access_conditions(
      sql: str, access_conditions: Dict[str, str]
  ) -> SQLRewriteResult:
      """
      Convenience function to rewrite SQL with access conditions.
      """
      rewriter = SQLRewriter(access_conditions)
      return rewriter.rewrite_sql(sql)
  ```

#### SQL 解析器

-   `sql_parser/`: 这个目录包含了使用 ANTLR（一个强大的解析器生成器）生成的 HPL/SQL 解析器。`Hplsql.g4` 是 ANTLR 的语法文件，定义了 SQL 的语法规则。基于这个文件，ANTLR 生成了 `HplsqlLexer.py` (词法分析器), `HplsqlParser.py` (语法分析器), 和 `HplsqlVisitor.py` (访问者模式，用于遍历语法树)。`sql_rewrite.py` 正是利用这个解析器来准确地理解 SQL 结构，从而安全地修改它。

总的来说，`di_brain/tools/` 目录不仅提供了底层的执行能力，还通过与 RAM 服务的集成以及强大的 SQL 解析和重写能力，构建了一套完善的数据安全和权限控制体系。

### `di_brain/trace/`

`di_brain/trace/` 目录负责实现系统的可观测性（Observability），特别是对于 LangChain 应用的追踪和调试。它通过集成 LangFuse（一个开源的 LLM 应用可观测性平台）来实现这一功能。

#### `tracer.py`

`tracer.py` 是追踪功能的入口。它提供了一个工厂函数 `get_default_tracer()`，用于创建和配置一个 LangFuse 的回调处理器（Callback Handler）。这个处理器可以被注入到任何 LangChain 的 `Runnable` 或 `Graph` 中，从而自动捕获执行过程中的所有事件，如链的调用、LLM 的输入输出、工具的使用等。

配置信息（如 LangFuse 服务器地址、公钥/私钥）都是通过环境变量加载的，这使得不同环境（开发、测试、生产）可以使用不同的追踪后端。

```python
def get_default_tracer(tags: Optional[list[str]] = None):
    secret_key = os.environ.get(
        "LANGFUSE_SECRET_KEY", "..."
    )
    public_key = os.environ.get(
        "LANGFUSE_PUBLIC_KEY", "..."
    )
    host = os.environ.get(
        "LANGFUSE_HOST", "http://observe.dibrain.data-infra.shopee.io"
    )

    # ...
    return CompassTokenCallbackHandler(
        secret_key=secret_key,
        public_key=public_key,
        host=host,
        tags=tags,
    )
```

#### `compass_token_callback.py`

`compass_token_callback.py` 文件中定义了 `CompassTokenCallbackHandler`，这是一个自定义的回调处理器，它继承自 LangFuse 的 `CallbackHandler`。这个自定义处理器的主要目的是为了更精细地控制追踪数据的捕获，特别是为了正确处理和记录来自特定模型（如 Compass AI）的 token 使用情况，包括缓存和推理所消耗的 token。

它重写了 `on_llm_end`, `on_chain_start`, `on_chain_end` 等关键事件的处理方法，确保在 LangChain 执行的每个环节，都能将相关的输入、输出、元数据和 token 使用量准确地发送到 LangFuse 服务器。这使得开发人员可以在 LangFuse 的 UI 界面上，清晰地看到每一次请求的完整执行路径、每个步骤的耗时和成本，极大地提高了调试效率和系统的透明度。

通过这个模块，DI-Brain 实现了生产级的应用监控和追踪能力，这对于维护一个复杂的、基于 LLM 的系统至关重要。

### `di_brain/translator/`

`di_brain/translator/` 是一个简单而实用的模块，其主要功能是提供文本翻译服务，特别是将输入文本翻译成英文。

#### `llm_translator.py`

`llm_translator.py` 文件中定义了一个简单的 LangChain `Runnable` 链，专门用于翻译任务。它利用一个 LLM（通过 `GET_SPECIFIC_LLM_WITH_FALLBACK` 获取）来执行翻译。

其核心是一个精心设计的提示（`TRANSLATE_PROMPT`），该提示明确指示 LLM 将输入文本翻译成英文，并且强调只返回翻译后的文本，不包含任何额外的介绍、解释或特殊格式。这种精确的提示工程确保了翻译链的输出是干净、可直接使用的。

这个模块虽然简单，但在处理多语言输入或需要将非英文术语标准化为英文时非常有用。

### `di_brain/utils/`

`di_brain/utils/` 目录提供了一系列通用的辅助工具函数和基类，这些工具在项目的多个模块中被复用，以减少代码冗余并统一行为。

-   `base_state.py`: 定义了一个 `BaseState` 类，作为项目中所有 LangGraph 状态对象的基类。它包含了一些通用的字段，如 `messages` (用于存储对话历史), `interruption_reason` (中断原因), 和 `timeout_timestamp` (超时时间戳)。通过继承 `BaseState`，各个功能模块的图（Graph）可以拥有统一的状态管理基础。

-   `json_utils.py`: 提供了一组用于处理 JSON 字符串的实用函数。
    -   `extract_json_code(text: str) -> str`: 这个函数非常关键，因为 LLM 的输出有时不稳定，可能会在 JSON 响应前后添加额外的文本或 markdown 代码块标记 (```json ... ```)。此函数负责从原始文本中稳健地提取出有效的 JSON 部分，提高了系统的容错性。
    -   `check_str_is_json(s: str, schema: dict = None) -> tuple`: 该函数用于验证一个字符串是否是合法的 JSON，并且可以选择性地根据一个 JSON Schema 进行结构验证。

-   `user_utils.py`: 包含与用户相关的业务逻辑函数。例如，`get_user_market_region` 函数根据用户的区域和部门信息，推断出用户所属的市场区域。这种业务逻辑的封装使得代码更清晰，易于维护。

### `di_brain/vectorstores/`

`di_brain/vectorstores/` 目录是对 LangChain 中现有的向量存储库功能进行扩展和定制的模块，主要围绕 Milvus 向量数据库。

-   `milvus_with_query.py`: 这个文件定义了 `MilvusWithQuery` 类，它继承自 LangChain 的 `Milvus` 类。其主要扩展是增加了一个 `query_by_expresion` 方法。这个方法允许用户直接使用 Milvus 的布尔表达式进行元数据过滤查询，而不是依赖于向量相似度搜索。这对于需要根据精确的元数据条件（例如，`schema = 'dwd'`）来检索文档的场景非常有用。

-   `milvus_retriever.py`: 该文件定义了 `MilvusWithSimilarityRetriever`，这是一个自定义的 LangChain `Retriever`。它包装了 `MilvusWithQuery` 向量存储，并提供了更高级和灵活的检索功能：
    -   **动态过滤**: `gen_filter_from_context` 方法可以从 LangChain 运行时的 `metadata` 中提取过滤条件，并将其动态地应用到 Milvus 查询中。这使得检索可以根据上游链或图的状态进行自适应调整。
    -   **相似度分数后处理**: `_get_relevant_documents` 方法在执行相似度搜索后，会将相似度分数（score）附加到返回的 `Document` 对象的元数据中，并可以根据配置的阈值（`score_threshold`）对结果进行后过滤。
    -   **支持多种搜索类型**: 除了标准的相似度搜索，它还支持 `mmr` (Maximal Marginal Relevance)，以增加检索结果的多样性。

通过这些定制化的类，DI-Brain 能够更精细地控制 Milvus 向量数据库的检索过程，实现了动态元数据过滤和灵活的后处理，从而提高了检索的准确性和相关性。

### `di_brain` 根目录核心文件

在 `di_brain` 包的根目录下，还有一些不属于特定子模块但对整个应用至关重要的文件。

- `main.py`: 这是整个 DI-Brain 应用的**入口点**。它使用 **FastAPI** 框架构建了一个 Web 服务器，并通过 **LangServe** 将项目中的各个核心功能（如 `ask_data`, `text2sql`, `fix_sql`, `tool/router` 等）暴露为 API 端点。每个端点都通过 `add_routes` 函数进行注册，并指定了输入输出的数据模型。此外，`main.py` 还集成了 Prometheus 监控（`Instrumentator`）和 CORS 中间件，使其成为一个生产级的服务。

  ```python
  app = FastAPI()
  # ... (Middleware and Instrumentator setup) ...
  
  add_routes(
      app,
      ask_data_global_graph,
      path="/ask_data_global",
      # ...
  )
  
  add_routes(
      app,
      text2sql_basic_compass_graph,
      path="/text2sql",
      # ...
  )
  
  add_routes(
      app,
      common_agent_chain,
      path="/tool/router",
      # ...
  )
  
  # ... (other routes) ...
  ```

- `chain.py`: 这个文件之前已经分析过，它是一个**链工厂**，负责构建和组装各种 LangChain `Runnable` 序列，是模块化构建 RAG 和 Agent 的核心。

- `custom_retriever.py`: 虽然代码中提供的是一个返回硬编码结果的 `VectorStoreRetrieverCustom`，但它展示了如何自定义一个 LangChain `Retriever`。在实际应用中，这种自定义能力对于实现复杂的检索逻辑（如混合搜索、多路召回等）至关重要。

- `logger.py`: 提供了一个标准化的日志记录器（logger）。它通过环境变量来控制日志级别，确保在开发环境（dev）可以输出详细的调试信息，而在生产环境则输出更简洁的日志。

## `benchmark` 目录分析

`benchmark` 目录包含了用于对项目核心功能进行性能评估和测试的脚本与 Notebook。

-   `fix_sql/`: 这个子目录专门用于测试和评估 **SQL 修复** (`fix_sql`) 功能。
    -   `fix_sql_bench.py` 和 `fix_sql_bench_v2.py`: 这些是主要的评测脚本。它们可能会读取一个包含错误 SQL 和正确 SQL 的数据集（例如 CSV 文件），然后运行 `fix_sql` 图来修复这些错误 SQL，并将其输出与标准答案进行比较，以计算准确率等指标。
    -   `filter_csv.py`: 用于预处理或筛选评测数据集。
    -   `.ipynb` 文件: 这些是 Jupyter Notebooks，可能用于对评测结果进行可视化分析，或者进行更具交互性的探索性测试。
    -   `run_script.sh` 和 `run_script_v2.sh`: 用于批量运行评测脚本的 shell 脚本。

通过这些基准测试，开发团队可以量化地评估 `fix_sql` 模块在不同场景下的表现，持续迭代和优化其性能。

## 项目根目录文件分析

-   `pyproject.toml` 和 `poetry.lock`: 这些是 **Poetry** 包管理工具的配置文件。`pyproject.toml` 定义了项目的基本信息、依赖项、开发依赖项以及脚本等。`poetry.lock` 文件则锁定了所有依赖项的具体版本，以确保在不同环境中都能有一致的、可复现的安装。
-   `Dockerfile`: 定义了如何将 DI-Brain 应用打包成一个 **Docker 镜像**。这对于应用的容器化部署至关重要，它确保了应用及其所有依赖可以在任何支持 Docker 的环境中以一致的方式运行。
-   `Makefile`: 提供了一组简便的命令行快捷方式（如 `make build`, `make run`），用于自动化常见的开发任务，如构建 Docker 镜像、运行容器、执行测试等，简化了开发和部署流程。
-   `README.md`: 项目的主说明文档，通常包含了项目介绍、安装指南、使用方法等关键信息。

## 总结

DI-Brain 是一个高度模块化、基于 LangChain 和 LangGraph 构建的复杂对话式 AI 应用。其核心架构围绕一个**中心化的路由代理** (`di_brain/router/`) 展开，该代理智能地将用户请求分发给下游的各种专业化子系统，如：

-   **RAG (Retrieval-Augmented Generation)**: 通过 `ask_data` 和 `ask_data_global` 实现，结合了向量搜索（Milvus）、关键词搜索（Elasticsearch）和知识图谱，为用户提供精准的知识问答。
-   **Text-to-SQL**: 在 `text2sql` 模块中实现，采用并行生成和自校正循环的先进策略，确保了 SQL 生成的准确性。
-   **Chat BI**: `chat_bi` 模块将数据查询、执行、结果分析和图表生成融为一体，提供了端到端的数据可视化体验。
-   **SQL 修复**: `fix_sql` 模块利用 LangGraph 的循环能力，实现了对错误 SQL 的自动诊断和修复。

整个系统通过强大的**基础服务**支撑：

-   **可配置的 LLM 和 Embedding 工厂** (`llms`, `embeddings`) 提供了模型选择的灵活性和可扩展性。
-   **完善的监控和追踪** (`monitor`, `trace`) 通过集成 Prometheus 和 LangFuse，为系统的可观测性提供了保障。
-   **严格的权限控制** (`tools/row_access`) 通过与 RAM 服务的集成和动态 SQL 重写，确保了数据安全。

项目在工程实践上也表现出色，使用了 **FastAPI** 和 **LangServe** 构建了健壮的 API 服务，通过 **Poetry** 管理依赖，并利用 **Docker** 和 **Makefile** 实现了标准化的开发与部署流程。

总而言之，DI-Brain 是一个设计精良、功能强大、工程实践优秀的对话式数据智能平台。

