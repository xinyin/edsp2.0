package com.jsnx.edsp.gateway;

import com.jsnx.edsp.log.LogEvent;
import com.jsnx.edsp.log.LogPublisher;
import com.jsnx.edsp.log.SensitiveDataMasker;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GatewayLogGlobalFilter implements GlobalFilter, Ordered {
    private final LogPublisher publisher;

    public GatewayLogGlobalFilter(LogPublisher publisher) { this.publisher = publisher; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!DynamicDataApplicationRoutingFilter.isDynamicApiRoute(exchange)) return chain.filter(exchange);
        String path = exchange.getRequest().getURI().getPath();
        long started = System.nanoTime();
        String traceId = exchange.getRequest().getHeaders().getFirst(TraceGlobalFilter.TRACE_HEADER);
        String appId = exchange.getRequest().getHeaders().getFirst("X-App-Id");
        return chain.filter(exchange)
                .doOnSuccess(ignored -> publish(exchange, traceId, appId, path, started, null))
                .doOnError(error -> publish(exchange, traceId, appId, path, started, error));
    }

    private void publish(ServerWebExchange exchange, String traceId, String appId,
                         String path, long started, Throwable error) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestTime", Instant.now().toString());
        fields.put("requestMethod", exchange.getRequest().getMethod() == null
                ? null : exchange.getRequest().getMethod().name());
        fields.put("requestPath", path);
        fields.put("requestQuery", exchange.getRequest().getURI().getRawQuery());
        fields.put("requestHeaders", SensitiveDataMasker.mask(headers(exchange.getRequest().getHeaders())));
        fields.put("requestBodyCaptured", false);
        fields.put("appId", appId);
        fields.put("orgCode", exchange.getRequest().getHeaders().getFirst("X-Org-Code"));
        fields.put("sourceSysCode", exchange.getRequest().getHeaders().getFirst("X-Source-Sys-Code"));
        fields.put("environment", exchange.getRequest().getHeaders().getFirst("X-Environment"));
        fields.put("apiCode", valueOr(exchange.getAttribute("edsp.route.apiCode"),
                exchange.getRequest().getHeaders().getFirst("X-Api-Code")));
        fields.put("providerCode", exchange.getRequest().getHeaders().getFirst("X-Provider-Code"));
        fields.put("dataSourceCode", valueOr(exchange.getAttribute("edsp.route.dataSourceCode"),
                exchange.getRequest().getHeaders().getFirst("X-Data-Source-Code")));
        fields.put("thirdCall", exchange.getRequest().getHeaders().getFirst("X-Third-Call"));
        fields.put("routeId", exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) == null
                ? null : exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR).toString());
        fields.put("httpCode", exchange.getResponse().getStatusCode() == null
                ? null : exchange.getResponse().getStatusCode().value());
        fields.put("responseTime", Instant.now().toString());
        fields.put("durationMs", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        fields.put("requestStatus", error == null ? "SUCCESS" : "FAILED");
        fields.put("errorType", error == null ? null : error.getClass().getName());
        fields.put("errorMessage", error == null ? null : error.getMessage());
        publisher.publish(new LogEvent("GATEWAY_API_CALL", traceId, Instant.now(), fields));
    }

    private Map<String, Object> headers(HttpHeaders headers) {
        Map<String, Object> values = new LinkedHashMap<>();
        headers.forEach((key, value) -> values.put(key, value == null || value.isEmpty() ? null : value.get(0)));
        return values;
    }

    private Object valueOr(Object primary, Object fallback) { return primary == null ? fallback : primary; }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 100; }
}
