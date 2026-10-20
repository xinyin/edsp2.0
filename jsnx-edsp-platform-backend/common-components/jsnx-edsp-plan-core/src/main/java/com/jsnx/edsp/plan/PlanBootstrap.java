package com.jsnx.edsp.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Converts the Admin diagnostic representation into the lightweight app-local plan. */
public final class PlanBootstrap {
    private PlanBootstrap() {}

    public static PlanSnapshot fromAdmin(Map<String, Object> body, ObjectMapper mapper) {
        Map<String, Object> summary = body.get("summary") instanceof Map<?, ?> value
                ? mapper.convertValue(value, new TypeReference<Map<String, Object>>() {}) : Map.of();
        return new PlanSnapshot(
                number(body.get("interfaceId")), String.valueOf(body.get("interfaceCode")), (int) number(body.get("version")),
                String.valueOf(body.getOrDefault("checksum", "")), String.valueOf(summary.getOrDefault("upstreamUrl", "")),
                (int) number(summary.getOrDefault("effectiveTimeoutMs", 3000)),
                list(body.get("requestMappings"), mapper), list(body.get("responseMappings"), mapper),
                list(body.get("responseCodes"), mapper), String.valueOf(body.getOrDefault("vendorCodePath", "result.code")),
                map(body.get("securityPolicy"), mapper), Instant.now(),
                String.valueOf(body.getOrDefault("upstreamHttpMethod", "POST")),
                map(body.get("providerConnection"), mapper), map(body.get("responsePolicy"), mapper));
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
    private static List<Map<String, Object>> list(Object value, ObjectMapper mapper) {
        return value == null ? List.of() : mapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {});
    }
    private static Map<String, Object> map(Object value, ObjectMapper mapper) {
        return value == null ? Map.of() : mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }
}
