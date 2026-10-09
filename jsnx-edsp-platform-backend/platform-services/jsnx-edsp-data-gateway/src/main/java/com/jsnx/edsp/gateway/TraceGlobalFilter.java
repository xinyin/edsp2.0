package com.jsnx.edsp.gateway;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {
    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        ServerHttpRequest request = exchange.getRequest().mutate().header(TRACE_HEADER, traceId).build();
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        MDC.put("traceId", traceId);
        return chain.filter(exchange.mutate().request(request).build()).doFinally(signal -> MDC.remove("traceId"));
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
