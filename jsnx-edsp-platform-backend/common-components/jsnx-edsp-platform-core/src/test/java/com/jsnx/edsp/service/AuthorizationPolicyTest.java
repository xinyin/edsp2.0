package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.AuthorizationRecord;
import com.jsnx.edsp.model.ApiModels.BatchAuthorizationRequest;
import com.jsnx.edsp.model.ApiModels.CreateAuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AuthorizationPolicyTest {
    private static final long CLIENT_ID = 7L;
    private static final long INTERFACE_ID = 42L;
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE", INTERFACE_ID))
                .thenReturn(api("DRAFT", 3));
        when(jdbc.queryForMap("SELECT status FROM api_client WHERE id=?", CLIENT_ID))
                .thenReturn(Map.of("status", "ACTIVE"));
        when(jdbc.queryForMap("SELECT status,published_version FROM api_interface WHERE id=?", INTERFACE_ID))
                .thenReturn(api("DRAFT", 3));
    }

    @Test
    void allowsAuthorizationWhileAReleasedVersionServesAndANewerDraftIsEdited() {
        AuthorizationRecord record = new AuthorizationRecord(1, CLIENT_ID, "信贷系统", INTERFACE_ID,
                "企业查询", "ENTERPRISE_QUERY", "TEST", 10_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "ACTIVE", "管理员");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(record));

        AuthorizationRecord result = service.createAuthorization(request(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));

        assertThat(result).isEqualTo(record);
        verify(jdbc).update(contains("INSERT INTO api_authorization"), eq(CLIENT_ID), eq(INTERFACE_ID),
                eq("TEST"), eq(10_000), eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2027, 1, 1)), eq("管理员"));
    }

    @Test
    void rejectsAnInterfaceThatHasNeverBeenPublished() {
        when(jdbc.queryForMap("SELECT status,published_version FROM api_interface WHERE id=?", INTERFACE_ID))
                .thenReturn(api("DRAFT", null));

        assertThatThrownBy(() -> service.createAuthorization(request(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))))
                .hasMessageContaining("已发布且在线");
        verify(jdbc, never()).update(contains("INSERT INTO api_authorization"), any(Object[].class));
    }

    @Test
    void rejectsAnInvertedValidityPeriod() {
        assertThatThrownBy(() -> service.createAuthorization(request(
                LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1))))
                .hasMessageContaining("生效日期");
        verify(jdbc, never()).update(contains("INSERT INTO api_authorization"), any(Object[].class));
    }

    @Test
    void authorizesEsbRouteByApiCodeWhenConfiguredPathDiffers() {
        Map<String, Object> authorization = Map.of(
                "client_id", CLIENT_ID,
                "client_code", "CORE_SYSTEM",
                "interface_id", INTERFACE_ID,
                "interface_code", "ENTERPRISE_QUERY",
                "daily_quota", 50_000);
        when(jdbc.queryForList(anyString(), eq("CORE_SYSTEM"), eq("/lhzx/v1/enterprise/query-01"),
                eq("ENTERPRISE_QUERY"), eq("ENTERPRISE_QUERY"), eq("ENTERPRISE_QUERY"), eq("TEST")))
                .thenReturn(List.of(authorization));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM usage_record"), eq(Integer.class),
                eq(CLIENT_ID), eq(INTERFACE_ID))).thenReturn(12);

        Map<String, Object> result = service.authorize(
                "CORE_SYSTEM", "/lhzx/v1/enterprise/query-01", "ENTERPRISE_QUERY");

        assertThat(result).containsEntry("authorized", true)
                .containsEntry("interfaceCode", "ENTERPRISE_QUERY")
                .containsEntry("usedToday", 12);
        verify(jdbc).queryForList(contains("pr.interface_code=?"), eq("CORE_SYSTEM"),
                eq("/lhzx/v1/enterprise/query-01"), eq("ENTERPRISE_QUERY"),
                eq("ENTERPRISE_QUERY"), eq("ENTERPRISE_QUERY"), eq("TEST"));
    }

    @Test
    void createsAuthorizationsForMultiplePublishedApis() {
        long secondInterfaceId = 43L;
        when(jdbc.queryForList(contains("SELECT id FROM api_interface"), eq(Long.class),
                eq(INTERFACE_ID), eq(secondInterfaceId))).thenReturn(List.of(INTERFACE_ID, secondInterfaceId));
        AuthorizationRecord first = new AuthorizationRecord(1, CLIENT_ID, "信贷系统", INTERFACE_ID,
                "企业查询", "ENTERPRISE_QUERY", "TEST", 50_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "ACTIVE", "管理员");
        AuthorizationRecord second = new AuthorizationRecord(2, CLIENT_ID, "信贷系统", secondInterfaceId,
                "企业风险查询", "ENTERPRISE_RISK_QUERY", "TEST", 50_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "ACTIVE", "管理员");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(first, second));

        List<AuthorizationRecord> result = service.createAuthorizations(new BatchAuthorizationRequest(
                CLIENT_ID, List.of(INTERFACE_ID, secondInterfaceId), "TEST", 50_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), "管理员"));

        assertThat(result).extracting(AuthorizationRecord::interfaceId)
                .containsExactlyInAnyOrder(INTERFACE_ID, secondInterfaceId);
        verify(jdbc).batchUpdate(contains("INSERT INTO api_authorization"), any(List.class));
    }

    private CreateAuthorizationRequest request(LocalDate validFrom, LocalDate validTo) {
        return new CreateAuthorizationRequest(CLIENT_ID, INTERFACE_ID, "test", 10_000,
                validFrom, validTo, "管理员");
    }

    private Map<String, Object> api(String status, Integer publishedVersion) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("published_version", publishedVersion);
        return row;
    }
}
