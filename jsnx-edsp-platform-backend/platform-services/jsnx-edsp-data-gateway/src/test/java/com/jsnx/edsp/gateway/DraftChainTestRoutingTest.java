package com.jsnx.edsp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DraftChainTestRoutingTest {
    @Test
    void validInternalCredentialBypassesBusinessGrantCheckOnlyForDraftTest() {
        ExternalApiAuthFilter filter = new ExternalApiAuthFilter(new ObjectMapper(), WebClient.builder(),
                "http://127.0.0.1:8081", "api-secret", "internal-secret");
        MockServerWebExchange exchange = dynamicExchange(headers -> {
            headers.add("X-App-Id", "EDSP_TEST_TOOL");
            headers.add("X-Api-Key", "api-secret");
            headers.add(ExternalApiAuthFilter.EXECUTION_SCOPE_HEADER, ExternalApiAuthFilter.DRAFT_TEST_SCOPE);
            headers.add(ExternalApiAuthFilter.INTERNAL_TEST_KEY_HEADER, "internal-secret");
        });
        AtomicBoolean chained = new AtomicBoolean();

        filter.filter(exchange, ignored -> { chained.set(true); return Mono.empty(); }).block();

        assertThat(chained).isTrue();
        assertThat(exchange.<Boolean>getAttribute(ExternalApiAuthFilter.DRAFT_TEST_AUTHORIZED_ATTRIBUTE)).isTrue();
    }

    @Test
    void invalidInternalCredentialCannotEnterDraftTestRoute() {
        ExternalApiAuthFilter filter = new ExternalApiAuthFilter(new ObjectMapper(), WebClient.builder(),
                "http://127.0.0.1:8081", "api-secret", "internal-secret");
        MockServerWebExchange exchange = dynamicExchange(headers -> {
            headers.add("X-App-Id", "EDSP_TEST_TOOL");
            headers.add("X-Api-Key", "api-secret");
            headers.add(ExternalApiAuthFilter.EXECUTION_SCOPE_HEADER, ExternalApiAuthFilter.DRAFT_TEST_SCOPE);
            headers.add(ExternalApiAuthFilter.INTERNAL_TEST_KEY_HEADER, "wrong");
        });

        filter.filter(exchange, ignored -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void authorizedDraftTestTargetsAdminExecutorInsteadOfPublishedRegistry() {
        DynamicDataApplicationRoutingFilter filter = new DynamicDataApplicationRoutingFilter(
                new PublishedRouteRegistry(WebClient.builder(), "http://127.0.0.1:8081"),
                new ObjectMapper(), "http://127.0.0.1:8081/");
        MockServerWebExchange exchange = dynamicExchange(headers -> headers.add("X-Api-Code", "TYGJJ_API_01"));
        exchange.getAttributes().put(ExternalApiAuthFilter.DRAFT_TEST_AUTHORIZED_ATTRIBUTE, true);
        AtomicBoolean chained = new AtomicBoolean();

        filter.filter(exchange, ignored -> { chained.set(true); return Mono.empty(); }).block();

        assertThat(chained).isTrue();
        URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(target).isEqualTo(URI.create("http://127.0.0.1:8081/api/internal/test-chain/TYGJJ_API_01"));
    }

    private MockServerWebExchange dynamicExchange(java.util.function.Consumer<HttpHeaders> headers) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.method(HttpMethod.POST, "/api/v1/tygjj/query");
        HttpHeaders values = new HttpHeaders();
        headers.accept(values);
        request.headers(values);
        MockServerWebExchange exchange = MockServerWebExchange.from(request.build());
        Route route = Route.async().id(DynamicDataApplicationRoutingFilter.DYNAMIC_ROUTE_ID)
                .uri("http://127.0.0.1:9").predicate(ignored -> true).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }
}
