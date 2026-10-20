package com.jsnx.edsp.contracts;

import java.util.Map;

/** Stable wire contracts shared by data applications and capability-runtime. */
public final class CapabilityContracts {
    private CapabilityContracts() {}

    public static String ensureTraceId(String traceId) {
        return traceId == null || traceId.isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "") : traceId;
    }

    public record PrepareRequest(String interfaceCode, Map<String, Object> payload) {}
    public record PrepareResponse(String interfaceCode, int planVersion, String upstreamUrl,
                                  int timeoutMs, Map<String, Object> upstreamRequest) {}
    public record CompleteRequest(String interfaceCode, Map<String, Object> upstreamResponse) {}
    public record CompleteResponse(String interfaceCode, int planVersion, String standardCode,
                                   String resultStatus, boolean billable, Map<String, Object> data,
                                   Map<String, Object> responseFields) {
        public CompleteResponse(String interfaceCode, int planVersion, String standardCode,
                                String resultStatus, boolean billable, Map<String, Object> data) {
            this(interfaceCode, planVersion, standardCode, resultStatus, billable, data, Map.of("data", data));
        }
    }
    public record StandardResponse<T>(String code, String message, String resultStatus, String traceId, T data) {
        public static <T> StandardResponse<T> success(String traceId, T data) {
            return new StandardResponse<>("EXT-SUCCESS", "查询成功", "HIT", traceId, data);
        }
        public static <T> StandardResponse<T> failure(String traceId, String code, String message) {
            return new StandardResponse<>(code, message, "ERROR", traceId, null);
        }
    }
}
