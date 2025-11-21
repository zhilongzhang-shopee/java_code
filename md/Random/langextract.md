# LangExtract 智能分块、结果合并与去重、多遍提取、文本溯源与映射功能分析

## 概述

LangExtract 是一个强大的文本信息提取框架，具备智能分块、多遍提取、结果合并去重和精确的文本溯源映射功能。本文档深入分析这些核心功能的实现原理和关键代码。

## 1. 核心数据结构

### 1.1 Document类 - 文档表示

```python
@dataclasses.dataclass
class Document:
    """文档类，用于标注文档
    
    Attributes:
        text: 文档的原始文本表示
        document_id: 每个文档的唯一标识符，如果未设置则自动生成
        additional_context: 用于补充提示指令的额外上下文
        tokenized_text: 文档的分词文本，从 `text` 计算得出
    """
    
    text: str
    additional_context: str | None = None
    _document_id: str | None = dataclasses.field(
        default=None, init=False, repr=False, compare=False
    )
    _tokenized_text: tokenizer.TokenizedText | None = dataclasses.field(
        init=False, default=None, repr=False, compare=False
    )
    
    @property
    def document_id(self) -> str:
        """返回文档ID，如果未设置则生成唯一ID"""
        if self._document_id is None:
            self._document_id = f"doc_{uuid.uuid4().hex[:8]}"
        return self._document_id
        
    @property
    def tokenized_text(self) -> tokenizer.TokenizedText:
        # 懒加载分词结果，提高性能
        if self._tokenized_text is None:
            self._tokenized_text = tokenizer.tokenize(self.text)
        return self._tokenized_text
```

### 1.2 Token类 - 词元表示

```python
@dataclasses.dataclass
class Token:
    """表示从文本中提取的词元
    
    每个词元都被分配一个索引并分类为类型（单词、数字、标点符号或缩写）。
    词元还记录对应于原始文本子串的字符范围（CharInterval）。
    
    Attributes:
        index: 词元在词元序列中的位置
        token_type: 词元的类型，由TokenType定义
        char_interval: 此词元在原始文本中跨越的字符区间
        first_token_after_newline: 如果词元紧跟在换行符或回车符后，则为True
    """
    
    index: int
    token_type: TokenType  # WORD, NUMBER, PUNCTUATION, ACRONYM
    char_interval: CharInterval = dataclasses.field(
        default_factory=lambda: CharInterval(0, 0)
    )
    first_token_after_newline: bool = False
```

### 1.3 TokenInterval类 - 词元区间

```python
@dataclasses.dataclass
class TokenInterval:
    """表示分词文本中的词元区间
    
    区间由起始索引（包含）和结束索引（不包含）定义
    
    Attributes:
        start_index: 区间中第一个词元的索引
        end_index: 区间中最后一个词元之后的索引
    """
    
    start_index: int = 0
    end_index: int = 0
```

### 1.4 CharInterval类 - 字符区间

```python
@dataclasses.dataclass
class CharInterval:
    """表示字符区间的类
    
    Attributes:
        start_pos: 区间的起始位置（包含）
        end_pos: 区间的结束位置（不包含）
    """
    
    start_pos: int | None = None
    end_pos: int | None = None
```

## 2. 智能分块功能

### 2.1 分词算法

LangExtract使用基于正则表达式的分词器，支持多种词元类型：

```python
# 分词的正则模式
_LETTERS_PATTERN = r"[A-Za-z]+"           # 字母模式
_DIGITS_PATTERN = r"[0-9]+"               # 数字模式  
_SYMBOLS_PATTERN = r"[^A-Za-z0-9\s]+"     # 符号模式
_SLASH_ABBREV_PATTERN = r"[A-Za-z0-9]+(?:/[A-Za-z0-9]+)+"  # 斜杠缩写模式

_TOKEN_PATTERN = re.compile(
    rf"{_SLASH_ABBREV_PATTERN}|{_LETTERS_PATTERN}|{_DIGITS_PATTERN}|{_SYMBOLS_PATTERN}"
)

@debug_utils.debug_log_calls
def tokenize(text: str) -> TokenizedText:
    """将文本分割为词元（单词、数字或标点符号）
    
    每个词元都用其字符位置和类型（WORD或PUNCTUATION）进行标注。
    如果词元前的间隙中有换行符或回车符，该词元的 `first_token_after_newline` 设为True。
    """
    tokenized = TokenizedText(text=text)
    previous_end = 0
    
    for token_index, match in enumerate(_TOKEN_PATTERN.finditer(text)):
        start_pos, end_pos = match.span()
        matched_text = match.group()
        
        # 创建新词元
        token = Token(
            index=token_index,
            char_interval=CharInterval(start_pos=start_pos, end_pos=end_pos),
            token_type=TokenType.WORD,
            first_token_after_newline=False,
        )
        
        # 检查此词元前的间隙中是否有换行符
        if token_index > 0:
            gap = text[previous_end:start_pos]
            if "\n" in gap or "\r" in gap:
                token.first_token_after_newline = True
                
        # 分类词元类型
        if re.fullmatch(_DIGITS_PATTERN, matched_text):
            token.token_type = TokenType.NUMBER
        elif re.fullmatch(_SLASH_ABBREV_PATTERN, matched_text):
            token.token_type = TokenType.ACRONYM
        elif _WORD_PATTERN.fullmatch(matched_text):
            token.token_type = TokenType.WORD
        else:
            token.token_type = TokenType.PUNCTUATION
            
        tokenized.tokens.append(token)
        previous_end = end_pos
        
    return tokenized
```

### 2.2 智能分块算法

