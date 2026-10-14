package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.DataSourceSummary;
import com.jsnx.edsp.model.ApiModels.SaveDataSourceSettingsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class DataSourceSettingsTest {
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE id=?", Integer.class, 7L)).thenReturn(1);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM data_provider WHERE id=?", Integer.class, 3L)).thenReturn(1);
    }

    @Test
    void updatesEditableAccessInformationAndKeepsTheStableSourceCode() {
        DataSourceSummary updated = new DataSourceSummary(
                7L, 3L, "新资源提供商", "STABLE_CODE", "新数据源名称",
                "jsnx-edsp-data-source-stable", null, 8199, "SOAP", "https://provider.example.com/ws", 5000,
                "TEST", "ACTIVE", 1, 2, 0);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<DataSourceSummary>>any()))
                .thenReturn(List.of(updated));

        DataSourceSummary result = service.saveDataSourceSettings(7L,
                new SaveDataSourceSettingsRequest(3L, " 新数据源名称 ", "soap",
                        "https://provider.example.com/ws/", 5000));

        assertThat(result).isEqualTo(updated);
        verify(jdbc).update(contains("SET provider_id=?"), eq(3L), eq("新数据源名称"), eq("SOAP"),
                eq("https://provider.example.com/ws"), eq(5000), eq(7L));
    }

    @Test
    void rejectsAnUnknownProviderBeforeUpdatingTheDataSource() {
        when(jdbc.queryForObject("SELECT COUNT(*) FROM data_provider WHERE id=?", Integer.class, 99L)).thenReturn(0);

        assertThatThrownBy(() -> service.saveDataSourceSettings(7L,
                new SaveDataSourceSettingsRequest(99L, "数据源", "HTTP_JSON", "https://provider.example.com", 3000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资源提供商不存在");
    }
}
