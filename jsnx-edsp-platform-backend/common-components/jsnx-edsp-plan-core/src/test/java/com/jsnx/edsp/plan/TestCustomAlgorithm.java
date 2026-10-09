package com.jsnx.edsp.plan;

import com.jsnx.edsp.crypto.SecretReferenceResolver;
import java.util.List;
import java.util.Map;

public final class TestCustomAlgorithm implements SecurityAlgorithm {
    @Override public Descriptor descriptor() {
        return new Descriptor("TEST_CUSTOM_SIGN", "测试扩展签名", "SIGN", "测试扩展",
                "验证 ServiceLoader SPI", List.of(), false);
    }

    @Override public String execute(String value, Map<String, Object> config, SecretReferenceResolver secrets) {
        return "custom:" + value;
    }
}
