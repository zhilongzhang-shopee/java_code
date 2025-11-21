# CommonChatService & ChatBIService 详细流程分析

## 📌 快速概览

| 维度 | CommonChatService | ChatBIService |
|------|-------------------|---------------|
| **功能** | 通用聊天（支持多 Agent） | BI 数据可视化分析 |
| **行数** | 1074 | 656 |
| **流式支持** | ✅ 是 (SSE + Flux) | ✅ 是 (SSE + Flux) |
| **核心 API 端点** | `/router/stream` | `/chat_bi/stream` |
| **主要方法** | `commonChatInvoke()` | `textToBIChartV2()` |
| | `commonChatStreamSse()` | |
| **支持特性** | 多 Agent 路由 | 权限检查、数据下载 |
| **响应类型** | 文本 | 图表 + 数据 |

---

## 🎯 Part 1: CommonChatService (1074 行)

### 1.1 核心职责

**作用**: 通用聊天服务，支持多种 AI Agent，支持流式和非流式两种模式。

```
用户问题
  ↓
支持多个 Tool/Agent
├─ Text2SQL (数据查询)
├─ LogifyBot (日志查询)
├─ DashboardAgent (仪表盘)
└─ 其他 AI 服务
  ↓
通过 DiBrain Router 或 Dashboard API 调用
  ↓
支持两种模式:
├─ 非流式: commonChatInvoke() → 一次性返回
└─ 流式: commonChatStreamSse() → SSE 逐步推送
```

### 1.2 两个核心方法

#### 方法 1: commonChatInvoke() - 非流式模式

**位置**: Line 133-190

**流程** (8 步):

```java
// 1️⃣ 验证权限
SessionDetailDTO session = sessionService.getSession(requestVO.getSessionId());
sessionService.checkAuth(requestVO.getCommonInfo().getUser(), session);

// 2️⃣ 处理"再问一遍" (删除上一轮对话)
if (requestVO.isAskAgain()) {
    chatService.deleteLastTwoChatMessage(requestVO.getSessionId());
}

// 3️⃣ 获取聊天历史
List<ChatMessageTab> messageHistory = chatService.getCommonChatMessageHistory(sessionId);
List<Map<String, String>> history = toDiBrainChatHistory(messageHistory);

// 4️⃣ 提取特殊信息 (ThreadId, Dataset 检查)
String threadId = getThreadId(messageHistory);
checkDataset(requestVO, messageHistory);

// 5️⃣ 创建用户提问消息
ChatCreateRequestDTO questionDto = convertor
    .convertMessageVOToChatCreateDto(requestVO, requestRelation);
Long chatId = chatService.createChatMessage(questionDto);

// 6️⃣ 构建 AI 请求（超时保护）
Supplier<CommonResponse<...>> supplier = 
    () -> getCommonChatResult(...);
CommonResponse<CommonChatResponseVO> resp = 
    GlobalTimeOutHandler.executeTaskWithTimeout(
        supplier,
        assistantGlobalConfig.getCommonChatTimeout(),
        timeoutReturn());

// 7️⃣ 创建响应消息
chatCreateRequestDTO = convertor
    .convertMessageVOToChatCreateDto(
        resp.getResponseVO(),
        AgentUtils.buildDiAssistantCommonInfo(),
        sessionId,
        resp.getTraceId());
Long responseId = chatService.createChatMessage(chatCreateRequestDTO);

// 8️⃣ 返回结果
return resp.getResponseVO();
```

**关键方法: getCommonChatResult()**

**位置**: Line 192-310

