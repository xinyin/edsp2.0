package com.jsnx.edsp.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes an unpublished draft request inside the selected data application.
 * Mapping and security have already been applied by Admin; the data application
 * owns only the isolated outbound HTTP call and its timeout.
 */
public final class DraftAdapterExecutor {
    private DraftAdapterExecutor() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> execute(String expectedDataSourceCode,
                                               Map<String, Object> payload,
                                               UpstreamClient upstream) {
        if (payload == null) throw new IllegalArgumentException("草稿联调请求不能为空");
        String dataSourceCode = text(payload.get("dataSourceCode"));
        if (!expectedDataSourceCode.equalsIgnoreCase(dataSourceCode)) {
            throw new IllegalArgumentException("请求数据源与当前应用不匹配：" + dataSourceCode);
        }
        Map<String, Object> result = executeRequest(payload, upstream);
        result.put("dataSourceCode", expectedDataSourceCode);
        return result;
    }

    /** Execute a draft in the application owned by one provider and shared by all its data sources. */
    public static Map<String, Object> executeForProvider(String expectedProviderCode,
                                                         Map<String, Object> payload,
                                                         UpstreamClient upstream) {
        if (payload == null) throw new IllegalArgumentException("草稿联调请求不能为空");
        String providerCode = text(payload.get("providerCode"));
        if (!expectedProviderCode.equalsIgnoreCase(providerCode)) {
            throw new IllegalArgumentException("请求资源提供商与当前应用不匹配：" + providerCode);
        }
        String dataSourceCode = text(payload.get("dataSourceCode"));
        if (dataSourceCode.isBlank()) throw new IllegalArgumentException("草稿联调缺少数据源编码");
        Map<String, Object> result = executeRequest(payload, upstream);
        result.put("providerCode", expectedProviderCode);
        result.put("dataSourceCode", dataSourceCode);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> executeRequest(Map<String, Object> payload, UpstreamClient upstream) {
        String upstreamUrl = text(payload.get("upstreamUrl"));
        Map<String, Object> connection = payload.get("providerConnection") instanceof Map<?, ?> config
                ? new LinkedHashMap<>((Map<String, Object>) config) : Map.of();
        int timeoutMs = timeout(payload.get("timeoutMs"));
        Map<String, Object> requestBody = payload.get("requestBody") instanceof Map<?, ?> body
                ? new LinkedHashMap<>((Map<String, Object>) body) : Map.of();
        String traceId = text(payload.get("traceId"));
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> response = upstream.execute(connection,
                    text(payload.getOrDefault("upstreamHttpMethod", "POST")), upstreamUrl, requestBody, traceId, timeoutMs);
            if (response == null) throw new IllegalStateException("上游返回空响应");
            result.put("success", true);
            result.put("upstreamResponse", response);
        } catch (UpstreamClient.UpstreamCallException error) {
            result.put("success", false);
            result.put("upstreamStatus", error.statusCode());
            result.put("upstreamError", error.responseBody());
        }
        result.put("traceId", traceId);
        result.put("interfaceCode", text(payload.get("interfaceCode")));
        return result;
    }

    private static int timeout(Object value) {
        int parsed;
        try { parsed = Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { parsed = 3000; }
        return Math.max(100, Math.min(120_000, parsed));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
