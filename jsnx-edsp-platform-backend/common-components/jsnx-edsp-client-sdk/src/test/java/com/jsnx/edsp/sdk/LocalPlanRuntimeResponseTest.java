package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.contracts.CapabilityContracts.CompleteResponse;
import com.jsnx.edsp.plan.PlanSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalPlanRuntimeResponseTest {
    @Test
    void publishedWholeBodyPlanUsesHttpSuccessAndPreservesVendorPayload() {
        Map<String, Object> upstream = Map.of("page", 1,
                "data", List.of(Map.of("id", 1), Map.of("id", 2)),
                "_meta", Map.of("variant", "v1"));
        PlanSnapshot plan = new PlanSnapshot(1, "QUERY", 2, "checksum", "https://supplier/query", 3000,
                List.of(), List.of(), List.of(), "result.code", Map.of(), Instant.now(), "GET", Map.of(),
                Map.of("dataMode", "WHOLE_BODY", "codeMode", "HTTP_SUCCESS", "successCode", "0000"));
        LocalPlanRuntime runtime = new LocalPlanRuntime(new ObjectMapper(), "http://127.0.0.1:8081", "data-test");

        CompleteResponse result = runtime.complete(plan, upstream);

        assertEquals("0000", result.standardCode());
        assertEquals("HIT", result.resultStatus());
        assertEquals(upstream, result.data());
    }
}