```java
private CommonResponse<CommonChatResponseVO> getCommonChatResult(...) {
    
    // 第1部分: 构建 AI 请求
    // ======================
    
    // 1. 获取用户设置
    String idcRegion = userSettingDetailVO.getUserSetting()
        .getSqlExecuteIdcRegion();
    dataScope.setTableUidList(...)
    dataScope.setSqlDialect(...)
    
    // 2. 构建配置对象
    CommonConfigDTO configDTO = CommonConfigDTO.builder()
        .configurable(ConfigurableDTO.builder().llm(model).build())
        .metadata(CommonReqMetadataDTO.builder()
            .sqlDialect(dataScope.getSqlDialect())
            .supportSkipAuth(assistantGlobalConfig.isSupportSkipAuth())
            .build())
        .build();
    
    // 3. 获取用户信息 (RAM 权限管理)
    RamResponseDTO<RamUserInfo> ramUser = ramClient
        .getUserInfo(commonInfo.getUserEmail());
    RamUserInfo userInfo = ramUser.getData();
    
    // 4. 获取 Presto 队列
    RamResponseDTO<List<PrestoQueue>> queueResp = 
        ramClient.getUserProjectPrestoQueueList(
            userInfo.getDefaultProjectCode(), idcRegion);
    String prestoQueue = queueResp.getData().getFirst()
        .getQueueName();
    
    // 5. 构建资产列表 (AssetsEntity)
    List<AssetsEntity> assetsEntityList = new ArrayList<>();
    // 添加 Hive 表
    for (String table : dataScope.getTableUidList()) {
        assetsEntityList.add(
            AssetsEntity.builder()
                .id(table).name(table)
                .type(AssetsType.HIVE_TABLE.getType())
                .build());
    }
    // 添加 BI Topic
    for (ChatBITopicEntityVO topic : dataScope.getChatBITopicList()) {
        assetsEntityList.add(
            AssetsEntity.builder()
                .id(topic.getAssetsId()).name(topic.getName())
                .type(AssetsType.CHAT_BI_TOPIC.getType())
                .build());
    }
    // 添加数据集
    for (ChatDatasetInfo dataset : dataScope.getChatDatasetInfoList()) {
        assetsEntityList.add(
            AssetsEntity.builder()
                .id(dataset.getAssetsId())
                .type(AssetsType.CHAT_DATASET.getType())
                .toolCallId(dataset.getToolCallId())
                .idcRegion(dataset.getIdcRegion())
                .build());
    }
    
    // 6. 构建输入对象
    CommonChatInputDTO.CommonChatInputDTOBuilder inputBuilder =
        CommonChatInputDTO.builder()
            .chatContext(DiBrainUtils
                .buildChatContextWithCommonChat(...))
            .question(question)
            .sessionId(sessionId)
            .chatId(chatId)
            .logStoreId(logStoreId)
            .agentName(agentName)           // 多 Agent 支持
            .threadId(threadId)             // LogifyBot 专用
            .originalSql(originalSql)       // 原始 SQL
            .errorMessage(errorMessage)     // 错误信息
            .selectedAssets(assetsEntityList);  // 资产列表
    
    inputBuilder.chatHistory(history);
    
    // 第2部分: 调用 AI
    // ================
    
    try {
        CommonChatResponseDTO responseDTO = 
            diBrainClient.commonChat(req.build());
        
        // 第3部分: 处理响应
        // ==================
        
        // 使用 ChatProcessor 转换
        CommonChatResponseVO commonChatResponseVO = 
            chatProcessor.convertCommonChat(
                responseDTO.getOutput().getAskHuman(),
                dataScope,
                responseDTO.getOutput().getLlmRawResponse(),
                responseDTO.getOutput().getSubAgentResponse(),
                AgentType.valueOfString(
                    responseDTO.getOutput().getResponseAgent())
                    .getCorrespondingSessionType(),
                logStoreId, tool, originalSql, chatId);
        
        // 设置额外信息
        commonChatResponseVO.setLlmResponse(...)
        commonChatResponseVO.setAskHuman(...)
        commonChatResponseVO.setSubAgentResponse(...)
        commonChatResponseVO.setMidState(...)
        
        return CommonResponse.<CommonChatResponseVO>builder()
            .responseVO(commonChatResponseVO)
            .traceId(responseDTO.getMetadata().getRunId())
            .build();
    } catch (Exception e) {
        throw e;
    }
}
```

#### 方法 2: commonChatStreamSse() - 流式模式 (SSE)

**位置**: Line 451-600

**流程** (13 步):

