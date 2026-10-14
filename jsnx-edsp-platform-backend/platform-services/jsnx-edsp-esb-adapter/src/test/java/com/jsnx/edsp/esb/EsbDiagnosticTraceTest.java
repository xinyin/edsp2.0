package com.jsnx.edsp.esb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EsbDiagnosticTraceTest {
    @Test
    void usesActualExecutionNodesAndUnwrapsOnlyInternalTestResult() throws Exception {
        var json = new ObjectMapper();
        var credential = new AtomicReference<String>();
        var trace = new AtomicReference<String>();
        var response = Map.of("standardResponse", Map.of("code", "EXT-ERROR", "resultStatus", "ERROR"),
                "upstreamRequest", Map.of("supplier_key", "001"),
                "upstreamResponse", Map.of("result", "FAILED"),
                "traceNodes", List.of(Map.of("key", "RESPONSE_MAPPING", "status", "ERROR", "durationMs", 13)));
        var builder = WebClient.builder().exchangeFunction(request -> {
            credential.set(request.headers().getFirst("X-Edsp-Internal-Test-Key"));
            trace.set(request.headers().getFirst("X-Trace-Id"));
            try {
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(json.writeValueAsString(response)).build());
            } catch (Exception error) { return Mono.error(error); }
        });
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        var adapter = new EsbMessageAdapter(json, new EsbXmlProtocol("QM", "150", "平台"), builder,
                kafka, "http://gateway", "http://admin", "reply", "TEST", 3000, 8000, "internal-secret");
        String xml = "<Service><Service_Header><service_sn>TRACE-001</service_sn><service_id>API_001</service_id>"
                + "<requester_id>EDSP_TEST_TOOL</requester_id><service_time>20261014105000</service_time><version_id>01</version_id></Service_Header>"
                + "<Service_Body><request><query_key>001</query_key></request></Service_Body></Service>";
        var result = adapter.forward("/api/v1/query", "EDSP_TEST_TOOL", "api-key", xml, "TRACE-001", true);
        assertEquals("internal-secret", credential.get());
        assertEquals("TRACE-001", trace.get());
        assertEquals(response.get("standardResponse"), result.standardResponse());
        assertEquals(response.get("upstreamRequest"), result.diagnosticResponse().get("upstreamRequest"));
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) result.diagnosticResponse().get("nodes");
        var mapping = nodes.stream().filter(node -> "RESPONSE_MAPPING".equals(node.get("key"))).findFirst().orElseThrow();
        assertEquals("ERROR", mapping.get("status"));
        assertEquals(13, mapping.get("durationMs"));
        assertTrue(nodes.stream().noneMatch(node -> "SUPPLIER".equals(node.get("key"))));

        var business = adapter.forward("/api/v1/query", "CLIENT", "api-key", xml, "TRACE-002", false);
        assertNull(credential.get());
        // An external provider may legitimately have a field called standardResponse.
        assertEquals(response, business.standardResponse());
    }
}
