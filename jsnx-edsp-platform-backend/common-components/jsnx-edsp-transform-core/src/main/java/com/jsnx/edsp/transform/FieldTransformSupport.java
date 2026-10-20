package com.jsnx.edsp.transform;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FieldTransformSupport {
    private FieldTransformSupport() {}

    @SuppressWarnings("unchecked")
    public static Object readPath(Map<String, Object> source, String path) {
        if ("$".equals(path)) return source;
        if (path.startsWith("$.")) path = path.substring(2);
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = ((Map<String, Object>) map).get(part);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    public static void writePath(Map<String, Object> target, String path, Object value) {
        if (path.startsWith("$.")) path = path.substring(2);
        String[] parts = path.split("\\.");
        Map<String, Object> current = target;
        for (int i = 0; i < parts.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(parts[i], key -> new LinkedHashMap<>());
        }
        current.put(parts[parts.length - 1], value);
    }
}
