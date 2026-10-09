package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.List;

/** Data-source-local HTTP adapter used by each independently deployed application. */
public class UpstreamClient {
    private final ObjectMapper mapper;

    public UpstreamClient(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> post(String url, Map<String, Object> body, String traceId, int timeoutMs) {
        try {
            Map<String, Object> response = RestClient.builder().requestFactory(factory(timeoutMs)).build().post().uri(url)
                    .header("X-Trace-Id", traceId == null ? "" : traceId)
                    .contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                    .retrieve().body(Map.class);
            if (response == null) throw new IllegalStateException("上游返回空响应");
            return response;
        } catch (RestClientResponseException e) {
            Map<String, Object> errorBody;
            try { errorBody = mapper.readValue(e.getResponseBodyAsByteArray(), Map.class); }
            catch (Exception ignored) { errorBody = Map.of("message", e.getResponseBodyAsString()); }
            throw new UpstreamCallException(e.getStatusCode().value(), errorBody, e);
        } catch (Exception e) {
            throw new IllegalStateException("数据源应用调用上游失败：" + e.getMessage(), e);
        }
    }

    public static final class UpstreamCallException extends RuntimeException {
        private final int statusCode;
        private final Map<String, Object> responseBody;

        public UpstreamCallException(int statusCode, Map<String, Object> responseBody, Throwable cause) {
            super(describe(statusCode, responseBody), cause);
            this.statusCode = statusCode;
            this.responseBody = responseBody == null ? Map.of() : Map.copyOf(responseBody);
        }

        public int statusCode() { return statusCode; }
        public Map<String, Object> responseBody() { return responseBody; }

        private static String describe(int statusCode, Map<String, Object> body) {
            String detail = null;
            Object result = body == null ? null : body.get("result");
            if (result instanceof Map<?, ?> resultMap && resultMap.get("message") != null) {
                detail = String.valueOf(resultMap.get("message"));
            } else if (body != null && body.get("message") != null) {
                detail = String.valueOf(body.get("message"));
            }
            Object errors = body == null ? null : body.get("errors");
            if (errors instanceof List<?> list && !list.isEmpty()) {
                detail = (detail == null || detail.isBlank() ? "" : detail + "：")
                        + String.join("；", list.stream().map(String::valueOf).toList());
            }
            return "上游返回 HTTP " + statusCode
                    + (detail == null || detail.isBlank() ? "" : "：" + detail);
        }
    }

    private org.springframework.http.client.ClientHttpRequestFactory factory(int timeoutMs) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(100, timeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(100, timeoutMs)));
        return requestFactory;
    }
}
