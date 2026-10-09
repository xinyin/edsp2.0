package com.jsnx.edsp.sdk;

import com.jsnx.edsp.contracts.CapabilityContracts;
import com.jsnx.edsp.contracts.CapabilityContracts.StandardResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Stable internal entry used by Gateway for every configuration-driven published API. */
@RestController
public class PublishedDataApplicationController {
    private final StandardDataApplicationExecutor executor;
    private final String applicationName;

    public PublishedDataApplicationController(StandardDataApplicationExecutor executor,
            @Value("${spring.application.name}") String applicationName) {
        this.executor = executor;
        this.applicationName = applicationName;
    }

    @PostMapping("/internal/adapter/published")
    public StandardResponse<Map<String, Object>> execute(
            @RequestBody Map<String, Object> request,
            @RequestHeader("X-Api-Code") String apiCode,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-App-Id", defaultValue = "UNKNOWN") String clientCode,
            @RequestHeader(value = "X-Entry-Channel", defaultValue = "ESB") String entryChannel,
            @RequestHeader("X-Route-Version") int routeVersion,
            @RequestHeader(value = "X-Data-Application", required = false) String routedApplication) {
        if (routedApplication != null && !routedApplication.isBlank()
                && !applicationName.equals(routedApplication)) {
            return StandardResponse.failure(CapabilityContracts.ensureTraceId(traceId), "EDSP-ROUTE-MISMATCH",
                    "Gateway 路由目标与当前数据应用不一致");
        }
        return executor.execute(apiCode.trim(), applicationName, request,
                CapabilityContracts.ensureTraceId(traceId), clientCode, entryChannel, routeVersion);
    }
}
