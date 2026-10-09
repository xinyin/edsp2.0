package com.jsnx.edsp.sdk;

import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Non-blocking demo replacement for Kafka. Delivery failure never fails a business request. */
public final class DemoEventClient implements AutoCloseable {
    private final RestClient observability;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "edsp-demo-event-publisher");
                thread.setDaemon(true);
                return thread;
            });

    public DemoEventClient(String observabilityUrl) {
        this.observability = RestClient.builder().baseUrl(observabilityUrl).build();
    }

    public void publishCall(Map<String, Object> event) {
        executor.submit(() -> {
            try {
                observability.post().uri("/internal/events/call").body(event).retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // Demo event transport deliberately has loss-over-blocking semantics, same as Gateway logging.
            }
        });
    }

    @Override public void close() { executor.shutdownNow(); }
}
