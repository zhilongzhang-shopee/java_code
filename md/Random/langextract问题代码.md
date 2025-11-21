```python
annotation.py
line325  for text_chunk, scored_outputs in zip(batch, batch_scored_outputs):
```



```shell
完整调用链路图：

extract()
├── validate_prompt_alignment()          # Prompt验证
├── factory.create_model()              # 创建语言模型
│   └── GeminiLanguageModel() 或 OpenAILanguageModel()
├── FormatHandler.from_resolver_params() # 创建格式处理器
├── resolver.Resolver()                  # 创建解析器
├── annotation.Annotator()               # 创建注释器
└── annotator.annotate_text() 或 annotator.annotate_documents()
    └── _annotate_documents_single_pass()
        ├── _document_chunk_iterator()   # 文档分块
        │   └── ChunkIterator()
        │       └── SentenceIterator()
        │           └── tokenizer.tokenize()
        ├── make_batches_of_textchunk()  # 批量组织
        └── For each batch:
            ├── _prompt_generator.render()     # 生成Prompt
            ├── language_model.infer()         #  LLM并行推理
            │   └── ThreadPoolExecutor + API调用
            ├── resolver.resolve()             # 解析LLM输出
            │   └── format_handler.parse_output()
            └── resolver.align()               # 对齐映射
                └── WordAligner.align_extractions()
                    ├── difflib.SequenceMatcher()
                    └── _fuzzy_align_extraction()
```





```python
# 代码块1 
for text_chunk, scored_outputs in zip(batch, batch_scored_outputs):
  logging.debug("Processing chunk: %s", text_chunk)
  if not scored_outputs:
    logging.error(
        "No scored outputs for chunk with ID %s.", text_chunk.document_id
    )
    raise exceptions.InferenceOutputError(
        "No scored outputs from language model."
    )
  while curr_document.document_id != text_chunk.document_id:
    logging.info(
        "Completing annotation for document ID %s.",
        curr_document.document_id,
    )
    annotated_doc = data.AnnotatedDocument(
        document_id=curr_document.document_id,
        extractions=annotated_extractions,
        text=curr_document.text,
    )
    yield annotated_doc
    annotated_extractions.clear()

    curr_document = next(doc_iter, None)
    assert curr_document is not None, (
        f"Document should be defined for {text_chunk} per"
        " _document_chunk_iterator(...) specifications."
    )

  top_inference_result = scored_outputs[0].output
  logging.debug("Top inference result: %s", top_inference_result)

  annotated_chunk_extractions = resolver.resolve(
      top_inference_result, debug=debug, **kwargs
  )
  chunk_text = text_chunk.chunk_text
  token_offset = text_chunk.token_interval.start_index
  char_offset = text_chunk.char_interval.start_pos
```





