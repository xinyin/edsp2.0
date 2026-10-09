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
        String upstreamUrl = text(payload.get("upstreamUrl"));
        if (!(upstreamUrl.startsWith("http://") || upstreamUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("草稿联调缺少有效的上游 HTTP 地址");
        }
        int timeoutMs = timeout(payload.get("timeoutMs"));
        Map<String, Object> requestBody = payload.get("requestBody") instanceof Map<?, ?> body
                ? new LinkedHashMap<>((Map<String, Object>) body) : Map.of();
        String traceId = text(payload.get("traceId"));
        Map<String, Object> upstreamResponse;
        try {
            upstreamResponse = upstream.post(upstreamUrl, requestBody, traceId, timeoutMs);
        } catch (UpstreamClient.UpstreamCallException error) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("success", false);
            failed.put("traceId", traceId);
            failed.put("dataSourceCode", expectedDataSourceCode);
            failed.put("interfaceCode", text(payload.get("interfaceCode")));
            failed.put("upstreamStatus", error.statusCode());
            failed.put("upstreamError", error.responseBody());
            return failed;
        }
        if (upstreamResponse == null) throw new IllegalStateException("上游返回空响应");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("traceId", traceId);
        result.put("dataSourceCode", expectedDataSourceCode);
        result.put("interfaceCode", text(payload.get("interfaceCode")));
        result.put("upstreamResponse", upstreamResponse);
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
