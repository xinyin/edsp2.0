package com.jsnx.edsp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.crypto.SecretReferenceResolver;
import com.jsnx.edsp.crypto.CryptoSupport;
import com.jsnx.edsp.model.ApiModels.*;
import com.jsnx.edsp.plan.PlanMappingSupport;
import com.jsnx.edsp.plan.ResponsePolicySupport;
import com.jsnx.edsp.plan.SecurityPlanSupport;
import com.jsnx.edsp.plan.SecurityAlgorithm;
import com.jsnx.edsp.plan.SecurityAlgorithmRegistry;
import com.jsnx.edsp.plan.SqlQuerySupport;
import com.jsnx.edsp.plan.XmlRequestTypeSupport;
import com.jsnx.edsp.transform.FieldTransformSupport;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.net.URI;
import java.util.*;

@Service
public class InterfaceService {
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final List<String> RESERVED_GATEWAY_PATHS = List.of(
            "/api/auth", "/api/dashboard", "/api/providers", "/api/data-sources",
            "/api/resource-apis", "/api/platform-services", "/api/interfaces",
            "/api/approvals", "/api/clients", "/api/authorizations", "/api/billing",
            "/api/usage-records", "/api/security-algorithms", "/api/platform-response-codes", "/api/call-logs",
            "/api/monitor", "/vendor-api", "/internal", "/actuator", "/error");
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final ObjectMapper mapper;
    private final PublishedPlanRegistry plans;
    private final Environment environment;
    private final SecretReferenceResolver secretResolver;
    private final RestClient restClient = RestClient.create();

