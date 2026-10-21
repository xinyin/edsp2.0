package com.jsnx.edsp.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.crypto.CryptoSupport;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
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
    void esbRequestRestoresNestedNumberAndBooleanTypesWithoutChangingTheParsedXml() {
        Map<String, Object> request = Map.of("data", Map.of("price", "59.99", "in_stock", "true",
                "name", "Wireless Headphones", "extra", "kept"));
        Map<String, Object> example = Map.of("data", Map.of("price", 59.99, "in_stock", true,
                "name", "Wireless Headphones"));

        Map<String, Object> converted = XmlRequestTypeSupport.coerce(request, example);
        Map<?, ?> data = (Map<?, ?>) converted.get("data");
        assertEquals(new BigDecimal("59.99"), data.get("price"));
        assertEquals(true, data.get("in_stock"));
        assertEquals("kept", data.get("extra"));
        assertEquals("59.99", ((Map<?, ?>) request.get("data")).get("price"));
    }

    @Test
    void esbRequestRejectsInvalidTypedValuesAndConvertsArrayItems() {
        Map<String, Object> example = Map.of("items", List.of(Map.of("price", 1, "active", true)));
        Map<String, Object> converted = XmlRequestTypeSupport.coerce(Map.of("items", List.of(
                Map.of("price", "1", "active", "false"), Map.of("price", "2", "active", "true"))), example);
        List<?> items = (List<?>) converted.get("items");
        assertEquals(new BigDecimal("2"), ((Map<?, ?>) items.get(1)).get("price"));
        assertEquals(false, ((Map<?, ?>) items.get(0)).get("active"));
        Map<String, Object> oneItem = XmlRequestTypeSupport.coerce(
                Map.of("items", Map.of("price", "1", "active", "true")), example);
        assertEquals(1, ((List<?>) oneItem.get("items")).size());
        assertThrows(IllegalArgumentException.class, () -> XmlRequestTypeSupport.coerce(
                Map.of("items", List.of(Map.of("price", "abc", "active", "true"))), example));
        assertThrows(IllegalArgumentException.class, () -> XmlRequestTypeSupport.coerce(
                Map.of("items", List.of(Map.of("price", "1", "active", "yes"))), example));
    }

    @Test
    void adminBootstrapIncludesThePublishedUpstreamRequestExample() {
        Map<String, Object> example = Map.of("data", Map.of("price", 59.99, "in_stock", true));
        PlanSnapshot plan = PlanBootstrap.fromAdmin(Map.of("interfaceId", 12, "interfaceCode", "PRODUCTS",
                "version", 1, "summary", Map.of("upstreamUrl", "https://provider/query"),
                "upstreamRequestExample", example), new ObjectMapper());
        assertEquals(example, plan.upstreamRequestExample());
    }

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
        assertEquals("code", ResponsePolicySupport.targetPath("$.code"));
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.targetPath("$.traceId"));
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
    void standardEnvelopeAllowsScalarAssignmentAndStillProtectsRuntimeFields() {
        assertEquals("message", ResponsePolicySupport.targetPath("$.message"));
        assertEquals("data.code", ResponsePolicySupport.targetPath("code"));
        for (String path : List.of("$.traceId", "$.resultStatus", "$.code.child", "$.message.child")) {
            assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.targetPath(path));
        }
        Map<String, Object> rule = Map.of("sourcePath", "result", "targetPath", "$.code", "transformType", "DIRECT");
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.fields(
                Map.of("result", Map.of("code", "ok")), List.of(rule), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.fields(
                Map.of("result", List.of("ok")), List.of(rule), Map.of(), null));
        assertEquals(Map.of("code", "200"), ResponsePolicySupport.fields(
                Map.of("result", 200), List.of(rule), Map.of(), null));
        assertEquals(Map.of(), ResponsePolicySupport.fields(Map.of(), List.of(rule), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.validateTargets(List.of(
                Map.of("sourcePath", "$", "targetPath", "$.message", "transformType", "WHOLE_BODY"))));
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.validateTargets(List.of(rule, rule)));
    }

    @Test
    void missAndErrorApplyEnvelopeRulesWithoutEvaluatingRequiredBusinessMappings() {
        List<Map<String, Object>> rules = List.of(
                Map.of("sourcePath", "status", "targetPath", "$.code", "transformType", "DIRECT"),
                Map.of("sourcePath", "$", "targetPath", "$.message", "transformType", "CONSTANT",
                        "transformConfig", Map.of("value", "固定说明")),
                Map.of("sourcePath", "absent", "targetPath", "$.data.name", "transformType", "DIRECT", "required", true));
        for (String status : List.of("MISS", "ERROR")) {
            assertEquals(Map.of("code", "404", "message", "固定说明"), ResponsePolicySupport.fieldsForOutcome(
                    Map.of("status", 404), rules, Map.of(), status, null));
        }
        assertThrows(IllegalArgumentException.class, () -> ResponsePolicySupport.fieldsForOutcome(
                Map.of("status", 200), rules, Map.of(), "HIT", null));
    }

    @Test
    void wholeBodyMappingCanBeCombinedWithEnvelopeAssignments() {
        Map<String, Object> upstream = Map.of("result", Map.of("code", "200", "message", "success"),
                "data", List.of(Map.of("id", 1)));
        List<Map<String, Object>> rules = List.of(
                Map.of("sourcePath", "$", "targetPath", "$.data", "transformType", "WHOLE_BODY"),
                Map.of("sourcePath", "$.result.code", "targetPath", "$.code", "transformType", "DIRECT"),
                Map.of("sourcePath", "$.result.message", "targetPath", "$.message", "transformType", "DIRECT"));
        assertEquals(Map.of("data", upstream, "code", "200", "message", "success"),
                ResponsePolicySupport.fieldsForOutcome(upstream, rules, Map.of("dataMode", "FIELD_MAPPING"), "HIT", null));
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
