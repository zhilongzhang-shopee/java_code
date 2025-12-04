```
package com.shopee.di.assistant.service.common;

import com.shopee.di.assistant.common.exception.ResponseCodeEnum;
import com.shopee.di.assistant.common.exception.ServerException;
import com.shopee.di.assistant.common.model.ChatSessionType;
import com.shopee.di.assistant.common.model.LogLevel;
import com.shopee.di.assistant.common.model.RequestRelation;
import com.shopee.di.assistant.common.model.StreamStatusType;
import com.shopee.di.assistant.common.model.ResponseStatusType;
import com.shopee.di.assistant.common.model.chat.MessageExtraInfo;
import com.shopee.di.assistant.common.model.commonchat.CommonChatRequestVO;
import com.shopee.di.assistant.common.model.commonchat.stream.CommonChatStreamEvent;
import com.shopee.di.assistant.common.model.commonchat.stream.CommonChatStreamEventInfo;
import com.shopee.di.assistant.common.model.commonchat.stream.RequestVO;
import com.shopee.di.assistant.common.model.setting.UserSettingDetailVO;
import com.shopee.di.assistant.common.utils.AgentUtils;
import com.shopee.di.assistant.common.utils.JsonUtils;
import com.shopee.di.assistant.constants.CommonConstants;
import com.shopee.di.assistant.constants.MessageConstants;
import com.shopee.di.assistant.convertor.ChatMessageConvertor;
import com.shopee.di.assistant.dao.entity.ChatMessageTab;
import com.shopee.di.assistant.dao.entity.ResponseEventTab;
import com.shopee.di.assistant.dao.entity.ResponseStateTab;
import com.shopee.di.assistant.service.dto.chat.SessionStatusDTO;
import com.shopee.di.assistant.service.response.ResponseEventTabService;
import com.shopee.di.assistant.rest.client.dto.dibrain.commonchat.CommonChatRequestDTO;
import com.shopee.di.assistant.service.chat.ChatService;
import com.shopee.di.assistant.service.dto.chat.ChatCreateRequestDTO;
import com.shopee.di.assistant.service.dto.session.SessionDetailDTO;
import com.shopee.di.assistant.service.response.ResponseStateTabService;
import com.shopee.di.assistant.service.session.SessionService;
import com.shopee.di.assistant.service.setting.UserSettingService;
import com.shopee.di.assistant.service.stream.StreamResponseTracker;
import com.shopee.di.assistant.service.utils.AssistantGlobalConfig;
import com.shopee.di.assistant.service.utils.CoreUserLogService;
import jakarta.annotation.Resource;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class CommonChatStreamService {

  @Resource
  private WebClient webClient;

  @Value("${assistant.feign.client-properties.uris.di-brain-client}")
  private String diBrainUrl;

  @Value("${assistant.feign.client-properties.uris.data-dashboard-client}")
  private String diDashBoardUrl;

  @Resource
  private ChatMessageConvertor convertor;

  @Resource
  private ChatService chatService;

  @Resource
  private SessionService sessionService;

  @Resource
  private AssistantGlobalConfig assistantGlobalConfig;

  @Resource
  private UserSettingService userSettingService;

  @Resource
  private CoreUserLogService coreUserLogService;

  @Resource
  private CommonChatService commonChatService;

  @Resource
  private ResponseEventTabService responseEventTabService;

  @Resource
  private ResponseStateTabService responseStateTabService;
  
  public void commonChatStreamSse(CommonChatRequestVO requestVO, SseEmitter sseEmitter) {
    String user = requestVO.getCommonInfo().getUser();
    String userEmail = requestVO.getCommonInfo().getUserEmail();
    boolean isCoreUser = coreUserLogService.isCoreUser(userEmail);
    coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "CommonChat stream invoke started, user: {}, userEmail: {}, sessionId: {}, question: {}, tool: {}",
        user, userEmail, requestVO.getSessionId(), requestVO.getQuestion(), requestVO.getTool());

    SessionDetailDTO session = sessionService.getSession(requestVO.getSessionId());
    if (ChatSessionType.DASHBOARD_AGENT.getType().equals(requestVO.getTool()) || Objects.equals(session.getSessionType(), ChatSessionType.DASHBOARD_AGENT)) {
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Use Dashboard stream processing, user: {}, userEmail: {}, sessionId: {}", user, userEmail, requestVO.getSessionId());
      commonChatService.commonChatDashboardStreamSse(requestVO, sseEmitter, session);
      return;
    }
    StreamResponseTracker tracker = new StreamResponseTracker();
    tracker.setIsCoreUser(isCoreUser);
    StreamResponseTracker previousTracker = new StreamResponseTracker();
    try {
      sessionService.checkAuth(user, session);
      if (requestVO.isAskAgain()) {
        coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Ask again, delete last two messages, user: {}, userEmail: {}, sessionId: {}", user, userEmail, requestVO.getSessionId());
        chatService.deleteLastTwoChatMessage(requestVO.getSessionId());
      }
      List<ChatMessageTab> messageHistory = chatService.getCommonChatMessageHistory(requestVO.getSessionId());
      List<Map<String, String>> history = commonChatService.toDiBrainChatHistory(messageHistory);
      String threadId = commonChatService.getThreadId(messageHistory);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Get message history, user: {}, userEmail: {}, sessionId: {}, historySize: {}, threadId: {}",
          user, userEmail, requestVO.getSessionId(), history.size(), threadId);
      commonChatService.checkDataset(requestVO, messageHistory);

      RequestRelation requestRelation = RequestRelation.builder()
          .requestFromChatId(requestVO.getRelationChatId())
          .build();
      ChatCreateRequestDTO chatCreateRequestDTO = convertor.convertMessageVOToChatCreateDto(requestVO, requestRelation);
      Long nowTime = System.currentTimeMillis();
      Long chatId = chatService.createChatMessageByTime(chatCreateRequestDTO, nowTime);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Create chat message, user: {}, userEmail: {}, sessionId: {}, chatId: {}", user, userEmail, requestVO.getSessionId(), chatId);

      UserSettingDetailVO userSettingDetailVO = userSettingService.getSetting(requestVO.getCommonInfo().getUserEmail());
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Get user settings, user: {}, userEmail: {}, settings: {}",
          user, userEmail, JsonUtils.toJsonWithOutNull(userSettingDetailVO));

      MessageExtraInfo messageExtraInfo = MessageExtraInfo.builder()
          .stream(true)
          .userSetting(userSettingDetailVO.getUserSetting())
          .build();
      ChatCreateRequestDTO responseCreateDTO = convertor.convertStreamMessageVOToChatCreateDto(tracker,
          AgentUtils.buildDiAssistantCommonInfo(), requestVO.getSessionId(), null, ChatSessionType.COMMON_CHAT.getType(), null, messageExtraInfo);
      Long responseChatId = chatService.createChatMessage(responseCreateDTO);
      tracker.setQuestionContent(RequestVO.builder()
          .chatId(chatId)
          .question(requestVO.getQuestion())
          .user(requestVO.getCommonInfo().getUser())
          .userEmail(requestVO.getCommonInfo().getUserEmail())
          .region(requestVO.getCommonInfo().getRegion())
          .createTime(nowTime)
          .build());
      tracker.setChatId(responseChatId);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Set tracker, user: {}, userEmail: {}, chatId: {}, responseChatId: {}, sessionType: {}",
          user, userEmail, chatId, responseChatId, session.getSessionType().getType());
      tracker.setSessionType(session.getSessionType().getType());

      CommonChatRequestDTO commonChatRequestDTO = commonChatService.createCommonChatStreamRequest(
          requestVO, session.getModel(), history, threadId, chatId, userSettingDetailVO, isCoreUser);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Create stream request, user: {}, userEmail: {}, request: {}", user, userEmail, JsonUtils.toJsonWithOutNull(commonChatRequestDTO));

      tracker.setStartTime(System.currentTimeMillis());
      tracker.setDataScope(requestVO.getDataScope());

      responseStateTabService.saveStatus(responseChatId, requestVO.getSessionId(), ResponseStatusType.PROCESS);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Set response state to progress, user: {}, userEmail: {}, responseChatId: {}", user, userEmail, responseChatId);

      Disposable streamSubscription = createStreamSubscription(responseChatId, requestVO.getSessionId(), commonChatRequestDTO, tracker, previousTracker, requestVO, chatId);

      Disposable pollingDisposable = sendEventsToFrontend(responseChatId, 0L, sseEmitter);

      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "Create stream subscription completed, user: {}, userEmail: {}, sessionId: {}", user, userEmail, requestVO.getSessionId());

      sseEmitter.onTimeout(() -> {
        coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.ERROR, "SSE stream timeout, user: {}, userEmail: {}, sessionId: {}", user, userEmail, requestVO.getSessionId());
        // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
        if (!pollingDisposable.isDisposed()) {
          pollingDisposable.dispose();
        }
      });
      sseEmitter.onCompletion(() -> {
        coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.INFO, "SSE stream completed, user: {}, userEmail: {}, sessionId: {}", user, userEmail, requestVO.getSessionId());
        // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
        if (!pollingDisposable.isDisposed()) {
          pollingDisposable.dispose();
        }
      });
      sseEmitter.onError((throwable) -> {
        coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.ERROR, "SSE stream error, user: {}, userEmail: {}, sessionId: {}, error: {}", user, userEmail, requestVO.getSessionId(), throwable.getMessage());
        // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
        if (!pollingDisposable.isDisposed()) {
          pollingDisposable.dispose();
        }
      });
    } catch (Exception e) {
      log.error("Error in CommonChat SSE stream processing", e);
      coreUserLogService.logIfCoreUser(isCoreUser, requestVO.getSessionId(), LogLevel.ERROR, "CommonChat stream processing exception, user: {}, userEmail: {}, sessionId: {}, error: {}",
          user, userEmail, requestVO.getSessionId(), e.getMessage(), e);
    }
  }

  /**
   * 创建流订阅
   * 处理事件并保存到数据库，在后台持续运行
   *
   * @param messageId 消息ID（responseChatId）
   * @param sessionId Session ID
   * @param commonChatRequestDTO 通用聊天请求DTO
   * @param tracker StreamResponseTracker
   * @param previousTracker StreamResponseTracker
   * @param requestVO CommonChatRequestVO
   * @param chatId 请求的 chatId
   * @return Disposable 用于管理流订阅，可在后台持续运行
   */
  public Disposable createStreamSubscription(Long messageId,
                                             Long sessionId,
                                             CommonChatRequestDTO commonChatRequestDTO,
                                             StreamResponseTracker tracker,
                                             StreamResponseTracker previousTracker,
                                             CommonChatRequestVO requestVO,
                                             Long chatId) {
    AtomicLong eventIdCounter = new AtomicLong(0L);
    long startTime = System.currentTimeMillis();
    
    return webClient.post()
        .uri(diBrainUrl + "/router/stream")
        .bodyValue(commonChatRequestDTO)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<CommonChatStreamEvent>() { })
        .concatMap(response -> {
          previousTracker.setStreamResponseTracker(tracker);
          // 使用 processCommonChatEventWithTracker 处理事件，得到包含 tracker 状态的 event
          String processedEvent = commonChatService.processCommonChatEventWithTracker(response, tracker, requestVO, chatId);
          if (processedEvent == null) {
            return Flux.empty();
          }
          
          // 保存处理后的 event 到 MySQL（包含 tracker 状态，心跳事件不保存）
          if (!isHeartbeatEvent(processedEvent)) {
            saveEventToDatabase(messageId, sessionId, eventIdCounter, processedEvent);
          }
          
          if (Objects.nonNull(response) && Objects.nonNull(response.getStatus())
              && (Objects.equals(StreamStatusType.END.getType(), response.getStatus()) 
                  || Objects.equals(StreamStatusType.ERROR.getType(), response.getStatus()))) {
            return Flux.just(processedEvent).concatWith(Flux.empty());
          }
          return Flux.just(processedEvent);
        })
        .mergeWith(Flux.interval(Duration.ofSeconds(1))
            .flatMap(tick -> {
              // 定期检查是否被取消
              if (responseStateTabService.isCanceled(messageId)) {
                log.info("Stream subscription detected cancel status, messageId: {}", messageId);
                tracker.setStreamResponseTracker(previousTracker);
                tracker.setCanceled(true);
                // 保存最终结果到数据库
                saveTrackerResultToDatabase(tracker, requestVO);
                return Flux.error(new RuntimeException("Stream cancelled by user"));
              }
              return Flux.empty(); // 继续，不发送任何事件
            }))
        .map(event -> {
          // 超时检查
          long currentTime = System.currentTimeMillis();
          long timeoutMs = assistantGlobalConfig.getCommonChatTimeout() * 1000L;
          if (currentTime - startTime > timeoutMs) {
            throw new ServerException(ResponseCodeEnum.STREAM_TIMEOUT_ERROR);
          }
          return event;
        })
        .takeUntil(event -> {
          // 检查结束条件
          if (Objects.nonNull(event)) {
            try {
              CommonChatStreamEvent streamEvent = JsonUtils.toObject(event, CommonChatStreamEvent.class);
              return Objects.nonNull(streamEvent)
                  && Objects.nonNull(streamEvent.getStatus())
                  && (Objects.equals(StreamStatusType.END.getType(), streamEvent.getStatus())
                      || Objects.equals(StreamStatusType.ERROR.getType(), streamEvent.getStatus()));
            } catch (Exception e) {
              log.warn("Failed to parse event for takeUntil check: {}", event, e);
              return false;
            }
          }
          return false;
        })
        .doFinally(signalType -> {
          log.info("CommonChat stream subscription ended with signal: {}, messageId: {}", signalType, messageId);
          coreUserLogService.logIfCoreUser(tracker.getIsCoreUser(), requestVO.getSessionId(), LogLevel.INFO, "CommonChat stream subscription ended with signal: {}", signalType);

          // 检查是否被取消（可能在 doFinally 之前已经处理过，但这里再次检查确保状态正确）
          if (responseStateTabService.isCanceled(messageId) && !tracker.isCanceled()) {
            log.info("CommonChat stream subscription detected cancel status in doFinally, messageId: {}", messageId);
            tracker.setStreamResponseTracker(previousTracker);
            tracker.setCanceled(true);
          }

          if (signalType == SignalType.ON_COMPLETE) {
            log.info("CommonChat stream subscription completed normally, messageId: {}", messageId);
            tracker.setCompleted(true);
          } else if (signalType == SignalType.ON_ERROR) {
            log.info("CommonChat stream subscription terminated due to an error, messageId: {}", messageId);
            tracker.setError(true);
          } else if (signalType == SignalType.CANCEL) {
            log.info("CommonChat stream subscription was cancelled, messageId: {}", messageId);
            if (!tracker.isCanceled()) {
              tracker.setStreamResponseTracker(previousTracker);
              tracker.setCanceled(true);
            }
          }
          // 保存最终结果到数据库
          saveTrackerResultToDatabase(tracker, requestVO);
        })
        // 使用 subscribeOn 让订阅在后台线程上运行，确保不随请求关闭而关闭
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            event -> {
              log.debug("Event processed and saved to database, messageId: {}", messageId);
            },
            error -> {
              // 错误处理
              // 检查是否是取消操作
              if (error instanceof RuntimeException && "Stream cancelled by user".equals(error.getMessage())) {
                log.info("CommonChat stream subscription cancelled by user, messageId: {}", messageId);
                // 取消流程已在 mergeWith 中处理，这里不需要额外处理
                return;
              }

              boolean isTimeout = (error instanceof TimeoutException)
                  || (error instanceof ServerException
                      && ((ServerException) error).getResponseCodeEnum().equals(ResponseCodeEnum.STREAM_TIMEOUT_ERROR));
              String errorEvent;
              if (isTimeout) {
                log.error("CommonChat stream subscription timeout, messageId: {}", messageId, error);
                errorEvent = commonChatService.buildCommonChatFailedResponse(tracker, tracker.getCurrentStage(), MessageConstants.COMMON_TIMEOUT_PREFIX_TEXT);
              } else {
                log.error("Error in CommonChat stream subscription, messageId: {}", messageId, error);
                errorEvent = commonChatService.buildCommonChatFailedResponse(tracker, tracker.getCurrentStage(), MessageConstants.COMMON_CHAT_ERROR_MESSAGE);
              }

              // 保存错误事件到数据库
              saveEventToDatabase(messageId, sessionId, eventIdCounter, errorEvent);
            },
            () -> {
              log.info("CommonChat stream subscription completed, messageId: {}", messageId);
            }
        );
  }

  /**
   * 从 MySQL 循环获取最新事件并发送给前端
   *
   * @param messageId 消息ID（responseChatId）
   * @param startEventId 开始的event
   * @param sseEmitter SSE 发送器
   * @return Disposable 用于管理轮询任务
   */
  public Disposable sendEventsToFrontend(Long messageId, Long startEventId, SseEmitter sseEmitter) {

    AtomicLong lastEventId = new AtomicLong(startEventId);
    AtomicBoolean isEnd = new AtomicBoolean(false);

    return Flux.interval(Duration.ofMillis(1000)) // 每1s轮询一次
        .flatMap(tick -> {
          if (isEnd.get()) {
            return Flux.empty();
          }

          List<ResponseEventTab> events = responseEventTabService.queryByMessageId(messageId,
              lastEventId.get() > 0 ? lastEventId.get() + 1 : null);

          if (events.isEmpty()) {
            try {
              CommonChatStreamEvent pingEvent = new CommonChatStreamEvent();
              pingEvent.setEvent(CommonChatStreamEventInfo.builder()
                  .name("ping")
                  .build());
              String pingContent = JsonUtils.toJsonWithOutNull(pingEvent);
              sseEmitter.send(pingContent);
            } catch (IOException e) {
              log.error("Failed to send ping event to SSE, messageId: {}", messageId, e);
              sseEmitter.completeWithError(e);
              isEnd.set(true);
              return Flux.just(true); // 标记结束
            }
            return Flux.just(false); // 继续轮询
          }

          ResponseEventTab lastEvent = events.getLast();
          if (lastEvent.getEventId() != null) {
            lastEventId.set(lastEvent.getEventId());
          }

          for (ResponseEventTab event : events) {
            if (event.getContent() != null) {
              try {
                CommonChatStreamEvent streamEvent = JsonUtils.toObject(event.getContent(), CommonChatStreamEvent.class);
                if (Objects.nonNull(streamEvent) && Objects.nonNull(streamEvent.getStatus())
                    && (Objects.equals(StreamStatusType.END.getType(), streamEvent.getStatus())
                        || Objects.equals(StreamStatusType.ERROR.getType(), streamEvent.getStatus()))) {
                  isEnd.set(true);
                }
              } catch (Exception e) {
                log.warn("Failed to parse event for end check: {}", event.getContent(), e);
              }
            }

            // 发送事件给前端
            try {
              sseEmitter.send(event.getContent());
            } catch (IOException e) {
              log.error("Failed to send event to SSE, messageId: {}, eventId: {}", messageId, event.getEventId(), e);
              sseEmitter.completeWithError(e);
              isEnd.set(true);
              return Flux.just(true); // 标记结束
            }
          }

          return Flux.just(isEnd.get()); // 返回是否结束
        })

        .takeUntil(end -> end) // 当检测到结束事件时停止轮询
        .doFinally(signalType -> {
          log.info("Event polling ended with signal: {}, messageId: {}", signalType, messageId);
          try {
            sseEmitter.complete();
          } catch (Exception e) {
            log.error("Failed to complete SSE, messageId: {}", messageId, e);
          }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            end -> {
              log.debug("Polling tick processed, messageId: {}, isEnd: {}", messageId, end);
            },
            error -> {
              log.error("Error in event polling, messageId: {}", messageId, error);
              try {
                sseEmitter.completeWithError(error);
              } catch (Exception e) {
                log.error("Failed to complete SSE with error", e);
              }
            },
            () -> {
              log.info("Event polling completed, messageId: {}", messageId);
            }
        );
  }

  private boolean isHeartbeatEvent(String event) {
    try {
      CommonChatStreamEvent streamEvent = JsonUtils.toObject(event, CommonChatStreamEvent.class);
      return streamEvent != null
          && streamEvent.getEvent() != null
          && "ping".equals(streamEvent.getEvent().getName());
    } catch (Exception e) {
      return false;
    }
  }

  private void saveEventToDatabase(Long messageId, Long sessionId, AtomicLong eventIdCounter, String eventContent) {
    try {
      Long eventId = eventIdCounter.incrementAndGet();
      ResponseEventTab eventTab = new ResponseEventTab();
      eventTab.setMessageId(messageId);
      eventTab.setSessionId(sessionId);
      eventTab.setEventId(eventId);
      eventTab.setContent(eventContent);
      eventTab.setCreateTime(System.currentTimeMillis());

      boolean saved = responseEventTabService.save(eventTab);
      if (!saved) {
        log.error("Failed to save event to database, messageId: {}, eventId: {}", messageId, eventId);
      } else {
        log.debug("Saved event to database, messageId: {}, eventId: {}", messageId, eventId);
      }
    } catch (Exception e) {
      log.error("Error saving event to database, messageId: {}", messageId, e);
    }
  }

  private void saveTrackerResultToDatabase(StreamResponseTracker tracker, CommonChatRequestVO requestVO) {
    ChatCreateRequestDTO chatCreateRequestDTO;
    MessageExtraInfo messageExtraInfo = MessageExtraInfo.builder()
        .stream(true)
        .build();
    if (Objects.nonNull(tracker.getFinalResponse())) {
      chatCreateRequestDTO = convertor.convertStreamMessageVOToChatCreateDto(tracker,
          AgentUtils.buildDiAssistantCommonInfo(), requestVO.getSessionId(), Optional.ofNullable(tracker.getTraceId()).orElse(CommonConstants.BLANK_STRING), tracker.getFinalResponse().getTool(), tracker.getMidState(), messageExtraInfo);
    } else {
      chatCreateRequestDTO = convertor.convertStreamMessageVOToChatCreateDto(tracker,
          AgentUtils.buildDiAssistantCommonInfo(), requestVO.getSessionId(), Optional.ofNullable(tracker.getTraceId()).orElse(CommonConstants.BLANK_STRING), ChatSessionType.COMMON_CHAT.getType(), tracker.getMidState(), messageExtraInfo);
    }
    chatService.rewriteChatMessage(tracker.getChatId(), chatCreateRequestDTO);
    sessionService.updateSessionTime(chatCreateRequestDTO.getSessionId());
    if (tracker.isCanceled()) {
      responseStateTabService.saveStatus(tracker.getChatId(), requestVO.getSessionId(), ResponseStatusType.CANCEL);
    } else if (tracker.isCompleted()) {
      responseStateTabService.saveStatus(tracker.getChatId(), requestVO.getSessionId(), ResponseStatusType.COMPLETE);
    } else {
      responseStateTabService.saveStatus(tracker.getChatId(), requestVO.getSessionId(), ResponseStatusType.ERROR);
    }
  }

  /**
   * 根据session id list 批量查询 status
   * @param sessionIds
   * @return
   */
  public List<SessionStatusDTO> batchQuerySessionStatus(List<Long> sessionIds){
    List<ResponseStateTab> result = responseStateTabService.batchQueryStatus(sessionIds);

    return result.stream()
        .map(responseStateTab -> SessionStatusDTO.builder()
            .sessionId(responseStateTab.getSessionId())
            .status(responseStateTab.getStatus())
            .build())
        .collect(Collectors.toList());
  }

  /**
   * 用户主动取消chat
   * @param messageId
   */
  public void cancelChat(Long messageId) {

  }

  /**
   * 定期（30min）删除event db内的数据
   */
  @Scheduled(fixedRate = 30 * 60 * 1000L)
  public void deleteResponseEvent() {
    // 获取当前时间
    long currentTimeMillis = System.currentTimeMillis();

    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    // 删除过期数据
    int deletedCount = responseEventTabService.cleanupOldEvents();
    log.info("deleteResponseEvent finished, currentTime: {}, deletedCount: {}",
        dateFormat.format(currentTimeMillis), deletedCount);
  }
}
```



