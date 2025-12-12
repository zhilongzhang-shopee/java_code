# ChatBI 核心逻辑与流程详解

## 概述

ChatBI是一个将自然语言查询转换为BI可视化结果的智能系统。整个处理流程分为四个核心阶段：

1. **SQL Generator** - SQL生成（在ChatBI之前由text2sql模块完成）
2. **SQL Authentication** - SQL权限验证
3. **SQL Executor** - SQL执行
4. **Chart Generator** - 图表生成与数据洞察

## 流程架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ChatBI Pipeline                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐   ┌──────────────────┐   ┌─────────────┐   ┌────────────┐ │
│  │    SQL      │   │      SQL         │   │    SQL      │   │   Chart    │ │
│  │  Generator  │──>│  Authentication  │──>│  Executor   │──>│ Generator  │ │
│  │ (text2sql)  │   │  (Row+Column)    │   │ (SR/Presto) │   │  (LLM)     │ │
│  └─────────────┘   └──────────────────┘   └─────────────┘   └────────────┘ │
│         │                   │                    │                 │        │
│         ▼                   ▼                    ▼                 ▼        │
│  ┌─────────────┐   ┌──────────────────┐   ┌─────────────┐   ┌────────────┐ │
│  │  SQL Query  │   │   Validated &    │   │   Query     │   │  Chart +   │ │
│  │  Generated  │   │  Rewritten SQL   │   │   Results   │   │  Insights  │ │
│  └─────────────┘   └──────────────────┘   └─────────────┘   └────────────┘ │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 处理阶段定义

### 非追问场景 (Initial Scenario)

```python
BASIC_PROCESS_DATA = {
    "stages": [
        {"order": 1, "type": "SQL Generator", "status": "WAITING", "message": ""},
        {"order": 2, "type": "SQL Authentication", "status": "WAITING", "message": ""},
        {"order": 3, "type": "SQL Executor", "status": "WAITING", "message": ""},
        {"order": 4, "type": "Chart Generator", "status": "WAITING", "message": ""},
    ]
}
```

### 追问场景 (Followup Scenario)

```python
FOLLOWUP_PROCESS_DATA = {
    "stages": [
        {"order": 1, "type": "SQL Executor", "status": "WAITING", "message": ""},
        {"order": 2, "type": "Chart Generator", "status": "WAITING", "message": ""},
    ]
}
```

---

## 1. SQL Authentication (权限验证)

### 1.1 权限验证流程

权限验证包含两个关键步骤：
1. **Row Access Check** - 行级权限检查与SQL重写
2. **Column Access Check** - 列级权限验证

```
┌────────────────────────────────────────────────────────────────┐
│                    SQL Authentication Flow                      │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Input: SQL Query + User Account + IDC Region                  │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │          Step 1: Row Access Check & SQL Rewrite          │   │
│  │  ┌───────────────────────────────────────────────────┐  │   │
│  │  │  1. 调用RAM API获取用户行级权限                     │  │   │
│  │  │  2. 提取access_conditions                          │  │   │
│  │  │  3. 重写SQL添加行级过滤条件                         │  │   │
│  │  └───────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │          Step 2: Column Access Check                     │   │
│  │  ┌───────────────────────────────────────────────────┐  │   │
│  │  │  1. 调用RAM SQL Auth API                           │  │   │
│  │  │  2. 验证用户对SQL中涉及列的访问权限                  │  │   │
│  │  │  3. 返回无权限的表/列信息                           │  │   │
│  │  └───────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────┘   │
│                          │                                      │
│                          ▼                                      │
│  Output: Rewritten SQL / Auth Failed Error                     │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 1.2 Row Access (行级权限)

#### 核心代码位置
`di_brain/tools/row_access/ram_row_access_tool.py`

#### 执行逻辑

```python
def get_user_row_access_for_sql_rewrite(
    sql_script: str,
    account: str,
    idc_region: str,
    queue_name: str,
) -> Tuple[bool, str, Dict[str, str]]:
    """
    获取用户行级权限并返回SQL重写条件
    
    流程:
    1. 调用RAM Row Access API
    2. 解析返回的tableInfo
    3. 提取每个表的orCondition和andCondition
    4. 组合为access_conditions字典
    """
```

#### RAM API调用

```python
# API Endpoint
ROW_ACCESS_PATH = "/ram/api/v1/developer/uc/auth/row"

