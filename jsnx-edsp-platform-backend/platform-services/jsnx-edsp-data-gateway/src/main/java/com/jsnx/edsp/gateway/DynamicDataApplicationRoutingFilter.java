package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Resolves apiCode/Method/Path from the local route snapshot and selects the data application. */
@Component
public class DynamicDataApplicationRoutingFilter implements GlobalFilter, Ordered {
    static final String DYNAMIC_ROUTE_ID = "edsp-published-api-dynamic";
    private final PublishedRouteRegistry routes;
    private final ObjectMapper mapper;

    public DynamicDataApplicationRoutingFilter(PublishedRouteRegistry routes, ObjectMapper mapper) {
        this.routes = routes;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!isDynamicApiRoute(exchange)) return chain.filter(exchange);
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        String requestedCode = exchange.getRequest().getHeaders().getFirst("X-Api-Code");
        var route = routes.resolve(requestedCode, method, path).orElse(null);
        if (route == null) {
            return reject(exchange, HttpStatus.NOT_FOUND, "EDSP-ROUTE-404",
                    "未找到与 apiCode、HTTP 方法及 URL Path 一致的已发布路由");
        }
        if (route.runtimeUrl() == null || route.runtimeUrl().isBlank()) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "EDSP-ROUTE-503",
                    "数据源尚未登记处理应用运行地址：" + route.sourceCode());
        }
        URI target = URI.create(route.runtimeUrl() + "/internal/adapter/published");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, target);
        exchange.getAttributes().put("edsp.route.apiCode", route.interfaceCode());
        exchange.getAttributes().put("edsp.route.dataSourceCode", route.sourceCode());
        exchange.getAttributes().put("edsp.route.applicationName", route.applicationName());
        ServerHttpRequest routed = exchange.getRequest().mutate()
                .header("X-Api-Code", route.interfaceCode())
                .header("X-Route-Version", String.valueOf(route.routeVersion()))
                .header("X-Data-Application", route.applicationName())
                .build();
        return chain.filter(exchange.mutate().request(routed).build());
    }

    static boolean isDynamicApiRoute(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null && DYNAMIC_ROUTE_ID.equals(route.getId());
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsString(Map.of("code", code, "message", message,
                    "resultStatus", "ERROR")).getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (Exception ignored) {
            return exchange.getResponse().setComplete();
        }
    }

    @Override public int getOrder() { return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1; }
}
