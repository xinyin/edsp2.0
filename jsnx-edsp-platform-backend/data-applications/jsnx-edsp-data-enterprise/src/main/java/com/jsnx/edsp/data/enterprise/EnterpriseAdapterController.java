package com.jsnx.edsp.data.enterprise;

import com.jsnx.edsp.plan.PlanSnapshot;
import com.jsnx.edsp.sdk.LocalPlanRuntime;
import com.jsnx.edsp.sdk.DraftAdapterExecutor;
import com.jsnx.edsp.sdk.UpstreamClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class EnterpriseAdapterController {
    private final LocalPlanRuntime plans;
    private final UpstreamClient upstream;

    public EnterpriseAdapterController(LocalPlanRuntime plans, UpstreamClient upstream) {
        this.plans = plans;
        this.upstream = upstream;
    }

    @GetMapping("/internal/adapter/info")
    public Map<String, Object> info() {
        return Map.of("application", "jsnx-edsp-data-enterprise", "dataSourceCode", "ENTERPRISE", "status", "UP");
    }

    @PostMapping("/internal/adapter/execute")
    public Map<String, Object> executeDraft(@RequestBody Map<String, Object> payload) {
        return DraftAdapterExecutor.execute("ENTERPRISE", payload, upstream);
    }

    @PostMapping("/internal/adapter/plans/{interfaceCode}/refresh")
    public Map<String, Object> refresh(@PathVariable String interfaceCode) {
        PlanSnapshot plan = plans.refresh(interfaceCode);
        return Map.of("accepted", true, "interfaceCode", interfaceCode,
                "version", plan.version(), "checksum", plan.checksum());
    }

    @DeleteMapping("/internal/adapter/plans/{interfaceCode}/refresh")
    public Map<String, Object> deactivate(@PathVariable String interfaceCode) {
        plans.deactivate(interfaceCode);
        return Map.of("accepted", true, "interfaceCode", interfaceCode, "active", false);
    }
}
