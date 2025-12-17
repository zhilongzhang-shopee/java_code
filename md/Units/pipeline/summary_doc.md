```python
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
import time
import requests
import json
import mysql.connector
from pydantic import BaseModel
from typing import List, Any, Iterator
import pandas as pd
from pyspark.sql.types import StructType, StructField, StringType


spark = SparkSession.builder.appName("Spark SQL").getOrCreate()
# 读取data_infra.di_diana_kb_markdown_document_tab_v1_cleaned表中的数据
query = f"""
    SELECT 
        knowledge_base_name,
        url AS source_url,
        'ai_summary' AS document_type,
        title AS title,
        title AS index_info,
        markdown_content AS text_content
    FROM data_infra.di_diana_kb_markdown_document_tab_v1_cleaned
"""

df = spark.sql(query)

# print("从spark中查询到数据: ")

# df.show()

# print(f"从spark中查询到数据行数: {df.count()}")

# 2. 按 knowledge_base_name 分组，并将文档信息聚合为列表
grouped_df = df.groupBy("knowledge_base_name").agg(
    F.collect_list(F.struct("index_info", "text_content", "title")).alias("raw_docs")
)

print(f"分组后有数据行数: {grouped_df.count()}")

def call_api_for_partition(iterator: Iterator[pd.DataFrame]) -> Iterator[pd.DataFrame]:
    """
    一个完全自包含的Pandas UDF,它在内部手动构建字典来创建JSON请求体,
    从而避免了对外部Pydantic类的依赖,彻底解决了序列化问题。
    """
    api_url = "https://backend.dibrain.data-infra.live-test.shopee.io/group_intro/generate/invoke"
    headers = {"Content-Type": "application/json"}

    for pdf in iterator:
        results = []
        for index, row in pdf.iterrows():
            output_row = {
                "knowledge_base_name": row["knowledge_base_name"],
                "text_content": "", # 默认值
                "document_type": "doc_summary",
                "index_info": "",
                "title": "",
                "source_url": ""
            }
            
            try:
                # 确保嵌套的 raw_docs 列表中的每个字典的值都是原生 Python 类型
                processed_raw_docs = []
                
                # 迭代 raw_docs 列表中的每个文档 (字典)
                for doc in row["raw_docs"]:
                    # 强制将字典中的值转换为原生 Python 字符串 (str)
                    # 这样可以处理 numpy.str_ 和 numpy.int64 等类型
                    processed_doc = {
                        "index_info": str(doc["index_info"]),
                        "text_content": str(doc["text_content"]),
                        "title": str(doc["title"]),
                        # 如果 raw_docs 还有其他字段，也需要在此处显式转换
                    }
                    processed_raw_docs.append(processed_doc)
                
                # 1. 手动构造请求体字典
                request_body_dict = {
                    "config": {"metadata": {"reg": "SG", "user_email": ""}},
                    "input": {
                        # 使用转换后的列表
                        "raw_docs": processed_raw_docs,
                        
                        # 同时确保 knowledge_base_name 也是原生 str 类型
                        "table_group_id": str(row["knowledge_base_name"]), 
                    },
                }


                # 2. 调用API，使用标准json库进行序列化
                response = requests.post(
                    api_url, data=json.dumps(request_body_dict), headers=headers
                )
                
                # 先检查状态码，再解析
                response.raise_for_status() 
                response_data = response.json()

                # 3. 解析响应字典
                introduction = response_data.get("output", {}).get("introduction")
                
                if introduction:
                    output_row["text_content"] = introduction
                else:
                    output_row["text_content"] = "API_RESPONSE_EMPTY"
                
            except requests.exceptions.HTTPError as e:
                # HTTP 4xx 或 5xx 错误
                error_msg = f"HTTP_ERROR: {e.response.status_code}"
                output_row["text_content"] = error_msg
                print(f"HTTP 错误 for {row['knowledge_base_name']}: {error_msg}")
            
            except requests.exceptions.RequestException as e:
                # 其他请求错误 (如连接超时)
                error_msg = f"REQUEST_ERROR: {e}"
                output_row["text_content"] = error_msg
                print(f"请求错误 for {row['knowledge_base_name']}: {error_msg}")
                
            except Exception as e:
                # JSON 解析或其他通用错误
                error_msg = f"GENERAL_ERROR: {e}"
                output_row["text_content"] = error_msg
                print(f"通用错误 for {row['knowledge_base_name']}: {error_msg}")

            results.append(output_row) # 添加到结果列表

        # yield 整个块的结果
        if results:
            yield pd.DataFrame(results)

# 4. 定义结果 DataFrame 的 Schema
result_schema = StructType([
    StructField("knowledge_base_name", StringType(), True),
    StructField("text_content", StringType(), True),
    StructField("document_type", StringType(), True),
    StructField("index_info", StringType(), True),
    StructField("title", StringType(), True),
    StructField("source_url", StringType(), True)
])


# 在不同patition上并行执行API调用
result_df = grouped_df.mapInPandas(call_api_for_partition, schema=result_schema)
# print("处理后的结果: ")
# result_df.show()

# print(f"处理后的数据行数: {result_df.count()}")


# 定义 UPSERT 函数
def upsert_partition(partition):
    print("开始连接数据库。")
    # 创建数据库连接
    connection = mysql.connector.connect(
        host="master.cfd4f8b9e8074c6f.mysql.cloud.staging.shopee.io",
        port="6606",
        user="data_infrastructure_stag",
        password="TuCHo7J4bbDNo2us_ShW",
        database="shopee_di_knowledge_base_db",
    )
    cursor = connection.cursor()

    print('连接数据库成功')
    # 遍历分区中的每一行
    i = 0
    for row in partition:
        data = row.asDict()
        columns = ", ".join(data.keys())
        placeholders = ", ".join(["%s"] * len(data))
        update_clause = ", ".join([f"{col} = VALUES({col})" for col in data.keys()])

        sql = f"""
        INSERT INTO knowledge_base_details_v1_5_4 ({columns})
        VALUES ({placeholders})
        ON DUPLICATE KEY UPDATE {update_clause}
        """
        print(sql)

        cursor.execute(sql, list(data.values()))

        i += 1
        if i % 100 == 0:
            print("commit batch %d" % i)
            connection.commit()
            # 限流逻辑：每次插入后暂停一段时间
            time.sleep(0.1)  # 暂停 0.1 秒

    connection.commit()
    cursor.close()
    connection.close()


# 5. 将 result_df 写入数据库
try:
    print("开始写入数据库。")
    # 6. 调用 foreachPartition 将数据写入 MySQL
    result_df.foreachPartition(upsert_partition)
    print("AI 摘要写入数据库完成。")
except:
    print("AI Summary 写入数据库失败!")

# 停止 SparkSession
spark.stop()

```

