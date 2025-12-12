# Common Agent（Supervisor Agent）功能详解

## 1. 架构概述

Common Agent 是项目中的核心协调组件，也称为 Supervisor Agent（监督代理），负责：
- **意图识别（Intent Identify）**：理解用户问题的意图
- **查询重写（Rewrite Query）**：将模糊或简单的用户输入重写为清晰的问题
- **计划生成（Gen Plan）**：生成问题解决的执行计划
- **代理调用（Agent Call Cmd）**：调用相应的子代理/工具完成任务

### 1.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Common Agent Graph (LangGraph)                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│   START                                                                   │
│     │                                                                     │
│     ▼                                                                     │
│  ┌──────────────┐                                                        │
│  │ intent_route │ ─────────────────────────────────────┐                 │
│  └──────┬───────┘                                      │                 │
│         │                                              │                 │
│         │ (普通流程)                                   │ (直接工具调用)   │
│         ▼                                              ▼                 │
│  ┌──────────────────┐                        ┌─────────────────┐         │
│  │invoke_common_agent│                        │ direct_tool_node │         │
│  │  (LLM 意图识别)    │                        │  (直接调用工具)   │         │
│  └────────┬─────────┘                        └────────┬────────┘         │
│           │                                           │                 │
│           ▼                                           │                 │
│  ┌────────────────────┐                               │                 │
│  │action_dispatch_node│ ◄─────────────────────────────┘                 │
│  │   (动作分发)        │                                                 │
│  └─────────┬──────────┘                                                 │
│            │                                                             │
│    ┌───────┼───────┬──────────────┐                                     │
│    │       │       │              │                                     │
│    ▼       ▼       ▼              ▼                                     │
│  END  tool_call  invoke_      response_                                 │
│       _node     common_agent  to_user_chain                             │
│         │                          │                                     │
│         │                          │                                     │
│         └──────────────────────────┴──────────▶ END                     │
│                                                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 核心代码位置

| 组件 | 文件路径 |
|------|----------|
| Graph 构建 | `di_brain/router/tool_router.py` → `build_common_chat_graph()` |
| System Prompt | `di_brain/router/common_agent_prompt.py` |
| 状态定义 | `di_brain/router/common_agent_state.py` |
| 工具类 | `di_brain/router/common_agent_tools.py` |

---

## 2. 意图识别（Intent Identify）

### 2.1 实现机制

意图识别通过 **LLM + Tool Binding** 实现。系统将所有可用工具绑定到 LLM，让 LLM 根据用户问题自主选择合适的工具。

```python
# di_brain/router/tool_router.py

# 定义可用工具列表
tools = [
    ask_human,           # 向用户询问更多信息
    data_discovery,      # 数据发现（查找表/列）
    generate_sql,        # 生成 SQL
    fix_sql,             # 修复 SQL 错误
    explain_sql,         # 解释 SQL
    datasuite_faq,       # DataSuite 平台 FAQ
    execute_sql_and_analyze_result,  # 执行 SQL 并分析结果
    search_log,          # 搜索日志（LogQL）
    data_suite_expert,   # DataSuite 专家问答
    reject_out_of_scope, # 拒绝超出范围的问题
]

# 创建工具管理类
common_tool = CommonChatTools(ROUTER_MODEL, tools)

# 准备带工具的 LLM
router_llm = common_tool.prepare_llm_with_tools()
```

### 2.2 LLM 配置

```python
# di_brain/router/tool_router.py

ROUTER_MODEL = "gpt-4.1-mini"  # 使用 GPT-4.1-mini 作为路由模型

class CommonChatTools:
    def prepare_llm_with_tools(self):
        """Prepare the LLM with the tools"""
        gpt_4o = GET_SPECIFIC_LLM(
            self.router_model, extra_config={"disable_streaming": True}
        )
        # 移除 datasuite_expert 工具（特殊处理）
        bind_tool_list = [
            tool for tool in self.tools if tool.name != "data_suite_expert"
        ]
        return gpt_4o.bind_tools(bind_tool_list)
```

### 2.3 意图识别节点

```python
# di_brain/router/tool_router.py

def invoked_common_agent_node(state: CommonAgentState):
    """调用 LLM 进行意图识别"""
    # 准备消息列表
    invoke_llm_messages = []
    if state["messages"]:
        for msg in state["messages"]:
            # 处理 data_discovery 工具消息，移除冗余信息
            if isinstance(msg, ToolMessage) and msg.name == "data_discovery":
                if msg.content.find('"related_docs":') != -1:
                    json_content = json.loads(msg.content)
                    json_content.pop("related_docs", None)
                    json_content.pop("recommend_tables", None)
                    # ... 精简数据
                    sample_tool_msg = copy.copy(msg)
                    sample_tool_msg.content = json.dumps(json_content)
                    invoke_llm_messages.append(sample_tool_msg)
            # ... 其他消息类型处理
            else:
                invoke_llm_messages.append(msg)

    # 调用 LLM
    try:
        session_id = state.get("session_id")
        if session_id:
            prompt_cache_key = str(session_id) + "_cache"
            merged_extra_body = {
                **instance_extra_body,
                "prompt_cache_key": prompt_cache_key,  # 启用 prompt 缓存
            }
            response = router_llm.invoke(invoke_llm_messages, extra_body=merged_extra_body)
        else:
            response = router_llm.invoke(invoke_llm_messages)

        state["messages"].append(response)
        return response
    except Exception as e:
        logger.error(f"Exception during invoked_common_agent_node: {e}")
        state["has_internal_error"] = True
        raise
```

