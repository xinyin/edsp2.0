package com.jsnx.edsp.data.lhzx;

import com.jsnx.edsp.plan.PlanSnapshot;
import com.jsnx.edsp.sdk.DraftAdapterExecutor;
import com.jsnx.edsp.sdk.LocalPlanRuntime;
import com.jsnx.edsp.sdk.UpstreamClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class LhzxAdapterController {
    private final LocalPlanRuntime plans;
    private final UpstreamClient upstream;

    public LhzxAdapterController(LocalPlanRuntime plans, UpstreamClient upstream) {
        this.plans = plans;
        this.upstream = upstream;
    }

    @GetMapping("/internal/adapter/info")
    public Map<String, Object> info() {
        return Map.of("application", "jsnx-edsp-data-source-lhzx",
                "dataSourceCode", "LHZX", "status", "UP");
    }

    @PostMapping("/internal/adapter/execute")
    public Map<String, Object> executeDraft(@RequestBody Map<String, Object> payload) {
        return DraftAdapterExecutor.execute("LHZX", payload, upstream);
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
