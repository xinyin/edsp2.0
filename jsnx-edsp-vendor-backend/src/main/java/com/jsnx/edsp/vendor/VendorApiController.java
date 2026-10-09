package com.jsnx.edsp.vendor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class VendorApiController {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final Pattern REQUEST_TOKEN = Pattern.compile("\\$\\{request\\.([^}]+)}");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VendorApiController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/admin/data-sources")
    public List<DataSourceSummary> dataSources() {
        return jdbc.query("SELECT * FROM vendor_data_source ORDER BY id", (rs, rowNum) -> toDataSource(rs, true));
    }

    @PostMapping("/admin/data-sources")
    @Transactional
    public DataSourceSummary createDataSource(@Valid @RequestBody SaveDataSourceRequest request) {
        String code = normalizeCode(request.sourceCode());
        jdbc.update("""
                INSERT INTO vendor_data_source(source_code,source_name,description,base_path,status)
                VALUES(?,?,?,?,?)
                """, code, request.sourceName(), request.description(), normalizeBasePath(request.basePath()), request.status());
        return dataSourceByCode(code);
    }

    @PutMapping("/admin/data-sources/{id}")
    @Transactional
    public DataSourceSummary updateDataSource(@PathVariable long id, @Valid @RequestBody SaveDataSourceRequest request) {
        jdbc.update("""
                UPDATE vendor_data_source SET source_code=?,source_name=?,description=?,base_path=?,status=? WHERE id=?
                """, normalizeCode(request.sourceCode()), request.sourceName(), request.description(),
                normalizeBasePath(request.basePath()), request.status(), id);
        return dataSourceById(id);
    }

    @GetMapping("/admin/apis")
    public List<ApiSummary> apis(@RequestParam(required = false) Long dataSourceId) {
        String sql = apiSelect() + (dataSourceId == null ? " ORDER BY a.id" : " WHERE a.data_source_id=? ORDER BY a.id");
        return dataSourceId == null
                ? jdbc.query(sql, (rs, rowNum) -> toApi(rs))
                : jdbc.query(sql, (rs, rowNum) -> toApi(rs), dataSourceId);
    }

    @PostMapping("/admin/apis")
    @Transactional
    public ApiSummary createApi(@Valid @RequestBody SaveApiRequest request) {
        String code = normalizeCode(request.apiCode());
        jdbc.update("""
                INSERT INTO vendor_api_definition(data_source_id,api_code,api_name,service_path,http_method,request_example,
                response_body,response_code,delay_ms,status,version) VALUES(?,?,?,?,?,?,?,?,?,?,1)
                """, request.dataSourceId(), code, request.apiName(), normalizeServicePath(request.dataSourceId(), request.servicePath()),
                request.httpMethod(), write(request.requestExample()), write(request.responseBody()), request.responseCode(),
                Math.max(0, request.delayMs()), request.status());
        return apiByCode(code);
    }

    @PutMapping("/admin/apis/{id}")
    @Transactional
    public ApiSummary updateApi(@PathVariable long id, @Valid @RequestBody SaveApiRequest request) {
        jdbc.update("""
                UPDATE vendor_api_definition SET data_source_id=?,api_code=?,api_name=?,service_path=?,http_method=?,
                request_example=?,response_body=?,response_code=?,delay_ms=?,status=?,version=version+1 WHERE id=?
                """, request.dataSourceId(), normalizeCode(request.apiCode()), request.apiName(),
                normalizeServicePath(request.dataSourceId(), request.servicePath()), request.httpMethod(), write(request.requestExample()),
                write(request.responseBody()), request.responseCode(), Math.max(0, request.delayMs()), request.status(), id);
        return apiById(id);
    }

    @DeleteMapping("/admin/apis/{id}")
    public void deleteApi(@PathVariable long id) {
        jdbc.update("DELETE FROM vendor_api_definition WHERE id=?", id);
    }

    @GetMapping("/admin/enterprise-records")
    public List<EnterpriseRecord> enterpriseRecords() {
        return jdbc.query("SELECT * FROM vendor_enterprise_record ORDER BY id", (rs, rowNum) -> new EnterpriseRecord(
                rs.getLong("id"), rs.getString("enterprise_code"), rs.getString("credit_code"),
                rs.getString("enterprise_name"), rs.getString("legal_person"), rs.getString("sex_code"),
                rs.getString("risk_level"), rs.getString("registration_status")));
    }

    @PostMapping("/admin/enterprise-records")
    @Transactional
    public EnterpriseRecord saveEnterpriseRecord(@Valid @RequestBody SaveEnterpriseRecord request) {
        jdbc.update("""
                INSERT INTO vendor_enterprise_record(enterprise_code,credit_code,enterprise_name,legal_person,sex_code,
                risk_level,registration_status) VALUES(?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE credit_code=VALUES(credit_code),enterprise_name=VALUES(enterprise_name),
                legal_person=VALUES(legal_person),sex_code=VALUES(sex_code),risk_level=VALUES(risk_level),
                registration_status=VALUES(registration_status)
                """, request.enterpriseCode(), request.creditCode(), request.enterpriseName(), request.legalPerson(),
                request.sexCode(), request.riskLevel(), request.registrationStatus());
        return jdbc.queryForObject("SELECT * FROM vendor_enterprise_record WHERE enterprise_code=?", (rs, rowNum) ->
                new EnterpriseRecord(rs.getLong("id"), rs.getString("enterprise_code"), rs.getString("credit_code"),
                        rs.getString("enterprise_name"), rs.getString("legal_person"), rs.getString("sex_code"),
                        rs.getString("risk_level"), rs.getString("registration_status")), request.enterpriseCode());
    }

    @GetMapping("/open/catalog")
    public List<DataSourceSummary> catalog() {
        return jdbc.query("SELECT * FROM vendor_data_source WHERE status='ACTIVE' ORDER BY id",
                (rs, rowNum) -> toDataSource(rs, false));
    }

    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, Object>> invoke(HttpServletRequest servletRequest,
                                                       @RequestBody(required = false) Map<String, Object> request) {
        List<ApiSummary> matches = jdbc.query(apiSelect() + " WHERE a.service_path=? AND d.status='ACTIVE'",
                (rs, rowNum) -> toApi(rs), servletRequest.getRequestURI());
        if (matches.isEmpty() || !"ACTIVE".equals(matches.get(0).status())) {
            return ResponseEntity.status(404).body(Map.of("result", Map.of("code", "1001", "message", "接口不存在或已停用")));
        }
        ApiSummary api = matches.get(0);
        if (!api.httpMethod().equalsIgnoreCase(servletRequest.getMethod())) {
            return ResponseEntity.status(405).body(errorBody("4002", "HTTP 方法不匹配",
                    List.of("接口要求 " + api.httpMethod() + "，实际收到 " + servletRequest.getMethod())));
        }
        Map<String, Object> actualRequest = request == null ? requestFromQuery(servletRequest) : request;
        List<String> validationErrors = new ArrayList<>();
        validateContract(api.requestExample(), actualRequest, "", validationErrors);
        if (!validationErrors.isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("4001", "请求参数校验失败", validationErrors));
        }
        try {
            Thread.sleep(Math.min(api.delayMs(), 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> businessResponse = "ANTIFRAUD_SCORE".equals(api.apiCode())
                ? antifraudQueryResponse(actualRequest) : enterpriseQueryResponse(actualRequest);
        return ResponseEntity.ok()
                .header("X-VENDOR-DATA-SOURCE", api.dataSourceCode())
                .header("X-VENDOR-API", api.apiCode())
                .header("X-VENDOR-API-VERSION", String.valueOf(api.version()))
                .body(businessResponse == null ? renderResponse(api.responseBody(), actualRequest) : businessResponse);
    }

    private Map<String, Object> enterpriseQueryResponse(Map<String, Object> request) {
        String code = firstText(request, "credit_code", "company_code", "enterprise_code");
        String name = firstText(request, "company_name", "enterprise_name");
        if (code == null && name == null) return null;
        String decodedCode = decodeBase64(code);
        List<Map<String, Object>> records = code != null
                ? jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE credit_code=? OR enterprise_code=?", code, code)
                : jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE UPPER(enterprise_name)=UPPER(?)", name);
        if (records.isEmpty() && decodedCode != null && !decodedCode.equals(code)) {
            records = jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE credit_code=? OR enterprise_code=?", decodedCode, decodedCode);
        }
        if (name != null) records = records.stream()
                .filter(row -> name.equalsIgnoreCase(String.valueOf(row.get("enterprise_name")))).toList();
        if (records.isEmpty()) return Map.of(
                "result", Map.of("code", "1001", "message", "企业信息未查得"),
                "data", Map.of());
        Map<String, Object> record = records.get(0);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enterprise_code", record.get("enterprise_code"));
        data.put("company_code", record.get("enterprise_code"));
        data.put("credit_code", record.get("credit_code"));
        data.put("enterprise_name", record.get("enterprise_name"));
        data.put("company_name", record.get("enterprise_name"));
        data.put("qymc", record.get("enterprise_name"));
        data.put("legal_person", record.get("legal_person"));
        data.put("fddbr", record.get("legal_person"));
        data.put("sex_code", record.get("sex_code"));
        data.put("xingbie", record.get("sex_code"));
        data.put("risk_level", record.get("risk_level"));
        data.put("riks_level", record.get("risk_level"));
        data.put("registration_status", record.get("registration_status"));
        return Map.of("result", Map.of("code", "0000", "message", "success"), "data", data);
    }

    private Map<String, Object> antifraudQueryResponse(Map<String, Object> request) {
        String code = firstText(request, "credit_code", "company_code", "enterprise_code");
        String name = firstText(request, "company_name", "enterprise_name");
        String decodedCode = decodeBase64(code);
        List<Map<String, Object>> records = code == null
                ? jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE UPPER(enterprise_name)=UPPER(?)", name)
                : jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE credit_code=? OR enterprise_code=?",
                code, code);
        if (records.isEmpty() && decodedCode != null && !decodedCode.equals(code)) {
            records = jdbc.queryForList("SELECT * FROM vendor_enterprise_record WHERE credit_code=? OR enterprise_code=?",
                    decodedCode, decodedCode);
        }
        if (records.isEmpty()) return Map.of("result", Map.of("code", "1001", "message", "企业不在反欺诈样本库"),
                "data", Map.of());
        Map<String, Object> record = records.get(0);
        String risk = String.valueOf(record.getOrDefault("risk_level", "C"));
        int score = "A".equalsIgnoreCase(risk) ? 12 : "B".equalsIgnoreCase(risk) ? 58 : 86;
        return Map.of("result", Map.of("code", "0000", "message", "success"), "data", Map.of(
                "risk_score", score, "risk_level", risk, "hit", "A".equalsIgnoreCase(risk) ? "N" : "Y",
                "rule_version", "BR-2026.09", "enterprise_code", record.get("enterprise_code")));
    }

    private String decodeBase64(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            return decoded.isBlank() ? value : decoded;
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }

    private Map<String, Object> requestFromQuery(HttpServletRequest servletRequest) {
        Map<String, Object> result = new LinkedHashMap<>();
        servletRequest.getParameterMap().forEach((key, values) ->
                result.put(key, values.length == 1 ? values[0] : List.of(values)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void validateContract(Object expected, Object actual, String path, List<String> errors) {
        String displayPath = path.isBlank() ? "$" : path;
        if (expected instanceof Map<?, ?> expectedMap) {
            if (!(actual instanceof Map<?, ?> actualMap)) {
                errors.add(displayPath + " 应为对象");
                return;
            }
            expectedMap.forEach((key, childExpected) -> {
                String name = String.valueOf(key);
                String childPath = path.isBlank() ? name : path + "." + name;
                if (!actualMap.containsKey(name) || actualMap.get(name) == null) {
                    errors.add(childPath + " 为必填字段");
                } else {
                    validateContract(childExpected, actualMap.get(name), childPath, errors);
                }
            });
            return;
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?> actualList)) {
                errors.add(displayPath + " 应为数组");
            } else if (!expectedList.isEmpty() && !actualList.isEmpty()) {
                validateContract(expectedList.get(0), actualList.get(0), displayPath + "[0]", errors);
            }
            return;
        }
        if (expected instanceof String && (!(actual instanceof String) || ((String) actual).isBlank())) {
            errors.add(displayPath + " 应为非空字符串");
        } else if (expected instanceof Number && !(actual instanceof Number)) {
            errors.add(displayPath + " 应为数字");
        } else if (expected instanceof Boolean && !(actual instanceof Boolean)) {
            errors.add(displayPath + " 应为布尔值");
        }
    }

    private Map<String, Object> errorBody(String code, String message, List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", Map.of("code", code, "message", message));
        body.put("errors", errors);
        body.put("trace_id", UUID.randomUUID().toString().replace("-", ""));
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> renderResponse(Map<String, Object> template, Map<String, Object> request) {
        return (Map<String, Object>) renderValue(template, request);
    }

    private Object renderValue(Object value, Map<String, Object> request) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(String.valueOf(key), renderValue(child, request)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> renderValue(item, request)).toList();
        if (!(value instanceof String text)) return value;
        Matcher matcher = REQUEST_TOKEN.matcher(text);
        if (matcher.matches()) {
            Object resolved = readPath(request, matcher.group(1));
            return resolved == null ? text : resolved;
        }
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object resolved = readPath(request, matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(resolved == null ? matcher.group() : String.valueOf(resolved)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private Object readPath(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(segment);
        }
        return current;
    }

    private DataSourceSummary dataSourceByCode(String code) {
        return jdbc.queryForObject("SELECT * FROM vendor_data_source WHERE source_code=?", (rs, rowNum) -> toDataSource(rs, true), code);
    }

    private DataSourceSummary dataSourceById(long id) {
        return jdbc.queryForObject("SELECT * FROM vendor_data_source WHERE id=?", (rs, rowNum) -> toDataSource(rs, true), id);
    }

    private ApiSummary apiByCode(String code) {
        return jdbc.queryForObject(apiSelect() + " WHERE a.api_code=?", (rs, rowNum) -> toApi(rs), code);
    }

    private ApiSummary apiById(long id) {
        return jdbc.queryForObject(apiSelect() + " WHERE a.id=?", (rs, rowNum) -> toApi(rs), id);
    }

    private DataSourceSummary toDataSource(ResultSet rs, boolean includeDisabledApis) throws SQLException {
        long id = rs.getLong("id");
        String condition = includeDisabledApis ? "" : " AND a.status='ACTIVE'";
        List<ApiSummary> sourceApis = jdbc.query(apiSelect() + " WHERE a.data_source_id=?" + condition + " ORDER BY a.id",
                (apiRs, rowNum) -> toApi(apiRs), id);
        return new DataSourceSummary(id, rs.getString("source_code"), rs.getString("source_name"),
                rs.getString("description"), rs.getString("base_path"), rs.getString("status"),
                rs.getTimestamp("updated_at").toLocalDateTime().toString(), sourceApis);
    }

    private ApiSummary toApi(ResultSet rs) throws SQLException {
        return new ApiSummary(rs.getLong("id"), rs.getLong("data_source_id"), rs.getString("source_code"),
                rs.getString("source_name"), rs.getString("api_code"), rs.getString("api_name"),
                rs.getString("service_path"), rs.getString("http_method"), read(rs.getString("request_example")),
                read(rs.getString("response_body")), rs.getString("response_code"), rs.getInt("delay_ms"),
                rs.getString("status"), rs.getInt("version"), rs.getTimestamp("updated_at").toLocalDateTime().toString());
    }

    private String apiSelect() {
        return """
                SELECT a.*,d.source_code,d.source_name FROM vendor_api_definition a
                JOIN vendor_data_source d ON d.id=a.data_source_id
                """;
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeBasePath(String value) {
        String path = value.trim().replaceAll("/{2,}", "/");
        path = path.startsWith("/") ? path : "/" + path;
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private String normalizeServicePath(long dataSourceId, String value) {
        String path = normalizeBasePath(value);
        String basePath = jdbc.queryForObject(
                "SELECT base_path FROM vendor_data_source WHERE id=?", String.class, dataSourceId);
        if (basePath == null) throw new IllegalArgumentException("所属数据源不存在");
        basePath = normalizeBasePath(basePath);
        if (!path.startsWith(basePath + "/")) {
            throw new IllegalArgumentException("服务路径必须位于数据源基础路径 " + basePath + " 下");
        }
        return path;
    }

    private String write(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("JSON 配置无效", e); }
    }

    private Map<String, Object> read(String value) {
        try { return mapper.readValue(value, JSON_MAP); }
        catch (Exception e) { return Map.of(); }
    }

    public record SaveDataSourceRequest(@NotBlank String sourceCode, @NotBlank String sourceName,
                                        String description, @NotBlank String basePath, @NotBlank String status) {}

    public record SaveApiRequest(@NotNull Long dataSourceId, @NotBlank String apiCode, @NotBlank String apiName,
                                 @NotBlank String servicePath, @NotBlank String httpMethod,
                                 @NotNull Map<String, Object> requestExample,
                                 @NotNull Map<String, Object> responseBody, @NotBlank String responseCode,
                                 int delayMs, @NotBlank String status) {}

    public record SaveEnterpriseRecord(@NotBlank String enterpriseCode, @NotBlank String creditCode,
                                       @NotBlank String enterpriseName, String legalPerson, String sexCode,
                                       String riskLevel, @NotBlank String registrationStatus) {}

    public record EnterpriseRecord(long id, String enterpriseCode, String creditCode, String enterpriseName,
                                   String legalPerson, String sexCode, String riskLevel, String registrationStatus) {}

    public record DataSourceSummary(long id, String sourceCode, String sourceName, String description,
                                    String basePath, String status, String updatedAt, List<ApiSummary> apis) {}

    public record ApiSummary(long id, long dataSourceId, String dataSourceCode, String dataSourceName,
                             String apiCode, String apiName, String servicePath, String httpMethod,
                             Map<String, Object> requestExample, Map<String, Object> responseBody,
                             String responseCode, int delayMs, String status, int version, String updatedAt) {}
}
