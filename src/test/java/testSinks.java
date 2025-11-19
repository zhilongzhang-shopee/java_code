import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 📚 Reactor Sinks.Many 响应式流演示
 * 
 * 核心问题解答：
 * ─────────────────────────────────────────
 * 
 * ❓ 问题 1：缓冲区=10，为什么发送15条消息没溢出？
 * ✅ 答案：消费者 A 的强背压机制限制了生产速率
 * - 预取量=1 意味着一次只请求1个，每200ms才能完成
 * - 背压会从下游传播到上游，限制生产者速率
 * - 结果：缓冲区不会堆满，不会触发溢出异常
 * 
 * ❓ 问题 2：为什么消费者 B 没比 A 更快消费同一消息？
 * ✅ 答案：多播模式中流速由最慢消费者决定
 * - 消费者 A (200ms) vs B (快速处理)
 * - 多播意味着所有消费者共享同一个热源
 * - 消息流向：元素1 → [A处理200ms] → [B处理] → 元素2...
 * - B 虽然快，但必须等待上游有新元素推送
 * - 预取量(32 vs 1)只影响缓冲策略，不影响源速率
 * 
 * 🎯 关键洞察：
 * ─────────────────────────────────────────
 * 1. 背压(Backpressure)是响应式编程的核心
 * 2. Per-Consumer Buffer 设计：每个消费者独立拥有缓冲区
 * 3. 多播(Multicast)是热流，所有消费者速率受限于最慢消费者
 * 4. 预取量(Prefetch)是优化参数，默认值32兼顾性能和内存
 */
public class testSinks {

