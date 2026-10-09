package com.jsnx.edsp.log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded asynchronous handoff. A slow or unavailable transport never blocks Gateway. */
public final class AsyncLogPublisher implements LogPublisher {
    private final BlockingQueue<LogEvent> queue;
    private final Consumer<LogEvent> sink;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    public AsyncLogPublisher(int capacity, Consumer<LogEvent> sink, String threadName) {
        if (capacity < 1) throw new IllegalArgumentException("日志队列容量必须大于0");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink = sink;
        this.worker = new Thread(this::drain, threadName);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean publish(LogEvent event) {
        if (!running.get() || !queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    @Override public long droppedCount() { return dropped.get(); }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            try {
                LogEvent event = queue.take();
                try { sink.accept(event); } catch (RuntimeException ignored) { dropped.incrementAndGet(); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            worker.interrupt();
            queue.clear();
        }
    }
}
