package com.jsnx.edsp.provision;

import com.jsnx.edsp.model.ApiModels.SaveProviderRuntimeRequest;
import com.jsnx.edsp.service.InterfaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataApplicationProvisionServiceTest {
    InterfaceService interfaces = mock(InterfaceService.class);
    DataApplicationScaffoldGenerator generator = mock(DataApplicationScaffoldGenerator.class);
    NacosConfigPublisher publisher = mock(NacosConfigPublisher.class);
    DataApplicationProvisionService service;
    SaveProviderRuntimeRequest request = new SaveProviderRuntimeRequest("jsnx-edsp-data-credit-v2", "http://127.0.0.1:8198", 8198);
    @BeforeEach void setup() {
        var provider = DataApplicationScaffoldGeneratorTest.provider(request.applicationName());
        when(interfaces.provider(3)).thenReturn(provider);
        when(interfaces.providers()).thenReturn(List.of(provider));
        when(interfaces.saveProviderRuntime(3, request)).thenReturn(provider);
        when(generator.outputRoot()).thenReturn(Path.of("/tmp/generated"));
        when(publisher.group()).thenReturn("DEFAULT_GROUP");
        when(publisher.namespace()).thenReturn("");
        service = new DataApplicationProvisionService(interfaces, generator, publisher, new MockEnvironment());
    }
    @Test void generationFailureDoesNotUndoSavedRouteOrPublishNacos() throws Exception {
        when(generator.generate(any(), anyInt())).thenThrow(new IOException("permission denied"));
        var result = service.save(3, request);
        assertThat(result.routeSave().status()).isEqualTo("SUCCESS");
        assertThat(result.generation().status()).isEqualTo("FAILED");
        assertThat(result.nacos().status()).isEqualTo("SKIPPED");
        verify(publisher, never()).publish(any(), any());
        var order = inOrder(interfaces, generator);
        order.verify(interfaces).saveProviderRuntime(3, request);
        order.verify(generator).generate(any(), eq(8198));
    }
    @Test void synchronizesExplicitListenPortInsteadOfTemplatePlaceholder() throws Exception {
        var generated = new ProvisionModels.Generation("REUSED", "existing", "/tmp/generated/app", "pkg", List.of());
        when(generator.generate(any(), eq(8198))).thenReturn(generated);
        when(generator.renderNacosYaml(any(), eq(8198))).thenReturn("server:\n  port: \"${SERVER_PORT:8198}\"\n");
        when(publisher.publishManaged(any(), any(), eq(8198))).thenReturn(
                new ProvisionModels.Nacos("SUCCESS", "updated", "app.yaml", "DEFAULT_GROUP", ""));

        var result = service.save(3, request);

        assertThat(result.nacos().status()).isEqualTo("SUCCESS");
        verify(publisher).publishManaged("jsnx-edsp-data-credit-v2",
                "server:\n  port: \"${SERVER_PORT:8198}\"\n", 8198);
    }
    @Test void invalidIdentityIsRejectedBeforeSaving() {
        doThrow(new IllegalArgumentException("invalid")).when(generator).validate(any(), any());
        assertThatThrownBy(() -> service.save(3, request)).isInstanceOf(IllegalArgumentException.class);
        verify(interfaces, never()).saveProviderRuntime(anyLong(), any());
    }
    @Test void statusReadsPersistedArtifactsWithoutSavingOrRefreshing() {
        var provider = DataApplicationScaffoldGeneratorTest.provider(request.applicationName());
        var generation = new ProvisionModels.Generation("REUSED", "found", "/tmp/generated/app", "pkg", List.of("pom.xml"));
        var nacos = new ProvisionModels.Nacos("REUSED", "found", "app.yaml", "DEFAULT_GROUP", "");
        when(generator.inspect(provider)).thenReturn(generation);
        when(publisher.inspect(provider.applicationName())).thenReturn(nacos);

        var result = service.status(3);

        assertThat(result.routeSave().status()).isEqualTo("SUCCESS");
        assertThat(result.generation().outputDirectory()).isEqualTo("/tmp/generated/app");
        assertThat(result.nacos().dataId()).isEqualTo("app.yaml");
        assertThat(result.routeRefresh().status()).isEqualTo("SKIPPED");
        verify(interfaces, never()).saveProviderRuntime(anyLong(), any());
    }
}
