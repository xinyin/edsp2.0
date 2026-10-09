package com.jsnx.edsp.crypto;

import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves key material without persisting it in interface configuration.
 * config:// values are read from Spring Environment and may therefore originate
 * from Nacos, environment variables or an external secret integration.
 */
public final class SecretReferenceResolver {
    private final Function<String, String> configLookup;

    public SecretReferenceResolver(Function<String, String> configLookup) {
        this.configLookup = Objects.requireNonNull(configLookup, "configLookup");
    }

    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("必须配置密钥引用，禁止使用明文或默认密钥");
        }
        String value;
        if (reference.startsWith("config://")) {
            value = configLookup.apply(reference.substring("config://".length()));
        } else if (reference.startsWith("env://")) {
            value = System.getenv(reference.substring("env://".length()));
        } else if (reference.startsWith("sys://")) {
            value = System.getProperty(reference.substring("sys://".length()));
        } else {
            throw new IllegalArgumentException("密钥引用仅支持 config://、env:// 或 sys://，禁止保存明文密钥");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("密钥引用无法解析：" + reference);
        }
        return value.trim();
    }
}
