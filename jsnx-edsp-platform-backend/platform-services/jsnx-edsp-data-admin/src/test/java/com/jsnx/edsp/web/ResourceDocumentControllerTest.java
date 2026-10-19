package com.jsnx.edsp.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceDocumentControllerTest {
    @TempDir Path storage;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void rejectsDocumentsWithoutValidOperatorToken() {
        var controller = new ResourceDocumentController(jdbc, storage.toString());
        assertThrows(ResponseStatusException.class, () -> controller.upload(authenticated(), "wrong", 1, null, null, "",
                new MockMultipartFile("file", "api.pdf", "application/pdf", "test".getBytes())));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsResourceApiFromAnotherProvider() {
        var controller = new ResourceDocumentController(jdbc, storage.toString());
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM data_provider WHERE id=?"), eq(Integer.class), eq(1L))).thenReturn(1);
        when(jdbc.queryForObject(contains("FROM resource_api ra"), eq(Boolean.class), any(), any(), any(), any())).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> controller.upload(authenticated(), "operator-token", 1, null, 5L, "",
                new MockMultipartFile("file", "api.pdf", "application/pdf", "test".getBytes())));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "operator-token");
        request.setSession(session);
        return request;
    }
}
