package org.example.java_code.service;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时日志输出数字的示例任务。
 * <p>
 * 每 5 秒输出一次自增的数字，便于验证 @Scheduled 功能是否正常。
 */
@Slf4j
@Component
public class ScheduledNumberLogger {

    private final AtomicInteger counter = new AtomicInteger(0);

    /**
     * 每隔 5 秒输出一个递增的数字。
     */
    @Scheduled(fixedRate = 5_000)
    public void logNextNumber() {
        int value = counter.incrementAndGet();
        log.info("🔁 Scheduled number: {}", value);
    }
}

