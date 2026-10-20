package com.jsnx.edsp.service;

import com.jsnx.edsp.model.ApiModels.FieldMapping;
import com.jsnx.edsp.model.ApiModels.InterfaceSummary;
import com.jsnx.edsp.model.ApiModels.ResponseCodeMapping;
import com.jsnx.edsp.model.ApiModels.SecurityPolicy;
import com.jsnx.edsp.plan.ResponsePolicySupport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PublishedPlanRegistry {
    public record PublishedPlan(
            long interfaceId, String interfaceCode, int version,
            List<FieldMapping> requestMappings, List<FieldMapping> responseMappings,
            String checksum, Instant activatedAt, InterfaceSummary summary,
            String vendorCodePath,
            List<ResponseCodeMapping> responseCodes, SecurityPolicy securityPolicy,
            Map<String, Object> responsePolicy) {
        public PublishedPlan {
            responsePolicy = Map.copyOf(ResponsePolicySupport.normalize(responsePolicy,
                    responseMappings != null && !responseMappings.isEmpty()));
        }
        public PublishedPlan(long interfaceId, String interfaceCode, int version,
                List<FieldMapping> requestMappings, List<FieldMapping> responseMappings,
                String checksum, Instant activatedAt, InterfaceSummary summary,
                String vendorCodePath, List<ResponseCodeMapping> responseCodes,
                SecurityPolicy securityPolicy) {
            this(interfaceId, interfaceCode, version, requestMappings, responseMappings,
                    checksum, activatedAt, summary, vendorCodePath, responseCodes, securityPolicy, Map.of());
        }
        public PublishedPlan(long interfaceId, String interfaceCode, int version,
                           List<FieldMapping> requestMappings, List<FieldMapping> responseMappings,
                           String checksum, Instant activatedAt) {
            this(interfaceId, interfaceCode, version, requestMappings, responseMappings,
                    checksum, activatedAt, null, "result.code", List.of(), null, Map.of());
        }

        public PublishedPlan(long interfaceId, String interfaceCode, int version,
                             List<FieldMapping> requestMappings, List<FieldMapping> responseMappings,
                             String checksum, Instant activatedAt, InterfaceSummary summary,
                             List<ResponseCodeMapping> responseCodes, SecurityPolicy securityPolicy) {
            this(interfaceId, interfaceCode, version, requestMappings, responseMappings,
                    checksum, activatedAt, summary, "result.code", responseCodes, securityPolicy);
        }
    }

    private final Map<Long, AtomicReference<PublishedPlan>> plans = new ConcurrentHashMap<>();

    public void activate(PublishedPlan plan) {
        plans.computeIfAbsent(plan.interfaceId(), key -> new AtomicReference<>()).set(plan);
    }

    public void deactivate(long interfaceId) {
        plans.remove(interfaceId);
    }

    public PublishedPlan get(long interfaceId) {
        var reference = plans.get(interfaceId);
        return reference == null ? null : reference.get();
    }

    public int size() {
        return plans.size();
    }
}