### 2.4 支持的意图类型

| 意图 | 对应工具 | 描述 |
|------|----------|------|
| 数据发现 | `data_discovery` | 查找相关的表、列、元数据 |
| 生成 SQL | `generate_sql` | 根据用户需求生成 Presto SQL |
| 执行 SQL | `execute_sql_and_analyze_result` | 执行 SQL 并分析结果 |
| 修复 SQL | `fix_sql` | 修复 SQL 语法或逻辑错误 |
| 解释 SQL | `explain_sql` | 解释 SQL 查询的含义 |
| 平台问答 | `datasuite_faq` | 回答 DataSuite 平台使用问题 |
| 日志查询 | `search_log` | 生成 LogQL 查询语句 |
| 用户询问 | `ask_human` | 向用户询问更多信息 |
| 拒绝处理 | `reject_out_of_scope` | 拒绝超出能力范围的请求 |

---

## 3. 查询重写（Rewrite Query）

### 3.1 实现原理

查询重写通过 **System Prompt** 中的规则指导 LLM 完成，不需要单独的代码逻辑。

```python
# di_brain/router/common_agent_prompt.py

common_chat_prompt = """
...

### **Workflow & Prioritization**

...

2. **Rewriting Simple Phrases:**
    * If the user's current input is a simple phrase (e.g., "yes", "execute it", "go ahead"), 
      rewrite it into a question with a clear intention based on the previous conversation 
      before proceeding.

...
"""
```

### 3.2 重写场景示例

| 用户输入 | 上下文 | 重写结果 |
|----------|--------|----------|
| "yes" | 之前询问是否执行 SQL | "Please execute the SQL query and analyze the results" |
| "go ahead" | 之前展示了数据表 | "Generate SQL based on the previously found tables" |
| "execute it" | 之前生成了 SQL | "Execute the previously generated SQL and provide data insights" |

### 3.3 工作流程

```
用户输入 "yes"
     │
     ▼
┌─────────────────────────┐
│  invoke_common_agent    │
│  LLM 接收完整对话历史    │
│  识别 "yes" 指的是什么   │
│  根据上下文重写为完整问题 │
└───────────┬─────────────┘
            │
            ▼
    选择合适的工具执行
```

---

## 4. 计划生成（Gen Plan）

### 4.1 计划生成规则

计划生成通过 System Prompt 中的详细规则实现，LLM 根据这些规则生成执行计划。

```python
# di_brain/router/common_agent_prompt.py

common_chat_prompt = """
...

### **Workflow & Prioritization**

1. **Problem Analysis & Process Generation:**
    * Analyze and understand the user's problem.
    * Determine the user's intention. 
    * Generate a problem-solving process based on the capabilities, 
      selecting the appropriate function for each step.
    * The rules to distinguish use intent between Generate SQL and Data Insight:
        * **Generate SQL**: Explicitly asks for the SQL code or calculate method
        * **Execute SQL & Analyze Results**: user's primary goal is to get the answer/result
    * If the question is beyond your capabilities, use the `reject_out_of_scope` tool

...

### **Execution Rules**

1. **SQL Generation Prerequisite:** Ensure you know Data Table details metadata 
   (including table description and column informations) before SQL generation.
   
2. **SQL Execution Prerequisite:** Before executing SQL, confirm that the SQL 
   for the user's question is available.

3. **`execute_sql_and_analyze_result` Priority:** If the user's question involves 
   data visualization, chart display, result display, SQL execution, data insight, 
   or analysis, prioritize using the `execute_sql_and_analyze_result` tool.

...

12. **Ultimate goal:**
    * First, before analyzing the problem, clarify the ultimate intention of 
      the entire round of interaction.
    * If the user's question involves data search or analysis, the ultimate goal 
      must be Execute SQL and Analyze Results.
    * Behavior must be consistent with the ultimate intention.

...
"""
```

### 4.2 典型执行计划示例

**场景：用户想查询过去30天的GMV数据**

```
用户问题："What was the daily GMV in the past 30 days?"

执行计划：
1. 识别意图 → 数据分析（ultimate goal: execute_sql_and_analyze_result）
2. 调用 data_discovery → 查找包含 GMV 数据的表
3. 调用 generate_sql → 生成查询 SQL
4. 调用 execute_sql_and_analyze_result → 执行并分析结果
```

**场景：用户只想生成SQL**

```
用户问题："Generate a SQL to calculate daily active users"

执行计划：
1. 识别意图 → 生成SQL（ultimate goal: generate_sql）
2. 调用 data_discovery → 查找用户活动表
3. 调用 generate_sql → 生成 DAU 计算 SQL
4. 返回 SQL 给用户（不执行）
```

### 4.3 最终意图（Final Intent）

每个工具调用都包含 `final_intent` 参数，用于标识用户的最终目标：

