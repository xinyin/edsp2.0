package com.jsnx.edsp.log;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class SensitiveDataMasker {
    private SensitiveDataMasker() {}

    public static Map<String, Object> mask(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        source.forEach((key, value) -> result.put(key, sensitive(key) ? "***" : value));
        return result;
    }

    private static boolean sensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("api-key")
                || normalized.contains("apikey") || normalized.contains("authorization");
    }
}
