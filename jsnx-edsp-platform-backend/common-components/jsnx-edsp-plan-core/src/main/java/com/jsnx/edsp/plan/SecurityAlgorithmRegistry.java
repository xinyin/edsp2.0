package com.jsnx.edsp.plan;

import com.jsnx.edsp.crypto.CryptoSupport;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.signature.SignatureSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/** Immutable algorithm registry. External JARs register implementations through ServiceLoader. */
public final class SecurityAlgorithmRegistry {
    private static final Map<String, SecurityAlgorithm> ALGORITHMS = load();

    private SecurityAlgorithmRegistry() {}

    public static List<SecurityAlgorithm.Descriptor> descriptors() {
        return ALGORITHMS.values().stream().map(SecurityAlgorithm::descriptor).toList();
    }

    public static SecurityAlgorithm require(String code, String kind) {
        SecurityAlgorithm algorithm = ALGORITHMS.get(normalize(code));
        if (algorithm == null || !kind.equalsIgnoreCase(algorithm.descriptor().kind())) {
            throw new IllegalArgumentException("未注册的" + ("ENCRYPT".equals(kind) ? "加密" : "签名") + "算法：" + code);
        }
        return algorithm;
    }

    private static Map<String, SecurityAlgorithm> load() {
        Map<String, SecurityAlgorithm> result = new LinkedHashMap<>();
        builtIns().forEach(item -> register(result, item));
        ServiceLoader.load(SecurityAlgorithm.class).forEach(item -> register(result, item));
        return Map.copyOf(result);
    }

    private static void register(Map<String, SecurityAlgorithm> target, SecurityAlgorithm algorithm) {
        String code = normalize(algorithm.descriptor().code());
        if (code.isBlank()) throw new IllegalStateException("安全算法编码不能为空");
        if (target.putIfAbsent(code, algorithm) != null) throw new IllegalStateException("安全算法编码重复：" + code);
    }

    private static List<SecurityAlgorithm> builtIns() {
        List<SecurityAlgorithm> result = new ArrayList<>();
        result.add(algorithm("AES_256_GCM", "AES-256-GCM", "ENCRYPT", "国际算法",
                "带完整性校验的认证加密，适合敏感字段。", encryptionParameters(true),
                (value, config, secrets) -> CryptoSupport.aes256GcmEncrypt(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "aad"))));
        result.add(algorithm("SM4_GCM", "SM4-GCM", "ENCRYPT", "国密算法",
                "国密 SM4 认证加密，128 位密钥。", encryptionParameters(true),
                (value, config, secrets) -> CryptoSupport.sm4GcmEncrypt(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "aad"))));
        result.add(algorithm("SM2", "SM2 公钥加密", "ENCRYPT", "国密算法",
                "使用供应商 SM2 X.509 公钥加密短字段。", encryptionParameters(false),
                (value, config, secrets) -> CryptoSupport.sm2Encrypt(value,
                        secrets.resolve(text(config, "keyReference")))));
        result.add(algorithm("HMAC_SHA256", "HMAC-SHA256", "SIGN", "国际算法",
                "共享密钥消息认证码。", signingParameters(),
                (value, config, secrets) -> SignatureSupport.hmacSha256(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "outputEncoding"))));
        result.add(algorithm("HMAC_SM3", "HMAC-SM3", "SIGN", "国密算法",
                "基于国密 SM3 的消息认证码。", signingParameters(),
                (value, config, secrets) -> SignatureSupport.hmacSm3(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "outputEncoding"))));
        result.add(algorithm("SM2_SM3", "SM2/SM3 数字签名", "SIGN", "国密算法",
                "使用 PKCS#8 SM2 私钥执行 SM3withSM2 数字签名。", signingParameters(),
                (value, config, secrets) -> SignatureSupport.sm2Sm3Sign(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "outputEncoding"))));
        result.add(algorithm("RSA_SHA256", "RSA-SHA256 数字签名", "SIGN", "国际算法",
                "使用 PKCS#8 RSA 私钥执行 SHA256withRSA 数字签名。", signingParameters(),
                (value, config, secrets) -> SignatureSupport.rsaSha256Sign(value,
                        secrets.resolve(text(config, "keyReference")), text(config, "outputEncoding"))));
        return result;
    }

    private static List<SecurityAlgorithm.Parameter> encryptionParameters(boolean aad) {
        List<SecurityAlgorithm.Parameter> values = new ArrayList<>();
        values.add(new SecurityAlgorithm.Parameter("keyReference", "密钥引用", "SECRET_REFERENCE", true,
                "", List.of(), "config://edsp.security.keys.vendor-encryption"));
        if (aad) values.add(new SecurityAlgorithm.Parameter("aad", "附加认证数据 AAD", "TEXT", false,
                "", List.of(), "建议使用接口编码或字段编码"));
        return List.copyOf(values);
    }

    private static List<SecurityAlgorithm.Parameter> signingParameters() {
        return List.of(
                new SecurityAlgorithm.Parameter("keyReference", "密钥引用", "SECRET_REFERENCE", true,
                        "", List.of(), "config://edsp.security.keys.vendor-signing"),
                new SecurityAlgorithm.Parameter("outputEncoding", "输出编码", "SELECT", true,
                        "HEX", List.of("HEX", "BASE64"), ""));
    }

    private interface Executor { String execute(String value, Map<String, Object> config, SecretReferenceResolver secrets); }

    private static SecurityAlgorithm algorithm(String code, String name, String kind, String category,
                                               String description, List<SecurityAlgorithm.Parameter> parameters,
                                               Executor executor) {
        return new SecurityAlgorithm() {
            private final Descriptor descriptor = new Descriptor(code, name, kind, category, description, parameters, true);
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public String execute(String value, Map<String, Object> config, SecretReferenceResolver secrets) {
                return executor.execute(value, config, secrets);
            }
        };
    }

    private static String text(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value);
    }
    private static String normalize(String code) { return code == null ? "" : code.trim().toUpperCase(Locale.ROOT); }
}