```python
# di_brain/router/tool_router.py

@tool
def data_discovery(
    user_question: str,
    ...
    final_intent: Annotated[
        Literal[
            "data_discovery",
            "generate_sql",
            "fix_sql",
            "execute_sql_and_analyze_result",
            "search_log",
        ],
        """The final, high-level intent or goal of the user's question. 
        This indicates the overall task the user wants to accomplish after the whole interaction.
        If the user's problem involves data search or analysis, 
        the ultimate goal must be execute_sql_and_analyze_result.""",
    ] = None,
):
    ...
```

---

## 5. 代理调用（Agent Call Cmd）

### 5.1 Graph 构建

```python
# di_brain/router/tool_router.py

def build_common_chat_graph():
    """构建 Common Agent 的 LangGraph 工作流"""
    common_agent_workflow = StateGraph(CommonAgentState)
    
    # 添加节点
    common_agent_workflow.add_node("invoke_common_agent", invoked_common_agent_node)
    common_agent_workflow.add_node("tool_call_node", tool_call_node)
    common_agent_workflow.add_node("action_dispatch_node", action_dispatch_node)
    common_agent_workflow.add_node("direct_tool_node", direct_tool_node)
    common_agent_workflow.add_node("response_to_user_chain", response_to_user_chain)

    # 设置边（流程控制）
    common_agent_workflow.add_conditional_edges(
        START, intent_route, {"invoke_common_agent", "direct_tool_node"}
    )
    common_agent_workflow.add_edge("invoke_common_agent", "action_dispatch_node")
    common_agent_workflow.add_edge("tool_call_node", "action_dispatch_node")
    common_agent_workflow.add_edge("response_to_user_chain", END)

    return common_agent_workflow.compile()
```

### 5.2 初始路由（Intent Route）

```python
# di_brain/router/tool_router.py

def intent_route(state: CommonAgentState):
    """根据 agent_name 决定初始路由"""
    # 某些 Agent 可以直接调用工具，不需要 LLM 判断
    if (
        state.get("agent_name") == "Fix SQL Agent"
        or state.get("agent_name") == "Explain SQL Agent"
        or state.get("agent_name") == "Logify Agent"
        or state.get("agent_name") == "Data Suite Expert"
    ):
        return "direct_tool_node"
    return "invoke_common_agent"
```

### 5.3 动作分发节点（Action Dispatch Node）

这是整个系统的核心调度器，决定下一步执行什么操作：

```python
# di_brain/router/tool_router.py

def action_dispatch_node(state: CommonAgentState):
    """
    Dispatch 规则：
    1. 有工具调用且调用的是 ask_human → 准备问题并结束
    2. 有工具调用且调用其他函数 → 保存参数并跳转到 tool_call_node
    3. 有工具响应且需要 ask_human → 准备问题并结束
    4. 有工具响应且不需要 ask_human → 保存响应并继续
       4.1 如果是 find_data 且没有 selected_tables → 结束（让用户确认）
       4.2 如果是 generate_sql → 结束（让用户确认 SQL）
       4.3 其他情况 → 继续调用 invoke_common_agent
    5. 指定工具调用 → 结束
    """
    
    if state.get("has_internal_error"):
        return Command(goto="response_to_user_chain")
    
    messages = state["messages"]
    last_message = messages[-1]
    
    # 处理 AIMessage 中的工具调用
    if isinstance(last_message, AIMessage) and last_message.tool_calls:
        tool_call_info = last_message.tool_calls[0]
        
        if tool_call_info["name"] == "ask_human":
            # ask_human 工具 → 直接返回给用户
            return Command(
                goto="response_to_user_chain",
                update={"ask_human": tool_call_info["args"]["question"]},
            )
        else:
            # 其他工具 → 保存调用信息并执行
            tool_call_id = tool_call_info.get("id")
            tool_call_name = tool_call_info.get("name")
            
            # 保存 tool_call 映射信息
            session_tool_call_info["tool_call_name_mapping"][tool_call_id] = tool_call_name
            session_tool_call_info["tool_call_input_mapping"][tool_call_id] = tool_call_info["args"]
            
            return Command(
                goto="tool_call_node",
                update={
                    "session_tool_call_info": session_tool_call_info,
                    "chat_tool_call_info": chat_tool_call_info,
                },
            )
    
    # 处理 ToolMessage（工具执行结果）
    elif isinstance(last_message, ToolMessage):
        tool_msg: ToolMessage = last_message
        tool_resp_str = tool_msg.content
        
        # 检查是否需要 ask_human
        if re.search(HAS_ASK_HUMAN_PATTERN, tool_resp_str):
            resp_dict = json.loads(tool_resp_str)
            if resp_dict.get("ask_human"):
                return Command(
                    goto="response_to_user_chain",
                    update={"ask_human": resp_dict.get("ask_human_question")},
                )
        
        # 根据 router_dispatch_strategy 决定下一步
        chat_context = state.get("chat_context")
        if chat_context.get("router_dispatch_strategy") == DISPATCH_STRATEGY_LLM:
            agent_name = common_tool.get_agent_name_by_tool_name(tool_msg.name)
            resp_dict = json.loads(tool_resp_str)
            final_intent = resp_dict.get("final_intent", "")
            
            if agent_name == final_intent:
                # 已达到最终意图 → 返回结果
                return Command(goto="response_to_user_chain")
            else:
                # 未达到最终意图 → 继续执行
                return Command(goto="invoke_common_agent")
        
        # data_discovery 特殊处理
        if tool_msg.name == "data_discovery":
            has_selected_tables = len(state.get("selected_tables", [])) > 0
            if not has_selected_tables:
                return Command(goto="response_to_user_chain")
            else:
                return Command(goto="invoke_common_agent")
        
        # generate_sql → 返回给用户确认
        if tool_msg.name == "generate_sql":
            return Command(goto="response_to_user_chain")
        
        # 其他情况 → 继续执行
        return Command(goto="invoke_common_agent")
```

