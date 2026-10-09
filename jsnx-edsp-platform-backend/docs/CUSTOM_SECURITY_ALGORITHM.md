# 自定义安全算法扩展

平台内置 AES-256-GCM、SM4-GCM、SM2 公钥加密、HMAC-SHA256、HMAC-SM3、SM2/SM3 和 RSA-SHA256。供应商存在专有算法时，不修改平台核心代码，通过 SPI 扩展。

## 开发约束

1. 新建独立 Maven JAR，依赖 `jsnx-edsp-plan-core`，实现 `com.jsnx.edsp.plan.SecurityAlgorithm`。
2. 算法编码必须全局唯一；`kind` 只能为 `ENCRYPT` 或 `SIGN`。
3. 参数通过 `Descriptor.parameters` 声明，管理页面会自动生成配置表单。
4. 密钥参数类型必须声明为 `SECRET_REFERENCE`，执行时调用 `SecretReferenceResolver.resolve`；扩展实现不得接收或持久化页面明文密钥。
5. 在扩展 JAR 中创建 `META-INF/services/com.jsnx.edsp.plan.SecurityAlgorithm`，内容为实现类全限定名。
6. 扩展 JAR 必须同时部署到 Admin 和所有可能执行该算法的数据应用，由发布流水线校验版本一致性后重启加载。

示例：

```java
public final class VendorSignatureAlgorithm implements SecurityAlgorithm {
    public Descriptor descriptor() {
        return new Descriptor(
            "VENDOR_HMAC_V2", "供应商 HMAC V2", "SIGN", "供应商扩展",
            "供应商规定的专有签名拼接规则",
            List.of(new Parameter("keyReference", "密钥引用", "SECRET_REFERENCE",
                true, "", List.of(), "config://edsp.security.keys.vendor-v2")),
            false);
    }

    public String execute(String value, Map<String, Object> config,
                          SecretReferenceResolver secrets) {
        String key = secrets.resolve(String.valueOf(config.get("keyReference")));
        return VendorLibrary.sign(value, key);
    }
}
```

扩展 JAR 必须纳入代码审查、算法测试、已知答案测试、依赖漏洞扫描和制品签名。涉及国密商用密码时，应采用行内认可的合规密码机或 Provider，平台 SPI 负责适配，不自行替代合规认证。
