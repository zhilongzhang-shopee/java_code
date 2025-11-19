package org.example.java_code.see;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 简单的SSE控制器
 * <p>
 * 仿照 CommonChatOpenApiController.testByFeedBack() 的设计： 1. 创建SseEmitter（用于推送SSE消息） 2.
 * 异步执行Service逻辑（不阻塞HTTP） 3. 返回SseEmitter给前端 4. 前端创建EventSource连接 5. Service逐个推送数据到SseEmitter 6.
 * 前端逐个接收并显示
 */
/**
 * CORS 配置说明：
 * 允许来自所有源的跨域请求
 * 用于支持前端从不同端口/域访问 SSE 端点
 */
@Slf4j
@RestController
@RequestMapping("/api/sse")
@CrossOrigin(origins = "*", methods = { RequestMethod.GET, RequestMethod.POST,
    RequestMethod.OPTIONS }, allowedHeaders = "*", maxAge = 3600)
public class SimpleFluxSseController {

  @Autowired
  private SimpleFluxSseService simpleFluxSseService;

  // 创建线程池用于异步处理（类似CommonChatOpenApiController中的executor）
  private static final Executor executor = Executors.newFixedThreadPool(10);

  private static final long SSE_TIMEOUT = 5 * 60 * 1000; // 5分钟超时

  /**
   * 简单的SSE接口
   * <p>
   * 请求：GET /api/sse/simple 返回：SSE连接 数据：10个简单数字，每个间隔500ms
   * <p>
   * 流程图： 前端 后端 Service │ │
   * │ ├─ GET /api/sse/simple ─→ │ │ ├─
   * 创建SseEmitter │ │ ← HTTP 200 ───────────┤ │ │ (SseEmitter)
   * ├─ 异步执行 ─────────→ │ │ │ ├─ 创建Flux流 │ ← SSE Event 1
   * ───────────────────────────────┤ (1号数据) │ ← SSE Event 2
   * ───────────────────────────────┤ (2号数据)
   * │ ← SSE Event 3 ───────────────────────────────┤ ... │ ← ... │
   * │ │ ← SSE Event 10 ──────────────────────────────┤ (10号数据) │ ← SSE完成 │
   * ├─ 流结束
   *
   * @return SseEmitter
   */
  @GetMapping("/simple")
  public SseEmitter simpleFluxStream() {
    long requestId = System.currentTimeMillis();
    log.info("📡 [{}] 收到SSE请求: /api/sse/simple", requestId);

    // 步骤1：创建SseEmitter（类似CommonChatOpenApiController第64行）
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

    // 当连接超时或断开时的回调
    emitter.onCompletion(() -> log.info("✅ [{}] SSE连接正常完成", requestId));
    emitter.onTimeout(() -> log.warn("⏱️ [{}] SSE连接超时", requestId));
    emitter.onError(throwable -> log.error("❌ [{}] SSE连接错误: {}", requestId, throwable.getMessage()));

    // // 步骤2：异步执行流处理（类似CommonChatOpenApiController第66-68行）
    // executor.execute(() -> {
    // log.info("🚀 [{}] 在后台线程中启动Flux流处理", requestId);
    // try {
    // simpleFluxSseService.createSimpleFluxStream(emitter);
    //
    // } catch (IOException e) {
    // log.error("❌ [{}] Flux流处理异常", requestId, e);
    // throw new RuntimeException(e);
    // }
    // });
    try {
      simpleFluxSseService.createSimpleFluxStream(emitter);

    } catch (IOException e) {
      log.error("❌ [{}] Flux流处理异常", requestId, e);
      throw new RuntimeException(e);
    }
    // 步骤3：立即返回emitter给前端（类似CommonChatOpenApiController第69行）
    log.info("✅ [{}] 返回SseEmitter给前端，前端可以立即建立EventSource连接", requestId);
    return emitter;
  }

  /**
   * 多阶段SSE接口（更复杂的示例）
   * <p>
   * 请求：GET /api/sse/multi-stage 返回：SSE连接 数据：分三个阶段发送数据 - 第一阶段：1-3（初始化） -
   * 第二阶段：4-6（处理） -
   * 第三阶段：7-9（验证）
   *
   * @return SseEmitter
   */
  @GetMapping("/multi-stage")
  public SseEmitter multiStageFluxStream() {
    log.info("📡 收到SSE请求: /api/sse/multi-stage");

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

    executor.execute(() -> {
      log.info("🚀 启动多阶段Flux流处理");
      simpleFluxSseService.createMultiStageFluxStream(emitter);
    });

    return emitter;
  }

  /**
   * 心跳检测接口
   * <p>
   * 用途：测试SSE连接是否正常 返回：每秒发送一个心跳消息，共10次
   *
   * @return SseEmitter
   */
  @GetMapping("/heartbeat")
  public SseEmitter heartbeatStream() {
    log.info("📡 收到SSE请求: /api/sse/heartbeat");

    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

    executor.execute(() -> {
      log.info("🚀 启动心跳检测");
      // 创建心跳Flux：每秒发送一个数字，共10个
      reactor.core.publisher.Flux.range(1, 10)
          .delaySequence(java.time.Duration.ofSeconds(1))
          .subscribe(
              number -> {
                try {
                  System.out.println("💓 发送心跳 " + number);
                  emitter.send(SseEmitter.event()
                      .id(number + "")
                      .name("heartbeat")
                      .data("心跳信号 #" + number)
                      .build());
                } catch (Exception e) {
                  log.error("心跳发送失败", e);
                  try {
                    emitter.completeWithError(e);
                  } catch (Exception ex) {
                    // ignore
                  }
                }
              },
              error -> {
                System.out.println("💓 发送心跳 " + error);
                try {
                  emitter.completeWithError(error);
                } catch (Exception e) {
                  // ignore
                }
              },
              () -> {
                log.info("✅ 心跳检测完成");
                try {
                  emitter.complete();
                } catch (Exception e) {
                  // ignore
                }
              });
    });

    return emitter;
  }
}
