package com.jsnx.edsp.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.contracts.CapabilityContracts.*;
import com.jsnx.edsp.plan.PlanBootstrap;
import com.jsnx.edsp.plan.PlanMappingSupport;
import com.jsnx.edsp.plan.PlanSnapshot;
import com.jsnx.edsp.plan.ResponsePolicySupport;
import com.jsnx.edsp.plan.SecurityPlanSupport;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.transform.FieldTransformSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/internal/capability")
public class CapabilityController {
    private static final Map<String, Long> DEMO_INTERFACE_IDS = Map.of(
            "ENTERPRISE_BASIC", 1L, "CREDIT_REPORT_QUERY", 2L);
    private final ObjectMapper mapper;
    private final String adminUrl;
    private final Map<String, PlanSnapshot> cache = new ConcurrentHashMap<>();
    private final SecretReferenceResolver secrets;

    public CapabilityController(ObjectMapper mapper, @Value("${edsp.admin-url}") String adminUrl,
                                Environment environment) {
        this.mapper = mapper;
        this.adminUrl = adminUrl;
        this.secrets = new SecretReferenceResolver(environment::getProperty);
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("service", "capability-runtime", "status", "UP"); }

    @PostMapping("/prepare")
    public PrepareResponse prepare(@RequestBody PrepareRequest request) {
        PlanSnapshot plan = plan(request.interfaceCode());
        Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();
        Map<String, Object> mapped = PlanMappingSupport.apply(payload, plan.requestMappings(), secrets);
        Map<String, Object> secured = SecurityPlanSupport.apply(mapped, plan.securityPolicy(), secrets, mapper);
        return new PrepareResponse(plan.interfaceCode(), plan.version(), plan.upstreamUrl(), plan.timeoutMs(), secured);
    }

    @PostMapping("/complete")
    public CompleteResponse complete(@RequestBody CompleteRequest request) {
        PlanSnapshot plan = plan(request.interfaceCode());
        Map<String, Object> response = request.upstreamResponse() == null ? Map.of() : request.upstreamResponse();
        Object vendorCodeValue = FieldTransformSupport.readPath(response, plan.vendorCodePath());
        String vendorCode = vendorCodeValue == null ? "" : String.valueOf(vendorCodeValue);
        boolean httpSuccess = "HTTP_SUCCESS".equals(plan.responsePolicy().get("codeMode"));
        String standardCode = httpSuccess ? String.valueOf(plan.responsePolicy().get("successCode")) : plan.responseCodes().stream()
                .filter(code -> vendorCode.equals(String.valueOf(code.get("vendorCode"))))
                .map(code -> String.valueOf(code.getOrDefault("standardCode", "EXT-VENDOR-ERROR")))
                .findFirst().orElse("EXT-VENDOR-ERROR");
        String resultStatus = httpSuccess ? "HIT" : plan.responseCodes().stream()
                .filter(code -> standardCode.equals(String.valueOf(code.get("standardCode"))))
                .map(code -> String.valueOf(code.getOrDefault("resultStatus", "ERROR"))).findFirst().orElse("ERROR");
        boolean billable = !httpSuccess && plan.responseCodes().stream()
                .filter(code -> standardCode.equals(String.valueOf(code.get("standardCode"))))
                .map(code -> Boolean.parseBoolean(String.valueOf(code.getOrDefault("billable", false))))
                .findFirst().orElse(false);
        Map<String, Object> fields = ResponsePolicySupport.fields(response, plan.responseMappings(), plan.responsePolicy(), secrets);
        Object data = fields.get("data");
        Map<String, Object> result = data instanceof Map<?, ?> value ? mapper.convertValue(value, Map.class) : Map.of();
        return new CompleteResponse(plan.interfaceCode(), plan.version(), standardCode, resultStatus, billable, result, fields);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestParam String interfaceCode) {
        cache.remove(interfaceCode);
        PlanSnapshot refreshed = plan(interfaceCode);
        return Map.of("accepted", true, "interfaceCode", interfaceCode, "version", refreshed.version());
    }

    private PlanSnapshot plan(String interfaceCode) {
        PlanSnapshot existing = cache.get(interfaceCode);
        if (existing != null) return existing;
        Long id = DEMO_INTERFACE_IDS.get(interfaceCode);
        if (id == null) throw new IllegalArgumentException("未配置执行计划：" + interfaceCode);
        Map<String, Object> body = RestClient.create().get().uri(adminUrl + "/api/interfaces/" + id + "/published-plan")
                .retrieve().body(Map.class);
        if (body == null || (body.containsKey("active") && Boolean.FALSE.equals(body.get("active"))))
            throw new IllegalStateException("接口尚未发布：" + interfaceCode);
        PlanSnapshot loaded = PlanBootstrap.fromAdmin(body, mapper);
        cache.put(interfaceCode, loaded);
        return loaded;
    }
}