```java
public void commonChatStreamSse(CommonChatRequestVO requestVO, 
                                SseEmitter sseEmitter) {
    // 第1-4步: 同非流式模式
    // 验证权限、处理再问、获取历史等
    
    StreamResponseTracker tracker = new StreamResponseTracker();
    
    // 第5步: 创建流式 Sink (反压缓冲)
    Sinks.Many<String> sink = Sinks.many()
        .multicast().onBackpressureBuffer();
    
    // 第6步: 调用流式 API (DiBrain Router)
    webClient.post()
        .uri(diBrainUrl + "/router/stream")  // ← 关键 URI
        .bodyValue(commonChatRequestDTO)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<
            CommonChatStreamEvent>() { })
        
        // 第7步: 处理 Flux 流
        .concatMap(response -> {
            String processedEvent = 
                processCommonChatEventWithTracker(
                    response, tracker, requestVO, chatId);
            
            if (processedEvent == null) {
                return Flux.empty();
            }
            
            // 如果是最后事件，结束流
            if (Objects.equals(response.getStatus(), 
                StreamStatusType.END.getType())) {
                return Flux.just(processedEvent)
                    .concatWith(Flux.empty());
            }
            
            return Flux.just(processedEvent);
        })
        
        // 第8步: 合并心跳信号
        .mergeWith(Flux.interval(Duration.ofSeconds(1))
            .map(tick -> {
                CommonChatStreamEvent heartbeat = 
                    new CommonChatStreamEvent();
                heartbeat.setEvent(
                    CommonChatStreamEventInfo.builder()
                        .name("ping").build());
                return JsonUtils.toJsonWithOutNull(heartbeat);
            }))
        
        // 第9步: 超时检查
        .map(event -> {
            long currentTime = System.currentTimeMillis();
            long timeoutMs = 
                assistantGlobalConfig.getCommonChatTimeout() 
                * 1000L;
            if (currentTime - tracker.getStartTime() > timeoutMs) {
                throw new ServerException(
                    ResponseCodeEnum.STREAM_TIMEOUT_ERROR);
            }
            return event;
        })
        
        // 第10步: 设置流结束条件
        .takeUntil(event -> {
            if (event instanceof String) {
                CommonChatStreamEvent streamEvent = 
                    JsonUtils.toObject(event, 
                        CommonChatStreamEvent.class);
                return Objects.nonNull(streamEvent) &&
                    Objects.nonNull(streamEvent.getStatus()) &&
                    (Objects.equals(streamEvent.getStatus(), 
                        StreamStatusType.END.getType()) ||
                     Objects.equals(streamEvent.getStatus(), 
                        StreamStatusType.ERROR.getType()));
            }
            return false;
        })
        
        // 第11步: 流结束回调
        .doFinally(signalType -> {
            log.info("CommonChat SSE stream ended with signal: {}", 
                signalType);
            
            if (signalType == SignalType.ON_COMPLETE) {
                log.info("Stream completed normally.");
                tracker.setCompleted(true);
            } else if (signalType == SignalType.ON_ERROR) {
                log.info("Stream terminated due to an error.");
            } else if (signalType == SignalType.CANCEL) {
                log.info("Stream was cancelled.");
                tracker.setCanceled(true);
            }
            
            // 第12步: 保存结果到数据库
            saveTrackerResultToDatabase(tracker, requestVO);
        })
        
        // 第13步: 订阅流事件
        .subscribe(
            e -> {
                // 成功事件: 推送给前端
                try {
                    sseEmitter.send(e);
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            },
            err -> {
                // 错误处理
                String error;
                boolean isTimeout = 
                    (err instanceof TimeoutException) ||
                    (err instanceof ServerException && 
                     ((ServerException)err)
                        .getResponseCodeEnum()
                        .equals(ResponseCodeEnum
                            .STREAM_TIMEOUT_ERROR));
                
                if (isTimeout) {
                    error = buildCommonChatFailedResponse(
                        tracker, 
                        tracker.getCurrentStage(),
                        MessageConstants.COMMON_TIMEOUT_PREFIX_TEXT);
                } else {
                    error = buildCommonChatFailedResponse(
                        tracker, 
                        tracker.getCurrentStage(),
                        MessageConstants.COMMON_CHAT_ERROR_MESSAGE);
                }
                
                try {
                    sseEmitter.send(error);
                } catch (IOException e) {
                    log.error("Failed to send error response", e);
                }
                sseEmitter.completeWithError(err);
            },
            () -> {
                // 完成: 关闭连接
                sseEmitter.complete();
            });
    
    // SSE 回调: 超时、完成、错误
    sseEmitter.onTimeout(...);
    sseEmitter.onCompletion(...);
    sseEmitter.onError(...);
}
```

### 1.3 核心辅助方法

#### toDiBrainChatHistory() - 历史转换

**位置**: Line 325-373

