package com.jsnx.edsp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageReportControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UsageReportController controller = new UsageReportController(jdbc);

    @Test
    void groupsUsageBySelectedDimension() {
        when(jdbc.queryForList(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(Map.of("dimensionValue", "资源方", "callCount", 2)));
        var rows = controller.report(authenticated(), "PROVIDER", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-19"));
        assertEquals(2, rows.get(0).get("callCount"));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("GROUP BY CONCAT(p.provider_name")), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void rejectsUnknownDimensionsAndOversizedRanges() {
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "UNKNOWN", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-19")));
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "DAY", LocalDate.parse("2024-01-01"), LocalDate.parse("2026-10-19")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void exportEscapesSpreadsheetFormulas() {
        when(jdbc.queryForList(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(Map.of(
                "dimensionValue", "=SUM(1,1)", "callCount", 1, "successCount", 1,
                "failureCount", 0, "billableCount", 0, "amount", 0, "averageDurationMs", 12)));
        var response = controller.export(authenticated(), "API", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-19"));
        assertTrue(new String(response.getBody(), StandardCharsets.UTF_8).contains("\"'=SUM(1,1)\""));
    }

    private HttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "operator-token");
        request.setSession(session);
        return request;
    }
}