# Request Data
data = {
    "account": account,        # 用户账号
    "idcRegion": idc_region,   # 数据中心区域 (SG/USEast)
    "catalog": "hive",
    "schema": "",
    "engineType": "presto",
    "engineVersion": "trino-389",
    "sqlScript": sql_script,
}
```

#### 条件提取逻辑

```python
def extract_row_conditions(table_info: List[Dict], email: str) -> Dict[str, str]:
    """
    从RAM API返回的tableInfo中提取行级过滤条件
    
    处理逻辑:
    - andCondition: 直接添加
    - orCondition: 用OR连接并加括号
    - 最终用AND组合所有条件
    """
    conditions = {}
    for table in table_info:
        table_id = f"{schema}.{table_name}"
        
        # 组合条件
        condition_parts = []
        condition_parts.extend(and_conditions)
        
        if or_conditions:
            if len(or_conditions) == 1:
                condition_parts.append(or_conditions[0])
            else:
                condition_parts.append(f"({' OR '.join(or_conditions)})")
        
        conditions[table_id] = " AND ".join(condition_parts)
    
    return conditions
```

### 1.3 SQL Rewrite (SQL重写)

#### 核心代码位置
`di_brain/tools/row_access/sql_rewrite.py`

#### 重写流程

```python
class SQLRewriter:
    def rewrite_sql(self, sql: str) -> SQLRewriteResult:
        """
        重写SQL添加行级访问控制条件
        
        处理流程:
        1. 检测是否有WITH语句(CTE)
        2. 分别处理每个CTE子查询和主查询
        3. 提取每个SELECT块中的表和别名
        4. 为每个表添加对应的访问条件
        5. 注入WHERE条件
        """
```

#### WHERE条件注入

```python
def _inject_where_condition(self, sql: str, condition: str, position: int) -> str:
    """
    在指定位置注入WHERE条件
    
    规则:
    - 如果已有WHERE: WHERE {condition} AND {原有条件}
    - 如果没有WHERE: WHERE {condition} {后续语句}
    """
    if after.upper().startswith("WHERE"):
        return f"{before} WHERE {condition} AND {after[6:].lstrip()}"
    else:
        return f"{before} WHERE {condition} {after}"
```

### 1.4 Column Access (列级权限)

#### 核心代码位置
`di_brain/tools/ram_sql_auth_tool.py`

#### 执行逻辑

```python
def validate_user_sql_auth(
    sql: str, user: str, idc_region: str, queue: str
) -> tuple[bool, str, Any]:
    """
    验证用户对SQL中列的访问权限
    
    API调用:
    - Endpoint: /ram/api/v1/developer/uc/sql/auth/common
    
    返回:
    - success: 是否验证成功
    - error_msg: 无权限列的错误信息
    - table_info: 包含无权限列的详细信息
        - schema, table
        - nonSensitiveColumns: 非敏感但无权限的列
        - sensitiveColumns: 敏感且无权限的列
    """
```

### 1.5 权限验证失败处理

根据数据来源类型返回不同的错误消息：

```python
# DataMart来源
DATAMART_AUTH_FAILED_MESSAGE_TEMPLATE = """
You do not have permission to access the data requested for the analysis. 

Columns and tables requiring data access: 
%s

Redirect to RAM to apply access: %s
"""

# DataTopic来源
TOPIC_AUTH_FAILED_MESSAGE_TEMPLATE = """
You do not have permission to access the data requested for the ChatBI Topic analysis. 
Please contact the Topic PIC: %s to grant you data access.

Columns and tables requiring data access:
%s
"""
```

---

## 2. SQL Execution (SQL执行)

### 2.1 执行引擎选择

ChatBI支持两种SQL执行引擎：

| 引擎 | 适用场景 | 特点 |
|------|---------|------|
| **StarRocks** | 主要执行引擎 | 物化视图、缓存加速 |
| **Presto** | 降级执行引擎 | 当StarRocks失败时使用 |

```
┌────────────────────────────────────────────────────────────────┐
│                    SQL Execution Flow                           │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│                 ┌─────────────────────┐                        │
│                 │   Rewritten SQL     │                        │
│                 └──────────┬──────────┘                        │
│                            │                                    │
│                            ▼                                    │
│              ┌─────────────────────────────┐                   │
│              │  Try StarRocks Execution    │                   │
│              │  (Cache Dataset/MV)         │                   │
│              └─────────────┬───────────────┘                   │
│                            │                                    │
│               ┌────────────┼────────────┐                      │
│               ▼            │            ▼                       │
│          ┌────────┐        │       ┌─────────┐                 │
│          │ Success │        │       │ Failed  │                 │
│          └────┬───┘        │       └────┬────┘                 │
│               │            │            │                       │
│               │            │            ▼                       │
│               │            │  ┌─────────────────────┐          │
│               │            │  │ Fallback to Presto  │          │
│               │            │  └──────────┬──────────┘          │
│               │            │             │                      │
│               ▼            ▼             ▼                      │
│         ┌──────────────────────────────────────┐               │
│         │          Query Results               │               │
│         │  { data: [], headers: [], ... }     │               │
│         └──────────────────────────────────────┘               │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 2.2 StarRocks执行

