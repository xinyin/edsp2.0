package com.jsnx.edsp.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record DataSourceSummary(
            long id, long providerId, String providerName, String sourceCode, String sourceName,
            String applicationName, String runtimeUrl, int serverPort, String protocolType, String endpointUrl, int defaultTimeoutMs, String environment,
            String status, int instanceCount, int configVersion, int interfaceCount) {}

    public record InterfaceSummary(
            long id, long dataSourceId, String interfaceCode, String interfaceName,
            String servicePath, String httpMethod, String upstreamUrl, String upstreamPath,
            String configScope, String dispatchMode, String routerServiceCode, int effectiveTimeoutMs,
            String upstreamStatus, String status,
            int currentVersion, Integer publishedVersion, LocalDateTime updatedAt,
            Long resourceApiId, String resourceApiCode, String resourceApiName) {}

    public record ResourceApiSummary(
            long id, long dataSourceId, String resourceApiCode, String resourceApiName,
            String httpMethod, String upstreamUrl, String upstreamPath, String configScope,
            int effectiveTimeoutMs, String status, int currentVersion,
            LocalDateTime verifiedAt, LocalDateTime updatedAt, int platformServiceCount) {}

    public record ResourceApiDetail(
            ResourceApiSummary summary, Map<String, Object> requestExample,
            Map<String, Object> responseExample, Map<String, String> requestHeaders, Integer configuredTimeoutMs,
            String timeoutSource) {}

    public record FieldMapping(
            Long id, @NotBlank String direction, @NotBlank String sourcePath,
            @NotBlank String targetPath, @NotBlank String transformType,
            Map<String, String> transformConfig, String defaultValue,
            boolean required, int sortOrder) {}

    public record InterfaceDetail(
            InterfaceSummary summary, Map<String, Object> requestSchema,
            Map<String, Object> responseSchema, Map<String, Object> requestExample,
            Map<String, Object> upstreamRequestExample,
            Map<String, Object> upstreamResponseExample,
            Map<String, Object> standardResponseExample, int timeoutMs, Integer configuredTimeoutMs,
            String timeoutSource, int retryCount,
            String vendorCodePath,
            List<FieldMapping> mappings, List<ResponseCodeMapping> responseCodes,
            SecurityPolicy securityPolicy, Map<String, Object> responsePolicy) {
        public InterfaceDetail(InterfaceSummary summary, Map<String, Object> requestSchema,
                Map<String, Object> responseSchema, Map<String, Object> requestExample,
                Map<String, Object> upstreamRequestExample, Map<String, Object> upstreamResponseExample,
                Map<String, Object> standardResponseExample, int timeoutMs, Integer configuredTimeoutMs,
                String timeoutSource, int retryCount, String vendorCodePath,
                List<FieldMapping> mappings, List<ResponseCodeMapping> responseCodes,
                SecurityPolicy securityPolicy) {
            this(summary, requestSchema, responseSchema, requestExample, upstreamRequestExample,
                    upstreamResponseExample, standardResponseExample, timeoutMs, configuredTimeoutMs,
                    timeoutSource, retryCount, vendorCodePath, mappings, responseCodes, securityPolicy, Map.of());
        }
    }

    public record SecurityPolicy(
            long interfaceId, boolean enabled, String encryptMode,
            String signatureMode, String requestFields, String encryptionKeyReference,
            String signingKeyReference, String signatureField,
            String description) {}

    public record SaveSecurityPolicyRequest(
            boolean enabled, String encryptMode, String signatureMode,
            String requestFields, String encryptionKeyReference,
            String signingKeyReference, String signatureField, String description) {}

    public record ResponseCodeMapping(
            Long id, @NotBlank String vendorCode, @NotBlank String standardCode,
            @NotBlank String resultStatus, boolean retryable, boolean billable) {}

    public record SaveMappingsRequest(
            @NotNull List<FieldMapping> mappings,
            @NotBlank String servicePath, @NotBlank String httpMethod,
            @NotNull Map<String, Object> requestExample,
            @NotNull Map<String, Object> upstreamResponseExample,
            Map<String, Object> responsePolicy) {
        public SaveMappingsRequest(List<FieldMapping> mappings, String servicePath, String httpMethod,
                Map<String, Object> requestExample, Map<String, Object> upstreamResponseExample) {
            this(mappings, servicePath, httpMethod, requestExample, upstreamResponseExample, null);
        }
    }

    public record TestRequest(
            @NotNull Map<String, Object> request,
            Map<String, Object> upstreamResponse,
            boolean callUpstream,
            List<FieldMapping> mappings,
            String testMode) {}

    public record TestResult(
            Map<String, Object> upstreamRequest,
            Map<String, Object> upstreamResponse,
            Map<String, Object> standardResponse,
            List<ExecutionStep> steps,
            List<TraceNode> traceNodes) {}

    public record ExecutionStep(String name, String status, String detail, long durationMs) {}

    public record TraceNode(
            String key,
            String name,
            String category,
            String status,
            String detail,
            long durationMs,
            Object input,
            Object output) {}

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {}

    public record PublishRequest(String releaseNote, String releasedBy) {}

    public record LifecycleRequest(String operatedBy, String reason) {}

    public record PublishResult(
            long releaseId, int version, String checksum,
            String status, int activePlanVersion) {}

    public record ReleaseApproval(
            long id, long interfaceId, String interfaceCode, String interfaceName,
            int releaseVersion, String applicant, String releaseNote, String status,
            String approvalNode, LocalDateTime submittedAt, String reviewedBy,
            String reviewComment, LocalDateTime reviewedAt, String checksum) {}

    public record InterfaceVersionHistory(
            long interfaceId, int version, String versionType, String status, boolean active,
            String applicant, String releaseNote, String approvalNode, String reviewedBy,
            String reviewComment, LocalDateTime submittedAt, LocalDateTime reviewedAt,
            String releasedBy, LocalDateTime releasedAt, String checksum,
            boolean snapshotAvailable, boolean historyComplete) {}

    public record ApprovalDecisionRequest(
            @NotBlank String decision, @NotBlank String reviewedBy, String reviewComment) {}

    public record DashboardSummary(
            int providerCount, int dataSourceCount, int interfaceCount,
            int publishedCount, int draftCount, int activePublishedPlans) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResult(String token, String displayName, String role, String institution) {}

    public record ProviderSummary(
            long id, String providerCode, String providerName, String creditCode,
            String providerType, String contactName, String contactPhone, String contactEmail,
            String servicePhone, String contractNo, LocalDate contractStartDate,
            LocalDate contractEndDate, String securityLevel, String settlementMode,
            String officeAddress, String description, LocalDate accessDate, String messageEncoding,
            boolean chargeRequired, boolean encryptionRequired, boolean loginRequired, String status,
            String accessMode, Map<String, Object> accessConfig,
            String applicationName, String runtimeUrl, int serverPort, int configVersion) {}

    public record CreateProviderRequest(
            @NotBlank String providerCode, @NotBlank String providerName, String creditCode,
            String providerType, String contactName, String contactPhone, String contactEmail,
            String servicePhone, String contractNo, LocalDate contractStartDate,
            LocalDate contractEndDate, String securityLevel, String settlementMode,
            String officeAddress, String description, LocalDate accessDate, String messageEncoding,
            boolean chargeRequired, boolean encryptionRequired, boolean loginRequired, String status,
            String accessMode, Map<String, Object> accessConfig) {}

    public record SaveProviderRuntimeRequest(
            @NotBlank String applicationName, @NotBlank String runtimeUrl,
            @NotNull @Min(1) @Max(65535) Integer serverPort) {}

    public record ProviderAccount(
            long id, long providerId, String providerName, String username,
            String displayName, String mobile, String email, String roleCode,
            List<String> permissions, String status, LocalDateTime lastLoginAt,
            LocalDateTime createdAt) {}

    public record SaveProviderAccountRequest(
            @NotBlank String username, @NotBlank String displayName, String mobile,
            String email, @NotBlank String roleCode, @NotNull List<String> permissions,
            @NotBlank String status) {}

    public record CreateDataSourceRequest(
            long providerId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+", message = "数据源编码不能包含空格或特殊字符") String sourceCode,
            @NotBlank String sourceName,
            @NotBlank String protocolType, String endpoint, Integer defaultTimeoutMs,
            @NotBlank String environment, int instanceCount) {}

    public record SaveDataSourceSettingsRequest(
            @NotNull Long providerId, @NotBlank String sourceName,
            @NotBlank String protocolType, String endpoint, Integer defaultTimeoutMs) {}

    public record CreateResourceApiRequest(
            long dataSourceId, @NotBlank String resourceApiCode, @NotBlank String resourceApiName,
            @NotBlank String httpMethod, String upstreamUrl, String upstreamPath,
            String configScope, Integer timeoutMs, boolean generateMock,
            Map<String, Object> requestExample, Map<String, Object> responseExample,
            Map<String, String> requestHeaders) {}

    public record SaveResourceApiRequest(
            @NotBlank String configScope, String upstreamPath, String upstreamUrl,
            Integer timeoutMs, @NotNull Map<String, Object> requestExample,
            @NotNull Map<String, Object> responseExample, Map<String, String> requestHeaders) {}

    public record UpdateResourceApiMetadataRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+", message = "资源 API 编码不能包含空格或特殊字符") String resourceApiCode,
            @NotBlank String resourceApiName,
            @NotBlank @Pattern(regexp = "(?i)GET|POST|PUT|DELETE|PATCH", message = "不支持的 HTTP 请求方式") String httpMethod) {}

    public record CreatePlatformServiceRequest(
            @Min(value = 1, message = "请选择来源资源 API") long resourceApiId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]+", message = "服务编码不能包含空格或特殊字符") String serviceCode,
            @NotBlank String serviceName, @NotBlank String servicePath,
            @NotBlank @Pattern(regexp = "(?i)GET|POST|PUT|DELETE|PATCH", message = "不支持的内部 HTTP 请求方式") String httpMethod) {}

    public record SaveInterfaceConnectionRequest(
            @NotBlank String configScope, String upstreamPath, String upstreamUrl,
            Integer timeoutMs, @NotNull Map<String, Object> upstreamRequestExample,
            @NotNull Map<String, Object> upstreamResponseExample) {}

    public record VendorMockConfig(
            long id, long interfaceId, String interfaceCode, String mockName,
            Map<String, Object> responseBody, int httpStatus, int delayMs, boolean enabled,
            String endpoint) {}

    public record SaveVendorMockRequest(
            @NotBlank String mockName, @NotNull Map<String, Object> responseBody,
            int httpStatus, int delayMs, boolean enabled) {}

    public record SaveResponseCodesRequest(String vendorCodePath,
                                           @NotNull List<ResponseCodeMapping> responseCodes) {}

    public record ClientApp(
            long id, String clientCode, String clientName, String institutionCode,
            String institutionName, String ownerName, String status) {}

    public record CreateClientRequest(
            @NotBlank String clientCode, @NotBlank String clientName,
            @NotBlank String institutionCode, @NotBlank String institutionName, String ownerName) {}

    public record AuthorizationRecord(
            long id, long clientId, String clientName, long interfaceId, String interfaceName,
            String interfaceCode, String environment, int dailyQuota, LocalDate validFrom,
            LocalDate validTo, String status, String approvedBy) {}

    public record CreateAuthorizationRequest(
            long clientId, long interfaceId, @NotBlank String environment, int dailyQuota,
            @NotNull LocalDate validFrom, @NotNull LocalDate validTo, String approvedBy) {}

    public record BatchAuthorizationRequest(
            long clientId, @NotEmpty List<Long> interfaceIds, @NotBlank String environment, int dailyQuota,
            @NotNull LocalDate validFrom, @NotNull LocalDate validTo, String approvedBy) {}

    public record BillingRule(
            long id, long interfaceId, String interfaceName, String interfaceCode,
            String billingMode, BigDecimal unitPrice, String currency,
            boolean hitBillable, boolean missBillable, LocalDate effectiveDate, String status) {}

    public record SaveBillingRuleRequest(
            long interfaceId, @NotBlank String billingMode, @NotNull BigDecimal unitPrice,
            boolean hitBillable, boolean missBillable, @NotNull LocalDate effectiveDate) {}

    public record BillingSummary(
            long totalCalls, long billableCalls, BigDecimal totalAmount,
            long hitCalls, long missCalls, long errorCalls) {}

    public record UsageRecord(
            long id, String traceId, String clientName, String interfaceName,
            String interfaceCode, String resultStatus, String standardCode,
            boolean billable, BigDecimal unitPrice, BigDecimal amount, int durationMs,
            int upstreamDurationMs, int gatewayDurationMs, String requestStatus,
            String nodeName, LocalDateTime calledAt) {}

    public record CallTraceDetail(UsageRecord summary, List<TraceNode> nodes) {}

    public record MonitorSummary(
            long totalCalls, long successCalls, long errorCalls, double successRate,
            long avgDurationMs, long p95DurationMs, long avgUpstreamDurationMs,
            long activeAlerts) {}

    public record MonitorPoint(
            String timeLabel, long calls, long successCalls, long errorCalls,
            long avgDurationMs, long avgUpstreamDurationMs) {}

    public record MonitorAlert(
            String level, String title, String detail, String timeLabel) {}
}
