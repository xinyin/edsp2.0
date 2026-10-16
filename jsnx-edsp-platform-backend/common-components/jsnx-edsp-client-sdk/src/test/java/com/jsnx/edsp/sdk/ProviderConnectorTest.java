package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ProviderConnectorTest {
    @Test
    void missingProtocolConnectorNeverFallsBackToHttp() {
        UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of());
        for (String mode : List.of("DATABASE", "KAFKA", "PLATFORM_SERVICE")) {
            assertThatThrownBy(() -> client.execute(Map.of("accessMode", mode), "POST",
                    "http://127.0.0.1:1/should-not-connect", Map.of(), "T1", 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未安装 " + mode + " 连接器")
                    .hasMessageContaining("禁止回退");
        }
    }

    @Test
    void providerPluginReceivesConnectionRequestAndTimeout() {
        ProviderConnector connector = new ProviderConnector() {
            public String accessMode() { return "DATABASE"; }
            public Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                    Map<String, Object> request, String traceId, int timeoutMs) {
                assertThat(connection.get("accessConfig")).isEqualTo(Map.of("secretReference", "config://db/password"));
                assertThat(endpoint).isEqualTo("company.lookup");
                assertThat(request).containsEntry("companyCode", "A001");
                assertThat(traceId).isEqualTo("TRACE-DB");
                assertThat(timeoutMs).isEqualTo(800);
                return Map.of("found", true);
            }
        };
        UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of(connector));
        assertThat(client.execute(Map.of("accessMode", "DATABASE", "accessConfig", Map.of("secretReference", "config://db/password")),
                "QUERY", "company.lookup", Map.of("companyCode", "A001"), "TRACE-DB", 800))
                .containsEntry("found", true);
    }

    @Test
    void httpGetSendsQueryParametersInsteadOfJsonBody() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicReference<String> trace = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            captured.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery());
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            byte[] response = "{\"code\":\"0000\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of());
            assertThat(client.execute(Map.of("accessMode", "HTTP"), "GET",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/query?existing=yes",
                    Map.of("name", "A B", "tag", List.of("one", "two")), "TRACE-GET", 1000))
                    .containsEntry("code", "0000");
            assertThat(captured.get()).startsWith("GET ").contains("existing=yes", "name=A%20B", "tag=one", "tag=two");
            assertThat(trace.get()).isEqualTo("TRACE-GET");
            assertThat(client.execute(Map.of("accessMode", "HTTP"), "PATCH",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/query",
                    Map.of("name", "updated"), "TRACE-PATCH", 1000)).containsEntry("code", "0000");
            assertThat(captured.get()).startsWith("PATCH ");
        } finally { server.stop(0); }
    }
}