#### 核心代码位置
`di_brain/chat_bi/starrocks_client.py`

#### 执行流程

##### 非追问场景 - 创建Base Table

```python
async def cache_dataset(
    self, chat_id: str, sql: str, is_follow_up: bool, idc_region: str
) -> MaterialViewInfo:
    if is_follow_up:
        return self.create_material_view(chat_id, sql, is_follow_up, idc_region)
    return self.create_base_table(chat_id, sql, is_follow_up, idc_region)

def create_base_table(self, chat_id: str, sql: str, ...) -> MaterialViewInfo:
    """
    创建Base Table (CTAS)
    
    流程:
    1. 生成视图名称: chatbi_dataset_{chat_id}
    2. 提取ORDER BY子句
    3. 生成CREATE TABLE AS SQL
    4. 执行创建语句
    5. 返回MaterialViewInfo
    """
    table_name = self.generate_view_name(chat_id)  # chatbi_dataset_{chat_id}
    
    create_table_sql = f"""
        CREATE TABLE IF NOT EXISTS {catalog}.{database}.{table_name}
        AS {sql}
    """
```

##### 追问场景 - 创建物化视图

```python
def create_material_view(self, chat_id: str, sql: str, ...) -> MaterialViewInfo:
    """
    创建Materialized View用于追问
    
    流程:
    1. 将Trino SQL转换为StarRocks SQL
    2. 生成CREATE MATERIALIZED VIEW SQL
    3. 执行创建语句
    4. 触发同步并等待完成
    """
    actual_sql, order_by_clause = self.safe_parse_and_convert(sql)
    
    create_mv_sql = f"""
        CREATE MATERIALIZED VIEW IF NOT EXISTS {catalog}.{database}.{view_name}
        REFRESH DEFERRED MANUAL
        AS {actual_sql}
    """
```

##### 物化视图同步

```python
async def trigger_sync(self, view_name: str, idc_region: str) -> bool:
    """触发物化视图刷新"""
    refresh_sql = f"REFRESH MATERIALIZED VIEW {catalog}.{database}.{view_name}"
    self.execute_sql_mysql(refresh_sql, fetch_result=False)

async def wait_for_sync_completion(self, view_name: str, max_wait_time: int = 300) -> bool:
    """等待同步完成，每5秒检查一次状态"""
    while time.time() - start_time < max_wait_time:
        status = await self.check_sync_status(view_name)
        if status.get("refresh_state") == "SUCCESS":
            return True
        elif status.get("refresh_state") == "FAILED":
            return False
        await asyncio.sleep(5)
```

#### 缓存加速

StarRocks客户端支持表级缓存加速：

```python
def check_in_cache(self, sql, db_name, table_name):
    """
    检查表是否在缓存中
    
    缓存命中条件:
    1. 表名在cache列表中
    2. 分区条件匹配(RANGE/LIST/UNPARTITIONED)
    """

def rewrite_sql_by_cache(self, sql: str, idc_region: str):
    """
    使用缓存表重写SQL
    
    替换逻辑:
    - 原表: schema.table_name
    - 缓存表: cache_{schema}_{table_name}
    """
```

### 2.3 Presto执行 (降级方案)

#### 核心代码位置
`di_brain/tools/presto_executor.py`

#### 执行流程

