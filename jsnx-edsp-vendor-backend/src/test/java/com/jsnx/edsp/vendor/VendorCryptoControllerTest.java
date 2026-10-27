package com.jsnx.edsp.vendor;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendorCryptoControllerTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private final VendorCryptoController controller = new VendorCryptoController(KEY);

    @Test
    void acceptsPlatformCompatibleAesGcmField() throws Exception {
        String encrypted = encrypt("CUST-001", "customer_id");
        var response = controller.verifyEncryptedField(Map.of("customer_id", encrypted));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("CUST-001", ((Map<?, ?>) response.getBody().get("data")).get("customer_id"));
        assertEquals(true, ((Map<?, ?>) response.getBody().get("data")).get("encryptionVerified"));
    }

    @Test
    void rejectsPlaintextAndWrongAadWithoutExposingInput() throws Exception {
        assertEquals(400, controller.verifyEncryptedField(Map.of("customer_id", "CUST-001")).getStatusCode().value());
        var response = controller.verifyEncryptedField(Map.of("customer_id", encrypt("CUST-001", "other_field")));
        assertEquals(400, response.getStatusCode().value());
        assertTrue(((Map<?, ?>) response.getBody().get("result")).get("message").toString().contains("AAD"));
    }

    private String encrypt(String value, String aad) throws Exception {
        byte[] iv = new byte[12];
        byte[] key = Base64.getDecoder().decode(KEY);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "v1." + encoder.encodeToString(iv) + "." + encoder.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
