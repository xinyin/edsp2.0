package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ProviderRoutingOwnershipTest {
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
    }

    @Test
    void publishedGatewaySnapshotReadsRuntimeFromProvider() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        service.publishedRoutes();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains("JOIN data_provider p ON p.id=ds.provider_id")
                .contains("p.application_name,p.runtime_url")
                .doesNotContain("ds.application_name,ds.runtime_url");
    }

    @Test
    void esbResolutionReturnsProviderOwnedRoute() {
        when(jdbc.queryForList(anyString(), eq("COMPANY_QUERY"))).thenReturn(List.of(Map.of(
                "id", 1L,
                "interface_code", "COMPANY_QUERY",
                "provider_code", "BAIRONG",
                "application_name", "jsnx-edsp-data-bairong",
                "runtime_url", "http://127.0.0.1:8201"
        )));

        Map<String, Object> route = service.resolveInterfaceRoute("COMPANY_QUERY", true);

        assertThat(route).containsEntry("provider_code", "BAIRONG")
                .containsEntry("application_name", "jsnx-edsp-data-bairong");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("COMPANY_QUERY"));
        assertThat(sql.getValue()).contains("JOIN data_provider p ON p.id=ds.provider_id");
    }
}
