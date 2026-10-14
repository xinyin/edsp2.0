package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Demo authentication plus real API-grant validation. The fixed secret only
 * represents credential verification; Admin validates client/API authorization,
 * validity period and daily quota from MySQL.
 */
@Component
public class ExternalApiAuthFilter implements GlobalFilter, Ordered {
    public static final String EXECUTION_SCOPE_HEADER = "X-Edsp-Execution-Scope";
    public static final String INTERNAL_TEST_KEY_HEADER = "X-Edsp-Internal-Test-Key";
    public static final String DRAFT_TEST_SCOPE = "DRAFT_TEST";
    public static final String DRAFT_TEST_AUTHORIZED_ATTRIBUTE = "edsp.draft-test.authorized";
    private final ObjectMapper mapper;
    private final WebClient admin;
    private final String bootstrapApiKey;
    private final String internalTestKey;

    public ExternalApiAuthFilter(ObjectMapper mapper, WebClient.Builder builder,
                                 @Value("${edsp.admin-url:http://127.0.0.1:8081}") String adminUrl,
                                 @Value("${edsp.auth.bootstrap-api-key:demo-secret-key}") String bootstrapApiKey,
                                 @Value("${edsp.test-tool.internal-key:demo-internal-test-key}") String internalTestKey) {
        this.mapper = mapper;
        this.admin = builder.baseUrl(adminUrl).build();
        this.bootstrapApiKey = bootstrapApiKey;
        this.internalTestKey = internalTestKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!DynamicDataApplicationRoutingFilter.isDynamicApiRoute(exchange)) {
            return chain.filter(exchange);
        }
        String servicePath = exchange.getRequest().getPath().value();

        String clientCode = exchange.getRequest().getHeaders().getFirst("X-App-Id");
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-Api-Key");
        String requestedEnvironment = exchange.getRequest().getHeaders().getFirst("X-Environment");
        String environment = requestedEnvironment == null || requestedEnvironment.isBlank()
                ? "TEST" : requestedEnvironment;
        if (clientCode == null || clientCode.isBlank() || !bootstrapApiKey.equals(apiKey)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "EDSP-AUTH-401", "调用方认证失败");
        }

        if (DRAFT_TEST_SCOPE.equals(exchange.getRequest().getHeaders().getFirst(EXECUTION_SCOPE_HEADER))) {
            String suppliedTestKey = exchange.getRequest().getHeaders().getFirst(INTERNAL_TEST_KEY_HEADER);
            if (!constantTimeEquals(internalTestKey, suppliedTestKey)) {
                return reject(exchange, HttpStatus.FORBIDDEN, "EDSP-TEST-403", "内部链路测试凭证无效");
            }
            exchange.getAttributes().put(DRAFT_TEST_AUTHORIZED_ATTRIBUTE, true);
            return chain.filter(exchange);
        }

        return admin.get().uri(uriBuilder -> uriBuilder.path("/api/internal/authorizations/check")
                        .queryParam("clientCode", clientCode)
                        .queryParam("servicePath", servicePath)
                        .queryParam("environment", environment)
                        .queryParamIfPresent("apiCode", java.util.Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("X-Api-Code")))
                        .build())
                .retrieve().bodyToMono(Map.class)
                .flatMap(result -> Boolean.TRUE.equals(result.get("authorized"))
                        ? chain.filter(exchange)
                        : reject(exchange, HttpStatus.FORBIDDEN, "EDSP-AUTH-403",
                                String.valueOf(result.getOrDefault("reason", "调用方未获得 API 授权"))))
                .onErrorResume(error -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "EDSP-AUTH-503", "授权中心暂不可用"));
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] body = mapper.writeValueAsString(Map.of(
                    "code", code, "message", message, "resultStatus", "ERROR"))
                    .getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception ignored) {
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