```java
private List<Map<String, String>> toDiBrainChatHistory(
    List<ChatMessageTab> history) {
    
    List<Map<String, String>> chatHistory = new ArrayList<>();
    
    for (ChatMessageTab message : history) {
        // 处理用户问题
        if (message.getMessageType() == QUESTION) {
            CommonChatRequestVO requestVO = 
                JsonUtils.toObject(message.getChatContent(), 
                    CommonChatRequestVO.class);
            
            HumanMessage humanMessage = new HumanMessage();
            humanMessage.setQuestion(requestVO.getQuestion());
            humanMessage.setSelectedAssets(
                requestVO.getDataScope());
            
            String selectedAssetsJson = 
                JsonUtils.toJsonWithOutNull(
                    humanMessage.getSelectedAssets());
            
            chatHistory.add(Map.of(
                USER_NAME, humanMessage.getQuestion(),
                SELECTED_ASSETS, selectedAssetsJson));
        }
        
        // 处理 AI 回复
        else if (message.getMessageType() == RESPONSE) {
            CommonChatResponseVO responseVO;
            
            // 检查是否流式响应
            if (isStreamResponseTracker(message.getChatContent())) {
                StreamResponseTracker tracker = 
                    JsonUtils.toObject(message.getChatContent(),
                        StreamResponseTracker.class);
                
                if (tracker.isCanceled()) {
                    chatHistory.add(Map.of(
                        DI_ASSISTANT_NAME, 
                        MessageConstants.USER_CANCEL_MESSAGE));
                    continue;
                }
                
                responseVO = tracker.getFinalResponse();
            } else {
                responseVO = JsonUtils.toObject(
                    message.getChatContent(), 
                    CommonChatResponseVO.class);
            }
            
            // 构建输出 DTO
            CommonChatOutputDTO outputDTO = 
                CommonChatOutputDTO.builder()
                    .responseAgent(AgentType
                        .valueOfSessionType(...)
                        .getType())
                    .askHuman(responseVO.getAskHuman())
                    .llmRawResponse(responseVO.getLlmResponse())
                    .subAgentResponse(
                        responseVO.getSubAgentResponse())
                    .midState(midState)
                    .build();
            
            String responseJson = 
                JsonUtils.toJsonWithOutNull(outputDTO);
            
            chatHistory.add(Map.of(
                DI_ASSISTANT_NAME, responseJson));
        }
    }
    
    return chatHistory;
}
```

---

## 🎯 Part 2: ChatBIService (656 行)

### 2.1 核心职责

**作用**: BI 数据可视化分析，生成图表并支持数据下载。

```
用户分析请求
  ↓
保存到消息表
  ↓
调用 DiBrain BI API (/chat_bi/stream)
  ↓
处理流式事件:
├─ METADATA: 获取 runId
├─ DATA: 中间数据处理
├─ SUCCESS: 图表生成成功 → 保存消息
└─ FAILED: 失败处理
  ↓
支持两种返回模式:
├─ Flux: WebFlux (非阻塞)
└─ SSE: 长连接推送
  ↓
支持数据下载:
├─ CSV 导出
├─ StarRocks 流式下载
└─ Scheduler 数据获取
```

### 2.2 两个核心方法

#### 方法 1: textToBIChartV2() - Flux 返回

**位置**: Line 156-205

