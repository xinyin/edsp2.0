package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

class ExternalApiAuthFilterTest {
    @Test void downstreamFailureIsNotReportedAsAuthorizationFailure() {
        WebClient.Builder admin = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"authorized\":true}").build()));
        ExternalApiAuthFilter filter = new ExternalApiAuthFilter(new ObjectMapper(), admin,
                "http://127.0.0.1:8081", "api-secret", "test-secret");
        MockServerWebExchange exchange = exchange();

        assertThatThrownBy(() -> filter.filter(exchange,
                ignored -> Mono.error(new IllegalStateException("data application unavailable"))).block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("data application unavailable");
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void authorizationLookupFailureStillReturnsAuth503() {
        WebClient.Builder admin = WebClient.builder().exchangeFunction(request ->
                Mono.error(new IllegalStateException("admin unavailable")));
        ExternalApiAuthFilter filter = new ExternalApiAuthFilter(new ObjectMapper(), admin,
                "http://127.0.0.1:8081", "api-secret", "test-secret");
        MockServerWebExchange exchange = exchange();

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("EDSP-AUTH-503");
    }

    private MockServerWebExchange exchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/test/01")
                .header("X-App-Id", "RISK_SYSTEM")
                .header("X-Api-Key", "api-secret")
                .header("X-Api-Code", "TEST_SERVICE")
                .build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, Route.async()
                .id(DynamicDataApplicationRoutingFilter.DYNAMIC_ROUTE_ID)
                .uri("http://127.0.0.1:9").predicate(ignored -> true).build());
        return exchange;
    }
}
