package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceServiceBoundaryTest {
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private InterfaceService service;
    private final Map<String, Object> request = Map.of("query_key", "001", "filters", List.of(Map.of("type", "risk")));
    private final Map<String, Object> response = Map.of("returnCode", "OK", "items", List.of(Map.of("score", 80)));

    @BeforeEach
    void setUp() throws Exception {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper, new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of(
                "request_example", mapper.writeValueAsString(request),
                "response_example", mapper.writeValueAsString(response),
                "request_headers", "{\"X-Provider-Channel\":\"EDSP\"}"));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(101L);
        resourceStatus("READY");
    }

    private void resourceStatus(String status) {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ResourceApiSummary>>any(), eq(7L)))
                .thenReturn(new ResourceApiSummary(7, 3, "RAEX_RISK_QUERY", "风险查询", "GET",
                        "https://provider.example/risk", "/risk", "INHERIT", 3000, status, 2,
                        null, LocalDateTime.now(), 0));
    }

    @Test
    void savedUntestedResourceCreatesIndependentServiceContractWithoutImplicitMappings() throws Exception {
        service.createPlatformService(new CreatePlatformServiceRequest(7, "risk_service", "风险服务", "/api/v1/risk", "post"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO api_interface"), args.capture());
        Object[] values = args.getValue();
        assertThat(values[0]).isEqualTo(7L);
        assertThat(values[1]).isEqualTo(3L);
        assertThat(values[2]).isEqualTo("RISK_SERVICE");
        assertThat(values[5]).isEqualTo("POST");
        assertThat(mapper.readValue((String) values[11], Map.class)).isEqualTo(request);
        assertThat(values[12]).isEqualTo(values[11]);
        assertThat(mapper.readValue((String) values[13], Map.class)).isEqualTo(response);
        assertThat(values[14]).isEqualTo(values[13]);
        assertThat(mapper.readValue((String) values[15], Map.class))
                .isEqualTo(Map.of("X-Provider-Channel", "EDSP"));
        verify(jdbc, never()).update(contains("INSERT INTO field_mapping"), any(Object[].class));
    }

    @Test
    void databaseResourceKeepsItsStoredRouteWhenInheritingConnectionSettings() throws Exception {
        assertThat(service.effectiveUpstreamUrl("INHERIT", "SQL", null, null, "jdbc://COMPANY_QUERY"))
                .isEqualTo("jdbc://COMPANY_QUERY");
        assertThat(service.effectiveUpstreamUrl("INHERIT", "HTTP", "https://provider.example", "/query", "old"))
                .isEqualTo("https://provider.example/query");

        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ResourceApiSummary>>any(), eq(7L)))
                .thenReturn(new ResourceApiSummary(7, 3, "COMPANY_QUERY", "企业工商信息查询接口01", "POST",
                        "jdbc://COMPANY_QUERY", null, "INHERIT", 3000, "READY", 2,
                        null, LocalDateTime.now(), 0));
        when(jdbc.queryForMap(anyString(), eq(7L))).thenReturn(Map.of(
                "request_example", "{}", "response_example", "{}", "request_headers", "{}",
                "execution_mode", "SQL", "sql_template", "SELECT name FROM company WHERE id = :id",
                "sql_parameters", "{\"id\":\"STRING\"}"));

        service.createPlatformService(new CreatePlatformServiceRequest(7, "company_service", "工商查询服务",
                "/api/v1/company/01", "POST"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO api_interface")
                && sql.contains("?,?,?,?,?,?,?,?,0,?,?,?,'READY','DRAFT',1")), args.capture());
        assertThat(args.getValue()[6]).isEqualTo("jdbc://COMPANY_QUERY");
        assertThat(args.getValue()[17]).isEqualTo("SQL");
    }

    @Test
    void unsavedResourceCannotCreateService() {
        resourceStatus("DRAFT");
        assertThatThrownBy(() -> service.createPlatformService(
                new CreatePlatformServiceRequest(7, "RISK_SERVICE", "风险服务", "/api/v1/risk", "POST")))
                .hasMessageContaining("尚未保存");
        verify(jdbc, never()).update(contains("INSERT INTO api_interface"), any(Object[].class));
    }

    @Test
    void resourceTestUsesOriginalPayloadAndReportsApplicationRouteWithoutStandardization() {
        InterfaceService rawService = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment()) {
            @Override
            Map<String, Object> invokeThroughDataApplication(InterfaceSummary summary, Map<String, Object> body,
                                                               String traceId, Map<String, String> headers) {
                assertThat(summary.dataSourceId()).isEqualTo(3);
                assertThat(body).isEqualTo(request);
                assertThat(headers).containsEntry("X-Provider-Channel", "EDSP");
                return response;
            }
        };
        when(jdbc.queryForList(contains("p.application_name"), eq(String.class), eq(3L)))
                .thenReturn(List.of("jsnx-edsp-data-raex"));
        when(jdbc.queryForList(contains("p.runtime_url"), eq(String.class), eq(3L)))
                .thenReturn(List.of("http://provider-app:8181"));

        TestResult result = rawService.verifyResourceApi(7);

        assertThat(result.upstreamRequest()).isEqualTo(request);
        assertThat(result.upstreamResponse()).isEqualTo(response);
        assertThat(result.traceNodes()).extracting(TraceNode::key).containsExactly("DATA_SOURCE", "VENDOR");
        Map<?, ?> invocation = (Map<?, ?>) result.traceNodes().get(0).output();
        assertThat(invocation.get("applicationEndpoint")).isEqualTo("http://provider-app:8181");
        assertThat(invocation.get("method")).isEqualTo("GET");
    }
}
