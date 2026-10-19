package com.jsnx.edsp.web;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceDirectoryControllerTest {
    @Test
    void returnsBoundedPageAndTreatsZeroFiltersAsAll() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new ResourceDirectoryController(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(125L);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        var page = controller.list(authenticated(), "risk", 0L, 0L, "READY", "HTTP_JSON", 2, 50);
        assertEquals(125L, page.get("total"));
        assertEquals(2, page.get("page"));
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("LIMIT ? OFFSET ?"), (Object[]) parameters.capture());
        Object[] args = parameters.getValue();
        assertEquals(15, args.length);
        assertNull(args[5]);
        assertNull(args[7]);
        assertEquals(50, args[13]);
        assertEquals(50L, args[14]);
    }

    @Test
    void rejectsInvalidPageAndMissingSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var controller = new ResourceDirectoryController(jdbc);
        assertThrows(ResponseStatusException.class, () -> controller.list(authenticated(), "", null, null, "", "", 1, 101));
        assertThrows(ResponseStatusException.class, () -> controller.list(new MockHttpServletRequest(), "", null, null, "", "", 1, 20));
        verifyNoInteractions(jdbc);
    }

    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        request.setSession(session);
        return request;
    }
}
