package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adds newly declared, unmapped standard response fields at the single HTTP boundary. */
@Component
public class PlatformResponseContractFilter implements GlobalFilter, Ordered {
    private final ObjectMapper mapper;
    private final PlatformResponseContractRegistry contracts;

    public PlatformResponseContractFilter(ObjectMapper mapper, PlatformResponseContractRegistry contracts) {
        this.mapper = mapper;
        this.contracts = contracts;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!DynamicDataApplicationRoutingFilter.isDynamicApiRoute(exchange)) return chain.filter(exchange);
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                if (!isJson(getHeaders().getContentType())) return super.writeWith(body);
                return DataBufferUtils.join(Flux.from(body)).flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    byte[] output = enrich(bytes);
                    getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
                    return super.writeWith(Mono.just(bufferFactory().wrap(output)));
                });
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    byte[] enrich(byte[] bytes) {
        try {
            Map<String, Object> result = mapper.readValue(bytes, new TypeReference<>() {});
            for (Map<String, Object> field : contracts.fields()) {
                String key = String.valueOf(field.getOrDefault("key", "")).trim();
                if (!key.isBlank()) result.putIfAbsent(key, null);
            }
            return mapper.writeValueAsBytes(new LinkedHashMap<>(result));
        } catch (Exception ignored) {
            return bytes;
        }
    }

    private boolean isJson(MediaType contentType) {
        return contentType != null && (MediaType.APPLICATION_JSON.includes(contentType)
                || contentType.getSubtype().contains("+json"));
    }

    /**
     * Install the decorator before Gateway's Netty response writer. A decorator
     * added after that filter cannot observe the downstream response body.
     */
    @Override public int getOrder() { return NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1; }
}
