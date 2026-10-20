package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.SaveMappingsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ResponseSamplePersistenceTest {
    @Test
    void savesEditedResponseSampleOnlyOnTheStandardServiceDraft() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        when(jdbc.queryForMap(contains("FOR UPDATE"), eq(42L)))
                .thenReturn(Map.of("status", "DRAFT"));
        ObjectMapper mapper = new ObjectMapper();
        InterfaceService service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        Map<String, Object> response = Map.of("data", Map.of("items", List.of(Map.of("id", 1))));

        service.saveMappings(42L, new SaveMappingsRequest(List.of(), "/api/query", "POST",
                Map.of("query", "A"), response));

        verify(jdbc).update(contains("upstream_response_example=?"), eq("/api/query"), eq("POST"),
                eq("{\"query\":\"A\"}"), eq("{\"data\":{\"items\":[{\"id\":1}]}}"), eq(42L));
        assertThat(response).containsKey("data");
    }
}