```java
public Flux<String> textToBIChartV2(ChatBIRequestVO biRequestVO) {
    try {
        // 1️⃣ 验证权限
        SessionDetailDTO session = 
            sessionService.getSession(biRequestVO.getSessionId());
        sessionService.checkAuth(
            biRequestVO.getCommonInfo().getUser(), session);
        
        // 2️⃣ 保存用户提问
        ChatCreateRequestDTO chatCreateRequestDTO = 
            convertor.convertMessageVOToChatCreateDto(biRequestVO);
        chatService.createChatMessage(chatCreateRequestDTO);
        
        // 3️⃣ 构建 DiBrain BI 请求
        CommonRequestDTO chatBIReq = 
            createChatBIRequest(biRequestVO, session.getModel());
        long sessionId = session.getSessionId();
        
        // 4️⃣ 创建反压缓冲 Sink
        Sinks.Many<String> sink = Sinks.many()
            .multicast().onBackpressureBuffer();
        
        // 5️⃣ 调用流式 API 并处理
        webClient.post()
            .uri(diBrainUrl + "/chat_bi/stream")
            .bodyValue(chatBIReq)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .retrieve()
            .bodyToFlux(new ParameterizedTypeReference<
                ServerSentEvent<String>>() { })
            
            // 6️⃣ 流结束条件 (两种方式)
            .takeUntil(sse -> 
                Objects.nonNull(sse) && 
                Objects.nonNull(sse.event()) && 
                ("end".equals(sse.event()) || 
                 "EOF".equals(sse.data())))
            
            // 7️⃣ 设置 4 分钟超时
            .timeout(Duration.ofMinutes(4))
            
            // 8️⃣ 预处理事件 (提取 runId)
            .map(this::preProcessEvent)
            
            // 9️⃣ 过滤事件 (只要 data 和 error)
            .filter(this::eventFilter)
            
            // 🔟 处理事件
            .map(e -> processChatBIEvent(e, sessionId, biRequestVO))
            
            // 1️⃣1️⃣ 流结束回调
            .doFinally(signalType -> {
                log.info("Stream ended with signal: {}", signalType);
                THREAD_LOCAL_RUN_ID.get().set("");
                // 日志记录
            })
            
            // 1️⃣2️⃣ 订阅流
            .subscribe(
                e -> sink.tryEmitNext(e),         // 成功
                err -> sink.tryEmitError(err),    // 错误
                () -> {                           // 完成
                    sink.tryEmitNext("end");
                    sink.tryEmitComplete();
                });
        
        return sink.asFlux();
        
    } catch (Exception e) {
        String errStr = buildChatBIFailedResponse(biRequestVO, e);
        return Flux.just(errStr);
    }
}
```

#### 方法 2: textToBIChartV2() - SSE 版本

**位置**: Line 207-260

```java
public void textToBIChartV2(ChatBIRequestVO biRequestVO, 
                            SseEmitter sseEmitter) {
    // 流程完全相同，区别在于最后推送方式
    
    webClient.post()
        ...
        .subscribe(
            e -> {
                try {
                    // 直接推送到 SSE
                    sseEmitter.send(e);
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            },
            err -> sseEmitter.completeWithError(err),
            () -> sseEmitter.complete());
}
```

### 2.3 核心辅助方法

#### preProcessEvent() - 提取元数据

**位置**: Line 281-294

```java
public ServerSentEvent<String> preProcessEvent(
    ServerSentEvent<String> event) {
    
    // 检查是否是 metadata 事件
    if (Objects.nonNull(event) && 
        Objects.nonNull(event.event()) && 
        event.event().equals(EVENT_METADATA)) {  // "metadata"
        
        log.info("metadata event received: {}", event.data());
        
        // 解析 runId
        CommonRespMetadataDTO metadataDTO = 
            JsonUtils.toObject(event.data(), 
                CommonRespMetadataDTO.class);
        
        if (metadataDTO != null) {
            // 保存到 ThreadLocal (便于后续使用)
            THREAD_LOCAL_RUN_ID.get()
                .set(metadataDTO.getRunId());
            log.debug("metadata event saved to thread local: {}", 
                metadataDTO.getRunId());
        }
    }
    
    return event;
}
```

#### processChatBIEvent() - 事件处理核心

**位置**: Line 302-376

