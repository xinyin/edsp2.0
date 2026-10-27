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
        var input = new PlatformResponseCodeController.Code("OK", "成功", "HIT", "SUCCESS", "", true);
        assertThrows(ResponseStatusException.class,
                () -> controller.create(input, new MockHttpServletRequest(), "token"));
        var request = authenticated();
        assertThrows(ResponseStatusException.class,
                () -> controller.create(new PlatformResponseCodeController.Code("BAD CODE", "错误", "ERROR", "OTHER", "", true),
                        request, "token"));
        verify(jdbc, never()).update(startsWith("INSERT INTO platform_response_code"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void referencedCodeCannotBeDeletedOrRenamed() {
        when(jdbc.queryForObject(contains("response_code_mapping"), eq(Integer.class), eq("USED"))).thenReturn(1);
        assertThrows(ResponseStatusException.class, () -> controller.delete("USED", authenticated(), "token"));
        assertThrows(ResponseStatusException.class, () -> controller.update("USED",
                new PlatformResponseCodeController.Code("NEW", "新编码", "HIT", "SUCCESS", "", true), authenticated(), "token"));
        verify(jdbc, never()).update(startsWith("DELETE FROM platform_response_code"), (Object) any());
    }

    @Test
    void createsAndDeletesUnreferencedCode() {
        var input = new PlatformResponseCodeController.Code("NEW_CODE", "新编码", "MISS", "NO_DATA", "说明", true);
        assertEquals(input, controller.create(input, authenticated(), "token"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("NEW_CODE"))).thenReturn(0);
        when(jdbc.update(startsWith("DELETE FROM platform_response_code"), eq("NEW_CODE"))).thenReturn(1);
        assertTrue(controller.delete("NEW_CODE", authenticated(), "token").get("deleted"));
    }

    @Test
    void productionCatalogUsesUniqueCodesAndPreservesAmbiguousLegacyValues() {
        var codes = PlatformResponseCodeCatalog.CODES;
        assertEquals(37, codes.size());
        assertEquals(codes.size(), codes.stream().map(PlatformResponseCodeController.Code::code).distinct().count());
        assertEquals("MISS", codes.stream().filter(item -> item.code().equals("-10011")).findFirst().orElseThrow().resultStatus());
        assertEquals(2, codes.stream().filter(item -> item.description().contains("原码 -10023")).count());
        assertEquals(2, codes.stream().filter(item -> item.description().contains("原码 -10024")).count());
        assertTrue(codes.stream().noneMatch(item -> item.code().equals("-10023") || item.code().equals("-10024")));
    }

    @Test
    void negativeCodeAndCategoryAreValidButUnknownCategoryIsRejected() {
        var code = new PlatformResponseCodeController.Code("-10001", "网络异常", "ERROR", "UPSTREAM", "", true);
        assertEquals(code, controller.create(code, authenticated(), "token"));
        assertThrows(ResponseStatusException.class, () -> controller.create(
                new PlatformResponseCodeController.Code("NEW", "错误", "ERROR", "UNKNOWN", "", true),
                authenticated(), "token"));
    }

    @Test
    void firstStartupMigratesLegacyColumnAndSeedsBeforeMappingPlaceholders() {
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM platform_response_code"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(contains("platform_dictionary_seed WHERE"), eq(Integer.class), eq("PRODUCTION_RESPONSE_CODES_V1")))
                .thenReturn(0);

        controller.ensureDictionary();

        var calls = inOrder(jdbc);
        calls.verify(jdbc).execute(contains("ADD COLUMN category"));
        calls.verify(jdbc).batchUpdate(contains("INSERT IGNORE INTO platform_response_code"),
                eq(PlatformResponseCodeCatalog.CODES), eq(37), any());
        calls.verify(jdbc).batchUpdate(contains("UPDATE platform_response_code"),
                eq(PlatformResponseCodeCatalog.CODES), eq(37), any());
        calls.verify(jdbc).update(contains("INSERT IGNORE INTO platform_dictionary_seed"), eq("PRODUCTION_RESPONSE_CODES_V1"));
        calls.verify(jdbc).update(contains("FROM response_code_mapping GROUP BY standard_code"));
    }

    @Test
    void laterStartupDoesNotRestoreDeletedOrOverwriteEditedCatalogEntries() {
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT COUNT(*) FROM platform_response_code"), eq(Integer.class))).thenReturn(50);
        when(jdbc.queryForObject(contains("platform_dictionary_seed WHERE"), eq(Integer.class), eq("PRODUCTION_RESPONSE_CODES_V1")))
                .thenReturn(1);

        controller.ensureDictionary();

        verify(jdbc, never()).execute(contains("ADD COLUMN category"));
        verify(jdbc, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
        verify(jdbc, never()).update(contains("INSERT IGNORE INTO platform_dictionary_seed"), (Object) any());
    }

    private MockHttpServletRequest authenticated() {
        var request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(ChainTestToolController.SESSION_TOKEN, "token");
        return request;
    }
}
