```python
from pyspark.sql import SparkSession
import mysql.connector
from pydantic import BaseModel
from typing import List, Any, Iterator
import pandas as pd
from pyspark.sql.types import StructType, StructField, StringType
import time

spark = SparkSession.builder.appName("Spark SQL").getOrCreate()

query = f"""
        SELECT 
            knowledge_base_name,
            document_type ,
            text_content ,
            index_info ,
            title ,
            source_url
        FROM data_infra.di_diana_kb_summary_document_tab
        WHERE knowledge_base_name NOT LIKE 'chatbi_topic%'
    """

df = spark.sql(query)

print('从hive表里读出数据: ')
df.show()
print(f"读出数据行数: {df.count()}")

# 定义 UPSERT 函数
def upsert_partition(partition):
    print("开始连接数据库。")
    # 创建数据库连接
    connection = mysql.connector.connect(
        host='master.bea82e5eb9804f11.mysql.cloud.test.shopee.io',
        port="6606",
        user='sg_di_test',
        password='WxLTBRO_M9rAzsL8dxHq',
        database='shopee_di_knowledge_base_db'
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

print("开始将数据从Hive表中写入MySQL.")

df.foreachPartition(upsert_partition)

print("数据从Hive表中写入MySQL完毕.")

spark.stop()
```

