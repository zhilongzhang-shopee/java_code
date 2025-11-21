# Common Agent 快速参考指南

## 🎯 快速速查表

### 路由决策流程

```
用户问题 → 意图分析 → 工具路由 → Sub-Agent执行 → 结果返回
```

### 工具-Agent 映射

| 用户意图 | 调用工具 | 对应Agent | 用途 |
|---------|---------|---------|------|
| "找表" | `find_data` | Data Discovery | 查询表结构和列信息 |
| "生成SQL" | `generate_sql` | Text2SQL Agent | 根据问题生成SQL |
| "修复SQL" | `fix_sql` | Fix SQL Agent | 修复SQL错误 |
| "执行SQL" | `execute_sql_and_analyze_result` | Chat BI Agent | 执行SQL并分析结果 |
| "解释SQL" | `explain_sql` | Explain SQL Agent | 解释SQL语句 |
| "查询日志" | `search_log` | Logify Agent | 搜索日志 |
| "澄清数据域" | `detect_data_domain` | Data Scope Clarification | 检测数据域 |
| "需要更多信息" | `ask_human` | Common Agent | 向用户请求信息 |

---

## 📊 状态管理快速指南

### 状态的三个层次

1. **Input State** (输入状态)
   - question: 用户问题
   - chat_history: 聊天历史
   - chat_context: 用户上下文
   - selected_assets: 选中资源

2. **Process State** (处理过程状态)
   - session_tool_call_info: 工具调用追踪
   - messages: 消息历史
   - ask_human: 待回答的问题

3. **Output State** (输出状态)
   - final_response: 最终响应
   - mid_state: 中间状态
   - has_internal_error: 是否出错

### 工具调用追踪

```python
{
    "tool_call_id": "uuid-123",
    "tool_call_name": "generate_sql",
    "tool_call_input": {...},
    "tool_call_output": {...}
}
```

---

## 🔄 常见场景流程

### 场景1: 完整的数据分析流程

```
用户: "分析@order_mart表过去7天的订单数据"

↓

Common Agent:
1. 检测用户意图 → 数据分析
2. 识别表 → order_mart

↓

调用 detect_data_domain
↓ (获取数据域信息)

调用 find_data
↓ (获取表结构)

调用 generate_sql
↓ (生成查询SQL)

调用 execute_sql_and_analyze_result
↓ (执行SQL并分析)

返回分析结果
```

### 场景2: SQL错误修复流程

```
用户: "这个SQL有问题: SELECT * FROM t WHERE date = 2024-01-01"

↓

Common Agent:
1. 检测用户意图 → SQL修复
2. 识别问题 → SQL语法错误

↓

调用 fix_sql
↓ (修复SQL)

(可选) 调用 execute_sql_and_analyze_result
↓ (验证修复后的SQL)

返回修复的SQL
```

### 场景3: 简单查询流程

```
用户: "什么是order_mart表?"

↓

Common Agent:
1. 检测用户意图 → 表信息查询

↓

调用 find_data
↓ (获取表结构和描述)

返回表描述信息
```

---

## 🛠️ 关键方法速查

### CommonChatTools 中的关键方法

```python
# 查找工具消息
find_latest_find_data_tool_message(state)

# 获取工具的输入输出
get_find_data_tool_io_by_tool_call_id(state, tool_call_id)

# Agent-Tool映射
get_agent_name_by_tool_name(tool_name)
get_tool_name_by_agent_name(agent_name)

# LLM准备
prepare_llm_with_tools()
prepare_tool_node()
```

### CommonAgentState 中的关键字段

```python
# 基本信息
question              # 用户问题
chat_id              # 聊天ID
session_id           # 会话ID

# 状态追踪
session_tool_call_info    # 会话级工具调用
chat_tool_call_info       # 聊天级工具调用

# 用户交互
ask_human            # 待回答的问题
ask_human_sub_tool_name   # 提问的工具名

# 资源
selected_tables      # 选中的表
selected_assets      # 选中的资源

# 错误处理
has_internal_error   # 是否有错误
internal_error_message    # 错误信息
```

---

## ⚙️ 配置文件位置

| 文件 | 路径 | 作用 |
|------|------|------|
| Prompt | `common_agent_prompt.py` | 定义Agent行为和能力 |
| State | `common_agent_state.py` | 定义状态数据结构 |
| Tools | `common_agent_tools.py` | 工具管理和映射 |
| Router | `tool_router.py` | 路由核心逻辑 |

---

## 🔑 核心原则

### 1. 意图一致性
- 整个对话过程中用户意图保持不变
- 最终行为必须与用户意图对应

### 2. 工具优先级
```
ask_human > detect_data_domain > find_data > generate_sql > execute_sql > fix_sql
```

### 3. 不分裂问题
- 禁止将单个问题分解成多个子问题
- 单次请求处理完整问题

### 4. 前置条件检查
- 生成SQL前需要知道表详情
- 执行SQL前需要有有效的SQL
- 修复SQL前需要确认SQL有错误

### 5. 错误恢复
- 每个工具调用都有错误捕获
- 支持错误后的重试
- 完整的错误日志记录

---

## 📈 性能优化建议

1. **缓存利用**
   - 缓存表结构信息
   - 缓存常见SQL模板

2. **并行处理**
   - 多个find_data调用可并行执行
   - 独立的工具调用可并行处理

3. **批量操作**
   - 批量查询多个表信息
   - 批量执行相关SQL

---

## 🐛 调试技巧

### 查看工具调用链

```python
# 在state中查看所有工具调用
state['session_tool_call_info']['tool_call_name_mapping']
state['session_tool_call_info']['tool_call_input_mapping']
state['session_tool_call_info']['tool_call_output_mapping']
```

### 跟踪用户意图

```python
# 在state中查看聊天历史
for msg in state['messages']:
    print(f"{type(msg).__name__}: {msg.content}")
```

### 检查错误

```python
if state.get('has_internal_error'):
    print(state['internal_error_message'])
```

---

## 📚 相关文档

- **完整分析**: `common_agent_routing_analysis.md`
- **其他Agent文档**: 同级目录下的其他Agent分析文档

---

## 🔗 代码位置速查

```
di_brain/
└── router/
    ├── __init__.py              # 导出common_agent_chain
    ├── common_agent_prompt.py   # Prompt定义
    ├── common_agent_state.py    # 状态定义
    ├── common_agent_tools.py    # 工具管理
    ├── tool_router.py           # 路由核心逻辑
    ├── test_gen_table_list.py   # 测试用例
    ├── tool_router_test_case.py # 测试用例
    └── a.json                   # 配置示例
```

---

## 💡 快速开始

### 基础流程

```python
from di_brain.router import common_agent_chain

# 准备输入
input_data = {
    "question": "分析订单数据",
    "chat_context": {"user_email": "user@example.com"},
    "chat_history": []
}

# 调用Common Agent
response = common_agent_chain.invoke(input_data)

# 获取结果
result = response.get("sub_agent_response")
```

### 处理错误

```python
if response.get("has_internal_error"):
    error_msg = response.get("internal_error_message")
    print(f"发生错误: {error_msg}")
else:
    print("执行成功")
```

---

**最后更新**: 2024年10月27日
