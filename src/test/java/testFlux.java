import reactor.core.publisher.Flux;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * 📚 Flux 背压演示 + onComplete回调问题
 * 
 * 问题：为什么 onComplete() 没有执行？
 * ──────────────────────────────────
 * 原因：主线程退出时间太短，onComplete还未来得及执行
 * 
 * 时间分析：
 * - 生产者: 20个 × 10ms = 200ms (快速)
 * - 消费者: 20个 × 500ms = 10000ms (慢速)
 * - 每4个请求一次：
 * t=0-2000ms: 处理 0-3
 * t=2000-4000ms: 处理 4-7
 * t=4000-6000ms: 处理 8-11
 * t=6000-8000ms: 处理 12-15
 * t=8000-10000ms: 处理 16-19
 * t=10000ms: onComplete执行 ✓
 * 
 * - 主线程等待: 5000ms ❌ 太短！
 * - 结果: main返回时onComplete还没执行
 * 
 * 解决方案：使用 CountDownLatch 同步
 */
public class testFlux {

  public static void main(String[] args) throws InterruptedException {
    // 使用 CountDownLatch 确保等待 onComplete
    CountDownLatch latch = new CountDownLatch(1);

    // 1. 创建一个快速的 Flux (生产者每 10 毫秒发送一个数字)
    Flux<Long> fastProducer = Flux.interval(Duration.ofMillis(10)).take(20);

    // 2. 订阅并使用自定义的慢速消费者 (MySlowSubscriber)
    fastProducer
        .doOnRequest(n -> System.out.println("--- Flux 收到需求: " + n + " ---")) // 监听上游请求
        .subscribe(new MySlowSubscriber<>(latch));

    // 3. 等待 onComplete 信号
    // 使用 CountDownLatch 而不是固定时间，确保等到onComplete
    System.out.println("🔄 主线程等待 onComplete...\n");
    boolean completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS); // 设置超时时间为15秒

    if (completed) {
      System.out.println("\n✅ 主线程检测到 onComplete，应用正常结束");
    } else {
      System.out.println("\n❌ 等待超时，onComplete未执行");
    }
  }
}

/**
 * 自定义慢速消费者：一次请求 4 个，每处理 1 个就暂停 500 毫秒
 * 
 * 关键改进：
 * - 接收 CountDownLatch，在 onComplete 时触发
 * - 确保主线程能等到流处理完成
 */
class MySlowSubscriber<T> implements Subscriber<T> {

  private Subscription subscription;
  private long count = 0;
  private final long batchSize = 4; // 一次拉取 4 个
  private final java.util.concurrent.CountDownLatch latch;

  public MySlowSubscriber(CountDownLatch latch) {
    this.latch = latch;
  }

  @Override
  public void onSubscribe(Subscription s) {
    this.subscription = s;
    System.out.println("订阅成功，请求初始批次: " + batchSize);
    s.request(batchSize); // 初始请求 4 个
  }

  @Override
  public void onNext(T t) {
    System.out.println("消费者处理数据: " + t);

    // 模拟慢速处理，暂停 500 毫秒
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    count++;
    // 每处理完 4 个元素，再次请求 4 个
    if (count % batchSize == 0) {
      System.out.println("已处理完一批，再次请求: " + batchSize);
      subscription.request(batchSize);
    }
  }

  @Override
  public void onError(Throwable t) {
    System.err.println("发生错误: " + t.getMessage());
    latch.countDown(); // 错误时也释放
  }

  @Override
  public void onComplete() {
    System.out.println("✅ 数据流处理完成！(onComplete执行)");
    latch.countDown(); // 关键：释放主线程的等待
  }
}