package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPageQueryTest {
    private InterfaceService service(JdbcTemplate jdbc) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
    }

    @Test
    void servicePageFiltersWithJoinedSourceAndProviderAndCapsPageSize() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(120L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        var page = service(jdbc).interfacePage(9L, " risk ", 2, 500);

        assertThat(page.total()).isEqualTo(120);
        assertThat(page.size()).isEqualTo(100);
        verify(jdbc).queryForObject(argThat(sql -> sql.contains("JOIN data_source ds")
                && sql.contains("ds.source_name LIKE ?") && sql.contains("p.provider_code LIKE ?")),
                eq(Long.class), any(Object[].class));
        verify(jdbc).query(argThat(sql -> sql.contains("ORDER BY ai.id DESC LIMIT ? OFFSET ?")),
                any(RowMapper.class), any(Object[].class));
    }

    @Test
    void resourcePageExcludesDraftsAndUsesServerSideLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        var page = service(jdbc).resourceApiPage(9L, "query", 1, 30);

        assertThat(page.total()).isEqualTo(1);
        verify(jdbc).queryForObject(argThat(sql -> sql.contains("ra.status='READY'")
                && sql.contains("JOIN data_source ds") && sql.contains("ra.data_source_id=?")),
                eq(Long.class), any(Object[].class));
        verify(jdbc).query(argThat(sql -> sql.contains("ORDER BY ra.id DESC LIMIT ? OFFSET ?")),
                any(RowMapper.class), any(Object[].class));
    }
}