```python
class ChunkIterator:
    """遍历分词文本的块
    
    块可能由句子或句子片段组成，能够适应我们可以运行推理的最大字符缓冲区。
    
    智能分块策略：
    A) 如果句子长度超过最大字符缓冲区，需要将其分解为适合最大字符缓冲区的块
    B) 如果单个词元超过最大字符缓冲区，它将构成整个块  
    C) 如果多个完整句子可以适合最大字符缓冲区内，则用它们形成块
    """
    
    def __init__(
        self,
        text: str | tokenizer.TokenizedText,
        max_char_buffer: int,
        document: data.Document | None = None,
    ):
        """构造函数
        
        Args:
            text: 要分块的文档，可以是字符串或分词文本
            max_char_buffer: 可以运行推理的缓冲区大小
            document: 可选的源文档
        """
        if isinstance(text, str):
            text = tokenizer.TokenizedText(text=text)
        self.tokenized_text = text
        self.max_char_buffer = max_char_buffer
        self.sentence_iter = SentenceIterator(self.tokenized_text)
        self.broken_sentence = False
        
        if document is None:
            self.document = data.Document(text=text.text)
        else:
            self.document = document
    
    def _tokens_exceed_buffer(
        self, token_interval: tokenizer.TokenInterval
    ) -> bool:
        """检查词元区间是否超过最大缓冲区大小"""
        char_interval = get_char_interval(self.tokenized_text, token_interval)
        return (
            char_interval.end_pos - char_interval.start_pos
        ) > self.max_char_buffer
    
    def __next__(self) -> TextChunk:
        """智能分块的核心逻辑"""
        sentence = next(self.sentence_iter)
        
        # 如果下一个词元大于max_char_buffer，让它成为整个块
        curr_chunk = create_token_interval(
            sentence.start_index, sentence.start_index + 1
        )
        if self._tokens_exceed_buffer(curr_chunk):
            self.sentence_iter = SentenceIterator(
                self.tokenized_text, curr_token_pos=sentence.start_index + 1
            )
            self.broken_sentence = curr_chunk.end_index < sentence.end_index
            return TextChunk(
                token_interval=curr_chunk,
                document=self.document,
            )
        
        # 将词元追加到块中，直到达到max_char_buffer
        start_of_new_line = -1
        for token_index in range(curr_chunk.start_index, sentence.end_index):
            if self.tokenized_text.tokens[token_index].first_token_after_newline:
                start_of_new_line = token_index
            test_chunk = create_token_interval(
                curr_chunk.start_index, token_index + 1
            )
            if self._tokens_exceed_buffer(test_chunk):
                # 只在换行处中断：1) 换行存在(> 0) 且 2) 在块开始之后（防止空区间）
                if start_of_new_line > 0 and start_of_new_line > curr_chunk.start_index:
                    # 在最近换行的开始处终止curr_chunk
                    curr_chunk = create_token_interval(
                        curr_chunk.start_index, start_of_new_line
                    )
                self.sentence_iter = SentenceIterator(
                    self.tokenized_text, curr_token_pos=curr_chunk.end_index
                )
                self.broken_sentence = True
                return TextChunk(
                    token_interval=curr_chunk,
                    document=self.document,
                )
            else:
                curr_chunk = test_chunk
        
        # 尝试合并更多完整句子
        if not self.broken_sentence:
            for sentence in self.sentence_iter:
                test_chunk = create_token_interval(
                    curr_chunk.start_index, sentence.end_index
                )
                if self._tokens_exceed_buffer(test_chunk):
                    self.sentence_iter = SentenceIterator(
                        self.tokenized_text, curr_token_pos=curr_chunk.end_index
                    )
                    return TextChunk(
                        token_interval=curr_chunk,
                        document=self.document,
                    )
                else:
                    curr_chunk = test_chunk
        
        return TextChunk(
            token_interval=curr_chunk,
            document=self.document,
        )
```

### 2.3 分块示例

**示例1: 基本分块**
```text
输入: "自然语言处理是人工智能的重要分支。它涉及计算机理解和生成人类语言的能力。深度学习技术在NLP领域取得了显著进展。"
最大字符缓冲区: 50
输出:
# 块1: '自然语言处理是人工智能的重要分支。' (长度: 17) # 完整句子
# 块2: '它涉及计算机理解和生成人类语言的能力。' (长度: 17) # 完整句子  
# 块3: '深度学习技术在NLP领域取得了显著进展。' (长度: 18) # 完整句子
```

**示例2: 换行符处理**
```text
输入: "人工智能技术发展迅速，\n包括机器学习、深度学习等多个方向，\n在各个领域都有广泛应用。"
最大字符缓冲区: 30
输出:
# 块1: '人工智能技术发展迅速，' (长度: 11) # 在换行符处分块
# 块2: '包括机器学习、深度学习等多个方向，' (长度: 16) # 在换行符处分块
# 块3: '在各个领域都有广泛应用。' (长度: 12) # 剩余部分
```

**示例3: 超长词元处理**
```text
输入: "This is antidisestablishmentarianism in a sentence."
最大字符缓冲区: 20
输出:
# 块1: 'This is' (长度: 7) # 正常分块
# 块2: 'antidisestablishmentarianism' (长度: 28) # 超长词元单独成块
# 块3: 'in a sentence.' (长度: 14) # 剩余部分
```

**示例4: 句子合并**
```text
输入: "红色。蓝色。绿色。黄色。紫色。"
最大字符缓冲区: 15
输出:
# 块1: '红色。蓝色。绿色。' (长度: 9) # 多个短句合并
# 块2: '黄色。紫色。' (长度: 6) # 剩余短句合并
```

## 3. 多遍提取与结果合并去重

### 3.1 多遍提取策略

```python
def _annotate_documents_sequential_passes(
    self,
    documents: Iterable[data.Document],
    resolver: resolver_lib.AbstractResolver,
    max_char_buffer: int,
    batch_length: int,
    debug: bool,
    extraction_passes: int,  # 提取遍数
    show_progress: bool = True,
    **kwargs,
) -> Iterator[data.AnnotatedDocument]:
    """用于提高召回率的顺序提取遍数逻辑"""
    
    logging.info(
        "开始顺序提取遍数以提高召回率，共 %d 遍",
        extraction_passes,
    )
    
    document_list = list(documents)
    
    # 按遍数存储文档提取结果
    document_extractions_by_pass: dict[str, list[list[data.Extraction]]] = {}
    document_texts: dict[str, str] = {}
    
    # 执行多遍提取
    for pass_num in range(extraction_passes):
        logging.info(
            "开始第 %d 遍提取，共 %d 遍", pass_num + 1, extraction_passes
        )
        
        # 每一遍都对相同文档进行独立提取
        for annotated_doc in self._annotate_documents_single_pass(
            document_list,
            resolver,
            max_char_buffer,
            batch_length,
            debug=(debug and pass_num == 0),  # 只在第一遍显示调试信息
            show_progress=show_progress if pass_num == 0 else False,
            **kwargs,
        ):
            doc_id = annotated_doc.document_id
            
            if doc_id not in document_extractions_by_pass:
                document_extractions_by_pass[doc_id] = []
                document_texts[doc_id] = annotated_doc.text or ""
            
            # 存储每一遍的提取结果
            document_extractions_by_pass[doc_id].append(
                annotated_doc.extractions or []
            )
    
    # 合并所有遍数的结果
    for doc_id, all_pass_extractions in document_extractions_by_pass.items():
        merged_extractions = _merge_non_overlapping_extractions(
            all_pass_extractions
        )
        
        if debug:
            total_extractions = sum(
                len(extractions) for extractions in all_pass_extractions
            )
            logging.info(
                "文档 %s: 从 %d 遍中合并了 %d 个提取结果为 %d 个非重叠提取结果",
                doc_id,
                total_extractions,
                extraction_passes,
                len(merged_extractions),
            )
        
        yield data.AnnotatedDocument(
            document_id=doc_id,
            extractions=merged_extractions,
            text=document_texts[doc_id],
        )
```

### 3.2 结果合并与去重算法

```python
def _merge_non_overlapping_extractions(
    all_extractions: list[Iterable[data.Extraction]],
) -> list[data.Extraction]:
    """合并多个提取遍数的提取结果
    
    当不同遍数的提取结果在字符位置上重叠时，保留较早遍数的提取结果
    （第一遍优先策略）。只有来自后续遍数的非重叠提取结果才会添加到结果中。
    
    Args:
        all_extractions: 来自不同顺序提取遍数的提取结果列表，按遍数排序
        
    Returns:
        合并的提取结果列表，重叠部分已解决，优先选择较早的遍数
    """
    if not all_extractions:
        return []
    
    if len(all_extractions) == 1:
        return list(all_extractions[0])
    
    # 从第一遍的结果开始
    merged_extractions = list(all_extractions[0])
    
    # 逐个处理后续遍数的结果
    for pass_extractions in all_extractions[1:]:
        for extraction in pass_extractions:
            overlaps = False
            if extraction.char_interval is not None:
                # 检查与已有提取结果是否重叠
                for existing_extraction in merged_extractions:
                    if existing_extraction.char_interval is not None:
                        if _extractions_overlap(extraction, existing_extraction):
                            overlaps = True
                            break
            
            # 只添加非重叠的提取结果
            if not overlaps:
                merged_extractions.append(extraction)
    
    return merged_extractions


def _extractions_overlap(
    extraction1: data.Extraction, extraction2: data.Extraction
) -> bool:
    """基于字符区间检查两个提取结果是否重叠"""
    if extraction1.char_interval is None or extraction2.char_interval is None:
        return False
    
    start1, end1 = (
        extraction1.char_interval.start_pos,
        extraction1.char_interval.end_pos,
    )
    start2, end2 = (
        extraction2.char_interval.start_pos,
        extraction2.char_interval.end_pos,
    )
    
    if start1 is None or end1 is None or start2 is None or end2 is None:
        return False
    
    # 如果一个区间在另一个结束之前开始，则两个区间重叠
    return start1 < end2 and start2 < end1
```

