package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.SaveResponseCodesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class InterfaceOfflineEditingTest {
    private static final long ID = 42L;
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE", ID))
                .thenReturn(Map.of("status", "OFFLINE", "published_version", 2));
    }

    @Test
    void savesNextDraftWithoutChangingOfflineRuntimeState() {
        service.saveResponseCodes(ID, new SaveResponseCodesRequest("result.code", List.of()));

        verify(jdbc).update(contains("status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END"),
                eq("result.code"), eq(ID));
        verify(jdbc).update(
                "DELETE FROM interface_validation_record WHERE interface_id=? AND validation_type IN ('ORCHESTRATION','PRE_RELEASE')",
                ID);
    }
}
