package com.jsnx.edsp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resource-directory")
public class ResourceDirectoryController {
    private final JdbcTemplate jdbc;

    public ResourceDirectoryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) Long dataSourceId,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String protocolType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (request.getSession(false) == null ||
                request.getSession(false).getAttribute(ChainTestToolController.SESSION_TOKEN) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (page < 1 || size < 1 || size > 100 || keyword.length() > 128 ||
                !status.matches("|DRAFT|READY") || !protocolType.matches("|[A-Z_]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询条件不合法");
        }
        String where = """
                FROM resource_api ra JOIN data_source ds ON ds.id=ra.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                WHERE (?='' OR ra.resource_api_name LIKE ? OR ra.resource_api_code LIKE ?
                    OR ds.source_name LIKE ? OR p.provider_name LIKE ?)
                  AND (? IS NULL OR p.id=?) AND (? IS NULL OR ds.id=?)
                  AND (?='' OR ra.status=?) AND (?='' OR ds.protocol_type=?)
                """;
        String like = "%" + keyword.trim() + "%";
        Long selectedProvider = providerId == null || providerId == 0 ? null : providerId;
        Long selectedSource = dataSourceId == null || dataSourceId == 0 ? null : dataSourceId;
        Object[] filters = {keyword.trim(), like, like, like, like, selectedProvider, selectedProvider,
                selectedSource, selectedSource, status, status, protocolType, protocolType};
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + where, Long.class, filters);
        Object[] pageArgs = java.util.Arrays.copyOf(filters, filters.length + 2);
        pageArgs[filters.length] = size;
        pageArgs[filters.length + 1] = (long) (page - 1) * size;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ra.id, ra.resource_api_code resourceApiCode, ra.resource_api_name resourceApiName,
                       ra.http_method httpMethod, ra.status, ra.updated_at updatedAt,
                       p.id providerId, p.provider_name providerName,
                       ds.id dataSourceId, ds.source_name dataSourceName,
                       ds.source_code dataSourceCode, ds.protocol_type protocolType
                """ + where + " ORDER BY ra.updated_at DESC, ra.id DESC LIMIT ? OFFSET ?", pageArgs);
        return Map.of("rows", rows, "total", total == null ? 0 : total, "page", page, "size", size);
    }
}