### 3.3 多遍提取示例

**3遍提取合并示例**
```text
源文本: "张三是一名医生，在北京医院工作。李四也是医生，在上海医院工作。"

第1遍提取结果:
# person: '张三' (位置: [0,2))
# profession: '医生' (位置: [6,8))  
# organization: '北京医院' (位置: [10,14))

第2遍提取结果:
# person: '李四' (位置: [17,19))
# profession: '医生' (位置: [21,23)) # 与第1遍重叠
# organization: '上海医院' (位置: [25,29))

第3遍提取结果:
# person: '张三' (位置: [0,2)) # 与第1遍重叠
# person: '李四' (位置: [17,19)) # 与第2遍重叠
# organization: '北京医院' (位置: [10,14)) # 与第1遍重叠
# organization: '上海医院' (位置: [25,29)) # 与第2遍重叠

合并后的最终结果 (第一遍优先策略):
# person: '张三' (位置: [0,2)) # 来自第1遍
# profession: '医生' (位置: [6,8)) # 来自第1遍，后续重叠被去除
# organization: '北京医院' (位置: [10,14)) # 来自第1遍
# person: '李四' (位置: [17,19)) # 来自第2遍
# organization: '上海医院' (位置: [25,29)) # 来自第2遍

去重统计: 原始提取8个 -> 合并后5个 (去重率: 37.5%)
```

## 4. 文本溯源与映射功能

### 4.1 精确对齐算法

```python
class WordAligner:
    """使用Python的difflib在两个词元序列之间对齐单词"""
    
    def __init__(self):
        """使用difflib SequenceMatcher初始化WordAligner"""
        self.matcher = difflib.SequenceMatcher(autojunk=False)
        self.source_tokens: Sequence[str] | None = None
        self.extraction_tokens: Sequence[str] | None = None
    
    def align_extractions(
        self,
        extraction_groups: Sequence[Sequence[data.Extraction]],
        source_text: str,
        token_offset: int = 0,
        char_offset: int = 0,
        delim: str = "\u241F",  # Unicode单位分隔符
        enable_fuzzy_alignment: bool = True,
        fuzzy_alignment_threshold: float = 0.75,
        accept_match_lesser: bool = True,
    ) -> Sequence[Sequence[data.Extraction]]:
        """将提取结果与其在源文本中的位置对齐
        
        此方法采用提取结果序列和源文本，将每个提取结果与其在源文本中
        的对应位置对齐。返回提取结果序列以及指示每个提取结果在源文本
        中开始和结束位置的词元区间。
        """
        logging.debug(
            "WordAligner: 开始将提取结果与源文本对齐"
        )
        
        tokenized_text = tokenizer.tokenize(source_text)
        source_tokens = [
            source_text[token.char_interval.start_pos:token.char_interval.end_pos]
            for token in tokenized_text.tokens
        ]
        
        aligned_extraction_groups = []
        
        for extraction_group in extraction_groups:
            aligned_extractions = []
            unmatched_extractions = []
            
            # 构建提取结果的词元序列用于匹配
            extraction_tokens_list = []
            for extraction in extraction_group:
                extraction_tokens = list(
                    _tokenize_with_lowercase(extraction.extraction_text)
                )
                extraction_tokens_list.extend(extraction_tokens)
                extraction_tokens_list.append(delim)  # 分隔符
            
            if extraction_tokens_list:
                extraction_tokens_list.pop()  # 移除最后的分隔符
            
            if not extraction_tokens_list:
                aligned_extraction_groups.append(extraction_group)
                continue
            
            # 设置序列进行对齐
            self._set_seqs(source_tokens, extraction_tokens_list)
            matching_blocks = self._get_matching_blocks()
            
            # 处理匹配块进行精确对齐
            extraction_index = 0
            extraction_token_offset = 0
            
            for match in matching_blocks:
                if match[2] == 0:  # 跳过虚拟匹配块
                    continue
                
                source_start, extraction_start, match_length = match
                
                # 找到对应的提取结果
                while (extraction_index < len(extraction_group) and 
                       extraction_token_offset + len(list(
                           _tokenize_with_lowercase(
                               extraction_group[extraction_index].extraction_text
                           )
                       )) <= extraction_start):
                    extraction_token_offset += len(list(
                        _tokenize_with_lowercase(
                            extraction_group[extraction_index].extraction_text
                        )
                    )) + 1  # +1 为分隔符
                    extraction_index += 1
                
                if extraction_index < len(extraction_group):
                    extraction = extraction_group[extraction_index]
                    extraction_tokens = list(
                        _tokenize_with_lowercase(extraction.extraction_text)
                    )
                    
                    # 设置词元区间
                    extraction.token_interval = tokenizer.TokenInterval(
                        start_index=source_start + token_offset,
                        end_index=source_start + len(extraction_tokens) + token_offset,
                    )
                    
                    # 设置字符区间
                    start_token = tokenized_text.tokens[source_start]
                    end_token = tokenized_text.tokens[
                        source_start + len(extraction_tokens) - 1
                    ]
                    extraction.char_interval = data.CharInterval(
                        start_pos=char_offset + start_token.char_interval.start_pos,
                        end_pos=char_offset + end_token.char_interval.end_pos,
                    )
                    
                    extraction.alignment_status = data.AlignmentStatus.MATCH_EXACT
                    aligned_extractions.append(extraction)
                    
                    extraction_index += 1
                else:
                    unmatched_extractions.extend(extraction_group[extraction_index:])
                    break
            
            # 对未匹配的提取结果进行模糊对齐
            if enable_fuzzy_alignment and unmatched_extractions:
                for extraction in unmatched_extractions:
                    fuzzy_aligned = self._fuzzy_align_extraction(
                        extraction,
                        source_tokens,
                        tokenized_text,
                        token_offset,
                        char_offset,
                        fuzzy_alignment_threshold,
                    )
                    if fuzzy_aligned:
                        aligned_extractions.append(fuzzy_aligned)
                    else:
                        aligned_extractions.append(extraction)
            else:
                aligned_extractions.extend(unmatched_extractions)
            
            aligned_extraction_groups.append(aligned_extractions)
        
        return aligned_extraction_groups
```

### 4.2 模糊对齐算法详解

模糊对齐算法是LangExtract文本溯源系统的核心组件，用于在精确匹配失败时，通过**滑动窗口 + 相似度计算**的方式找到提取结果在源文本中的最佳匹配位置。

#### 4.2.1 算法概述

