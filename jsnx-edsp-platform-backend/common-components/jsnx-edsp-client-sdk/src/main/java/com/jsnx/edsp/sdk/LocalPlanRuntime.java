package com.jsnx.edsp.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.contracts.CapabilityContracts.CompleteResponse;
import com.jsnx.edsp.contracts.CapabilityContracts.PrepareResponse;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.plan.PlanBootstrap;
import com.jsnx.edsp.plan.PlanMappingSupport;
import com.jsnx.edsp.plan.PlanSnapshot;
import com.jsnx.edsp.plan.SecurityPlanSupport;
import com.jsnx.edsp.transform.FieldTransformSupport;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Data-application-local execution runtime. Published plans are durable in MySQL,
 * loaded through Admin and atomically swapped per interface in this JVM.
 */
public final class LocalPlanRuntime {
    private final ObjectMapper mapper;
    private final RestClient admin;
    private final String applicationName;
    private final Map<String, Long> interfaceIds = new ConcurrentHashMap<>();
    private final SecretReferenceResolver secrets;
    private final Map<String, AtomicReference<PlanSnapshot>> plans = new ConcurrentHashMap<>();

    public LocalPlanRuntime(ObjectMapper mapper, String adminUrl, String applicationName) {
        this(mapper, adminUrl, applicationName, System::getProperty);
    }

    public LocalPlanRuntime(ObjectMapper mapper, String adminUrl, String applicationName,
                            Function<String, String> configLookup) {
        this.mapper = mapper;
        this.admin = RestClient.builder().baseUrl(adminUrl).build();
        this.applicationName = applicationName;
        this.secrets = new SecretReferenceResolver(configLookup);
    }

    public PrepareResponse prepare(String interfaceCode, Map<String, Object> standardRequest) {
        return prepare(current(interfaceCode), standardRequest);
    }

    public PrepareResponse prepare(PlanSnapshot plan, Map<String, Object> standardRequest) {
        Map<String, Object> mapped = PlanMappingSupport.apply(
                standardRequest == null ? Map.of() : standardRequest, plan.requestMappings(), secrets);
        Map<String, Object> secured = SecurityPlanSupport.apply(mapped, plan.securityPolicy(), secrets, mapper);
        return new PrepareResponse(plan.interfaceCode(), plan.version(), plan.upstreamUrl(),
                plan.timeoutMs(), secured);
    }

    public CompleteResponse complete(String interfaceCode, Map<String, Object> upstreamResponse) {
        return complete(current(interfaceCode), upstreamResponse);
    }

    public CompleteResponse complete(PlanSnapshot plan, Map<String, Object> upstreamResponse) {
        Map<String, Object> response = upstreamResponse == null ? Map.of() : upstreamResponse;
        Object vendorCodeValue = FieldTransformSupport.readPath(response, plan.vendorCodePath());
        String vendorCode = vendorCodeValue == null ? "" : String.valueOf(vendorCodeValue);
        Map<String, Object> code = plan.responseCodes().stream()
                .filter(item -> vendorCode.equals(String.valueOf(item.get("vendorCode"))))
                .findFirst().orElse(Map.of());
        String standardCode = String.valueOf(code.getOrDefault("standardCode", "EXT-VENDOR-ERROR"));
        String resultStatus = String.valueOf(code.getOrDefault("resultStatus", "ERROR"));
        boolean billable = Boolean.parseBoolean(String.valueOf(code.getOrDefault("billable", false)));
        Map<String, Object> mapped = "HIT".equals(resultStatus)
                ? PlanMappingSupport.apply(response, plan.responseMappings(), secrets) : Map.of();
        Object data = mapped.get("data");
        Map<String, Object> standardData = data instanceof Map<?, ?> value
                ? mapper.convertValue(value, new TypeReference<>() {}) : mapped;
        return new CompleteResponse(plan.interfaceCode(), plan.version(), standardCode,
                resultStatus, billable, standardData);
    }

    public PlanSnapshot refresh(String interfaceCode) {
        interfaceIds.remove(interfaceCode);
        PlanSnapshot next = load(interfaceCode);
        plans.computeIfAbsent(interfaceCode, ignored -> new AtomicReference<>()).set(next);
        return next;
    }

    public void deactivate(String interfaceCode) {
        plans.remove(interfaceCode);
    }

    public PlanSnapshot current(String interfaceCode) {
        AtomicReference<PlanSnapshot> reference = plans.computeIfAbsent(
                interfaceCode, ignored -> new AtomicReference<>());
        PlanSnapshot current = reference.get();
        if (current != null) return current;
        PlanSnapshot loaded = load(interfaceCode);
        return reference.compareAndSet(null, loaded) ? loaded : reference.get();
    }

    /**
     * Return the exact version selected by Gateway. A missed publication push is
     * repaired synchronously once; an unavailable/mismatched durable snapshot is
     * rejected instead of silently executing an older contract.
     */
    public PlanSnapshot current(String interfaceCode, int expectedVersion) {
        PlanSnapshot selected = current(interfaceCode);
        if (selected.version() == expectedVersion) return selected;
        PlanSnapshot refreshed = refresh(interfaceCode);
        if (refreshed.version() != expectedVersion) {
            throw new IllegalStateException("执行计划版本不一致：Gateway=" + expectedVersion
                    + "，数据应用=" + refreshed.version());
        }
        return refreshed;
    }

    @SuppressWarnings("unchecked")
    private PlanSnapshot load(String interfaceCode) {
        Long interfaceId = interfaceIds.computeIfAbsent(interfaceCode, this::resolveInterfaceId);
        Map<String, Object> body = admin.get().uri("/api/interfaces/{id}/published-plan", interfaceId)
                .retrieve().body(Map.class);
        if (body == null || Boolean.FALSE.equals(body.get("active"))) {
            throw new IllegalStateException("接口尚未发布：" + interfaceCode);
        }
        return PlanBootstrap.fromAdmin(body, mapper);
    }

    @SuppressWarnings("unchecked")
    private long resolveInterfaceId(String interfaceCode) {
        Map<String, Object> route = admin.get().uri(uriBuilder -> uriBuilder
                        .path("/api/internal/interfaces/resolve").queryParam("apiCode", interfaceCode).build())
                .retrieve().body(Map.class);
        if (route == null || !(route.get("id") instanceof Number id)) {
            throw new IllegalStateException("未找到已发布执行计划：" + interfaceCode);
        }
        String owner = String.valueOf(route.getOrDefault("application_name", ""));
        if (!applicationName.equals(owner)) {
            throw new IllegalStateException("接口路由归属 " + owner + "，不能由 " + applicationName + " 执行");
        }
        return id.longValue();
    }

}