### 5.4 直接工具调用节点

对于已知意图的请求，可以直接调用对应工具：

```python
# di_brain/router/tool_router.py

def direct_tool_node(state: CommonAgentState):
    """直接调用工具，跳过 LLM 意图识别"""
    tool_name = common_tool.get_tool_name_by_agent_name(state.get("agent_name"))
    tool_call_id = f"call_{str(uuid.uuid4()).replace('-', '')[:24]}"

    args = {}
    
    # 根据 agent_name 构造工具参数
    if state.get("agent_name") == "Data Discovery":
        args = {"user_question": state.get("question")}
    elif state.get("agent_name") == "Fix SQL Agent":
        args = {
            "sql": state.get("original_sql") or state.get("question"),
            "sql_dialect": state.get("sql_dialect") or "Presto SQL",
            "error_info": state.get("error_message") or "",
        }
    elif state.get("agent_name") == "Explain SQL Agent":
        args = {
            "sql": state.get("original_sql") or state.get("question"),
            "sql_dialect": state.get("sql_dialect") or "Presto SQL",
        }
    elif state.get("agent_name") == "Data Suite Expert":
        args = {
            "user_question": state.get("question"),
            "product_name": state.get("data_suite_expert_product_name"),
            "session_id": state.get("data_suite_expert_session_id"),
        }

    # 构造伪 AIMessage 触发工具调用
    tool_call_info = {"name": tool_name, "id": tool_call_id, "args": args}
    fake_ai_message = AIMessage(content="", tool_calls=[tool_call_info])
    state["messages"].append(fake_ai_message)

    return Command(goto="action_dispatch_node")
```

### 5.5 工具定义详解

#### 5.5.1 data_discovery（数据发现）

```python
@tool
def data_discovery(
    user_question: str,  # 用户问题
    config: RunnableConfig,
    state: Annotated[dict, InjectedState],
    selected_tables: List[DataTableModel],  # 用户指定的表
    selected_table_groups: List[TableGroupModel],  # 用户指定的表组
    final_intent: Literal[...] = None,  # 最终意图
):
    """
    数据发现工具的能力：
    1. 分析用户问题并查找相关的数据表（Hive表或ChatDataset）
    2. 获取表的详细信息（schema、列、描述等）
    3. 回答关于数据域/数据表的问题
    """
```

#### 5.5.2 generate_sql（生成SQL）

```python
@tool(args_schema=GenerateSqlInput)
def generate_sql(
    user_question: str,
    related_tables_info: str,  # 相关表信息（来自 data_discovery）
    related_table_source_tool_call_id: str,  # data_discovery 的 tool_call_id
    state: Annotated[dict, InjectedState],
    final_intent: str = None,
):
    """生成或重写 Presto SQL"""
```

#### 5.5.3 execute_sql_and_analyze_result（执行SQL并分析）

```python
@tool(args_schema=ExecuteSqlAndAnalyzeResultInput)
def execute_sql_and_analyze_result(
    sql: str,  # 要执行的 SQL
    find_data_info: str,  # 相关表描述信息
    user_question: str,  # 用户问题
    tool_call_id: str,  # SQL 来源的 tool_call_id
    state: dict,
):
    """
    执行 SQL 并分析结果的能力：
    1. 执行 SQL 查询
    2. 提供数据洞察分析
    3. 提供可视化建议
    """
```

---

## 6. 状态管理

### 6.1 CommonAgentState

```python
# di_brain/router/common_agent_state.py

class CommonAgentState(BaseState):
    """Common Agent 的状态定义"""
    
    # 基础信息
    question: Optional[str] = None
    chat_context: Optional[Dict[str, Any]] = None
    agent_name: Optional[str] = None
    
    # 会话信息
    chat_id: Optional[int] = None
    session_id: Optional[int] = None
    thread_id: Optional[str] = None
    
    # SQL 相关
    sql_dialect: Optional[str] = None
    original_sql: Optional[str] = None
    error_message: Optional[str] = None
    
    # 工具调用追踪
    session_tool_call_info: Optional[ToolCallInfo] = None  # 跨会话
    chat_tool_call_info: Optional[ToolCallInfo] = None     # 当前对话
    
    # 错误处理
    has_internal_error: bool = False
    internal_error_message: Optional[str] = None
    
    # ask_human 相关
    ask_human: Optional[str] = None
    ask_human_sub_tool_name: Optional[str] = None
    ask_human_sub_tool_call_id: Optional[str] = None
    
    # 表选择
    selected_tables: Optional[List[Dict[str, Any]]] = None
    selected_table_groups: Optional[List[Dict[str, Any]]] = None
    
    # 重试控制
    tool_call_retry_count: Optional[int] = 0
```