模糊对齐算法采用**滑动窗口扫描 + difflib相似度计算**的策略，具有以下特点：
- 只在精确对齐失败时触发，作为后备方案
- 通过词元标准化处理复数、大小写等差异
- 使用高效的预检查机制避免不必要的计算
- 支持可配置的相似度阈值

#### 4.2.2 算法运行流程

**步骤1: 算法触发条件**

```text
精确对齐失败 → 启动模糊对齐
- 当difflib.SequenceMatcher无法找到精确匹配时
- 只对未成功对齐的提取结果进行模糊对齐
- 通常只影响总提取结果的小部分
```

**步骤2: 预处理阶段**
```python
# 词元提取和标准化
extraction_tokens = list(_tokenize_with_lowercase(extraction.extraction_text))
extraction_tokens_norm = [_normalize_token(t) for t in extraction_tokens]

# _normalize_token函数的标准化规则:
def _normalize_token(token: str) -> str:
    """小写化并应用轻度复数词干化"""
    token = token.lower()
    # 去除复数后缀 (长度>3 且以's'结尾但不以'ss'结尾)
    if len(token) > 3 and token.endswith("s") and not token.endswith("ss"):
        token = token[:-1]  # "books" → "book", "class" → "class"
    return token
```

**步骤3: 滑动窗口扫描**
```text
核心思想: 在源文本中尝试所有可能的窗口位置和大小

窗口大小范围: [提取词元数, 源文本词元总数]
窗口位置: 从左到右滑动，覆盖所有可能位置

示例:
源文本词元: ["苹果", "公司", "的", "iPhone", "手机", "很", "好"]
提取文本: "iPhone"
窗口大小: 从1到7
窗口位置: 每个大小下的所有可能起始位置
```

**步骤4: 性能优化：快速预检查**
```python
# 词元计数交集预检查
extraction_counts = collections.Counter(extraction_tokens_norm)
min_overlap = int(len_e * fuzzy_alignment_threshold)

# 只有当窗口中的重叠词元数量 >= 最小重叠要求时，才进行昂贵的序列匹配
if (extraction_counts & window_counts).total() >= min_overlap:
    # 执行详细的序列匹配
```

**步骤5: 相似度计算**
```python
# 使用difflib.SequenceMatcher计算匹配度
matcher.set_seq1(window_tokens_norm)
matches = sum(size for _, _, size in matcher.get_matching_blocks())
ratio = matches / len_e  # 匹配词元数 / 提取词元总数
```

**步骤6: 最佳匹配选择**
```python
if ratio > best_ratio:
    best_ratio = ratio
    best_span = (start_idx, window_size)

# 只接受超过阈值的匹配
if best_span and best_ratio >= fuzzy_alignment_threshold:
    # 设置对齐结果
```

#### 4.2.3 详细运行示例

```text
源文本: "苹果公司的iPhone手机和华为手机在市场上竞争激烈"
提取结果: "iPhone"
阈值: 0.6

步骤1: 预处理
- 提取词元: ["iPhone"] 
- 标准化: ["iphone"]
- 源词元: ["苹果", "公司", "的", "iPhone", "手机", "和", "华为", "手机", "在", "市场", "上", "竞争", "激烈"]
- 源标准化: ["苹果", "公司", "的", "iphone", "手机", "和", "华为", "手机", "在", "市场", "上", "竞争", "激烈"]

步骤2: 滑动窗口扫描
窗口大小=1:
- 位置0: ["苹果"] vs ["iphone"] → ratio=0.0
- 位置1: ["公司"] vs ["iphone"] → ratio=0.0  
- 位置2: ["的"] vs ["iphone"] → ratio=0.0
- 位置3: ["iphone"] vs ["iphone"] → ratio=1.0 ✓ (最佳匹配)
- ...继续扫描其他位置

窗口大小=2:
- 位置0: ["苹果", "公司"] vs ["iphone"] → ratio=0.0
- 位置1: ["公司", "的"] vs ["iphone"] → ratio=0.0
- 位置2: ["的", "iphone"] vs ["iphone"] → ratio=1.0 ✓
- 位置3: ["iphone", "手机"] vs ["iphone"] → ratio=1.0 ✓
- ...

步骤3: 最佳匹配选择
- 最高相似度: 1.0 (完美匹配)
- 最佳窗口: 位置3, 大小1 (对应源文本中的"iPhone")
- 超过阈值0.6 → 接受匹配

步骤4: 设置对齐结果
- token_interval: [3, 4)
- char_interval: [5, 11) (对应"iPhone"在源文本中的字符位置)
- alignment_status: MATCH_FUZZY
```

#### 4.2.4 性能优化策略

**1. 双端队列滑动窗口**
```python
# 使用collections.deque实现高效的窗口滑动
window_deque = collections.deque(source_tokens[0:window_size])

# 滑动时只需O(1)的添加和删除操作
old_token = window_deque.popleft()  # 移除左边
new_token = source_tokens[start_idx + window_size]
window_deque.append(new_token)      # 添加右边
```

**2. 词元计数缓存**
```python
# 维护滑动窗口的词元计数，避免重复计算
window_counts[old_token_norm] -= 1
if window_counts[old_token_norm] == 0:
    del window_counts[old_token_norm]
window_counts[new_token_norm] += 1
```

**3. LRU缓存优化**
```python
@functools.lru_cache(maxsize=10000)
def _normalize_token(token: str) -> str:
    # 词元标准化结果缓存，避免重复计算
```

**4. 早期剪枝**
```python
# 快速预检查：如果重叠词元数不足，直接跳过昂贵的序列匹配
if (extraction_counts & window_counts).total() < min_overlap:
    continue  # 跳过这个窗口
```

#### 4.2.5 算法特点与优势

**1. 智能容错**
- 处理复数形式差异："books" ↔ "book"
- 处理大小写差异："iPhone" ↔ "iphone"
- 处理部分匹配："深度学习算法" ↔ "深度学习"

**2. 性能高效**
- 时间复杂度：O(n×m×w) 其中n=源词元数，m=窗口数，w=平均窗口大小
- 空间复杂度：O(w) 其中w=最大窗口大小
- 实际性能：通过预检查大幅减少计算量

**3. 可配置阈值**
```python
# 默认阈值0.75，可根据需求调整
fuzzy_alignment_threshold = 0.75  # 75%的词元重叠度

# 阈值影响:
# - 高阈值(0.9): 严格匹配，精度高但召回率低
# - 低阈值(0.5): 宽松匹配，召回率高但可能误匹配
```

**4. 渐进式匹配**
```text
匹配策略优先级:
1. 精确匹配 (difflib) - 最高优先级
2. 部分精确匹配 (MATCH_LESSER) - 中等优先级  
3. 模糊匹配 (MATCH_FUZZY) - 最后手段
4. 无匹配 (None) - 对齐失败
```

#### 4.2.6 实际应用场景

**场景1：复数形式处理**
```text
源文本: "这些书籍很有用"
提取: "书籍"
标准化后匹配: "书籍" ↔ "书籍" → 精确匹配
```

**场景2：部分匹配**
```text
源文本: "深度学习技术发展迅速"
提取: "深度学习"
窗口匹配: "深度学习" ↔ "深度学习技术" → 模糊匹配 (ratio=1.0)
```

**场景3：词序差异**
```text
源文本: "人工智能与机器学习"
提取: "机器学习"
滑动窗口找到: "机器学习" → 成功匹配
```

