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
}
