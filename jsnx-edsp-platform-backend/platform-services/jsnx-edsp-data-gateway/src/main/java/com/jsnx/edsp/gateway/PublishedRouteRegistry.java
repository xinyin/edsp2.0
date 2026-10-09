package com.jsnx.edsp.gateway;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Last-good, immutable routing snapshot. Business requests never query MySQL or
 * Admin; publication pushes a refresh and the scheduler repairs missed pushes.
 */
@Component
public class PublishedRouteRegistry {
    private final WebClient admin;
    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    public PublishedRouteRegistry(WebClient.Builder builder,
                                  @Value("${edsp.admin-url:http://127.0.0.1:8081}") String adminUrl) {
        this.admin = builder.baseUrl(adminUrl).build();
    }

    @PostConstruct
    public void initialize() { refreshSafely(); }

    @Scheduled(initialDelayString = "${edsp.route-registry.initial-delay-ms:3000}",
            fixedDelayString = "${edsp.route-registry.refresh-ms:5000}")
    public void scheduledRefresh() { refreshSafely(); }

    public synchronized Snapshot refresh() {
        List<Map<String, Object>> rows = admin.get().uri("/api/internal/routes/published")
                .retrieve().bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(Duration.ofSeconds(3));
        List<PublishedRoute> parsed = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            parsed.add(PublishedRoute.from(row));
        }
        return install(parsed);
    }

    Snapshot install(Collection<PublishedRoute> routes) {
        Map<String, PublishedRoute> byCode = new HashMap<>();
        Map<String, PublishedRoute> byPath = new HashMap<>();
        for (PublishedRoute route : routes) {
            PublishedRoute duplicateCode = byCode.put(route.interfaceCode(), route);
            PublishedRoute duplicatePath = byPath.put(route.pathKey(), route);
            if (duplicateCode != null || duplicatePath != null) {
                throw new IllegalStateException("发布路由存在重复接口编码或 METHOD+Path：" + route.interfaceCode());
            }
        }
        Snapshot next = new Snapshot(Map.copyOf(byCode), Map.copyOf(byPath), Instant.now(), null);
        current.set(next);
        return next;
    }

    public Snapshot refreshSafely() {
        try {
            return refresh();
        } catch (Exception error) {
            Snapshot previous = current.get();
            Snapshot retained = new Snapshot(previous.byCode(), previous.byPath(), previous.loadedAt(),
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            current.set(retained);
            return retained;
        }
    }

    public Optional<PublishedRoute> resolve(String apiCode, String method, String path) {
        if (apiCode == null || apiCode.isBlank()) return Optional.empty();
        Snapshot snapshot = current.get();
        PublishedRoute byCode = snapshot.byCode().get(apiCode.trim().toUpperCase(Locale.ROOT));
        PublishedRoute byPath = snapshot.byPath().get(PublishedRoute.pathKey(method, path));
        if (byCode == null || byPath == null || byCode.interfaceId() != byPath.interfaceId()) return Optional.empty();
        PublishedRoute resolved = byCode;
        if (resolved == null || !resolved.servicePath().equals(path)
                || !resolved.httpMethod().equalsIgnoreCase(method)) return Optional.empty();
        return Optional.of(resolved);
    }

    public Snapshot snapshot() { return current.get(); }

    public record Snapshot(Map<String, PublishedRoute> byCode, Map<String, PublishedRoute> byPath,
                           Instant loadedAt, String lastError) {
        static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Instant.EPOCH, "路由表尚未加载"); }
    }

    public record PublishedRoute(long interfaceId, String interfaceCode, String servicePath,
                                 String httpMethod, int routeVersion, long dataSourceId,
                                 String sourceCode, String applicationName, String runtimeUrl) {
        static PublishedRoute from(Map<String, Object> row) {
            String runtime = text(row.get("runtime_url"));
            if (!runtime.isBlank()) validateRuntimeUrl(runtime);
            return new PublishedRoute(number(row.get("interface_id")).longValue(),
                    text(row.get("interface_code")).toUpperCase(Locale.ROOT), text(row.get("service_path")),
                    text(row.get("http_method")).toUpperCase(Locale.ROOT), number(row.get("route_version")).intValue(),
                    number(row.get("data_source_id")).longValue(), text(row.get("source_code")),
                    text(row.get("application_name")), trim(runtime));
        }

        String pathKey() { return pathKey(httpMethod, servicePath); }
        static String pathKey(String method, String path) {
            return text(method).toUpperCase(Locale.ROOT) + " " + text(path);
        }
        private static Number number(Object value) {
            if (value instanceof Number number) return number;
            throw new IllegalArgumentException("路由表数字字段缺失：" + value);
        }
        private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
        private static String trim(String value) {
            String result = value;
            while (result.endsWith("/") && result.length() > 1) result = result.substring(0, result.length() - 1);
            return result;
        }
        private static void validateRuntimeUrl(String value) {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("非法数据应用运行地址：" + value);
            }
        }
    }
}
