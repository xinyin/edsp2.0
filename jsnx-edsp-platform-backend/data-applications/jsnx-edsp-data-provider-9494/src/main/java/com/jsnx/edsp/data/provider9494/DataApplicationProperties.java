package com.jsnx.edsp.data.provider9494;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "edsp.data-application")
public record DataApplicationProperties(String providerCode, String displayName) {
    public DataApplicationProperties {
        if (!StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("edsp.data-application.provider-code must not be blank");
        }
        if (!StringUtils.hasText(displayName)) {
            throw new IllegalArgumentException("edsp.data-application.display-name must not be blank");
        }
    }
}