#### 4.2.7 算法参数调优

```python
# 关键参数说明
fuzzy_alignment_threshold: float = 0.75  # 匹配阈值
enable_fuzzy_alignment: bool = True      # 是否启用模糊对齐
accept_match_lesser: bool = True         # 是否接受部分匹配

# 调优建议:
# - 高精度需求: threshold=0.9, accept_match_lesser=False
# - 高召回需求: threshold=0.6, accept_match_lesser=True  
# - 性能优先: enable_fuzzy_alignment=False (仅精确匹配)
```

#### 4.2.8 核心代码实现

```python
def _fuzzy_align_extraction(
    self,
    extraction: data.Extraction,
    source_tokens: list[str],
    tokenized_text: tokenizer.TokenizedText,
    token_offset: int,
    char_offset: int,
    fuzzy_alignment_threshold: float = 0.75,
) -> data.Extraction | None:
    """使用difflib.SequenceMatcher对词元进行模糊对齐提取结果
    
    算法扫描 `source_tokens` 中的每个候选窗口，选择具有最高
    SequenceMatcher `ratio` 的窗口。它使用高效的词元计数交集作为
    快速预检查，以丢弃无法满足对齐阈值的窗口。
    """
    
    extraction_tokens = list(
        _tokenize_with_lowercase(extraction.extraction_text)
    )
    # 使用轻度词干化的词元，这样复数形式不会阻碍对齐
    extraction_tokens_norm = [_normalize_token(t) for t in extraction_tokens]
    
    if not extraction_tokens:
        return None
    
    logging.debug(
        "模糊对齐 %r (%d 个词元)",
        extraction.extraction_text,
        len(extraction_tokens),
    )
    
    best_ratio = 0.0
    best_span: tuple[int, int] | None = None  # (start_idx, window_size)
    
    len_e = len(extraction_tokens)
    max_window = len(source_tokens)
    
    extraction_counts = collections.Counter(extraction_tokens_norm)
    min_overlap = int(len_e * fuzzy_alignment_threshold)
    
    matcher = difflib.SequenceMatcher(autojunk=False, b=extraction_tokens_norm)
    
    # 滑动窗口算法
    for window_size in range(len_e, max_window + 1):
        if window_size > len(source_tokens):
            break
        
        # 初始化滑动窗口
        window_deque = collections.deque(source_tokens[0:window_size])
        window_counts = collections.Counter(
            [_normalize_token(t) for t in window_deque]
        )
        
        for start_idx in range(len(source_tokens) - window_size + 1):
            # 优化：在昂贵的序列匹配之前检查是否存在足够的重叠词元
            # 这是匹配计数的上界
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
            
            # 将窗口向右滑动
            if start_idx + window_size < len(source_tokens):
                # 从计数中移除最左边的词元
                old_token = window_deque.popleft()
                old_token_norm = _normalize_token(old_token)
                window_counts[old_token_norm] -= 1
                if window_counts[old_token_norm] == 0:
                    del window_counts[old_token_norm]
                
                # 将新的最右边的词元添加到双端队列和计数中
                new_token = source_tokens[start_idx + window_size]
                window_deque.append(new_token)
                new_token_norm = _normalize_token(new_token)
                window_counts[new_token_norm] += 1
    
    # 如果找到满足阈值的最佳匹配
    if best_span and best_ratio >= fuzzy_alignment_threshold:
        start_idx, window_size = best_span
        
        try:
            # 设置词元区间
            extraction.token_interval = tokenizer.TokenInterval(
                start_index=start_idx + token_offset,
                end_index=start_idx + window_size + token_offset,
            )
            
            # 设置字符区间
            start_token = tokenized_text.tokens[start_idx]
            end_token = tokenized_text.tokens[start_idx + window_size - 1]
            extraction.char_interval = data.CharInterval(
                start_pos=char_offset + start_token.char_interval.start_pos,
                end_pos=char_offset + end_token.char_interval.end_pos,
            )
            
            extraction.alignment_status = data.AlignmentStatus.MATCH_FUZZY
            return extraction
        except IndexError:
            logging.exception(
                "模糊对齐期间设置区间时出现索引错误"
            )
            return None
    
    return None
```

这个模糊对齐算法通过巧妙的滑动窗口设计和多层优化，在保持高性能的同时实现了智能的文本溯源，是LangExtract系统中处理复杂文本对齐场景的核心技术。

## 5. LLM文本映射回原始文档的完整流程

### 5.1 核心问题解答

**LangExtract如何保证大模型给出的结果一定出现在原始文本中？**

答案是：**LangExtract并不保证LLM的输出一定出现在原始文本中，但它通过强大的对齐机制来尽最大努力找到匹配位置，并明确标记对齐状态**。

### 5.2 完整映射流程

#### 5.2.1 第一阶段：文档预处理与分块

```text
原始文档 → 分词处理 → 智能分块 → 发送给LLM

1. Document对象创建
   - 原始文本存储
   - 自动生成document_id
   - 延迟分词（lazy tokenization）

2. 智能分块处理
   - 按max_char_buffer分割
   - 保持句子完整性
   - 记录每个chunk的token_interval和char_interval

3. LLM推理
   - 每个chunk独立处理
   - 获得结构化提取结果（JSON/YAML）
```

#### 5.2.2 第二阶段：LLM输出解析

```python
# resolver.resolve() 处理LLM原始输出
def resolve(self, input_text: str) -> Sequence[data.Extraction]:
    """将LLM的原始文本输出解析为结构化数据"""
    
    # 1. 解析JSON/YAML格式
    extraction_data = self.format_handler.parse_output(input_text)
    
    # 2. 转换为Extraction对象
    processed_extractions = self.extract_ordered_extractions(extraction_data)
    
    # 此时的Extraction对象只包含:
    # - extraction_text: LLM提取的文本
    # - extraction_class: 实体类别
    # - 但没有位置信息 (token_interval, char_interval = None)
    
    return processed_extractions
```

#### 5.2.3 第三阶段：关键的对齐映射过程

这是确保提取结果映射到原始文本的**核心步骤**：

```python
# resolver.align() - 核心映射函数
def align(self, extractions, source_text, token_offset, char_offset):
    """将提取结果对齐到源文本的具体位置"""
    
    # 使用WordAligner进行对齐
    aligner = WordAligner()
    aligned_extractions = aligner.align_extractions(
        extractions, source_text, token_offset, char_offset
    )
    
    return aligned_extractions
```

**对齐过程的三个层次：**

##### 1. **精确对齐（MATCH_EXACT）**
```python
# 使用difflib.SequenceMatcher进行词元级精确匹配
for i, j, n in self._get_matching_blocks():
    extraction.token_interval = TokenInterval(
        start_index=i + token_offset,
        end_index=i + n + token_offset,
    )
    
    # 将token位置转换为字符位置
    start_token = tokenized_text.tokens[i]
    end_token = tokenized_text.tokens[i + n - 1]
    extraction.char_interval = CharInterval(
        start_pos=char_offset + start_token.char_interval.start_pos,
        end_pos=char_offset + end_token.char_interval.end_pos,
    )
    
    extraction.alignment_status = AlignmentStatus.MATCH_EXACT
```

