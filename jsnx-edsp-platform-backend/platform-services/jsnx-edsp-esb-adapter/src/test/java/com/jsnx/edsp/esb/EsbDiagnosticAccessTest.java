package com.jsnx.edsp.esb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.Mockito.*;

class EsbDiagnosticAccessTest {
    @Test
    void publicDiagnosticFlagCannotBypassAuthorization() throws Exception {
        var queue = mock(MockEsbQueue.class, withSettings().mockMaker("mock-maker-subclass"));
        var client = WebTestClient.bindToController(new EsbAdapterController(
                queue, new ObjectMapper(), new EsbXmlProtocol("QMG4GW06", "150", "外部数据平台"),
                mock(EsbMessageAdapter.class), "internal-secret", "MOCK")).build();
        for (String key : new String[]{"", "wrong-key"}) {
            client.post().uri("/esb/queue/request?diagnostic=true")
                            .header("X-Edsp-Internal-Test-Key", key)
                            .header("X-App-Id", "EDSP_TEST_TOOL")
                            .header("X-Api-Key", "demo-secret-key")
                            .contentType(MediaType.APPLICATION_XML).bodyValue("<Service/>")
                    .exchange().expectStatus().isForbidden();
        }
        verifyNoInteractions(queue);
    }
}
