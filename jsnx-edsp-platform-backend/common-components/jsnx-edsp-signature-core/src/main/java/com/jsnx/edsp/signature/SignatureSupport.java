package com.jsnx.edsp.signature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class SignatureSupport {
    private SignatureSupport() {}

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 签名失败", e);
        }
    }

    /** HMAC is used for shared-secret message authentication; plain SHA-256 is not a signature. */
    public static String hmacSha256(String value, String base64Key, String outputEncoding) {
        return hmac(value, base64Key, outputEncoding, "HmacSHA256", null, 32);
    }

    public static String hmacSm3(String value, String base64Key, String outputEncoding) {
        return hmac(value, base64Key, outputEncoding, "HMACSM3", BouncyCastleProvider.PROVIDER_NAME, 16);
    }

    private static String hmac(String value, String base64Key, String outputEncoding,
                               String algorithm, String provider, int minimumBytes) {
        if (base64Key == null || base64Key.isBlank()) throw new IllegalArgumentException("签名密钥不能为空");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("HMAC 密钥必须为 Base64 值", e);
        }
        if (key.length < minimumBytes) throw new IllegalArgumentException(algorithm + " 密钥长度不足");
        try {
            Mac mac = provider == null ? Mac.getInstance(algorithm) : Mac.getInstance(algorithm, provider);
            mac.init(new SecretKeySpec(key, algorithm));
            byte[] result = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "BASE64".equalsIgnoreCase(outputEncoding)
                    ? Base64.getEncoder().encodeToString(result)
                    : HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " 签名失败", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    public static String sm2Sm3Sign(String value, String base64PrivateKey, String outputEncoding) {
        return asymmetricSign(value, base64PrivateKey, outputEncoding, "SM3withSM2", "EC", BouncyCastleProvider.PROVIDER_NAME);
    }

    public static String rsaSha256Sign(String value, String base64PrivateKey, String outputEncoding) {
        return asymmetricSign(value, base64PrivateKey, outputEncoding, "SHA256withRSA", "RSA", null);
    }

    private static String asymmetricSign(String value, String base64PrivateKey, String outputEncoding,
                                         String algorithm, String keyAlgorithm, String provider) {
        try {
            byte[] encoded = Base64.getDecoder().decode(base64PrivateKey);
            KeyFactory keyFactory = provider == null ? KeyFactory.getInstance(keyAlgorithm)
                    : KeyFactory.getInstance(keyAlgorithm, provider);
            Signature signature = provider == null ? Signature.getInstance(algorithm)
                    : Signature.getInstance(algorithm, provider);
            signature.initSign(keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded)));
            signature.update(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = signature.sign();
            return "HEX".equalsIgnoreCase(outputEncoding)
                    ? HexFormat.of().formatHex(result) : Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException(algorithm + " 签名失败，请检查 PKCS#8 私钥", e);
        }
    }
}
