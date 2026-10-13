package com.jsnx.edsp.data.template;

import com.jsnx.edsp.plan.PlanSnapshot;
import com.jsnx.edsp.sdk.DraftAdapterExecutor;
import com.jsnx.edsp.sdk.LocalPlanRuntime;
import com.jsnx.edsp.sdk.UpstreamClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class TemplateAdapterController {
    private final LocalPlanRuntime plans;
    private final UpstreamClient upstream;
    private final DataApplicationProperties properties;
    private final String applicationName;

    public TemplateAdapterController(LocalPlanRuntime plans,
                                     UpstreamClient upstream,
                                     DataApplicationProperties properties,
                                     @Value("${spring.application.name}") String applicationName) {
        this.plans = plans;
        this.upstream = upstream;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    @GetMapping("/internal/adapter/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", applicationName);
        result.put("dataSourceCode", properties.dataSourceCode());
        result.put("displayName", properties.displayName());
        result.put("status", "UP");
        return result;
    }

    @PostMapping("/internal/adapter/execute")
    public Map<String, Object> executeDraft(@RequestBody Map<String, Object> payload) {
        return DraftAdapterExecutor.execute(properties.dataSourceCode(), payload, upstream);
    }

    @PostMapping("/internal/adapter/plans/{interfaceCode}/refresh")
    public Map<String, Object> refresh(@PathVariable String interfaceCode) {
        PlanSnapshot plan = plans.refresh(interfaceCode);
        return Map.of(
                "accepted", true,
                "interfaceCode", interfaceCode,
                "version", plan.version(),
                "checksum", plan.checksum()
        );
    }

    @DeleteMapping("/internal/adapter/plans/{interfaceCode}/refresh")
    public Map<String, Object> deactivate(@PathVariable String interfaceCode) {
        plans.deactivate(interfaceCode);
        return Map.of("accepted", true, "interfaceCode", interfaceCode, "active", false);
    }
}
