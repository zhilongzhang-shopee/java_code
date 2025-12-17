```python
vfrom pyspark.sql import SparkSession
from pyspark.sql import Row
import time
import random
import requests
from openai import OpenAI
from datetime import datetime
from pymilvus import MilvusClient, DataType
import json
import re
from rag_config import get_rag_config_by_env

spark = SparkSession.builder.appName("hive_table_column_manifest_embedding").getOrCreate()
RAG_CONFIG = get_rag_config_by_env()

# 配置
MILVUS_CONFIG = RAG_CONFIG['MILVUS']
MILVUS_URI = MILVUS_CONFIG['MILVUS_URI']
MILVUS_TOKEN = MILVUS_CONFIG['MILVUS_TOKEN']
MILVUS_DB = MILVUS_CONFIG['MILVUS_DB']
COMPASS_GENERATE_URL = RAG_CONFIG['EMBEDDING']['BASE_URL']
COMPASS_API_KEY = RAG_CONFIG['EMBEDDING']['BASE_URL']




# 全局客户端
openAIClient = None
milvusClient = None
translate_client = None


def create_collection_schema():
    """创建collection的schema，基于embedding.py中的数据结构"""

    fields = [
        FieldSchema(
            name="uid",
            dtype=DataType.VARCHAR,
            max_length=700,
            is_primary=True,
            auto_id=False,
        ),
        FieldSchema(
            name="data_marts",
            dtype=DataType.VARCHAR,
            max_length=200,
        ),
        FieldSchema(
            name="table_column_vector",
            dtype=DataType.FLOAT_VECTOR,
            dim=384
        )
    ]
    schema = CollectionSchema(fields=fields, description="Hive table column manifest for RAG")
    return schema



def format_group(row):
    group_row_dict = row.asDict()
    row_list = group_row_dict['row_list']
    
    new_row_list = []
    for row_struct in row_list:
        row_dict = row_struct.asDict()
        table_full_name = f"{row_dict['schema']}.{row_dict['table_group_name']}"
        data_marts = row_dict['data_marts']
        column_info_list = row_dict.get('column_info', [])
        
        if column_info_list:
            for column_info_str in column_info_list:
                # try:
                column_info_dict = json.loads(column_info_str)
                
                # 过滤掉column_name包含'id'的列（不区分大小写）
                column_name = column_info_dict.get('column_name', '')
                if re.search(r'\bid\b', column_name, re.IGNORECASE):
                    continue
                
                formated_txt = f"{column_info_dict['column_name']}\n"
                formated_txt += f"{column_info_dict['data_type']}\n"
                
                if column_info_dict.get("ai_desc", ""):
                    desc = column_info_dict['ai_desc']
                    formated_txt += f"{desc}"
                
                uid = f"{table_full_name}.{column_info_dict['column_name']}"
                new_row_list.append(Row(uid=uid, data_marts=data_marts, text=formated_txt))
                
                # except Exception as e:
                #     print(f"Error processing column info: {e}, column_info: {column_info_str}")
                #     continue
    
    return Row(row_list=new_row_list)




def process_batch_embeddings_with_retry(text_to_embedding, collection_name, batch_size=50):
    """带重试的批量embedding处理，支持不同维度的embedding"""
    vectors = []
    try:
        vectors = process_batch_embeddings(text_to_embedding, collection_name, batch_size)
    except Exception as e:
        # if retry_count > 0:
        print(f'Error processing batch with size {batch_size}: {str(e)}')
        print(f'Retry count: {retry_count}')
        time.sleep(0.5)
        # 重试时进一步减小batch_size
        new_batch_size = max(10, int(batch_size / 2))
        return process_batch_embeddings_with_retry(text_to_embedding, collection_name, new_batch_size)
        # else:
            # raise e
    return vectors

def process_batch_embeddings(text_to_embedding, collection_name, batch_size=20):
    """批量处理文本嵌入，支持不同维度的embedding"""
    vectors = []
    
    # 实验组4使用896维，其他使用384维
    dimensions = 384
    
    for i in range(0, len(text_to_embedding), batch_size):
        batch_texts = text_to_embedding[i:i + batch_size]
        
        # QPM限流

        time.sleep(1)
        # try:
        embeddings = openAIClient.embeddings.create(
            input=batch_texts,
            model="compass-embedding-v3",
            dimensions=dimensions
        )
        batch_vectors = [item.embedding for item in embeddings.data]
        vectors.extend(batch_vectors)
        
        if (i // batch_size) % 10 == 0:  # 每10个批次打印一次进度
            print(f'Processed {i + len(batch_texts)} texts for embedding (group: {collection_name}, dim: {dimensions})')
                
        # except Exception as e:
        #     print(f"Error in embedding batch {i}-{i+batch_size} (group: {test_group}): {e}")
        #     # 对于失败的批次，填充空向量
        #     vectors.extend([[] for _ in range(len(batch_texts))])
        #     continue
    
    return vectors

def embedding_function(row, collection_name):
    """生成embedding，支持不同实验组"""
    global openAIClient
    if openAIClient is None:
        openAIClient = OpenAI(
            api_key = COMPASS_API_KEY, 
            base_url = COMPASS_GENERATE_URL
        )
    
    group_row_dict = row.asDict()
    row_list = group_row_dict['row_list']
    
    text_to_embedding = []
    valid_rows = []
    
    for row_struct in row_list:
        row_dict = row_struct.asDict()
        text = row_dict['text']
        
        # 截断过长的文本
        if len(text) > 7000:
            text = text[:7000]
        
        text_to_embedding.append(text)
        valid_rows.append(row_struct)
    
    if not text_to_embedding:
        return Row(row_list=[])
    
    # 批量处理嵌入
    vectors = process_batch_embeddings_with_retry(text_to_embedding, collection_name=collection_name, batch_size=50)
    
    # 构建新的行列表
    new_row_list = []
    for i, row_struct in enumerate(valid_rows):
        if i < len(vectors) and vectors[i]:  # 确保有有效的向量
            row_dict = row_struct.asDict()
            # 移除text字段以节省内存
            row_dict.pop('text', None)
            
            new_row_struct = Row(**row_dict, table_column_vector=vectors[i])
            new_row_list.append(new_row_struct)
    
    return Row(row_list=new_row_list)

def write_data_to_milvus(row, collection_name):
    """写入数据到指定的Milvus collection"""
    group_row_dict = row.asDict()
    row_list = group_row_dict['row_list']
    
    if not row_list:
        return Row(original=0, result=0, collection=collection_name)
    
    new_row_list = []
    for row_struct in row_list:
        row_dict = row_struct.asDict()
        new_row_list.append(row_dict)
    
    global milvusClient
    if milvusClient is None:
        milvusClient = MilvusClient(uri=MILVUS_URI, token=MILVUS_TOKEN, db_name=MILVUS_DB)
    
    # 分批写入Milvus
    upsert_count = 0
    batch_size = 200
    
    for i in range(0, len(new_row_list), batch_size):
        batch = new_row_list[i:i + batch_size]
        
        res = milvusClient.upsert(collection_name=collection_name, data=batch)
        upsert_count += res['upsert_count']
        print(f'Successfully upserted batch {i//batch_size + 1} to {collection_name}, count: {res["upsert_count"]}')
        # except Exception as e:
        #     print(f'Error upserting batch {i//batch_size + 1} to {collection_name}: {e}')
        #     continue
        # time.sleep(0.5)  # 控制QPS
    
    return Row(original=len(new_row_list), result=upsert_count, collection=collection_name)

def process_group_data(data, format_func, collection_name):
    """处理单个实验组的数据"""
    print(f"\n{'='*60}")
    print(f"Processing {collection_name} group...")
    print(f"{'='*60}")
    
    # 格式化文本
    formatted_data = data.map(format_func)
    
    # 生成embedding
    embedded_data = formatted_data.map(lambda x: embedding_function(x, collection_name))
    
    # 写入Milvus
    result_rdd = embedded_data.map(lambda x: write_data_to_milvus(x, collection_name))
    results = result_rdd.collect()
    
    # 统计结果
    total_original = sum(i.original for i in results if i)
    total_upserted = sum(i.result for i in results if i)
    

    print(f"  Collection: {collection_name}")
    print(f"  Total original rows: {total_original}")
    print(f"  Total upserted rows: {total_upserted}")
    
    if total_original > 0:
        success_rate = (total_upserted / total_original) * 100
        print(f"  Success rate: {success_rate:.2f}%")
    
    return {
        'collection': collection_name,
        'original': total_original,
        'upserted': total_upserted,
        'success_rate': (total_upserted / total_original * 100) if total_original > 0 else 0
    }




def main():

    milvus_client = MilvusClient(uri=MILVUS_URI, token=MILVUS_TOKEN, db_name=MILVUS_DB)
    COLLECTION_NAME = "di_rag_hive_table_column_manifest_v0"
    if not milvus_client.has_collection(COLLECTION_NAME):

        milvus_client.create_collection(
            collection_name=COLLECTION_NAME,
            shards_num=2,
            schema=create_collection_schema(),
        )
        index_params = milvus_client.prepare_index_params()
        index_params.add_index(
            field_name="table_column_vector",
            index_type="HNSW",
            metric_type="L2",
            params={"M": 16, "efConstruction": 500},
        )
        milvus_client.create_index(collection_name=COLLECTION_NAME, index_params=index_params)
        print("✅ 索引创建成功!")

        # 7. 加载collection到内存
        print("📥 加载 Collection 到内存...")
        milvus_client.load_collection(COLLECTION_NAME)
        print("✅ Collection 加载成功!")

    grass_date = "${bizTimeFormatter(BIZ_TIME, 'yyyy-MM-dd', '-2d')}"

    df = spark.sql(f"""
        select 
            group_key,
            collect_list(row_struct) as row_list
        from (
            select 
                named_struct(
                    'schema', schema,
                    'data_marts', data_marts,
                    'table_group_name', table_group_name,
                    'column_info', column_info,
                    'ai_desc', ai_desc
                ) as row_struct,
                floor(random() * 1000) as group_key
            from (
                SELECT 
                    t.schema,
                    t.table_group_name,
                    nvl(first(t.ai_desc, true), '') AS ai_desc,
                    nvl(first(t.data_marts, true), '') AS data_marts,
                    nvl(first(t.column_info, true), array()) AS column_info
                FROM (
                    SELECT * FROM data_infra.dwd_di_rag_hive_group_knowledge_live_df 
                    WHERE grass_date = '{grass_date}'
                ) g1
                JOIN (
                    SELECT *
                    FROM data_infra.dwd_di_rag_hive_table_knowledge_live_df 
                    WHERE grass_date = '{grass_date}'
                    AND data_marts in ("Item Mart", "User Mart", "Order Mart", "DI MetaMart")
                ) t
                ON g1.schema = t.schema
                AND g1.table_name = t.table_name
                AND g1.table_group_name = t.table_group_name
                
                GROUP BY t.schema, t.table_group_name
            ) t1
        ) t2 
        group by group_key
    """)
    # df.cache()
    print(f"Total partitions: {df.rdd.getNumPartitions()}")
    estimated_rows = df.count()
    print(f"Estimated rows: {estimated_rows}")
    
    

    data = df.rdd


    
    group_config = {
            'format_func': format_group,
            'collection': 'di_rag_hive_table_column_manifest_v0'
        }
    
    
    result = process_group_data(
        data, 
        group_config['format_func'], 
        group_config['collection']
    )
    
    
    # 打印总结报告
    print(f"\n{'='*60}")
    print("SUMMARY REPORT")
    print(f"{'='*60}")

    print(f"  Collection: {result['collection']}")
    print(f"  Rows processed: {result['original']}")
    print(f"  Rows upserted: {result['upserted']}")
    print(f"  Success rate: {result['success_rate']:.2f}%")
    
    print(f"\n{'='*60}")
    print("All groups processing completed!")
    print(f"{'='*60}")

if __name__ == "__main__":
    main()
```