### 6.2 ToolCallInfo

```python
class ToolCallInfo(TypedDict):
    """工具调用信息追踪"""
    next_tool_call_id: Optional[str] = None
    tool_call_input_mapping: Optional[Dict[str, Dict]] = None   # tool_call_id -> 输入参数
    tool_call_name_mapping: Optional[Dict[str, str]] = None     # tool_call_id -> 工具名
    tool_call_output_mapping: Optional[Dict[str, Any]] = None   # tool_call_id -> 输出结果
```

---

## 7. 完整工作流程示例

### 7.1 用户查询 GMV 数据的完整流程

```
用户: "What was the daily GMV in Singapore for the past 30 days?"

Step 1: common_agent_start_chain
    ├─ 初始化 CommonAgentState
    ├─ 获取用户上下文（部门、区域等）
    ├─ 构建 SystemMessage（包含 common_chat_prompt）
    └─ 构建初始 HumanMessage

Step 2: intent_route
    └─ agent_name 为空 → 返回 "invoke_common_agent"

Step 3: invoke_common_agent
    ├─ 准备消息列表
    └─ 调用 LLM（GPT-4.1-mini）
        ├─ LLM 分析问题
        ├─ 识别意图：数据分析（final_intent: execute_sql_and_analyze_result）
        └─ 选择工具：data_discovery
            参数: {
                "user_question": "daily GMV in Singapore for past 30 days",
                "selected_tables": [],
                "selected_table_groups": [{"id": "DataMart.Order Mart", "name": "Order Mart"}],
                "final_intent": "execute_sql_and_analyze_result"
            }

Step 4: action_dispatch_node
    ├─ 检测到 AIMessage 有 tool_calls
    ├─ 保存 tool_call 信息到 state
    └─ 返回 Command(goto="tool_call_node")

Step 5: tool_call_node
    ├─ 执行 data_discovery 工具
    ├─ 调用 /ask_data_global/invoke 接口
    └─ 返回找到的表信息（mp_order.dws_seller_gmv_1d__sg_s0_live）

Step 6: action_dispatch_node
    ├─ 检测到 ToolMessage
    ├─ 检查 final_intent != "data_discovery"
    └─ 返回 Command(goto="invoke_common_agent")

Step 7: invoke_common_agent（第二次）
    └─ LLM 选择工具：generate_sql
        参数: {
            "user_question": "daily GMV in Singapore for past 30 days",
            "related_tables_info": "...(表和列信息)",
            "related_table_source_tool_call_id": "call_xxx",
            "final_intent": "execute_sql_and_analyze_result"
        }

Step 8-9: 执行 generate_sql，返回生成的 SQL

Step 10: action_dispatch_node
    └─ final_intent 仍是 execute_sql_and_analyze_result
        → 返回 Command(goto="invoke_common_agent")

Step 11: invoke_common_agent（第三次）
    └─ LLM 选择工具：execute_sql_and_analyze_result
        参数: {
            "sql": "SELECT grass_date, SUM(gmv_1d) ...",
            "find_data_info": "...",
            "user_question": "daily GMV in Singapore for past 30 days",
            "tool_call_id": "call_xxx"
        }

Step 12-13: 执行 SQL 并分析结果

Step 14: action_dispatch_node
    ├─ agent_name == final_intent
    └─ 返回 Command(goto="response_to_user_chain")

Step 15: response_to_user_chain
    └─ 构造 CommonAgentResponse 返回给用户
```

---

## 8. 错误处理与重试

### 8.1 工具调用重试机制

```python
# di_brain/router/tool_router.py

TOOL_CALL_MAX_RETRIES = 3  # 最大重试次数
TOOL_CALL_RETRY_DELAY = 1.0  # 重试延迟（秒）

def action_dispatch_node(state: CommonAgentState):
    ...
    
    # 检测 validation error
    validation_error_pattern = r"Error:\s*\d+\s+validation\s+errors?"
    is_validation_error = re.search(validation_error_pattern, tool_resp_str)
    
    if is_validation_error:
        retry_count = state.get("tool_call_retry_count", 0)
        if retry_count < TOOL_CALL_MAX_RETRIES:
            # 清理错误的 tool_call 信息
            session_tool_call_info["tool_call_name_mapping"].pop(tool_call_id, None)
            session_tool_call_info["tool_call_input_mapping"].pop(tool_call_id, None)
            
            # 重试
            return Command(
                goto="invoke_common_agent",
                update={"tool_call_retry_count": retry_count + 1},
            )
        else:
            # 超过最大重试次数
            return Command(
                goto="response_to_user_chain",
                update={"has_internal_error": True},
            )
```

### 8.2 内部错误处理

```python
def response_to_user_chain(state: CommonAgentState):
    if state.get("has_internal_error"):
        return CommonAgentResponse(
            response_agent="Error",
            ask_human=False,
            llm_raw_response=INTERNAL_ERROR_MESSAGE,
        )
    ...
```

---

## 9. 意图识别的具体实现机制

### 9.1 意图识别的核心原理

