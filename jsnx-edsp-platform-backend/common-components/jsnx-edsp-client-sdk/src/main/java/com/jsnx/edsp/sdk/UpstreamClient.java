package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.ServiceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

/** Provider-local protocol dispatch used by each independently deployed application. */
public class UpstreamClient {
    private final ObjectMapper mapper;
    private final Map<String, ProviderConnector> connectors;

    public UpstreamClient(ObjectMapper mapper) {
        List<ProviderConnector> extensions = new java.util.ArrayList<>();
        extensions.add(new DatabaseProviderConnector(key -> {
            String system = System.getProperty(key);
            return system == null ? System.getenv(key) : system;
        }));
        extensions.addAll(ServiceLoader.load(ProviderConnector.class).stream().map(ServiceLoader.Provider::get).toList());
        this.mapper = mapper;
        Map<String, ProviderConnector> registered = new LinkedHashMap<>();
        for (ProviderConnector connector : extensions) {
            String mode = connector.accessMode().trim().toUpperCase(Locale.ROOT);
            if (registered.putIfAbsent(mode, connector) != null) throw new IllegalArgumentException("重复的提供商连接器：" + mode);
        }
        this.connectors = Map.copyOf(registered);
    }

    public UpstreamClient(ObjectMapper mapper, List<ProviderConnector> extensions) {
        this.mapper = mapper;
        Map<String, ProviderConnector> registered = new LinkedHashMap<>();
        for (ProviderConnector connector : extensions) {
            String mode = connector.accessMode().trim().toUpperCase(Locale.ROOT);
            if (registered.putIfAbsent(mode, connector) != null) {
                throw new IllegalArgumentException("重复的提供商连接器：" + mode);
            }
        }
        this.connectors = Map.copyOf(registered);
    }

    public Map<String, Object> execute(Map<String, Object> connection, String method, String url,
                                       Map<String, Object> body, String traceId, int timeoutMs) {
        Map<String, Object> config = connection == null ? Map.of() : connection;
        String mode = String.valueOf(config.getOrDefault("accessMode", "HTTP")).toUpperCase(Locale.ROOT);
        ProviderConnector connector = connectors.get(mode);
        if (connector != null) return connector.execute(config, method, url, body, traceId, timeoutMs);
        if (!"HTTP".equals(mode)) {
            throw new IllegalStateException("当前资源提供商应用未安装 " + mode + " 连接器；禁止回退为 HTTP 调用");
        }
        String normalized = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        Map<String, String> headers = requestHeaders(config.get("requestHeaders"));
        if ("POST".equals(normalized) && headers.isEmpty()) return post(url, body, traceId, timeoutMs);
        return exchange(normalized, url, body, traceId, timeoutMs, headers);
    }

    public Map<String, Object> post(String url, Map<String, Object> body, String traceId, int timeoutMs) {
        return exchange("POST", url, body, traceId, timeoutMs, Map.of());
    }

    private Map<String, Object> exchange(String method, String url, Map<String, Object> body, String traceId,
                                         int timeoutMs, Map<String, String> headers) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("缺少有效的上游 HTTP 地址");
        }
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("不支持的上游 HTTP 方法：" + method);
        }
        try {
            Map<String, Object> values = body == null ? Map.of() : body;
            java.net.URI target;
            if ("GET".equals(method)) {
                var builder = UriComponentsBuilder.fromUriString(url);
                values.forEach((key, value) -> {
                    if (value instanceof Map<?, ?>) throw new IllegalArgumentException("GET 查询参数不支持嵌套对象：" + key);
                    if (value instanceof Iterable<?> items) items.forEach(item -> {
                        if (item instanceof Map<?, ?> || item instanceof Iterable<?>) {
                            throw new IllegalArgumentException("GET 查询参数不支持嵌套集合：" + key);
                        }
                        builder.queryParam(key, item);
                    });
                    else if (value != null) builder.queryParam(key, value);
                });
                target = builder.build().encode().toUri();
            } else target = java.net.URI.create(url);
            var request = RestClient.builder().requestFactory(factory(timeoutMs)).build()
                    .method(HttpMethod.valueOf(method)).uri(target)
                    .header("X-Trace-Id", traceId == null ? "" : traceId);
            headers.forEach(request::header);
            if (!"GET".equals(method)) request.contentType(MediaType.APPLICATION_JSON).body(values);
            Map<String, Object> response = request.retrieve().body(Map.class);
            if (response == null) throw new IllegalStateException("上游返回空响应");
            return response;
        } catch (RestClientResponseException e) {
            Map<String, Object> errorBody;
            try { errorBody = mapper.readValue(e.getResponseBodyAsByteArray(), Map.class); }
            catch (Exception ignored) { errorBody = Map.of("message", e.getResponseBodyAsString()); }
            throw new UpstreamCallException(e.getStatusCode().value(), errorBody, e);
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                        || cause.getMessage() != null && cause.getMessage().toLowerCase(Locale.ROOT).contains("timed out")) {
                    throw new UpstreamTransportException("连接上游超时（" + timeoutMs
                            + "ms）；请检查处理应用到供应商的网络连通性，或调整接口超时时间", e);
                }
            }
            throw new IllegalStateException("数据源应用调用上游失败：" + e.getMessage(), e);
        }
    }

    public static final class UpstreamTransportException extends RuntimeException {
        public UpstreamTransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Map<String, String> requestHeaders(Object configured) {
        if (!(configured instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> resolved = new LinkedHashMap<>();
        values.forEach((key, raw) -> {
            String name = String.valueOf(key);
            String value = String.valueOf(raw);
            if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}") || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0 || List.of("host", "content-length", "content-type",
                    "transfer-encoding", "connection", "x-trace-id").contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("上游请求头配置不合法：" + name);
            }
            if (value.startsWith("${env:") && value.endsWith("}")) {
                String variable = value.substring(6, value.length() - 1);
                if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("请求头环境变量引用格式不合法：" + name);
                }
                value = System.getenv(variable);
                if (value == null || value.isBlank()) throw new IllegalStateException("上游请求头缺少环境变量：" + variable);
            }
            resolved.put(name, value);
        });
        return resolved;
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
        Duration timeout = Duration.ofMillis(Math.max(100, Math.min(120_000, timeoutMs)));
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        // HttpURLConnection cannot issue PATCH on JDK17. Use the JDK HTTP client
        // so the advertised method is the method actually sent to the provider.
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }
}