```java
public String processChatBIEvent(ServerSentEvent<String> event, 
                                 final long sessionId, 
                                 final ChatBIRequestVO biRequestVO) {
    
    // 1️⃣ 处理错误事件
    if (event.event().equals(EVENT_ERROR)) {
        String prevData = prevEventData.get();
        prevEventData.remove();
        
        CommonSseEvent<GenerateChartEvent> prevContextEvent = 
            JsonUtils.toObject(prevData, 
                new TypeReference<CommonSseEvent<...>>() { });
        
        StageInfo errorStage = findErrorStage(prevContextEvent);
        SseError sseError = JsonUtils.toObject(event.data(), 
            SseError.class);
        
        ChatBIFailedResponseVO errorResp = 
            ChatBIFailedResponseVO.builder()
                .order(errorStage.getOrder())
                .status(String.valueOf(sseError.getStatusCode()))
                .type(errorStage.getType())
                .sql(errorStage.getMessage())
                .message(sseError.getMessage())
                .build();
        
        return genErrorResponseEvent(sessionId, biRequestVO, 
            errorResp);
    }
    
    // 2️⃣ 处理失败事件 (FAILED)
    if (isFailedEvent(event)) {
        prevEventData.remove();
        
        CommonSseEvent<StageInfo> failedEvent = 
            JsonUtils.toObject(event.data(), 
                new TypeReference<CommonSseEvent<StageInfo>>() { });
        
        StageInfo failedStage = failedEvent.getData();
        
        ChatBIFailedResponseVO errorResp = 
            ChatBIFailedResponseVO.builder()
                .order(failedStage.getOrder())
                .status("200")
                .type(failedStage.getType())
                .sql(failedStage.getExtraData())
                .message(failedStage.getMessage())
                .build();
        
        return genErrorResponseEvent(sessionId, biRequestVO, 
            errorResp);
    }
    
    // 3️⃣ 处理成功事件 (SUCCESS) ✅
    else if (isSuccessEvent(event)) {
        prevEventData.remove();
        
        // 3.1: 解析响应
        ChatBIResponseDTO responseDTO = 
            JsonUtils.toObject(event.data(), 
                ChatBIResponseDTO.class);
        ChatBISuccessResponseDTO successResponseDTO = 
            JsonUtils.convertObjectToClass(
                responseDTO.getData(), 
                ChatBISuccessResponseDTO.class);
        
        // 3.2: 转换为 VO
        ChatBISuccessResponseVO successResponseVO = 
            ChatBISuccessResponseVO.builder()
                .sql(successResponseDTO.getSql())
                .suggestChart(successResponseDTO.getSuggestChart())
                .dataset(DTOConverter.convertToDatasetSet(
                    successResponseDTO.getDataset()))
                .message(successResponseDTO.getMessage())
                .build();
        
        // 3.3: 设置额外信息
        successResponseVO.setQuestion(biRequestVO.getQuestion());
        successResponseVO.setIdcRegion(
            biRequestVO.getIdcRegion());
        successResponseVO.setTableUidList(
            biRequestVO.getTableUidList());
        successResponseVO.setLanguageType(
            biRequestVO.getLanguageType());
        successResponseVO.setTranslateText(
            biRequestVO.getTranslateText());
        
        // 3.4: 构建 SSE 事件
        CommonSseEvent<ChatBISuccessResponseVO> successEvent = 
            CommonSseEvent.<ChatBISuccessResponseVO>builder()
                .event(GenerateChartEventType.fromString(
                    responseDTO.getEvent()))
                .data(successResponseVO)
                .build();
        
        // 3.5: 保存到数据库 ✅ 关键
        ChatCreateRequestDTO chatCreateRequestDTO = 
            convertor.convertMessageVOToChatCreateDto(
                successEvent,
                ChatMessageType.RESPONSE,
                sessionId,
                AgentUtils.buildDiAssistantCommonInfo(),
                THREAD_LOCAL_RUN_ID.get().get());
        
        Long chatId = chatService.createChatMessage(
            chatCreateRequestDTO);
        
        successResponseVO.setChatId(chatId);
        log.info("saved success message with chat id: {}", chatId);
        
        return JsonUtils.toJson(successEvent);
    }
    
    // 4️⃣ 其他事件: 缓存并返回
    prevEventData.set(event.data());
    return event.data();
}
```

#### downloadChatBIData() - 数据下载

**位置**: Line 507-566

