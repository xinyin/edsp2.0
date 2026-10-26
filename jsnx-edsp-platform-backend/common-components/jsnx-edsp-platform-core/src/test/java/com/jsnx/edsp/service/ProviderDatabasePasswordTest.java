package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.crypto.CryptoSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProviderDatabasePasswordTest {
    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    @SuppressWarnings("unchecked")
    @Test void encryptsInputMasksApiResponseAndKeepsPasswordOnEmptyEdit() {
        ObjectMapper mapper = new ObjectMapper();
        InterfaceService service = new InterfaceService(mock(JdbcTemplate.class), new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment().withProperty("EDSP_DATABASE_CREDENTIAL_KEY", KEY));
        Map<String, Object> stored = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service,
                "normalizeAccessConfig", "DATABASE", Map.of("username", "reader", "password", "db-secret"), null);
        assertThat(stored).doesNotContainKeys("password", "secretReference");
        String ciphertext = String.valueOf(stored.get("passwordCiphertext"));
        assertThat(ciphertext).startsWith("v1.");
        assertThat(CryptoSupport.aes256GcmDecrypt(ciphertext, KEY, "edsp-provider-database")).isEqualTo("db-secret");

        Map<String, Object> visible = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service,
                "publicProviderAccessConfig", stored);
        assertThat(visible).containsEntry("passwordConfigured", true).doesNotContainKeys("passwordCiphertext", "password");

        Map<String, Object> unchanged = (Map<String, Object>) ReflectionTestUtils.invokeMethod(service,
                "normalizeAccessConfig", "DATABASE", Map.of("username", "reader", "password", ""), stored);
        assertThat(unchanged.get("passwordCiphertext")).isEqualTo(ciphertext);
    }
}
