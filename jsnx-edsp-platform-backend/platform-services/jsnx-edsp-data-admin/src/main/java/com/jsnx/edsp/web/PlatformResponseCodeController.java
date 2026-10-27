package com.jsnx.edsp.web;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/platform-response-codes")
public class PlatformResponseCodeController {
    private static final Set<String> STATUSES = Set.of("HIT", "MISS", "ERROR");
    private static final Set<String> CATEGORIES = Set.of("SUCCESS", "NO_DATA", "REQUEST", "AUTH",
            "LIMIT", "DEPENDENCY", "UPSTREAM", "PLATFORM", "SECURITY", "FILE", "OTHER");
    private final JdbcTemplate jdbc;

    public PlatformResponseCodeController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Code(@NotBlank String code, @NotBlank String name, @NotBlank String resultStatus,
                       String category, String description, boolean enabled) {}

    @PostConstruct
    public void ensureDictionary() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_response_code (
                    code VARCHAR(64) PRIMARY KEY, name VARCHAR(128) NOT NULL,
                    result_status VARCHAR(16) NOT NULL, category VARCHAR(24) NOT NULL DEFAULT 'OTHER',
                    description VARCHAR(500) NOT NULL DEFAULT '',
                    enabled TINYINT(1) NOT NULL DEFAULT 1,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)
                """);
        Integer categoryColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='platform_response_code' AND column_name='category'
                """, Integer.class);
        if (categoryColumns != null && categoryColumns == 0) {
            jdbc.execute("ALTER TABLE platform_response_code ADD COLUMN category VARCHAR(24) NOT NULL DEFAULT 'OTHER' AFTER result_status");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM platform_response_code", Integer.class);
        if (existing != null && existing == 0) {
            jdbc.update("""
                    INSERT INTO platform_response_code(code,name,result_status,description) VALUES
                    ('0000','成功','HIT','HTTP 成功判定默认码'),
                    ('EXT-SUCCESS','查询成功','HIT','平台默认成功码'),
                    ('EXT-NOT-FOUND','未查得数据','MISS','正常返回但未命中'),
                    ('EXT-PLATFORM-ERROR','平台处理异常','ERROR','平台处理失败')
                    """);
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_dictionary_seed (
                    catalog_key VARCHAR(64) PRIMARY KEY,
                    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        Integer seeded = jdbc.queryForObject("SELECT COUNT(*) FROM platform_dictionary_seed WHERE catalog_key=?",
                Integer.class, "PRODUCTION_RESPONSE_CODES_V1");
        if (seeded == null || seeded == 0) {
            jdbc.batchUpdate("""
                    INSERT IGNORE INTO platform_response_code(code,name,result_status,category,description,enabled)
                    VALUES(?,?,?,?,?,?)
                    """, PlatformResponseCodeCatalog.CODES, PlatformResponseCodeCatalog.CODES.size(), (statement, code) -> {
                statement.setString(1, code.code());
                statement.setString(2, code.name());
                statement.setString(3, code.resultStatus());
                statement.setString(4, code.category());
                statement.setString(5, code.description());
                statement.setBoolean(6, code.enabled());
            });
            jdbc.batchUpdate("""
                    UPDATE platform_response_code
                    SET name=?,result_status=?,category=?,description=?
                    WHERE code=? AND name=CONCAT('已使用：',code) AND description='从现有接口规则登记'
                    """, PlatformResponseCodeCatalog.CODES, PlatformResponseCodeCatalog.CODES.size(), (statement, code) -> {
                statement.setString(1, code.name());
                statement.setString(2, code.resultStatus());
                statement.setString(3, code.category());
                statement.setString(4, code.description());
                statement.setString(5, code.code());
            });
            jdbc.update("INSERT IGNORE INTO platform_dictionary_seed(catalog_key) VALUES(?)", "PRODUCTION_RESPONSE_CODES_V1");
        }
        jdbc.update("""
                INSERT IGNORE INTO platform_response_code(code,name,result_status,description)
                SELECT standard_code,CONCAT('已使用：',standard_code),MIN(result_status),'从现有接口规则登记'
                FROM response_code_mapping GROUP BY standard_code
                """);
    }

    @GetMapping
    public List<Code> list() {
        return jdbc.query("SELECT code,name,result_status,category,description,enabled FROM platform_response_code ORDER BY code",
                (rs, i) -> new Code(rs.getString("code"), rs.getString("name"), rs.getString("result_status"),
                        rs.getString("category"), rs.getString("description"), rs.getBoolean("enabled")));
    }

    @PostMapping
    public Code create(@Valid @RequestBody Code input, HttpServletRequest request,
                       @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Code code = normalized(input);
        try {
            jdbc.update("INSERT INTO platform_response_code(code,name,result_status,category,description,enabled) VALUES(?,?,?,?,?,?)",
                    code.code(), code.name(), code.resultStatus(), code.category(), code.description(), code.enabled());
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台返回码已存在");
        }
        return code;
    }

    @PutMapping("/{code}")
    public Code update(@PathVariable String code, @Valid @RequestBody Code input, HttpServletRequest request,
                       @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Code next = normalized(input);
        if (!code.equals(next.code()) && referenced(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "返回码已被接口引用，不能修改编码");
        }
        try {
            if (jdbc.update("UPDATE platform_response_code SET code=?,name=?,result_status=?,category=?,description=?,enabled=? WHERE code=?",
                    next.code(), next.name(), next.resultStatus(), next.category(), next.description(), next.enabled(), code) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台返回码不存在");
            }
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标返回码已存在");
        }
        return next;
    }

    @DeleteMapping("/{code}")
    public Map<String, Boolean> delete(@PathVariable String code, HttpServletRequest request,
                                       @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        if (referenced(code)) throw new ResponseStatusException(HttpStatus.CONFLICT, "返回码已被接口引用，不能删除");
        if (jdbc.update("DELETE FROM platform_response_code WHERE code=?", code) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台返回码不存在");
        }
        return Map.of("deleted", true);
    }

    private boolean referenced(String code) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM response_code_mapping WHERE standard_code=?", Integer.class, code);
        Integer successCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM api_interface WHERE JSON_UNQUOTE(JSON_EXTRACT(response_policy,'$.successCode'))=?
                """, Integer.class, code);
        Integer publishedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM release_plan_snapshot WHERE
                JSON_SEARCH(plan_json,'one',?,NULL,'$.responseCodes[*].standardCode') IS NOT NULL
                OR JSON_UNQUOTE(JSON_EXTRACT(plan_json,'$.responsePolicy.successCode'))=?
                """, Integer.class, code, code);
        return (count != null && count > 0) || (successCount != null && successCount > 0)
                || (publishedCount != null && publishedCount > 0);
    }

    private Code normalized(Code input) {
        String code = input.code().trim();
        String name = input.name().trim();
        String status = input.resultStatus().trim().toUpperCase();
        String category = input.category() == null || input.category().isBlank()
                ? "OTHER" : input.category().trim().toUpperCase();
        String description = input.description() == null ? "" : input.description().trim();
        if (!code.matches("[A-Za-z0-9_-]{1,64}") || name.isBlank() || name.length() > 128
                || description.length() > 500 || !STATUSES.contains(status) || !CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "返回码、名称或结果状态不合法");
        }
        return new Code(code, name, status, category, description, input.enabled());
    }

    private void requireOperator(HttpServletRequest request, String token) {
        var session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(ChainTestToolController.SESSION_TOKEN);
        if (!(stored instanceof String expected) || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录后维护返回码字典");
        }
    }
}
