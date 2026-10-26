package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jsnx.edsp.model.ApiModels.ProviderSummary;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseConnectionTest {
    private InterfaceService service(ProviderSummary provider) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        when(jdbc.queryForObject(eq("SELECT access_config FROM data_provider WHERE id=?"), eq(String.class), eq(provider.id())))
                .thenReturn("{\"jdbcUrl\":\"jdbc:mysql://db.internal:3306/test\",\"username\":\"reader\",\"secretReference\":\"env://TEST_DB_PASSWORD\"}");
        InterfaceService service = spy(new InterfaceService(
                jdbc,
                new JsonSupport(mapper), mapper, new PublishedPlanRegistry(), new MockEnvironment()));
        doReturn(provider).when(service).provider(provider.id());
        return service;
    }

    private ProviderSummary provider(String mode, String runtimeUrl) {
        return new ProviderSummary(7, "BANK", "测试资源方", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                false, false, false, "ACTIVE", mode,
                Map.of("jdbcUrl", "jdbc:mysql://db.internal:3306/test", "username", "reader",
                        "secretReference", "env://TEST_DB_PASSWORD"),
                "jsnx-edsp-data-provider-bank", runtimeUrl, 8201, 1);
    }

    @Test
    void requiresDatabaseProviderAndConfiguredApplication() {
        assertThatThrownBy(() -> service(provider("HTTP", "http://127.0.0.1:8201")).testDatabaseConnection(7))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("数据库类型");
        var result = service(provider("DATABASE", "")).testDatabaseConnection(7);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("独立应用");
    }

    @Test
    void forwardsSavedConnectionToProviderApplicationAndReadsResult() throws Exception {
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        ObjectMapper mapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/adapter/execute", exchange -> {
            payload.set(mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {}));
            byte[] response = "{\"success\":true,\"upstreamResponse\":{\"connected\":true,\"databaseProduct\":\"MySQL\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var result = service(provider("DATABASE", "http://127.0.0.1:" + server.getAddress().getPort()))
                    .testDatabaseConnection(7);
            assertThat(result.success()).isTrue();
            assertThat(result.databaseProduct()).isEqualTo("MySQL");
            assertThat(payload.get()).containsEntry("providerCode", "BANK")
                    .containsEntry("executionMode", "CONNECTION_TEST")
                    .containsEntry("dataSourceCode", "DATABASE_CONNECTION_TEST")
                    .containsEntry("requestBody", Map.of());
            Map<?, ?> connection = (Map<?, ?>) payload.get().get("providerConnection");
            assertThat(connection.get("accessMode")).isEqualTo("DATABASE");
            assertThat(connection.get("accessConfig")).isEqualTo(provider("DATABASE", "unused").accessConfig());
            assertThat(payload.get()).doesNotContainKey("sqlTemplate");
        } finally {
            server.stop(0);
        }
    }
}