##### 2. **部分匹配（MATCH_LESSER）**
```python
# 当提取文本比匹配的源文本更长时
if extraction_text_len > n:
    if accept_match_lesser:
        extraction.alignment_status = AlignmentStatus.MATCH_LESSER
    else:
        # 拒绝部分匹配，重置位置信息
        extraction.token_interval = None
        extraction.char_interval = None
```

##### 3. **模糊对齐（MATCH_FUZZY）**
```python
# 对未成功精确匹配的提取结果使用模糊对齐
if enable_fuzzy_alignment and unaligned_extractions:
    for extraction in unaligned_extractions:
        aligned_extraction = self._fuzzy_align_extraction(
            extraction, source_tokens, tokenized_text,
            token_offset, char_offset, fuzzy_alignment_threshold
        )
```

### 5.3 位置验证与保障机制

#### 5.3.1 严格的位置验证

```python
# 验证char_interval是否对应正确的文本
def assert_char_interval_match_source(source_text, extractions):
    for extraction in extractions:
        if extraction.alignment_status == AlignmentStatus.MATCH_EXACT:
            char_int = extraction.char_interval
            start = char_int.start_pos
            end = char_int.end_pos
            
            # 从源文本中提取对应位置的文本
            extracted = source_text[start:end]
            
            # 验证是否匹配（忽略大小写）
            assert extracted.lower() == extraction.extraction_text.lower()
```

#### 5.3.2 边界检查

```python
# 确保位置索引在有效范围内
def assert_valid_char_intervals(result):
    for extraction in result.extractions:
        if hasattr(result, "text") and result.text:
            text_length = len(result.text)
            assert extraction.char_interval.start_pos >= 0
            assert extraction.char_interval.end_pos <= text_length
```

#### 5.3.3 Token与字符位置的双重映射

```python
# 通过token位置重建文本，确保一致性
def tokens_text(tokenized_text, token_interval):
    """从token区间重建原始文本子串"""
    start_token = tokenized_text.tokens[token_interval.start_index]
    end_token = tokenized_text.tokens[token_interval.end_index - 1]
    
    return tokenized_text.text[
        start_token.char_interval.start_pos : end_token.char_interval.end_pos
    ]
```

### 5.4 对齐状态系统

LangExtract通过明确的对齐状态来标记每个提取结果的可靠性：

```python
class AlignmentStatus(enum.Enum):
    MATCH_EXACT = "match_exact"      # 完美匹配
    MATCH_LESSER = "match_lesser"    # 部分匹配
    MATCH_FUZZY = "match_fuzzy"      # 模糊匹配
    # None = 无法对齐
```

### 5.5 关键保障机制

#### 5.5.1 不保证100%存在，但提供透明度

```text
LangExtract的设计理念：
- 不强制要求LLM输出必须在原文中存在
- 但通过对齐算法尽力找到匹配位置
- 通过alignment_status明确标记匹配质量
- 允许用户根据对齐状态过滤结果
```

#### 5.5.2 多层次容错机制

```text
对齐策略的优先级：
1. 精确匹配 → 最高可信度
2. 部分匹配 → 中等可信度
3. 模糊匹配 → 较低可信度，但仍有价值
4. 无匹配 → 明确标记为未对齐
```

#### 5.5.3 可配置的严格程度

```python
# 用户可以控制对齐的严格程度
resolver.align(
    extractions,
    source_text,
    enable_fuzzy_alignment=True,        # 是否启用模糊对齐
    fuzzy_alignment_threshold=0.75,     # 模糊匹配阈值
    accept_match_lesser=True,           # 是否接受部分匹配
)
```

### 5.6 实际运行示例

```text
输入文档: "苹果公司发布了新的iPhone 15系列手机，配备了强大的A17芯片。"

1. LLM提取结果:
   - "苹果公司" (公司名)
   - "iPhone 15" (产品名)
   - "A17芯片" (技术组件)

2. 对齐过程:
   提取结果 "苹果公司":
   - 精确匹配成功
   - char_interval: [0, 4)
   - token_interval: [0, 2)
   - alignment_status: MATCH_EXACT

   提取结果 "iPhone 15":
   - 精确匹配成功
   - char_interval: [8, 17)
   - token_interval: [4, 6)
   - alignment_status: MATCH_EXACT

   提取结果 "A17芯片":
   - 精确匹配成功
   - char_interval: [27, 32)
   - token_interval: [11, 13)
   - alignment_status: MATCH_EXACT

3. 验证结果:
   - 原文[0:4] = "苹果公司" ✓
   - 原文[8:17] = "iPhone 15" ✓
   - 原文[27:32] = "A17芯片" ✓
```

### 5.7 核心流程代码实现

#### 5.7.1 完整的注释处理流程

```python
def _annotate_documents_single_pass(
    self,
    documents: Iterable[data.Document],
    resolver: resolver_lib.AbstractResolver,
    max_char_buffer: int,
    batch_length: int,
    debug: bool,
    show_progress: bool = True,
    **kwargs,
) -> Iterator[data.AnnotatedDocument]:
    """单轮文档注释处理的核心流程"""
    
    # 1. 文档分块处理
    chunk_iter = _document_chunk_iterator(documents, max_char_buffer)
    
    # 2. 批量处理文档块
    for batch in chunking.batch_iterator(chunk_iter, batch_length):
        # 3. 生成提示词
        batch_prompts = [
            self._prompt_generator.generate_prompt(chunk.chunk_text, **kwargs)
            for chunk in batch
        ]
        
        # 4. LLM推理
        batch_scored_outputs = list(
            self._language_model.infer(batch_prompts, **kwargs)
        )
        
        # 5. 处理每个chunk的结果
        for text_chunk, scored_outputs in zip(batch, batch_scored_outputs):
            # 获取最佳推理结果
            top_inference_result = scored_outputs[0].output
            
            # 6. 解析LLM输出为结构化数据
            annotated_chunk_extractions = resolver.resolve(
                top_inference_result, debug=debug, **kwargs
            )
            
            # 7. 关键步骤：对齐映射到原始文本位置
            chunk_text = text_chunk.chunk_text
            token_offset = text_chunk.token_interval.start_index
            char_offset = text_chunk.char_interval.start_pos
            
            aligned_extractions = resolver.align(
                annotated_chunk_extractions,  # LLM提取的结果
                chunk_text,                   # 源文本块
                token_offset,                 # token偏移量
                char_offset,                  # 字符偏移量
                **kwargs,
            )
            
            # 8. 收集对齐后的提取结果
            annotated_extractions.extend(aligned_extractions)
    
    # 9. 构建最终的注释文档
    return annotated_doc
```

#### 5.7.2 对齐验证的具体实现