意图识别的核心是 **LLM + Tool Calling（Function Calling）** 机制。项目不是通过传统的分类器或规则引擎来识别意图，而是让 **LLM 自主决定调用哪个工具**。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           意图识别流程                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│   用户问题 + 对话历史                                                         │
│         │                                                                     │
│         ▼                                                                     │
│   ┌───────────────────────────────────────────┐                              │
│   │          System Prompt                     │                              │
│   │  - 能力边界定义（Allowed/Forbidden）        │                              │
│   │  - 工作流规则（Workflow & Prioritization） │                              │
│   │  - 执行规则（Execution Rules）             │                              │
│   │  - 用户背景信息                            │                              │
│   └───────────────────────────────────────────┘                              │
│         │                                                                     │
│         ▼                                                                     │
│   ┌───────────────────────────────────────────┐                              │
│   │     LLM (GPT-4.1-mini) + Tool Binding     │                              │
│   │                                           │                              │
│   │  已绑定的工具列表：                        │                              │
│   │  - ask_human（向用户询问）                 │                              │
│   │  - data_discovery（数据发现）              │                              │
│   │  - generate_sql（生成SQL）                 │                              │
│   │  - fix_sql（修复SQL）                      │                              │
│   │  - explain_sql（解释SQL）                  │                              │
│   │  - datasuite_faq（平台问答）               │                              │
│   │  - execute_sql_and_analyze_result（执行分析）│                            │
│   │  - search_log（日志搜索）                  │                              │
│   │  - reject_out_of_scope（拒绝处理）         │                              │
│   └───────────────────────────────────────────┘                              │
│         │                                                                     │
│         ▼                                                                     │
│   LLM 输出：tool_calls（选择的工具 + 参数）                                   │
│   包含：                                                                      │
│   - tool_name: 选择调用的工具                                                 │
│   - args: 工具参数（包含 final_intent）                                       │
│                                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.2 LLM 如何决定调用哪个工具

#### 9.2.1 工具描述（Tool Description）

每个工具都有详细的 **docstring 描述**，告诉 LLM 该工具能做什么：

```python
# di_brain/router/tool_router.py

@tool
def data_discovery(...):
    """The 'Data Discovery' tool has the following capabilities:
    1. Can analyze the user's question and then help the user find the correct 
       Data Tables(Data Table can be Hive Table or ChatDataset) that contains 
       the data the user wants.
    2. Can provide table description, column details, related documents and 
       sample SQL based on user-specified Hive Tables or ChatDatasets.
    3. Can answer questions about data domains, data definitions, and data concepts.
    ...
    """

@tool
def generate_sql(...):
    """The 'generate_sql' tool can help generate or rewrite(adjust) a Presto SQL 
    according user question and data_discovery tool response data table string."""

@tool
def execute_sql_and_analyze_result(...):
    """The 'execute_sql_and_analyze_result' tool can help execute SQL to obtain data, 
    and provide data insight analysis and visualization suggestions.
    The sql parameter is the SQL string that related to user question..."""
```

#### 9.2.2 参数类型定义（Annotated Type Hints）

工具的每个参数都有详细的类型注解，指导 LLM 如何填写参数：

```python
@tool
def data_discovery(
    user_question: Annotated[
        str,
        "The user's question. Remove mentioned tables and table groups from the question.",
    ],
    selected_tables: Annotated[
        List[DataTableModel],
        "the data tables that the user directly mentioned in question. "
        "If the user did not mention the data tables, this parameter should be empty list.",
    ],
    selected_table_groups: Annotated[
        List[TableGroupModel],
        "the data table groups that the user directly mentioned in question. "
        "If the user did not mention the data table groups, this parameter should be empty list.",
    ],
    final_intent: Annotated[
        Literal[
            "data_discovery",
            "generate_sql", 
            "fix_sql",
            "execute_sql_and_analyze_result",
            "search_log",
        ],
        """The final, high-level intent or goal of the user's question. 
        This indicates the overall task the user wants to accomplish after the whole interaction.
        If the user's problem involves data search or analysis, 
        the ultimate goal must be execute_sql_and_analyze_result.""",
    ] = None,
):
```

#### 9.2.3 Tool Binding 过程

```python
# di_brain/router/tool_router.py

class CommonChatTools:
    def prepare_llm_with_tools(self):
        """准备带工具的 LLM"""
        gpt_4o = GET_SPECIFIC_LLM(
            self.router_model, 
            extra_config={"disable_streaming": True}
        )
        # 将工具列表绑定到 LLM
        bind_tool_list = [
            tool for tool in self.tools if tool.name != "data_suite_expert"
        ]
        return gpt_4o.bind_tools(bind_tool_list)

# 工具列表
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
    reject_out_of_scope,
]

# 绑定工具到 LLM
router_llm = common_tool.prepare_llm_with_tools()
```

### 9.3 final_intent 是如何确定的

#### 9.3.1 final_intent 的定义

`final_intent` 是每个工具的一个参数，用于标识用户的**最终目标**（而非当前步骤的目标）：

```python
final_intent: Annotated[
    Literal[
        "data_discovery",        # 数据发现
        "generate_sql",          # 生成 SQL
        "fix_sql",               # 修复 SQL
        "execute_sql_and_analyze_result",  # 执行 SQL 并分析
        "search_log",            # 搜索日志
    ],
    """The final, high-level intent or goal of the user's question. 
    This indicates the overall task the user wants to accomplish 
    after the whole interaction.""",
]
```

