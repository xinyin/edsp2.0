package com.jsnx.edsp.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.transform.FieldTransformSupport;

/** Response payload and outcome decisions are independent of field mappings. */
public final class ResponsePolicySupport {
    private static final Set<String> RESERVED = Set.of("code", "message", "resultStatus", "traceId",
            "msg", "response_code", "resp_code", "status");
    private static final Set<String> ASSIGNABLE = Set.of("code", "message");
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
                                            SecretReferenceResolver secrets) {
        Object data = fields(upstream, mappings, policy, secrets).get("data");
        if (data instanceof Map<?, ?> values) {
            Map<String, Object> result = new LinkedHashMap<>();
            values.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    public static Map<String, Object> fields(Map<String, Object> upstream, List<Map<String, Object>> mappings,
                                              Map<String, Object> policy, SecretReferenceResolver secrets) {
        return fieldsForOutcome(upstream, mappings, policy, "HIT", secrets);
    }

    /** Envelope values apply to every valid supplier response; business mappings apply only to HIT. */
    public static Map<String, Object> fieldsForOutcome(Map<String, Object> upstream, List<Map<String, Object>> mappings,
                                              Map<String, Object> policy, String resultStatus, SecretReferenceResolver secrets) {
        String mode = String.valueOf(normalize(policy, mappings != null && !mappings.isEmpty()).get("dataMode"));
        boolean includeData = "HIT".equals(resultStatus);
        if ("WHOLE_BODY".equals(mode)) return includeData ? Map.of("data", new LinkedHashMap<>(upstream)) : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        if (mappings == null) return result;
        validateTargets(mappings);
        for (Map<String, Object> mapping : mappings) {
            String target = targetPath(String.valueOf(mapping.getOrDefault("targetPath", "")));
            if (!includeData && !ASSIGNABLE.contains(target)) continue;
            Object value;
            if ("WHOLE_BODY".equals(mapping.get("transformType"))) {
                if (!"$".equals(mapping.get("sourcePath"))) throw new IllegalArgumentException("完整报文来源必须为 $");
                value = new LinkedHashMap<>(upstream);
            } else {
                Map<String, Object> single = new LinkedHashMap<>(mapping);
                single.put("targetPath", target);
                Map<String, Object> mapped = PlanMappingSupport.apply(upstream, List.of(single), secrets);
                value = FieldTransformSupport.readPath(mapped, target);
            }
            if (ASSIGNABLE.contains(target) && value != null) {
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                    throw new IllegalArgumentException("平台响应字段 $." + target + " 只能赋予文本、数字或布尔值");
                }
                // Optional missing/blank values retain the platform's default code/message.
                if (String.valueOf(value).isBlank()) continue;
                value = String.valueOf(value);
            }
            if (value != null) FieldTransformSupport.writePath(result, target, value);
        }
        return result;
    }

    public static String targetPath(String raw) {
        String path = raw == null ? "" : raw.trim();
        boolean root = path.startsWith("$.");
        if (root) path = path.substring(2);
        else if (!path.equals("data") && !path.startsWith("data.")) path = "data." + path;
        if (path.isBlank() || path.endsWith(".") || !path.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw new IllegalArgumentException("无效的平台响应目标路径：" + raw);
        }
        if (RESERVED.contains(path.split("\\.")[0]) && !ASSIGNABLE.contains(path)) {
            throw new IllegalArgumentException("不能覆盖平台运行字段或将标准字段改为对象：" + raw);
        }
        return path;
    }

    public static void validateTargets(List<Map<String, Object>> mappings) {
        for (Map<String, Object> mapping : mappings) {
            String target = targetPath(String.valueOf(mapping.get("targetPath")));
            if (ASSIGNABLE.contains(target) && "WHOLE_BODY".equals(mapping.get("transformType"))) {
                throw new IllegalArgumentException("完整报文不能写入平台文本字段：$." + target);
            }
        }
        List<String> paths = mappings.stream().map(m -> targetPath(String.valueOf(m.get("targetPath")))).toList();
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                if (paths.get(i).equals(paths.get(j)) || paths.get(i).startsWith(paths.get(j) + ".")
                        || paths.get(j).startsWith(paths.get(i) + ".")) {
                    throw new IllegalArgumentException("响应映射目标路径冲突：" + paths.get(i) + " / " + paths.get(j));
                }
            }
        }
    }

    private static String text(Map<String, Object> policy, String key, String fallback) {
        Object value = policy == null ? null : policy.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