```python
class PrestoExecutor:
    def execute(self, sql, **kwargs):
        """
        通过Adhoc任务执行SQL
        
        流程:
        1. 提交Adhoc任务 (submit_adhoc_task)
        2. 轮询任务状态 (check_adhoc_task_status)
        3. 获取结果数据 (fetch_adhoc_task_data)
        
        超时处理:
        - 最长等待300秒
        - 超时后kill任务
        """
        # Step 1: 提交任务
        ret, task_code = submit_adhoc_task(sql, account, project_code, queue, ...)
        
        # Step 2: 轮询状态 (最多300次，每次1秒)
        for i in range(300):
            status, msg = check_adhoc_task_status(task_code)
            if status == "SUCCESS":
                break
            elif status == "FAILED":
                return {"error": msg}
            time.sleep(1.0)
        
        # Step 3: 获取结果
        return fetch_adhoc_task_data(task_code)
```

### 2.4 执行结果格式

```python
# 成功结果
{
    "data": [[...], [...], ...],    # 数据行
    "headers": ["col1", "col2"],     # 列名
    "is_support_follow_up": True,    # 是否支持追问
    "adhocCode": "task_xxx"          # Presto任务码
}

# 失败结果
{
    "error": "error message"
}
```

---

## 3. Data Insight (LLM数据分析)

### 3.1 核心功能

```
┌────────────────────────────────────────────────────────────────┐
│                    Chart Generator (LLM)                        │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Input:                                                         │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ • User Question                                           │  │
│  │ • Query Result (CSV format)                               │  │
│  │ • Table Metadata                                          │  │
│  │ • Related Glossaries & Rules                              │  │
│  │ • Generated SQL                                           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                    │
│                            ▼                                    │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                     LLM Processing                        │  │
│  │  ┌────────────────────────────────────────────────────┐  │  │
│  │  │ 1. 分析数据 - 提取关键洞察                          │  │  │
│  │  │ 2. 选择图表 - 基于数据特征选择最佳可视化类型         │  │  │
│  │  │ 3. 列分类 - 识别DIMENSION/MEASURE/GROUPING          │  │  │
│  │  │ 4. 格式建议 - 推荐数值格式化配置                     │  │  │
│  │  │ 5. 生成洞察 - Markdown格式的数据分析                 │  │  │
│  │  │ 6. 相关问题 - 推荐3个后续分析问题                    │  │  │
│  │  └────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                    │
│                            ▼                                    │
│  Output:                                                        │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ • chart: 推荐图表类型                                     │  │
│  │ • headers: 列信息(名称、类型、描述)                        │  │
│  │ • explain: 数据洞察说明                                   │  │
│  │ • related_questions: 推荐问题                             │  │
│  │ • dataset_name: 数据集名称                                │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### 3.2 核心代码

#### 结果分析函数

```python
async def result_analyze(
    self, input, result, sql, tables, related_glossaries, related_rules, config
):
    """
    使用LLM分析查询结果
    
    处理流程:
    1. 获取LLM实例并配置structured_output
    2. 限制传给LLM的数据大小 (64KB)
    3. 格式化数据为CSV字符串
    4. 调用LLM生成分析结果
    5. 后处理结果数据
    """
    # 获取LLM
    llm = GET_SPECIFIC_LLM(model_name, extra_config=extra_config)
    llm = llm.with_structured_output(ChoiceChartOutput, strict=True)
    
    # 限制数据大小
    MAX_DATA_SIZE_FOR_LLM = 64 * 1024  # 64KB
    truncated_data = self.truncate_data(result["data"], MAX_DATA_SIZE_FOR_LLM)
    
    # 格式化为CSV
    data_str = self.format_result_to_csv_str(truncated_result)
    
    # 调用LLM
    gen_chart_result = gen_chart_chain.invoke({
        "question": input.get("question"),
        "data": data_str,
        "table_context": table_ctx,
        "sql": sql,
        "related_glossaries": json.dumps(related_glossaries),
        "related_rules": json.dumps(related_rules),
    })
```

### 3.3 LLM输出结构

```python
class ChoiceChartOutput(BaseModel):
    chart: Literal[
        "LINE_CHART", "BAR_CHART", "PIE_CHART", "FUNNEL_CHART",
        "METRIC_CARD", "AREA_CHART", "HORIZONTAL_BAR_CHART",
        "COMBO_CHART", "SCATTER_CHART"
    ]
    
    headers: list[DataColumnInfo]  # 列定义
    explain: str                    # 数据洞察 (max 1024 chars, Markdown)
    related_questions: List[str]    # 3个相关问题
    dataset_name: str               # 数据集名称

