package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.contracts.CapabilityContracts.CompleteResponse;
import com.jsnx.edsp.plan.PlanSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalPlanRuntimeResponseTest {
    @Test
    void upstreamOutputCodeDoesNotChangeOutcomeOrBilling() {
        List<Map<String, Object>> rules = List.of(
                Map.of("sourcePath", "result.code", "targetPath", "$.code", "transformType", "DIRECT"),
                Map.of("sourcePath", "result.message", "targetPath", "$.message", "transformType", "DIRECT"),
                Map.of("sourcePath", "company", "targetPath", "$.data.company", "transformType", "DIRECT", "required", true));
        for (String status : List.of("HIT", "MISS", "ERROR")) {
            PlanSnapshot plan = new PlanSnapshot(1, "QUERY", 2, "checksum", "https://supplier/query", 3000,
                    List.of(), rules, List.of(Map.of("vendorCode", "V01", "standardCode", "BANK-01",
                    "resultStatus", status, "billable", "HIT".equals(status))), "result.code", Map.of(), Instant.now(),
                    "GET", Map.of(), Map.of("dataMode", "FIELD_MAPPING", "codeMode", "VENDOR_CODE"));
            CompleteResponse result = new LocalPlanRuntime(new ObjectMapper(), "http://127.0.0.1:8081", "data-test")
                    .complete(plan, Map.of("result", Map.of("code", "V01", "message", "上游说明"), "company", "ABC"));
            assertEquals("V01", result.standardCode());
            assertEquals("V01", result.responseFields().get("code"));
            assertEquals("上游说明", result.responseFields().get("message"));
            assertEquals(status, result.resultStatus());
            assertEquals("HIT".equals(status), result.billable());
            assertEquals("HIT".equals(status) ? Map.of("company", "ABC") : Map.of(), result.data());
        }
    }

    @Test
    void fixedCodeAndMissingOptionalMessageKeepCompletionConsistent() {
        PlanSnapshot plan = new PlanSnapshot(1, "QUERY", 2, "checksum", "https://supplier/query", 3000,
                List.of(), List.of(
                Map.of("sourcePath", "$", "targetPath", "$.code", "transformType", "CONSTANT",
                        "transformConfig", Map.of("value", "CUSTOM-OK")),
                Map.of("sourcePath", "missing", "targetPath", "$.message", "transformType", "DIRECT", "defaultValue", "")),
                List.of(), "result.code", Map.of(), Instant.now(), "GET", Map.of(),
                Map.of("dataMode", "FIELD_MAPPING", "codeMode", "HTTP_SUCCESS", "successCode", "0000"));
        CompleteResponse result = new LocalPlanRuntime(new ObjectMapper(), "http://127.0.0.1:8081", "data-test")
                .complete(plan, Map.of());
        assertEquals("CUSTOM-OK", result.standardCode());
        assertFalse(result.responseFields().containsKey("message"));
        assertEquals("HIT", result.resultStatus());
    }

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

    @Test
    void publishedPlanPlacesWholeBodyAtConfiguredRootPath() {
        Map<String, Object> upstream = Map.of("data", List.of(Map.of("id", 1)), "page", 1);
        PlanSnapshot plan = new PlanSnapshot(1, "QUERY", 2, "checksum", "https://supplier/query", 3000,
                List.of(), List.of(Map.of("sourcePath", "$", "targetPath", "$.payload.supplierResult",
                        "transformType", "WHOLE_BODY")), List.of(), "result.code", Map.of(), Instant.now(),
                "GET", Map.of(), Map.of("dataMode", "FIELD_MAPPING", "codeMode", "HTTP_SUCCESS", "successCode", "0000"));
        CompleteResponse result = new LocalPlanRuntime(new ObjectMapper(), "http://127.0.0.1:8081", "data-test")
                .complete(plan, upstream);
        assertEquals(Map.of("payload", Map.of("supplierResult", upstream)), result.responseFields());
        assertEquals(Map.of(), result.data());
    }
}
