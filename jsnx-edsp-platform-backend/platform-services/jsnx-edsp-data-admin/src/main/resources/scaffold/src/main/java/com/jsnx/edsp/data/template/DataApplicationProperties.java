package com.jsnx.edsp.data.template;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "edsp.data-application")
public record DataApplicationProperties(String dataSourceCode, String displayName) {
    public DataApplicationProperties {
        if (!StringUtils.hasText(dataSourceCode)) {
            throw new IllegalArgumentException("edsp.data-application.data-source-code must not be blank");
        }
        if (!StringUtils.hasText(displayName)) {
            throw new IllegalArgumentException("edsp.data-application.display-name must not be blank");
        }
    }
}
