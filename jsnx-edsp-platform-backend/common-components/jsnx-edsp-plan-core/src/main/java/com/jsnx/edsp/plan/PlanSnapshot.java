package com.jsnx.edsp.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Lightweight published plan contract shared by independent data applications. */
public record PlanSnapshot(
        long interfaceId,
        String interfaceCode,
        int version,
        String checksum,
        String upstreamUrl,
        int timeoutMs,
        List<Map<String, Object>> requestMappings,
        List<Map<String, Object>> responseMappings,
        List<Map<String, Object>> responseCodes,
        String vendorCodePath,
        Map<String, Object> securityPolicy,
        Instant activatedAt,
        String upstreamHttpMethod,
        Map<String, Object> providerConnection,
        Map<String, Object> responsePolicy) {
    public PlanSnapshot {
        requestMappings = requestMappings == null ? List.of() : List.copyOf(requestMappings);
        responseMappings = responseMappings == null ? List.of() : List.copyOf(responseMappings);
        responseCodes = responseCodes == null ? List.of() : List.copyOf(responseCodes);
        vendorCodePath = vendorCodePath == null || vendorCodePath.isBlank() ? "result.code" : vendorCodePath.trim();
        securityPolicy = securityPolicy == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(securityPolicy));
        timeoutMs = timeoutMs <= 0 ? 3000 : timeoutMs;
        activatedAt = activatedAt == null ? Instant.now() : activatedAt;
        upstreamHttpMethod = upstreamHttpMethod == null || upstreamHttpMethod.isBlank() ? "POST" : upstreamHttpMethod;
        providerConnection = providerConnection == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(providerConnection));
        responsePolicy = Collections.unmodifiableMap(
                ResponsePolicySupport.normalize(responsePolicy, !responseMappings.isEmpty()));
    }

    public PlanSnapshot(long interfaceId, String interfaceCode, int version, String checksum,
                        String upstreamUrl, int timeoutMs, List<Map<String, Object>> requestMappings,
                        List<Map<String, Object>> responseMappings, List<Map<String, Object>> responseCodes,
                        String vendorCodePath, Map<String, Object> securityPolicy, Instant activatedAt) {
        this(interfaceId, interfaceCode, version, checksum, upstreamUrl, timeoutMs, requestMappings,
                responseMappings, responseCodes, vendorCodePath, securityPolicy, activatedAt, "POST", Map.of(), Map.of());
    }

    public PlanSnapshot(long interfaceId, String interfaceCode, int version, String checksum,
                        String upstreamUrl, int timeoutMs,
                        List<Map<String, Object>> requestMappings,
                        List<Map<String, Object>> responseMappings,
                        List<Map<String, Object>> responseCodes,
                        Map<String, Object> securityPolicy,
                        Instant activatedAt) {
        this(interfaceId, interfaceCode, version, checksum, upstreamUrl, timeoutMs,
                requestMappings, responseMappings, responseCodes, "result.code", securityPolicy, activatedAt);
    }

    public PlanSnapshot(long interfaceId, String interfaceCode, int version, String checksum,
                        String upstreamUrl, int timeoutMs, List<Map<String, Object>> requestMappings,
                        List<Map<String, Object>> responseMappings, List<Map<String, Object>> responseCodes,
                        String vendorCodePath, Map<String, Object> securityPolicy, Instant activatedAt,
                        String upstreamHttpMethod, Map<String, Object> providerConnection) {
        this(interfaceId, interfaceCode, version, checksum, upstreamUrl, timeoutMs, requestMappings,
                responseMappings, responseCodes, vendorCodePath, securityPolicy, activatedAt,
                upstreamHttpMethod, providerConnection, Map.of());
    }
}