class DataColumnInfo(BaseModel):
    column_name: str
    dw_type: Literal["DIMENSION", "MEASURE", "GROUPING"]
    data_type: str  # STRING, DATE, NUMBER
    data_index: int
    column_description: str
    data_format: ColumnFormatConfig  # 数值格式化配置
```

### 3.4 图表选择逻辑

| 图表类型 | 适用场景 | 约束条件 |
|---------|---------|----------|
| METRIC_CARD | 单值或独立指标展示 | 仅MEASURE列 |
| LINE_CHART | 时间趋势数据 | 时间维度 + 指标 |
| BAR_CHART | 分类比较 | 离散分类 |
| PIE_CHART | 占比分布 | ≤6个分类 |
| FUNNEL_CHART | 转化流程 | 有序递减阶段 |
| SCATTER_CHART | 双变量关系 | 恰好2个MEASURE |
| COMBO_CHART | 双Y轴展示 | 恰好2个不同量纲的MEASURE |
| HORIZONTAL_BAR_CHART | 长标签分类 | 分类名称较长或>8个分类 |
| AREA_CHART | 累积趋势 | ≤5-6个分类 |

---

## 4. Post Process (后处理)

### 4.1 数据结构转换

```python
def process_result(self, result, data_header, data):
    """
    处理LLM分析结果，生成前端展示格式
    
    处理内容:
    1. 分离DIMENSION/MEASURE/GROUPING列
    2. 构建HeaderTree处理分组列
    3. 聚合数据按维度分组
    4. 转换格式化配置为styles
    """
```

### 4.2 HeaderTree构建

用于处理GROUPING列的层级结构：

```python
class HeaderTree:
    """
    构建表头树结构，处理分组展示
    
    场景: 当有GROUPING列时，每个GROUPING值会产生多个MEASURE列
    例如: region分组 + sales指标 -> sales_region1, sales_region2, ...
    """
    
    def add_data_for_all_leaves(self, nodeList: List[HeaderNode]):
        """为所有叶子节点添加子节点"""
        
    def search_field(self, path: List[str]):
        """按路径查找字段"""
```

### 4.3 数据格式化

```python
def convert_format_to_styles(self, columnHeader, headerTree):
    """
    将LLM生成的格式化配置转换为前端styles格式
    
    格式化类型:
    - Number: 普通数值 (带千分位分隔符)
    - Percent: 百分比 (2位小数)
    
    输出格式:
    {
        "columnFormatting": {
            "selectFieldId": ["field1", "field2"],
            "column": {
                "field1": {
                    "format": {"formatType": "Number", "numberSeparator": "Comma"},
                    "decimal": 2
                }
            }
        }
    }
    """
```

### 4.4 成功响应构建

```python
def build_success_event(self, result_data, sql, is_skip_auth, adhoc_code, access_conditions):
    return {
        "event": "SUCCESS",
        "data": {
            "suggestChart": result_data.get("chart"),
            "message": result_data.get("explain"),
            "dataset": {
                "dataset_name": result_data.get("dataset_name"),
                "headers": result_data.get("headers"),
                "data": result_data.get("data"),
                "styles": result_data.get("styles", {}),
            },
            "sql": sql,
            "access_conditions": access_conditions,
            "related_questions": result_data.get("related_questions"),
            "is_support_follow_up": result_data.get("is_support_follow_up"),
            "cached_tables": result_data.get("cached_tables"),
        }
    }
```

---

## 5. 追问场景处理

### 5.1 追问流程差异

| 步骤 | 非追问 | 追问 |
|------|--------|------|
| SQL生成 | ✅ | ❌ (使用已有SQL) |
| 权限验证 | ✅ | ❌ (已验证过) |
| SQL执行 | Base Table | Materialized View + Sync |
| 图表生成 | ✅ | ✅ |

### 5.2 追问执行流程

```python
async def _handle_followup_scenario(self, input, config, run_manager):
    """
    追问场景处理
    
    1. 合并多个dataset的access_conditions
    2. 创建物化视图
    3. 触发同步并等待完成
    4. 查询物化视图结果
    5. 执行LLM分析
    """
    # 合并权限条件
    access_conditions = self.merge_row_access_conditions(
        input.get("access_conditions_list", {})
    )
    
    # 创建物化视图
    mv_info = await starrocks_client.cache_dataset(chat_id, sql, True, idc_region)
    
    # 触发同步
    await starrocks_client.trigger_sync(mv_info.view_name)
    
    # 等待同步完成
    await starrocks_client.wait_for_sync_completion(mv_info.view_name)
    
    # 查询结果
    mv_result = await starrocks_client.query_data_set(mv_info.view_name)
