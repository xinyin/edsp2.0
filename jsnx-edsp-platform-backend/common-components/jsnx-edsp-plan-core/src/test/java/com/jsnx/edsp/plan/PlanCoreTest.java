package com.jsnx.edsp.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.crypto.CryptoSupport;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlanCoreTest {
    private static final String KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final SecretReferenceResolver SECRETS = new SecretReferenceResolver(
            key -> "edsp.security.keys.test".equals(key) ? KEY : null);

    @Test
    void adminBootstrapPreservesProviderConnectionAndUpstreamMethod() {
        PlanSnapshot plan = PlanBootstrap.fromAdmin(Map.of(
                "interfaceId", 12, "interfaceCode", "COMPANY_QUERY", "version", 3,
                "summary", Map.of("upstreamUrl", "http://provider/query", "effectiveTimeoutMs", 800),
                "upstreamHttpMethod", "GET",
                "providerConnection", Map.of("providerCode", "BAIRONG", "accessMode", "HTTP",
                        "accessConfig", Map.of("baseUrl", "http://provider"))
        ), new ObjectMapper());
        assertEquals("GET", plan.upstreamHttpMethod());
        assertEquals("BAIRONG", plan.providerConnection().get("providerCode"));
        assertEquals(800, plan.timeoutMs());
        assertThrows(UnsupportedOperationException.class, () -> plan.providerConnection().put("accessMode", "KAFKA"));
    }

    @Test
    void adminBootstrapKeepsThePublishedResponsePolicy() {
        PlanSnapshot plan = PlanBootstrap.fromAdmin(Map.of(
                "interfaceId", 12, "interfaceCode", "QUERY", "version", 3,
                "summary", Map.of("upstreamUrl", "https://provider/query"),
                "responsePolicy", Map.of("dataMode", "WHOLE_BODY", "codeMode", "HTTP_SUCCESS", "successCode", "0000")
        ), new ObjectMapper());
        assertEquals("WHOLE_BODY", plan.responsePolicy().get("dataMode"));
        assertEquals("HTTP_SUCCESS", plan.responsePolicy().get("codeMode"));
        assertThrows(UnsupportedOperationException.class, () -> plan.responsePolicy().put("dataMode", "FIELD_MAPPING"));
    }

    @Test
    void replacesOneInterfaceAtomicallyAndMapsNestedFields() {
        PlanRegistry registry = new PlanRegistry();
        registry.activate(new PlanSnapshot(1, "ENTERPRISE_BASIC", 2, "v2", "http://upstream", 3000,
                List.of(Map.of("sourcePath", "creditCode", "targetPath", "credit_code", "transformType", "DIRECT")),
                List.of(), List.of(), Map.of(), null));
        assertEquals(2, registry.get("ENTERPRISE_BASIC").version());
        assertEquals("ABC", PlanMappingSupport.apply(Map.of("creditCode", "ABC"),
                registry.get("ENTERPRISE_BASIC").requestMappings()).get("credit_code"));
    }

    @Test
    void emptyDirectionalPlanPassesThroughTheOriginalPayload() {
        Map<String, Object> input = Map.of("company", Map.of("name", "百度"), "credit_code", "bd001");
        Map<String, Object> output = PlanMappingSupport.apply(input, List.of());
        assertEquals(input, output);
        assertNotSame(input, output);
        assertEquals(Map.of(), PlanMappingSupport.apply(null, List.of()));
    }

    @Test
    void wholeBodyResponseKeepsNestedArraysAndMetadata() {
        Map<String, Object> upstream = Map.of(
                "page", 1, "data", List.of(Map.of("id", 1, "email", "a@example.com")),
                "support", Map.of("url", "https://example.com"),
                "_meta", Map.of("variant", "v1"));
        Map<String, Object> policy = ResponsePolicySupport.normalize(
                Map.of("dataMode", "WHOLE_BODY", "codeMode", "HTTP_SUCCESS", "successCode", "0000"), false);
        Map<String, Object> data = ResponsePolicySupport.data(upstream, List.of(), policy, null);
        assertEquals(upstream, data);
        assertNotSame(upstream, data);
        assertEquals("HTTP_SUCCESS", policy.get("codeMode"));
    }

    @Test
    void wholeBodyCanBePlacedOutsideDataAndOverlappingTargetsAreRejected() {
        Map<String, Object> upstream = Map.of("data", List.of(Map.of("id", 1)), "page", 1);
        List<Map<String, Object>> rules = List.of(Map.of("sourcePath", "$", "targetPath", "$.payload.supplierResult",
                "transformType", "WHOLE_BODY"));
        Map<String, Object> result = ResponsePolicySupport.fields(upstream, rules,
                Map.of("dataMode", "FIELD_MAPPING"), null);
        assertEquals(Map.of("payload", Map.of("supplierResult", upstream)), result);
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.targetPath("$.code"));
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.validateTargets(List.of(
                rules.get(0), Map.of("sourcePath", "page", "targetPath", "$.payload.supplierResult.page"))));
    }

    @Test
    void fieldMappingResponseExtractsConfiguredFieldsOnly() {
        Map<String, Object> upstream = Map.of("data", Map.of("company", "Acme", "unused", "hidden"));
        Map<String, Object> data = ResponsePolicySupport.data(upstream,
                List.of(Map.of("sourcePath", "data.company", "targetPath", "data.name", "transformType", "DIRECT")),
                ResponsePolicySupport.normalize(Map.of("dataMode", "FIELD_MAPPING"), true), null);
        assertEquals(Map.of("name", "Acme"), data);
    }

    @Test
    void executesAesGcmFieldTransformWithReferencedKey() {
        Map<String, Object> mapping = Map.of(
                "sourcePath", "customer.id", "targetPath", "customer_id", "transformType", "ENCRYPT",
                "transformConfig", Map.of("mode", "AES_256_GCM", "keyReference", "config://edsp.security.keys.test", "aad", "customer_id"));
        String encrypted = String.valueOf(PlanMappingSupport.apply(
                Map.of("customer", Map.of("id", "320100199001011234")), List.of(mapping), SECRETS).get("customer_id"));
        assertTrue(encrypted.startsWith("v1."));
        assertEquals("320100199001011234", CryptoSupport.aes256GcmDecrypt(encrypted, KEY, "customer_id"));
    }

    @Test
    void executesHmacAndRejectsPlaintextSecretReferences() {
        Map<String, Object> mapping = Map.of(
                "sourcePath", "queryKey", "targetPath", "sign", "transformType", "SIGN",
                "transformConfig", Map.of("mode", "HMAC_SHA256", "keyReference", "config://edsp.security.keys.test", "outputEncoding", "HEX"));
        String signature = String.valueOf(PlanMappingSupport.apply(
                Map.of("queryKey", "abc"), List.of(mapping), SECRETS).get("sign"));
        assertEquals(64, signature.length());
        assertThrows(IllegalArgumentException.class, () -> new SecretReferenceResolver(key -> KEY).resolve("plaintext-key"));
    }

    @Test
    void appliesTheSameAuthenticatedSecurityPipelineToWholeRequest() {
        Map<String, Object> policy = Map.of("enabled", true, "encryptMode", "AES_256_GCM",
                "signatureMode", "HMAC_SHA256", "requestFields", "customer.id",
                "encryptionKeyReference", "config://edsp.security.keys.test",
                "signingKeyReference", "config://edsp.security.keys.test", "signatureField", "signature");
        Map<String, Object> secured = SecurityPlanSupport.apply(
                Map.of("customer", Map.of("id", "A001")), policy, SECRETS, new ObjectMapper());
        assertNotEquals("A001", ((Map<?, ?>) secured.get("customer")).get("id"));
        assertEquals(64, String.valueOf(secured.get("signature")).length());
    }

    @Test
    void publishesInternationalAndCommercialCryptographyCatalog() {
        var codes = SecurityAlgorithmRegistry.descriptors().stream()
                .map(SecurityAlgorithm.Descriptor::code).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.containsAll(java.util.Set.of(
                "AES_256_GCM", "SM4_GCM", "SM2", "HMAC_SHA256", "HMAC_SM3", "SM2_SM3", "RSA_SHA256")));
        assertTrue(codes.contains("TEST_CUSTOM_SIGN"));
        assertEquals("custom:payload", SecurityAlgorithmRegistry.require("TEST_CUSTOM_SIGN", "SIGN")
                .execute("payload", Map.of(), SECRETS));
    }

    @Test
    void executesSm4GcmAndHmacSm3ThroughTheRegistry() {
        String sm4Key = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        SecretReferenceResolver gmSecrets = new SecretReferenceResolver(key ->
                "gm.sm4".equals(key) ? sm4Key : "gm.sm3".equals(key) ? KEY : null);
        String encrypted = SecurityAlgorithmRegistry.require("SM4_GCM", "ENCRYPT").execute(
                "统一社会信用代码", Map.of("keyReference", "config://gm.sm4", "aad", "credit_code"), gmSecrets);
        String signature = SecurityAlgorithmRegistry.require("HMAC_SM3", "SIGN").execute(
                "canonical-message", Map.of("keyReference", "config://gm.sm3", "outputEncoding", "HEX"), gmSecrets);
        assertTrue(encrypted.startsWith("sm4gcm.v1."));
        assertEquals(64, signature.length());
    }
}
