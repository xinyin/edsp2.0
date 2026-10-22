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
    private final LocalDate from = LocalDate.parse("2026-10-01");
    private final LocalDate to = LocalDate.parse("2026-10-19");

    @Test
    void combinesProviderSourceAndApiWithRollup() {
        when(jdbc.queryForList(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(
                Map.of("d0", "资源方", "g0", 0, "d1", "风险", "g1", 0, "d2", "查询", "g2", 0, "callCount", 2),
                Map.of("d0", "资源方", "g0", 0, "d1", "风险", "g1", 0, "g2", 1, "callCount", 2),
                Map.of("d0", "资源方", "g0", 0, "g1", 1, "g2", 1, "callCount", 2),
                Map.of("g0", 1, "g1", 1, "g2", 1, "callCount", 2)));
        Map<String, Object> result = controller.report(authenticated(), "PROVIDER,DATA_SOURCE,API", "callCount", from, to);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertEquals(List.of("资源方", "风险", "查询"), rows.get(0).get("path"));
        assertEquals(2, rows.get(1).get("level"));
        assertEquals("总计", rows.get(3).get("label"));
        verify(jdbc).queryForList(argThat(sql -> sql.contains("GROUP BY CONCAT(p.provider_name")
                && sql.contains("WITH ROLLUP") && sql.contains("GROUPING(")), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void rejectsUnknownOrDuplicateDimensionsAndOversizedRanges() {
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "UNKNOWN", "callCount", from, to));
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "API,API", "callCount", from, to));
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "DAY", "SUM(u.amount)", from, to));
        assertThrows(ResponseStatusException.class, () -> controller.report(authenticated(), "DAY", "callCount", LocalDate.parse("2024-01-01"), to));
        verifyNoInteractions(jdbc);
    }

    @Test
    void exportEscapesSpreadsheetFormulas() {
        when(jdbc.queryForList(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of(
                Map.of("d0", "=SUM(1,1)", "g0", 0, "callCount", 1)));
        var response = controller.export(authenticated(), "API", "callCount", from, to);
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
