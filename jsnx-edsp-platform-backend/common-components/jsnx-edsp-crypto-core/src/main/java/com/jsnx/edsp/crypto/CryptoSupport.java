package com.jsnx.edsp.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.KeyFactory;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.X509EncodedKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class CryptoSupport {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private CryptoSupport() {}

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String mask(String value) {
        if (value == null || value.length() < 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * Authenticated AES encryption. The key must be a Base64 encoded 256-bit key.
     * Result format is versioned so ciphertext can be migrated without guessing.
     */
    public static String aes256GcmEncrypt(String value, String base64Key, String aad) {
        byte[] key = decodeAes256Key(base64Key);
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isBlank()) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "v1." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM 加密失败", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    public static String aes256GcmDecrypt(String encoded, String base64Key, String aad) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalArgumentException("不支持的 AES-GCM 密文格式");
        }
        byte[] key = decodeAes256Key(base64Key);
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[1]);
            if (iv.length != GCM_IV_BYTES) throw new IllegalArgumentException("AES-GCM IV 长度非法");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isBlank()) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(decoder.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("AES-256-GCM 解密或完整性校验失败", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    public static String sm4GcmEncrypt(String value, String base64Key, String aad) {
        byte[] key = decodeKey(base64Key, 16, "SM4");
        byte[] iv = new byte[GCM_IV_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "SM4"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && !aad.isBlank()) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "sm4gcm.v1." + encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SM4-GCM 加密失败", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** Encrypts a field using a Base64 DER X.509 SM2 public key. */
    public static String sm2Encrypt(String value, String base64PublicKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            var publicKey = KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Cipher cipher = Cipher.getInstance("SM2", BouncyCastleProvider.PROVIDER_NAME);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, SECURE_RANDOM);
            return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("SM2 加密失败，请检查 X.509 公钥", e);
        }
    }

    private static byte[] decodeAes256Key(String base64Key) {
        return decodeKey(base64Key, 32, "AES-256");
    }

    private static byte[] decodeKey(String base64Key, int expectedBytes, String algorithm) {
        if (base64Key == null || base64Key.isBlank()) throw new IllegalArgumentException(algorithm + " 密钥不能为空");
        try {
            byte[] key = Base64.getDecoder().decode(base64Key);
            if (key.length != expectedBytes) throw new IllegalArgumentException(
                    algorithm + " 密钥必须为 " + expectedBytes + " 字节的 Base64 值");
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(algorithm + " 密钥格式错误", e);
        }
    }
}