```python
def align_extractions(
    self,
    extraction_groups: Sequence[Sequence[data.Extraction]],
    source_text: str,
    token_offset: int = 0,
    char_offset: int = 0,
    enable_fuzzy_alignment: bool = True,
    fuzzy_alignment_threshold: float = 0.75,
    accept_match_lesser: bool = True,
) -> Sequence[Sequence[data.Extraction]]:
    """对齐提取结果到源文本的具体位置"""
    
    # 1. 源文本分词处理
    tokenized_text = tokenizer.tokenize(source_text)
    source_tokens = [
        tokenized_text.text[token.char_interval.start_pos:token.char_interval.end_pos]
        for token in tokenized_text.tokens
    ]
    
    # 2. 提取结果分词处理
    extraction_tokens_list = []
    for extraction_group in extraction_groups:
        for extraction in extraction_group:
            extraction_tokens = list(
                _tokenize_with_lowercase(extraction.extraction_text)
            )
            extraction_tokens_list.append(extraction_tokens)
    
    # 3. 使用difflib进行序列匹配
    combined_extraction_tokens = [
        token for tokens in extraction_tokens_list for token in tokens
    ]
    
    self.matcher = difflib.SequenceMatcher(
        autojunk=False,
        a=source_tokens,
        b=combined_extraction_tokens,
    )
    
    # 4. 精确匹配阶段
    aligned_extractions = []
    for i, j, n in self._get_matching_blocks()[:-1]:
        extraction = self._get_extraction_by_index(j)
        if extraction is None:
            continue
            
        # 设置token区间
        extraction.token_interval = tokenizer.TokenInterval(
            start_index=i + token_offset,
            end_index=i + n + token_offset,
        )
        
        # 设置字符区间
        try:
            start_token = tokenized_text.tokens[i]
            end_token = tokenized_text.tokens[i + n - 1]
            extraction.char_interval = data.CharInterval(
                start_pos=char_offset + start_token.char_interval.start_pos,
                end_pos=char_offset + end_token.char_interval.end_pos,
            )
            
            # 验证匹配质量
            extraction_text_len = len(
                list(_tokenize_with_lowercase(extraction.extraction_text))
            )
            
            if extraction_text_len == n:
                extraction.alignment_status = data.AlignmentStatus.MATCH_EXACT
            elif extraction_text_len > n and accept_match_lesser:
                extraction.alignment_status = data.AlignmentStatus.MATCH_LESSER
            
            aligned_extractions.append(extraction)
            
        except IndexError as e:
            # 位置验证失败
            raise IndexError(
                f"Failed to align extraction with source text. "
                f"Token interval {extraction.token_interval} does not match "
                f"source text tokens {tokenized_text.tokens}."
            ) from e
    
    # 5. 模糊对齐阶段
    unaligned_extractions = self._get_unaligned_extractions(aligned_extractions)
    if enable_fuzzy_alignment and unaligned_extractions:
        for extraction in unaligned_extractions:
            fuzzy_aligned = self._fuzzy_align_extraction(
                extraction, source_tokens, tokenized_text,
                token_offset, char_offset, fuzzy_alignment_threshold
            )
            if fuzzy_aligned:
                aligned_extractions.append(fuzzy_aligned)
    
    return aligned_extractions
```

### 5.8 总结

**LangExtract的映射保障机制：**

1. **不是绝对保证**：系统不强制要求LLM输出必须在原文中存在
2. **尽力而为**：通过多层次对齐算法尽最大努力找到匹配位置
3. **透明标记**：明确标记每个提取结果的对齐状态和可信度
4. **严格验证**：对成功对齐的结果进行严格的位置验证
5. **用户控制**：允许用户根据对齐状态和需求进行结果过滤

这种设计平衡了**准确性**和**实用性**，既保持了对LLM创造性的开放态度，又通过技术手段最大程度地确保了结果的可追溯性和可靠性。

## 6. 完整示例

### 6.1 分词示例

**基本分词**
```text
输入: "张三博士在2023年发表了重要论文。"
输出:
# 词元0: '张三' (类型: WORD, 位置: [0,2))
# 词元1: '博士' (类型: WORD, 位置: [2,4))
# 词元2: '在' (类型: WORD, 位置: [4,5))
# 词元3: '2023' (类型: NUMBER, 位置: [5,9))
# 词元4: '年' (类型: WORD, 位置: [9,10))
# 词元5: '发表' (类型: WORD, 位置: [10,12))
# 词元6: '了' (类型: WORD, 位置: [12,13))
# 词元7: '重要' (类型: WORD, 位置: [13,15))
# 词元8: '论文' (类型: WORD, 位置: [15,17))
# 词元9: '。' (类型: PUNCTUATION, 位置: [17,18))
```

**包含换行符的分词**
```text
输入: "第一行\n第二行"
输出:
# 词元0: '第一行' (类型: WORD, 位置: [0,3), 换行标记: False)
# 词元1: '第二行' (类型: WORD, 位置: [4,7), 换行标记: True) # 前面有换行符
```

### 6.2 长文本分块示例

**长文本智能分块**
```text
输入: "人工智能是计算机科学的一个分支，它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器。该领域的研究包括机器人、语言识别、图像识别、自然语言处理和专家系统等。自从人工智能诞生以来，理论和技术日益成熟，应用领域也不断扩大。"
最大字符缓冲区: 80
输出:
# 块1: '人工智能是计算机科学的一个分支，它企图了解智能的实质，并生产出一种新的能以人类智能相似的方式做出反应的智能机器。' (长度: 56) # 完整句子
# 块2: '该领域的研究包括机器人、语言识别、图像识别、自然语言处理和专家系统等。' (长度: 33) # 完整句子
# 块3: '自从人工智能诞生以来，理论和技术日益成熟，应用领域也不断扩大。' (长度: 28) # 完整句子
```

### 6.3 溯源映射示例

**示例1: 精确对齐**
```text
源文本: "北京大学的张教授和清华大学的李教授合作研究人工智能。"
提取结果: ["北京大学", "张教授", "清华大学", "李教授", "人工智能"]
对齐结果:
# organization: '北京大学' -> '北京大学' (位置: [0,3), 状态: MATCH_EXACT)
# person: '张教授' -> '张教授' (位置: [4,7), 状态: MATCH_EXACT)
# organization: '清华大学' -> '清华大学' (位置: [8,11), 状态: MATCH_EXACT)
# person: '李教授' -> '李教授' (位置: [12,15), 状态: MATCH_EXACT)
# field: '人工智能' -> '人工智能' (位置: [18,22), 状态: MATCH_EXACT)
```

**示例2: 模糊对齐**
```text
源文本: "苹果公司的iPhone手机和华为手机在市场上竞争激烈。"
提取结果: ["苹果公司", "iPhone", "华为", "市场竞争"]
模糊对齐阈值: 0.6
对齐结果:
# company: '苹果公司' -> '苹果公司' (位置: [0,4), 状态: MATCH_EXACT)
# product: 'iPhone' -> 'iPhone手机' (位置: [5,11), 状态: MATCH_FUZZY, 相似度: 0.50)
# company: '华为' -> '华为手机' (位置: [12,16), 状态: MATCH_FUZZY, 相似度: 0.50)
# concept: '市场竞争' -> '市场上竞争' (位置: [17,22), 状态: MATCH_FUZZY, 相似度: 0.75)
```

**示例3: 对齐失败处理**
```text
源文本: "机器学习是人工智能的重要组成部分。"
提取结果: ["机器学习", "深度学习", "人工智能"]
对齐结果:
# technology: '机器学习' -> '机器学习' (位置: [0,4), 状态: MATCH_EXACT)
# technology: '深度学习' -> 对齐失败 (源文本中未找到)
# field: '人工智能' -> '人工智能' (位置: [5,9), 状态: MATCH_EXACT)
```

### 6.4 完整工作流程示例