```python
# 代码块2 模糊匹配
extraction_tokens = list(_tokenize_with_lowercase(extraction.extraction_text))
# Work with lightly stemmed tokens so pluralisation doesn't block alignment
extraction_tokens_norm = [_normalize_token(t) for t in extraction_tokens]

# 优化1、 对source_tokens进行规范化缓存，避免每次都重新规范化 
source_tokens_norm = [_normalize_token(t) for t in source_tokens]

if not extraction_tokens:
    return None

logging.debug(
    "Fuzzy aligning %r (%d tokens)",
    extraction.extraction_text,
    len(extraction_tokens),
)

best_ratio = 0.0
best_span: tuple[int, int] | None = None  # (start_idx, window_size)

len_e = len(extraction_tokens)
# max_window = len(source_tokens)

# 优化2、 限制最大窗口大小
max_window = min(len(source_tokens), len_e * 10)

extraction_counts = collections.Counter(extraction_tokens_norm)
min_overlap = int(len_e * fuzzy_alignment_threshold)

matcher = difflib.SequenceMatcher(autojunk=False, b=extraction_tokens_norm)

for window_size in range(len_e, max_window + 1):
  if window_size > len(source_tokens):
    break

  # Initialize for sliding window
  window_deque = collections.deque(source_tokens[0:window_size])
  # window_counts = collections.Counter(
  #    [_normalize_token(t) for t in window_deque]
  # )
  
  # 优化 使用预处理的token
  window_counts = collections.Counter(source_tokens_norm[0:window_size])

  for start_idx in range(len(source_tokens) - window_size + 1):
    # Optimization: check if enough overlapping tokens exist before expensive
    # sequence matching. This is an upper bound on the match count.
    if (extraction_counts & window_counts).total() >= min_overlap:
      window_tokens_norm = [_normalize_token(t) for t in window_deque]
      matcher.set_seq1(window_tokens_norm)
      matches = sum(size for _, _, size in matcher.get_matching_blocks())
      if len_e > 0:
        ratio = matches / len_e
      else:
        ratio = 0.0
      if ratio > best_ratio:
        best_ratio = ratio
        best_span = (start_idx, window_size)
        
        # 优化 尝试早停
        if ratio >= early_stop:
            break

    # Slide the window to the right
    if start_idx + window_size < len(source_tokens):
      # Remove the leftmost token from the count
      old_token = window_deque.popleft()
      old_token_norm = _normalize_token(old_token)
      window_counts[old_token_norm] -= 1
      if window_counts[old_token_norm] == 0:
        del window_counts[old_token_norm]

      # Add the new rightmost token to the deque and count
      new_token = source_tokens[start_idx + window_size]
      window_deque.append(new_token)
      # new_token_norm = _normalize_token(new_token)
      # 优化 使用预处理的token
      new_token_norm = source_tokens_norm[start_idx + window_size]
      window_counts[new_token_norm] += 1
```



```shell
算法时间复杂度：平均O(n^2 * m) 最坏O(n^3 * m)
其中：
n = source_tokens 的长度
m = extraction_tokens 的长度
1. 外层循环：window_size 从 len_e 到 max_window，最多 O(n) 次
2. 内层循环：start_idx 从 0 到 len(source_tokens) - window_size，最多 O(n) 次
3. SequenceMatcher：当通过预检查时，调用 get_matching_blocks()，复杂度约为 O(m)，最坏情况下O(n*m)，相当于 最长公共序列

# 假设：
window_tokens_norm = ['hello', 'beautiful', 'world']
extraction_tokens_norm = ['hello', 'world', 'test']
matcher = difflib.SequenceMatcher(None, window_tokens_norm, extraction_tokens_norm)
blocks = matcher.get_matching_blocks()
# 可能返回：[(0, 0, 1), (2, 1, 1), (3, 3, 0)]
# 解释：'hello'匹配(长度1)，'world'匹配(长度1)，结束标记(长度0)
matches = sum(size for _, _, size in blocks) # 1 + 1 + 0 = 2

可能的优化方案：

1. 早退出机制，如果相识度达到一个threshold，就不再继续比较了
2. 缓存标准化结果_normalize_token。
3. 窗口大小优化，源码窗口大小从len_e到整个len(source_tokens)，缩小窗口大小。
4. 并行处理多个窗口、增加批次batch以及woker。
5. 可能存在的优化的字符串匹配算法：N-gram+Jaccard Similarity 
  - 时间复杂度为O(n+m)
  - 缺点是Jaccard Similarity是集合计算，忽略词序
  
  示例：
  对于句子："The quick brown fox jumps over the lazy dog"
  Unigrams (N=1): ["The", "quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog"]
  Bigrams (N=2): ["The quick", "quick brown", "brown fox", "fox jumps", "jumps over", "over the", "the lazy", "lazy dog"]
  Trigrams (N=3): ["The quick brown", "quick brown fox", "brown fox jumps", ...]
  
  对于中文句子："我爱新加坡" (分词后为: 我 / 爱 / 新加坡)
  Unigrams: ["我", "爱", "新加坡"]
  Bigrams: ["我 爱", "爱 新加坡"]
  Trigrams: ["我 爱 新加坡"]
```



