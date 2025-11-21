# Common Agent：LangGraph 复杂 Agent 架构分析

## 目录

1. [概述](#概述)
2. [系统架构](#系统架构)
3. [状态管理](#状态管理)
4. [工具系统](#工具系统)
5. [Graph 构建](#graph-构建)
6. [执行流程](#执行流程)
7. [Agent 协调机制](#agent-协调机制)
8. [核心算法](#核心算法)

---

## 概述

DI-Brain 中的 **Common Agent** 是一个基于 LangGraph 构建的高级路由代理，负责：
- 接收用户请求
- 理解用户意图
- 调用下游的多个专业化 Agent（Text-to-SQL Agent、Fix SQL Agent、Chat BI Agent 等）
- 协调各个 Agent 之间的交互
- 返回最终结果

这个系统展示了 LangChain 如何构建复杂的多 Agent 协作架构。

---

## 系统架构

### 核心组件

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Request                             │
└────────────────────────────┬────────────────────────────────────┘
                             │
                ┌────────────▼────────────┐
                │   Common Agent Graph    │
                │  (主路由和协调引擎)     │
                └────────────┬────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ Text-to-SQL Agnt│ │ Fix SQL Agent    │ │ Chat BI Agent    │
└──────────────────┘ └──────────────────┘ └──────────────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                ┌────────────▼────────────┐
                │    Result Response      │
                └─────────────────────────┘
```

### 关键特点

1. **中心化路由**：所有请求都通过 Common Agent 处理
2. **工具调用**：使用 LLM 的 Tool Calling 能力自动调用下游 Agent
3. **状态持久化**：维护完整的对话状态和工具调用链
4. **条件分支**：根据用户意图和工具返回结果进行动态路由
5. **自动修复**：能够自动调用 Fix SQL Agent 修复错误的 SQL

---

## 状态管理

### CommonAgentState 定义

```python
class CommonAgentState(BaseState):
    # 用户输入信息
    question: Optional[str] = None                          # 原始问题
    chat_id: Optional[int] = None                          # 聊天会话ID
    session_id: Optional[int] = None                       # 会话ID
    chat_context: Optional[Dict[str, str]] = None          # 对话上下文
    
    # 数据资产信息
    selected_tables: Optional[List[Dict[str, Any]]] = None # 用户选择的表
    selected_table_groups: Optional[List[Dict[str, Any]]] = None  # 用户选择的表组
    
    # 执行状态
    sql_dialect: Optional[str] = None                      # SQL方言
    original_sql: Optional[str] = None                     # 原始SQL
    error_message: Optional[str] = None                    # 错误信息
    
    # 工具调用信息（关键！）
    session_tool_call_info: Optional[ToolCallInfo] = None  # 会话级工具调用记录
    chat_tool_call_info: Optional[ToolCallInfo] = None     # 聊天级工具调用记录
    
    # 控制流信息
    messages: Annotated[list[BaseMessage], add_messages]   # 消息历史（LangGraph特性）
    interruption_reason: str                               # 中断原因
    timeout_timestamp: float                               # 超时时间戳
    run_name: str                                          # 运行名称
    
    # 错误处理
    has_internal_error: bool = False                       # 是否有内部错误
    internal_error_message: Optional[str] = None           # 内部错误信息
```

### ToolCallInfo 结构

```python
class ToolCallInfo(TypedDict):
    tool_call_output_mapping: Dict[str, Any]    # tool_call_id -> 工具输出
    tool_call_name_mapping: Dict[str, Any]      # tool_call_id -> 工具名称
    tool_call_input_mapping: Dict[str, Any]     # tool_call_id -> 工具输入参数
```

**关键理解**：这个结构维护了**完整的工具调用链**，使得系统可以在任何时刻回溯到之前的调用信息。

---

## 工具系统

### 工具列表

Common Agent 拥有以下工具（通过 `@tool` 装饰器定义）：

```python
tools = [
    ask_human,                          # 向用户提问
    data_discovery,                     # 数据发现（调用数据范围澄清Agent）
    generate_sql,                       # 生成SQL（调用Text-to-SQL Agent）
    fix_sql,                            # 修复SQL（调用Fix SQL Agent）
    explain_sql,                        # 解释SQL
    datasuite_faq,                      # 数据平台FAQ
    execute_sql_and_analyze_result,     # 执行SQL并分析结果（调用Chat BI Agent）
    search_log,                         # 搜索日志
    data_suite_expert,                  # 数据平台专家
]
```

### 工具实现示例：generate_sql

```python
@tool
def generate_sql(
    user_question: str,
    related_tables_info: str,
    related_table_source_tool_call_id: str,  # 关键：跟踪前一个工具的调用ID
    state: Annotated[dict, InjectedState],
    final_intent: str = None,
):
    """生成SQL的工具"""
    # 1. 从状态中获取前一个工具（data_discovery）的输出
    _, find_data_resp = CommonChatTools.get_find_data_tool_io_by_tool_call_id(
        state, related_table_source_tool_call_id
    )
    
    # 2. 构建输入
    input = SubAgentInput(
        question=user_question,
        find_data_docs=related_tables_info,
        # ... 其他参数
    )
    
    # 3. 调用 Text-to-SQL Agent（子图）
    resp = text2sql_ask_human_compass_graph.invoke(input, config)
    
    # 4. 返回结果
    return json.dumps(resp)
```

**关键特点**：
- 工具之间通过 `tool_call_id` 进行链接
- 工具可以访问完整的状态历史
- 工具的输出会自动作为下一个工具的输入

---

## Graph 构建

### Graph 拓扑结构

```python
def build_common_chat_graph():
    # 创建状态图
    common_agent_workflow = StateGraph(CommonAgentState)
    
    # 1. 添加节点（Nodes）
    common_agent_workflow.add_node("invoke_common_agent", invoked_common_agent_node)
    common_agent_workflow.add_node("tool_call_node", tool_call_node)
    common_agent_workflow.add_node("action_dispatch_node", action_dispatch_node)
    common_agent_workflow.add_node("direct_tool_node", direct_tool_node)
    common_agent_workflow.add_node("response_to_user_chain", response_to_user_chain)
    
    # 2. 添加边（Edges）- 连接节点的流路径
    # 条件边：根据 intent_route 的返回值选择路径
    common_agent_workflow.add_conditional_edges(
        START,                                      # 起点
        intent_route,                              # 条件函数
        {"invoke_common_agent", "direct_tool_node"}  # 可能的目标节点
    )
    
    # 直接边：invoke_common_agent -> action_dispatch_node
    common_agent_workflow.add_edge("invoke_common_agent", "action_dispatch_node")
    
    # 直接边：tool_call_node -> action_dispatch_node
    common_agent_workflow.add_edge("tool_call_node", "action_dispatch_node")
    
    # 直接边：response_to_user_chain -> END
    common_agent_workflow.add_edge("response_to_user_chain", END)
    
    # 3. 编译为可执行的图
    return common_agent_workflow.compile()
```

### Graph 的节点

#### 1. invoke_common_agent 节点

**作用**：调用 LLM 进行决策和工具选择

```python
def invoked_common_agent_node(state: CommonAgentState):
    # 准备消息历史
    invoke_llm_messages = []
    for msg in state["messages"]:
        # 处理和精简消息内容
        invoke_llm_messages.append(msg)
    
    try:
        # 调用绑定了工具的 LLM
        # LLM 会自动分析消息历史和当前状态，决定
        # 1. 是否需要调用工具
        # 2. 调用哪个工具
        # 3. 工具的参数是什么
        response = router_llm.invoke(invoke_llm_messages)
        state["messages"].append(response)
    except Exception as e:
        state["has_internal_error"] = True
        state["internal_error_message"] = str(e)
    
    return state
```

**工作原理**：
- `router_llm` 是一个绑定了所有工具的 LLM 实例
- LLM 通过 LangChain 的工具调用机制生成包含 `tool_calls` 信息的 `AIMessage`
- AIMessage 被添加到消息历史中

#### 2. action_dispatch_node 节点

**作用**：根据最后的消息（LLM 响应或工具响应）决定下一步行动

```python
def action_dispatch_node(state: CommonAgentState):
    """复杂的条件分支逻辑"""
    
    if state.get("has_internal_error"):
        return Command(goto="response_to_user_chain")
    
    messages = state["messages"]
    last_message = messages[-1]
    
    # 场景1：LLM 刚输出了新的工具调用请求
    if isinstance(last_message, AIMessage) and last_message.tool_calls:
        tool_call_info = last_message.tool_calls[0]
        
        # 情况1a：LLM 要求与用户交互
        if tool_call_info["name"] == "ask_human":
            return Command(
                goto="response_to_user_chain",
                update={"ask_human": tool_call_info["args"]["question"]}
            )
        
        # 情况1b：LLM 要求调用其他工具
        else:
            # 记录工具调用信息
            session_tool_call_info["tool_call_name_mapping"][tool_call_id] = tool_name
            session_tool_call_info["tool_call_input_mapping"][tool_call_id] = tool_args
            
            return Command(
                goto="tool_call_node",
                update={"session_tool_call_info": session_tool_call_info}
            )
    
    # 场景2：工具执行完毕，返回了 ToolMessage
    elif isinstance(last_message, ToolMessage):
        tool_msg = last_message
        tool_resp_str = tool_msg.content
        
        # 情况2a：工具返回错误
        if re.search(ERROR_TOOL_RESPONSE_PATTERN, tool_resp_str):
            return Command(
                goto="response_to_user_chain",
                update={"has_internal_error": True}
            )
        
        # 情况2b：工具返回"需要用户确认"
        if re.search(HAS_ASK_HUMAN_PATTERN, tool_resp_str):
            return Command(
                goto="response_to_user_chain",
                update={"ask_human": ask_human_question}
            )
        
        # 情况2c：某些工具完成后需要结束（如 generate_sql）
        if tool_msg.name == "generate_sql":
            return Command(goto="response_to_user_chain")
        
        # 情况2d：继续询问 LLM 是否还需要调用其他工具
        else:
            return Command(goto="invoke_common_agent")
```

**关键点**：
- 使用 `Command` 对象进行动态路由
- 支持同时更新状态和跳转节点

#### 3. tool_call_node 节点

**作用**：执行 LLM 选择的工具

```python
# 使用 LangGraph 的预构建 ToolNode
tool_call_node = ToolNode(tools_without_ask_human)
```

**工作原理**：
- ToolNode 是 LangGraph 提供的内置节点
- 它自动从 AIMessage 中提取 `tool_calls` 信息
- 根据工具名称和参数调用对应的工具
- 将工具的返回值包装成 `ToolMessage`，添加到消息历史

#### 4. response_to_user_chain 节点

**作用**：格式化最终响应返回给用户

```python
def response_to_user_chain(state: CommonAgentState):
    """生成最终响应"""
    # 从最后的工具调用中提取结果
    last_message = state["messages"][-1]
    
    if isinstance(last_message, ToolMessage):
        # 解析工具的输出
        final_response = json.loads(last_message.content)
        return {
            "response_agent": agent_name,
            "sub_agent_response": final_response,
            "mid_state": state.to_dict()
        }
```

#### 5. intent_route 函数

**作用**：条件边函数，在图的起点进行初始路由

```python
def intent_route(state: CommonAgentState):
    """根据 agent_name 进行初始路由"""
    
    # 某些特定 Agent 可以直接跳过 LLM 决策
    if state.get("agent_name") in [
        "Fix SQL Agent",
        "Explain SQL Agent",
        "Logify Agent",
        "Data Suite Expert"
    ]:
        # 直接调用对应的工具
        return "direct_tool_node"
    
    # 其他情况：让 LLM 进行决策
    return "invoke_common_agent"
```

---

## 执行流程

### 典型执行序列：用户要求分析数据

```
1. 用户输入
   ├─ 问题：生成查询最近7天用户数据的SQL
   └─ 上下文：选择了"用户表"
   
2. Graph 开始执行 (START)
   │
3. intent_route 决策
   └─> agent_name 为空，返回 "invoke_common_agent"
   
4. invoke_common_agent 节点
   ├─ LLM 收到消息：用户问题 + 上下文
   ├─ LLM 分析并决策：需要先进行数据发现
   ├─ LLM 调用 data_discovery 工具
   └─ 返回 AIMessage（包含 tool_call）
   
5. action_dispatch_node 节点
   ├─ 检测到 AIMessage 中有 tool_calls
   ├─ 记录工具调用信息到 session_tool_call_info
   └─ 路由到 "tool_call_node"
   
6. tool_call_node 节点
   ├─ 执行 data_discovery 工具
   ├─ 工具返回相关表信息
   └─ 生成 ToolMessage 添加到消息历史
   
7. action_dispatch_node 节点（再次）
   ├─ 检测到 ToolMessage（来自 data_discovery）
   ├─ 检查工具响应内容
   ├─ 数据发现完成，继续请 LLM 进行下一步决策
   └─ 路由到 "invoke_common_agent"
   
8. invoke_common_agent 节点（再次）
   ├─ LLM 再次分析（现在有了表信息）
   ├─ LLM 决策：现在可以生成SQL
   ├─ LLM 调用 generate_sql 工具
   └─ 返回新的 AIMessage
   
9. action_dispatch_node 节点（再次）
   ├─ 检测到新的 tool_calls（generate_sql）
   ├─ 记录到 session_tool_call_info
   └─ 路由到 "tool_call_node"
   
10. tool_call_node 节点（再次）
    ├─ 执行 generate_sql 工具
    ├─ generate_sql 工具内部调用 Text-to-SQL Agent
    ├─ Text-to-SQL Agent 返回生成的SQL
    └─ 生成 ToolMessage
    
11. action_dispatch_node 节点（再次）
    ├─ 检测到 ToolMessage（来自 generate_sql）
    ├─ 检查规则：generate_sql 完成后应该结束
    └─ 路由到 "response_to_user_chain"
    
12. response_to_user_chain 节点
    ├─ 从 ToolMessage 中提取结果
    ├─ 格式化响应
    └─ 返回给用户
    
13. Graph 结束 (END)
```

### 关键点

1. **消息链**：每个步骤的消息都被添加到 `state["messages"]` 中
2. **状态持久化**：完整的调用链保存在 `session_tool_call_info` 中
3. **条件分支**：根据最后一条消息的类型和内容进行路由
4. **循环控制**：通过 `invoke_common_agent -> action_dispatch_node` 的循环实现多步骤工作流

---

## Agent 协调机制

### 1. 工具调用链跟踪

```python
# 每次工具调用都会被记录
session_tool_call_info = {
    "tool_call_name_mapping": {
        "call_001": "data_discovery",
        "call_002": "generate_sql",
        "call_003": "execute_sql_and_analyze_result"
    },
    "tool_call_input_mapping": {
        "call_001": {"user_question": "...", "selected_tables": ["..."]},
        "call_002": {"user_question": "...", "related_table_source_tool_call_id": "call_001"},
        "call_003": {"sql_tool_call_id": "call_002", ...}
    },
    "tool_call_output_mapping": {
        "call_001": '{"related_tables": [...], "related_docs": [...]}',
        "call_002": '{"sql": "SELECT ...", "explanation": "..."}',
        "call_003": '{"result": [...], "chart": ...}'
    }
}
```

### 2. 工具间的数据传递

```python
# 示例：generate_sql 工具访问 data_discovery 的输出
def generate_sql(
    ...
    related_table_source_tool_call_id: str,  # 前一个工具的 call_id
    state: Annotated[dict, InjectedState],
):
    # 获取前一个工具的输入和输出
    find_data_input, find_data_output = CommonChatTools.get_find_data_tool_io_by_tool_call_id(
        state, 
        related_table_source_tool_call_id
    )
    
    # 使用前一个工具的输出作为本工具的输入
    related_tables_info = find_data_output
    
    # 调用子 Agent
    resp = text2sql_ask_human_compass_graph.invoke({
        "find_data_docs": related_tables_info,
        ...
    })
    
    return resp
```

### 3. 子 Agent 调用

```python
# Common Agent 通过工具调用来触发子 Agent
# 工具示例：generate_sql 中调用 Text-to-SQL Agent

@tool
def generate_sql(...):
    # 这里 text2sql_ask_human_compass_graph 是一个子图
    # 它是另一个完整的 LangGraph 应用
    resp = text2sql_ask_human_compass_graph.invoke(input, config)
    return resp
```

### 4. 多 Agent 通信的关键机制

| 机制 | 描述 |
|------|------|
| **tool_call_id** | 每个工具调用都有唯一的 ID，用于追踪调用链 |
| **ToolCallInfo** | 维护工具调用的输入/输出映射 |
| **Command 对象** | 在 action_dispatch_node 中用于动态路由和状态更新 |
| **Messages 历史** | 保存完整的对话历史，供 LLM 参考 |
| **State 传递** | 状态对象在所有节点间传递，实现信息共享 |

---

## 核心算法

### 1. LLM 决策循环

```
┌─────────────────────────────────────────┐
│     while not done:                     │
│                                         │
│  1. invoke_llm(messages, tools)        │
│     ├─ Input: 消息历史 + 可用工具列表  │
│     └─ Output: AIMessage               │
│         ├─ tool_calls: [工具调用]      │
│         └─ content: [LLM 响应文本]     │
│                                         │
│  2. if tool_calls:                      │
│     ├─ for each tool_call:             │
│     │   ├─ Execute tool               │
│     │   └─ Get ToolMessage            │
│     ├─ Add ToolMessage to messages    │
│     └─ Continue loop                  │
│                                         │
│  3. else:                               │
│     └─ Generate response and exit      │
└─────────────────────────────────────────┘
```

### 2. Tool Calling 机制

```python
# LangChain 的工具调用过程

# 步骤1：绑定工具到 LLM
llm_with_tools = llm.bind_tools([
    data_discovery,
    generate_sql,
    fix_sql,
    ...
])

# 步骤2：LLM 调用
response = llm_with_tools.invoke(messages)

# 步骤3：LLM 返回包含工具调用的消息
# response = AIMessage(
#     content="我找到了相关的表，现在生成SQL",
#     tool_calls=[
#         {
#             "name": "generate_sql",
#             "id": "call_xyz",
#             "args": {
#                 "user_question": "...",
#                 "related_tables_info": "..."
#             }
#         }
#     ]
# )

# 步骤4：执行工具
tool_result = generate_sql(**response.tool_calls[0]["args"])

# 步骤5：生成 ToolMessage
tool_message = ToolMessage(
    content=tool_result,
    tool_call_id=response.tool_calls[0]["id"],
    name="generate_sql"
)

# 步骤6：添加到消息历史
messages.append(response)
messages.append(tool_message)

# 步骤7：继续循环
```

### 3. 条件路由算法

```python
def action_dispatch_node(state):
    """根据消息类型和内容进行路由"""
    
    last_msg = state["messages"][-1]
    
    # 路由决策树
    if isinstance(last_msg, AIMessage):
        if has_tool_calls(last_msg):
            if first_tool_call_is("ask_human"):
                return "response_to_user_chain"  # 结束
            else:
                return "tool_call_node"          # 执行工具
        else:
            return "response_to_user_chain"      # 结束
    
    elif isinstance(last_msg, ToolMessage):
        if has_error(last_msg):
            return "response_to_user_chain"      # 结束（错误）
        elif needs_user_confirmation(last_msg):
            return "response_to_user_chain"      # 结束（等待确认）
        elif is_terminal_tool(last_msg.name):
            return "response_to_user_chain"      # 结束（如 generate_sql）
        else:
            return "invoke_common_agent"        # 继续决策
    
    return "response_to_user_chain"              # 默认结束
```

### 4. 状态聚合算法

```python
def append_messages_and_update_state(state, response):
    """
    维护消息链和状态同步的算法
    """
    
    # 1. 添加 LLM 响应
    state["messages"].append(response)
    
    # 2. 如果有工具调用，记录到 session_tool_call_info
    if response.tool_calls:
        for tool_call in response.tool_calls:
            tool_call_id = tool_call["id"]
            tool_name = tool_call["name"]
            tool_args = tool_call["args"]
            
            state["session_tool_call_info"]["tool_call_name_mapping"][
                tool_call_id
            ] = tool_name
            state["session_tool_call_info"]["tool_call_input_mapping"][
                tool_call_id
            ] = tool_args
    
    # 3. 执行工具后，记录输出
    if tool_message:
        tool_call_id = tool_message.tool_call_id
        state["session_tool_call_info"]["tool_call_output_mapping"][
            tool_call_id
        ] = tool_message.content
        
        # 添加 ToolMessage 到历史
        state["messages"].append(tool_message)
    
    return state
```

---

## 关键设计模式

### 1. State Machine Pattern（状态机模式）

```
状态 = {messages, session_tool_call_info, ...}

转移 = invoke_llm -> dispatch -> execute_tool -> invoke_llm ...
```

### 2. Chain of Responsibility（责任链模式）

```
LLM决策 -> 动作分发 -> 工具执行 -> 结果处理 -> 再次LLM决策
```

### 3. Composite Pattern（组合模式）

```
Common Agent
  ├─ Text-to-SQL Agent (子图)
  ├─ Fix SQL Agent (子图)
  ├─ Chat BI Agent (子图)
  └─ ...
```

### 4. Strategy Pattern（策略模式）

```
intent_route: 选择初始策略
action_dispatch_node: 根据结果动态选择下一个策略
```

---

## 性能和可靠性考虑

### 1. 错误处理

```python
# 在每个关键节点都有 try-catch
try:
    response = router_llm.invoke(invoke_llm_messages)
    state["messages"].append(response)
except Exception as e:
    state["has_internal_error"] = True
    state["internal_error_message"] = str(e)
    return Command(goto="response_to_user_chain")
```

### 2. 超时管理

```python
# 追踪执行时间
state["timeout_timestamp"] = datetime.now().timestamp() + DEFAULT_MAX_EXECUTE_SECOND

# 定期检查是否超时
if time_consumed > state["max_execute_second"]:
    state["error"] = {"error_type": "timeout"}
    return Command(goto="response_to_user_chain")
```

### 3. 工具调用链验证

```python
def get_find_data_tool_io_by_generate_sql_call_id(state, generate_sql_call_id):
    # 递归获取前一个工具的信息
    generate_sql_input = tool_call_input_mapping.get(generate_sql_call_id)
    find_data_call_id = generate_sql_input.get("related_table_source_tool_call_id")
    
    # 防止死循环
    if find_data_call_id == generate_sql_call_id:
        logger.warning("Circle detected in tool call chain")
        return None, None
    
    # 递归调用
    return get_find_data_tool_io_by_find_data_call_id(state, find_data_call_id)
```

---

## 总结

Common Agent 通过以下关键机制实现复杂的多 Agent 协作：

| 组件 | 作用 |
|------|------|
| **LangGraph StateGraph** | 定义 Agent 的控制流和状态转移 |
| **Tool Calling** | LLM 自动选择要调用的工具 |
| **ToolCallInfo** | 记录和追踪所有工具调用 |
| **Messages History** | 维护完整的对话历史，供 LLM 参考 |
| **Command 对象** | 支持动态路由和条件分支 |
| **子图集成** | 通过工具调用其他 LangGraph 应用 |

这种架构使得系统能够：
- **自动化决策**：LLM 自动选择下一步行动
- **灵活性**：支持动态添加新的工具和 Agent
- **可追踪性**：完整记录所有工具调用链
- **鲁棒性**：多层次的错误处理和超时管理
- **可扩展性**：支持无限深度的 Agent 嵌套

