package com.jsnx.edsp.web;

import com.jsnx.edsp.model.ApiModels.*;
import com.jsnx.edsp.plan.SecurityAlgorithm;
import com.jsnx.edsp.plan.SecurityAlgorithmRegistry;
import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.service.PublishedPlanRegistry;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final InterfaceService service;
    private final PublishedPlanRegistry plans;

    public ApiController(InterfaceService service, PublishedPlanRegistry plans) {
        this.service = service;
        this.plans = plans;
    }

    @PostMapping("/auth/login")
    public LoginResult login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        http.setAttribute(PlatformOperationAudit.AUTH_OPERATOR_ATTRIBUTE, auditOperator(request.username()));
        LoginResult user = service.login(request);
        var previous = http.getSession(false);
        if (previous != null) previous.invalidate();
        var session = http.getSession(true);
        session.setMaxInactiveInterval(1800);
        String token = java.util.UUID.randomUUID().toString();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, token);
        session.setAttribute("edsp.operator.username", request.username());
        session.setAttribute("edsp.operator.role", user.role());
        return new LoginResult(token, user.displayName(), user.role(), user.institution());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        var session = http.getSession(false);
        if (session == null || session.getAttribute(ChainTestToolController.SESSION_TOKEN) == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Object operator = session.getAttribute("edsp.operator.username");
        http.setAttribute(PlatformOperationAudit.AUTH_OPERATOR_ATTRIBUTE,
                auditOperator(operator == null ? null : String.valueOf(operator)));
        session.invalidate();
        return ResponseEntity.noContent().build();
    }

    private static String auditOperator(String username) {
        if (username == null || username.isBlank()) return "UNKNOWN";
        return username.length() > 64 ? username.substring(0, 64) : username;
    }

    @GetMapping("/dashboard")
    public DashboardSummary dashboard() { return service.dashboard(); }

    @GetMapping("/data-sources")
    public List<DataSourceSummary> dataSources() { return service.dataSources(); }

    @GetMapping("/providers")
    public List<ProviderSummary> providers() { return service.providers(); }

    @PostMapping("/providers")
    public ProviderSummary createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return service.createProvider(request);
    }

    @GetMapping("/providers/{id}")
    public ProviderSummary provider(@PathVariable long id) { return service.provider(id); }

    @PutMapping("/providers/{id}")
    public ProviderSummary updateProvider(@PathVariable long id, @Valid @RequestBody CreateProviderRequest request) {
        return service.updateProvider(id, request);
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<Void> deleteProvider(@PathVariable long id) {
        service.deleteProvider(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/providers/{id}/accounts")
    public List<ProviderAccount> providerAccounts(@PathVariable long id) { return service.providerAccounts(id); }

    @PostMapping("/providers/{id}/accounts")
    public ProviderAccount createProviderAccount(@PathVariable long id, @Valid @RequestBody SaveProviderAccountRequest request) {
        return service.createProviderAccount(id, request);
    }

    @PutMapping("/provider-accounts/{id}")
    public ProviderAccount updateProviderAccount(@PathVariable long id, @Valid @RequestBody SaveProviderAccountRequest request) {
        return service.updateProviderAccount(id, request);
    }

    @PostMapping("/data-sources")
    public DataSourceSummary createDataSource(@Valid @RequestBody CreateDataSourceRequest request) {
        return service.createDataSource(request);
    }

    @PutMapping("/data-sources/{id}/settings")
    public DataSourceSummary saveDataSourceSettings(@PathVariable long id,
                                                     @Valid @RequestBody SaveDataSourceSettingsRequest request) {
        return service.saveDataSourceSettings(id, request);
    }

    @GetMapping("/resource-apis")
    public List<ResourceApiSummary> resourceApis(@RequestParam(required = false) Long dataSourceId) {
        return service.resourceApis(dataSourceId);
    }

    @GetMapping("/data-sources/{id}/database-tables")
    public Map<String, Object> databaseTables(@PathVariable long id) {
        return service.databaseMetadata(id, null);
    }

    @GetMapping("/data-sources/{id}/database-columns")
    public Map<String, Object> databaseColumns(@PathVariable long id, @RequestParam String table) {
        return service.databaseMetadata(id, table);
    }

    @PostMapping("/resource-apis")
    public ResourceApiSummary createResourceApi(@Valid @RequestBody CreateResourceApiRequest request) {
        return service.createResourceApi(request);
    }

    @GetMapping("/resource-apis/{id}")
    public ResourceApiDetail resourceApi(@PathVariable long id) { return service.resourceApi(id); }

    @PutMapping("/resource-apis/{id}")
    public ResourceApiDetail saveResourceApi(@PathVariable long id,
                                              @Valid @RequestBody SaveResourceApiRequest request) {
        return service.saveResourceApi(id, request);
    }

    @PutMapping("/resource-apis/{id}/metadata")
    public ResourceApiSummary updateResourceApiMetadata(@PathVariable long id,
                                                         @Valid @RequestBody UpdateResourceApiMetadataRequest request) {
        return service.updateResourceApiMetadata(id, request);
    }

    @PostMapping("/resource-apis/{id}/verify")
    public TestResult verifyResourceApi(@PathVariable long id) { return service.verifyResourceApi(id); }

    @PostMapping("/platform-services")
    public InterfaceSummary createPlatformService(@Valid @RequestBody CreatePlatformServiceRequest request) {
        return service.createPlatformService(request);
    }

    @GetMapping("/interfaces")
    public List<InterfaceSummary> interfaces(@RequestParam(required = false) Long dataSourceId) {
        return service.interfaces(dataSourceId);
    }

    @GetMapping("/release-services")
    public ReleaseServicePage releaseServices(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(defaultValue = "ALL") String runStatus,
            @RequestParam(defaultValue = "ALL") String authorizationStatus,
            @RequestParam(defaultValue = "ALL") String environment,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.releaseServices(keyword, providerId, dataSourceId, runStatus, authorizationStatus, environment, page, size);
    }

    @DeleteMapping("/interfaces/{id}")
    public ResponseEntity<Void> deleteInterface(@PathVariable long id) {
        service.deleteInterface(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/interfaces/{id}/offline")
    public InterfaceSummary offline(@PathVariable long id, @RequestBody(required = false) LifecycleRequest request) {
        return service.offlineInterface(id, request);
    }

    @PostMapping("/interfaces/{id}/online")
    public InterfaceSummary online(@PathVariable long id, @RequestBody(required = false) LifecycleRequest request) {
        return service.onlineInterface(id, request);
    }

    @GetMapping("/interfaces/{id}")
    public InterfaceDetail detail(@PathVariable long id) { return service.interfaceDetail(id); }

    @GetMapping("/interfaces/{id}/versions")
    public List<InterfaceVersionHistory> interfaceVersions(@PathVariable long id) {
        return service.interfaceVersions(id);
    }

    @PostMapping("/interfaces/{id}/sync-resource")
    public InterfaceDetail syncResourceContract(@PathVariable long id) {
        return service.syncResourceContract(id);
    }

    @PutMapping("/interfaces/{id}/connection-config")
    public InterfaceDetail saveConnectionConfig(
            @PathVariable long id, @Valid @RequestBody SaveInterfaceConnectionRequest request) {
        return service.saveConnectionConfig(id, request);
    }

    @PutMapping("/interfaces/{id}/mappings")
    public List<FieldMapping> saveMappings(@PathVariable long id, @Valid @RequestBody SaveMappingsRequest request) {
        return service.saveMappings(id, request);
    }

    @PutMapping("/interfaces/{id}/response-codes")
    public List<ResponseCodeMapping> saveResponseCodes(
            @PathVariable long id, @Valid @RequestBody SaveResponseCodesRequest request) {
        return service.saveResponseCodes(id, request);
    }

    @GetMapping("/interfaces/{id}/security-policy")
    public SecurityPolicy securityPolicy(@PathVariable long id) { return service.securityPolicy(id); }

    @PutMapping("/interfaces/{id}/security-policy")
    public SecurityPolicy saveSecurityPolicy(@PathVariable long id,
                                              @Valid @RequestBody SaveSecurityPolicyRequest request) {
        return service.saveSecurityPolicy(id, request);
    }

    @PostMapping("/interfaces/{id}/validate")
    public ValidationResult validate(@PathVariable long id) { return service.validate(id); }

    @GetMapping("/interfaces/{id}/validation-status")
    public Map<String, Boolean> validationStatus(@PathVariable long id) { return service.validationStatus(id); }

    @GetMapping("/resource-apis/{id}/validation-status")
    public Map<String, Boolean> resourceValidationStatus(@PathVariable long id) {
        return service.resourceValidationStatus(id);
    }

    @PostMapping("/interfaces/{id}/test")
    public TestResult test(@PathVariable long id, @Valid @RequestBody TestRequest request) {
        return service.test(id, request);
    }

    @GetMapping("/interfaces/{id}/vendor-mock")
    public VendorMockConfig vendorMock(@PathVariable long id) { return service.vendorMock(id); }

    @PutMapping("/interfaces/{id}/vendor-mock")
    public VendorMockConfig saveVendorMock(
            @PathVariable long id, @Valid @RequestBody SaveVendorMockRequest request) {
        return service.saveVendorMock(id, request);
    }

    @PostMapping("/interfaces/{id}/publish")
    public ReleaseApproval publish(@PathVariable long id, @RequestBody PublishRequest request) {
        return service.submitApproval(id, request);
    }

    @PostMapping("/interfaces/{id}/approval-requests")
    public ReleaseApproval submitApproval(@PathVariable long id, @RequestBody PublishRequest request) {
        return service.submitApproval(id, request);
    }

    @PostMapping("/interfaces/{id}/direct-publish")
    public InterfaceSummary publishDirect(@PathVariable long id, @RequestBody(required = false) PublishRequest request) {
        return service.publishDirect(id, request);
    }

    @GetMapping("/approvals")
    public List<ReleaseApproval> approvals(@RequestParam(required = false) String status) {
        return service.approvals(status);
    }

    @GetMapping("/approvals/{id}")
    public ReleaseApproval approval(@PathVariable long id) { return service.approval(id); }

    @PostMapping("/approvals/{id}/decision")
    public ReleaseApproval decideApproval(@PathVariable long id, @Valid @RequestBody ApprovalDecisionRequest request) {
        return service.decideApproval(id, request);
    }

    @GetMapping("/interfaces/{interfaceId}/published-plan")
    public Object publishedPlan(@PathVariable long interfaceId) {
        return service.publishedPlanConfiguration(interfaceId);
    }

    @GetMapping("/clients")
    public List<ClientApp> clients() { return service.clients(); }

    @PostMapping("/clients")
    public ClientApp createClient(@Valid @RequestBody CreateClientRequest request) {
        return service.createClient(request);
    }

    @GetMapping("/authorizations")
    public List<AuthorizationRecord> authorizations() { return service.authorizations(); }

    @GetMapping("/authorizations/summary")
    public Map<String, Long> authorizationSummary() { return Map.of("activeCount", service.activeAuthorizationCount()); }

    @GetMapping("/internal/authorizations/check")
    public Map<String, Object> authorize(@RequestParam String clientCode,
                                         @RequestParam String servicePath,
                                         @RequestParam(required = false) String apiCode,
                                         @RequestParam(defaultValue = "TEST") String environment) {
        return service.authorize(clientCode, servicePath, apiCode, environment);
    }

    @GetMapping("/internal/interfaces/resolve")
    public Map<String, Object> resolveInterface(@RequestParam String apiCode,
                                                 @RequestParam(defaultValue = "false") boolean includeDraft) {
        return service.resolveInterfaceRoute(apiCode, includeDraft);
    }

    @GetMapping("/internal/routes/published")
    public List<Map<String, Object>> publishedRoutes() { return service.publishedRoutes(); }

    @PostMapping("/authorizations")
    public AuthorizationRecord createAuthorization(@Valid @RequestBody CreateAuthorizationRequest request) {
        return service.createAuthorization(request);
    }

    @PostMapping("/authorizations/batch")
    public List<AuthorizationRecord> createAuthorizations(@Valid @RequestBody BatchAuthorizationRequest request) {
        return service.createAuthorizations(request);
    }

    @GetMapping("/billing/rules")
    public List<BillingRule> billingRules() { return service.billingRules(); }

    @PutMapping("/billing/rules")
    public BillingRule saveBillingRule(@Valid @RequestBody SaveBillingRuleRequest request) {
        return service.saveBillingRule(request);
    }

    @GetMapping("/billing/summary")
    public BillingSummary billingSummary() { return service.billingSummary(); }

    @GetMapping("/security-algorithms")
    public List<SecurityAlgorithm.Descriptor> securityAlgorithms() {
        return SecurityAlgorithmRegistry.descriptors();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(InterfaceService.TrackedCallException.class)
    public ResponseEntity<Map<String, Object>> handleTrackedCallFailure(InterfaceService.TrackedCallException e) {
        List<TraceNode> traceNodes = List.of();
        try {
            traceNodes = service.callTrace(e.traceId()).nodes();
        } catch (RuntimeException ignored) {
            // The execution error remains useful even when trace persistence is unavailable.
        }
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "TEST_EXECUTION_FAILED",
                "message", e.getMessage() == null ? "接口联调执行失败" : e.getMessage(),
                "traceId", e.traceId(),
                "traceNodes", traceNodes));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "DUPLICATE_RESOURCE", "message", "接口编码或服务路径已存在，请修改后重试"));
    }
}
