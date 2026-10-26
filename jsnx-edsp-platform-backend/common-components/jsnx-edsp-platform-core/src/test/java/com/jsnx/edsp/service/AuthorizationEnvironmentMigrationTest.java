package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationEnvironmentMigrationTest {
    @Test
    void refusesToMergeConflictingGrantsAutomatically() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        InterfaceService service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Integer.class),
                eq("api_authorization"), eq("environment"))).thenReturn(1);
        when(jdbc.queryForObject(contains("duplicate_grants"), eq(Long.class))).thenReturn(1L);

        assertThatThrownBy(service::migrateAuthorizationEnvironment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("人工合并");
        verify(jdbc, never()).execute(contains("ALTER TABLE api_authorization"));
    }
}
