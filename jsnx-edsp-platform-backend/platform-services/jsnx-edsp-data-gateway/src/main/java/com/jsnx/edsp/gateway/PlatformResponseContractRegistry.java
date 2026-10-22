package com.jsnx.edsp.gateway;

import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Last-good snapshot of the platform-wide response fields currently in effect. */
@Component
public class PlatformResponseContractRegistry {
    private final WebClient admin;
    private final AtomicReference<List<Map<String, Object>>> fields = new AtomicReference<>(List.of());

    public PlatformResponseContractRegistry(WebClient.Builder builder,
            @Value("${edsp.admin-url:http://127.0.0.1:8081}") String adminUrl) {
        this.admin = builder.baseUrl(adminUrl).build();
    }

    @Scheduled(initialDelayString = "${edsp.response-contract.initial-delay-ms:3000}",
            fixedDelayString = "${edsp.response-contract.refresh-ms:5000}")
    public void refreshSafely() {
        try {
            Map<String, Object> result = admin.get().uri("/api/platform-response-contracts/effective")
                    .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(3));
            Object value = result == null ? null : result.get("fields");
            if (value instanceof List<?> list) {
                fields.set(list.stream().filter(item -> item instanceof Map<?, ?>)
                        .map(item -> (Map<String, Object>) item).toList());
            }
        } catch (Exception ignored) {
            // Keep the last known contract so a temporary Admin outage never changes response shape.
        }
    }

    @PostConstruct
    public void initialize() { refreshSafely(); }

    public List<Map<String, Object>> fields() { return fields.get(); }
}
