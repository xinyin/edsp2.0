package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReleaseServiceQueryTest {
    @Test
    void filtersByActiveRouteAndAuthorization() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InterfaceService service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        service.releaseServices("risk", 4L, 9L, "RUNNING", "AUTHORIZED", 2, 50);

        verify(jdbc).queryForObject(argThat(sql -> sql.contains("EXISTS (SELECT 1 FROM published_route")
                        && sql.contains("ea.status='ACTIVE'") && !sql.contains("ea.environment")), eq(Long.class), any(Object[].class));
        verify(jdbc).query(argThat(sql -> sql.contains("release_run_status")
                        && sql.contains("COUNT(DISTINCT ea.client_id)")
                        && sql.contains("LIMIT ?,?")), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void rejectsUnsupportedReleaseStatusBeforeQueryingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InterfaceService service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());

        assertThatThrownBy(() -> service.releaseServices(null, null, null,
                "PUBLISHED", "ALL", 1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("运行状态");
        verifyNoInteractions(jdbc);
    }
}
