package com.jsnx.edsp.plan;

import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.transform.FieldTransformSupport;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlanMappingSupport {
    private PlanMappingSupport() {}

    public static Map<String, Object> apply(Map<String, Object> source,
                                             List<Map<String, Object>> mappings) {
        return apply(source, mappings, null);
    }

    public static Map<String, Object> apply(Map<String, Object> source,
                                             List<Map<String, Object>> mappings,
                                             SecretReferenceResolver secrets) {
        // An empty directional plan is an explicit pass-through contract. Keep
        // this behavior in the shared runtime so SDKs and data applications do
        // not accidentally turn a valid transparent interface into an empty
        // request or response.
        if (mappings == null || mappings.isEmpty()) {
            return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        }
        Map<String, Object> target = new LinkedHashMap<>();
        for (Map<String, Object> mapping : mappings) {
            String sourcePath = text(mapping.get("sourcePath"));
            String targetPath = text(mapping.get("targetPath"));
            if (sourcePath.isBlank() || targetPath.isBlank()) continue;
            Object value = FieldTransformSupport.readPath(source, sourcePath);
            if (value == null || String.valueOf(value).isBlank()) value = mapping.get("defaultValue");
            String transform = text(mapping.get("transformType")).toUpperCase();
            if ("CONSTANT".equals(transform)) value = config(mapping).getOrDefault("value", value);
            if ("UPPERCASE".equals(transform) && value != null) value = String.valueOf(value).toUpperCase();
            if ("LOWERCASE".equals(transform) && value != null) value = String.valueOf(value).toLowerCase();
            if ("ENUM".equals(transform) && value != null) {
                value = config(mapping).getOrDefault(String.valueOf(value),
                        mapping.getOrDefault("defaultValue", value));
            }
            if ("DATE".equals(transform) && value != null) {
                String sourceFormat = String.valueOf(config(mapping).getOrDefault("sourceFormat", "yyyy-MM-dd"));
                String targetFormat = String.valueOf(config(mapping).getOrDefault("targetFormat", "yyyyMMdd"));
                try {
                    value = DateTimeFormatter.ofPattern(targetFormat).format(
                            LocalDate.parse(String.valueOf(value), DateTimeFormatter.ofPattern(sourceFormat)));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("日期转换失败：" + sourcePath, e);
                }
            }
            if ("ENCRYPT".equals(transform) && value != null) {
                requireSecrets(secrets, sourcePath);
                String mode = text(config(mapping).get("mode"));
                value = SecurityAlgorithmRegistry.require(mode, "ENCRYPT")
                        .execute(String.valueOf(value), config(mapping), secrets);
            }
            if ("SIGN".equals(transform) && value != null) {
                requireSecrets(secrets, sourcePath);
                String mode = text(config(mapping).get("mode"));
                value = SecurityAlgorithmRegistry.require(mode, "SIGN")
                        .execute(String.valueOf(value), config(mapping), secrets);
            }
            boolean required = Boolean.parseBoolean(String.valueOf(mapping.getOrDefault("required", false)));
            if (required && (value == null || String.valueOf(value).isBlank())) {
                throw new IllegalArgumentException("必填映射字段缺失：" + sourcePath);
            }
            if (value != null) FieldTransformSupport.writePath(target, targetPath, value);
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> mapping) {
        Object value = mapping.get("transformConfig");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static void requireSecrets(SecretReferenceResolver secrets, String sourcePath) {
        if (secrets == null) throw new IllegalStateException("字段 " + sourcePath + " 使用安全转换，但运行时未配置密钥解析器");
    }
}
