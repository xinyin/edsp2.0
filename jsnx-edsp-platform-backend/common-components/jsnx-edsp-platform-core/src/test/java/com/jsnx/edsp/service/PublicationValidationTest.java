package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jsnx.edsp.model.ApiModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PublicationValidationTest {
    private static final long SERVICE_ID = 42L;
    private static final long RESOURCE_ID = 7L;
    private static final String URL = "https://supplier.example/company";
    private static final Map<String, Object> REQUEST = Map.of("query_key", "企业001");
    private static final Map<String, Object> RESPONSE = Map.of("result", Map.of("code", "0000"),
            "data", Map.of("names", List.of("企业001", "企业002")));
    private JdbcTemplate jdbc;
    private InterfaceService service;
    private ObjectMapper mapper;
    private PublishedPlanRegistry plans;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        mapper = new ObjectMapper().findAndRegisterModules();
        plans = new PublishedPlanRegistry();
        service = spy(new InterfaceService(jdbc, new JsonSupport(mapper), mapper, plans, new MockEnvironment()));
        doReturn(detail()).when(service).interfaceDetail(SERVICE_ID);
        doReturn(resource(URL, "POST", 3000, "READY", REQUEST, RESPONSE)).when(service).resourceApi(RESOURCE_ID);
        when(jdbc.queryForMap(contains("FOR UPDATE"), eq(SERVICE_ID)))
                .thenReturn(Map.of("status", "DRAFT"));
        doReturn(new ValidationResult(true, List.of(), List.of())).when(service).validate(SERVICE_ID);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<InterfaceSummary>>any(), eq(SERVICE_ID)))
                .thenReturn(detail().summary());
        when(jdbc.queryForList(contains("SELECT p.runtime_url FROM data_source"), eq(String.class), eq(1L)))
                .thenReturn(List.of("http://127.0.0.1:1"));
    }

    @Test
    void publishesValidConfigurationWithoutOptionalLinkTest() {
        service.publishDirect(SERVICE_ID, new PublishRequest("管理员", "首次上线"));
        assertThat(plans.size()).isEqualTo(1);
        verify(jdbc).update("UPDATE api_interface SET status='PUBLISHED',published_version=? WHERE id=?", 3, SERVICE_ID);
        verify(jdbc).update(contains("INSERT INTO published_route"), eq(SERVICE_ID), eq("COMPANY_QUERY"),
                eq("/services/company"), eq("POST"), eq(3), eq(1L));
    }

    @Test
    void publishingDoesNotReadOptionalLinkTestProof() {
        service.publishDirect(SERVICE_ID, new PublishRequest("管理员", "上线"));
        verify(jdbc, never()).queryForList(contains("FROM interface_validation_record"), eq(String.class),
                eq(SERVICE_ID), anyString());
        assertThat(plans.size()).isEqualTo(1);
    }

    @Test
    void refusesToPublishWithoutProviderApplicationRoute() {
        when(jdbc.queryForList(contains("SELECT p.runtime_url FROM data_source"), eq(String.class), eq(1L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.publishDirect(SERVICE_ID, new PublishRequest("管理员", "上线")))
                .hasMessageContaining("资源提供商")
                .hasMessageContaining("处理应用");
        assertThat(plans.size()).isZero();
    }

    @Test
    void upstreamTestRefusesDirectConnectionWhenProviderRouteIsMissing() {
        when(jdbc.queryForList(contains("SELECT p.runtime_url FROM data_source"), eq(String.class), eq(1L)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.invokeThroughDataApplication(detail().summary(), REQUEST, "TEST-NO-ROUTE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许绕过应用直连外围系统");
    }

    @Test
    void upstreamTestSendsRequestToProviderApplicationNotToVendorUrl() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicReference<Map<?, ?>>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/adapter/execute", exchange -> {
            received.set(mapper.readValue(exchange.getRequestBody(), Map.class));
            byte[] response = mapper.writeValueAsBytes(Map.of("success", true, "upstreamResponse", RESPONSE));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            when(jdbc.queryForList(contains("SELECT p.runtime_url FROM data_source"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("http://127.0.0.1:" + server.getAddress().getPort()));
            when(jdbc.queryForList(contains("SELECT p.provider_code FROM"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("BAIRONG"));
            when(jdbc.queryForList(contains("SELECT source_code FROM"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("COMPANY"));
            when(jdbc.queryForList(contains("p.access_mode,p.access_config"), eq(1L)))
                    .thenReturn(List.of(Map.of("provider_code", "BAIRONG", "access_mode", "HTTP", "access_config", "{}")));
            when(jdbc.queryForObject("SELECT http_method FROM resource_api WHERE id=?", String.class, RESOURCE_ID))
                    .thenReturn("GET");
            assertThat(service.invokeThroughDataApplication(detail().summary(), REQUEST, "APP-ROUTE-TEST"))
                    .isEqualTo(RESPONSE);
            assertThat(received.get().get("providerCode")).isEqualTo("BAIRONG");
            assertThat(received.get().get("dataSourceCode")).isEqualTo("COMPANY");
            assertThat(received.get().get("upstreamUrl")).isEqualTo(URL);
            assertThat(received.get().get("upstreamHttpMethod")).isEqualTo("GET");
            assertThat(received.get().get("requestBody")).isEqualTo(REQUEST);
        } finally { server.stop(0); }
    }

    @Test
    void providerConnectionTimeoutIsReportedWithoutWhitelabelHtml() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/adapter/execute", exchange -> {
            byte[] response = mapper.writeValueAsBytes(Map.of("success", false, "errorType", "TRANSPORT",
                    "errorMessage", "连接上游超时（3000ms）；请检查网络连通性"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            when(jdbc.queryForList(contains("SELECT p.runtime_url FROM data_source"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("http://127.0.0.1:" + server.getAddress().getPort()));
            when(jdbc.queryForList(contains("SELECT p.provider_code FROM"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("CSSJ"));
            when(jdbc.queryForList(contains("SELECT source_code FROM"), eq(String.class), eq(1L)))
                    .thenReturn(List.of("PRD_01"));
            when(jdbc.queryForList(contains("p.access_mode,p.access_config"), eq(1L)))
                    .thenReturn(List.of(Map.of("provider_code", "CSSJ", "access_mode", "HTTP", "access_config", "{}")));
            assertThatThrownBy(() -> service.invokeThroughDataApplication(detail().summary(), REQUEST, "TIMEOUT-TRACE"))
                    .hasMessageContaining("连接上游超时（3000ms）")
                    .hasMessageNotContaining("Whitelabel")
                    .hasMessageNotContaining("上游返回 HTTP 500");
        } finally { server.stop(0); }
    }

    @Test
    void historicalTimestampWithoutConfigurationProofIsNotAccepted() {
        // The fixture has verifiedAt; a timestamp alone cannot prove which configuration was tested.
        when(jdbc.queryForList("SELECT verified_checksum FROM resource_api WHERE id=?", String.class, RESOURCE_ID))
                .thenReturn(Collections.singletonList(null));
        assertThat(service.validationStatus(SERVICE_ID).get("upstreamVerified")).isFalse();
    }

    @Test
    void resourceAddressChangeInvalidatesPreviousProof() throws Exception {
        resourceVerified();
        doReturn(resource(URL + "/v2", "POST", 3000, "READY", REQUEST, RESPONSE))
                .when(service).resourceApi(RESOURCE_ID);
        assertThat(service.resourceValidationStatus(RESOURCE_ID).get("upstreamVerified")).isFalse();
        assertThat(service.validationStatus(SERVICE_ID).get("upstreamVerified")).isFalse();
    }

    @Test
    void methodTimeoutAndBothMessageContractsArePartOfVerification() throws Exception {
        resourceVerified();
        for (ResourceApiDetail changed : List.of(
                resource(URL, "GET", 3000, "READY", REQUEST, RESPONSE),
                resource(URL, "POST", 5000, "READY", REQUEST, RESPONSE),
                resource(URL, "POST", 3000, "READY", Map.of("enterpriseName", "企业001"), RESPONSE),
                resource(URL, "POST", 3000, "READY", REQUEST, Map.of("code", "OK")))) {
            doReturn(changed).when(service).resourceApi(RESOURCE_ID);
            assertThat(service.resourceValidationStatus(RESOURCE_ID).get("upstreamVerified")).isFalse();
        }
    }

    @Test
    void independentlyVerifiedServiceCanUseItsOwnUpstreamConfiguration() throws Exception {
        when(jdbc.queryForList(contains("FROM interface_validation_record"), eq(String.class),
                eq(SERVICE_ID), eq("UPSTREAM"))).thenReturn(List.of(upstreamFingerprint()));
        assertThat(service.validationStatus(SERVICE_ID).get("upstreamVerified")).isTrue();
    }

    @Test
    void jsonKeyOrderDoesNotInvalidateIdenticalContracts() throws Exception {
        resourceVerified();
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("data", RESPONSE.get("data"));
        reordered.put("result", RESPONSE.get("result"));
        doReturn(resource(URL, "POST", 3000, "READY", REQUEST, reordered)).when(service).resourceApi(RESOURCE_ID);
        assertThat(service.validationStatus(SERVICE_ID).get("upstreamVerified")).isTrue();
    }

    @Test
    void aDraftResourceCannotSupplyValidationProof() throws Exception {
        resourceVerified();
        doReturn(resource(URL, "POST", 3000, "DRAFT", REQUEST, RESPONSE)).when(service).resourceApi(RESOURCE_ID);
        assertThat(service.validationStatus(SERVICE_ID).get("upstreamVerified")).isFalse();
    }

    private void resourceVerified() throws Exception {
        when(jdbc.queryForList("SELECT verified_checksum FROM resource_api WHERE id=?", String.class, RESOURCE_ID))
                .thenReturn(List.of(upstreamFingerprint()));
    }

    @SuppressWarnings("unchecked")
    private String configurationFingerprint() throws Exception {
        Map<String, Object> config = mapper.convertValue(detail(), Map.class);
        Map<String, Object> summary = (Map<String, Object>) config.get("summary");
        summary.remove("status"); summary.remove("updatedAt"); summary.remove("publishedVersion");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(config)));
    }

    @Test
    void preReleaseCallsDataApplicationAndNeverUsesSubmittedMockResponse() {
        doReturn(RESPONSE).when(service).invokeThroughDataApplication(any(), eq(REQUEST), anyString());
        TestResult result = service.test(SERVICE_ID,
                new TestRequest(REQUEST, Map.of("fake", "response"), false, List.of(), "PLATFORM"));
        assertThat(result.upstreamResponse()).isEqualTo(RESPONSE);
        verify(service).invokeThroughDataApplication(any(), eq(REQUEST), anyString());
        verify(jdbc).update(contains("INSERT INTO interface_validation_record"), eq(SERVICE_ID), eq("DRAFT_TEST"), anyString());
        assertThat(result.traceNodes()).anyMatch(n -> n.key().equals("GATEWAY") && n.status().equals("SKIPPED"));
    }

    @Test
    void upstreamBusinessErrorCannotProduceReleaseProof() {
        doReturn(Map.of("result", Map.of("code", "9999"))).when(service)
                .invokeThroughDataApplication(any(), anyMap(), anyString());
        TestResult result = service.test(SERVICE_ID, new TestRequest(REQUEST, null, true, List.of(), "PLATFORM"));
        assertThat(result.traceNodes()).anyMatch(n -> n.key().equals("RESPONSE_MAPPING") && n.status().equals("ERROR"));
        verify(jdbc, never()).update(contains("INSERT INTO interface_validation_record"), eq(SERVICE_ID), eq("DRAFT_TEST"), anyString());
    }

    @Test
    void responseTraceExplainsMissingAndUnmappedSupplierCodes() {
        doReturn(Map.of("page", 1)).when(service)
                .invokeThroughDataApplication(any(), anyMap(), anyString());
        TestResult missing = service.test(SERVICE_ID, new TestRequest(REQUEST, null, true, List.of(), "PLATFORM"));
        assertThat(missing.traceNodes()).filteredOn(node -> node.key().equals("RESPONSE_MAPPING"))
                .first().extracting(TraceNode::detail).asString()
                .contains("未从上游响应读取到业务返回码", "result.code", "HTTP 成功判定");

        doReturn(Map.of("result", Map.of("code", "7777"))).when(service)
                .invokeThroughDataApplication(any(), anyMap(), anyString());
        TestResult unmapped = service.test(SERVICE_ID, new TestRequest(REQUEST, null, true, List.of(), "PLATFORM"));
        assertThat(unmapped.traceNodes()).filteredOn(node -> node.key().equals("RESPONSE_MAPPING"))
                .first().extracting(TraceNode::detail).asString()
                .contains("7777", "未配置结果码映射");
    }

    @Test
    void transportFailureCannotProduceReleaseProofAndIsTraced() {
        doThrow(new IllegalStateException("上游返回 HTTP 400")).when(service)
                .invokeThroughDataApplication(any(), anyMap(), anyString());
        assertThatThrownBy(() -> service.test(SERVICE_ID, new TestRequest(REQUEST, null, true, List.of(), "PLATFORM")))
                .isInstanceOf(InterfaceService.TrackedCallException.class).hasMessageContaining("HTTP 400");
        verify(jdbc, never()).update(contains("INSERT INTO interface_validation_record"), eq(SERVICE_ID), eq("DRAFT_TEST"), anyString());
        assertThat(mockingDetails(jdbc).getInvocations()).anyMatch(invocation -> Arrays.asList(invocation.getArguments()).contains("ERROR"));
    }

    @Test
    void unsavedInputCannotCertifySavedDraft() {
        assertThatThrownBy(() -> service.test(SERVICE_ID,
                new TestRequest(Map.of("query_key", "未保存样例"), null, true, List.of(), "PLATFORM")))
                .hasMessageContaining("请先保存");
        verify(service, never()).invokeThroughDataApplication(any(), anyMap(), anyString());
    }

    @Test
    void changedDraftDuringRealCallDoesNotReceiveProof() {
        doAnswer(invocation -> {
            InterfaceDetail original = detail();
            InterfaceDetail changed = new InterfaceDetail(original.summary(), original.requestSchema(), original.responseSchema(),
                    Map.of("query_key", "changed"), REQUEST, RESPONSE, RESPONSE, 3000, null, "DATA_SOURCE", 0,
                    "result.code", List.of(), original.responseCodes(), original.securityPolicy());
            doReturn(changed).when(service).interfaceDetail(SERVICE_ID);
            return RESPONSE;
        }).when(service).invokeThroughDataApplication(any(), anyMap(), anyString());
        assertThatThrownBy(() -> service.test(SERVICE_ID, new TestRequest(REQUEST, null, true, List.of(), "PLATFORM")))
                .hasMessageContaining("测试期间草稿配置已变更");
        verify(jdbc, never()).update(contains("INSERT INTO interface_validation_record"), eq(SERVICE_ID), eq("DRAFT_TEST"), anyString());
    }

    @Test
    void syncUpdatesPassThroughBaselineButNeverTouchesPublishedPlan() {
        Map<String, Object> updatedRequest = Map.of("company_name", "企业001");
        doReturn(resource(URL + "/v2", "POST", 3000, "READY", updatedRequest, RESPONSE))
                .when(service).resourceApi(RESOURCE_ID);
        service.syncResourceContract(SERVICE_ID);
        verify(jdbc).update(contains("upstream_status='READY',current_version=COALESCE(published_version,0)+1"),
                eq("OVERRIDE"), eq("/company"), eq(URL + "/v2"), isNull(),
                eq(new JsonSupport(mapper).write(updatedRequest)), eq(new JsonSupport(mapper).write(RESPONSE)),
                eq("{}"),
                eq(new JsonSupport(mapper).write(updatedRequest)), eq(new JsonSupport(mapper).write(RESPONSE)), eq(SERVICE_ID));
        assertThat(plans.size()).isZero();
        assertThat(service.validationStatus(SERVICE_ID).get("resourceContractChanged")).isTrue();
    }

    private String upstreamFingerprint() throws Exception {
        Map<String, Object> contract = Map.of("httpMethod", "POST", "upstreamUrl", URL, "timeoutMs", 3000,
                "request", REQUEST, "response", RESPONSE);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(contract)));
    }

    private InterfaceDetail detail() {
        InterfaceSummary summary = new InterfaceSummary(SERVICE_ID, 1L, "COMPANY_QUERY", "企业查询",
                "/services/company", "POST", URL, "/company", "OVERRIDE", "DIRECT", null, 3000,
                "READY", "DRAFT", 3, null, LocalDateTime.of(2026, 10, 8, 10, 0),
                RESOURCE_ID, "COMPANY_RESOURCE", "企业资源接口");
        return new InterfaceDetail(summary, Map.of(), Map.of(), REQUEST, REQUEST, RESPONSE, RESPONSE,
                3000, null, "DATA_SOURCE", 0, "result.code", List.of(),
                List.of(new ResponseCodeMapping(1L, "0000", "EXT-SUCCESS", "HIT", false, false)),
                new SecurityPolicy(SERVICE_ID, false, "NONE", "NONE", "", "", "", "signature", ""));
    }

    private ResourceApiDetail resource(String url, String method, int timeout, String status,
                                       Map<String, Object> request, Map<String, Object> response) {
        LocalDateTime verified = LocalDateTime.of(2026, 10, 8, 9, 0);
        return new ResourceApiDetail(new ResourceApiSummary(RESOURCE_ID, 1L, "COMPANY_RESOURCE", "企业资源接口",
                method, url, "/company", "OVERRIDE", timeout, status, 2, verified, verified, 1),
                request, response, Map.of(), null, "DATA_SOURCE");
    }
}
