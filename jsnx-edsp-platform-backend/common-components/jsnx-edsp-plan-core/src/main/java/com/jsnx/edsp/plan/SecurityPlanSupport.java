package com.jsnx.edsp.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.transform.FieldTransformSupport;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** One security pipeline shared by draft tests and independently deployed data applications. */
public final class SecurityPlanSupport {
    private SecurityPlanSupport() {}

    public static Map<String, Object> apply(Map<String, Object> request,
                                             Map<String, Object> policy,
                                             SecretReferenceResolver secrets,
                                             ObjectMapper mapper) {
        if (policy == null || !Boolean.parseBoolean(String.valueOf(policy.getOrDefault("enabled", false)))) {
            return mapper.convertValue(request, new TypeReference<Map<String, Object>>() {});
        }
        Map<String, Object> secured = mapper.convertValue(request, new TypeReference<Map<String, Object>>() {});
        String encryptMode = text(policy.getOrDefault("encryptMode", "NONE"));
        String signatureMode = text(policy.getOrDefault("signatureMode", "NONE"));
        String encryptionKeyReference = text(policy.get("encryptionKeyReference"));
        String signingKeyReference = text(policy.get("signingKeyReference"));
        String signatureField = text(policy.getOrDefault("signatureField", "signature"));

        if (!"NONE".equalsIgnoreCase(encryptMode)) {
            SecurityAlgorithm algorithm = SecurityAlgorithmRegistry.require(encryptMode, "ENCRYPT");
            Arrays.stream(text(policy.get("requestFields")).split(","))
                    .map(String::trim).filter(field -> !field.isBlank()).forEach(field -> {
                        Object value = FieldTransformSupport.readPath(secured, field);
                        if (value != null) FieldTransformSupport.writePath(secured, field,
                                algorithm.execute(String.valueOf(value), Map.of(
                                        "keyReference", encryptionKeyReference, "aad", field,
                                        "outputEncoding", "BASE64"), secrets));
                    });
        }
        if (!"NONE".equalsIgnoreCase(signatureMode)) {
            String canonical = canonicalJson(secured, mapper);
            if (signatureField.isBlank()) throw new IllegalArgumentException("签名输出字段不能为空");
            FieldTransformSupport.writePath(secured, signatureField,
                    SecurityAlgorithmRegistry.require(signatureMode, "SIGN").execute(canonical,
                            Map.of("keyReference", signingKeyReference, "outputEncoding", "HEX"), secrets));
        }
        return secured;
    }

    private static String canonicalJson(Map<String, Object> value, ObjectMapper source) {
        try {
            ObjectMapper canonical = source.copy()
                    .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("签名报文规范化失败", e);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
