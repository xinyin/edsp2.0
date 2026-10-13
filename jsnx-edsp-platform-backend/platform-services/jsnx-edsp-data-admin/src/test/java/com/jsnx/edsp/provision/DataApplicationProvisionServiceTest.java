package com.jsnx.edsp.provision;

import com.jsnx.edsp.model.ApiModels.SaveDataSourceRuntimeRequest;
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
    SaveDataSourceRuntimeRequest request = new SaveDataSourceRuntimeRequest("jsnx-edsp-data-credit-v2", "http://127.0.0.1:8198");
    @BeforeEach void setup() {
        var source = DataApplicationScaffoldGeneratorTest.source(request.applicationName());
        when(interfaces.dataSources()).thenReturn(List.of(source));
        when(interfaces.saveDataSourceRuntime(7, request)).thenReturn(source);
        when(generator.outputRoot()).thenReturn(Path.of("/tmp/generated"));
        when(publisher.group()).thenReturn("DEFAULT_GROUP");
        when(publisher.namespace()).thenReturn("");
        service = new DataApplicationProvisionService(interfaces, generator, publisher, new MockEnvironment());
    }
    @Test void generationFailureDoesNotUndoSavedRouteOrPublishNacos() throws Exception {
        when(generator.generate(any())).thenThrow(new IOException("permission denied"));
        var result = service.save(7, request);
        assertThat(result.routeSave().status()).isEqualTo("SUCCESS");
        assertThat(result.generation().status()).isEqualTo("FAILED");
        assertThat(result.nacos().status()).isEqualTo("SKIPPED");
        verify(publisher, never()).publish(any(), any());
        var order = inOrder(interfaces, generator);
        order.verify(interfaces).saveDataSourceRuntime(7, request);
        order.verify(generator).generate(any());
    }
    @Test void invalidIdentityIsRejectedBeforeSaving() {
        doThrow(new IllegalArgumentException("invalid")).when(generator).validate(any(), any());
        assertThatThrownBy(() -> service.save(7, request)).isInstanceOf(IllegalArgumentException.class);
        verify(interfaces, never()).saveDataSourceRuntime(anyLong(), any());
    }
}
