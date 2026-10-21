package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftAdapterExecutorTest {
    @Test
    void executesDraftAgainstConfiguredUpstream() {
        UpstreamClient upstream = new UpstreamClient(new ObjectMapper()) {
            @Override
            public Map<String, Object> post(String url, Map<String, Object> body, String traceId, int timeoutMs) {
                assertThat(url).isEqualTo("http://provider.example/query");
                assertThat(body).containsEntry("company_name", "百度");
                assertThat(traceId).isEqualTo("TRACE-001");
                assertThat(timeoutMs).isEqualTo(1000);
                return Map.of("result", Map.of("code", "0000"),
                        "data", Map.of("name", "百度"));
            }
        };
        Map<String, Object> result = DraftAdapterExecutor.execute("ENTERPRISE", Map.of(
                "traceId", "TRACE-001",
                "dataSourceCode", "ENTERPRISE",
                "interfaceCode", "ENTERPRISE_BASIC",
                "upstreamUrl", "http://provider.example/query",
                "timeoutMs", 1000,
                "requestBody", Map.of("company_name", "百度")
        ), upstream);

        assertThat(result).containsEntry("traceId", "TRACE-001")
                .containsEntry("dataSourceCode", "ENTERPRISE");
        Map<?, ?> upstreamResponse = (Map<?, ?>) result.get("upstreamResponse");
        assertThat(upstreamResponse.containsKey("result")).isTrue();
        assertThat(upstreamResponse.containsKey("data")).isTrue();
    }

    @Test
    void rejectsRequestRoutedToWrongDataApplication() {
        assertThatThrownBy(() -> DraftAdapterExecutor.execute("ENTERPRISE", Map.of(
                "dataSourceCode", "CREDIT",
                "upstreamUrl", "http://127.0.0.1/query"
        ), new UpstreamClient(new ObjectMapper())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void rejectsInvalidUpstreamUrlBeforeNetworkCall() {
        assertThatThrownBy(() -> DraftAdapterExecutor.execute("ENTERPRISE", Map.of(
                "dataSourceCode", "ENTERPRISE",
                "upstreamUrl", "/relative/path"
        ), new UpstreamClient(new ObjectMapper())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP 地址");
    }

    @Test
    void providerApplicationAcceptsDifferentDataSourcesOwnedByTheSameProvider() {
        UpstreamClient upstream = new UpstreamClient(new ObjectMapper()) {
            @Override
            public Map<String, Object> post(String url, Map<String, Object> body, String traceId, int timeoutMs) {
                return Map.of("code", "0000");
            }
        };

        Map<String, Object> first = DraftAdapterExecutor.executeForProvider("BAIRONG", Map.of(
                "providerCode", "BAIRONG", "dataSourceCode", "ANTI_FRAUD",
                "upstreamUrl", "http://provider.example/anti-fraud"
        ), upstream);
        Map<String, Object> second = DraftAdapterExecutor.executeForProvider("BAIRONG", Map.of(
                "providerCode", "BAIRONG", "dataSourceCode", "CREDIT_SCORE",
                "upstreamUrl", "http://provider.example/credit-score"
        ), upstream);

        assertThat(first).containsEntry("providerCode", "BAIRONG")
                .containsEntry("dataSourceCode", "ANTI_FRAUD");
        assertThat(second).containsEntry("providerCode", "BAIRONG")
                .containsEntry("dataSourceCode", "CREDIT_SCORE");
    }

    @Test
    void originalResourcePayloadDoesNotRunStandardServiceMappingOrSecurity() {
        Map<String, Object> request = Map.of("query_key", "001", "filters", java.util.List.of(Map.of("kind", "risk")));
        Map<String, Object> response = Map.of("vendorCode", "A1", "data", java.util.List.of(Map.of("value", 0)));
        UpstreamClient upstream = new UpstreamClient(new ObjectMapper()) {
            @Override
            public Map<String, Object> execute(Map<String, Object> connection, String method, String url,
                                               Map<String, Object> body, String traceId, int timeoutMs) {
                assertThat(method).isEqualTo("PUT");
                assertThat(body).isEqualTo(request);
                assertThat(connection).containsEntry("accessMode", "HTTP");
                return response;
            }
        };
        Map<String, Object> result = DraftAdapterExecutor.executeForProvider("RAEX", Map.of(
                "providerCode", "RAEX", "dataSourceCode", "raex-risk", "interfaceCode", "RISK_QUERY",
                "upstreamUrl", "https://provider.example/risk", "upstreamHttpMethod", "PUT",
                "requestBody", request, "providerConnection", Map.of("accessMode", "HTTP"),
                "mappings", java.util.List.of(Map.of("sourcePath", "query_key", "targetPath", "other_key")),
                "securityPolicy", Map.of("enabled", true)
        ), upstream);
        assertThat(result).containsEntry("upstreamResponse", response).containsEntry("success", true);
    }

    @Test
    void providerApplicationRejectsRequestOwnedByAnotherProvider() {
        assertThatThrownBy(() -> DraftAdapterExecutor.executeForProvider("BAIRONG", Map.of(
                "providerCode", "OTHER_PROVIDER", "dataSourceCode", "ANTI_FRAUD",
                "upstreamUrl", "http://provider.example/anti-fraud"
        ), new UpstreamClient(new ObjectMapper())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源提供商");
    }

    @Test
    void connectionTimeoutReturnsStructuredFailureInsteadOfHttp500Page() {
        UpstreamClient upstream = new UpstreamClient(new ObjectMapper()) {
            @Override
            public Map<String, Object> execute(Map<String, Object> connection, String method, String url,
                                               Map<String, Object> body, String traceId, int timeoutMs) {
                throw new UpstreamTransportException("连接上游超时（3000ms）", new java.net.SocketTimeoutException());
            }
        };
        Map<String, Object> result = DraftAdapterExecutor.executeForProvider("CSSJ", Map.of(
                "providerCode", "CSSJ", "dataSourceCode", "PRD_01", "interfaceCode", "PRD_01_API_01",
                "upstreamUrl", "https://reqres.in/api/collections/products/records?project_id=55411",
                "upstreamHttpMethod", "POST", "requestBody", Map.of("data", Map.of("name", "Headphones")),
                "timeoutMs", 3000
        ), upstream);
        assertThat(result).containsEntry("success", false).containsEntry("errorType", "TRANSPORT")
                .containsEntry("errorMessage", "连接上游超时（3000ms）")
                .containsEntry("interfaceCode", "PRD_01_API_01");
    }
}
