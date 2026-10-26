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
    void databaseMetadataRequestsAreDispatchedInsideTheProviderApplication() {
        ProviderConnector connector = new ProviderConnector() {
            public String accessMode() { return "DATABASE"; }
            public Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                    Map<String, Object> request, String traceId, int timeoutMs) {
                assertThat(connection.get("resourceQuery")).isEqualTo(Map.of(
                        "executionMode", "METADATA_TABLES", "sqlTemplate", ""));
                assertThat(connection.get("accessConfig")).isEqualTo(Map.of("jdbcUrl", "jdbc:test"));
                return Map.of("tables", List.of(Map.of("name", "USER_INFO", "type", "TABLE")));
            }
        };
        UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of(connector));
        Map<String, Object> result = DraftAdapterExecutor.executeForProvider("BANK", Map.of(
                "providerCode", "BANK", "dataSourceCode", "BANK_DB", "executionMode", "METADATA_TABLES",
                "providerConnection", Map.of("accessMode", "DATABASE", "accessConfig", Map.of("jdbcUrl", "jdbc:test")),
                "timeoutMs", 3000), client);
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("upstreamResponse")).isEqualTo(Map.of("tables", List.of(Map.of("name", "USER_INFO", "type", "TABLE"))));
    }

    @Test
    void databaseConnectionTestUsesProviderConnectorWithoutSql() {
        ProviderConnector connector = new ProviderConnector() {
            public String accessMode() { return "DATABASE"; }
            public Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                    Map<String, Object> request, String traceId, int timeoutMs) {
                assertThat(connection.get("resourceQuery")).isEqualTo(Map.of(
                        "executionMode", "CONNECTION_TEST", "sqlTemplate", ""));
                assertThat(request).isEmpty();
                assertThat(timeoutMs).isEqualTo(10_000);
                return Map.of("connected", true, "databaseProduct", "MySQL");
            }
        };
        UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of(connector));
        Map<String, Object> payload = Map.of("providerCode", "BANK", "dataSourceCode", "DATABASE_CONNECTION_TEST",
                "executionMode", "CONNECTION_TEST", "timeoutMs", 10_000,
                "providerConnection", Map.of("accessMode", "DATABASE", "accessConfig", Map.of("jdbcUrl", "jdbc:test")));

        Map<String, Object> result = DraftAdapterExecutor.executeForProvider("BANK", payload, client);

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("upstreamResponse")).isEqualTo(Map.of("connected", true, "databaseProduct", "MySQL"));
        assertThatThrownBy(() -> DraftAdapterExecutor.executeForProvider("OTHER", payload, client))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不匹配");
    }

    @Test
    void connectionTestReturnsAdapterFailureWithoutExposingExceptionAsHttp500() {
        ProviderConnector connector = new ProviderConnector() {
            public String accessMode() { return "DATABASE"; }
            public Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                    Map<String, Object> request, String traceId, int timeoutMs) {
                throw new IllegalStateException("数据库连接配置不完整");
            }
        };
        UpstreamClient client = new UpstreamClient(new ObjectMapper(), List.of(connector));
        Map<String, Object> result = DraftAdapterExecutor.executeForProvider("BANK", Map.of(
                "providerCode", "BANK", "dataSourceCode", "DATABASE_CONNECTION_TEST",
                "executionMode", "CONNECTION_TEST", "providerConnection", Map.of("accessMode", "DATABASE")), client);
        assertThat(result).containsEntry("success", false).containsEntry("errorType", "ADAPTER")
                .containsEntry("errorMessage", "数据库连接配置不完整");
    }

    @Test
    void httpGetSendsQueryParametersInsteadOfJsonBody() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        AtomicReference<String> trace = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            captured.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawQuery());
            trace.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Provider-Channel"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
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
            assertThat(client.execute(Map.of("accessMode", "HTTP", "requestHeaders",
                            Map.of("X-Provider-Channel", "EDSP")), "POST",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/query",
                    Map.of("name", "test"), "TRACE-HEADER", 1000)).containsEntry("code", "0000");
            assertThat(customHeader.get()).isEqualTo("EDSP");
            assertThat(trace.get()).isEqualTo("TRACE-HEADER");
            assertThat(client.execute(Map.of("accessMode", "HTTP", "requestHeaders",
                            Map.of("X-Api-Key", "plain-test-key")), "POST",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/query",
                    Map.of("name", "test"), "TRACE-KEY", 1000)).containsEntry("code", "0000");
            assertThat(apiKey.get()).isEqualTo("plain-test-key");
        } finally { server.stop(0); }
    }
}
