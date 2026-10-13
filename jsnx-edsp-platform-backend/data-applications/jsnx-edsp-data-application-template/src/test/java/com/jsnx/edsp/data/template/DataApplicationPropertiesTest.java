package com.jsnx.edsp.data.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataApplicationPropertiesTest {
    @Test
    void acceptsCompleteApplicationIdentity() {
        DataApplicationProperties properties =
                new DataApplicationProperties("CREDIT_V2", "征信2.0");

        assertThat(properties.dataSourceCode()).isEqualTo("CREDIT_V2");
        assertThat(properties.displayName()).isEqualTo("征信2.0");
    }

    @Test
    void rejectsMissingDataSourceCode() {
        assertThatThrownBy(() -> new DataApplicationProperties(" ", "征信2.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data-source-code");
    }
}