#### 9.3.2 LLM 判断 final_intent 的依据

LLM 根据 **System Prompt 中的规则** 来判断 final_intent：

```python
# di_brain/router/common_agent_prompt.py

common_chat_prompt = """
...

### **Workflow & Prioritization**

1. **Problem Analysis & Process Generation:**
    * The rules to distinguish use intent between Generate SQL and Data Insight:
        * **Generate SQL**: Explicitly asks for the SQL code or calculate method 
          using phrases like: "generate SQL," "write a query for," "give me the SQL."
          Asks how to calculate a metric, implying they need the method (the query).
          For example: "How can I calculate the daily active users?" 
        
        * **Execute SQL & Analyze Results**: the user's primary goal is to get 
          the answer, result, data, metric, or an analysis directly from the data. 
          For examples: "What were the total sales last month?" or 
          "I want to know the top 5 performing products."

...

### **Execution Rules**

12. **Ultimate goal:**
    * First, before analyzing the problem, we must clarify the ultimate intention 
      of the entire round of interaction and ensure the consistency of intention 
      in each round of interaction.
    * If the user's question involves data search or analysis or get/calculate/want 
      some metrics data (such as: I want to find the dau data for the last few days; 
      Most visited dashboards in the past 3 days; get Page View(PV) of xxx), 
      the ultimate goal must be Execute SQL and Analyze Results.
    * **IMPORTANT Exception for fix_sql:** If the user explicitly requests to only 
      fix SQL without executing it (e.g., "fix this SQL", "correct this query"), 
      and does not request to execute or analyze results, the ultimate goal should 
      be `fix_sql`, NOT `execute_sql_and_analyze_result`.

...
"""
```

#### 9.3.3 final_intent 判断示例

| 用户问题 | LLM 判断的 final_intent | 判断依据 |
|----------|------------------------|----------|
| "What was the daily GMV last month?" | `execute_sql_and_analyze_result` | 用户想要获取数据结果 |
| "Generate a SQL to calculate DAU" | `generate_sql` | 用户明确要求生成 SQL |
| "How can I calculate conversion rate?" | `generate_sql` | 用户想知道计算方法（SQL） |
| "Fix this SQL: SELECT * FORM users" | `fix_sql` | 用户只想修复 SQL |
| "What tables contain order data?" | `data_discovery` | 用户只想找表 |
| "What is Data Quality?" | `datasuite_faq` | 询问平台功能定义 |

### 9.4 final_intent 如何控制流程终止

`action_dispatch_node` 中通过比较**当前执行的工具**和 **final_intent** 来决定是否继续执行：

```python
# di_brain/router/tool_router.py

def action_dispatch_node(state: CommonAgentState):
    ...
    elif isinstance(last_message, ToolMessage):
        tool_msg: ToolMessage = last_message
        tool_resp_str = tool_msg.content
        
        # 保存工具调用输出
        session_tool_call_info["tool_call_output_mapping"][tool_call_id] = tool_resp_str
        
        # 检查分发策略
        chat_context = state.get("chat_context")
        if chat_context.get("router_dispatch_strategy") == DISPATCH_STRATEGY_LLM:
            # 1. 获取当前工具对应的 agent name
            agent_name = common_tool.get_agent_name_by_tool_name(tool_msg.name)
            
            # 2. 从工具响应中提取 final_intent
            resp_dict = json.loads(tool_resp_str)
            final_intent = resp_dict.get("final_intent", "")
            
            # 3. 如果当前 agent_name == final_intent，说明已达到最终目标
            if agent_name == final_intent and agent_name != "Datasuite FAQ Agent":
                # 结束流程，返回结果给用户
                return Command(goto="response_to_user_chain")
            else:
                # 未达到最终目标，继续执行
                return Command(goto="invoke_common_agent")
```

#### 9.4.1 流程控制示例

```
用户问题: "What was the daily GMV last month?"
LLM 判断: final_intent = "execute_sql_and_analyze_result"

第1轮: LLM 选择 data_discovery 工具
       ├─ 执行 data_discovery
       ├─ 返回结果: {final_intent: "execute_sql_and_analyze_result", ...}
       ├─ agent_name = "Data Discovery"
       ├─ agent_name != final_intent
       └─ 继续执行 → invoke_common_agent

第2轮: LLM 选择 generate_sql 工具
       ├─ 执行 generate_sql
       ├─ 返回结果: {final_intent: "execute_sql_and_analyze_result", sql: "...", ...}
       ├─ agent_name = "Text2SQL Agent"
       ├─ agent_name != final_intent
       └─ 继续执行 → invoke_common_agent

第3轮: LLM 选择 execute_sql_and_analyze_result 工具
       ├─ 执行 execute_sql_and_analyze_result
       ├─ 返回结果: {final_intent: "execute_sql_and_analyze_result", ...}
       ├─ agent_name = "Chat BI Agent"
       ├─ agent_name == final_intent ✓
       └─ 结束流程 → response_to_user_chain
```

### 9.5 工具名到 Agent 名的映射

