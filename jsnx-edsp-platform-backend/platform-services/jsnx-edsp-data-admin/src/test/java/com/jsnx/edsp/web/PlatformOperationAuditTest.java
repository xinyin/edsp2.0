package com.jsnx.edsp.web;

import com.jsnx.edsp.model.ApiModels.LoginRequest;
import com.jsnx.edsp.model.ApiModels.LoginResult;
import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.service.PublishedPlanRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        assertTrue(PlatformOperationAudit.shouldAudit("POST", "/api/auth/login"));
        assertTrue(PlatformOperationAudit.shouldAudit("POST", "/api/auth/logout"));
        assertFalse(PlatformOperationAudit.shouldAudit("GET", "/api/auth/login"));
        assertFalse(PlatformOperationAudit.shouldAudit("POST", "/api/auth/other"));
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
        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 50, null, null, null, null));

        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        session.setAttribute("edsp.operator.role", "操作员");
        request.setSession(session);
        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 50, null, null, null, null));
        verifyNoInteractions(jdbc);

        session.setAttribute("edsp.operator.role", "系统管理员");
        controller.list(request, today, today, 1, 50, null, null, null, null);
        verify(jdbc).queryForObject(contains("COUNT(*) FROM platform_operation_audit"), eq(Long.class),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verify(jdbc).queryForList(contains("FROM platform_operation_audit"), any(LocalDateTime.class),
                any(LocalDateTime.class), eq(50), eq(0));
    }

    @Test
    void filtersAuditHistoryInDatabaseAndRejectsInvalidValues() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new PlatformOperationAudit.QueryController(jdbc);
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        session.setAttribute("edsp.operator.role", "系统管理员");
        request.setSession(session);
        LocalDate today = LocalDate.now();

        var result = controller.list(request, today.minusDays(1), today, 2, 20,
                "POST", " admin ", "providers", "FAILURE");
        assertEquals(2, result.page());
        assertEquals(20, result.size());
        verify(jdbc).queryForObject(contains("action=? AND operator_name LIKE ? ESCAPE '!' AND target_type=? AND outcome=?"),
                eq(Long.class), any(LocalDateTime.class), any(LocalDateTime.class), eq("POST"),
                eq("admin%"), eq("providers"), eq("FAILURE"));
        verify(jdbc).queryForList(contains("action=? AND operator_name LIKE ? ESCAPE '!' AND target_type=? AND outcome=?"),
                any(LocalDateTime.class), any(LocalDateTime.class), eq("POST"), eq("admin%"),
                eq("providers"), eq("FAILURE"), eq(20), eq(20));

        controller.list(request, today, today, 1, 20, null, "a_%!", null, null);
        verify(jdbc).queryForObject(contains("operator_name LIKE ? ESCAPE '!'"), eq(Long.class),
                any(LocalDateTime.class), any(LocalDateTime.class), eq("a!_!%!!%"));

        controller.list(request, today, today, 1, 20, "LOGIN", null, "auth", "FAILURE");
        verify(jdbc).queryForObject(contains("action=? AND target_type=? AND outcome=?"), eq(Long.class),
                any(LocalDateTime.class), any(LocalDateTime.class), eq("LOGIN"), eq("auth"), eq("FAILURE"));

        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 20,
                "GET", null, null, null));
        assertThrows(ResponseStatusException.class, () -> controller.list(request, today, today, 1, 20,
                null, null, "providers' OR 1=1", null));
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

    @Test
    void recordsAuthenticationOutcomesWithoutCredentialsOrSessionDependence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var audit = new PlatformOperationAudit(jdbc);
        var login = new MockHttpServletRequest("POST", "/api/auth/login");
        login.setAttribute(PlatformOperationAudit.AUTH_OPERATOR_ATTRIBUTE, "admin");
        login.addHeader("X-Api-Key", "never-persist-this");
        audit.afterCompletion(login, new MockHttpServletResponse(), new Object(), null);
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("LOGIN"),
                eq("/api/auth/login"), eq("auth"), eq("SUCCESS"), eq(200), isNull(), anyString());

        var failed = new MockHttpServletRequest("POST", "/api/auth/login");
        failed.setAttribute(PlatformOperationAudit.AUTH_OPERATOR_ATTRIBUTE, "invalid-user");
        var badResponse = new MockHttpServletResponse();
        badResponse.setStatus(400);
        audit.afterCompletion(failed, badResponse, new Object(), null);
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("invalid-user"), eq("LOGIN"),
                eq("/api/auth/login"), eq("auth"), eq("FAILURE"), eq(400), isNull(), anyString());

        var logout = new MockHttpServletRequest("POST", "/api/auth/logout");
        logout.setAttribute(PlatformOperationAudit.AUTH_OPERATOR_ATTRIBUTE, "admin");
        var noContent = new MockHttpServletResponse();
        noContent.setStatus(204);
        audit.afterCompletion(logout, noContent, new Object(), null);
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("LOGOUT"),
                eq("/api/auth/logout"), eq("auth"), eq("SUCCESS"), eq(204), isNull(), anyString());
    }

    @Test
    void loginAndLogoutHttpRequestsReachAuditInterceptor() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InterfaceService service = mock(InterfaceService.class);
        when(service.login(new LoginRequest("admin", "correct")))
                .thenReturn(new LoginResult("ignored", "管理员", "系统管理员", "省联社"));
        when(service.login(new LoginRequest("admin", "wrong")))
                .thenThrow(new IllegalArgumentException("用户名或密码错误"));
        var mvc = MockMvcBuilders.standaloneSetup(new ApiController(service, mock(PublishedPlanRegistry.class)))
                .addInterceptors(new PlatformOperationAudit(jdbc)).build();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("LOGIN"),
                eq("/api/auth/login"), eq("auth"), eq("FAILURE"), eq(400), isNull(), anyString());

        var loggedIn = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct\"}"))
                .andExpect(status().isOk()).andReturn();
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("LOGIN"),
                eq("/api/auth/login"), eq("auth"), eq("SUCCESS"), eq(200), isNull(), anyString());

        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        verify(jdbc).update(contains("INSERT INTO platform_operation_audit"), eq("admin"), eq("LOGOUT"),
                eq("/api/auth/logout"), eq("auth"), eq("SUCCESS"), eq(204), isNull(), anyString());
    }
}