**端到端提取工作流程**
```text
源文本: "北京大学是中国著名的综合性大学，成立于1898年。清华大学也是顶尖学府，以工科闻名。张教授在北京大学计算机系工作，专门研究人工智能和机器学习。李博士则在清华大学电子系，主要从事深度学习和神经网络的研究。两位学者经常合作，在国际会议上发表了多篇重要论文。"

步骤1: 文本分块
最大字符缓冲区: 80
分块结果:
# 块1: '北京大学是中国著名的综合性大学，成立于1898年。清华大学也是顶尖学府，以工科闻名。' (长度: 38)
# 块2: '张教授在北京大学计算机系工作，专门研究人工智能和机器学习。' (长度: 27)
# 块3: '李博士则在清华大学电子系，主要从事深度学习和神经网络的研究。' (长度: 28)
# 块4: '两位学者经常合作，在国际会议上发表了多篇重要论文。' (长度: 22)

步骤2: 多遍提取
第1遍: ["北京大学", "张教授", "人工智能", "机器学习"]
第2遍: ["清华大学", "李博士", "深度学习", "张教授"] # 重叠
第3遍: ["神经网络", "计算机系", "电子系", "人工智能"] # 重叠

步骤3: 对齐和溯源映射
第1遍对齐: 4个成功对齐
第2遍对齐: 3个成功对齐 (1个重叠)
第3遍对齐: 4个成功对齐 (1个重叠)

步骤4: 结果合并与去重
最终结果按类别分组:
DEPARTMENT: ['计算机系', '电子系']
FIELD: ['人工智能', '机器学习', '深度学习', '神经网络'] 
PERSON: ['张教授', '李博士']
UNIVERSITY: ['北京大学', '清华大学']

统计信息:
- 原始提取总数: 12个
- 合并后结果: 8个
- 去重率: 33.3%
```

## 7. 分词、分句、分块的联系与区别

### 7.1 概念定义

**分词（Tokenization）**
- **定义**: 将连续的文本分解为独立的词元（tokens）
- **粒度**: 最小语义单位（单词、数字、标点符号、缩写）
- **目的**: 为文本分析提供基础的原子单位

**分句（Sentence Segmentation）**  
- **定义**: 在词元序列的基础上识别句子边界
- **粒度**: 语义完整的句子单位
- **目的**: 识别具有完整语义的文本片段

**分块（Chunking）**
- **定义**: 将文本分割为适合模型处理的固定大小片段
- **粒度**: 基于字符长度限制的文本块
- **目的**: 适配模型的上下文窗口限制

### 7.2 层次关系与依赖

```text
原始文本
    ↓
分词（Tokenization）
    ↓
分句（Sentence Segmentation）
    ↓  
分块（Chunking）
    ↓
模型处理
```

**依赖关系**:
- 分句依赖分词结果（基于词元序列识别句子边界）
- 分块依赖分句结果（优先在句子边界处分割）
- 三者形成递进的层次结构

### 7.3 技术实现对比

**分词算法**
```text
输入: "张三博士在2023年发表论文。"
处理: 正则表达式匹配 + 词元分类
输出: 
# Token[0]: '张三' (WORD, [0,2))
# Token[1]: '博士' (WORD, [2,4))  
# Token[2]: '在' (WORD, [4,5))
# Token[3]: '2023' (NUMBER, [5,9))
# Token[4]: '年' (WORD, [9,10))
# Token[5]: '发表' (WORD, [10,12))
# Token[6]: '论文' (WORD, [12,14))
# Token[7]: '。' (PUNCTUATION, [14,15))
```

**分句算法**
```text
输入: 上述词元序列
处理: 句子边界检测规则
# 规则1: 句号、问号、感叹号（排除缩写如Dr.）
# 规则2: 换行符 + 大写字母开头
# 规则3: 避免在已知缩写处分割
输出: 
# Sentence[0]: TokenInterval(0, 8) # 完整句子
```

**分块算法**
```text
输入: 词元序列 + 句子边界信息
处理: 智能分块策略
# 策略1: 优先保持句子完整性
# 策略2: 超长句子在换行符处分割
# 策略3: 超长词元单独成块
# 策略4: 多个短句合并
输出:
# Chunk[0]: "张三博士在2023年发表论文。" (长度: 15, 完整句子)
```

### 7.4 关键区别对比

| 维度 | 分词 | 分句 | 分块 |
|------|------|------|------|
| **输入** | 原始文本字符串 | 词元序列 | 词元序列+句子边界 |
| **输出** | 词元序列 | 句子区间 | 文本块 |
| **依据** | 字符模式匹配 | 语法规则 | 长度约束 |
| **目标** | 语言单位识别 | 语义完整性 | 模型适配性 |
| **粒度** | 词/符号级别 | 句子级别 | 可变长度块 |
| **边界** | 词汇边界 | 语法边界 | 容量边界 |

### 7.5 协同工作机制

**示例：长文本处理流程**
```text
原始文本: "人工智能发展迅速。机器学习是其重要分支。深度学习技术不断突破。这些技术正在改变世界。"

步骤1 - 分词:
# [人工智能] [发展] [迅速] [。] [机器学习] [是] [其] [重要] [分支] [。] [深度学习] [技术] [不断] [突破] [。] [这些] [技术] [正在] [改变] [世界] [。]

步骤2 - 分句:
# Sentence1: [人工智能] [发展] [迅速] [。] (TokenInterval: 0-4)
# Sentence2: [机器学习] [是] [其] [重要] [分支] [。] (TokenInterval: 4-10)  
# Sentence3: [深度学习] [技术] [不断] [突破] [。] (TokenInterval: 10-15)
# Sentence4: [这些] [技术] [正在] [改变] [世界] [。] (TokenInterval: 15-21)

步骤3 - 分块 (假设max_char_buffer=30):
# Chunk1: "人工智能发展迅速。机器学习是其重要分支。" (长度: 21, 2个完整句子)
# Chunk2: "深度学习技术不断突破。这些技术正在改变世界。" (长度: 23, 2个完整句子)
```

### 7.6 处理策略的智能性

**分词的智能性**
- 词元类型自动识别（WORD/NUMBER/PUNCTUATION/ACRONYM）
- 换行符标记处理
- 特殊模式识别（如斜杠缩写 "TCP/IP"）

**分句的智能性**
- 缩写词排除（"Dr." 不是句子结束）
- 换行符启发式（换行+大写字母 = 句子边界）
- 容错性设计（宁可提前结束也不错过边界）

**分块的智能性**
- 句子完整性优先
- 换行符边界利用
- 超长内容特殊处理
- 多句合并优化

### 7.7 应用场景差异

**分词应用**
- 文本对齐和映射
- 特征提取
- 语言分析
- 搜索索引

**分句应用**  
- 语义理解
- 翻译系统
- 摘要生成
- 情感分析

**分块应用**
- 大模型输入预处理
- 并行处理优化
- 内存管理
- 批量推理

## 8. 总结

LangExtract的核心功能包括：

1. **智能分块**: 基于词元的智能分块算法，支持句子边界检测和换行符处理
2. **多遍提取**: 通过多次独立提取提高召回率，采用第一遍优先的合并策略
3. **结果去重**: 基于字符区间重叠检测的去重算法
4. **精确溯源**: 使用difflib的精确匹配和滑动窗口的模糊匹配算法
5. **文本映射**: 完整的词元级别和字符级别的位置映射

分词、分句、分块三个层次形成了完整的文本预处理流水线，每个层次都有其特定的作用和智能化处理策略。它们协同工作，为大规模文本信息提取提供了强大而可靠的解决方案。通过精确的文本溯源和映射，用户可以准确地定位提取结果在原文中的位置，为后续的分析和应用提供了坚实的基础。