```

---

## 6. 错误处理

### 6.1 错误类型

| 阶段 | 错误类型 | 处理方式 |
|------|---------|----------|
| SQL Authentication | 权限不足 | 返回申请权限链接 |
| SQL Rewrite | 重写失败 | 返回错误信息 |
| SQL Executor | 语法错误 | 提示检查SQL |
| SQL Executor | 执行失败 | 返回具体错误 |
| SQL Executor | 超时 | Kill任务，返回超时 |
| Chart Generator | 分组过多 | 提示视觉混乱 |

### 6.2 错误消息模板

```python
# 权限失败
AUTHENTICATION_FAILED_MESSAGE_TEMPLATE = """
Here is the SQL query based on your question, but execution failed 
due to you do not have access to %s columns used on the SQL.
"""

# SQL语法错误
SQL_SYNTAX_ERROR = """
Here is the SQL query based on your question, but execution failed 
because the query contains syntax errors and could not be executed.
"""

# SQL执行失败
SQL_EXECUTION_FAILED_MESSAGE_TEMPLATE = """
Here is the SQL query based on your question, but execution failed due to %s.
"""

# 图表生成失败
CHART_FAILED_MESSAGE_TEMPLATE = """
Grouping exceeds recommended limit, leading to visual clutter.
"""
```

---

## 7. 核心类与函数索引

| 模块 | 类/函数 | 功能 |
|------|---------|------|
| `chat_bi_stream_runnable.py` | `ChatBIStreamRunnable` | 主处理类 |
| `chat_bi_stream_runnable.py` | `_handle_initial_scenario` | 非追问场景处理 |
| `chat_bi_stream_runnable.py` | `_handle_followup_scenario` | 追问场景处理 |
| `chat_bi_stream_runnable.py` | `check_sql_authentication` | 权限验证入口 |
| `chat_bi_stream_runnable.py` | `execute_sql` | Presto执行入口 |
| `chat_bi_stream_runnable.py` | `result_analyze` | LLM分析入口 |
| `starrocks_client.py` | `StarRocksClient` | StarRocks执行客户端 |
| `starrocks_client.py` | `cache_dataset` | 创建缓存数据集 |
| `ram_sql_auth_tool.py` | `validate_user_sql_auth` | 列级权限验证 |
| `ram_row_access_tool.py` | `get_user_row_access_for_sql_rewrite` | 行级权限获取 |
| `sql_rewrite.py` | `rewrite_sql_with_access_conditions` | SQL重写 |
| `presto_executor.py` | `PrestoExecutor` | Presto执行器 |
| `chat_bi_prompt.py` | `GEN_CHART_PROMPT` | 图表生成Prompt |

---

## 8. 配置项

### 8.1 StarRocks配置

```python
starrocks_config = {
    "catalog": "default_catalog",
    "hive_catalog": "hive_catalog",
    "SG": {
        "host": "...",
        "port": ...,
        "database": "...",
    },
    "USEast": {
        "host": "...",
        "port": ...,
        "database": "...",
    }
}
```

### 8.2 RAM配置

```python
ram_sql_auth_config = {
    "url": "https://ram-api.xxx.com",
    "Authorization": "Bearer xxx",
    "X-DMP-Authorization": "xxx",
}
```

---

## 9. 总结

ChatBI的核心价值在于将自然语言查询转换为可视化的BI分析结果，整个流程通过：

1. **严格的权限控制** - 行级和列级双重权限验证
2. **智能的执行策略** - StarRocks主路径 + Presto降级方案
3. **AI驱动的分析** - LLM自动选择图表类型和生成数据洞察
4. **灵活的追问机制** - 支持基于已有数据的进一步探索

实现了企业级数据分析的安全性、性能和智能化的统一。

