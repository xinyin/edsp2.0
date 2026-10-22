package com.jsnx.edsp.web;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformOperationAuditTest {
    @Test
    void recordsManagementMutationWithoutRequestBodyOrSecrets() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var request = new MockHttpServletRequest("PUT", "/api/platform-response-contracts/3");
        request.addHeader("X-Trace-Id", "TRACE-3");
        request.addHeader("X-Api-Key", "secret");
        var session = new MockHttpSession();
        session.setAttribute("edsp.operator.username", "admin");
        request.setSession(session);
        var response = new MockHttpServletResponse();
        new PlatformOperationAudit(jdbc).afterCompletion(request, response, new Object(), null);
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("PUT"),
                eq("/api/platform-response-contracts/3"), eq("platform-response-contracts"),
                eq("SUCCESS"), eq(200), eq("TRACE-3"), anyString());
        assertFalse(PlatformOperationAudit.shouldAudit("POST", "/api/auth/login"));
        assertFalse(PlatformOperationAudit.shouldAudit("POST", "/api/interfaces/3/test"));
        assertFalse(PlatformOperationAudit.shouldAudit("POST", "/api/resource-apis/3/verify"));
        assertTrue(PlatformOperationAudit.shouldAudit("DELETE", "/api/providers/2"));
    }

    @Test
    void auditHistoryRequiresAdministratorSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new PlatformOperationAudit.QueryController(jdbc);
        var request = new MockHttpServletRequest();
        LocalDate today = LocalDate.now();
        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 50));

        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        session.setAttribute("edsp.operator.role", "操作员");
        request.setSession(session);
        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 50));
        verifyNoInteractions(jdbc);

        session.setAttribute("edsp.operator.role", "系统管理员");
        controller.list(request, today, today, 1, 50);
        verify(jdbc).queryForList(contains("FROM platform_operation_audit"), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(50), eq(0));
    }

    @Test
    void recordsFailedManagementMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var request = new MockHttpServletRequest("DELETE", "/api/providers/7");
        var response = new MockHttpServletResponse();
        response.setStatus(409);
        new PlatformOperationAudit(jdbc).afterCompletion(request, response, new Object(), null);
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("UNKNOWN"), eq("DELETE"),
                eq("/api/providers/7"), eq("providers"), eq("FAILURE"), eq(409), isNull(), anyString());
    }
}
