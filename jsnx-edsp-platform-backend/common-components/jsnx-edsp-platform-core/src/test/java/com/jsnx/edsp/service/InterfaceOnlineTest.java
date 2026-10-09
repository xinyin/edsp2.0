package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterfaceOnlineTest {
    private static final long ID = 42L;
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private PublishedPlanRegistry plans;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        mapper = new ObjectMapper().findAndRegisterModules();
        plans = new PublishedPlanRegistry();
        MockEnvironment environment = new MockEnvironment().withProperty("edsp.gateway-refresh-urls", "");
        service = spy(new InterfaceService(jdbc, new JsonSupport(mapper), mapper, plans, environment));
        when(jdbc.queryForList(contains("SELECT runtime_url FROM data_source"), eq(String.class), eq(1L)))
                .thenReturn(List.of("disabled"));
    }

    @Test
    void restoresPublishedV2AndKeepsV3AsDraft() throws Exception {
        InterfaceSummary publishedV2 = summary(2, 2, "PUBLISHED", "/published/v2");
        InterfaceSummary currentV3 = summary(3, 2, "DRAFT", "/draft/v3");
        when(jdbc.queryForMap(contains("SELECT status,published_version,current_version"), eq(ID)))
                .thenReturn(Map.of("status", "OFFLINE", "published_version", 2, "current_version", 3));
        when(jdbc.queryForList(contains("FROM release_plan_snapshot"), eq(ID), eq(2)))
                .thenReturn(List.of(Map.of("plan_json", snapshot(publishedV2), "checksum", "v2-checksum")));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<InterfaceSummary>>any(), eq(ID)))
                .thenReturn(currentV3);

        InterfaceSummary result = service.onlineInterface(ID,
                new LifecycleRequest("平台管理员", "恢复线上服务"));

        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.currentVersion()).isEqualTo(3);
        assertThat(result.publishedVersion()).isEqualTo(2);
        assertThat(plans.get(ID)).isNotNull();
        assertThat(plans.get(ID).version()).isEqualTo(2);
        assertThat(plans.get(ID).summary().servicePath()).isEqualTo("/published/v2");
        verify(jdbc).update(contains("INSERT INTO published_route"), eq(ID), eq("COMPANY_QUERY"),
                eq("/published/v2"), eq("POST"), eq(2), eq(1L));
        verify(jdbc).update("UPDATE api_interface SET status=? WHERE id=?", "DRAFT", ID);
        verify(jdbc).update(contains("'ONLINE'"), eq(ID), eq("平台管理员"), eq("恢复线上服务"));
    }

    @Test
    void refusesToRestoreDraftWhenPublishedSnapshotIsMissing() {
        when(jdbc.queryForMap(contains("SELECT status,published_version,current_version"), eq(ID)))
                .thenReturn(Map.of("status", "OFFLINE", "published_version", 2, "current_version", 3));
        when(jdbc.queryForList(contains("FROM release_plan_snapshot"), eq(ID), eq(2))).thenReturn(List.of());

        assertThatThrownBy(() -> service.onlineInterface(ID, null))
                .hasMessageContaining("V2 缺少执行计划快照")
                .hasMessageContaining("V3 草稿");
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(invocation ->
                invocation.getMethod().getName().equals("update")
                        && invocation.getArguments().length > 0
                        && String.valueOf(invocation.getArguments()[0]).contains("INSERT INTO published_route"));
        assertThat(plans.size()).isZero();
    }

    private String snapshot(InterfaceSummary summary) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceCode", summary.interfaceCode());
        snapshot.put("version", 2);
        snapshot.put("requestMappings", List.of());
        snapshot.put("responseMappings", List.of());
        snapshot.put("summary", summary);
        snapshot.put("vendorCodePath", "result.code");
        snapshot.put("responseCodes", List.of(new ResponseCodeMapping(1L, "0000", "EXT-SUCCESS", "HIT", false, false)));
        snapshot.put("securityPolicy", new SecurityPolicy(ID, false, "NONE", "NONE", "", "", "", "signature", ""));
        snapshot.put("checksum", "v2-checksum");
        return mapper.writeValueAsString(snapshot);
    }

    private InterfaceSummary summary(int currentVersion, Integer publishedVersion, String status, String servicePath) {
        return new InterfaceSummary(ID, 1L, "COMPANY_QUERY", "企业查询", servicePath, "POST",
                "http://supplier.example/company", "/company", "OVERRIDE", "DIRECT", null, 3000,
                "READY", status, currentVersion, publishedVersion, LocalDateTime.of(2026, 10, 9, 10, 0),
                7L, "COMPANY_RESOURCE", "企业资源接口");
    }
}
