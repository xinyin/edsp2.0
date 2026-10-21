package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProviderDeletionTest {
    private static final long ID = 17L;
    private static final String EXISTS = "SELECT COUNT(*) FROM data_provider WHERE id=?";
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
    void deletesProviderWhenNoResourcesAreAssociated() {
        when(jdbc.queryForObject(EXISTS, Integer.class, ID)).thenReturn(1);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE provider_id=?", Integer.class, ID)).thenReturn(0);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM provider_account WHERE provider_id=?", Integer.class, ID)).thenReturn(0);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM resource_document WHERE provider_id=?", Integer.class, ID)).thenReturn(0);
        when(jdbc.update("DELETE FROM data_provider WHERE id=?", ID)).thenReturn(1);

        service.deleteProvider(ID);

        verify(jdbc).update("DELETE FROM data_provider WHERE id=?", ID);
    }

    @Test
    void refusesDeletionAndListsAllAssociatedResourceCounts() {
        when(jdbc.queryForObject(EXISTS, Integer.class, ID)).thenReturn(1);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE provider_id=?", Integer.class, ID)).thenReturn(2);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM provider_account WHERE provider_id=?", Integer.class, ID)).thenReturn(1);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM resource_document WHERE provider_id=?", Integer.class, ID)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteProvider(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据源（2）")
                .hasMessageContaining("资源提供商账号（1）");
        verify(jdbc, never()).update(eq("DELETE FROM data_provider WHERE id=?"), eq(ID));
    }

    @Test
    void rejectsUnknownProvider() {
        when(jdbc.queryForObject(EXISTS, Integer.class, ID)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteProvider(ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verify(jdbc, never()).update(eq("DELETE FROM data_provider WHERE id=?"), eq(ID));
    }
}
