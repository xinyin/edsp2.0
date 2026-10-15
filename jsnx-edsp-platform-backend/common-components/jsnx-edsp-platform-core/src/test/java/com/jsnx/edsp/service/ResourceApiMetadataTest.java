package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.ResourceApiSummary;
import com.jsnx.edsp.model.ApiModels.UpdateResourceApiMetadataRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ResourceApiMetadataTest {
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
    }

    @Test
    void updatesStableMetadataAndInvalidatesPreviousVerification() {
        LocalDateTime now = LocalDateTime.now();
        ResourceApiSummary expected = new ResourceApiSummary(7L, 3L, "4093_QUERY_V2", "工商查询新版",
                "GET", "https://provider.example.com/query", "/query", "INHERIT", 3000,
                "READY", 4, null, now, 1);
        when(jdbc.update(anyString(), eq("4093_QUERY_V2"), eq("工商查询新版"), eq("GET"), eq(7L)))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ResourceApiSummary>>any(), eq(7L)))
                .thenReturn(expected);

        ResourceApiSummary actual = service.updateResourceApiMetadata(7L,
                new UpdateResourceApiMetadataRequest(" 4093_query_v2 ", " 工商查询新版 ", "get"));

        assertThat(actual).isEqualTo(expected);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("verified_at=NULL"),
                eq("4093_QUERY_V2"), eq("工商查询新版"), eq("GET"), eq(7L));
    }
}
