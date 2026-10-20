package com.jsnx.edsp.web;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformResponseCodeControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformResponseCodeController controller = new PlatformResponseCodeController(jdbc);

    @Test
    void requiresOperatorTokenAndRejectsInvalidCode() {
        var input = new PlatformResponseCodeController.Code("OK", "成功", "HIT", "", true);
        assertThrows(ResponseStatusException.class,
                () -> controller.create(input, new MockHttpServletRequest(), "token"));
        var request = authenticated();
        assertThrows(ResponseStatusException.class,
                () -> controller.create(new PlatformResponseCodeController.Code("BAD CODE", "错误", "ERROR", "", true),
                        request, "token"));
        verify(jdbc, never()).update(startsWith("INSERT INTO platform_response_code"), any(), any(), any(), any(), any());
    }

    @Test
    void referencedCodeCannotBeDeletedOrRenamed() {
        when(jdbc.queryForObject(contains("response_code_mapping"), eq(Integer.class), eq("USED"))).thenReturn(1);
        assertThrows(ResponseStatusException.class, () -> controller.delete("USED", authenticated(), "token"));
        assertThrows(ResponseStatusException.class, () -> controller.update("USED",
                new PlatformResponseCodeController.Code("NEW", "新编码", "HIT", "", true), authenticated(), "token"));
        verify(jdbc, never()).update(startsWith("DELETE FROM platform_response_code"), (Object) any());
    }

    @Test
    void createsAndDeletesUnreferencedCode() {
        var input = new PlatformResponseCodeController.Code("NEW_CODE", "新编码", "MISS", "说明", true);
        assertEquals(input, controller.create(input, authenticated(), "token"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("NEW_CODE"))).thenReturn(0);
        when(jdbc.update(startsWith("DELETE FROM platform_response_code"), eq("NEW_CODE"))).thenReturn(1);
        assertTrue(controller.delete("NEW_CODE", authenticated(), "token").get("deleted"));
    }

    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        return request;
    }
}