```
package com.shopee.di.assistant.controller.common;

import com.shopee.di.assistant.common.model.CommonInfo;
import com.shopee.di.assistant.common.model.CommonRequest;
import com.shopee.di.assistant.common.model.commonchat.CommonChatRequestVO;
import com.shopee.di.assistant.common.model.commonchat.CommonChatResponseVO;
import com.shopee.di.assistant.dao.entity.ResponseStateTab;
import com.shopee.di.assistant.service.common.CommonChatService;
import com.shopee.di.assistant.service.common.CommonChatStreamService;
import com.shopee.di.assistant.service.dto.chat.ReOpenSessionRequestDTO;
import com.shopee.di.assistant.service.dto.chat.SessionStatusDTO;
import com.shopee.di.assistant.service.response.ResponseStateTabService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

@Slf4j
@Tag(name = "Common Chat API")
@RestController
@RequestMapping("/common")
public class CommonChatController {

    public static final long SSE_EMITTER_TIMEOUT = 660_000L;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Resource
    private CommonChatService commonChatService;

    @Resource
    private CommonChatStreamService commonChatStreamService;

    @Resource
    private ResponseStateTabService responseStateTabService;

    @PostMapping("/chat")
    private CommonChatResponseVO commonChat(@RequestBody CommonChatRequestVO commonChatRequestVO,
                                            @RequestAttribute CommonRequest commonRequest) {
        if (Objects.isNull(commonChatRequestVO.getCommonInfo())) {
            commonChatRequestVO.setCommonInfo(new CommonInfo());
        }
        commonChatRequestVO.getCommonInfo().setUser(commonRequest.getUser());
        commonChatRequestVO.getCommonInfo().setUserEmail(commonRequest.getUserEmail());
        return commonChatService.commonChatInvoke(commonChatRequestVO);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter commonChatStream(@RequestBody CommonChatRequestVO requestVO,
                                       @RequestAttribute(value = "commonRequest", required = false) CommonRequest commonRequest) {

        if (Objects.nonNull(commonRequest)) {
            requestVO.setCommonInfo(new CommonInfo());
            requestVO.getCommonInfo().setUser(commonRequest.getUser());
            requestVO.getCommonInfo().setUserEmail(commonRequest.getUserEmail());
        }

        SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            commonChatStreamService.commonChatStreamSse(requestVO, emitter);
            MDC.clear();
        });

        return emitter;
    }

  /**
   * 用户reopen session时，需要断点拉取event
   * @param request
   * @return
   */
  @PostMapping(value = "/chat/reopen", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter reOpenSession(@RequestBody ReOpenSessionRequestDTO request) {
    log.info("reopen session request:{}", request);

    SseEmitter emitter = new SseEmitter(SSE_EMITTER_TIMEOUT);

    Disposable pollingDisposable =
        commonChatStreamService.sendEventsToFrontend(request.getMessageId(),
            request.getStartEventId(),
            emitter);

    emitter.onTimeout(() -> {
      // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
      if (!pollingDisposable.isDisposed()) {
        pollingDisposable.dispose();
      }
    });
    emitter.onCompletion(() -> {
      // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
      if (!pollingDisposable.isDisposed()) {
        pollingDisposable.dispose();
      }
    });
    emitter.onError((throwable) -> {
      // 只停止向前端发送事件，不取消后台订阅，让 createStreamSubscription 继续运行
      if (!pollingDisposable.isDisposed()) {
        pollingDisposable.dispose();
      }
    });

    return emitter;
  }

  /**
   * 查询当前resopnse的完成状态
   * @param messageId
   * @return
   */
  @GetMapping(value = "/query/responseStatus")
  public Long getResponseStatus(@RequestParam Long messageId) {
    log.info("query response message Id:{}", messageId);

    ResponseStateTab responseStateTab = responseStateTabService.getByMessageId(messageId);

    return responseStateTab.getStatus();
  }

  /**
   * 根据session id list 批量查询 status
   * @param sessionIds
   * @return
   */
  @GetMapping(value = "/batchQuery/responseStatus")
  public List<SessionStatusDTO> getBatchResponseStatus(@RequestParam("sessionIds") List<Long> sessionIds) {
    log.info("batch query response sessionIds:{}", sessionIds);

    return commonChatStreamService.batchQuerySessionStatus(sessionIds);
  }

  /**
   * 用户主动取消chat
   * @param messageId
   */
  @PostMapping(value = "/cancel/chat")
  public void cancelChat(@RequestParam Long messageId) {
    log.info("cancel chat message Id:{}", messageId);


  }
}
```