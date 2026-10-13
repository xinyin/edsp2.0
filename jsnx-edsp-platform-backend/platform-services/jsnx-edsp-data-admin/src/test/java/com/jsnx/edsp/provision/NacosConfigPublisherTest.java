package com.jsnx.edsp.provision;

import com.alibaba.nacos.api.config.ConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosConfigPublisherTest {
    ConfigService client = mock(ConfigService.class);
    NacosConfigPublisher publisher = new NacosConfigPublisher(new MockEnvironment(), client);
    @Test void neverOverwritesAnExistingConfig() throws Exception {
        when(client.getConfig(any(), any(), anyLong())).thenReturn("operator configuration");
        assertThat(publisher.publish("jsnx-edsp-data-credit", "generated").status()).isEqualTo("REUSED");
        verify(client, never()).publishConfigCas(any(), any(), any(), any(), any());
    }
    @Test void checksPublishedContent() throws Exception {
        when(client.getConfig(any(), any(), anyLong())).thenReturn(null, "generated");
        when(client.publishConfigCas(any(), any(), any(), any(), any())).thenReturn(true);
        assertThat(publisher.publish("jsnx-edsp-data-credit", "generated").status()).isEqualTo("SUCCESS");
        verify(client).publishConfigCas("jsnx-edsp-data-credit.yaml", "DEFAULT_GROUP", "generated",
                "d41d8cd98f00b204e9800998ecf8427e", "yaml");
    }
    @Test void readFailureDoesNotPublish() throws Exception {
        when(client.getConfig(any(), any(), anyLong())).thenThrow(new com.alibaba.nacos.api.exception.NacosException(403, "denied"));
        assertThatThrownBy(() -> publisher.publish("jsnx-edsp-data-credit", "generated")).hasMessageContaining("denied");
        verify(client, never()).publishConfigCas(any(), any(), any(), any(), any());
    }
}