```java
public void downloadChatBIData(long chatId, String user, 
                               OutputStreamWriter outputStreamWriter) 
    throws IOException {
    
    // 1️⃣ 验证用户
    if (Objects.isNull(user)) {
        throw new ServerException(..., "user is null");
    }
    
    // 2️⃣ 获取聊天详情
    ChatDetailDTO chatDetail = 
        chatService.getChatDetail(chatId);
    SessionDetailDTO sessionDetailDTO = 
        sessionService.getSession(chatDetail.getSessionId());
    
    // 3️⃣ 验证所有权
    if (!user.equals(sessionDetailDTO.getUser())) {
        throw new ServerException(..., 
            "only session owner can download chat result");
    }
    
    // 4️⃣ 提取响应
    CommonChatResponseVO responseVO;
    if (ChatResponseTypeUtils.isStreamResponseTracker(
        chatDetail.getChatContent())) {
        
        StreamResponseTracker tracker = 
            JsonUtils.toObject(chatDetail.getChatContent(), 
                StreamResponseTracker.class);
        responseVO = tracker.getFinalResponse();
    } else {
        responseVO = JsonUtils.toObject(
            chatDetail.getChatContent(), 
            CommonChatResponseVO.class);
    }
    
    // 5️⃣ 提取 BI 响应
    ChatBIResponseVO response = 
        JsonUtils.convertObjectToClass(
            responseVO.getResultData(), 
            ChatBIResponseVO.class);
    
    // 6️⃣ 验证成功
    if (!SUCCESS_EVENT.equals(response.getEvent())) {
        throw new ServerException(..., 
            "only success chat result can trigger download data");
    }
    
    ChatBISuccessResponseVO successResponseVO = 
        JsonUtils.convertObjectToClass(response.getData(), 
            ChatBISuccessResponseVO.class);
    
    // 7️⃣ 验证权限
    if (successResponseVO.isSkipAuth()) {
        throw new ServerException(..., 
            "No permission to download this data");
    }
    
    // 8️⃣ 三种数据来源优先级
    if (Objects.nonNull(successResponseVO.getAdhocCode())) {
        // 方式1: 通过 Scheduler (AdhocCode)
        if (successResponseVO.isSkipAuth()) {
            // 生产环境
            iterateFetchData(outputStreamWriter, 
                schedulerProdClient::fetchProdData, 
                successResponseVO.getAdhocCode());
        } else {
            // 开发环境
            iterateFetchData(outputStreamWriter, 
                schedulerDevClient::fetchDevData, 
                successResponseVO.getAdhocCode());
        }
    } else if (Objects.nonNull(
        successResponseVO.getChatDataset())) {
        
        // 方式2: StarRocks 数据
        ChatDatasetInfo datasetInfo = 
            successResponseVO.getChatDataset();
        starRocksService.downloadDatasetData(datasetInfo, 
            outputStreamWriter);
    } else {
        // 方式3: 内嵌数据集 (CSV)
        List<List<String>> csvContent = 
            toCsvConverter.toCSV(successResponseVO.getDataset());
        
        CSVPrinter csvPrinter = new CSVPrinter(
            outputStreamWriter, CSVFormat.DEFAULT);
        
        for (List<String> headerLine : csvContent) {
            csvPrinter.printRecord(headerLine);
        }
        
        csvPrinter.flush();
    }
}
```

---

## 📊 流程对比表

| 阶段 | CommonChatService | ChatBIService |
|------|-------------------|---------------|
| **验证** | 权限检查 | 权限检查 |
| **保存** | 用户提问 → ChatService | 用户提问 → ChatService |
| **API 调用** | diBrainClient.commonChat() | webClient + /chat_bi/stream |
| **流式处理** | Router/Stream | SSE + Flux |
| **关键参数** | agentName, threadId | RunID, StageInfo |
| **响应保存** | 完整响应 | 仅 SUCCESS 保存 |
| **错误处理** | 超时 + 异常 | 三级错误处理 |
| **下载支持** | ❌ 无 | ✅ 三种方式 |

---

## 🔑 关键代码片段

### 1. StreamResponseTracker (流式追踪器)

```java
// CommonChatService 中的流式处理核心
StreamResponseTracker tracker = new StreamResponseTracker();
tracker.setStartTime(System.currentTimeMillis());
tracker.setDataScope(requestVO.getDataScope());

// 每个事件更新 tracker
tracker.startNewStage(eventName);
tracker.endStage(eventName, response);
tracker.updateStage(eventName, status, data);

// 最后保存
saveTrackerResultToDatabase(tracker, requestVO);
```

### 2. ThreadLocal 管理 (ChatBIService)

```java
// 保存 RunID (METADATA 事件)
THREAD_LOCAL_RUN_ID.get().set(metadataDTO.getRunId());

// 使用 RunID (SUCCESS 事件)
THREAD_LOCAL_RUN_ID.get().get()

// 清理
THREAD_LOCAL_RUN_ID.get().set("");
```

### 3. 事件过滤 (ChatBIService)

```java
public boolean eventFilter(ServerSentEvent<String> event) {
    return Objects.nonNull(event) &&
        Objects.nonNull(event.event()) &&
        (event.event().equals(EVENT_DATA) ||  // "data"
         event.event().equals(EVENT_ERROR));  // "error"
    // 过滤掉 "metadata"
}
```

### 4. 超时保护 (CommonChatService)

```java
.map(event -> {
    long currentTime = System.currentTimeMillis();
    long timeoutMs = assistantGlobalConfig
        .getCommonChatTimeout() * 1000L;
    
    if (currentTime - tracker.getStartTime() > timeoutMs) {
        throw new ServerException(
            ResponseCodeEnum.STREAM_TIMEOUT_ERROR);
    }
    
    return event;
})
```