```python
# di_brain/router/common_agent_tools.py

class CommonChatTools:
    @staticmethod
    def get_agent_name_by_tool_name(tool_name: str):
        """将工具名映射为 Agent 名"""
        if tool_name == "ask_human":
            return "Common Agent"
        elif tool_name == "find_data" or tool_name == "data_discovery":
            return "Data Discovery"
        elif tool_name == "generate_sql":
            return "Text2SQL Agent"
        elif tool_name == "fix_sql":
            return "Fix SQL Agent"
        elif tool_name == "explain_sql":
            return "Explain SQL Agent"
        elif tool_name == "datasuite_faq":
            return "Datasuite FAQ Agent"
        elif tool_name == "execute_sql_and_analyze_result":
            return "Chat BI Agent"
        elif tool_name == "search_log":
            return "Logify Agent"
        elif tool_name == "data_suite_expert":
            return "Data Suite Expert"
        else:
            return "Unknown Agent"
```

### 9.6 意图识别的完整时序图

```
┌──────────┐      ┌─────────────────────┐      ┌─────────────────────┐      ┌──────────────┐
│   User   │      │invoke_common_agent  │      │action_dispatch_node │      │ tool_call_node│
└────┬─────┘      └──────────┬──────────┘      └──────────┬──────────┘      └──────┬───────┘
     │                       │                           │                         │
     │ "Show me daily GMV"   │                           │                         │
     │──────────────────────>│                           │                         │
     │                       │                           │                         │
     │                       │ LLM 分析问题              │                         │
     │                       │ - 识别关键词: GMV, daily  │                         │
     │                       │ - 判断用户想获取数据      │                         │
     │                       │ - 确定 final_intent =     │                         │
     │                       │   "execute_sql_and_       │                         │
     │                       │    analyze_result"        │                         │
     │                       │                           │                         │
     │                       │ LLM 选择第一步工具        │                         │
     │                       │ → data_discovery          │                         │
     │                       │                           │                         │
     │                       │ AIMessage{tool_calls:[    │                         │
     │                       │   {name:"data_discovery", │                         │
     │                       │    args:{                 │                         │
     │                       │      user_question:...,   │                         │
     │                       │      final_intent:        │                         │
     │                       │        "execute_sql_and_  │                         │
     │                       │         analyze_result"   │                         │
     │                       │    }}                     │                         │
     │                       │ ]}                        │                         │
     │                       │                           │                         │
     │                       │──────────────────────────>│                         │
     │                       │                           │                         │
     │                       │                           │ 检测到 tool_call        │
     │                       │                           │ → goto tool_call_node   │
     │                       │                           │                         │
     │                       │                           │────────────────────────>│
     │                       │                           │                         │
     │                       │                           │         执行 data_discovery
     │                       │                           │                         │
     │                       │                           │<────────────────────────│
     │                       │                           │      ToolMessage{       │
     │                       │                           │        final_intent:    │
     │                       │                           │        "execute_sql..." │
     │                       │                           │      }                  │
     │                       │                           │                         │
     │                       │                           │ 比较:                   │
     │                       │                           │ agent_name="Data Discovery"
     │                       │                           │ final_intent="Chat BI Agent"
     │                       │                           │ → 不相等，继续执行      │
     │                       │                           │                         │
     │                       │<──────────────────────────│                         │
     │                       │      继续下一轮           │                         │
     │                       │                           │                         │
     │                      ...     (重复直到达到 final_intent)      ...          │
     │                       │                           │                         │
     │<──────────────────────────────────────────────────│                         │
     │     最终响应（数据分析结果）                       │                         │
     │                       │                           │                         │
```

### 9.7 小结：意图识别的三层机制

| 层级 | 机制 | 作用 |
|------|------|------|
| **Prompt 层** | System Prompt 中的规则定义 | 告诉 LLM 如何判断意图、选择工具、确定 final_intent |
| **Tool 层** | Tool Binding + 参数注解 | 告诉 LLM 有哪些工具可用、每个工具能做什么、参数如何填写 |
| **Control 层** | action_dispatch_node | 根据 final_intent 控制流程是否继续 |

关键点：
1. **意图识别不是一次性的**：LLM 在每一轮都会重新分析当前状态
2. **final_intent 保持一致**：贯穿整个对话，确保朝着最终目标前进
3. **工具选择是自主的**：LLM 根据当前上下文自动选择下一步调用的工具
4. **流程控制是自动的**：通过 agent_name 与 final_intent 比较决定是否结束

---

## 10. 总结

Common Agent 通过以下核心机制实现了智能的任务编排：

| 功能 | 实现方式 |
|------|----------|
| **意图识别** | LLM + Tool Binding，由 GPT-4.1-mini 根据上下文选择工具 |
| **查询重写** | System Prompt 规则，LLM 自动将简单输入重写为完整问题 |
| **计划生成** | System Prompt 中的执行规则，LLM 根据 final_intent 规划执行路径 |
| **代理调用** | LangGraph StateGraph，通过 action_dispatch_node 协调工具调用 |

关键特点：
1. **循环执行**：Common Agent 会循环调用 LLM，直到达到 final_intent
2. **状态追踪**：通过 session_tool_call_info 追踪工具调用链
3. **错误恢复**：支持工具调用重试和错误处理
4. **灵活路由**：支持直接工具调用和 LLM 决策两种模式

