package com.jsnx.edsp.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Restores JSON scalar types lost when an ESB XML request is parsed into text nodes. */
public final class XmlRequestTypeSupport {
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    private XmlRequestTypeSupport() {}

    public static Map<String, Object> coerce(Map<String, Object> request, Map<String, Object> example) {
        if (request == null) return new LinkedHashMap<>();
        if (example == null || example.isEmpty()) return new LinkedHashMap<>(request);
        return coerceObject(request, example, "");
    }

    private static Map<String, Object> coerceObject(Map<?, ?> request, Map<?, ?> example, String path) {
        Map<String, Object> result = new LinkedHashMap<>();
        request.forEach((key, value) -> {
            String name = String.valueOf(key);
            String childPath = path.isEmpty() ? name : path + "." + name;
            result.put(name, coerceValue(value, example.get(name), childPath));
        });
        return result;
    }

    private static Object coerceValue(Object value, Object expected, String path) {
        if (value == null || expected == null) return value;
        if (expected instanceof Map<?, ?> expectedMap) {
            if (value instanceof Map<?, ?> actualMap) return coerceObject(actualMap, expectedMap, path);
            throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为对象");
        }
        if (expected instanceof List<?> expectedList) {
            List<?> values = value instanceof List<?> list ? list : List.of(value);
            if (expectedList.isEmpty()) return new ArrayList<>(values);
            List<Object> result = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                result.add(coerceValue(values.get(index), expectedList.get(0), path + "[" + index + "]"));
            }
            return result;
        }
        if (expected instanceof Number) {
            if (value instanceof Number) return value;
            if (!(value instanceof String raw)) throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为数字");
            String text = raw.trim();
            if (!JSON_NUMBER.matcher(text).matches()) {
                throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为数字");
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为数字", error);
            }
        }
        if (expected instanceof Boolean) {
            if (value instanceof Boolean) return value;
            if (!(value instanceof String raw)) throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为布尔值 true 或 false");
            String text = raw.trim();
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
            throw new IllegalArgumentException("ESB 请求字段 " + path + " 应为布尔值 true 或 false");
        }
        return value;
    }
}