    public InterfaceService(JdbcTemplate jdbc, JsonSupport json, ObjectMapper mapper,
                            PublishedPlanRegistry plans, Environment environment) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = mapper;
        this.plans = plans;
        this.environment = environment;
        this.secretResolver = new SecretReferenceResolver(environment::getProperty);
    }

    @PostConstruct
    public void restorePublishedPlans() {
        ensureSchemaMigrations();
        // SQL initialization is intentionally disabled in deployed environments; keep the small
        // The published-plan snapshot self-heals the local cache after an application restart.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS release_plan_snapshot (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    interface_id BIGINT NOT NULL,
                    release_version INT NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    plan_json JSON NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_plan_snapshot_version (interface_id, release_version)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS interface_lifecycle_record (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    interface_id BIGINT NOT NULL,
                    action VARCHAR(24) NOT NULL,
                    operated_by VARCHAR(64) NOT NULL,
                    reason VARCHAR(500),
                    operated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_lifecycle_interface (interface_id, operated_at)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS published_route (
                    interface_id BIGINT PRIMARY KEY,
                    interface_code VARCHAR(64) NOT NULL UNIQUE,
                    service_path VARCHAR(160) NOT NULL,
                    http_method VARCHAR(16) NOT NULL,
                    route_version INT NOT NULL,
                    data_source_id BIGINT NOT NULL,
                    published_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_published_route_method_path (http_method, service_path),
                    INDEX idx_published_route_source (data_source_id)
                )
                """);
        // One-time migration for versions published before the immutable route table existed.
        // Prefer the released Plan snapshot because api_interface may already contain edits
        // for the next draft. Falling back is only for legacy releases without a snapshot.
        jdbc.update("""
                INSERT IGNORE INTO published_route(interface_id,interface_code,service_path,http_method,route_version,data_source_id)
                SELECT i.id,i.interface_code,
                       COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.plan_json,'$.summary.servicePath')),''),i.service_path),
                       COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.plan_json,'$.summary.httpMethod')),''),i.http_method),
                       i.published_version,
                       COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(s.plan_json,'$.summary.dataSourceId')) AS UNSIGNED),i.data_source_id)
                FROM api_interface i
                LEFT JOIN release_plan_snapshot s
                  ON s.interface_id=i.id AND s.release_version=i.published_version
                WHERE i.published_version IS NOT NULL AND i.status NOT IN ('OFFLINE','DELETED')
                """);
        jdbc.queryForList("""
                SELECT i.id, i.published_version, s.plan_json, s.checksum
                FROM api_interface i LEFT JOIN release_plan_snapshot s
                  ON s.interface_id=i.id AND s.release_version=i.published_version
                WHERE i.published_version IS NOT NULL AND i.status <> 'OFFLINE' AND i.status <> 'DELETED'
                """).forEach(row -> {
            long interfaceId = ((Number) row.get("id")).longValue();
            try {
                if (row.get("plan_json") != null) {
                    Map<String, Object> snapshot = mapper.readValue(String.valueOf(row.get("plan_json")), Map.class);
                    plans.activate(new PublishedPlanRegistry.PublishedPlan(interfaceId,
                            String.valueOf(snapshot.get("interfaceCode")),
                            ((Number) snapshot.get("version")).intValue(),
                            mapper.convertValue(snapshot.get("requestMappings"), new TypeReference<List<FieldMapping>>() {}),
                            mapper.convertValue(snapshot.get("responseMappings"), new TypeReference<List<FieldMapping>>() {}),
                            String.valueOf(snapshot.get("checksum")), Instant.now(),
                            mapper.convertValue(snapshot.get("summary"), InterfaceSummary.class),
                            normalizeVendorCodePath(String.valueOf(snapshot.getOrDefault("vendorCodePath", "result.code"))),
                            mapper.convertValue(snapshot.get("responseCodes"), new TypeReference<List<ResponseCodeMapping>>() {}),
                            mapper.convertValue(snapshot.get("securityPolicy"), SecurityPolicy.class),
                            responsePolicy(snapshot.get("responsePolicy"), !((List<?>) snapshot.getOrDefault("responseMappings", List.of())).isEmpty())));
                    return;
                }
            } catch (Exception ignored) {
                // Fall back to the live record for databases upgraded before snapshots were introduced.
            }
            InterfaceDetail detail = interfaceDetail(interfaceId);
            List<FieldMapping> mappings = detail.mappings();
            plans.activate(new PublishedPlanRegistry.PublishedPlan(interfaceId, detail.summary().interfaceCode(),
                    detail.summary().publishedVersion() == null ? detail.summary().currentVersion() : detail.summary().publishedVersion(),
                    mappings.stream().filter(item -> "REQUEST".equals(item.direction())).toList(),
                    mappings.stream().filter(item -> "RESPONSE".equals(item.direction())).toList(),
                    checksum(detail), Instant.now(), detail.summary(), detail.vendorCodePath(), detail.responseCodes(), detail.securityPolicy(), detail.responsePolicy()));
        });
    }

    private void ensureSchemaMigrations() {
        Integer runtimeUrlColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='data_source' AND column_name='runtime_url'
                """, Integer.class);
        if (runtimeUrlColumns == null || runtimeUrlColumns == 0) {
            jdbc.execute("ALTER TABLE data_source ADD COLUMN runtime_url VARCHAR(500) NULL AFTER application_name");
        }
        Integer serverPortColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='data_source' AND column_name='server_port'
                """, Integer.class);
        if (serverPortColumns == null || serverPortColumns == 0) {
            jdbc.execute("ALTER TABLE data_source ADD COLUMN server_port INT NOT NULL DEFAULT 8199 AFTER runtime_url");
            jdbc.queryForList("SELECT id,runtime_url FROM data_source WHERE runtime_url IS NOT NULL").forEach(row -> {
                int inferredPort = inferRuntimePort(String.valueOf(row.get("runtime_url")));
                jdbc.update("UPDATE data_source SET server_port=? WHERE id=?", inferredPort, row.get("id"));
            });
        }
        jdbc.update("""
                UPDATE data_source SET runtime_url=CASE source_code
                    WHEN 'ENTERPRISE' THEN 'http://127.0.0.1:8181'
                    WHEN 'CREDIT' THEN 'http://127.0.0.1:8182'
                    WHEN 'ANTIFRAUD' THEN 'http://127.0.0.1:8183'
                    ELSE runtime_url END
                WHERE runtime_url IS NULL
                """);
        ensureProviderRoutingColumns();
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS resource_api (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    data_source_id BIGINT NOT NULL,
                    resource_api_code VARCHAR(64) NOT NULL UNIQUE,
                    resource_api_name VARCHAR(128) NOT NULL,
                    http_method VARCHAR(16) NOT NULL DEFAULT 'POST',
                    upstream_url VARCHAR(500) NOT NULL,
                    upstream_path VARCHAR(256),
                    config_scope VARCHAR(16) NOT NULL DEFAULT 'OVERRIDE',
                    request_example JSON NOT NULL,
                    response_example JSON NOT NULL,
                    request_headers JSON NOT NULL DEFAULT ('{}'),
                    timeout_ms INT,
                    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                    current_version INT NOT NULL DEFAULT 1,
                    verified_at DATETIME NULL,
                    verified_checksum VARCHAR(64) NULL,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_resource_api_source(data_source_id),
                    INDEX idx_resource_api_status(status)
                )
                """);
        Integer verifiedChecksumColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='resource_api' AND column_name='verified_checksum'
                """, Integer.class);
        if (verifiedChecksumColumns == null || verifiedChecksumColumns == 0) {
            jdbc.execute("ALTER TABLE resource_api ADD COLUMN verified_checksum VARCHAR(64) NULL AFTER verified_at");
        }
        if (columnMissing("resource_api", "request_headers")) {
            jdbc.execute("ALTER TABLE resource_api ADD COLUMN request_headers JSON NULL AFTER response_example");
            jdbc.update("UPDATE resource_api SET request_headers='{}' WHERE request_headers IS NULL");
            jdbc.execute("ALTER TABLE resource_api MODIFY COLUMN request_headers JSON NOT NULL DEFAULT ('{}')");
        }
        if (columnMissing("resource_api", "execution_mode")) {
            jdbc.execute("ALTER TABLE resource_api ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'HTTP' AFTER request_headers");
        }
        if (columnMissing("resource_api", "sql_template")) {
            jdbc.execute("ALTER TABLE resource_api ADD COLUMN sql_template TEXT NULL AFTER execution_mode");
        }
        if (columnMissing("resource_api", "sql_parameters")) {
            jdbc.execute("ALTER TABLE resource_api ADD COLUMN sql_parameters JSON NOT NULL DEFAULT ('{}') AFTER sql_template");
        }
        if (columnMissing("api_interface", "upstream_request_headers")) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN upstream_request_headers JSON NULL AFTER standard_response_example");
            jdbc.update("UPDATE api_interface SET upstream_request_headers='{}' WHERE upstream_request_headers IS NULL");
            jdbc.execute("ALTER TABLE api_interface MODIFY COLUMN upstream_request_headers JSON NOT NULL DEFAULT ('{}')");
        }
        if (columnMissing("api_interface", "execution_mode")) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'HTTP' AFTER upstream_request_headers");
        }
        if (columnMissing("api_interface", "sql_template")) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN sql_template TEXT NULL AFTER execution_mode");
        }
        if (columnMissing("api_interface", "sql_parameters")) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN sql_parameters JSON NOT NULL DEFAULT ('{}') AFTER sql_template");
        }
        // Before verified_checksum existed, saveResourceApi already cleared verified_at on every
        // contract change. Therefore a remaining verified_at safely identifies a previously
        // tested current contract; materialize its fingerprint once during the upgrade.
        jdbc.query("""
                SELECT ra.id,ra.http_method,ra.upstream_url,COALESCE(ra.timeout_ms,ds.default_timeout_ms) effective_timeout_ms,
                       ra.request_example,ra.response_example
                FROM resource_api ra JOIN data_source ds ON ds.id=ra.data_source_id
                WHERE ra.verified_at IS NOT NULL AND ra.verified_checksum IS NULL
                """, rs -> {
            long resourceId = rs.getLong("id");
            String verifiedChecksum = upstreamChecksum(rs.getString("http_method"), rs.getString("upstream_url"),
                    rs.getInt("effective_timeout_ms"), json.readMap(rs.getString("request_example")),
                    json.readMap(rs.getString("response_example")));
            jdbc.update("UPDATE resource_api SET verified_checksum=? WHERE id=?", verifiedChecksum, resourceId);
        });
        Integer resourceApiColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='api_interface' AND column_name='resource_api_id'
                """, Integer.class);
        if (resourceApiColumns == null || resourceApiColumns == 0) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN resource_api_id BIGINT NULL AFTER id");
        }
        // Existing platform services are retained as services and receive a matching
        // resource contract. This migration is idempotent and does not merge the two lifecycles.
        jdbc.update("""
                INSERT INTO resource_api(data_source_id,resource_api_code,resource_api_name,http_method,
                    upstream_url,upstream_path,config_scope,request_example,response_example,timeout_ms,
                    status,current_version,verified_at)
                SELECT ai.data_source_id,CONCAT('RES_',LEFT(ai.interface_code,60)),CONCAT(ai.interface_name,'（资源接口）'),
                    ai.http_method,ai.upstream_url,ai.upstream_path,ai.config_scope,
                    COALESCE(ai.upstream_request_example,ai.request_example),ai.upstream_response_example,
                    ai.timeout_ms,'READY',1,NULL
                FROM api_interface ai
                WHERE ai.resource_api_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM resource_api ra
                                  WHERE ra.resource_api_code=CONCAT('RES_',LEFT(ai.interface_code,60)))
                """);
        jdbc.update("""
                UPDATE api_interface ai JOIN resource_api ra
                  ON ra.resource_api_code=CONCAT('RES_',LEFT(ai.interface_code,60))
                SET ai.resource_api_id=ra.id WHERE ai.resource_api_id IS NULL
                """);
        Integer upstreamStatusColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='api_interface' AND column_name='upstream_status'
                """, Integer.class);
        if (upstreamStatusColumns == null || upstreamStatusColumns == 0) {
            // Existing configured APIs already passed the legacy upstream step. Only APIs
            // created after this migration start as DRAFT until their upstream contract is saved.
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN upstream_status VARCHAR(16) NOT NULL DEFAULT 'READY' AFTER retry_count");
            jdbc.execute("ALTER TABLE api_interface MODIFY COLUMN upstream_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'");
        }
        Integer vendorCodePathColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='api_interface' AND column_name='vendor_code_path'
                """, Integer.class);
        if (vendorCodePathColumns == null || vendorCodePathColumns == 0) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN vendor_code_path VARCHAR(256) NOT NULL DEFAULT 'result.code' AFTER retry_count");
        }
        Integer responsePolicyColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='api_interface' AND column_name='response_policy'
                """, Integer.class);
        if (responsePolicyColumns == null || responsePolicyColumns == 0) {
            jdbc.execute("ALTER TABLE api_interface ADD COLUMN response_policy JSON NULL AFTER vendor_code_path");
        }
        migrateAuthorizationEnvironment();
        if (!columnMissing("data_source", "environment")) {
            jdbc.execute("ALTER TABLE data_source DROP COLUMN environment");
        }
    }

    void migrateAuthorizationEnvironment() {
        Integer indexCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='api_authorization' AND index_name='uk_client_interface'
                """, Integer.class);
        if (!columnMissing("api_authorization", "environment")) {
            Long conflicts = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM (
                        SELECT client_id,interface_id FROM api_authorization
                        GROUP BY client_id,interface_id HAVING COUNT(*) > 1
                    ) duplicate_grants
                    """, Long.class);
            if (conflicts != null && conflicts > 0) {
                throw new IllegalStateException("存在同一调用系统和 API 的多环境授权，请先人工合并后再升级");
            }
            if (indexCount == null || indexCount == 0) {
                jdbc.execute("ALTER TABLE api_authorization ADD UNIQUE KEY uk_client_interface (client_id,interface_id)");
            }
            Integer legacyIndexCount = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name='api_authorization' AND index_name='uk_client_interface_env'
                    """, Integer.class);
            if (legacyIndexCount != null && legacyIndexCount > 0) {
                jdbc.execute("ALTER TABLE api_authorization DROP INDEX uk_client_interface_env");
            }
            jdbc.execute("ALTER TABLE api_authorization DROP COLUMN environment");
        } else if (indexCount == null || indexCount == 0) {
            jdbc.execute("ALTER TABLE api_authorization ADD UNIQUE KEY uk_client_interface (client_id,interface_id)");
        }
    }

    private void ensureProviderRoutingColumns() {
        Map<String, String> definitions = new LinkedHashMap<>();
        definitions.put("access_mode", "VARCHAR(32) NOT NULL DEFAULT 'HTTP'");
        definitions.put("access_config", "JSON NULL");
        definitions.put("application_name", "VARCHAR(128) NULL");
        definitions.put("runtime_url", "VARCHAR(500) NULL");
        definitions.put("server_port", "INT NOT NULL DEFAULT 8199");
        definitions.put("config_version", "INT NOT NULL DEFAULT 1");
        definitions.forEach((column, definition) -> {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='data_provider' AND column_name=?
                    """, Integer.class, column);
            if (count == null || count == 0) jdbc.execute("ALTER TABLE data_provider ADD COLUMN " + column + " " + definition);
        });
        jdbc.update("UPDATE data_provider SET access_config=JSON_OBJECT() WHERE access_config IS NULL");
        jdbc.update("""
                UPDATE data_provider p
                JOIN (
                    SELECT provider_id,MIN(id) first_id FROM data_source GROUP BY provider_id
                    HAVING COUNT(DISTINCT CONCAT(COALESCE(application_name,''),'|',COALESCE(runtime_url,''),'|',server_port))=1
                ) consistent ON consistent.provider_id=p.id
                JOIN data_source ds ON ds.id=consistent.first_id
                LEFT JOIN data_provider owner ON owner.application_name=ds.application_name AND owner.id<>p.id
                SET p.application_name=COALESCE(p.application_name,ds.application_name),
                    p.server_port=CASE WHEN p.runtime_url IS NULL THEN ds.server_port ELSE p.server_port END,
                    p.runtime_url=COALESCE(p.runtime_url,ds.runtime_url)
                WHERE (p.application_name IS NULL OR p.runtime_url IS NULL)
                  AND (p.application_name IS NOT NULL OR owner.id IS NULL)
                """);
        Integer routeIndex = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='data_provider' AND index_name='uk_provider_application'
                """, Integer.class);
        if (routeIndex == null || routeIndex == 0) {
            jdbc.update("UPDATE data_provider SET application_name=NULL WHERE TRIM(application_name)=''");
            List<Map<String, Object>> duplicates = jdbc.queryForList("""
                    SELECT application_name FROM data_provider WHERE application_name IS NOT NULL
                    GROUP BY application_name HAVING COUNT(*)>1
                    """);
            if (!duplicates.isEmpty()) throw new IllegalStateException(
                    "多个资源提供商共用同一处理应用，请先拆分提供商路由：" + duplicates);
            jdbc.execute("CREATE UNIQUE INDEX uk_provider_application ON data_provider(application_name)");
        }
    }

    public DashboardSummary dashboard() {
        return new DashboardSummary(
                count("data_provider", null), count("data_source", null), count("api_interface", "status <> 'DELETED'"),
                count("api_interface", "published_version IS NOT NULL AND status NOT IN ('OFFLINE','DELETED')"),
                count("api_interface", "status <> 'DELETED' AND (published_version IS NULL OR current_version <> published_version)"),
                plans.size());
    }

    private int count(String table, String where) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + (where == null ? "" : " WHERE " + where), Integer.class)).orElse(0);
    }

    public LoginResult login(LoginRequest request) {
        if (!"admin".equals(request.username()) || !"admin123".equals(request.password())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return new LoginResult("edsp-demo-token", "平台管理员", "系统管理员", "省联社信息科技部");
    }

    public List<ProviderSummary> providers() {
        return jdbc.query("SELECT * FROM data_provider ORDER BY id", (rs, rowNum) -> providerFromRow(rs));
    }

    @Transactional
    public ProviderSummary createProvider(CreateProviderRequest request) {
        String accessMode = normalizeAccessMode(request.accessMode());
        jdbc.update("""
                INSERT INTO data_provider(provider_code,provider_name,credit_code,provider_type,contact_name,
                contact_phone,contact_email,service_phone,contract_no,contract_start_date,contract_end_date,
                security_level,settlement_mode,office_address,description,access_date,message_encoding,
                charge_required,encryption_required,login_required,status,access_mode,access_config)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, request.providerCode().trim().toUpperCase(Locale.ROOT), request.providerName(), request.creditCode(),
                valueOr(request.providerType(), "DATA_VENDOR"), request.contactName(), request.contactPhone(),
                request.contactEmail(), request.servicePhone(), request.contractNo(), request.contractStartDate(),
                request.contractEndDate(), valueOr(request.securityLevel(), "L2"),
                valueOr(request.settlementMode(), "MONTHLY"), request.officeAddress(), request.description(),
                request.accessDate(), valueOr(request.messageEncoding(), "UTF-8"), request.chargeRequired(),
                request.encryptionRequired(), request.loginRequired(),
                valueOr(request.status(), "ACTIVE"), accessMode, json.write(normalizeAccessConfig(accessMode, request.accessConfig(), null)));
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        return provider(id);
    }

    public ProviderSummary provider(long id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT * FROM data_provider WHERE id=?", (rs, rowNum) -> providerFromRow(rs), id));
    }

    public DatabaseConnectionTestResult testDatabaseConnection(long providerId) {
        ProviderSummary provider = provider(providerId);
        if (!"DATABASE".equalsIgnoreCase(provider.accessMode())) {
            throw new IllegalArgumentException("只有数据库类型的资源提供商可以测试连接");
        }
        if (provider.runtimeUrl() == null || provider.runtimeUrl().isBlank()) {
            return new DatabaseConnectionTestResult(false, "请先配置并启动资源提供商独立应用", 0, null);
        }
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("providerCode", provider.providerCode());
        connection.put("accessMode", "DATABASE");
        connection.put("accessConfig", storedProviderAccessConfig(providerId));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", "DB-CONNECTION-" + UUID.randomUUID().toString().replace("-", ""));
        payload.put("providerCode", provider.providerCode());
        payload.put("dataSourceCode", "DATABASE_CONNECTION_TEST");
        payload.put("interfaceCode", "DATABASE_CONNECTION_TEST");
        payload.put("providerConnection", connection);
        payload.put("executionMode", "CONNECTION_TEST");
        payload.put("timeoutMs", 10_000);
        payload.put("requestBody", Map.of());
        long started = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restClient(12_000).post()
                    .uri(trimTrailingSlash(provider.runtimeUrl()) + "/internal/adapter/execute")
                    .body(payload).retrieve().body(Map.class);
            long duration = Duration.ofNanos(System.nanoTime() - started).toMillis();
            if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
                String error = result == null ? "" : Objects.toString(result.get("errorMessage"), "");
                return new DatabaseConnectionTestResult(false, databaseTestFailure(error), duration, null);
            }
            Object response = result.get("upstreamResponse");
            if (!(response instanceof Map<?, ?> details) || !Boolean.TRUE.equals(details.get("connected"))) {
                return new DatabaseConnectionTestResult(false, "处理应用未返回连接校验结果，请更新并重启应用", duration, null);
            }
            return new DatabaseConnectionTestResult(true, "数据库连接正常", duration,
                    Objects.toString(details.get("databaseProduct"), ""));
        } catch (org.springframework.web.client.RestClientResponseException error) {
            String message = error.getStatusCode().value() == 404
                    ? "处理应用未提供连接测试接口，请核对应用路由和部署版本"
                    : "处理应用返回 HTTP " + error.getStatusCode().value() + "，请检查应用日志";
            return new DatabaseConnectionTestResult(false, message,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), null);
        } catch (org.springframework.web.client.RestClientException error) {
            return new DatabaseConnectionTestResult(false, "处理应用不可达或请求超时，请检查应用路由与运行状态",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), null);
        }
    }

    private String databaseTestFailure(String error) {
        String normalized = error.toLowerCase(Locale.ROOT);
        if (normalized.contains("密码") || normalized.contains("密钥引用")) return "数据库密码不可用，请检查密码及应用密钥配置";
        if (normalized.contains("driver") || normalized.contains("驱动")) return "数据库 JDBC 驱动不可用，请检查独立应用依赖";
        if (normalized.contains("access denied") || normalized.contains("authentication") || normalized.contains("login failed"))
            return "数据库认证失败，请检查账号及密码";
        if (normalized.contains("timeout") || normalized.contains("timed out")) return "数据库连接超时，请检查网络和防火墙";
        if (normalized.contains("connection refused") || normalized.contains("communications link failure"))
            return "数据库地址不可达，请检查 JDBC 地址和网络";
        if (normalized.contains("尚未配置 sql 查询模式")) return "处理应用未包含连接测试能力，请重新打包并部署";
        if (normalized.contains("连接配置不完整")) return "数据库连接配置不完整，请检查 JDBC 地址和用户名";
        return "数据库连接校验失败，请检查独立应用日志";
    }

    @Transactional
    public ProviderSummary updateProvider(long id, CreateProviderRequest request) {
        String accessMode = normalizeAccessMode(request.accessMode());
        ProviderSummary before = provider(id);
        if (before.applicationName() != null && !before.providerCode().equalsIgnoreCase(request.providerCode().trim())) {
            throw new IllegalArgumentException("已绑定处理应用的资源提供商不能直接修改编码，请先完成应用归属迁移");
        }
        jdbc.update("""
                UPDATE data_provider SET provider_code=?,provider_name=?,credit_code=?,provider_type=?,contact_name=?,
                contact_phone=?,contact_email=?,service_phone=?,contract_no=?,contract_start_date=?,contract_end_date=?,
                security_level=?,settlement_mode=?,office_address=?,description=?,access_date=?,message_encoding=?,
                charge_required=?,encryption_required=?,login_required=?,status=?,access_mode=?,access_config=? WHERE id=?
                """, request.providerCode().trim().toUpperCase(Locale.ROOT), request.providerName(), request.creditCode(),
                valueOr(request.providerType(), "DATA_VENDOR"), request.contactName(), request.contactPhone(),
                request.contactEmail(), request.servicePhone(), request.contractNo(), request.contractStartDate(),
                request.contractEndDate(), valueOr(request.securityLevel(), "L2"),
                valueOr(request.settlementMode(), "MONTHLY"), request.officeAddress(), request.description(),
                request.accessDate(), valueOr(request.messageEncoding(), "UTF-8"), request.chargeRequired(),
                request.encryptionRequired(), request.loginRequired(),
                valueOr(request.status(), "ACTIVE"), accessMode,
                json.write(normalizeAccessConfig(accessMode, request.accessConfig(), storedProviderAccessConfig(id))), id);
        runAfterCommit(() -> refreshProviderPlans(id));
        return provider(id);
    }

    @Transactional
    public void deleteProvider(long providerId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM data_provider WHERE id=?", Integer.class, providerId);
        if (exists == null || exists == 0) throw new IllegalArgumentException("资源提供商不存在或已删除");

        List<String> dependencies = new ArrayList<>();
        addProviderDependency(dependencies, "数据源", "data_source", providerId);
        addProviderDependency(dependencies, "资源提供商账号", "provider_account", providerId);
        addProviderDependency(dependencies, "接口文档", "resource_document", providerId);
        if (!dependencies.isEmpty()) {
            throw new IllegalStateException("当前资源提供商仍有关联的" + String.join("、", dependencies)
                    + "，请先处理关联数据后再删除。平台不会级联删除这些业务数据。");
        }

        int deleted = jdbc.update("DELETE FROM data_provider WHERE id=?", providerId);
        if (deleted == 0) throw new IllegalArgumentException("资源提供商不存在或已删除");
    }

    private void addProviderDependency(List<String> dependencies, String label, String table, long providerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE provider_id=?", Integer.class, providerId);
        if (count != null && count > 0) dependencies.add(label + "（" + count + "）");
    }

    @Transactional
    public ProviderSummary saveProviderRuntime(long providerId, SaveProviderRuntimeRequest request) {
        String runtimeUrl = normalizeRuntimeUrl(request.runtimeUrl(), true);
        int updated = jdbc.update("""
                UPDATE data_provider
                SET application_name=?,runtime_url=?,server_port=?,config_version=config_version+1
                WHERE id=?
                """, request.applicationName().trim(), runtimeUrl, request.serverPort(), providerId);
        if (updated == 0) throw new IllegalArgumentException("资源提供商不存在：" + providerId);
        // Retain legacy columns as a readable mirror during migration. Runtime routing never reads them.
        jdbc.update("""
                UPDATE data_source SET application_name=?,runtime_url=?,server_port=?,config_version=config_version+1
                WHERE provider_id=?
                """, request.applicationName().trim(), runtimeUrl, request.serverPort(), providerId);
        notifyGatewayRouteRefreshAfterCommit();
        runAfterCommit(() -> refreshProviderPlans(providerId));
        return provider(providerId);
    }

    public List<ProviderAccount> providerAccounts(long providerId) {
        return jdbc.query("""
                SELECT a.*,p.provider_name FROM provider_account a JOIN data_provider p ON p.id=a.provider_id
                WHERE a.provider_id=? ORDER BY a.id
                """, (rs, rowNum) -> new ProviderAccount(rs.getLong("id"), rs.getLong("provider_id"),
                rs.getString("provider_name"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("mobile"), rs.getString("email"), rs.getString("role_code"),
                readStringList(rs.getString("permissions")), rs.getString("status"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()), providerId);
    }

    @Transactional
    public ProviderAccount createProviderAccount(long providerId, SaveProviderAccountRequest request) {
        jdbc.update("""
                INSERT INTO provider_account(provider_id,username,display_name,mobile,email,role_code,permissions,status)
                VALUES(?,?,?,?,?,?,?,?)
                """, providerId, request.username(), request.displayName(), request.mobile(), request.email(),
                request.roleCode(), writeStringList(request.permissions()), request.status());
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        return providerAccount(id);
    }

    @Transactional
    public ProviderAccount updateProviderAccount(long id, SaveProviderAccountRequest request) {
        jdbc.update("""
                UPDATE provider_account SET username=?,display_name=?,mobile=?,email=?,role_code=?,permissions=?,status=?
                WHERE id=?
                """, request.username(), request.displayName(), request.mobile(), request.email(), request.roleCode(),
                writeStringList(request.permissions()), request.status(), id);
        return providerAccount(id);
    }

    @Transactional
    public DataSourceSummary createDataSource(CreateDataSourceRequest request) {
        ProviderSummary provider = provider(request.providerId());
        String applicationName = valueOr(provider.applicationName(), "jsnx-edsp-data-provider-unassigned");
        String runtimeUrl = provider.runtimeUrl();
        int serverPort = provider.serverPort();
        int defaultTimeoutMs = validTimeout(request.defaultTimeoutMs(), 3000);
        jdbc.update("""
                INSERT INTO data_source(provider_id,source_code,source_name,application_name,protocol_type,
                runtime_url,server_port,endpoint_url,default_timeout_ms,status,instance_count,config_version)
                VALUES(?,?,?,?,?,?,?,?,?,'TRIAL',?,1)
                """, request.providerId(), request.sourceCode(), request.sourceName(), applicationName,
                request.protocolType(), runtimeUrl, serverPort, trimTrailingSlash(request.endpoint()), defaultTimeoutMs,
                Math.max(1, request.instanceCount()));
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        return dataSources().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public DataSourceSummary saveDataSourceSettings(long dataSourceId, SaveDataSourceSettingsRequest request) {
        Integer sourceCount = jdbc.queryForObject("SELECT COUNT(*) FROM data_source WHERE id=?", Integer.class, dataSourceId);
        if (sourceCount == null || sourceCount == 0) throw new IllegalArgumentException("数据源不存在：" + dataSourceId);
        Integer providerCount = jdbc.queryForObject("SELECT COUNT(*) FROM data_provider WHERE id=?", Integer.class, request.providerId());
        if (providerCount == null || providerCount == 0) throw new IllegalArgumentException("资源提供商不存在：" + request.providerId());
        String protocolType = request.protocolType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HTTP_JSON", "SOAP", "LOCAL_SQL").contains(protocolType)) {
            throw new IllegalArgumentException("不支持的协议类型：" + request.protocolType());
        }
        String endpoint = normalizeDataSourceEndpoint(request.endpoint(), protocolType);
        int defaultTimeoutMs = validTimeout(request.defaultTimeoutMs(), 3000);
        jdbc.update("""
                UPDATE data_source
                SET provider_id=?,source_name=?,protocol_type=?,endpoint_url=?,default_timeout_ms=?,config_version=config_version+1
                WHERE id=?
                """, request.providerId(), request.sourceName().trim(), protocolType, endpoint, defaultTimeoutMs, dataSourceId);
        jdbc.update("""
                UPDATE resource_api SET verified_at=NULL
                WHERE data_source_id=? AND config_scope='INHERIT'
                """, dataSourceId);
        jdbc.update("""
                DELETE FROM interface_validation_record
                WHERE interface_id IN (
                    SELECT id FROM api_interface WHERE data_source_id=? AND config_scope='INHERIT'
                )
                """, dataSourceId);
        return dataSources().stream().filter(item -> item.id() == dataSourceId).findFirst().orElseThrow();
    }

    public List<ResourceApiSummary> resourceApis(Long dataSourceId) {
        String sql = resourceApiSelect() + (dataSourceId == null ? "" : " WHERE ra.data_source_id=?")
                + " ORDER BY ra.id";
        return dataSourceId == null ? jdbc.query(sql, resourceApiMapper())
                : jdbc.query(sql, resourceApiMapper(), dataSourceId);
    }

    @Transactional
    public ResourceApiSummary createResourceApi(CreateResourceApiRequest request) {
        String code = request.resourceApiCode().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> source = jdbc.queryForMap(
                "SELECT ds.endpoint_url,ds.default_timeout_ms,ds.protocol_type,p.access_mode FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id WHERE ds.id=?", request.dataSourceId());
        boolean databaseSource = "LOCAL_SQL".equalsIgnoreCase(String.valueOf(source.get("protocol_type")));
        String executionMode = normalizeExecutionMode(request.executionMode(), databaseSource ? "SQL" : "HTTP");
        validateDatabaseMode(source, executionMode);
        String sqlTemplate = normalizeSqlTemplate(request.sqlTemplate(), executionMode);
        Map<String, String> sqlParameters = validSqlParameters(request.sqlParameters());
        String scope = request.generateMock() ? "OVERRIDE" : normalizeConfigScope(request.configScope());
        String path = normalizePath(request.upstreamPath());
        String url = request.generateMock() ? "http://127.0.0.1:8080/mock/vendor/" + code
                : trimTrailingSlash(request.upstreamUrl());
        if (isSqlExecution(executionMode)) {
            url = "jdbc://" + code;
            path = null;
        } else if ("INHERIT".equals(scope)) {
            if (source.get("endpoint_url") == null || String.valueOf(source.get("endpoint_url")).isBlank()) {
                throw new IllegalArgumentException("所选数据源未配置基础地址，请改用接口独立配置");
            }
            if (path == null || path.isBlank()) throw new IllegalArgumentException("请填写供应商接口相对路径");
            url = joinUrl(String.valueOf(source.get("endpoint_url")), path);
        } else if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("供应商接口地址不能为空");
        }
        Map<String, Object> requestExample = request.requestExample() == null || request.requestExample().isEmpty()
                ? (isSqlExecution(executionMode) ? Map.of() : Map.of("query_key", "DEMO-QUERY-001"))
                : request.requestExample();
        Map<String, Object> responseExample = request.responseExample() == null || request.responseExample().isEmpty()
                ? Map.of("result", Map.of("code", "0000", "message", "success"), "data", Map.of())
                : request.responseExample();
        Integer timeout = request.timeoutMs() == null ? null : validTimeout(request.timeoutMs(), 3000);
        jdbc.update("""
                INSERT INTO resource_api(data_source_id,resource_api_code,resource_api_name,http_method,
                    upstream_url,upstream_path,config_scope,request_example,response_example,request_headers,
                    execution_mode,sql_template,sql_parameters,timeout_ms,status,current_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'DRAFT',1)
                """, request.dataSourceId(), code, request.resourceApiName().trim(), request.httpMethod(),
                url, path, scope, json.write(requestExample), json.write(responseExample),
                json.write(validRequestHeaders(request.requestHeaders())), executionMode, sqlTemplate,
                json.write(sqlParameters), timeout);
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        return resourceApiSummary(id);
    }

    public ResourceApiDetail resourceApi(long id) {
        Map<String, Object> row = jdbc.queryForMap(resourceApiSelect() + " WHERE ra.id=?", id);
        Integer configured = row.get("timeout_ms") == null ? null : ((Number) row.get("timeout_ms")).intValue();
        return new ResourceApiDetail(resourceApiSummary(id),
                json.readMap(String.valueOf(row.get("request_example"))),
                json.readMap(String.valueOf(row.get("response_example"))),
                readRequestHeaders(row.get("request_headers")), configured,
                configured == null ? "DATA_SOURCE" : "RESOURCE_API",
                valueOr((String) row.get("execution_mode"), "HTTP"),
                Objects.toString(row.get("sql_template"), ""), readStringMap(row.get("sql_parameters")));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> databaseMetadata(long dataSourceId, String tableName) {
        Map<String, Object> source = jdbc.queryForMap("""
                SELECT ds.source_code,ds.protocol_type,ds.default_timeout_ms,p.provider_code,p.access_mode,p.access_config,p.runtime_url
                FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id WHERE ds.id=?
                """, dataSourceId);
        if (!"LOCAL_SQL".equalsIgnoreCase(Objects.toString(source.get("protocol_type"), ""))) {
            throw new IllegalArgumentException("该数据源不是数据库资源");
        }
        if (!"DATABASE".equalsIgnoreCase(Objects.toString(source.get("access_mode"), ""))) {
            throw new IllegalArgumentException("资源提供商未配置数据库接入方式");
        }
        String endpoint = trimTrailingSlash(Objects.toString(source.get("runtime_url"), ""));
        if (endpoint.isBlank()) throw new IllegalStateException("资源提供商尚未配置处理应用访问地址");
        int timeout = source.get("default_timeout_ms") instanceof Number number
                ? Math.max(100, Math.min(120_000, number.intValue())) : 10_000;
        String mode = tableName == null || tableName.isBlank() ? "METADATA_TABLES" : "METADATA_COLUMNS";
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("providerCode", source.get("provider_code"));
        connection.put("accessMode", source.get("access_mode"));
        connection.put("accessConfig", source.get("access_config") == null ? Map.of()
                : json.readMap(String.valueOf(source.get("access_config"))));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", "DB-METADATA-" + java.util.UUID.randomUUID().toString().replace("-", ""));
        payload.put("providerCode", source.get("provider_code"));
        payload.put("dataSourceCode", source.get("source_code"));
        payload.put("interfaceCode", "DATABASE_METADATA");
        payload.put("upstreamUrl", tableName == null ? "" : tableName);
        payload.put("upstreamHttpMethod", "POST");
        payload.put("providerConnection", connection);
        payload.put("executionMode", mode);
        payload.put("timeoutMs", timeout);
        payload.put("requestBody", Map.of());
        try {
            Map<String, Object> result = restClient(timeout + 2_000).post()
                    .uri(endpoint + "/internal/adapter/execute").body(payload).retrieve().body(Map.class);
            if (result == null || Boolean.FALSE.equals(result.get("success"))) {
                throw new IllegalStateException(result == null ? "处理应用未返回元数据" : describeUpstreamError(result));
            }
            Object response = result.get("upstreamResponse");
            if (!(response instanceof Map<?, ?> metadata)) throw new IllegalStateException("处理应用返回的元数据格式无效");
            return (Map<String, Object>) metadata;
        } catch (Exception error) {
            throw new IllegalStateException("通过资源提供商处理应用读取数据库元数据失败：" + error.getMessage(), error);
        }
    }

    @Transactional
    public ResourceApiDetail saveResourceApi(long id, SaveResourceApiRequest request) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT ds.endpoint_url,ds.protocol_type,p.access_mode FROM resource_api ra JOIN data_source ds ON ds.id=ra.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                WHERE ra.id=? FOR UPDATE
                """, id);
        String executionMode = normalizeExecutionMode(request.executionMode(), "HTTP");
        validateDatabaseMode(row, executionMode);
        String sqlTemplate = normalizeSqlTemplate(request.sqlTemplate(), executionMode);
        Map<String, String> sqlParameters = validSqlParameters(request.sqlParameters());
        String scope = normalizeConfigScope(request.configScope());
        String path = normalizePath(request.upstreamPath());
        String url = trimTrailingSlash(request.upstreamUrl());
        if (isSqlExecution(executionMode)) {
            url = "jdbc://resource-api-" + id;
            path = null;
        } else if ("INHERIT".equals(scope)) {
            if (row.get("endpoint_url") == null || String.valueOf(row.get("endpoint_url")).isBlank()) {
                throw new IllegalArgumentException("数据源未配置基础地址，请改用接口独立配置");
            }
            if (path == null || path.isBlank()) throw new IllegalArgumentException("请填写供应商接口相对路径");
            url = joinUrl(String.valueOf(row.get("endpoint_url")), path);
        } else if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("供应商接口地址不能为空");
        }
        Integer timeout = request.timeoutMs() == null ? null : validTimeout(request.timeoutMs(), 3000);
        jdbc.update("""
                UPDATE resource_api SET config_scope=?,upstream_path=?,upstream_url=?,timeout_ms=?,
                    request_example=?,response_example=?,request_headers=?,execution_mode=?,sql_template=?,sql_parameters=?,
                    status='READY',verified_at=NULL,verified_checksum=NULL,current_version=current_version+1
                WHERE id=?
                """, scope, path, url, timeout, json.write(request.requestExample()),
                json.write(request.responseExample()), json.write(validRequestHeaders(request.requestHeaders())),
                executionMode, sqlTemplate, json.write(sqlParameters), id);
        return resourceApi(id);
    }

    @Transactional
    public ResourceApiSummary updateResourceApiMetadata(long id, UpdateResourceApiMetadataRequest request) {
        String code = request.resourceApiCode().trim().toUpperCase(Locale.ROOT);
        String name = request.resourceApiName().trim();
        String method = request.httpMethod().trim().toUpperCase(Locale.ROOT);
        int updated = jdbc.update("""
                UPDATE resource_api
                SET resource_api_code=?,resource_api_name=?,http_method=?,
                    verified_at=NULL,verified_checksum=NULL,current_version=current_version+1
                WHERE id=?
                """, code, name, method, id);
        if (updated == 0) throw new IllegalArgumentException("资源 API 不存在：" + id);
        return resourceApiSummary(id);
    }

    @Transactional
    public TestResult verifyResourceApi(long id) { return verifyResourceApi(id, null); }

    public TestResult verifyResourceApi(long id, Integer maxRows) {
        jdbc.queryForObject("SELECT id FROM resource_api WHERE id=? FOR UPDATE", Long.class, id);
        ResourceApiDetail resource = resourceApi(id);
        if ("SQL".equalsIgnoreCase(resource.executionMode()) && maxRows != null
                && (maxRows < 1 || maxRows > 500)) {
            throw new IllegalArgumentException("单次查询条数必须在 1 到 500 之间");
        }
        long started = System.nanoTime();
        ResourceApiSummary summary = resource.summary();
        InterfaceSummary executionResource = new InterfaceSummary(summary.id(), summary.dataSourceId(),
                summary.resourceApiCode(), summary.resourceApiName(), summary.upstreamPath(), summary.httpMethod(),
                summary.upstreamUrl(), summary.upstreamPath(), summary.configScope(), "DIRECT", null,
                summary.effectiveTimeoutMs(), "READY", "DRAFT", summary.currentVersion(), null,
                summary.updatedAt(), summary.id(), summary.resourceApiCode(), summary.resourceApiName());
        String traceId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> response = maxRows == null
                ? invokeThroughDataApplication(executionResource, resource.requestExample(), traceId, resource.requestHeaders())
                : invokeThroughDataApplication(executionResource, resource.requestExample(), traceId, resource.requestHeaders(), maxRows);
        long duration = elapsedMs(started);
        Map<String, Object> safeResponse = response == null ? Map.of() : response;
        jdbc.update("UPDATE resource_api SET status='READY',verified_at=CURRENT_TIMESTAMP,verified_checksum=? WHERE id=?",
                upstreamChecksum(resource), id);
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("application", dataApplicationName(executionResource));
        invocation.put("applicationEndpoint", dataApplicationEndpoint(executionResource));
        invocation.put("method", summary.httpMethod());
        invocation.put("url", summary.upstreamUrl());
        invocation.put("body", resource.requestExample());
        List<TraceNode> nodes = List.of(
                new TraceNode("DATA_SOURCE", "资源提供商处理应用", "ADAPTER", "SUCCESS", "装载资源接口连接配置", 1,
                        resource.requestExample(), invocation),
                new TraceNode("VENDOR", "上游资源接口", "EXTERNAL", "SUCCESS", "收到资源提供商原始响应", duration,
                        resource.requestExample(), safeResponse));
        return new TestResult(resource.requestExample(), safeResponse, safeResponse,
                List.of(new ExecutionStep("上游资源接口验证", "通过", resource.summary().upstreamUrl(), duration)), nodes);
    }

    @Transactional
    public InterfaceSummary createPlatformService(CreatePlatformServiceRequest request) {
        // Keep the inherited request/response contract from one saved resource revision.
        jdbc.queryForObject("SELECT id FROM resource_api WHERE id=? FOR UPDATE", Long.class, request.resourceApiId());
        ResourceApiDetail resource = resourceApi(request.resourceApiId());
        if (!"READY".equals(resource.summary().status())) {
            throw new IllegalStateException("资源 API 尚未保存为可用状态，不能创建平台服务");
        }
        String servicePath = normalizePath(request.servicePath());
        validatePublishableServicePath(servicePath);
        jdbc.update("""
                INSERT INTO api_interface(resource_api_id,data_source_id,interface_code,interface_name,service_path,http_method,
                    upstream_url,upstream_path,config_scope,dispatch_mode,request_schema,response_schema,request_example,
                    upstream_request_example,upstream_response_example,standard_response_example,upstream_request_headers,timeout_ms,retry_count,
                    execution_mode,sql_template,sql_parameters,upstream_status,status,current_version)
                VALUES(?,?,?,?,?,?,?,?,?,'DIRECT',?,?,?,?,?,?,?,?,0,?,?,?,'READY','DRAFT',1)
                """, resource.summary().id(), resource.summary().dataSourceId(), request.serviceCode().trim().toUpperCase(Locale.ROOT),
                request.serviceName().trim(), servicePath, request.httpMethod().toUpperCase(Locale.ROOT), resource.summary().upstreamUrl(),
                resource.summary().upstreamPath(), resource.summary().configScope(),
                json.write(Map.of("type", "object")), json.write(Map.of("type", "object")),
                json.write(resource.requestExample()), json.write(resource.requestExample()), json.write(resource.responseExample()),
                json.write(resource.responseExample()), json.write(resource.requestHeaders()), resource.configuredTimeoutMs(),
                resource.executionMode(), resource.sqlTemplate(), json.write(resource.sqlParameters()));
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        jdbc.update("""
                INSERT INTO response_code_mapping(interface_id,vendor_code,standard_code,result_status,retryable,billable)
                VALUES(?,'0000','EXT-SUCCESS','HIT',0,1),(?,'1001','EXT-NO-DATA','MISS',0,0),(?,'9999','EXT-VENDOR-ERROR','ERROR',1,0)
                """, id, id, id);
        return interfaceSummary(id);
    }

    public List<DataSourceSummary> dataSources() {
        return jdbc.query("""
                SELECT ds.*, p.provider_name,
                       p.application_name provider_application_name,p.runtime_url provider_runtime_url,
                       p.server_port provider_server_port,COUNT(ai.id) interface_count
                FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id
                LEFT JOIN api_interface ai ON ai.data_source_id=ds.id AND ai.status <> 'DELETED'
                GROUP BY ds.id, p.provider_name,p.application_name,p.runtime_url,p.server_port ORDER BY ds.id
                """, (rs, rowNum) -> new DataSourceSummary(
                rs.getLong("id"), rs.getLong("provider_id"), rs.getString("provider_name"), rs.getString("source_code"),
                rs.getString("source_name"), rs.getString("provider_application_name"), rs.getString("provider_runtime_url"), rs.getInt("provider_server_port"), rs.getString("protocol_type"),
                rs.getString("endpoint_url"), rs.getInt("default_timeout_ms"),
                rs.getString("status"), rs.getInt("instance_count"),
                rs.getInt("config_version"), rs.getInt("interface_count")));
    }

    public List<InterfaceSummary> interfaces(Long dataSourceId) {
        String sql = interfaceSelect() + " WHERE ai.status <> 'DELETED'"
                + (dataSourceId == null ? "" : " AND ai.data_source_id=?") + " ORDER BY ai.id";
        return dataSourceId == null ? jdbc.query(sql, interfaceMapper()) : jdbc.query(sql, interfaceMapper(), dataSourceId);
    }

    public ReleaseServicePage releaseServices(String keyword, Long providerId, Long dataSourceId,
                                               String runStatus, String authorizationStatus,
                                               int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String normalizedRunStatus = valueOr(runStatus, "ALL").trim().toUpperCase(Locale.ROOT);
        String normalizedAuthorizationStatus = valueOr(authorizationStatus, "ALL").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "UNPUBLISHED", "RUNNING", "OFFLINE").contains(normalizedRunStatus)) {
            throw new IllegalArgumentException("不支持的服务运行状态");
        }
        if (!Set.of("ALL", "AUTHORIZED", "UNAUTHORIZED").contains(normalizedAuthorizationStatus)) {
            throw new IllegalArgumentException("不支持的服务授权状态");
        }
        String effectiveAuthorization = """
                EXISTS (SELECT 1 FROM api_authorization ea JOIN api_client ec ON ec.id=ea.client_id
                        WHERE ea.interface_id=ai.id AND ea.status='ACTIVE' AND ec.status='ACTIVE'
                          AND CURRENT_DATE BETWEEN ea.valid_from AND ea.valid_to)
                """;
        StringBuilder where = new StringBuilder(" WHERE ai.status<>'DELETED'");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (ai.interface_name LIKE ? OR ai.interface_code LIKE ? OR ai.service_path LIKE ? OR ds.source_name LIKE ? OR ds.source_code LIKE ? OR p.provider_name LIKE ? OR p.provider_code LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            for (int i = 0; i < 7; i++) args.add(pattern);
        }
        if (providerId != null && providerId > 0) { where.append(" AND p.id=?"); args.add(providerId); }
        if (dataSourceId != null && dataSourceId > 0) { where.append(" AND ds.id=?"); args.add(dataSourceId); }
        switch (normalizedRunStatus) {
            case "UNPUBLISHED" -> where.append(" AND ai.published_version IS NULL");
            case "RUNNING" -> where.append(" AND EXISTS (SELECT 1 FROM published_route pr WHERE pr.interface_id=ai.id)");
            case "OFFLINE" -> where.append(" AND ai.published_version IS NOT NULL AND NOT EXISTS (SELECT 1 FROM published_route pr WHERE pr.interface_id=ai.id)");
        }
        if ("AUTHORIZED".equals(normalizedAuthorizationStatus)) where.append(" AND ").append(effectiveAuthorization);
        else if ("UNAUTHORIZED".equals(normalizedAuthorizationStatus)) where.append(" AND NOT ").append(effectiveAuthorization);

        String joins = " FROM api_interface ai JOIN data_source ds ON ds.id=ai.data_source_id JOIN data_provider p ON p.id=ds.provider_id ";
        List<Object> queryArgs = new ArrayList<>(args);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, queryArgs.toArray());

        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add((safePage - 1) * safeSize);
        rowArgs.add(safeSize);
        String rowSql = """
                SELECT ai.*,ds.endpoint_url,ds.default_timeout_ms,ra.resource_api_code,ra.resource_api_name,
                       p.provider_name,ds.source_name AS data_source_name,
                       CASE WHEN ai.published_version IS NULL THEN 'UNPUBLISHED'
                            WHEN EXISTS (SELECT 1 FROM published_route pr WHERE pr.interface_id=ai.id) THEN 'RUNNING'
                            ELSE 'OFFLINE' END release_run_status,
                       (SELECT COUNT(DISTINCT ea.client_id) FROM api_authorization ea JOIN api_client ec ON ec.id=ea.client_id
                        WHERE ea.interface_id=ai.id AND ea.status='ACTIVE' AND ec.status='ACTIVE'
                          AND CURRENT_DATE BETWEEN ea.valid_from AND ea.valid_to) active_authorization_client_count,
                       COALESCE((SELECT MAX(rr.released_at) FROM release_record rr WHERE rr.interface_id=ai.id),
                                (SELECT MAX(pr.published_at) FROM published_route pr WHERE pr.interface_id=ai.id)) latest_published_at
                FROM api_interface ai JOIN data_source ds ON ds.id=ai.data_source_id
                LEFT JOIN resource_api ra ON ra.id=ai.resource_api_id
                JOIN data_provider p ON p.id=ds.provider_id
                """
                + where + " ORDER BY COALESCE((SELECT MAX(rr.released_at) FROM release_record rr WHERE rr.interface_id=ai.id),ai.updated_at) DESC,ai.id DESC LIMIT ?,?";
        List<ReleaseServiceRow> rows = jdbc.query(rowSql, (rs, rowNum) -> {
            Timestamp latest = rs.getTimestamp("latest_published_at");
            return new ReleaseServiceRow(interfaceMapper().mapRow(rs, rowNum), rs.getString("release_run_status"), rs.getString("provider_name"),
                    rs.getString("data_source_name"), rs.getLong("active_authorization_client_count"),
                    latest == null ? null : latest.toLocalDateTime());
        }, rowArgs.toArray());
        return new ReleaseServicePage(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** Soft-delete a draft while retaining related audit, billing and call data. */
    @Transactional
    public void deleteInterface(long interfaceId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE", interfaceId);
        String status = String.valueOf(row.get("status"));
        if ("DELETED".equals(status)) return;
        boolean draftDelete = "DRAFT".equals(status) && row.get("published_version") == null;
        boolean offlineArchive = "OFFLINE".equals(status) && row.get("published_version") != null;
        if (!draftDelete && !offlineArchive) {
            throw new IllegalStateException("已发布 API 必须先下线，确认下线后才能归档删除");
        }
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_approval WHERE interface_id=? AND status='PENDING'",
                Integer.class, interfaceId);
        if (pending != null && pending > 0) {
            throw new IllegalStateException("接口仍有待处理的发布审批，不能删除");
        }
        jdbc.update("UPDATE api_interface SET status='DELETED' WHERE id=?", interfaceId);
        jdbc.update("INSERT INTO interface_lifecycle_record(interface_id,action,operated_by,reason) VALUES(?, 'ARCHIVE', ?, ?)",
                interfaceId, "平台管理员", offlineArchive ? "下线后归档删除" : "删除未发布草稿");
    }

    @Transactional
    public InterfaceSummary offlineInterface(long interfaceId, LifecycleRequest request) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE", interfaceId);
        if ("DELETED".equals(row.get("status"))) throw new IllegalStateException("API 已归档删除");
        if (row.get("published_version") == null) throw new IllegalStateException("接口尚未上线，无需执行下线");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_approval WHERE interface_id=? AND status='PENDING'",
                Integer.class, interfaceId);
        if (pending != null && pending > 0) throw new IllegalStateException("接口存在待处理审批，请先处理审批单");
        jdbc.update("UPDATE api_interface SET status='OFFLINE' WHERE id=?", interfaceId);
        jdbc.update("DELETE FROM published_route WHERE interface_id=?", interfaceId);
        jdbc.update("INSERT INTO interface_lifecycle_record(interface_id,action,operated_by,reason) VALUES(?, 'OFFLINE', ?, ?)",
                interfaceId, valueOr(request == null ? null : request.operatedBy(), "平台管理员"),
                request == null ? "接口下线" : valueOr(request.reason(), "接口下线"));
        plans.deactivate(interfaceId);
        InterfaceDetail detail = interfaceDetail(interfaceId);
        runAfterCommit(() -> {
            removePlanFromDataApplication(detail);
            refreshGatewayRoutes();
        });
        return detail.summary();
    }

    /** Restore the last immutable published snapshot without publishing a newer draft. */
    @Transactional
    public InterfaceSummary onlineInterface(long interfaceId, LifecycleRequest request) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,published_version,current_version FROM api_interface WHERE id=? FOR UPDATE", interfaceId);
        if ("DELETED".equals(row.get("status"))) throw new IllegalStateException("API 已归档删除，不能重新上线");
        if (!"OFFLINE".equals(row.get("status"))) throw new IllegalStateException("只有已下线的 API 可以重新上线");
        if (row.get("published_version") == null) throw new IllegalStateException("接口没有可恢复的已发布版本");
        int publishedVersion = ((Number) row.get("published_version")).intValue();
        int currentVersion = ((Number) row.get("current_version")).intValue();
        List<Map<String, Object>> snapshots = jdbc.queryForList("""
                SELECT plan_json,checksum FROM release_plan_snapshot
                WHERE interface_id=? AND release_version=?
                """, interfaceId, publishedVersion);

        PublishedPlanRegistry.PublishedPlan restoredPlan;
        InterfaceSummary publishedSummary;
        if (!snapshots.isEmpty()) {
            try {
                Map<String, Object> snapshot = mapper.readValue(String.valueOf(snapshots.get(0).get("plan_json")), Map.class);
                publishedSummary = mapper.convertValue(snapshot.get("summary"), InterfaceSummary.class);
                int snapshotVersion = ((Number) snapshot.get("version")).intValue();
                if (publishedSummary.id() != interfaceId || snapshotVersion != publishedVersion) {
                    throw new IllegalStateException("发布快照标识与待恢复接口不一致");
                }
                restoredPlan = new PublishedPlanRegistry.PublishedPlan(interfaceId,
                        String.valueOf(snapshot.get("interfaceCode")), publishedVersion,
                        mapper.convertValue(snapshot.get("requestMappings"), new TypeReference<List<FieldMapping>>() {}),
                        mapper.convertValue(snapshot.get("responseMappings"), new TypeReference<List<FieldMapping>>() {}),
                        String.valueOf(snapshots.get(0).get("checksum")), Instant.now(), publishedSummary,
                        normalizeVendorCodePath(String.valueOf(snapshot.getOrDefault("vendorCodePath", "result.code"))),
                        mapper.convertValue(snapshot.get("responseCodes"), new TypeReference<List<ResponseCodeMapping>>() {}),
                        mapper.convertValue(snapshot.get("securityPolicy"), SecurityPolicy.class),
                        responsePolicy(snapshot.get("responsePolicy"), !((List<?>) snapshot.getOrDefault("responseMappings", List.of())).isEmpty()));
            } catch (Exception e) {
                throw new IllegalStateException("已发布版本 V" + publishedVersion + " 的执行计划快照无法解析，不能安全恢复", e);
            }
        } else {
            if (currentVersion != publishedVersion) {
                throw new IllegalStateException("已发布版本 V" + publishedVersion + " 缺少执行计划快照；当前还存在 V"
                        + currentVersion + " 草稿，不能使用草稿覆盖恢复");
            }
            InterfaceDetail detail = interfaceDetail(interfaceId);
            publishedSummary = detail.summary();
            restoredPlan = new PublishedPlanRegistry.PublishedPlan(interfaceId, publishedSummary.interfaceCode(),
                    publishedVersion,
                    detail.mappings().stream().filter(item -> "REQUEST".equals(item.direction())).toList(),
                    detail.mappings().stream().filter(item -> "RESPONSE".equals(item.direction())).toList(),
                    checksum(detail), Instant.now(), publishedSummary, detail.vendorCodePath(),
                    detail.responseCodes(), detail.securityPolicy(), detail.responsePolicy());
        }

        requireDataApplicationRoute(publishedSummary);
        validatePublishableServicePath(publishedSummary.servicePath());
        upsertPublishedRoute(publishedSummary, publishedVersion);
        String restoredStatus = currentVersion == publishedVersion ? "PUBLISHED" : "DRAFT";
        jdbc.update("UPDATE api_interface SET status=? WHERE id=?", restoredStatus, interfaceId);
        jdbc.update("INSERT INTO interface_lifecycle_record(interface_id,action,operated_by,reason) VALUES(?, 'ONLINE', ?, ?)",
                interfaceId, valueOr(request == null ? null : request.operatedBy(), "平台管理员"),
                request == null ? "恢复最近已发布版本" : valueOr(request.reason(), "恢复最近已发布版本"));
        plans.activate(restoredPlan);
        InterfaceSummary notificationSummary = publishedSummary;
        runAfterCommit(() -> {
            pushPlanToDataApplication(notificationSummary);
            refreshGatewayRoutes();
        });
        return interfaceSummary(interfaceId);
    }

    private void removePlanFromDataApplication(InterfaceDetail detail) {
        String endpoint = dataApplicationEndpoint(detail.summary());
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            restClient.delete().uri(endpoint + "/internal/adapter/plans/" + detail.summary().interfaceCode() + "/refresh").retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // The durable OFFLINE state is authoritative; a restarted application will
            // refuse to load a plan whose published endpoint is inactive.
        }
    }

    // Serialize editing, deletion and release on the same API row. OFFLINE only
    // stops production traffic; its next draft remains editable.
    private void lockEditableInterface(long interfaceId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=? FOR UPDATE", interfaceId);
        if ("DELETED".equals(row.get("status"))) {
            throw new IllegalStateException("API 已删除，请刷新资源目录");
        }
    }

    public InterfaceDetail interfaceDetail(long id) {
        Map<String, Object> row = jdbc.queryForMap(interfaceSelect() + " WHERE ai.id=?", id);
        if ("DELETED".equals(row.get("status"))) {
            throw new IllegalStateException("API 已删除，请刷新资源目录");
        }
        InterfaceSummary summary = interfaceSummary(id);
        Integer configuredTimeoutMs = row.get("timeout_ms") == null ? null : ((Number) row.get("timeout_ms")).intValue();
        int effectiveTimeoutMs = configuredTimeoutMs == null
                ? ((Number) row.get("default_timeout_ms")).intValue() : configuredTimeoutMs;
        List<FieldMapping> fieldMappings = mappings(id);
        return new InterfaceDetail(summary,
                json.readMap((String) row.get("request_schema")), json.readMap((String) row.get("response_schema")),
                json.readMap((String) row.get("request_example")), json.readMap((String) row.get("upstream_request_example")),
                json.readMap((String) row.get("upstream_response_example")),
                json.readMap((String) row.get("standard_response_example")), effectiveTimeoutMs, configuredTimeoutMs,
                configuredTimeoutMs == null ? "DATA_SOURCE" : "INTERFACE",
                ((Number) row.get("retry_count")).intValue(),
                normalizeVendorCodePath((String) row.get("vendor_code_path")), fieldMappings, responseCodes(id), securityPolicy(id),
                responsePolicy(row.get("response_policy"), fieldMappings.stream().anyMatch(m -> "RESPONSE".equals(m.direction()))),
                valueOr(row.get("execution_mode") == null ? null : String.valueOf(row.get("execution_mode")), "HTTP"),
                Objects.toString(row.get("sql_template"), ""), readStringMap(row.get("sql_parameters")));
    }

    @Transactional
    public InterfaceDetail syncResourceContract(long interfaceId) {
        lockEditableInterface(interfaceId);
        InterfaceDetail draft = interfaceDetail(interfaceId);
        Long resourceId = draft.summary().resourceApiId();
        if (resourceId == null) throw new IllegalStateException("该服务未绑定资源 API");
        jdbc.queryForObject("SELECT id FROM resource_api WHERE id=? FOR UPDATE", Long.class, resourceId);
        ResourceApiDetail resource = resourceApi(resourceId);
        if (!"READY".equals(resource.summary().status())) throw new IllegalStateException("请先保存上游资源接口");
        boolean inheritRequest = draft.mappings().stream().noneMatch(m -> "REQUEST".equals(m.direction()))
                && Objects.equals(draft.requestExample(), draft.upstreamRequestExample());
        boolean inheritResponse = draft.mappings().stream().noneMatch(m -> "RESPONSE".equals(m.direction()))
                && Objects.equals(draft.standardResponseExample(), draft.upstreamResponseExample());
        String syncSql = """
                UPDATE api_interface SET config_scope=?,upstream_path=?,upstream_url=?,timeout_ms=?,
                upstream_request_example=?,upstream_response_example=?,upstream_request_headers=?,request_example=?,standard_response_example=?,
                upstream_status='READY',current_version=COALESCE(published_version,0)+1,
                status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                """;
        List<Object> syncArgs = new ArrayList<>(Arrays.asList(resource.summary().configScope(), resource.summary().upstreamPath(), resource.summary().upstreamUrl(),
                resource.configuredTimeoutMs(), json.write(resource.requestExample()), json.write(resource.responseExample()),
                json.write(resource.requestHeaders()),
                json.write(inheritRequest ? resource.requestExample() : draft.requestExample()),
                json.write(inheritResponse ? resource.responseExample() : draft.standardResponseExample()), interfaceId));
        if ("SQL".equalsIgnoreCase(resource.executionMode())) {
            syncSql = """
                    UPDATE api_interface SET config_scope=?,upstream_path=?,upstream_url=?,timeout_ms=?,
                    upstream_request_example=?,upstream_response_example=?,upstream_request_headers=?,request_example=?,standard_response_example=?,
                    execution_mode=?,sql_template=?,sql_parameters=?,
                    upstream_status='READY',current_version=COALESCE(published_version,0)+1,
                    status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                    """;
            syncArgs = new ArrayList<>(Arrays.asList(resource.summary().configScope(), resource.summary().upstreamPath(), resource.summary().upstreamUrl(),
                    resource.configuredTimeoutMs(), json.write(resource.requestExample()), json.write(resource.responseExample()),
                    json.write(resource.requestHeaders()), json.write(inheritRequest ? resource.requestExample() : draft.requestExample()),
                    json.write(inheritResponse ? resource.responseExample() : draft.standardResponseExample()), resource.executionMode(),
                    resource.sqlTemplate(), json.write(resource.sqlParameters()), interfaceId));
        }
        jdbc.update(syncSql, syncArgs.toArray());
        invalidateDraftValidation(interfaceId);
        return interfaceDetail(interfaceId);
    }

    private void invalidateDraftValidation(long interfaceId) {
        jdbc.update("DELETE FROM interface_validation_record WHERE interface_id=? AND validation_type IN ('ORCHESTRATION','PRE_RELEASE','DRAFT_TEST')", interfaceId);
    }

    @Transactional
    public InterfaceDetail saveConnectionConfig(long interfaceId, SaveInterfaceConnectionRequest request) {
        lockEditableInterface(interfaceId);
        String configScope = normalizeConfigScope(request.configScope());
        String upstreamPath = normalizePath(request.upstreamPath());
        String upstreamUrl = trimTrailingSlash(request.upstreamUrl());
        Map<String, Object> source = jdbc.queryForMap("""
                SELECT ds.endpoint_url,ai.upstream_status,ai.request_example,ai.standard_response_example
                FROM api_interface ai JOIN data_source ds ON ds.id=ai.data_source_id
                WHERE ai.id=?
                """, interfaceId);
        if ("INHERIT".equals(configScope)) {
            if (source.get("endpoint_url") == null || String.valueOf(source.get("endpoint_url")).isBlank()) {
                throw new IllegalArgumentException("所选数据源未配置基础地址，请改用接口独立配置");
            }
            if (upstreamPath == null || upstreamPath.isBlank()) {
                throw new IllegalArgumentException("继承数据源配置时必须填写供应商接口相对路径");
            }
            upstreamUrl = joinUrl(String.valueOf(source.get("endpoint_url")), upstreamPath);
        } else if (upstreamUrl == null || upstreamUrl.isBlank()) {
            throw new IllegalArgumentException("接口独立配置时供应商接口地址不能为空");
        }
        Integer configuredTimeoutMs = request.timeoutMs() == null ? null : validTimeout(request.timeoutMs(), 3000);
        Map<String, Object> standardRequest = json.readMap((String) source.get("request_example"));
        Map<String, Object> standardResponse = json.readMap((String) source.get("standard_response_example"));
        boolean firstSave = "DRAFT".equals(source.get("upstream_status"));
        if (firstSave) standardRequest = request.upstreamRequestExample();
        if (firstSave) standardResponse = request.upstreamResponseExample();
        jdbc.update("""
                UPDATE api_interface SET config_scope=?,upstream_path=?,upstream_url=?,timeout_ms=?,
                upstream_request_example=?,upstream_response_example=?,
                request_example=?,standard_response_example=?,
                upstream_status='READY',current_version=COALESCE(published_version,0)+1,
                status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                """, configScope, upstreamPath, upstreamUrl, configuredTimeoutMs,
                json.write(request.upstreamRequestExample()), json.write(request.upstreamResponseExample()),
                json.write(standardRequest), json.write(standardResponse), interfaceId);
        invalidateDraftValidation(interfaceId);
        return interfaceDetail(interfaceId);
    }

    public SecurityPolicy securityPolicy(long interfaceId) {
        List<SecurityPolicy> policies = jdbc.query("SELECT * FROM api_security_policy WHERE interface_id=?",
                (rs, rowNum) -> new SecurityPolicy(rs.getLong("interface_id"), rs.getBoolean("enabled"),
                        rs.getString("encrypt_mode"), rs.getString("signature_mode"),
                        rs.getString("request_fields"), rs.getString("encryption_key_reference"),
                        rs.getString("signing_key_reference"), rs.getString("signature_field"),
                        rs.getString("description")), interfaceId);
        return policies.isEmpty() ? new SecurityPolicy(interfaceId, false, "NONE", "NONE", "", "", "", "signature", "") : policies.get(0);
    }

    @Transactional
    public SecurityPolicy saveSecurityPolicy(long interfaceId, SaveSecurityPolicyRequest request) {
        lockEditableInterface(interfaceId);
        validateSecurityPolicy(request.enabled(), request.encryptMode(), request.signatureMode(),
                request.requestFields(), request.encryptionKeyReference(), request.signingKeyReference(), request.signatureField());
        jdbc.update("""
                INSERT INTO api_security_policy(interface_id,enabled,encrypt_mode,signature_mode,request_fields,
                encryption_key_reference,signing_key_reference,signature_field,description)
                VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),encrypt_mode=VALUES(encrypt_mode),
                signature_mode=VALUES(signature_mode),request_fields=VALUES(request_fields),
                encryption_key_reference=VALUES(encryption_key_reference),signing_key_reference=VALUES(signing_key_reference),
                signature_field=VALUES(signature_field),description=VALUES(description)
                """, interfaceId, request.enabled(), valueOr(request.encryptMode(), "NONE"),
                valueOr(request.signatureMode(), "NONE"), request.requestFields(), request.encryptionKeyReference(),
                request.signingKeyReference(), valueOr(request.signatureField(), "signature"), request.description());
        jdbc.update("""
                UPDATE api_interface SET current_version=COALESCE(published_version,0)+1,
                status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                """, interfaceId);
        invalidateDraftValidation(interfaceId);
        return securityPolicy(interfaceId);
    }

    public List<FieldMapping> mappings(long interfaceId) {
        return jdbc.query("SELECT * FROM field_mapping WHERE interface_id=? ORDER BY mapping_direction, sort_order, id",
                (rs, rowNum) -> new FieldMapping(rs.getLong("id"), rs.getString("mapping_direction"),
                        rs.getString("source_path"), rs.getString("target_path"), rs.getString("transform_type"),
                        parseStringMap(rs.getString("transform_config")), rs.getString("default_value"),
                        rs.getBoolean("required_flag"), rs.getInt("sort_order")), interfaceId);
    }

    private Map<String, String> parseStringMap(String value) {
        try {
            return value == null ? Map.of() : mapper.readValue(value, STRING_MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ProviderSummary providerFromRow(ResultSet rs) throws SQLException {
        return new ProviderSummary(rs.getLong("id"), rs.getString("provider_code"),
                rs.getString("provider_name"), rs.getString("credit_code"), rs.getString("provider_type"),
                rs.getString("contact_name"), rs.getString("contact_phone"), rs.getString("contact_email"),
                rs.getString("service_phone"), rs.getString("contract_no"),
                rs.getDate("contract_start_date") == null ? null : rs.getDate("contract_start_date").toLocalDate(),
                rs.getDate("contract_end_date") == null ? null : rs.getDate("contract_end_date").toLocalDate(),
                rs.getString("security_level"), rs.getString("settlement_mode"),
                rs.getString("office_address"), rs.getString("description"),
                rs.getDate("access_date") == null ? null : rs.getDate("access_date").toLocalDate(),
                rs.getString("message_encoding"), rs.getBoolean("charge_required"),
                rs.getBoolean("encryption_required"), rs.getBoolean("login_required"), rs.getString("status"),
                valueOr(rs.getString("access_mode"), "HTTP"), publicProviderAccessConfig(json.readMap(rs.getString("access_config"))),
                rs.getString("application_name"), rs.getString("runtime_url"), rs.getInt("server_port"),
                rs.getInt("config_version"));
    }

    private ProviderAccount providerAccount(long id) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT a.*,p.provider_name FROM provider_account a JOIN data_provider p ON p.id=a.provider_id
                WHERE a.id=?
                """, (rs, rowNum) -> new ProviderAccount(rs.getLong("id"), rs.getLong("provider_id"),
                rs.getString("provider_name"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("mobile"), rs.getString("email"), rs.getString("role_code"),
                readStringList(rs.getString("permissions")), rs.getString("status"),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime()), id));
    }

    private List<String> readStringList(String value) {
        try { return value == null ? List.of() : mapper.readValue(value, STRING_LIST); }
        catch (Exception e) { return List.of(); }
    }

    private String writeStringList(List<String> value) {
        try { return mapper.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("权限配置无效", e); }
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public List<ResponseCodeMapping> responseCodes(long interfaceId) {
        return jdbc.query("SELECT * FROM response_code_mapping WHERE interface_id=? ORDER BY vendor_code",
                (rs, rowNum) -> new ResponseCodeMapping(rs.getLong("id"), rs.getString("vendor_code"),
                        rs.getString("standard_code"), rs.getString("result_status"),
                        rs.getBoolean("retryable"), rs.getBoolean("billable")), interfaceId);
    }

    @Transactional
    public List<ResponseCodeMapping> saveResponseCodes(long interfaceId, SaveResponseCodesRequest request) {
        lockEditableInterface(interfaceId);
        String vendorCodePath = normalizeVendorCodePath(request.vendorCodePath());
        Set<String> vendorCodes = new HashSet<>();
        for (ResponseCodeMapping code : request.responseCodes()) {
            if (!vendorCodes.add(code.vendorCode())) throw new IllegalArgumentException("供应商返回码重复：" + code.vendorCode());
            requirePlatformResponseCode(code.standardCode(), code.resultStatus());
        }
        jdbc.update("DELETE FROM response_code_mapping WHERE interface_id=?", interfaceId);
        for (ResponseCodeMapping code : request.responseCodes()) {
            jdbc.update("""
                    INSERT INTO response_code_mapping(interface_id,vendor_code,standard_code,result_status,retryable,billable)
                    VALUES(?,?,?,?,?,?)
                    """, interfaceId, code.vendorCode(), code.standardCode(), code.resultStatus(),
                    code.retryable(), code.billable());
        }
        jdbc.update("""
                UPDATE api_interface SET vendor_code_path=?,current_version=COALESCE(published_version,0)+1,
                status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                """,
                vendorCodePath, interfaceId);
        invalidateDraftValidation(interfaceId);
        return responseCodes(interfaceId);
    }

    private void requirePlatformResponseCode(String code, String resultStatus) {
        List<String> statuses = jdbc.query("""
                SELECT result_status FROM platform_response_code WHERE code=? AND enabled=1
                """, (rs, index) -> rs.getString(1), code);
        if (statuses.isEmpty()) throw new IllegalArgumentException("平台返回码不存在或已停用：" + code);
        if (!statuses.get(0).equals(resultStatus)) {
            throw new IllegalArgumentException("平台返回码与结果状态不一致：" + code);
        }
    }

    @Transactional
    public List<FieldMapping> saveMappings(long interfaceId, SaveMappingsRequest request) {
        lockEditableInterface(interfaceId);
        boolean hasResponseMappings = request.mappings().stream().anyMatch(m -> "RESPONSE".equals(m.direction()));
        Map<String, Object> responsePolicy = ResponsePolicySupport.normalize(request.responsePolicy(), hasResponseMappings);
        if ("HTTP_SUCCESS".equals(responsePolicy.get("codeMode"))) {
            requirePlatformResponseCode(String.valueOf(responsePolicy.get("successCode")), "HIT");
        }
        if ("FIELD_MAPPING".equals(responsePolicy.get("dataMode")) && !hasResponseMappings) {
            throw new IllegalArgumentException("字段映射封装模式至少需要一条响应映射");
        }
        if ("FIELD_MAPPING".equals(responsePolicy.get("dataMode"))) {
            ResponsePolicySupport.validateTargets(mapper.convertValue(request.mappings().stream()
                    .filter(m -> "RESPONSE".equals(m.direction())).toList(), new TypeReference<>() {}));
        }
        jdbc.update("DELETE FROM field_mapping WHERE interface_id=?", interfaceId);
        for (FieldMapping mapping : request.mappings()) {
            if (!"RESPONSE".equals(mapping.direction()) || "FIELD_MAPPING".equals(responsePolicy.get("dataMode"))) {
                validateMappingConfiguration(mapping);
            }
            Map<String, String> transformConfig = mapping.transformConfig() == null ? Map.of() : mapping.transformConfig();
            jdbc.update("""
                    INSERT INTO field_mapping(interface_id,mapping_direction,source_path,target_path,transform_type,
                    transform_config,default_value,required_flag,sort_order) VALUES(?,?,?,?,?,?,?,?,?)
                    """, interfaceId, mapping.direction(), mapping.sourcePath(), mapping.targetPath(),
                    mapping.transformType(), transformConfig.isEmpty() ? null : json.write(transformConfig),
                    mapping.defaultValue(), mapping.required(), mapping.sortOrder());
        }
        if (request.requestExample() != null) {
            String servicePath = normalizePath(request.servicePath());
            if (servicePath == null || servicePath.isBlank()) {
                throw new IllegalArgumentException("平台标准服务路径不能为空");
            }
            validatePublishableServicePath(servicePath);
            String httpMethod = valueOr(request.httpMethod(), "POST").trim().toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(httpMethod)) {
                throw new IllegalArgumentException("不支持的平台服务 HTTP 方法");
            }
            jdbc.update("""
                    UPDATE api_interface SET service_path=?,http_method=?,request_example=?,upstream_response_example=?,response_policy=?,
                    current_version=COALESCE(published_version,0)+1,
                    status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                    """, servicePath, httpMethod, json.write(request.requestExample()),
                    json.write(request.upstreamResponseExample()), json.write(responsePolicy),
                    interfaceId);
        } else {
            jdbc.update("""
                    UPDATE api_interface SET response_policy=?,current_version=COALESCE(published_version,0)+1,
                    status=CASE WHEN status='OFFLINE' THEN 'OFFLINE' ELSE 'DRAFT' END WHERE id=?
                    """, json.write(responsePolicy), interfaceId);
        }
        invalidateDraftValidation(interfaceId);
        return mappings(interfaceId);
    }

    public ValidationResult validate(long interfaceId) {
        InterfaceDetail detail = interfaceDetail(interfaceId);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (detail.mappings().isEmpty()) warnings.add("请求和响应均采用原报文透传");
        Set<String> targets = new HashSet<>();
        for (FieldMapping mapping : detail.mappings()) {
            if ("RESPONSE".equals(mapping.direction())
                    && !"FIELD_MAPPING".equals(detail.responsePolicy().get("dataMode"))) continue;
            String key = mapping.direction() + ":" + mapping.targetPath();
            if (!targets.add(key)) errors.add("目标字段重复：" + mapping.targetPath());
            if (mapping.sourcePath().isBlank() || mapping.targetPath().isBlank()) errors.add("字段路径不能为空");
            try { validateMappingConfiguration(mapping); }
            catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        }
        if ("FIELD_MAPPING".equals(detail.responsePolicy().get("dataMode"))) {
            try { ResponsePolicySupport.validateTargets(mapper.convertValue(detail.mappings().stream()
                    .filter(m -> "RESPONSE".equals(m.direction())).toList(), new TypeReference<>() {})); }
            catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        }
        try {
            Map<String, Object> mappedRequest = applyMappings(
                    detail.requestExample(), detail.mappings(), "REQUEST");
            Map<String, Object> upstreamRequest = applySecurityPolicy(mappedRequest, detail.securityPolicy());
            validateExampleContract(detail.upstreamRequestExample(), upstreamRequest, "", errors);
        } catch (RuntimeException error) {
            errors.add("平台请求样例执行转换失败：" + valueOr(error.getMessage(), error.getClass().getSimpleName()));
        }
        try {
            SecurityPolicy policy = detail.securityPolicy();
            validateSecurityPolicy(policy.enabled(), policy.encryptMode(), policy.signatureMode(),
                    policy.requestFields(), policy.encryptionKeyReference(), policy.signingKeyReference(), policy.signatureField());
        } catch (IllegalArgumentException e) { errors.add(e.getMessage()); }
        if ("VENDOR_CODE".equals(detail.responsePolicy().get("codeMode")) && detail.responseCodes().isEmpty()) {
            warnings.add("尚未配置供应商返回码映射");
        }
        if ("VENDOR_CODE".equals(detail.responsePolicy().get("codeMode"))
                && readPath(detail.upstreamResponseExample(), detail.vendorCodePath()) == null) {
            warnings.add("供应商响应样例中未找到返回码字段：" + detail.vendorCodePath() + "，请确认路径或更新样例报文");
        }
        if (detail.retryCount() > 0) warnings.add("已启用重试，请确认供应商接口具备幂等性");
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * The current resource contract is example-driven: every field present in
     * the supplier request example is required and its JSON type is enforced.
     */
    private void validateExampleContract(Object expected, Object actual, String path, List<String> errors) {
        String label = path.isBlank() ? "$" : path;
        if (expected instanceof Map<?, ?> expectedMap) {
            if (!(actual instanceof Map<?, ?> actualMap)) {
                errors.add("转换后的上游请求 " + label + " 应为对象");
                return;
            }
            expectedMap.forEach((key, expectedChild) -> {
                String name = String.valueOf(key);
                String childPath = path.isBlank() ? name : path + "." + name;
                if (!actualMap.containsKey(name) || actualMap.get(name) == null
                        || String.valueOf(actualMap.get(name)).isBlank()) {
                    errors.add("转换后的上游请求缺少供应商必填字段：" + childPath);
                } else {
                    validateExampleContract(expectedChild, actualMap.get(name), childPath, errors);
                }
            });
            return;
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?> actualList)) {
                errors.add("转换后的上游请求 " + label + " 应为数组");
            } else if (!expectedList.isEmpty() && !actualList.isEmpty()) {
                validateExampleContract(expectedList.get(0), actualList.get(0), label + "[0]", errors);
            }
            return;
        }
        if (expected instanceof String && !(actual instanceof String)) {
            errors.add("转换后的上游请求字段类型不匹配：" + label + " 应为字符串");
        } else if (expected instanceof Number && !(actual instanceof Number)) {
            errors.add("转换后的上游请求字段类型不匹配：" + label + " 应为数字");
        } else if (expected instanceof Boolean && !(actual instanceof Boolean)) {
            errors.add("转换后的上游请求字段类型不匹配：" + label + " 应为布尔值");
        }
    }

    private void validateMappingConfiguration(FieldMapping mapping) {
        String type = valueOr(mapping.transformType(), "DIRECT").toUpperCase(Locale.ROOT);
        if ("RESPONSE".equals(mapping.direction())) ResponsePolicySupport.targetPath(mapping.targetPath());
        if ("WHOLE_BODY".equals(type)) {
            if (!"RESPONSE".equals(mapping.direction()) || !"$".equals(mapping.sourcePath())) {
                throw new IllegalArgumentException("完整报文规则仅用于响应映射，来源必须为 $");
            }
            ResponsePolicySupport.targetPath(mapping.targetPath());
            return;
        }
        Map<String, String> config = mapping.transformConfig() == null ? Map.of() : mapping.transformConfig();
        if ("CONSTANT".equals(type) && valueOr(config.get("value"), "").isBlank()) {
            throw new IllegalArgumentException("固定值不能为空：" + mapping.targetPath());
        }
        if ("ENUM".equals(type) && config.isEmpty()) {
            throw new IllegalArgumentException("字段 " + mapping.sourcePath() + " 至少需要配置一条枚举转换规则");
        }
        if ("ENCRYPT".equals(type)) {
            validateAlgorithmConfiguration(mapping.sourcePath(), "ENCRYPT", config);
        }
        if ("SIGN".equals(type)) {
            validateAlgorithmConfiguration(mapping.sourcePath(), "SIGN", config);
        }
    }

    private void validateAlgorithmConfiguration(String sourcePath, String kind, Map<String, String> config) {
        SecurityAlgorithm algorithm = SecurityAlgorithmRegistry.require(config.get("mode"), kind);
        for (SecurityAlgorithm.Parameter parameter : algorithm.descriptor().parameters()) {
            String value = config.get(parameter.name());
            if (parameter.required() && (value == null || value.isBlank())) {
                throw new IllegalArgumentException("字段 " + sourcePath + " 缺少参数：" + parameter.label());
            }
            if ("SECRET_REFERENCE".equals(parameter.type()) && value != null && !value.isBlank()) {
                validateSecretReference(value, "字段 " + sourcePath);
            }
        }
    }

    private void validateSecurityPolicy(boolean enabled, String encryptMode, String signatureMode,
                                        String requestFields, String encryptionKeyReference,
                                        String signingKeyReference, String signatureField) {
        if (!enabled) return;
        String encryption = valueOr(encryptMode, "NONE");
        String signature = valueOr(signatureMode, "NONE");
        if (!"NONE".equalsIgnoreCase(encryption)) SecurityAlgorithmRegistry.require(encryption, "ENCRYPT");
        if (!"NONE".equalsIgnoreCase(signature)) SecurityAlgorithmRegistry.require(signature, "SIGN");
        if ("NONE".equalsIgnoreCase(encryption) && "NONE".equalsIgnoreCase(signature)) {
            throw new IllegalArgumentException("启用安全策略后至少选择一种加密或签名算法");
        }
        if (!"NONE".equalsIgnoreCase(encryption) && valueOr(requestFields, "").isBlank()) {
            throw new IllegalArgumentException("启用加密后必须填写请求字段路径");
        }
        if (!"NONE".equalsIgnoreCase(encryption)) validateSecretReference(encryptionKeyReference, "接口加密策略");
        if (!"NONE".equalsIgnoreCase(signature)) {
            validateSecretReference(signingKeyReference, "接口签名策略");
            if (valueOr(signatureField, "").isBlank()) throw new IllegalArgumentException("必须配置签名输出字段");
        }
    }

    private void validateSecretReference(String reference, String label) {
        if (reference == null || !(reference.startsWith("config://") || reference.startsWith("env://")
                || reference.startsWith("sys://"))) {
            throw new IllegalArgumentException(label + " 必须使用 config://、env:// 或 sys:// 密钥引用，禁止填写明文密钥");
        }
    }

    public TestResult test(long interfaceId, TestRequest request) {
        return executeTest(interfaceId, request, diagnosticClientId());
    }

    /** Execute the saved draft through the internal full-chain test route. */
    public TestResult testDraftChain(String interfaceCode, Map<String, Object> request, String traceId) {
        Map<String, Object> route = resolveInterfaceRoute(interfaceCode, true);
        Object rawId = route.get("id");
        if (!(rawId instanceof Number id)) {
            throw new IllegalStateException("测试路由缺少接口标识：" + interfaceCode);
        }
        return executeTest(id.longValue(),
                new TestRequest(request == null ? Map.of() : request, null, true, null, "E2E"),
                diagnosticClientId(), traceId);
    }

    private Long diagnosticClientId() {
        List<Long> clientIds = jdbc.queryForList(
                "SELECT id FROM api_client WHERE status='ACTIVE' ORDER BY id LIMIT 1", Long.class);
        return clientIds.isEmpty() ? null : clientIds.get(0);
    }

    private TestResult executeTest(long interfaceId, TestRequest request, Long clientId) {
        return executeTest(interfaceId, request, clientId, null);
    }

    private TestResult executeTest(long interfaceId, TestRequest request, Long clientId, String suppliedTraceId) {
        long started = System.nanoTime();
        List<TraceNode> traceNodes = new ArrayList<>();
        String traceId = suppliedTraceId == null || suppliedTraceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : suppliedTraceId;
        try {
            return executeTestInternal(interfaceId, request, clientId, started, traceId, traceNodes, null);
        } catch (TrackedCallException e) {
            throw e;
        } catch (RuntimeException e) {
            try {
                persistFailedTrace(interfaceId, clientId, traceId, request.request(), traceNodes, started, e);
            } catch (RuntimeException traceError) {
                e.addSuppressed(traceError);
            }
            throw new TrackedCallException(traceId, e.getMessage(), e);
        }
    }

    /** Production calls use the atomically activated plan; draft DB changes never enter this path. */
    private TestResult executePublishedTest(long interfaceId, TestRequest request, Long clientId) {
        long started = System.nanoTime();
        List<TraceNode> traceNodes = new ArrayList<>();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        try {
            PublishedPlanRegistry.PublishedPlan plan = plans.get(interfaceId);
            if (plan == null) {
                InterfaceDetail detail = interfaceDetail(interfaceId);
                plan = new PublishedPlanRegistry.PublishedPlan(interfaceId, detail.summary().interfaceCode(),
                        detail.summary().publishedVersion() == null ? detail.summary().currentVersion() : detail.summary().publishedVersion(),
                        detail.mappings().stream().filter(m -> "REQUEST".equals(m.direction())).toList(),
                        detail.mappings().stream().filter(m -> "RESPONSE".equals(m.direction())).toList(),
                        checksum(detail), Instant.now(), detail.summary(), detail.vendorCodePath(), detail.responseCodes(), detail.securityPolicy(), detail.responsePolicy());
                plans.activate(plan);
            }
            return executeTestInternal(interfaceId, request, clientId, started, traceId, traceNodes, plan);
        } catch (TrackedCallException e) {
            throw e;
        } catch (RuntimeException e) {
            try {
                persistFailedTrace(interfaceId, clientId, traceId, request.request(), traceNodes, started, e);
            } catch (RuntimeException traceError) {
                e.addSuppressed(traceError);
            }
            throw new TrackedCallException(traceId, e.getMessage(), e);
        }
    }

    private TestResult executeTestInternal(long interfaceId, TestRequest request, Long clientId,
                                           long started, String traceId, List<TraceNode> traceNodes,
                                           PublishedPlanRegistry.PublishedPlan publishedPlan) {
        InterfaceDetail detail = interfaceDetail(interfaceId);
        List<ExecutionStep> steps = new ArrayList<>();
        long upstreamDurationMs = 0;
        if (publishedPlan == null && request.mappings() != null && !request.mappings().isEmpty()) {
            Set<Long> currentMappingIds = detail.mappings().stream()
                    .map(FieldMapping::id).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            boolean containsForeignMapping = request.mappings().stream()
                    .map(FieldMapping::id).filter(Objects::nonNull).anyMatch(id -> !currentMappingIds.contains(id));
            if (containsForeignMapping) {
                throw new IllegalArgumentException("联调映射不属于当前接口，请刷新接口配置后重试");
            }
        }
        List<FieldMapping> effectiveMappings = publishedPlan != null
                ? new ArrayList<>() {{ addAll(publishedPlan.requestMappings()); addAll(publishedPlan.responseMappings()); }}
                : request.mappings() == null || request.mappings().isEmpty() ? detail.mappings() : request.mappings();
        InterfaceSummary executionSummary = publishedPlan == null ? detail.summary() : publishedPlan.summary();
        String testMode = request.testMode() == null || request.testMode().isBlank()
                ? (request.callUpstream() ? "E2E" : "PLATFORM") : request.testMode();
        if (!Set.of("UPSTREAM", "PLATFORM", "E2E").contains(testMode)) {
            throw new IllegalArgumentException("不支持的验证模式");
        }
        if ("UPSTREAM".equals(testMode)) {
            TestResult result = testUpstreamConnection(detail, request.request());
            if (Objects.equals(request.request(), detail.upstreamRequestExample())) {
                saveValidationRecord(interfaceId, "UPSTREAM", upstreamChecksum(detail));
            }
            return result;
        }
        boolean preRelease = publishedPlan == null && "PLATFORM".equals(testMode);
        if (preRelease) {
            invalidateDraftValidation(interfaceId);
            if (!Objects.equals(request.request(), detail.requestExample())
                    || (request.mappings() != null && !Objects.equals(request.mappings(), detail.mappings()))) {
                throw new IllegalStateException("请先保存标准请求与映射配置，再执行草稿链路测试");
            }
            ValidationResult validation = validate(interfaceId);
            if (!validation.valid()) throw new IllegalStateException(String.join("；", validation.errors()));
            requireDataApplicationRoute(detail.summary());
        }

        Map<String, Object> downstreamEnvelope = new LinkedHashMap<>();
        downstreamEnvelope.put("systemCode", "DEMO_DOWNSTREAM_SYSTEM");
        downstreamEnvelope.put("protocol", "HTTP/JSON");
        downstreamEnvelope.put("requestBody", request.request());
        Map<String, Object> gatewayRequest = new LinkedHashMap<>();
        gatewayRequest.put("method", executionSummary.httpMethod());
        gatewayRequest.put("path", executionSummary.servicePath());
        gatewayRequest.put("headers", Map.of("X-App-Id", "DEMO_DOWNSTREAM_SYSTEM", "X-Trace-Id", traceId));
        gatewayRequest.put("body", request.request());
        traceNodes.add(new TraceNode("DOWNSTREAM", "下游调用系统", "CLIENT", "SUCCESS",
                "模拟外围业务系统按平台标准协议发起请求", 1, downstreamEnvelope, gatewayRequest));

        Map<String, Object> gatewayOutput = new LinkedHashMap<>();
        gatewayOutput.put("traceId", traceId);
        gatewayOutput.put("interfaceCode", executionSummary.interfaceCode());
        gatewayOutput.put("route", "data-source-" + executionSummary.dataSourceId());
        gatewayOutput.put("body", request.request());
        boolean draftFullChain = publishedPlan == null && "E2E".equals(testMode);
        traceNodes.add(new TraceNode("GATEWAY", "统一 Gateway", "PLATFORM",
                publishedPlan == null && !draftFullChain ? "SKIPPED" : "SUCCESS",
                draftFullChain ? "内部测试通道已路由当前草稿；不检查发布状态和业务授权"
                        : publishedPlan == null ? "草稿链路测试不经过 ESB/Gateway"
                        : "正式调用入口已完成授权与配额校验", 0, gatewayRequest, gatewayOutput));

        Map<String, Object> schemaOutput = new LinkedHashMap<>();
        schemaOutput.put("valid", true);
        schemaOutput.put("validatedFields", request.request().keySet());
        schemaOutput.put("body", request.request());
        traceNodes.add(new TraceNode("SCHEMA", "Schema 校验", "PLATFORM", "SUCCESS",
                "已装载标准请求；转换后的字段按保存的上游样例契约校验", 1, request.request(), schemaOutput));
        steps.add(new ExecutionStep("请求契约校验", "通过", "已装载保存的标准请求", 1));
        long mappingStarted = System.nanoTime();
        Map<String, Object> upstreamRequest = applyMappings(request.request(), effectiveMappings, "REQUEST");
        if ("E2E".equals(testMode)) {
            upstreamRequest = XmlRequestTypeSupport.coerce(upstreamRequest, detail.upstreamRequestExample());
        }
        long mappingDurationMs = elapsedMs(mappingStarted);
        steps.add(new ExecutionStep("请求字段映射", "通过",
                "按当前页面映射快照生成上游请求字段 " + upstreamRequest.size() + " 个",
                mappingDurationMs));
        traceNodes.add(new TraceNode("REQUEST_MAPPING", "请求标准化", "PLATFORM", "SUCCESS",
                "按当前页面映射快照转换为供应商字段", mappingDurationMs,
                Map.of("requestBody", request.request(), "mappingCount", effectiveMappings.stream()
                        .filter(mapping -> "REQUEST".equals(mapping.direction())).count()), upstreamRequest));
        steps.add(new ExecutionStep("安全策略", "通过", "按配置执行加密与签名", 1));
        SecurityPolicy securityPolicy = publishedPlan == null ? detail.securityPolicy() : publishedPlan.securityPolicy();
        Map<String, Object> securedBody = applySecurityPolicy(upstreamRequest, securityPolicy);
        if (publishedPlan == null) {
            List<String> contractErrors = new ArrayList<>();
            validateExampleContract(detail.upstreamRequestExample(), securedBody, "", contractErrors);
            if (!contractErrors.isEmpty()) throw new IllegalStateException(String.join("；", contractErrors));
        }
        Map<String, Object> securedRequest = new LinkedHashMap<>();
        securedRequest.put("encryptMode", securityPolicy.enabled() ? securityPolicy.encryptMode() : "NONE");
        securedRequest.put("signatureMode", securityPolicy.enabled() ? securityPolicy.signatureMode() : "NONE");
        securedRequest.put("body", securedBody);
        traceNodes.add(new TraceNode("SECURITY", "安全处理", "PLATFORM", "SUCCESS",
                securityPolicy.enabled() ? "按当前执行计划执行加密和签名策略" : "未启用加密和签名策略",
                1, upstreamRequest, securedRequest));

        Map<String, Object> dataSourceOutput = new LinkedHashMap<>();
        dataSourceOutput.put("application", dataApplicationName(executionSummary));
        dataSourceOutput.put("applicationEndpoint", dataApplicationEndpoint(executionSummary));
        dataSourceOutput.put("endpoint", executionSummary.upstreamUrl());
        dataSourceOutput.put("timeoutMs", detail.timeoutMs());
        dataSourceOutput.put("requestBody", securedBody);
        traceNodes.add(new TraceNode("DATA_SOURCE", "资源提供商处理应用", "ADAPTER", "SUCCESS",
                "资源提供商独立处理应用执行协议适配", 1, securedRequest, dataSourceOutput));
        long callStarted = System.nanoTime();
        Map<String, Object> upstreamResponse = invokeThroughDataApplication(executionSummary, securedBody, traceId);
        upstreamDurationMs = Math.max(1, (System.nanoTime() - callStarted) / 1_000_000);
        steps.add(new ExecutionStep("上游 HTTP 调用", "通过", executionSummary.upstreamUrl(), upstreamDurationMs));
        Map<String, Object> vendorInput = new LinkedHashMap<>();
        vendorInput.put("method", "POST");
        vendorInput.put("url", executionSummary.upstreamUrl());
        vendorInput.put("body", securedBody);
        traceNodes.add(new TraceNode("VENDOR", "上游供应商", "EXTERNAL", "SUCCESS",
                "真实 HTTP 请求已完成", upstreamDurationMs, vendorInput, upstreamResponse));
        long standardizeStarted = System.nanoTime();
        String vendorCodePath = publishedPlan == null ? detail.vendorCodePath() : publishedPlan.vendorCodePath();
        Object vendorCodeValue = readPath(upstreamResponse, vendorCodePath);
        String vendorCode = vendorCodeValue == null ? "" : String.valueOf(vendorCodeValue);
        List<ResponseCodeMapping> executionCodes = publishedPlan == null ? detail.responseCodes() : publishedPlan.responseCodes();
        Map<String, Object> responsePolicy = publishedPlan == null ? detail.responsePolicy() : publishedPlan.responsePolicy();
        boolean httpSuccessMode = "HTTP_SUCCESS".equals(responsePolicy.get("codeMode"));
        ResponseCodeMapping matchedCode = executionCodes.stream()
                .filter(c -> c.vendorCode().equals(vendorCode)).findFirst().orElse(null);
        ResponseCodeMapping code = httpSuccessMode
                ? new ResponseCodeMapping(null, "", String.valueOf(responsePolicy.get("successCode")), "HIT", false, false)
                : matchedCode == null
                    ? new ResponseCodeMapping(null, vendorCode, "EXT-VENDOR-ERROR", "ERROR", false, false)
                    : matchedCode;
        String responseMappingDetail = "完成供应商响应封装";
        if (!httpSuccessMode && vendorCode.isBlank()) {
            responseMappingDetail = "未从上游响应读取到业务返回码（字段路径：" + vendorCodePath
                    + "）。请核对返回码字段路径；无业务返回码的接口可选择 HTTP 成功判定。";
        } else if (!httpSuccessMode && matchedCode == null) {
            responseMappingDetail = "上游业务返回码 " + vendorCode + " 未配置结果码映射（字段路径："
                    + vendorCodePath + "）。请补充该码值的结果映射。";
        } else if (!httpSuccessMode && "ERROR".equals(code.resultStatus())) {
            responseMappingDetail = "上游业务返回码 " + vendorCode + " 被映射为异常状态（平台码："
                    + code.standardCode() + "）。请核对供应商响应与结果码映射。";
        }
        // MISS/ERROR responses carry no business data; required HIT mappings must not turn them into platform errors.
        Map<String, Object> responseFields = ResponsePolicySupport.fieldsForOutcome(upstreamResponse,
                    mapper.convertValue(effectiveMappings.stream().filter(m -> "RESPONSE".equals(m.direction())).toList(), new TypeReference<>() {}),
                    responsePolicy, code.resultStatus(), secretResolver);
        code = new ResponseCodeMapping(code.id(), code.vendorCode(),
                String.valueOf(responseFields.getOrDefault("code", code.standardCode())),
                code.resultStatus(), code.retryable(), code.billable());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code.standardCode());
        response.put("message", "HIT".equals(code.resultStatus()) ? "查询成功"
                : "MISS".equals(code.resultStatus()) ? "未查询到符合条件的数据" : "供应商返回异常");
        response.put("resultStatus", code.resultStatus());
        response.put("traceId", traceId);
        response.putAll(responseFields);
        response.putIfAbsent("data", Map.of());
        steps.add(new ExecutionStep("响应解析", "通过", "供应商返回码 " + vendorCode, 1));
        long standardizeDurationMs = elapsedMs(standardizeStarted);
        steps.add(new ExecutionStep("字段标准化", "通过", "生成统一标准响应", standardizeDurationMs));
        traceNodes.add(new TraceNode("RESPONSE_MAPPING", "响应标准化", "PLATFORM", "ERROR".equals(code.resultStatus()) ? "ERROR" : "SUCCESS",
                responseMappingDetail, standardizeDurationMs,
                Map.of("vendorResponse", upstreamResponse, "responseMappingCount", effectiveMappings.stream()
                        .filter(mapping -> "RESPONSE".equals(mapping.direction())).count()), response));
        long totalDurationMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        long billingStarted = System.nanoTime();
        boolean billed = recordUsage(interfaceId, clientId, traceId, code, totalDurationMs, upstreamDurationMs,
                Math.max(1, totalDurationMs - upstreamDurationMs));
        long billingDurationMs = elapsedMs(billingStarted);
        String billingDetail = billed ? "已记录调用量，纳入线下对账" : "已记录调用量，本次不纳入线下对账";
        steps.add(new ExecutionStep("调用计量", "通过", billingDetail, billingDurationMs));
        traceNodes.add(new TraceNode("BILLING", "调用计量", "PLATFORM", "SUCCESS", billingDetail,
                billingDurationMs,
                Map.of("traceId", traceId, "resultStatus", code.resultStatus(), "durationMs", totalDurationMs),
                Map.of("recorded", true, "includedInReconciliation", billed, "standardCode", code.standardCode())));
        recordTraceNodes(traceId, traceNodes);
        if (preRelease && Set.of("HIT", "MISS").contains(code.resultStatus())) {
            if (!Objects.equals(checksum(detail), checksum(interfaceDetail(interfaceId)))) {
                throw new IllegalStateException("测试期间草稿配置已变更，请重新执行草稿链路测试");
            }
            saveValidationRecord(interfaceId, "DRAFT_TEST", checksum(detail));
        }
        return new TestResult(upstreamRequest, upstreamResponse, response, steps, traceNodes);
    }

    private void persistFailedTrace(long interfaceId, Long clientId, String traceId,
                                    Map<String, Object> request, List<TraceNode> completed,
                                    long started, RuntimeException error) {
        List<String[]> pipeline = List.of(
                new String[]{"DOWNSTREAM", "下游调用系统", "CLIENT"},
                new String[]{"GATEWAY", "统一 Gateway", "PLATFORM"},
                new String[]{"SCHEMA", "Schema 校验", "PLATFORM"},
                new String[]{"REQUEST_MAPPING", "请求标准化", "PLATFORM"},
                new String[]{"SECURITY", "安全处理", "PLATFORM"},
                new String[]{"DATA_SOURCE", "资源提供商处理应用", "ADAPTER"},
                new String[]{"VENDOR", "上游供应商", "EXTERNAL"},
                new String[]{"RESPONSE_MAPPING", "响应标准化", "PLATFORM"},
                new String[]{"BILLING", "调用计量", "PLATFORM"});
        Set<String> completedKeys = completed.stream().map(TraceNode::key).collect(java.util.stream.Collectors.toSet());
        int failedIndex = failedNodeIndex(completedKeys);
        String[] failed = pipeline.get(failedIndex);
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("traceId", traceId);
        failure.put("errorType", error.getClass().getSimpleName());
        failure.put("message", valueOr(error.getMessage(), "调用执行失败"));
        completed.removeIf(node -> failed[0].equals(node.key()));
        completed.add(new TraceNode(failed[0], failed[1], failed[2], "ERROR",
                "调用在该节点失败：" + valueOr(error.getMessage(), "未知异常"),
                Math.max(1, elapsedMs(started)), request, failure));
        for (int index = failedIndex + 1; index < pipeline.size(); index++) {
            String[] skipped = pipeline.get(index);
            completed.add(new TraceNode(skipped[0], skipped[1], skipped[2], "SKIPPED",
                    "因上游节点失败，本节点未执行", 0, Map.of("traceId", traceId), Map.of()));
        }
        long totalDuration = elapsedMs(started);
        long upstreamDuration = completed.stream().filter(node -> "VENDOR".equals(node.key()))
                .mapToLong(TraceNode::durationMs).max().orElse(0);
        jdbc.update("""
                INSERT INTO usage_record(trace_id,client_id,interface_id,result_status,standard_code,billable,
                duration_ms,upstream_duration_ms,gateway_duration_ms,request_status,node_name)
                VALUES(?,?,?,'ERROR','EXT-PLATFORM-ERROR',0,?,?,?,'FAILED',?)
                ON DUPLICATE KEY UPDATE result_status='ERROR',standard_code='EXT-PLATFORM-ERROR',
                request_status='FAILED',duration_ms=VALUES(duration_ms),node_name=VALUES(node_name)
                """, traceId, clientId, interfaceId, totalDuration, upstreamDuration,
                Math.max(1, totalDuration - upstreamDuration), failed[1]);
        recordTraceNodes(traceId, completed);
    }

    private int failedNodeIndex(Set<String> completedKeys) {
        if (!completedKeys.contains("DOWNSTREAM")) return 0;
        if (!completedKeys.contains("GATEWAY")) return 1;
        if (!completedKeys.contains("SCHEMA")) return 2;
        if (!completedKeys.contains("REQUEST_MAPPING")) return 3;
        if (!completedKeys.contains("SECURITY")) return 4;
        if (!completedKeys.contains("DATA_SOURCE")) return 5;
        if (!completedKeys.contains("VENDOR") && !completedKeys.contains("MOCK_RESPONSE")) return 6;
        if (!completedKeys.contains("RESPONSE_MAPPING")) return 7;
        return 8;
    }

    public static final class TrackedCallException extends RuntimeException {
        private final String traceId;
        public TrackedCallException(String traceId, String message, Throwable cause) {
            super(message, cause);
            this.traceId = traceId;
        }
        public String traceId() { return traceId; }
    }

    public Map<String, Object> invokeAuthorized(String servicePath, String clientCode,
                                                Map<String, Object> request) {
        Map<String, Object> route = jdbc.queryForMap("""
                SELECT pr.interface_id id,i.status,i.published_version
                FROM published_route pr JOIN api_interface i ON i.id=pr.interface_id
                WHERE pr.service_path=? AND pr.http_method='POST'
                """, servicePath);
        long interfaceId = ((Number) route.get("id")).longValue();
        if (route.get("published_version") == null
                || Set.of("OFFLINE", "DELETED").contains(String.valueOf(route.get("status")))) {
            throw new IllegalStateException("接口尚未发布或已下线");
        }
        Map<String, Object> client = jdbc.queryForMap(
                "SELECT id,status FROM api_client WHERE client_code=?", clientCode);
        if (!"ACTIVE".equals(client.get("status"))) throw new IllegalStateException("调用系统已停用");
        long clientId = ((Number) client.get("id")).longValue();
        List<Map<String, Object>> grants = jdbc.queryForList("""
                SELECT daily_quota FROM api_authorization WHERE client_id=? AND interface_id=?
                AND status='ACTIVE' AND CURDATE() BETWEEN valid_from AND valid_to
                """, clientId, interfaceId);
        if (grants.isEmpty()) throw new IllegalStateException("当前调用系统未获得该 API 的有效授权");
        long todayCalls = Optional.ofNullable(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_record WHERE client_id=? AND interface_id=? AND called_at>=CURDATE()
                """, Long.class, clientId, interfaceId)).orElse(0L);
        long quota = ((Number) grants.get(0).get("daily_quota")).longValue();
        if (todayCalls >= quota) throw new IllegalStateException("当前 API 日调用配额已耗尽");
        TestResult result = executePublishedTest(interfaceId,
                new TestRequest(request, null, true, null, "E2E"), clientId);
        return result.standardResponse();
    }

    /** Resolve the ESB apiCode against the durable Admin route registry. */
    public Map<String, Object> resolveInterfaceRoute(String interfaceCode, boolean includeDraft) {
        if (interfaceCode == null || interfaceCode.isBlank()) {
            throw new IllegalArgumentException("ESB 报文缺少 apiCode");
        }
        List<Map<String, Object>> rows = includeDraft ? jdbc.queryForList("""
                SELECT i.id,i.interface_code,i.interface_name,i.service_path,i.http_method,
                       i.status,i.published_version,i.current_version,ds.source_code,p.provider_code,
                       p.application_name,p.runtime_url
                FROM api_interface i JOIN data_source ds ON ds.id=i.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                WHERE i.interface_code=? AND i.status<>'DELETED'
                ORDER BY CASE WHEN i.status='PUBLISHED' THEN 0 ELSE 1 END, i.updated_at DESC,i.id DESC LIMIT 1
                """, interfaceCode.trim()) : jdbc.queryForList("""
                SELECT i.id,pr.interface_code,i.interface_name,pr.service_path,pr.http_method,
                       i.status,i.published_version,i.current_version,ds.source_code,p.provider_code,
                       p.application_name,p.runtime_url
                FROM published_route pr JOIN api_interface i ON i.id=pr.interface_id
                JOIN data_source ds ON ds.id=pr.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                WHERE pr.interface_code=? LIMIT 1
                """, interfaceCode.trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到可用的接口 apiCode：" + interfaceCode);
        Map<String, Object> route = new LinkedHashMap<>(rows.get(0));
        route.put("resolvedAt", Instant.now().toString());
        route.put("routeSource", "MYSQL_ADMIN_REGISTRY");
        return route;
    }

    /** Complete route snapshot consumed atomically by every Gateway instance. */
    public List<Map<String, Object>> publishedRoutes() {
        return jdbc.queryForList("""
                SELECT pr.interface_id,pr.interface_code,pr.service_path,pr.http_method,
                       pr.route_version,ds.id data_source_id,ds.source_code,p.provider_code,
                       p.application_name,p.runtime_url
                FROM published_route pr JOIN data_source ds ON ds.id=pr.data_source_id
                JOIN data_provider p ON p.id=ds.provider_id
                ORDER BY pr.interface_id
                """);
    }

    public String recordGatewayRejection(String servicePath, String clientCode,
                                         Map<String, Object> request, String message) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        List<Long> interfaceIds = jdbc.queryForList(
                "SELECT id FROM api_interface WHERE service_path=?", Long.class, servicePath);
        if (interfaceIds.isEmpty()) return traceId;
        List<Long> clientIds = clientCode == null ? List.of() : jdbc.queryForList(
                "SELECT id FROM api_client WHERE client_code=?", Long.class, clientCode);
        Long clientId = clientIds.isEmpty() ? null : clientIds.get(0);
        jdbc.update("""
                INSERT INTO usage_record(trace_id,client_id,interface_id,result_status,standard_code,billable,
                duration_ms,upstream_duration_ms,gateway_duration_ms,request_status,node_name)
                VALUES(?,?,?,'ERROR','EDSP-AUTH-403',0,1,0,1,'FAILED','统一 Gateway')
                """, traceId, clientId, interfaceIds.get(0));
        List<TraceNode> nodes = new ArrayList<>();
        nodes.add(new TraceNode("DOWNSTREAM", "下游调用系统", "CLIENT", "SUCCESS",
                "请求已到达平台", 1, request, Map.of("servicePath", servicePath)));
        nodes.add(new TraceNode("GATEWAY", "统一 Gateway", "PLATFORM", "ERROR",
                message, 1, Map.of("clientCode", valueOr(clientCode, "UNKNOWN"), "request", request),
                Map.of("code", "EDSP-AUTH-403", "message", message, "traceId", traceId)));
        for (String[] node : List.of(
                new String[]{"SCHEMA", "Schema 校验", "PLATFORM"},
                new String[]{"REQUEST_MAPPING", "请求标准化", "PLATFORM"},
                new String[]{"SECURITY", "安全处理", "PLATFORM"},
                new String[]{"DATA_SOURCE", "资源提供商处理应用", "ADAPTER"},
                new String[]{"VENDOR", "上游供应商", "EXTERNAL"},
                new String[]{"RESPONSE_MAPPING", "响应标准化", "PLATFORM"},
                new String[]{"BILLING", "调用计量", "PLATFORM"})) {
            nodes.add(new TraceNode(node[0], node[1], node[2], "SKIPPED",
                    "因 Gateway 校验失败，本节点未执行", 0, Map.of("traceId", traceId), Map.of()));
        }
        recordTraceNodes(traceId, nodes);
        return traceId;
    }

    private TestResult testUpstreamConnection(InterfaceDetail detail, Map<String, Object> upstreamRequest) {
        List<ExecutionStep> steps = new ArrayList<>();
        List<TraceNode> traceNodes = new ArrayList<>();
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("application", dataApplicationName(detail.summary()));
        invocation.put("applicationEndpoint", dataApplicationEndpoint(detail.summary()));
        invocation.put("method", upstreamHttpMethod(detail.summary()));
        invocation.put("url", detail.summary().upstreamUrl());
        invocation.put("timeoutMs", detail.timeoutMs());
        invocation.put("body", upstreamRequest);
        traceNodes.add(new TraceNode("DATA_SOURCE", "资源提供商处理应用", "ADAPTER", "SUCCESS",
                "装载供应商连接、认证和超时配置", 1, upstreamRequest, invocation));
        long callStarted = System.nanoTime();
        Map<String, Object> upstreamResponse = invokeThroughDataApplication(
                detail.summary(), upstreamRequest, UUID.randomUUID().toString().replace("-", ""));
        long durationMs = elapsedMs(callStarted);
        steps.add(new ExecutionStep("上游连通性调用", "通过", detail.summary().upstreamUrl(), durationMs));
        traceNodes.add(new TraceNode("VENDOR", "上游供应商", "EXTERNAL", "SUCCESS",
                "真实 HTTP 请求已完成，本模式不执行平台标准化和调用计量", durationMs, invocation, upstreamResponse));
        return new TestResult(upstreamRequest, upstreamResponse, upstreamResponse, steps, traceNodes);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> invokeThroughDataApplication(InterfaceSummary summary,
                                                              Map<String, Object> request,
                                                              String traceId) {
        return invokeThroughDataApplication(summary, request, traceId, upstreamRequestHeaders(summary));
    }

    Map<String, Object> invokeThroughDataApplication(InterfaceSummary summary,
                                                              Map<String, Object> request, String traceId,
                                                              Map<String, String> requestHeaders) {
        return invokeThroughDataApplication(summary, request, traceId, requestHeaders, null);
    }

    Map<String, Object> invokeThroughDataApplication(InterfaceSummary summary,
                                                              Map<String, Object> request, String traceId,
                                                              Map<String, String> requestHeaders, Integer maxRows) {
        String applicationEndpoint = dataApplicationEndpoint(summary);
        if (applicationEndpoint == null) {
            throw new IllegalStateException("资源提供商尚未配置处理应用访问地址，不允许绕过应用直连外围系统");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("providerCode", dataProviderCode(summary));
        payload.put("dataSourceCode", dataApplicationCode(summary));
        payload.put("interfaceCode", summary.interfaceCode());
        payload.put("upstreamUrl", summary.upstreamUrl());
        payload.put("upstreamHttpMethod", upstreamHttpMethod(summary));
        Map<String, Object> connection = new LinkedHashMap<>(providerConnection(summary));
        connection.put("requestHeaders", requestHeaders);
        Map<String, Object> resourceQuery = new LinkedHashMap<>(resourceQuery(summary));
        if (maxRows != null && "SQL".equalsIgnoreCase(Objects.toString(resourceQuery.get("executionMode"), ""))) {
            resourceQuery.put("maxRows", Math.max(1, Math.min(500, maxRows)));
        }
        connection.put("resourceQuery", resourceQuery);
        payload.put("providerConnection", connection);
        payload.put("timeoutMs", summary.effectiveTimeoutMs());
        payload.put("requestBody", request);
        try {
            // The data application applies the upstream timeout. Admin waits only a
            // small grace period beyond it, so a broken application cannot hold a
            // Tomcat request thread forever.
            Map<String, Object> result = restClient(summary.effectiveTimeoutMs() + 2_000).post()
                    .uri(applicationEndpoint + "/internal/adapter/execute")
                    .body(payload).retrieve().body(Map.class);
            if (result != null && Boolean.FALSE.equals(result.get("success"))) {
                throw new IllegalStateException(describeUpstreamError(result));
            }
            if (result == null || !(result.get("upstreamResponse") instanceof Map<?, ?> response)) {
                throw new IllegalStateException("资源提供商处理应用返回结构不完整");
            }
            return (Map<String, Object>) response;
        } catch (Exception e) {
            throw new IllegalStateException(dataApplicationName(summary) + " 调用失败：" + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String describeUpstreamError(Map<String, Object> result) {
        if (result.get("errorType") != null) {
            String message = String.valueOf(result.getOrDefault("errorMessage", "处理应用调用失败"));
            return "TRANSPORT".equals(result.get("errorType"))
                    ? message : "资源提供商处理应用执行失败：" + message;
        }
        int status = result.get("upstreamStatus") instanceof Number number ? number.intValue() : 500;
        Object rawBody = result.get("upstreamError");
        Map<String, Object> body = rawBody instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String message = "";
        if (body.get("result") instanceof Map<?, ?> responseResult && responseResult.get("message") != null) {
            message = String.valueOf(responseResult.get("message"));
        }
        List<String> details = body.get("errors") instanceof Collection<?> errors
                ? errors.stream().map(String::valueOf).toList() : List.of();
        StringBuilder description = new StringBuilder("上游返回 HTTP ").append(status);
        if (!message.isBlank()) description.append("：").append(message);
        if (!details.isEmpty()) description.append("（").append(String.join("；", details)).append("）")
                .append("。请按供应商字段名修正上游请求样例。");
        return description.toString();
    }

    private RestClient restClient(int timeoutMs) {
        int bounded = Math.max(100, Math.min(122_000, timeoutMs));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(5_000, bounded)));
        factory.setReadTimeout(Duration.ofMillis(bounded));
        return RestClient.builder().requestFactory(factory).build();
    }

    private String dataApplicationCode(InterfaceSummary summary) {
        List<String> codes = jdbc.queryForList(
                "SELECT source_code FROM data_source WHERE id=?", String.class, summary.dataSourceId());
        if (!codes.isEmpty()) return codes.get(0);
        if (summary.interfaceCode().startsWith("ENTERPRISE_")) return "ENTERPRISE";
        if (summary.interfaceCode().startsWith("CREDIT_")) return "CREDIT";
        return null;
    }

    private String dataApplicationName(InterfaceSummary summary) {
        List<String> names = jdbc.queryForList(
                """
                SELECT p.application_name FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id
                WHERE ds.id=? AND p.application_name IS NOT NULL AND p.application_name<>''
                """, String.class, summary.dataSourceId());
        return names.isEmpty() ? "资源提供商处理应用" : names.get(0);
    }

    private String dataApplicationEndpoint(InterfaceSummary summary) {
        List<String> endpoints = jdbc.queryForList(
                """
                SELECT p.runtime_url FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id
                WHERE ds.id=? AND p.runtime_url IS NOT NULL AND p.runtime_url<>''
                """,
                String.class, summary.dataSourceId());
        return endpoints.isEmpty() ? null : trimTrailingSlash(endpoints.get(0));
    }

    private String dataProviderCode(InterfaceSummary summary) {
        List<String> codes = jdbc.queryForList("""
                SELECT p.provider_code FROM data_source ds JOIN data_provider p ON p.id=ds.provider_id
                WHERE ds.id=?
                """, String.class, summary.dataSourceId());
        return codes.isEmpty() ? null : codes.get(0);
    }

    public VendorMockConfig vendorMock(long interfaceId) {
        List<VendorMockConfig> configs = jdbc.query("""
                SELECT m.*,i.interface_code FROM vendor_mock_config m JOIN api_interface i ON i.id=m.interface_id
                WHERE m.interface_id=?
                """, (rs, rowNum) -> new VendorMockConfig(rs.getLong("id"), rs.getLong("interface_id"),
                rs.getString("interface_code"), rs.getString("mock_name"), json.readMap(rs.getString("response_body")),
                rs.getInt("http_status"), rs.getInt("delay_ms"), rs.getBoolean("enabled"),
                "/mock/vendor/" + rs.getString("interface_code")), interfaceId);
        if (configs.isEmpty()) throw new IllegalArgumentException("当前接口尚未生成模拟上游");
        return configs.get(0);
    }

    @Transactional
    public VendorMockConfig saveVendorMock(long interfaceId, SaveVendorMockRequest request) {
        lockEditableInterface(interfaceId);
        jdbc.update("""
                INSERT INTO vendor_mock_config(interface_id,mock_name,response_body,http_status,delay_ms,enabled)
                VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE mock_name=VALUES(mock_name),response_body=VALUES(response_body),
                http_status=VALUES(http_status),delay_ms=VALUES(delay_ms),enabled=VALUES(enabled)
                """, interfaceId, request.mockName(), json.write(request.responseBody()), request.httpStatus(),
                Math.max(0, request.delayMs()), request.enabled());
        String code = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT interface_code FROM api_interface WHERE id=?", String.class, interfaceId));
        jdbc.update("UPDATE api_interface SET upstream_url=?,config_scope='OVERRIDE',upstream_path=NULL WHERE id=?",
                "http://127.0.0.1:8080/mock/vendor/" + code, interfaceId);
        return vendorMock(interfaceId);
    }

    public VendorMockConfig vendorMockByCode(String interfaceCode) {
        Long interfaceId = jdbc.queryForObject("SELECT id FROM api_interface WHERE interface_code=?", Long.class, interfaceCode);
        VendorMockConfig config = vendorMock(Objects.requireNonNull(interfaceId));
        if (!config.enabled()) throw new IllegalStateException("模拟上游已停用");
        try {
            Thread.sleep(Math.min(config.delayMs(), 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return config;
    }

    private boolean recordUsage(long interfaceId, Long clientId, String traceId, ResponseCodeMapping code,
                                long durationMs, long upstreamDurationMs, long gatewayDurationMs) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT hit_billable,miss_billable FROM billing_rule WHERE interface_id=? AND status='ACTIVE'", interfaceId);
        boolean resultBillable = "HIT".equals(code.resultStatus())
                ? !rules.isEmpty() && Boolean.TRUE.equals(rules.get(0).get("hit_billable"))
                : "MISS".equals(code.resultStatus()) && !rules.isEmpty() && Boolean.TRUE.equals(rules.get(0).get("miss_billable"));
        boolean billable = code.billable() && resultBillable;
        jdbc.update("""
                INSERT INTO usage_record(trace_id,client_id,interface_id,result_status,standard_code,billable,
                duration_ms,upstream_duration_ms,gateway_duration_ms,request_status,node_name)
                VALUES(?,?,?,?,?,?,?,?,?,'SUCCESS','jsnx-edsp-data-source-demo-01')
                """, traceId, clientId, interfaceId, code.resultStatus(), code.standardCode(), billable,
                durationMs, upstreamDurationMs, gatewayDurationMs);
        return billable;
    }

    private void recordTraceNodes(String traceId, List<TraceNode> nodes) {
        jdbc.update("DELETE FROM api_call_node_log WHERE trace_id=?", traceId);
        for (int index = 0; index < nodes.size(); index++) {
            TraceNode node = nodes.get(index);
            jdbc.update("""
                    INSERT INTO api_call_node_log(trace_id,node_key,node_name,node_category,node_status,node_detail,
                    duration_ms,input_data,output_data,sort_order) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, traceId, node.key(), node.name(), node.category(), node.status(), node.detail(),
                    node.durationMs(), json.write(node.input()), json.write(node.output()), index);
        }
    }

    public List<ClientApp> clients() {
        return jdbc.query("SELECT * FROM api_client ORDER BY id", (rs, rowNum) -> new ClientApp(
                rs.getLong("id"), rs.getString("client_code"), rs.getString("client_name"),
                rs.getString("institution_code"), rs.getString("institution_name"),
                rs.getString("owner_name"), rs.getString("status")));
    }

    @Transactional
    public ClientApp createClient(CreateClientRequest request) {
        jdbc.update("""
                INSERT INTO api_client(client_code,client_name,institution_code,institution_name,owner_name,status)
                VALUES(?,?,?,?,?,'ACTIVE')
                """, request.clientCode(), request.clientName(), request.institutionCode(),
                request.institutionName(), request.ownerName());
        long id = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        return clients().stream().filter(item -> item.id() == id).findFirst().orElseThrow();
    }

    public List<AuthorizationRecord> authorizations() {
        return jdbc.query("""
                SELECT a.*,c.client_name,i.interface_name,i.interface_code
                FROM api_authorization a JOIN api_client c ON c.id=a.client_id
                JOIN api_interface i ON i.id=a.interface_id ORDER BY a.id DESC
                """, (rs, rowNum) -> new AuthorizationRecord(
                rs.getLong("id"), rs.getLong("client_id"), rs.getString("client_name"),
                rs.getLong("interface_id"), rs.getString("interface_name"), rs.getString("interface_code"),
                rs.getInt("daily_quota"), rs.getDate("valid_from").toLocalDate(),
                rs.getDate("valid_to").toLocalDate(), rs.getString("status"), rs.getString("approved_by")));
    }

    public long activeAuthorizationCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM api_authorization a JOIN api_client c ON c.id=a.client_id
                WHERE a.status='ACTIVE' AND c.status='ACTIVE'
                  AND CURRENT_DATE BETWEEN a.valid_from AND a.valid_to
                """, Long.class);
        return count == null ? 0 : count;
    }

    public Map<String, Object> authorize(String clientCode, String servicePath) {
        return authorize(clientCode, servicePath, null);
    }

    public Map<String, Object> authorize(String clientCode, String servicePath, String apiCode) {
        List<Map<String, Object>> matches = jdbc.queryForList("""
                SELECT c.id client_id,c.client_code,i.id interface_id,i.interface_code,a.daily_quota
                FROM api_client c
                JOIN api_authorization a ON a.client_id=c.id
                JOIN api_interface i ON i.id=a.interface_id
                JOIN published_route pr ON pr.interface_id=i.id
                WHERE c.client_code=? AND c.status='ACTIVE'
                  AND pr.service_path=?
                  AND (? IS NULL OR ? = '' OR pr.interface_code=?)
                  AND i.status NOT IN ('OFFLINE','DELETED')
                  AND a.status='ACTIVE'
                  AND CURRENT_DATE BETWEEN a.valid_from AND a.valid_to
                """, clientCode, servicePath, apiCode, apiCode, apiCode);
        if (matches.isEmpty()) {
            return Map.of("authorized", false, "reason",
                    authorizationRejectionReason(clientCode, servicePath, apiCode));
        }
        Map<String, Object> match = matches.get(0);
        int usedToday = Optional.ofNullable(jdbc.queryForObject("""
                SELECT COUNT(*) FROM usage_record
                WHERE client_id=? AND interface_id=? AND called_at>=CURRENT_DATE
                """, Integer.class, match.get("client_id"), match.get("interface_id"))).orElse(0);
        int quota = ((Number) match.get("daily_quota")).intValue();
        if (usedToday >= quota) {
            return Map.of("authorized", false, "reason", "调用系统已达到该 API 的日配额",
                    "dailyQuota", quota, "usedToday", usedToday);
        }
        return Map.of("authorized", true, "clientCode", match.get("client_code"),
                "interfaceId", match.get("interface_id"), "interfaceCode", match.get("interface_code"),
                "dailyQuota", quota, "usedToday", usedToday);
    }

    private String authorizationRejectionReason(String clientCode, String servicePath,
                                                String apiCode) {
        List<Map<String, Object>> clients = jdbc.queryForList(
                "SELECT id,status FROM api_client WHERE client_code=?", clientCode);
        if (clients.isEmpty()) return "调用系统编码未登记：" + clientCode;
        Map<String, Object> client = clients.get(0);
        if (!"ACTIVE".equals(String.valueOf(client.get("status")))) return "调用系统已停用：" + clientCode;

        List<Map<String, Object>> interfaces = jdbc.queryForList("""
                SELECT i.id,i.status,i.published_version,pr.interface_code FROM published_route pr
                JOIN api_interface i ON i.id=pr.interface_id
                WHERE pr.service_path=? AND (? IS NULL OR ? = '' OR pr.interface_code=?)
                ORDER BY i.id DESC LIMIT 1
                """, servicePath, apiCode, apiCode, apiCode);
        if (interfaces.isEmpty()) return "未找到已登记的 API 路由：" + (apiCode == null ? servicePath : apiCode);
        Map<String, Object> api = interfaces.get(0);
        if (api.get("published_version") == null) return "API 尚未发布：" + api.get("interface_code");
        if (Set.of("OFFLINE", "DELETED").contains(String.valueOf(api.get("status")))) {
            return "API 已下线或归档：" + api.get("interface_code");
        }

        List<Map<String, Object>> grants = jdbc.queryForList("""
                SELECT status,valid_from,valid_to FROM api_authorization
                WHERE client_id=? AND interface_id=? ORDER BY id DESC
                """, client.get("id"), api.get("id"));
        if (grants.isEmpty()) return "调用系统未获得该 API 授权";
        Map<String, Object> grant = grants.get(0);
        if (!"ACTIVE".equals(String.valueOf(grant.get("status")))) return "该 API 授权已停用";
        LocalDate today = LocalDate.now();
        LocalDate validFrom = ((java.sql.Date) grant.get("valid_from")).toLocalDate();
        LocalDate validTo = ((java.sql.Date) grant.get("valid_to")).toLocalDate();
        if (today.isBefore(validFrom)) return "该 API 授权尚未生效，生效日期：" + validFrom;
        if (today.isAfter(validTo)) return "该 API 授权已过期，失效日期：" + validTo;
        return "调用系统未获得该 API 的有效授权";
    }

    @Transactional
    public AuthorizationRecord createAuthorization(CreateAuthorizationRequest request) {
        lockEditableInterface(request.interfaceId());
        if (request.dailyQuota() <= 0) throw new IllegalArgumentException("日调用配额必须大于 0");
        if (request.validFrom().isAfter(request.validTo())) {
            throw new IllegalArgumentException("生效日期不能晚于失效日期");
        }
        Map<String, Object> client = jdbc.queryForMap(
                "SELECT status FROM api_client WHERE id=?", request.clientId());
        if (!"ACTIVE".equals(client.get("status"))) {
            throw new IllegalStateException("调用系统已停用，不能授权");
        }
        Map<String, Object> api = jdbc.queryForMap(
                "SELECT status,published_version FROM api_interface WHERE id=?", request.interfaceId());
        if (api.get("published_version") == null
                || Set.of("OFFLINE", "DELETED").contains(String.valueOf(api.get("status")))) {
            throw new IllegalStateException("只有已发布且在线的 API 才能授权");
        }
        jdbc.update("""
                INSERT INTO api_authorization(client_id,interface_id,daily_quota,valid_from,valid_to,status,approved_by)
                VALUES(?,?,?,?,?,'ACTIVE',?)
                ON DUPLICATE KEY UPDATE daily_quota=VALUES(daily_quota),valid_from=VALUES(valid_from),
                valid_to=VALUES(valid_to),status='ACTIVE',approved_by=VALUES(approved_by)
                """, request.clientId(), request.interfaceId(), request.dailyQuota(),
                request.validFrom(), request.validTo(), request.approvedBy());
        return authorizations().stream()
                .filter(item -> item.clientId() == request.clientId() && item.interfaceId() == request.interfaceId())
                .findFirst().orElseThrow();
    }

    @Transactional
    public List<AuthorizationRecord> createAuthorizations(BatchAuthorizationRequest request) {
        if (request.interfaceIds() == null || request.interfaceIds().isEmpty()) {
            throw new IllegalArgumentException("至少选择一个已发布 API");
        }
        if (request.dailyQuota() <= 0) throw new IllegalArgumentException("日调用配额必须大于 0");
        if (request.validFrom().isAfter(request.validTo())) {
            throw new IllegalArgumentException("生效日期不能晚于失效日期");
        }
        Map<String, Object> client = jdbc.queryForMap(
                "SELECT status FROM api_client WHERE id=?", request.clientId());
        if (!"ACTIVE".equals(client.get("status"))) {
            throw new IllegalStateException("调用系统已停用，不能授权");
        }

        List<Long> interfaceIds = request.interfaceIds().stream().filter(Objects::nonNull).distinct().toList();
        if (interfaceIds.isEmpty()) throw new IllegalArgumentException("至少选择一个已发布 API");
        String placeholders = String.join(",", Collections.nCopies(interfaceIds.size(), "?"));
        List<Long> eligibleIds = jdbc.queryForList("""
                SELECT id FROM api_interface
                WHERE id IN (%s) AND published_version IS NOT NULL
                  AND status NOT IN ('OFFLINE','DELETED') FOR UPDATE
                """.formatted(placeholders), Long.class, interfaceIds.toArray());
        if (eligibleIds.size() != interfaceIds.size()) {
            Set<Long> invalidIds = new LinkedHashSet<>(interfaceIds);
            invalidIds.removeAll(eligibleIds);
            throw new IllegalStateException("以下 API 未发布或已下线，无法授权：" + invalidIds);
        }
        String sql = """
                INSERT INTO api_authorization(client_id,interface_id,daily_quota,valid_from,valid_to,status,approved_by)
                VALUES(?,?,?,?,?,'ACTIVE',?)
                ON DUPLICATE KEY UPDATE daily_quota=VALUES(daily_quota),valid_from=VALUES(valid_from),
                valid_to=VALUES(valid_to),status='ACTIVE',approved_by=VALUES(approved_by)
                """;
        List<Object[]> batch = interfaceIds.stream().map(interfaceId -> new Object[]{
                request.clientId(), interfaceId, request.dailyQuota(),
                request.validFrom(), request.validTo(), request.approvedBy()
        }).toList();
        jdbc.batchUpdate(sql, batch);
        return authorizations().stream().filter(item -> item.clientId() == request.clientId()
                && interfaceIds.contains(item.interfaceId())).toList();
    }

    public List<BillingRule> billingRules() {
        return jdbc.query("""
                SELECT b.*,i.interface_name,i.interface_code FROM billing_rule b
                JOIN api_interface i ON i.id=b.interface_id ORDER BY b.id
                """, (rs, rowNum) -> new BillingRule(
                rs.getLong("id"), rs.getLong("interface_id"), rs.getString("interface_name"),
                rs.getString("interface_code"),
                rs.getBoolean("hit_billable"), rs.getBoolean("miss_billable"),
                rs.getDate("effective_date").toLocalDate(), rs.getString("status")));
    }

    @Transactional
    public BillingRule saveBillingRule(SaveBillingRuleRequest request) {
        lockEditableInterface(request.interfaceId());
        jdbc.update("""
                INSERT INTO billing_rule(interface_id,hit_billable,miss_billable,effective_date,status)
                VALUES(?,?,?,?,'ACTIVE') ON DUPLICATE KEY UPDATE
                hit_billable=VALUES(hit_billable),miss_billable=VALUES(miss_billable),
                effective_date=VALUES(effective_date),status='ACTIVE'
                """, request.interfaceId(), request.hitBillable(),
                request.missBillable(), request.effectiveDate());
        return billingRules().stream().filter(item -> item.interfaceId() == request.interfaceId()).findFirst().orElseThrow();
    }

    public BillingSummary billingSummary() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) total_calls,COALESCE(SUM(billable),0) billable_calls,
                COALESCE(SUM(result_status='HIT'),0) hit_calls,
                COALESCE(SUM(result_status='MISS'),0) miss_calls,
                COALESCE(SUM(result_status='ERROR'),0) error_calls FROM usage_record
                """, (rs, rowNum) -> new BillingSummary(rs.getLong("total_calls"), rs.getLong("billable_calls"),
                rs.getLong("hit_calls"), rs.getLong("miss_calls"), rs.getLong("error_calls")));
    }

    public List<UsageRecord> usageRecords() {
        return jdbc.query("""
                SELECT u.*,c.client_name,i.interface_name,i.interface_code FROM usage_record u
                LEFT JOIN api_client c ON c.id=u.client_id JOIN api_interface i ON i.id=u.interface_id
                ORDER BY u.called_at DESC,u.id DESC LIMIT 100
                """, usageRecordMapper());
    }

    public List<UsageRecord> callLogs(String status, String interfaceCode, String keyword) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.*,c.client_name,i.interface_name,i.interface_code FROM usage_record u
                LEFT JOIN api_client c ON c.id=u.client_id JOIN api_interface i ON i.id=u.interface_id WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            if ("SUCCESS".equalsIgnoreCase(status)) sql.append(" AND u.request_status='SUCCESS' AND u.result_status<>'ERROR'");
            else sql.append(" AND (u.request_status<>'SUCCESS' OR u.result_status='ERROR')");
        }
        if (interfaceCode != null && !interfaceCode.isBlank()) {
            sql.append(" AND i.interface_code=?"); args.add(interfaceCode);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (u.trace_id LIKE ? OR i.interface_name LIKE ? OR i.interface_code LIKE ? OR c.client_name LIKE ?)");
            String pattern = "%" + keyword.trim() + "%";
            args.add(pattern); args.add(pattern); args.add(pattern); args.add(pattern);
        }
        sql.append(" ORDER BY u.called_at DESC,u.id DESC LIMIT 200");
        return jdbc.query(sql.toString(), usageRecordMapper(), args.toArray());
    }

    public CallTraceDetail callTrace(String traceId) {
        UsageRecord summary = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT u.*,c.client_name,i.interface_name,i.interface_code FROM usage_record u
                LEFT JOIN api_client c ON c.id=u.client_id JOIN api_interface i ON i.id=u.interface_id
                WHERE u.trace_id=?
                """, usageRecordMapper(), traceId));
        List<TraceNode> nodes = jdbc.query("""
                SELECT * FROM api_call_node_log WHERE trace_id=? ORDER BY sort_order,id
                """, (rs, rowNum) -> new TraceNode(rs.getString("node_key"), rs.getString("node_name"),
                rs.getString("node_category"), rs.getString("node_status"), rs.getString("node_detail"),
                rs.getLong("duration_ms"), json.readMap(rs.getString("input_data")),
                json.readMap(rs.getString("output_data"))), traceId);
        if (nodes.isEmpty()) nodes = fallbackTrace(summary);
        return new CallTraceDetail(summary, nodes);
    }

    private List<TraceNode> fallbackTrace(UsageRecord record) {
        boolean success = "SUCCESS".equals(record.requestStatus());
        String finalStatus = success ? "SUCCESS" : "ERROR";
        Map<String, Object> context = Map.of("traceId", record.traceId(), "interfaceCode", record.interfaceCode());
        return List.of(
                new TraceNode("GATEWAY", "统一 Gateway", "PLATFORM", "SUCCESS", "认证、授权与路由", Math.max(1, record.gatewayDurationMs() / 2), context, context),
                new TraceNode("DATA_SOURCE", "资源提供商处理应用", "ADAPTER", success ? "SUCCESS" : "ERROR", "协议适配与字段转换", Math.max(1, record.gatewayDurationMs() / 2), context, context),
                new TraceNode("VENDOR", "上游供应商", "EXTERNAL", finalStatus, success ? "上游调用完成" : "调用失败，请结合标准返回码定位", record.upstreamDurationMs(), context, Map.of("standardCode", record.standardCode()))
        );
    }

    private RowMapper<UsageRecord> usageRecordMapper() {
        return (rs, rowNum) -> new UsageRecord(rs.getLong("id"), rs.getString("trace_id"),
                rs.getString("client_name"), rs.getString("interface_name"), rs.getString("interface_code"),
                rs.getString("result_status"), rs.getString("standard_code"), rs.getBoolean("billable"),
                rs.getInt("duration_ms"),
                rs.getInt("upstream_duration_ms"), rs.getInt("gateway_duration_ms"), rs.getString("request_status"),
                rs.getString("node_name"), rs.getTimestamp("called_at").toLocalDateTime());
    }

    public MonitorSummary monitorSummary() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT duration_ms,upstream_duration_ms,request_status FROM usage_record");
        if (rows.isEmpty()) return new MonitorSummary(0, 0, 0, 100, 0, 0, 0, 0);
        List<Long> durations = rows.stream().map(row -> ((Number) row.get("duration_ms")).longValue()).sorted().toList();
        long success = rows.stream().filter(row -> "SUCCESS".equals(row.get("request_status"))).count();
        long error = rows.size() - success;
        long avg = Math.round(rows.stream().mapToLong(row -> ((Number) row.get("duration_ms")).longValue()).average().orElse(0));
        long upstreamAvg = Math.round(rows.stream().mapToLong(row -> ((Number) row.get("upstream_duration_ms")).longValue()).average().orElse(0));
        long p95 = durations.get(Math.min(durations.size() - 1, (int) Math.ceil(durations.size() * 0.95) - 1));
        return new MonitorSummary(rows.size(), success, error, Math.round(success * 10000.0 / rows.size()) / 100.0,
                avg, p95, upstreamAvg, error > 0 || p95 > 1000 ? 1 : 0);
    }

    public List<MonitorPoint> monitorPoints() {
        String currentHour = jdbc.queryForObject("SELECT DATE_FORMAT(NOW(),'%Y-%m-%d %H:00:00')", String.class);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE_FORMAT(called_at,'%Y-%m-%d %H:00:00') hour_bucket,COUNT(*) calls,
                SUM(request_status='SUCCESS') success_calls,SUM(request_status<>'SUCCESS') error_calls,
                ROUND(AVG(duration_ms)) avg_duration_ms,ROUND(AVG(upstream_duration_ms)) avg_upstream_duration_ms
                FROM usage_record
                WHERE called_at >= DATE_SUB(DATE_FORMAT(NOW(),'%Y-%m-%d %H:00:00'), INTERVAL 23 HOUR)
                  AND called_at < DATE_ADD(DATE_FORMAT(NOW(),'%Y-%m-%d %H:00:00'), INTERVAL 1 HOUR)
                GROUP BY hour_bucket
                ORDER BY hour_bucket
                """);
        return fillMonitorHours(LocalDateTime.parse(currentHour, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), rows);
    }

    static List<MonitorPoint> fillMonitorHours(LocalDateTime currentHour, List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> byHour = new HashMap<>();
        rows.forEach(row -> byHour.put(String.valueOf(row.get("hour_bucket")), row));
        DateTimeFormatter bucketFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter labelFormat = DateTimeFormatter.ofPattern("HH:mm");
        List<MonitorPoint> points = new ArrayList<>(24);
        for (int offset = 23; offset >= 0; offset--) {
            LocalDateTime hour = currentHour.minusHours(offset);
            Map<String, Object> row = byHour.get(hour.format(bucketFormat));
            points.add(row == null ? new MonitorPoint(hour.format(labelFormat), 0, 0, 0, 0, 0)
                    : new MonitorPoint(hour.format(labelFormat), ((Number) row.get("calls")).longValue(),
                    ((Number) row.get("success_calls")).longValue(), ((Number) row.get("error_calls")).longValue(),
                    ((Number) row.get("avg_duration_ms")).longValue(),
                    ((Number) row.get("avg_upstream_duration_ms")).longValue()));
        }
        return points;
    }

    public List<MonitorAlert> monitorAlerts() {
        MonitorSummary summary = monitorSummary();
        List<MonitorAlert> alerts = new ArrayList<>();
        if (summary.errorCalls() > 0) alerts.add(new MonitorAlert("WARNING", "存在调用失败记录", "请在调用明细中检查返回码和上游响应", "当前"));
        if (summary.p95DurationMs() > 1000) alerts.add(new MonitorAlert("CRITICAL", "P95 调用耗时超过阈值", "当前 P95 为 " + summary.p95DurationMs() + " ms", "当前"));
        if (alerts.isEmpty()) alerts.add(new MonitorAlert("INFO", "全链路运行正常", "当前没有超过阈值的监控告警", "当前"));
        return alerts;
    }

    private long elapsedMs(long started) {
        return Math.max(1, (System.nanoTime() - started) / 1_000_000);
    }

    private Map<String, Object> applyMappings(Map<String, Object> input, List<FieldMapping> mappings, String direction) {
        List<FieldMapping> directionalMappings = mappings.stream()
                .filter(m -> direction.equals(m.direction())).toList();
        if (directionalMappings.isEmpty()) return new LinkedHashMap<>(input);
        List<Map<String, Object>> planMappings = mapper.convertValue(directionalMappings, new TypeReference<>() {});
        return PlanMappingSupport.apply(input, planMappings, secretResolver);
    }

    private Map<String, Object> responsePolicy(Object raw, boolean hasResponseMappings) {
        Map<String, Object> value = raw == null ? Map.of() : raw instanceof Map<?, ?>
                ? mapper.convertValue(raw, new TypeReference<>() {}) : json.readMap(String.valueOf(raw));
        return ResponsePolicySupport.normalize(value, hasResponseMappings);
    }

    @Transactional
    public InterfaceSummary publishDirect(long interfaceId, PublishRequest request) {
        lockEditableInterface(interfaceId);
        InterfaceDetail detail = interfaceDetail(interfaceId);
        if (!"READY".equals(detail.summary().upstreamStatus())) {
            throw new IllegalStateException("上游能力尚未保存完成，不能上线");
        }
        if (Objects.equals(detail.summary().publishedVersion(), detail.summary().currentVersion())
                && "PUBLISHED".equals(detail.summary().status())) {
            throw new IllegalStateException("当前版本已经在线运行");
        }
        ValidationResult validation = validate(interfaceId);
        if (!validation.valid()) throw new IllegalStateException(String.join("；", validation.errors()));
        requireDataApplicationRoute(detail.summary());
        validatePublishableServicePath(detail.summary().servicePath());
        int version = detail.summary().currentVersion();
        String releaseChecksum = checksum(detail);
        String releasedBy = valueOr(request == null ? null : request.releasedBy(), "平台管理员");
        String releaseNote = request == null ? null : request.releaseNote();
        jdbc.update("UPDATE api_interface SET status='PUBLISHED',published_version=? WHERE id=?", version, interfaceId);
        // Persist the immutable online route in the same transaction as the release.
        // Draft edits continue to live in api_interface and cannot affect Gateway traffic
        // until a later release atomically replaces this published snapshot.
        upsertPublishedRoute(detail, version);
        jdbc.update("""
                INSERT INTO release_record(interface_id,release_version,release_status,checksum,release_note,released_by)
                VALUES(?,?,'SUCCESS',?,?,?)
                ON DUPLICATE KEY UPDATE release_status='SUCCESS',checksum=VALUES(checksum),
                release_note=VALUES(release_note),released_by=VALUES(released_by),released_at=NOW()
                """, interfaceId, version, releaseChecksum, releaseNote, releasedBy);
        jdbc.update("INSERT INTO interface_lifecycle_record(interface_id,action,operated_by,reason) VALUES(?, 'PUBLISH', ?, ?)",
                interfaceId, releasedBy, valueOr(releaseNote, "直接上线"));
        InterfaceDetail publishedDetail = interfaceDetail(interfaceId);
        List<FieldMapping> mappings = publishedDetail.mappings();
        plans.activate(new PublishedPlanRegistry.PublishedPlan(interfaceId, publishedDetail.summary().interfaceCode(),
                version, mappings.stream().filter(m -> "REQUEST".equals(m.direction())).toList(),
                mappings.stream().filter(m -> "RESPONSE".equals(m.direction())).toList(), releaseChecksum, Instant.now(),
                publishedDetail.summary(), publishedDetail.vendorCodePath(), publishedDetail.responseCodes(), publishedDetail.securityPolicy(), publishedDetail.responsePolicy()));
        savePlanSnapshot(interfaceId, version, releaseChecksum, publishedDetail);
        notifyPublishedRouteAfterCommit(publishedDetail);
        return interfaceSummary(interfaceId);
    }

    private Map<String, Object> applySecurityPolicy(Map<String, Object> request, SecurityPolicy policy) {
        Map<String, Object> planPolicy = policy == null ? Map.of()
                : mapper.convertValue(policy, new TypeReference<>() {});
        return SecurityPlanSupport.apply(request, planPolicy, secretResolver, mapper);
    }

    private String maskValue(String value) {
        if (value == null || value.length() < 4) return "****";
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private String normalizeVendorCodePath(String path) {
        String normalized = valueOr(path, "result.code").trim();
        if (normalized.startsWith("$") || normalized.startsWith("/") || normalized.contains("[")) {
            throw new IllegalArgumentException("供应商返回码字段路径仅支持点号路径，例如 result.code、status.code");
        }
        for (String part : normalized.split("\\.")) {
            if (part.isBlank() || !part.matches("[A-Za-z0-9_\\-]+")) {
                throw new IllegalArgumentException("供应商返回码字段路径格式无效：" + path);
            }
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private Object readPath(Map<String, Object> source, String path) {
        return FieldTransformSupport.readPath(source, path);
    }

    @SuppressWarnings("unchecked")
    private void writePath(Map<String, Object> target, String path, Object value) {
        FieldTransformSupport.writePath(target, path, value);
    }

    @Transactional
    public ReleaseApproval submitApproval(long interfaceId, PublishRequest request) {
        lockEditableInterface(interfaceId);
        ValidationResult validation = validate(interfaceId);
        if (!validation.valid()) throw new IllegalStateException(String.join("；", validation.errors()));
        InterfaceDetail detail = interfaceDetail(interfaceId);
        int version = detail.summary().currentVersion();
        Integer pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM release_approval WHERE interface_id=? AND release_version=? AND status='PENDING'
                """, Integer.class, interfaceId, version);
        if (pending != null && pending > 0) throw new IllegalStateException("当前版本已提交审批，请勿重复提交");
        jdbc.update("""
                INSERT INTO release_approval(interface_id,release_version,applicant,release_note,status,approval_node)
                VALUES(?,?,?,?,'PENDING','技术审批')
                """, interfaceId, version, valueOr(request.releasedBy(), "平台管理员"), request.releaseNote());
        long approvalId = Objects.requireNonNull(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
        jdbc.update("UPDATE api_interface SET status='PENDING_APPROVAL' WHERE id=?", interfaceId);
        return approval(approvalId);
    }

    public List<ReleaseApproval> approvals(String status) {
        String sql = """
                SELECT a.*,i.interface_code,i.interface_name FROM release_approval a
                JOIN api_interface i ON i.id=a.interface_id
                """ + (status == null || status.isBlank() ? "" : " WHERE a.status=?") + " ORDER BY a.submitted_at DESC,a.id DESC";
        return status == null || status.isBlank()
                ? jdbc.query(sql, approvalMapper()) : jdbc.query(sql, approvalMapper(), status);
    }

    public ReleaseApproval approval(long id) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT a.*,i.interface_code,i.interface_name FROM release_approval a
                JOIN api_interface i ON i.id=a.interface_id WHERE a.id=?
                """, approvalMapper(), id));
    }

    public List<InterfaceVersionHistory> interfaceVersions(long interfaceId) {
        InterfaceSummary summary = interfaceSummary(interfaceId);
        Map<Integer, VersionHistoryAccumulator> versions = new TreeMap<>(Comparator.reverseOrder());

        jdbc.query("""
                SELECT release_version,applicant,release_note,status,approval_node,submitted_at,
                       reviewed_by,review_comment,reviewed_at,checksum
                FROM release_approval WHERE interface_id=?
                ORDER BY release_version DESC,submitted_at DESC,id DESC
                """, rs -> {
            int version = rs.getInt("release_version");
            VersionHistoryAccumulator item = versions.computeIfAbsent(version, VersionHistoryAccumulator::new);
            if (!item.hasApproval) {
                item.hasApproval = true;
                item.applicant = rs.getString("applicant");
                item.releaseNote = rs.getString("release_note");
                item.approvalStatus = rs.getString("status");
                item.approvalNode = rs.getString("approval_node");
                item.submittedAt = localDateTime(rs.getTimestamp("submitted_at"));
                item.reviewedBy = rs.getString("reviewed_by");
                item.reviewComment = rs.getString("review_comment");
                item.reviewedAt = localDateTime(rs.getTimestamp("reviewed_at"));
                item.checksum = rs.getString("checksum");
            }
        }, interfaceId);

        jdbc.query("""
                SELECT release_version,release_status,checksum,release_note,released_by,released_at
                FROM release_record WHERE interface_id=? ORDER BY release_version DESC
                """, rs -> {
            int version = rs.getInt("release_version");
            VersionHistoryAccumulator item = versions.computeIfAbsent(version, VersionHistoryAccumulator::new);
            item.hasReleaseRecord = true;
            item.releaseStatus = rs.getString("release_status");
            item.checksum = valueOr(rs.getString("checksum"), item.checksum);
            item.releaseNote = valueOr(rs.getString("release_note"), item.releaseNote);
            item.releasedBy = rs.getString("released_by");
            item.releasedAt = localDateTime(rs.getTimestamp("released_at"));
        }, interfaceId);

        jdbc.query("SELECT release_version FROM release_plan_snapshot WHERE interface_id=?", rs -> {
            VersionHistoryAccumulator item = versions.computeIfAbsent(
                    rs.getInt("release_version"), VersionHistoryAccumulator::new);
            item.snapshotAvailable = true;
        }, interfaceId);

        versions.computeIfAbsent(summary.currentVersion(), VersionHistoryAccumulator::new).currentDraft = true;
        if (summary.publishedVersion() != null) {
            versions.computeIfAbsent(summary.publishedVersion(), VersionHistoryAccumulator::new).publishedMarker = true;
        }

        return versions.values().stream()
                .map(item -> item.toHistory(interfaceId, summary.publishedVersion(), summary.status()))
                .toList();
    }

    @Transactional
    public ReleaseApproval decideApproval(long id, ApprovalDecisionRequest request) {
        ReleaseApproval approval = approval(id);
        lockEditableInterface(approval.interfaceId());
        approval = approval(id);
        if (!"PENDING".equals(approval.status())) throw new IllegalStateException("该审批单已处理");
        if ("REJECT".equalsIgnoreCase(request.decision())) {
            jdbc.update("""
                    UPDATE release_approval SET status='REJECTED',reviewed_by=?,review_comment=?,reviewed_at=NOW()
                    WHERE id=?
                    """, request.reviewedBy(), request.reviewComment(), id);
            jdbc.update("UPDATE api_interface SET status='DRAFT' WHERE id=?", approval.interfaceId());
            return approval(id);
        }
        if (!"APPROVE".equalsIgnoreCase(request.decision())) throw new IllegalArgumentException("审批决定必须为 APPROVE 或 REJECT");
        InterfaceDetail detail = interfaceDetail(approval.interfaceId());
        if (!"PENDING_APPROVAL".equals(detail.summary().status())
                || detail.summary().currentVersion() != approval.releaseVersion()) {
            throw new IllegalStateException("审批版本与当前草稿版本不一致，请驳回后重新提交");
        }
        ValidationResult validation = validate(approval.interfaceId());
        if (!validation.valid()) throw new IllegalStateException(String.join("；", validation.errors()));
        requireDataApplicationRoute(detail.summary());
        validatePublishableServicePath(detail.summary().servicePath());
        String checksum = checksum(detail);
        jdbc.update("UPDATE api_interface SET status='PUBLISHED',published_version=? WHERE id=?",
                approval.releaseVersion(), approval.interfaceId());
        upsertPublishedRoute(detail, approval.releaseVersion());
        jdbc.update("""
                INSERT INTO release_record(interface_id,release_version,release_status,checksum,release_note,released_by)
                VALUES(?,?,'SUCCESS',?,?,?)
                ON DUPLICATE KEY UPDATE release_status='SUCCESS',checksum=VALUES(checksum),
                release_note=VALUES(release_note),released_by=VALUES(released_by),released_at=NOW()
                """, approval.interfaceId(), approval.releaseVersion(), checksum, approval.releaseNote(), request.reviewedBy());
        jdbc.update("""
                UPDATE release_approval SET status='APPROVED',reviewed_by=?,review_comment=?,reviewed_at=NOW(),checksum=?
                WHERE id=?
                """, request.reviewedBy(), request.reviewComment(), checksum, id);
        List<FieldMapping> mappings = mappings(approval.interfaceId());
        plans.activate(new PublishedPlanRegistry.PublishedPlan(approval.interfaceId(), detail.summary().interfaceCode(),
                approval.releaseVersion(), mappings.stream().filter(m -> "REQUEST".equals(m.direction())).toList(),
                mappings.stream().filter(m -> "RESPONSE".equals(m.direction())).toList(), checksum, Instant.now(),
                detail.summary(), detail.vendorCodePath(), detail.responseCodes(), detail.securityPolicy(), detail.responsePolicy()));
        savePlanSnapshot(approval.interfaceId(), approval.releaseVersion(), checksum, detail);
        notifyPublishedRouteAfterCommit(detail);
        return approval(id);
    }

    private void upsertPublishedRoute(InterfaceDetail detail, int version) {
        upsertPublishedRoute(detail.summary(), version);
    }

    private void upsertPublishedRoute(InterfaceSummary summary, int version) {
        jdbc.update("""
                INSERT INTO published_route(
                    interface_id,interface_code,service_path,http_method,route_version,data_source_id
                ) VALUES(?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    interface_code=VALUES(interface_code),
                    service_path=VALUES(service_path),
                    http_method=VALUES(http_method),
                    route_version=VALUES(route_version),
                    data_source_id=VALUES(data_source_id),
                    published_at=NOW()
                """, summary.id(), summary.interfaceCode(), summary.servicePath(),
                summary.httpMethod(), version, summary.dataSourceId());
    }

    public Map<String, Object> publishedPlanConfiguration(long interfaceId) {
        var plan = plans.get(interfaceId);
        if (plan == null) return Map.of("active", false);
        Map<String, Object> result = mapper.convertValue(plan, new TypeReference<>() {});
        Map<String, Object> connection = new LinkedHashMap<>(providerConnection(plan.summary()));
        List<Map<String, Object>> snapshots = jdbc.queryForList("""
                SELECT JSON_EXTRACT(plan_json,'$.upstreamRequestHeaders') request_headers,
                       JSON_EXTRACT(plan_json,'$.upstreamRequestExample') request_example,
                       JSON_EXTRACT(plan_json,'$.resourceQuery') resource_query
                FROM release_plan_snapshot WHERE interface_id=? AND release_version=?
                """, interfaceId, plan.version());
        if (!snapshots.isEmpty() && snapshots.get(0).get("request_headers") != null) {
            connection.put("requestHeaders", readRequestHeaders(snapshots.get(0).get("request_headers")));
        }
        if (!snapshots.isEmpty() && snapshots.get(0).get("request_example") != null) {
            result.put("upstreamRequestExample", json.readMap(String.valueOf(snapshots.get(0).get("request_example"))));
        }
        if (!snapshots.isEmpty() && snapshots.get(0).get("resource_query") != null) {
            connection.put("resourceQuery", json.readMap(String.valueOf(snapshots.get(0).get("resource_query"))));
        }
        result.put("providerConnection", connection);
        result.put("upstreamHttpMethod", publishedUpstreamMethod(plan.summary(), plan.version()));
        return result;
    }

    private String publishedUpstreamMethod(InterfaceSummary summary, int version) {
        List<Map<String, Object>> snapshots = jdbc.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(plan_json,'$.upstreamHttpMethod')) upstream_method
                FROM release_plan_snapshot WHERE interface_id=? AND release_version=?
                """, summary.id(), version);
        if (!snapshots.isEmpty() && snapshots.get(0).get("upstream_method") instanceof String method && !method.isBlank()) {
            return method;
        }
        return upstreamHttpMethod(summary);
    }

    private Map<String, Object> providerConnection(InterfaceSummary summary) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.provider_code,p.access_mode,p.access_config FROM data_source ds
                JOIN data_provider p ON p.id=ds.provider_id WHERE ds.id=?
                """, summary.dataSourceId());
        if (rows.isEmpty()) throw new IllegalStateException("未找到数据源所属的资源提供商");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerCode", row.get("provider_code"));
        result.put("accessMode", valueOr(Objects.toString(row.get("access_mode"), null), "HTTP"));
        result.put("accessConfig", row.get("access_config") == null ? Map.of()
                : json.readMap(String.valueOf(row.get("access_config"))));
        return result;
    }

    private Map<String, Object> resourceQuery(InterfaceSummary summary) {
        long resourceId = summary.resourceApiId() == null ? -1L : summary.resourceApiId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT execution_mode,sql_template,sql_parameters FROM resource_api WHERE id=?", resourceId);
        if (rows.isEmpty()) {
            rows = jdbc.queryForList("SELECT execution_mode,sql_template,sql_parameters FROM api_interface WHERE id=?", summary.id());
        }
        if (rows.isEmpty()) return Map.of("executionMode", "HTTP", "sqlTemplate", "", "sqlParameters", Map.of());
        Map<String, Object> row = rows.get(0);
        return Map.of("executionMode", valueOr(Objects.toString(row.get("execution_mode"), null), "HTTP"),
                "sqlTemplate", Objects.toString(row.get("sql_template"), ""),
                "sqlParameters", readStringMap(row.get("sql_parameters")));
    }

    private Map<String, String> upstreamRequestHeaders(InterfaceSummary summary) {
        Object raw = jdbc.queryForObject("SELECT upstream_request_headers FROM api_interface WHERE id=?",
                Object.class, summary.id());
        return readRequestHeaders(raw);
    }

    private Map<String, String> readRequestHeaders(Object raw) {
        if (raw == null) return Map.of();
        Map<String, Object> decoded = json.readMap(String.valueOf(raw));
        Map<String, String> headers = new LinkedHashMap<>();
        decoded.forEach((name, value) -> headers.put(name, String.valueOf(value)));
        return headers;
    }

    private Map<String, String> validRequestHeaders(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        if (input.size() > 32) throw new IllegalArgumentException("自定义请求头最多 32 项");
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}") || value.length() > 2048
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("请求头名称或内容不合法：" + name);
            }
            if (Set.of("host", "content-length", "content-type", "transfer-encoding", "connection",
                    "x-trace-id").contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("不允许自定义受控请求头：" + name);
            }
            if (result.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("请求头名称重复：" + name);
            }
            result.put(name, value);
        }
        return result;
    }

    private boolean columnMissing(String table, String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, Integer.class, table, column);
        return count == null || count == 0;
    }

    private String upstreamHttpMethod(InterfaceSummary summary) {
        if (summary.resourceApiId() == null) return summary.httpMethod();
        return jdbc.queryForObject("SELECT http_method FROM resource_api WHERE id=?", String.class, summary.resourceApiId());
    }

    private void refreshProviderPlans(long providerId) {
        jdbc.queryForList("""
                SELECT i.id FROM api_interface i JOIN data_source ds ON ds.id=i.data_source_id
                WHERE ds.provider_id=? AND i.published_version IS NOT NULL AND i.status NOT IN ('OFFLINE','DELETED')
                """, providerId).forEach(row -> {
            var plan = plans.get(((Number) row.get("id")).longValue());
            if (plan != null) pushPlanToDataApplication(plan.summary());
        });
    }

    private void requireDataApplicationRoute(InterfaceSummary summary) {
        if (dataApplicationEndpoint(summary) == null) {
            throw new IllegalStateException("资源提供商尚未登记处理应用访问地址，不能上线；请先配置提供商应用路由");
        }
    }

    private void notifyPublishedRouteAfterCommit(InterfaceDetail detail) {
        runAfterCommit(() -> {
            pushPlanToDataApplication(detail.summary());
            refreshGatewayRoutes();
        });
    }

    private void notifyGatewayRouteRefreshAfterCommit() {
        runAfterCommit(this::refreshGatewayRoutes);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void refreshGatewayRoutes() {
        String configured = environment.getProperty("edsp.gateway-refresh-urls", "http://127.0.0.1:8080");
        for (String gateway : configured.split(",")) {
            String endpoint = trimTrailingSlash(gateway);
            if (endpoint == null || endpoint.isBlank()) continue;
            try {
                restClient.post().uri(endpoint + "/internal/routes/refresh").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // Every Gateway also performs periodic full refresh and retains its last-good snapshot.
            }
        }
    }

    private void pushPlanToDataApplication(InterfaceSummary summary) {
        // MySQL remains the durable source. This notification only asks the owning data
        // application to pull the published snapshot and atomically replace its local plan.
        String endpoint = dataApplicationEndpoint(summary);
        if (endpoint == null || endpoint.isBlank()) return;
        try {
            restClient.post().uri(URI.create(endpoint + "/internal/adapter/plans/"
                            + summary.interfaceCode() + "/refresh"))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            // Publication remains durable in MySQL. A stopped data application loads the
            // latest snapshot after restart; other data applications are not affected.
        }
    }

    private void savePlanSnapshot(long interfaceId, int version, String checksum, InterfaceDetail detail) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceCode", detail.summary().interfaceCode());
        snapshot.put("version", version);
        snapshot.put("requestMappings", detail.mappings().stream().filter(m -> "REQUEST".equals(m.direction())).toList());
        snapshot.put("upstreamRequestExample", detail.upstreamRequestExample());
        snapshot.put("responseMappings", detail.mappings().stream().filter(m -> "RESPONSE".equals(m.direction())).toList());
        snapshot.put("summary", detail.summary());
        snapshot.put("upstreamHttpMethod", upstreamHttpMethod(detail.summary()));
        snapshot.put("upstreamRequestHeaders", upstreamRequestHeaders(detail.summary()));
        snapshot.put("resourceQuery", resourceQuery(detail.summary()));
        snapshot.put("vendorCodePath", detail.vendorCodePath());
        snapshot.put("responseCodes", detail.responseCodes());
        snapshot.put("responsePolicy", detail.responsePolicy());
        snapshot.put("securityPolicy", detail.securityPolicy());
        snapshot.put("checksum", checksum);
        jdbc.update("""
                INSERT INTO release_plan_snapshot(interface_id,release_version,checksum,plan_json)
                VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE checksum=VALUES(checksum),plan_json=VALUES(plan_json)
                """, interfaceId, version, checksum, json.write(snapshot));
    }

    private String checksum(InterfaceDetail detail) {
        try {
            Map<String, Object> config = mapper.convertValue(detail, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) config.get("summary");
            // Lifecycle transitions are not execution configuration changes.
            summary.remove("status");
            summary.remove("updatedAt");
            summary.remove("publishedVersion");
            config.put("upstreamRequestHeaders", upstreamRequestHeaders(detail.summary()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(config));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create release checksum", e);
        }
    }

    private String upstreamChecksum(InterfaceDetail detail) {
        return upstreamChecksum(detail.summary().httpMethod(), detail.summary().upstreamUrl(), detail.timeoutMs(),
                detail.upstreamRequestExample(), detail.upstreamResponseExample(),
                upstreamRequestHeaders(detail.summary()));
    }

    private String upstreamChecksum(ResourceApiDetail resource) {
        return upstreamChecksum(resource.summary().httpMethod(), resource.summary().upstreamUrl(),
                resource.summary().effectiveTimeoutMs(), resource.requestExample(), resource.responseExample(),
                resource.requestHeaders());
    }

    private String upstreamChecksum(String method, String url, int timeoutMs,
                                    Map<String, Object> request, Map<String, Object> response) {
        return upstreamChecksum(method, url, timeoutMs, request, response, Map.of());
    }

    private String upstreamChecksum(String method, String url, int timeoutMs,
                                    Map<String, Object> request, Map<String, Object> response,
                                    Map<String, String> headers) {
        Map<String, Object> sourceContract = new LinkedHashMap<>();
        sourceContract.put("httpMethod", method);
        sourceContract.put("upstreamUrl", url);
        sourceContract.put("timeoutMs", timeoutMs);
        sourceContract.put("request", request);
        sourceContract.put("response", response);
        if (!headers.isEmpty()) sourceContract.put("requestHeaders", headers);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsBytes(sourceContract));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create upstream contract checksum", e);
        }
    }

    public Map<String, Boolean> validationStatus(long interfaceId) {
        InterfaceDetail detail = interfaceDetail(interfaceId);
        return Map.of("upstreamVerified", hasUpstreamValidation(detail),
                "orchestrationVerified", hasValidationRecord(interfaceId, "DRAFT_TEST", checksum(detail)),
                "resourceContractChanged", detail.summary().resourceApiId() != null
                        && !Objects.equals(upstreamChecksum(detail), upstreamChecksum(resourceApi(detail.summary().resourceApiId()))));
    }

    public Map<String, Boolean> resourceValidationStatus(long resourceId) {
        ResourceApiDetail resource = resourceApi(resourceId);
        return Map.of("upstreamVerified", hasResourceValidation(resource));
    }

    private boolean hasResourceValidation(ResourceApiDetail resource) {
        if (!"READY".equals(resource.summary().status())) return false;
        List<String> checksums = jdbc.queryForList(
                "SELECT verified_checksum FROM resource_api WHERE id=?", String.class, resource.summary().id());
        return !checksums.isEmpty() && Objects.equals(checksums.get(0), upstreamChecksum(resource));
    }

    private boolean hasUpstreamValidation(InterfaceDetail detail) {
        String expected = upstreamChecksum(detail);
        if (hasValidationRecord(detail.summary().id(), "UPSTREAM", expected)) return true;
        Long resourceId = detail.summary().resourceApiId();
        if (resourceId == null) return false;
        ResourceApiDetail resource = resourceApi(resourceId);
        return Objects.equals(expected, upstreamChecksum(resource)) && hasResourceValidation(resource);
    }

    private void saveValidationRecord(long interfaceId, String validationType, String configChecksum) {
        jdbc.update("""
                INSERT INTO interface_validation_record(interface_id,validation_type,config_checksum)
                VALUES(?,?,?) ON DUPLICATE KEY UPDATE config_checksum=VALUES(config_checksum),validated_at=NOW()
                """, interfaceId, validationType, configChecksum);
    }

    private boolean hasValidationRecord(long interfaceId, String validationType, String expectedChecksum) {
        List<String> checksums = jdbc.queryForList("""
                SELECT config_checksum FROM interface_validation_record
                WHERE interface_id=? AND validation_type=?
                """, String.class, interfaceId, validationType);
        return !checksums.isEmpty() && Objects.equals(checksums.get(0), expectedChecksum);
    }

    private RowMapper<InterfaceSummary> interfaceMapper() {
        return (rs, rowNum) -> {
            String scope = valueOr(rs.getString("config_scope"), "OVERRIDE");
            String upstreamPath = rs.getString("upstream_path");
            String effectiveUrl = effectiveUpstreamUrl(scope, rs.getString("execution_mode"),
                    rs.getString("endpoint_url"), upstreamPath, rs.getString("upstream_url"));
            Integer configuredTimeout = (Integer) rs.getObject("timeout_ms");
            int effectiveTimeout = configuredTimeout == null ? rs.getInt("default_timeout_ms") : configuredTimeout;
            return new InterfaceSummary(rs.getLong("id"), rs.getLong("data_source_id"),
                rs.getString("interface_code"), rs.getString("interface_name"), rs.getString("service_path"),
                rs.getString("http_method"), effectiveUrl, upstreamPath, scope,
                valueOr(rs.getString("dispatch_mode"), "DIRECT"), rs.getString("router_service_code"), effectiveTimeout,
                valueOr(rs.getString("upstream_status"), "READY"), rs.getString("status"),
                rs.getInt("current_version"), (Integer) rs.getObject("published_version"),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                (Long) rs.getObject("resource_api_id"), rs.getString("resource_api_code"),
                rs.getString("resource_api_name"));
        };
    }

    private String interfaceSelect() {
        return """
                SELECT ai.*,ds.endpoint_url,ds.default_timeout_ms,
                       ra.resource_api_code,ra.resource_api_name
                FROM api_interface ai JOIN data_source ds ON ds.id=ai.data_source_id
                LEFT JOIN resource_api ra ON ra.id=ai.resource_api_id
                """;
    }

    private String resourceApiSelect() {
        return """
                SELECT ra.*,ds.endpoint_url AS source_endpoint_url,ds.default_timeout_ms,
                       (SELECT COUNT(*) FROM api_interface ai
                        WHERE ai.resource_api_id=ra.id AND ai.status<>'DELETED') platform_service_count
                FROM resource_api ra JOIN data_source ds ON ds.id=ra.data_source_id
                """;
    }

    private RowMapper<ResourceApiSummary> resourceApiMapper() {
        return (rs, rowNum) -> {
            String scope = valueOr(rs.getString("config_scope"), "OVERRIDE");
            String path = rs.getString("upstream_path");
            String effectiveUrl = effectiveUpstreamUrl(scope, rs.getString("execution_mode"),
                    rs.getString("source_endpoint_url"), path, rs.getString("upstream_url"));
            Integer configured = (Integer) rs.getObject("timeout_ms");
            int timeout = configured == null ? rs.getInt("default_timeout_ms") : configured;
            Timestamp verified = rs.getTimestamp("verified_at");
            return new ResourceApiSummary(rs.getLong("id"), rs.getLong("data_source_id"),
                    rs.getString("resource_api_code"), rs.getString("resource_api_name"),
                    rs.getString("http_method"), effectiveUrl, path, scope, timeout,
                    rs.getString("status"), rs.getInt("current_version"),
                    verified == null ? null : verified.toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime(), rs.getInt("platform_service_count"));
        };
    }

    private ResourceApiSummary resourceApiSummary(long id) {
        return jdbc.queryForObject(resourceApiSelect() + " WHERE ra.id=?", resourceApiMapper(), id);
    }

    private InterfaceSummary interfaceSummary(long id) {
        return jdbc.queryForObject(interfaceSelect() + " WHERE ai.id=?", interfaceMapper(), id);
    }

    private String normalizeConfigScope(String value) {
        String scope = valueOr(value, "INHERIT").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("INHERIT", "OVERRIDE").contains(scope)) {
            throw new IllegalArgumentException("连接配置模式只支持 INHERIT 或 OVERRIDE");
        }
        return scope;
    }

    private String normalizeExecutionMode(String value, String fallback) {
        String mode = valueOr(value, fallback).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HTTP", "SQL").contains(mode)) {
            throw new IllegalArgumentException("不支持的资源执行模式：" + value);
        }
        return mode;
    }

    private boolean isSqlExecution(String mode) { return "SQL".equalsIgnoreCase(mode); }

    private void validateDatabaseMode(Map<String, Object> source, String executionMode) {
        boolean databaseSource = "LOCAL_SQL".equalsIgnoreCase(Objects.toString(source.get("protocol_type"), ""));
        boolean databaseProvider = "DATABASE".equalsIgnoreCase(Objects.toString(source.get("access_mode"), ""));
        if (databaseSource != databaseProvider) throw new IllegalArgumentException("数据库资源必须同时使用数据库接入类型和 LOCAL_SQL 数据源协议");
        if (databaseSource != isSqlExecution(executionMode)) throw new IllegalArgumentException("数据库数据源请选择 SQL 执行模式，HTTP 数据源请选择 HTTP 执行模式");
    }

    private String normalizeSqlTemplate(String sql, String mode) {
        if (!isSqlExecution(mode)) return null;
        SqlQuerySupport.validateAndFindParameters(sql);
        return sql.trim();
    }

    private Map<String, String> validSqlParameters(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        if (input.size() > 64) throw new IllegalArgumentException("SQL 参数最多 64 项");
        Map<String, String> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("SQL 参数名不合法：" + key);
            if (value != null && value.length() > 2000) throw new IllegalArgumentException("SQL 参数值过长：" + key);
            result.put(key, value == null ? "" : value);
        });
        return result;
    }

    private Map<String, String> readStringMap(Object raw) {
        if (raw == null) return Map.of();
        Map<String, Object> values = json.readMap(String.valueOf(raw));
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private int validTimeout(Integer value, int fallback) {
        int timeout = value == null ? fallback : value;
        if (timeout < 100 || timeout > 120_000) {
            throw new IllegalArgumentException("超时时间必须在 100 到 120000 毫秒之间");
        }
        return timeout;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) return null;
        String result = value.trim();
        while (result.endsWith("/") && result.length() > 1) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String normalizeRuntimeUrl(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("数据处理应用运行地址不能为空");
            return null;
        }
        String normalized = trimTrailingSlash(value);
        try {
            URI uri = URI.create(normalized);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("数据处理应用运行地址必须是合法的 HTTP/HTTPS 基础地址");
            }
            return normalized;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("数据处理应用运行地址必须是合法的 HTTP/HTTPS 基础地址", error);
        }
    }

    private int inferRuntimePort(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getPort() > 0) return uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        } catch (RuntimeException ignored) {
            return 8199;
        }
    }

    private String normalizeDataSourceEndpoint(String value, String protocolType) {
        String endpoint = trimTrailingSlash(value);
        if ("LOCAL_SQL".equalsIgnoreCase(protocolType)) return endpoint;
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("数据源基础地址不能为空");
        }
        try {
            URI uri = URI.create(endpoint);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("数据源基础地址必须是合法的 HTTP/HTTPS 地址");
            }
            return endpoint;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("数据源基础地址必须是合法的 HTTP/HTTPS 地址", error);
        }
    }

    private String normalizeAccessMode(String value) {
        String mode = valueOr(value, "HTTP").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HTTP", "DATABASE", "PLATFORM_SERVICE", "KAFKA").contains(mode)) {
            throw new IllegalArgumentException("不支持的提供商接入类型：" + value);
        }
        return mode;
    }

    private Map<String, Object> normalizeAccessConfig(String accessMode, Map<String, Object> source,
                                                      Map<String, Object> previous) {
        Map<String, Object> config = source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
        String password = Objects.toString(config.remove("password"), "");
        config.remove("passwordValue");
        config.remove("passwordConfigured");
        config.remove("passwordCiphertext");
        if ("DATABASE".equals(accessMode)) {
            if (!password.isEmpty()) {
                String key = environment.getProperty("EDSP_DATABASE_CREDENTIAL_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("数据库密码加密密钥未配置，请联系平台管理员");
                }
                config.put("passwordCiphertext", CryptoSupport.aes256GcmEncrypt(password, key, "edsp-provider-database"));
                config.remove("secretReference");
            } else if (previous != null && previous.get("passwordCiphertext") != null) {
                config.put("passwordCiphertext", previous.get("passwordCiphertext"));
            }
        }
        return config;
    }

    private Map<String, Object> storedProviderAccessConfig(long providerId) {
        String value = jdbc.queryForObject("SELECT access_config FROM data_provider WHERE id=?", String.class, providerId);
        return json.readMap(value);
    }

    private Map<String, Object> publicProviderAccessConfig(Map<String, Object> stored) {
        Map<String, Object> config = new LinkedHashMap<>(stored);
        boolean configured = config.remove("passwordCiphertext") != null
                || !Objects.toString(config.get("secretReference"), "").isBlank();
        config.remove("password");
        config.remove("passwordValue");
        if (configured) config.put("passwordConfigured", true);
        return config;
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) return null;
        String path = value.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private void validatePublishableServicePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new IllegalArgumentException("平台标准服务路径不能为空且不能为根路径");
        }
        String normalized = path.toLowerCase(Locale.ROOT);
        boolean reserved = RESERVED_GATEWAY_PATHS.stream()
                .anyMatch(prefix -> normalized.equals(prefix) || normalized.startsWith(prefix + "/"));
        if (reserved) {
            throw new IllegalArgumentException("平台标准服务路径与管理面保留路径冲突：" + path);
        }
    }

    private String joinUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) return path;
        if (path == null || path.isBlank()) return trimTrailingSlash(baseUrl);
        return trimTrailingSlash(baseUrl) + normalizePath(path);
    }

    String effectiveUpstreamUrl(String scope, String executionMode, String baseUrl, String path, String storedUrl) {
        return "INHERIT".equals(scope) && !isSqlExecution(executionMode)
                ? joinUrl(baseUrl, path) : storedUrl;
    }

    private RowMapper<ReleaseApproval> approvalMapper() {
        return (rs, rowNum) -> new ReleaseApproval(rs.getLong("id"), rs.getLong("interface_id"),
                rs.getString("interface_code"), rs.getString("interface_name"), rs.getInt("release_version"),
                rs.getString("applicant"), rs.getString("release_note"), rs.getString("status"),
                rs.getString("approval_node"), rs.getTimestamp("submitted_at").toLocalDateTime(),
                rs.getString("reviewed_by"), rs.getString("review_comment"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                rs.getString("checksum"));
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static final class VersionHistoryAccumulator {
        private final int version;
        private boolean currentDraft;
        private boolean publishedMarker;
        private boolean hasApproval;
        private boolean hasReleaseRecord;
        private boolean snapshotAvailable;
        private String applicant;
        private String releaseNote;
        private String approvalStatus;
        private String approvalNode;
        private String reviewedBy;
        private String reviewComment;
        private LocalDateTime submittedAt;
        private LocalDateTime reviewedAt;
        private String releaseStatus;
        private String releasedBy;
        private LocalDateTime releasedAt;
        private String checksum;

        private VersionHistoryAccumulator(int version) {
            this.version = version;
        }

        private InterfaceVersionHistory toHistory(long interfaceId, Integer publishedVersion, String interfaceStatus) {
            boolean active = publishedVersion != null && publishedVersion == version && !"OFFLINE".equals(interfaceStatus);
            boolean published = publishedMarker || hasReleaseRecord;
            boolean complete = !publishedMarker || hasReleaseRecord || hasApproval || snapshotAvailable;
            String type = published ? "PUBLISHED" : hasApproval ? "APPROVAL" : "DRAFT";
            String status;
            if ("OFFLINE".equals(interfaceStatus) && published && active == false) status = "OFFLINE";
            else if (published && !complete) status = "PUBLISHED_LEGACY";
            else if (published) status = active ? "ACTIVE" : "SUPERSEDED";
            else if (hasApproval) status = approvalStatus;
            else status = "DRAFT";
            return new InterfaceVersionHistory(interfaceId, version, type, status, active,
                    applicant, releaseNote, approvalNode, reviewedBy, reviewComment, submittedAt, reviewedAt,
                    releasedBy, releasedAt, checksum, snapshotAvailable, complete);
        }
    }
}
