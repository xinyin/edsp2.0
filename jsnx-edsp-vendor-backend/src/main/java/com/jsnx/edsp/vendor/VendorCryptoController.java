package com.jsnx.edsp.vendor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

@RestController
public class VendorCryptoController {
    private static final String FIELD = "customer_id";
    private final String testKey;

    public VendorCryptoController(@Value("${vendor.crypto.test-key}") String testKey) {
        this.testKey = testKey;
    }

    @PostMapping("/vendor/crypto/aes-field")
    public ResponseEntity<Map<String, Object>> verifyEncryptedField(@RequestBody Map<String, Object> request) {
        Object value = request.get(FIELD);
        if (!(value instanceof String ciphertext) || ciphertext.isBlank()) {
            return invalid("customer_id 必须为 AES-256-GCM 密文");
        }
        final String customerId;
        try {
            customerId = decrypt(ciphertext);
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            return invalid("customer_id 解密或完整性校验失败，请检查密钥和 AAD");
        }
        if (customerId.isBlank()) return invalid("customer_id 解密后不能为空");
        return ResponseEntity.ok(Map.of(
                "result", Map.of("code", "0000", "message", "加密字段校验通过"),
                "data", Map.of("customer_id", customerId, "encryptionVerified", true)));
    }

    private String decrypt(String encoded) throws GeneralSecurityException {
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("密文版本无效");
        byte[] key = Base64.getDecoder().decode(testKey);
        if (key.length != 32) throw new IllegalArgumentException("测试密钥长度无效");
        try {
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            if (iv.length != 12) throw new IllegalArgumentException("IV 长度无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(FIELD.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private ResponseEntity<Map<String, Object>> invalid(String message) {
        return ResponseEntity.badRequest().body(Map.of("result", Map.of("code", "4003", "message", message)));
    }
}
