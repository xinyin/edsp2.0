package com.jsnx.edsp.esb;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** In-process request/response queue used only when a real bank ESB is unavailable. */
@Component
public class MockEsbQueue {
    private static final Logger log = LoggerFactory.getLogger(MockEsbQueue.class);
    private final EsbMessageAdapter adapter;
    private final ArrayBlockingQueue<Message> requests;
    private final long timeoutMs;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private Thread worker;

    public MockEsbQueue(EsbMessageAdapter adapter,
                        @Value("${edsp.esb.mock-queue.capacity:1000}") int capacity,
                        @Value("${edsp.esb.mock-queue.timeout-ms:10000}") long timeoutMs) {
        this.adapter = adapter;
        this.requests = new ArrayBlockingQueue<>(Math.max(10, capacity));
        this.timeoutMs = Math.max(1000, timeoutMs);
    }

    @PostConstruct
    void start() {
        worker = new Thread(() -> {
            while (running.get() || !requests.isEmpty()) {
                try {
                    Message message = requests.poll(300, TimeUnit.MILLISECONDS);
                    if (message == null) continue;
                    try {
                        EsbForwardResult response = adapter.forward(message.servicePath(), message.appId(), message.apiKey(),
                                message.xml(), message.traceId(), message.diagnostic());
                        completed.incrementAndGet();
                        message.result().complete(response);
                    } catch (Exception error) {
                        failed.incrementAndGet();
                        log.error("ESB 模拟队列消费失败，traceId={}，error={}",
                                message.traceId(), error.getMessage(), error);
                        message.result().completeExceptionally(error);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mock-esb-request-queue");
        worker.setDaemon(true);
        worker.start();
    }

    public EsbForwardResult requestReply(String servicePath, String appId, String apiKey,
                                            String traceId, String xml, boolean diagnostic) {
        String currentTrace = traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : traceId;
        Message message = new Message(servicePath, appId, apiKey, currentTrace, xml, diagnostic, new CompletableFuture<>());
        if (!requests.offer(message)) throw new IllegalStateException("模拟 ESB 请求队列已满");
        accepted.incrementAndGet();
        try {
            return message.result().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            timedOut.incrementAndGet();
            log.error("ESB 模拟请求等待响应超时，traceId={}，timeoutMs={}", currentTrace, timeoutMs);
            throw new IllegalStateException("等待模拟 ESB 响应队列超时（" + timeoutMs + "ms）", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("模拟 ESB 消费处理失败：" + cause.getMessage(), cause);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待模拟 ESB 响应时线程被中断", error);
        }
    }

    public Map<String, Object> status() {
        return Map.of("mode", "IN_MEMORY_REQUEST_REPLY", "status", running.get() ? "UP" : "DOWN",
                "queueDepth", requests.size(), "capacity", requests.size() + requests.remainingCapacity(),
                "accepted", accepted.get(), "completed", completed.get(), "failed", failed.get(),
                "timedOut", timedOut.get(),
                "replyTimeout", Duration.ofMillis(timeoutMs).toString());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (worker != null) worker.interrupt();
    }

    private record Message(String servicePath, String appId, String apiKey, String traceId,
                           String xml, boolean diagnostic, CompletableFuture<EsbForwardResult> result) {}
}
