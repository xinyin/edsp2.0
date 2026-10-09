package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.log.AsyncLogPublisher;
import com.jsnx.edsp.log.LogEvent;
import com.jsnx.edsp.log.LogPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GatewayLogPublisher implements LogPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final String transport;
    private final WebClient observability;
    private final AsyncLogPublisher delegate;
    private final AtomicLong transportFailures = new AtomicLong();

    public GatewayLogPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                               @Value("${edsp.log.kafka-topic:edsp.api.raw-log}") String topic,
                               @Value("${edsp.log.queue-capacity:10000}") int capacity,
                               @Value("${edsp.log.transport:HTTP}") String transport,
                               @Value("${edsp.log.observability-url:http://127.0.0.1:8083}") String observabilityUrl,
                               WebClient.Builder webClientBuilder) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
        this.transport = transport;
        this.observability = webClientBuilder.baseUrl(observabilityUrl).build();
        this.delegate = new AsyncLogPublisher(capacity, this::send, "edsp-gateway-log-publisher");
    }

    @Override public boolean publish(LogEvent event) { return delegate.publish(event); }
    @Override public long droppedCount() { return delegate.droppedCount() + transportFailures.get(); }

    private void send(LogEvent event) {
        try {
            if ("HTTP".equalsIgnoreCase(transport)) {
                observability.post().uri("/internal/events/gateway").bodyValue(event)
                        .retrieve().toBodilessEntity().block();
                return;
            }
            String payload = mapper.writeValueAsString(event);
            kafka.send(topic, event.traceId(), payload).whenComplete((ignored, error) -> {
                if (error != null) transportFailures.incrementAndGet();
            });
        } catch (Exception error) {
            transportFailures.incrementAndGet();
        }
    }

    @PreDestroy
    @Override public void close() { delegate.close(); }
}