- **定期**从kb中读出所有的raw (doc、group、table)，根据文档**类型**筛选出**未进行过精简** 操作的文档，调用generate AI Introduction API后，处理后的文件**直接**存入KB中，还是放入async schedule task queue中，等待任务自动执行？

- 文档类型有：

  ```shell
  # Document types
  TYPE_DATAMAP_TABLE_MANIFEST: ClassVar[str] = "datamap_table_manifest"
  TYPE_DATAMAP_TABLE_DETAIL: ClassVar[str] = "datamap_table_detail"
  TYPE_DATAMAP: ClassVar[str] = "datamap"
  TYPE_CONFLUENCE: ClassVar[str] = "confluence"
  TYPE_GOOGLE_DOC: ClassVar[str] = "google_doc"
  TYPE_DATAMART_DESC_DOC: ClassVar[str] = "datamart_desc_doc"
  TYPE_DATAMART_SUMMARY: ClassVar[str] = "datamart_desc_doc_summary"
  ```

​	除此之外，还需要新增一个新的类型 **AI Introduction**，对于精简文档操作，是仅针对**datamart_desc_doc**还是所有文档类型？

- 哪里能够看到kb的具体表格，想知道kb表格的具体字段。

- 大概执行步骤：

  1. 对于datamart_kb_names中的所有mart，依次取出其中的所有需要进行精简的文档类型Document types和该文档类型的所有文档。

     ```python
     kb_name_list = [to_datamart_kb_name(mart_name) for mart_name in mart_name_list]
     # 1、遍历所有mart
     for doc_type in Document types:
       # retrive knowledge base detail from kb
       knowledge_base_details_list[doc_type] = kb_client.get_details_by_knowledge_base_names(
           kb_name_list, doc_type
       )
     ```

  2. 判断该文档是否存在精简后的AI Introduction，由于就算一个文档执行精简操作后，**源文档也不会删除**；因此无法直接查询出所有Document types不为**AI Introduction**的文档进行精简操作。而是需要对于每一个源文档（raw file）都需要判断其存不存在对应的**AI Introduction**，如果不存在，则执行精简操作；否则跳过。
      ```python
      # 对于每一个文档，判断其是否存在精简文档类型，将所有mart的所有精简文档都查询出来放入到set中（但是应该不需要查询出文档的detail内容，只需要查询出源文档的唯一标识名称（唯一标识主键，例如mart+document_type+file_name））
      knowledge_base_done_list = set(kb_client.get_details_by_knowledge_base_names(
            kb_name_list, "AI Introduction"
        ))
      # 逐一判断knowledge_base_details_list中的文件是否在knowledge_base_done_list中
      for file in knowledge_base_details_list:
        if file in knowledge_base_done_list:
          continue
         # 精简后的结果文件
        result_file = generate_introduction_api(file)
        
        # 保存到kb中 ? 或者放入async schedule task queue中？
        save_to_kb(result_file)
      ```

-       对于`gen_mid_json_direct_llm.py`文件的提取类型不应该写死为**datamart_desc_doc**？

 

