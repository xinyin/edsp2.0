package com.jsnx.edsp.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Response payload and outcome decisions are independent of field mappings. */
public final class ResponsePolicySupport {
    private ResponsePolicySupport() {}

    public static Map<String, Object> normalize(Map<String, Object> policy, boolean hasMappings) {
        Map<String, Object> result = new LinkedHashMap<>();
        String dataMode = text(policy, "dataMode", hasMappings ? "FIELD_MAPPING" : "WHOLE_BODY");
        if ("PASSTHROUGH".equals(dataMode)) dataMode = "WHOLE_BODY";
        if (!List.of("FIELD_MAPPING", "WHOLE_BODY").contains(dataMode)) {
            throw new IllegalArgumentException("不支持的响应封装模式：" + dataMode);
        }
        String codeMode = text(policy, "codeMode", "VENDOR_CODE");
        if (!List.of("VENDOR_CODE", "HTTP_SUCCESS").contains(codeMode)) {
            throw new IllegalArgumentException("不支持的结果判定模式：" + codeMode);
        }
        result.put("dataMode", dataMode);
        result.put("codeMode", codeMode);
        result.put("successCode", text(policy, "successCode", "0000"));
        return result;
    }

    public static Map<String, Object> data(Map<String, Object> upstream, List<Map<String, Object>> mappings,
                                            Map<String, Object> policy,
                                            com.jsnx.edsp.crypto.SecretReferenceResolver secrets) {
        String mode = String.valueOf(normalize(policy, mappings != null && !mappings.isEmpty()).get("dataMode"));
        if ("WHOLE_BODY".equals(mode)) return new LinkedHashMap<>(upstream);
        if (mappings == null || mappings.isEmpty()) return new LinkedHashMap<>();
        Map<String, Object> mapped = PlanMappingSupport.apply(upstream, mappings, secrets);
        Object data = mapped.get("data");
        if (data instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return mapped;
    }

    private static String text(Map<String, Object> policy, String key, String fallback) {
        Object value = policy == null ? null : policy.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
