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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/reports")
public class UsageReportController {
    private static final Map<String, String> DIMENSIONS = Map.of(
            "DAY", "DATE(u.called_at)",
            "PROVIDER", "CONCAT(p.provider_name,' (',p.provider_code,')')",
            "DATA_SOURCE", "CONCAT(ds.source_name,' (',ds.source_code,')')",
            "API", "CONCAT(i.interface_name,' (',i.interface_code,')')",
            "CLIENT", "COALESCE(CONCAT(c.client_name,' (',c.client_code,')'),'未识别调用系统')",
            "RESULT", "u.result_status");
    private static final Map<String, String> METRICS = Map.of(
            "callCount", "COUNT(*)",
            "successCount", "SUM(CASE WHEN u.request_status='SUCCESS' AND u.result_status<>'ERROR' THEN 1 ELSE 0 END)",
            "failureCount", "SUM(CASE WHEN u.request_status<>'SUCCESS' OR u.result_status='ERROR' THEN 1 ELSE 0 END)",
            "billableCount", "SUM(CASE WHEN u.billable=1 THEN 1 ELSE 0 END)",
            "amount", "COALESCE(SUM(u.amount),0)",
            "averageDurationMs", "ROUND(AVG(u.duration_ms),2)",
            "upstreamDurationMs", "ROUND(AVG(u.upstream_duration_ms),2)");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("DAY", "日期"), Map.entry("PROVIDER", "资源提供商"),
            Map.entry("DATA_SOURCE", "数据源"), Map.entry("API", "服务 API"),
            Map.entry("CLIENT", "调用系统"), Map.entry("RESULT", "结果状态"),
            Map.entry("callCount", "调用量"), Map.entry("successCount", "成功量"),
            Map.entry("failureCount", "失败量"), Map.entry("billableCount", "计费量"),
            Map.entry("amount", "金额"), Map.entry("averageDurationMs", "平均耗时(ms)"),
            Map.entry("upstreamDurationMs", "上游平均耗时(ms)"));
    private static final String DEFAULT_METRICS = "callCount,successCount,failureCount,billableCount,amount,averageDurationMs";
    private final JdbcTemplate jdbc;

    public UsageReportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> report(HttpServletRequest request,
            @RequestParam(defaultValue = "DAY") String dimensions,
            @RequestParam(defaultValue = DEFAULT_METRICS) String metrics,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        requireSession(request);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表时间范围不能超过 366 天");
        List<String> dims = selected(dimensions, DIMENSIONS.keySet(), 4, "维度");
        List<String> measures = selected(metrics, METRICS.keySet(), METRICS.size(), "度量");
        List<String> expressions = dims.stream().map(DIMENSIONS::get).toList();
        List<String> columns = new ArrayList<>();
        for (int n = 0; n < expressions.size(); n++) {
            columns.add(expressions.get(n) + " d" + n);
            columns.add("GROUPING(" + expressions.get(n) + ") g" + n);
        }
        for (String metric : measures) columns.add(METRICS.get(metric) + " " + metric);
        String sql = "SELECT " + String.join(",", columns) + " FROM usage_record u "
                + "JOIN api_interface i ON i.id=u.interface_id "
                + "JOIN data_source ds ON ds.id=i.data_source_id "
                + "JOIN data_provider p ON p.id=ds.provider_id "
                + "LEFT JOIN api_client c ON c.id=u.client_id "
                + "WHERE u.called_at>=? AND u.called_at<? GROUP BY "
                + String.join(",", expressions) + " WITH ROLLUP LIMIT 10001";
        List<Map<String, Object>> raw = jdbc.queryForList(sql, from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        if (raw.size() > 10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报表结果超过 10000 行，请缩短时间范围或减少维度");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : raw) {
            int level = 0;
            List<String> path = new ArrayList<>();
            for (int n = 0; n < dims.size(); n++) {
                if (((Number) source.get("g" + n)).intValue() == 0) {
                    level++;
                    path.add(String.valueOf(source.get("d" + n)));
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("path", path);
            row.put("label", level == 0 ? "总计" : path.get(level - 1));
            for (String metric : measures) row.put(metric, source.get(metric));
            rows.add(row);
        }
        return Map.of("dimensions", dims, "metrics", measures, "rows", rows);
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(HttpServletRequest request,
            @RequestParam(defaultValue = "DAY") String dimensions,
            @RequestParam(defaultValue = DEFAULT_METRICS) String metrics,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        Map<String, Object> result = report(request, dimensions, metrics, from, to);
        @SuppressWarnings("unchecked") List<String> dims = (List<String>) result.get("dimensions");
        @SuppressWarnings("unchecked") List<String> measures = (List<String>) result.get("metrics");
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        StringBuilder csv = new StringBuilder("\uFEFF");
        for (String dimension : dims) csv.append(cell(LABELS.get(dimension))).append(',');
        csv.append(cell("汇总层级"));
        for (String metric : measures) csv.append(',').append(cell(LABELS.get(metric)));
        csv.append("\r\n");
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked") List<String> path = (List<String>) row.get("path");
            for (int n = 0; n < dims.size(); n++) csv.append(cell(n < path.size() ? path.get(n) : "")).append(',');
            csv.append(cell(row.get("level").equals(0) ? "总计" : path.size() == dims.size() ? "明细" : "小计"));
            for (String metric : measures) csv.append(',').append(cell(row.get(metric)));
            csv.append("\r\n");
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("edsp-report-" + from + "-" + to + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<String> selected(String value, Set<String> allowed, int max, String label) {
        List<String> selected = List.of(value.split(",", -1)).stream().map(String::trim).toList();
        if (selected.isEmpty() || selected.size() > max || selected.stream().anyMatch(item -> !allowed.contains(item))
                || selected.stream().distinct().count() != selected.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "选择不合法");
        return selected;
    }

    private String cell(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void requireSession(HttpServletRequest request) {
        if (request.getSession(false) == null ||
                request.getSession(false).getAttribute(ChainTestToolController.SESSION_TOKEN) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