    public static void main(String[] args) throws InterruptedException {

        // 1. 创建一个 Sinks.Many (多播/多个值)
        // 使用 multicast() 允许多个订阅者。
        // onBackpressureBuffer(10, false) 的作用：
        // - 10: 每个消费者独立拥有一个大小为10的缓冲区 (Per-Consumer Buffer)
        // - false: 缓冲区满时不延迟错误，立即抛出 FAIL_OVERFLOW
        Sinks.Many<Integer> sink = Sinks.many().multicast()
                .onBackpressureBuffer(10, false); // 缓冲区大小10

        // 2. 将 Sinks 暴露为 Flux 供下游订阅
        Flux<Integer> flux = sink.asFlux();

        // --- 消费者 A (慢速，背压强) ---
        // 关键: publishOn(scheduler, 1) 中的 1 是预取量(prefetch)
        // 预取量=1 意味着:
        // - A一次只请求1个元素
        // - A必须处理完这个元素才会请求下一个
        // - 这会对上游产生强烈的背压，限制发射速率
        flux.publishOn(Schedulers.boundedElastic(), 1) // 预取量=1 (强背压)
                .doOnSubscribe(s -> System.out.println("-> 消费者 A 订阅成功"))
                .subscribe(data -> {
                    try {
                        // 模拟慢速处理（200ms）
                        Thread.sleep(200);
                        System.out.println("✅ 消费者 A [慢速] 收到并处理: " + data);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

        // --- 消费者 B (快速，预取量大) ---
        // publishOn(scheduler) 未指定预取量，使用默认值(通常是32)
        // 预取量=32 意味着:
        // - B会一次性预先拉取多个元素到缓冲区
        // - B更快地处理元素
        // - 但B无法比A更快地获得新元素(因为A的背压限制了流速)
        flux.publishOn(Schedulers.boundedElastic()) // 预取量=默认32 (较弱背压)
                .doOnSubscribe(s -> System.out.println("-> 消费者 B 订阅成功"))
                .subscribe(data -> {
                    // 模拟快速处理（10ms）
                    System.out.println("⭐ 消费者 B [快速] 收到: " + data);
                });

        // 等待订阅完成，给调度器一些时间
        Thread.sleep(100);

        // --- 快速生产者（External Producer）---
        // 在主线程中快速推送 15 个数据
        // 关键问题：为什么15条消息没有溢出10的缓冲区？
        // 原因：消费者 A 的强背压会限制生产速率
        // - 消费者 A 一次只请求1个元素，每200ms才能处理完
        // - 这会阻止生产者快速发射太多数据
        // - 结果：缓冲区不会满，不会出现 FAIL_OVERFLOW
        for (int i = 1; i <= 15; i++) {
            // 使用 tryEmitNext() 发射数据
            Sinks.EmitResult result = sink.tryEmitNext(i);
            if (result.isFailure()) {
                System.err.println("❌ 生产者发射失败 (" + i + "): " + result);
                if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                    System.err.println("    原因: 某个慢速消费者的缓冲区已满!");
                }
                // 生产者可以根据失败结果决定是重试、丢弃还是终止
            } else {
                System.out.println("-> 生产者成功发射: " + i);
            }
            Thread.sleep(1); // 生产者每 1ms 发射一次
        }

        // 等待所有数据被消费
        Thread.sleep(5000);
        sink.tryEmitComplete();
    }
}

/*
 * 📚 关键概念深度讲解：
 * 
 * 【问题1】缓冲区=10，为什么发送15条没溢出？
 * 
 * 答案：消费者 A 的强背压机制！
 * 
 * 流程图：
 * ┌─────────┐ ┌──────────────┐ ┌──────────────┐
 * │ 生产者 │ emit │ Sink Buffer │ pull │ 消费者 A(1) │
 * │(快速) │ ──→ │(Per-Consumer)│ ←── │(慢速,200ms) │
 * └─────────┘ └──────────────┘ └──────────────┘
 * ▲ │
 * │ 背压(Backpressure) │
 * └──────────────── 限速 ←─────────────────┘
 * 
 * 消费者 A 的行为：
 * - 初始请求 1 个元素 (预取量=1)
 * - 等待 200ms 处理完
 * - 再请求 1 个元素
 * - 循环...
 * 
 * 结果：流的速率被锁定在 1个/200ms = 5个/秒
 * 生产者虽然想快速发送，但会被背压阻止
 * 缓冲区永远不会堆满
 * 
 * 【问题2】为什么消费者 B 没有比 A 更快消费同一个消息？
 * 
 * 答案：多播(Multicast) + 背压传播
 * 
 * 多播的特点：
 * - 所有消费者从同一个热源接收数据
 * - 流的速率由最慢的消费者决定
 * - 背压会从下游传播到上游
 * 
 * 消息流向：
 * 时间轴 →
 * └─ 元素1 ──→ [A 处理 200ms] ──→ [B 处理]
 * └─ 元素2 ──→ 等待 A 先处理完 ──→ [A 处理 200ms] ──→ [B 处理]
 * └─ 元素3 ...
 * 
 * 即使 B 的预取量大（32），也无法"提前"获得未来的元素。
 * B 必须等待 A 处理完，以及上游有新元素推送。
 * 
 * 预取量的真实作用：
 * - 消费者 A (预取=1)：一个一个拉取，处理完才请求下一个
 * - 消费者 B (预取=32)：预先拉取多个缓存，但仍然受上游速率限制
 * 
 * 【对比】如果修改参数会怎样？
 * 
 * 情景 1：都改成预取量 32
 * - 流速会稍微快一点（但仍由消费者 A 的 200ms 处理时间限制）
 * - A 和 B 的行为不会有质性改变
 * 
 * 情景 2：改成 A 预取量 32，B 预取量 1
 * - 流速由 B 决定（B 的处理更快）
 * - B 会表现出强背压行为
 * 
 * 情景 3：使用两个独立的 Flux（不是多播）
 * - A 和 B 各自的流速独立
 * - B 会显著快于 A
 * 
 * ────────────────────────────────────────────────────────
 * 🎯 关键外带：
 * 
 * 1. 背压是响应式流的核心
 * - 慢速消费者会阻止快速生产者
 * - 缓冲区溢出的主要原因是背压过大
 * 
 * 2. 多播的特殊性
 * - 所有消费者共享同一个源
 * - 流速由最慢消费者决定
 * - 这是热流（Hot Observable）的特征
 * 
 * 3. 预取量的作用
 * - 控制消费者的背压强度
 * - 影响流的响应性和资源占用
 * - 默认值（32）是性能和内存的平衡
 */

// ============================================================
// 💡 实验建议：要看到完全不同的行为，可以尝试以下修改：
// ============================================================

/*
 * 
 * 【实验1】移除多播，使用 unicast (单播)
 * 
 * 改为：
 * Sinks.Many<Integer> sink = Sinks.many().unicast()
 * .onBackpressureBuffer(10);
 * 
 * 结果：只有第一个订阅者会收到数据！
 * 
 * ────────────────────────────────────────────────────────
 * 
 * 【实验2】保持多播，但改变缓冲区大小为 2，移除预取量限制
 * 
 * 改为：
 * Sinks.Many<Integer> sink = Sinks.many().multicast()
 * .onBackpressureBuffer(2, false);
 * 
 * flux.publishOn(Schedulers.boundedElastic(), 32) // 都改为32
 * flux.publishOn(Schedulers.boundedElastic(), 32)
 * 
 * 结果：很快会看到 FAIL_OVERFLOW 错误！
 * 因为两个消费者的处理速度无法匹配快速生产者
 * 
 * ────────────────────────────────────────────────────────
 * 
 * 【实验3】验证独立 Flux 的行为对比
 * 
 * 创建两个独立的 Flux 而不是从同一个 sink 的 multicast 订阅：
 * 
 * Flux<Integer> flux1 = Flux.interval(Duration.ofMillis(1))
 * .take(15)
 * .publishOn(Schedulers.boundedElastic(), 1)
 * .subscribe(A的处理逻辑);
 * 
 * Flux<Integer> flux2 = Flux.interval(Duration.ofMillis(1))
 * .take(15)
 * .publishOn(Schedulers.boundedElastic())
 * .subscribe(B的处理逻辑);
 * 
 * 结果：B 会显著快于 A！
 * 因为它们各自的流速独立，不受对方背压影响
 * 
 */