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
    @Test void updatesOnlyManagedServerPortInExistingConfig() throws Exception {
        String current = "server:\n  port: \"${SERVER_PORT:8199}\"\nspring:\n  application:\n    name: custom\n";
        String updated = "server:\n  port: \"${SERVER_PORT:8205}\"\nspring:\n  application:\n    name: custom\n";
        when(client.getConfig(any(), any(), anyLong())).thenReturn(current, updated);
        when(client.publishConfigCas(any(), any(), any(), any(), any())).thenReturn(true);

        var result = publisher.publishManaged("jsnx-edsp-data-credit", "generated", 8205);

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(client).publishConfigCas(eq("jsnx-edsp-data-credit.yaml"), eq("DEFAULT_GROUP"),
                eq(updated), matches("[0-9a-f]{32}"), eq("yaml"));
    }

    @Test void patchesDottedPortAndPreservesOtherSettings() {
        assertThat(NacosConfigPublisher.withServerPort("server.port: 8199\nfeature: true\n", 8210))
                .isEqualTo("server.port: \"${SERVER_PORT:8210}\"\nfeature: true\n");
        assertThat(NacosConfigPublisher.withServerPort("feature: true\n", 8210))
                .isEqualTo("server:\n  port: \"${SERVER_PORT:8210}\"\nfeature: true\n");
    }
}
