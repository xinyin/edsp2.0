package com.jsnx.edsp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class UsageReportController {
    private final JdbcTemplate jdbc;

    public UsageReportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<Map<String, Object>> report(HttpServletRequest request,
            @RequestParam(defaultValue = "DAY") String dimension,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        requireSession(request);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表时间范围不能超过 366 天");
        }
        String group = switch (dimension) {
            case "DAY" -> "DATE(u.called_at)";
            case "PROVIDER" -> "CONCAT(p.provider_name,' (',p.provider_code,')')";
            case "DATA_SOURCE" -> "CONCAT(ds.source_name,' (',ds.source_code,')')";
            case "API" -> "CONCAT(i.interface_name,' (',i.interface_code,')')";
            case "CLIENT" -> "COALESCE(CONCAT(c.client_name,' (',c.client_code,')'),'未识别调用系统')";
            case "RESULT" -> "u.result_status";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的报表维度");
        };
        String sql = """
                SELECT %s dimensionValue, COUNT(*) callCount,
                       SUM(CASE WHEN u.request_status='SUCCESS' AND u.result_status<>'ERROR' THEN 1 ELSE 0 END) successCount,
                       SUM(CASE WHEN u.request_status<>'SUCCESS' OR u.result_status='ERROR' THEN 1 ELSE 0 END) failureCount,
                       SUM(CASE WHEN u.billable=1 THEN 1 ELSE 0 END) billableCount,
                       COALESCE(SUM(u.amount),0) amount,
                       ROUND(AVG(u.duration_ms),2) averageDurationMs
                FROM usage_record u JOIN api_interface i ON i.id=u.interface_id
                JOIN data_source ds ON ds.id=i.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                LEFT JOIN api_client c ON c.id=u.client_id
                WHERE u.called_at>=? AND u.called_at<?
                GROUP BY %s ORDER BY callCount DESC, dimensionValue LIMIT 10000
                """.formatted(group, group);
        return jdbc.queryForList(sql, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(HttpServletRequest request,
            @RequestParam(defaultValue = "DAY") String dimension,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        List<Map<String, Object>> rows = report(request, dimension, from, to);
        StringBuilder csv = new StringBuilder("\uFEFF统计维度,调用量,成功量,失败量,计费量,金额,平均耗时(ms)\r\n");
        for (Map<String, Object> row : rows) {
            csv.append(cell(row.get("dimensionValue"))).append(',')
                    .append(cell(row.get("callCount"))).append(',')
                    .append(cell(row.get("successCount"))).append(',')
                    .append(cell(row.get("failureCount"))).append(',')
                    .append(cell(row.get("billableCount"))).append(',')
                    .append(cell(row.get("amount"))).append(',')
                    .append(cell(row.get("averageDurationMs"))).append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("edsp-report-" + from + "-" + to + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String cell(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        // Prevent spreadsheet formula execution when externally managed names are exported.
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void requireSession(HttpServletRequest request) {
        if (request.getSession(false) == null ||
                request.getSession(false).getAttribute(ChainTestToolController.SESSION_TOKEN) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }
}
