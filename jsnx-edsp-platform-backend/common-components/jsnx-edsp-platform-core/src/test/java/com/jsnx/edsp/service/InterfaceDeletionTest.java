package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterfaceDeletionTest {
    private static final long ID = 42L;
    private static final String LOCK = "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE";
    private JdbcTemplate jdbc;
    private InterfaceService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper();
        service = new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment());
    }

    private void givenInterface(String status, Integer publishedVersion) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("published_version", publishedVersion);
        when(jdbc.queryForMap(LOCK, ID)).thenReturn(row);
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_approval WHERE interface_id=? AND status='PENDING'",
                Integer.class, ID)).thenReturn(0);
    }

    @Test
    void deletesOnlyTheDraftRecordAndPreservesRelatedData() {
        givenInterface("DRAFT", null);
        service.deleteInterface(ID);
        verify(jdbc).update("UPDATE api_interface SET status='DELETED' WHERE id=?", ID);
        verify(jdbc, never()).update(startsWith("DELETE"), anyLong());
    }

    @Test
    void rejectsDraftWithAnAlreadyPublishedVersion() {
        givenInterface("DRAFT", 2);
        assertThatThrownBy(() -> service.deleteInterface(ID)).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void rejectsApiStillInApproval() {
        givenInterface("PENDING_APPROVAL", null);
        assertThatThrownBy(() -> service.deleteInterface(ID)).isInstanceOf(IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void rejectsDraftEditedWhileAnEarlierApprovalIsStillPending() {
        givenInterface("DRAFT", null);
        when(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_approval WHERE interface_id=? AND status='PENDING'",
                Integer.class, ID)).thenReturn(1);
        assertThatThrownBy(() -> service.deleteInterface(ID)).hasMessageContaining("待处理");
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void repeatedDeleteIsIdempotent() {
        givenInterface("DELETED", null);
        service.deleteInterface(ID);
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void staleEditorsCannotRestoreDeletedDrafts() {
        givenInterface("DELETED", null);
        assertThatThrownBy(() -> service.saveMappings(ID, null)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.saveConnectionConfig(ID, null)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.saveResponseCodes(ID, null)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.saveSecurityPolicy(ID, null)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.saveVendorMock(ID, null)).hasMessageContaining("已删除");
        assertThatThrownBy(() -> service.submitApproval(ID, null)).hasMessageContaining("已删除");
        verify(jdbc, never()).update(anyString(), anyLong());
    }
}
