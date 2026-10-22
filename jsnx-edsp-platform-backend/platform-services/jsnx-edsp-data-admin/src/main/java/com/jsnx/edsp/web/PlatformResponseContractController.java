package com.jsnx.edsp.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-level standard response definitions. Interface orchestration refers
 * to these definitions; it does not duplicate the platform contract editor.
 */
@RestController
@RequestMapping("/api/platform-response-contracts")
public class PlatformResponseContractController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final RestClient restClient = RestClient.create();

    public PlatformResponseContractController(JdbcTemplate jdbc, ObjectMapper mapper, Environment environment) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.environment = environment;
    }

    public record Field(@NotBlank String key, @NotBlank String label, @NotBlank String type,
                        boolean required, String description) {}

    public record Contract(Long id, @NotBlank String contractCode, @NotBlank String contractName,
                           int version, String description, boolean enabled, boolean defaultContract, boolean active,
                           List<Field> fields) {}

    @PostConstruct
    public void ensureDictionary() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_response_contract (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    contract_code VARCHAR(64) NOT NULL UNIQUE,
                    contract_name VARCHAR(128) NOT NULL,
                    version_no INT NOT NULL,
                    description VARCHAR(500) NOT NULL DEFAULT '',
                    fields_json JSON NOT NULL,
                    active_fields_json JSON NULL,
                    is_active TINYINT(1) NOT NULL DEFAULT 0,
                    enabled TINYINT(1) NOT NULL DEFAULT 1,
                    is_default TINYINT(1) NOT NULL DEFAULT 0,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)
                """);
        ensureColumn("active_fields_json", "JSON NULL AFTER fields_json");
        ensureColumn("is_active", "TINYINT(1) NOT NULL DEFAULT 0 AFTER active_fields_json");
        jdbc.update("UPDATE platform_response_contract SET active_fields_json=fields_json,is_active=1 WHERE is_default=1 AND active_fields_json IS NULL");
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM platform_response_contract", Integer.class);
        if (existing != null && existing == 0) {
            Contract standard = new Contract(null, "EDSP_STANDARD", "平台标准响应 V1", 1,
                    "平台统一对外响应结构", true, true, true, defaultFields());
            jdbc.update("""
                    INSERT INTO platform_response_contract(contract_code,contract_name,version_no,description,
                    fields_json,active_fields_json,is_active,enabled,is_default) VALUES(?,?,?,?,?,?,1,?,?)
                    """, standard.contractCode(), standard.contractName(), standard.version(), standard.description(),
                    writeFields(standard.fields()), writeFields(standard.fields()), standard.enabled(), standard.defaultContract());
        }
    }

    @GetMapping
    public List<Contract> list() {
        return jdbc.query("""
                SELECT id,contract_code,contract_name,version_no,description,fields_json,active_fields_json,enabled,is_default,is_active
                FROM platform_response_contract ORDER BY is_default DESC, version_no DESC, id DESC
                """, (rs, rowNum) -> new Contract(rs.getLong("id"), rs.getString("contract_code"),
                rs.getString("contract_name"), rs.getInt("version_no"), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"), active(rs), readFields(rs.getString("fields_json"))));
    }

    @GetMapping("/effective")
    public Map<String, Object> effective() {
        return jdbc.query("""
                SELECT contract_code,version_no,active_fields_json FROM platform_response_contract WHERE is_active=1
                ORDER BY id DESC LIMIT 1
                """, rs -> {
            if (!rs.next()) return Map.of("contractCode", "EDSP_STANDARD", "version", 1, "fields", defaultFields());
            return Map.of("contractCode", rs.getString("contract_code"), "version", rs.getInt("version_no"),
                    "fields", readFields(rs.getString("active_fields_json")));
        });
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public Contract activate(@PathVariable long id, HttpServletRequest request,
                             @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Contract current = find(id);
        if (!current.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "已停用的标准响应不能生效");
        jdbc.update("UPDATE platform_response_contract SET is_active=0 WHERE is_active=1");
        jdbc.update("UPDATE platform_response_contract SET active_fields_json=fields_json,is_active=1 WHERE id=?", id);
        runAfterCommit(this::refreshGateways);
        return find(id);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private void refreshGateways() {
        for (String configured : environment.getProperty("edsp.gateway-refresh-urls", "http://127.0.0.1:8080").split(",")) {
            String endpoint = configured.trim().replaceAll("/+$", "");
            if (endpoint.isBlank()) continue;
            try { restClient.post().uri(endpoint + "/internal/response-contract/refresh").retrieve().toBodilessEntity(); }
            catch (Exception ignored) { /* The Gateway refresh schedule repairs a missed notification. */ }
        }
    }

    @PostMapping
    @Transactional
    public Contract create(@Valid @RequestBody Contract input, HttpServletRequest request,
                           @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Contract value = normalized(input, null);
        try {
            if (value.defaultContract()) clearDefault();
            jdbc.update("""
                    INSERT INTO platform_response_contract(contract_code,contract_name,version_no,description,
                    fields_json,enabled,is_default) VALUES(?,?,?,?,?,?,?)
                    """, value.contractCode(), value.contractName(), value.version(), value.description(),
                    writeFields(value.fields()), value.enabled(), value.defaultContract());
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "标准响应编码已存在");
        }
        return findByCode(value.contractCode());
    }

    @PutMapping("/{id}")
    @Transactional
    public Contract update(@PathVariable long id, @Valid @RequestBody Contract input,
                           HttpServletRequest request,
                           @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Contract current = find(id);
        Contract value = normalized(input, id);
        if (value.defaultContract()) clearDefault();
        try {
            jdbc.update("""
                    UPDATE platform_response_contract SET contract_code=?,contract_name=?,version_no=?,
                    description=?,fields_json=?,enabled=?,is_default=? WHERE id=?
                    """, value.contractCode(), value.contractName(), value.version(), value.description(),
                    writeFields(value.fields()), value.enabled(), value.defaultContract(), id);
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "标准响应编码已存在");
        }
        return find(id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Boolean> delete(@PathVariable long id, HttpServletRequest request,
                                       @RequestHeader("X-Edsp-Operator-Token") String token) {
        requireOperator(request, token);
        Contract current = find(id);
        if (current.defaultContract()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "默认标准响应不能删除，请先设置其他默认项");
        }
        if (isActive(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "当前生效的标准响应不能删除，请先让其他版本生效");
        if (jdbc.update("DELETE FROM platform_response_contract WHERE id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "标准响应不存在");
        }
        return Map.of("deleted", true);
    }

    private Contract normalized(Contract input, Long id) {
        String code = input.contractCode() == null ? "" : input.contractCode().trim();
        String name = input.contractName() == null ? "" : input.contractName().trim();
        String description = input.description() == null ? "" : input.description().trim();
        if (!code.matches("[A-Za-z0-9_-]{1,64}") || name.isBlank() || name.length() > 128
                || description.length() > 500 || input.version() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准响应编码、名称或版本不合法");
        }
        List<Field> fields = input.fields() == null ? List.of() : input.fields();
        if (fields.isEmpty() || fields.size() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准响应至少需要一个字段，最多支持 64 个字段");
        }
        Map<String, Boolean> keys = new LinkedHashMap<>();
        List<Field> normalizedFields = new ArrayList<>();
        for (Field field : fields) {
            String key = field.key() == null ? "" : field.key().trim();
            String label = field.label() == null ? "" : field.label().trim();
            String type = field.type() == null ? "" : field.type().trim().toUpperCase();
            String fieldDescription = field.description() == null ? "" : field.description().trim();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || label.isBlank()
                    || type.isBlank() || fieldDescription.length() > 500 || keys.put(key, true) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准响应字段不合法或存在重复字段");
            }
            normalizedFields.add(new Field(key, label, type, field.required(), fieldDescription));
        }
        return new Contract(id, code, name, input.version(), description, input.enabled(),
                input.defaultContract(), false, normalizedFields);
    }

    private Contract find(long id) {
        return jdbc.query("""
                SELECT id,contract_code,contract_name,version_no,description,fields_json,active_fields_json,enabled,is_default,is_active
                FROM platform_response_contract WHERE id=?
                """, (rs, rowNum) -> new Contract(rs.getLong("id"), rs.getString("contract_code"),
                rs.getString("contract_name"), rs.getInt("version_no"), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"), active(rs), readFields(rs.getString("fields_json"))), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "标准响应不存在"));
    }

    private Contract findByCode(String code) {
        return jdbc.query("""
                SELECT id,contract_code,contract_name,version_no,description,fields_json,active_fields_json,enabled,is_default,is_active
                FROM platform_response_contract WHERE contract_code=?
                """, (rs, rowNum) -> new Contract(rs.getLong("id"), rs.getString("contract_code"),
                rs.getString("contract_name"), rs.getInt("version_no"), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getBoolean("is_default"), active(rs), readFields(rs.getString("fields_json"))), code)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "标准响应不存在"));
    }

    private boolean active(java.sql.ResultSet rs) throws java.sql.SQLException {
        return rs.getBoolean("is_active")
                && readFields(rs.getString("fields_json")).equals(readFields(rs.getString("active_fields_json")));
    }

    private boolean isActive(long id) {
        Integer active = jdbc.queryForObject("SELECT is_active FROM platform_response_contract WHERE id=?", Integer.class, id);
        return active != null && active == 1;
    }

    private void clearDefault() {
        jdbc.update("UPDATE platform_response_contract SET is_default=0 WHERE is_default=1");
    }

    private void ensureColumn(String column, String definition) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='platform_response_contract' AND column_name=?
                """, Integer.class, column);
        if (exists == null || exists == 0) jdbc.execute("ALTER TABLE platform_response_contract ADD COLUMN " + column + " " + definition);
    }

    private String writeFields(List<Field> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标准响应字段无法保存");
        }
    }

    private List<Field> readFields(String raw) {
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "标准响应字段配置损坏");
        }
    }

    private List<Field> defaultFields() {
        return List.of(
                new Field("code", "平台返回码", "STRING", true, "平台统一结果码"),
                new Field("message", "平台提示语", "STRING", true, "平台统一提示语"),
                new Field("resultStatus", "结果状态", "ENUM", true, "HIT / MISS / ERROR"),
                new Field("traceId", "全局流水号", "STRING", true, "全链路追踪号"),
                new Field("data", "业务数据", "OBJECT", true, "标准化数据或完整供应商报文"));
    }

    private void requireOperator(HttpServletRequest request, String token) {
        var session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(ChainTestToolController.SESSION_TOKEN);
        if (!(stored instanceof String expected) || token == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录后维护标准响应");
        }
    }
}