```shell
# chunk大小为 默认1000 字符数 30000 默认worker=10, 默认batch = 10 69.23s
# chunk大小为 500 字符数 30000 worker=10, batch = 10 时间 270.14s
# chunk 大小为 1000 字符数30000 worker=20, batch = 20 时间 45.35s
# chunk 大小为 2000 字符数30000 worker=20, batch = 20 时间 252.05s
# chunk 大小为 1500 字符数30000 worker=20, batch = 20 时间 128.43s
# chunk 大小为 1000 字符数30000 worker=20, batch = 20 修改代码 时间 41.69s
# chunk 大小为 1500 字符数30000 worker=30, batch = 30 时间 46.35s
# chunk 大小为 500 字符数30000 worker=30, batch = 30 时间 231.96s
# chunk 大小为 2000 字符数177312 worker=30, batch = 30 时间 1238.08s
# chunk 大小为 1000 字符数60000 worker=30, batch = 30 时间 68.64s


export PYTHONPATH=/Users/zhilong.zhang/PycharmProjects/Langextract:$PYTHONPATH

# chunk大小为 默认1000 字符数 30000 69.23s
Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
Chunk 1 completed successfully
Processing chunk 2/6 (30000 chars)...
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 185.46 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 310k/310k [00:00<00:00, 166MB/s]
Chunk 2 completed successfully
Extraction completed, saving results...
Extraction completed in 150.88 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
Visualization file saved to: kb_extraction_visualization.html 

# chunk大小为 500 字符数 30000 时间 270.14s

# chunk 大小为 1000 字符数30000 worker=20, batch = 20 时间 45.35s
Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 45.35 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 274.05 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 248k/248k [00:00<00:00, 149MB/s]
Visualization file saved to: kb_extraction_visualization.html

# chunk 大小为 2000 字符数30000 worker=20, batch = 20 时间 252.05s
Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 252.05 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 209.55 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 246k/246k [00:00<00:00, 172MB/s]
Visualization file saved to: kb_extraction_visualization.html

# chunk 大小为 1500 字符数30000 worker=20, batch = 20 时间 128.43s
Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 128.43 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
Visualization file saved to: kb_extraction_visualization.html
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 261.08 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 242k/242k [00:00<00:00, 164MB/s]

Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
LangExtract: model=gemini-2.5-flash [00:41]
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 41.69 seconds
LangExtract: Saving to langextract_modify_result.jsonl: 1 docs [00:00, 220.22 docs/s]
✓ Saved 1 documents to langextract_modify_result.jsonl
Results saved to: kb_extlangextract_modify_result.jsonl

Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 227.17 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 248k/248k [00:00<00:00, 197MB/s]
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 46.35 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
Visualization file saved to: kb_extraction_visualization.html

Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (30000 chars)...
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 251.70 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 249k/249k [00:00<00:00, 171MB/s]
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 231.96 seconds
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
Visualization file saved to: kb_extraction_visualization.html

Starting knowledge base document extraction...
Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 6 chunks
Starting langextract extraction...
Processing chunk 1/6 (177312 chars)...
Chunk 1 completed successfully
Extraction completed, saving results...
Extraction completed in 1238.08 seconds
LangExtract: Saving to kb_extraction_results.jsonl: 1 docs [00:00, 62.61 docs/s]
LangExtract: Loading kb_extraction_results.jsonl: 100%|██████████| 581k/581k [00:00<00:00, 132MB/s]
✓ Saved 1 documents to kb_extraction_results.jsonl
Results saved to: kb_extraction_results.jsonl
✓ Loaded 1 documents from kb_extraction_results.jsonl
Visualization file saved to: kb_extraction_visualization.html

Successfully read document, length: 177312 characters
Document is large (177312 chars), processing in chunks...
Split into 3 chunks
Starting langextract extraction...
Processing chunk 1/3 (60000 chars)...
Chunk 1 completed successfully, time: 68.64 seconds
```







## **TODO list:**

1. generate AI Intriduction API目前是调用一个http服务，后续进行长时间优化迭代
2. 定时将kb中的所有文档取出进行 **精简操作**，但是需要根据文档的**类型**进行区分，如果是已经精简过的文档（例如 文档类型type为 AI Introduction，表明文档已经是精简过的）则不需要取出操作。
3. 对于精简后的文档进行必要的结构化（或格式化），使得文档适应kb中的文档格式。
4. 从kb中查询用户所需要的table、group、introduction（经过find data agent处理后的请求），然后返回给用户（JAVA层面的查询操作）
5. 将定时任务（sync schedule task）从任务队列中取出Mart（topic等）进行精简处理做成一个下游的服务（downstream process）,还是等待任务到它的定时时间自动处理？

## **Wanna to know:**

1. sync schedule task的具体实现以及哪些操作会导致有task会存到其中。
2. kb service的业务文档与介绍
3. 与kb service交互（从中读取出文档、将精简处理好的文档如何存入）的方法
4. generate AI Intriduction API的相关信息
