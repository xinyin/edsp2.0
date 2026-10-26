package com.jsnx.edsp.sdk;

import com.jsnx.edsp.plan.SqlQuerySupport;
import com.jsnx.edsp.crypto.CryptoSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Executes read-only database resource queries inside the provider-owned application. */
public final class DatabaseProviderConnector implements ProviderConnector, AutoCloseable {
    private static final int ABSOLUTE_MAX_ROWS = 2_000;
    private final Function<String, String> configLookup;
    private final Map<String, QueryPool> pools = new ConcurrentHashMap<>();

    public DatabaseProviderConnector(Function<String, String> configLookup) {
        this.configLookup = Objects.requireNonNull(configLookup);
    }

    @Override public String accessMode() { return "DATABASE"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                                       Map<String, Object> request, String traceId, int timeoutMs) {
        Map<String, Object> query = map(connection.get("resourceQuery"));
        String executionMode = text(query.get("executionMode"));
        if ("CONNECTION_TEST".equalsIgnoreCase(executionMode)
                || "METADATA_TABLES".equalsIgnoreCase(executionMode)
                || "METADATA_COLUMNS".equalsIgnoreCase(executionMode)) {
            return metadata(connection, executionMode, endpoint, timeoutMs);
        }
        if (!"SQL".equalsIgnoreCase(executionMode)) {
            throw new IllegalArgumentException("数据库资源 API 尚未配置 SQL 查询模式");
        }
        String sql = text(query.get("sqlTemplate"));
        Set<String> names = SqlQuerySupport.validateAndFindParameters(sql);
        Map<String, Object> values = request == null ? Map.of() : request;
        Map<String, Object> parameterTypes = map(query.get("sqlParameters"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (String name : names) {
            if (!values.containsKey(name)) throw new IllegalArgumentException("SQL 查询缺少请求参数：" + name);
            Object value = values.get(name);
            if (value instanceof Map<?, ?>) throw new IllegalArgumentException("SQL 参数不支持对象值：" + name);
            parameters.put(name, typedParameter(name, text(parameterTypes.get(name)), value));
        }

        Map<String, Object> config = map(connection.get("accessConfig"));
        String url = text(config.get("jdbcUrl"));
        String user = text(config.get("username"));
        if (!url.startsWith("jdbc:") || user.isBlank()) throw new IllegalStateException("数据库连接配置不完整");
        String password = password(config);
        int maxRows = boundedNumber(config.get("maxRows"), 500, 1, ABSOLUTE_MAX_ROWS);
        int queryTimeout = Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
        QueryPool pool = pool(url, user, password, config, queryTimeout, maxRows);
        List<Map<String, Object>> rows = pool.query.query(sql, parameters, resultSet -> {
            List<Map<String, Object>> result = new ArrayList<>();
            var metadata = resultSet.getMetaData();
            int columns = metadata.getColumnCount();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= columns; index++) {
                    row.put(metadata.getColumnLabel(index), resultSet.getObject(index));
                }
                result.add(row);
            }
            return result;
        });
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("rowCount", rows.size());
        response.put("rowLimitReached", rows.size() >= maxRows);
        return response;
    }

    private Object typedParameter(String name, String type, Object value) {
        if (value == null || type.isBlank() || "STRING".equalsIgnoreCase(type)) {
            if (value instanceof Iterable<?>) throw new IllegalArgumentException("SQL 参数不支持集合值：" + name);
            return value;
        }
        String raw = String.valueOf(value).trim();
        try {
            return switch (type.toUpperCase(Locale.ROOT)) {
                case "NUMBER", "DECIMAL" -> value instanceof Number ? value : new java.math.BigDecimal(raw);
                case "INTEGER", "INT", "LONG" -> value instanceof Number number
                        ? number.longValue() : Long.valueOf(raw);
                case "BOOLEAN", "BOOL" -> {
                    if (value instanceof Boolean) yield value;
                    if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw))
                        throw new IllegalArgumentException("SQL 参数应为布尔值：" + name);
                    yield Boolean.valueOf(raw);
                }
                case "DATE" -> java.time.LocalDate.parse(raw);
                case "DATETIME" -> java.time.LocalDateTime.parse(raw);
                default -> throw new IllegalArgumentException("不支持的 SQL 参数类型：" + type);
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("SQL 参数类型不匹配：" + name + "（" + type + "）", invalid);
        }
    }

    private Map<String, Object> metadata(Map<String, Object> connection, String mode, String tableName, int timeoutMs) {
        Map<String, Object> config = map(connection.get("accessConfig"));
        String url = text(config.get("jdbcUrl"));
        String user = text(config.get("username"));
        if (!url.startsWith("jdbc:") || user.isBlank()) throw new IllegalStateException("数据库连接配置不完整");
        String password = password(config);
        QueryPool pool = pool(url, user, password, config, Math.max(1, (int) Math.ceil(timeoutMs / 1000.0)), 2_000);
        try (var jdbc = pool.dataSource.getConnection()) {
            jdbc.setNetworkTimeout(java.util.concurrent.ForkJoinPool.commonPool(), Math.max(100, Math.min(120_000, timeoutMs)));
            if ("CONNECTION_TEST".equalsIgnoreCase(mode)) {
                if (!jdbc.isValid(Math.max(1, (int) Math.ceil(timeoutMs / 1000.0)))) {
                    throw new IllegalStateException("数据库连接不可用");
                }
                return Map.of("connected", true, "databaseProduct",
                        Objects.toString(jdbc.getMetaData().getDatabaseProductName(), ""));
            }
            var metadata = jdbc.getMetaData();
            String catalog = jdbc.getCatalog();
            String schema = null;
            try { schema = jdbc.getSchema(); } catch (java.sql.SQLException ignored) { }
            List<Map<String, Object>> items = new ArrayList<>();
            if ("METADATA_TABLES".equalsIgnoreCase(mode)) {
                try (var result = metadata.getTables(catalog, schema, "%", new String[]{"TABLE", "VIEW"})) {
                    while (result.next() && items.size() < 2_000) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", result.getString("TABLE_NAME"));
                        item.put("type", result.getString("TABLE_TYPE"));
                        item.put("remarks", result.getString("REMARKS"));
                        items.add(item);
                    }
                }
                return Map.of("tables", items);
            }
            if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_$]{0,127}")) {
                throw new IllegalArgumentException("数据表名称不合法");
            }
            boolean exists;
            try (var result = metadata.getTables(catalog, schema, tableName, new String[]{"TABLE", "VIEW"})) {
                exists = result.next();
            }
            if (!exists) throw new IllegalArgumentException("所选数据表不存在或不可访问");
            try (var result = metadata.getColumns(catalog, schema, tableName, "%")) {
                while (result.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", result.getString("COLUMN_NAME"));
                    item.put("type", result.getString("TYPE_NAME"));
                    item.put("nullable", result.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls);
                    item.put("ordinal", result.getInt("ORDINAL_POSITION"));
                    items.add(item);
                }
            }
            return Map.of("table", tableName, "columns", items);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("读取数据库元数据失败：" + error.getMessage(), error);
        }
    }

    private QueryPool pool(String url, String user, String password, Map<String, Object> config,
                           int queryTimeout, int maxRows) {
        String key = Integer.toHexString(Objects.hash(url, user, password));
        return pools.computeIfAbsent(key, ignored -> {
            HikariConfig pool = new HikariConfig();
            pool.setJdbcUrl(url);
            pool.setUsername(user);
            pool.setPassword(password);
            String driver = text(config.get("driver"));
            if (!driver.isBlank()) pool.setDriverClassName(driver);
            pool.setPoolName("edsp-provider-db-" + key);
            pool.setMaximumPoolSize(boundedNumber(config.get("maxActive"), 10, 1, 20));
            pool.setMinimumIdle(boundedNumber(config.get("minIdle"), 0, 0, 10));
            pool.setConnectionTimeout(Math.max(1_000, Math.min(15_000, queryTimeout * 1_000L)));
            pool.setReadOnly(true);
            pool.setAutoCommit(true);
            HikariDataSource dataSource = new HikariDataSource(pool);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.setQueryTimeout(queryTimeout);
            jdbc.setMaxRows(maxRows);
            return new QueryPool(dataSource, new NamedParameterJdbcTemplate(jdbc));
        });
    }

    private String resolveSecret(String reference) {
        if (reference.isBlank()) throw new IllegalArgumentException("数据库密码必须配置密钥引用");
        if (reference.startsWith("config://")) {
            String value = configLookup.apply(reference.substring("config://".length()));
            if (value == null || value.isBlank()) throw new IllegalStateException("无法解析数据库密码密钥引用");
            return value;
        }
        if (reference.startsWith("env://")) {
            String value = System.getenv(reference.substring("env://".length()));
            if (value == null || value.isBlank()) throw new IllegalStateException("无法解析数据库密码环境变量引用");
            return value;
        }
        if (reference.startsWith("sys://")) {
            String value = System.getProperty(reference.substring("sys://".length()));
            if (value == null || value.isBlank()) throw new IllegalStateException("无法解析数据库密码系统属性引用");
            return value;
        }
        throw new IllegalArgumentException("数据库密码仅支持 config://、env:// 或 sys:// 密钥引用");
    }

    String password(Map<String, Object> config) {
        String ciphertext = text(config.get("passwordCiphertext"));
        if (!ciphertext.isBlank()) {
            String key = configLookup.apply("EDSP_DATABASE_CREDENTIAL_KEY");
            if (key == null || key.isBlank()) throw new IllegalStateException("数据库密码加密密钥未配置");
            return CryptoSupport.aes256GcmDecrypt(ciphertext, key, "edsp-provider-database");
        }
        String reference = text(config.get("secretReference"));
        return reference.isBlank() ? "" : resolveSecret(reference);
    }

    private static int boundedNumber(Object value, int fallback, int minimum, int maximum) {
        try { return Math.max(minimum, Math.min(maximum, Integer.parseInt(String.valueOf(value)))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    @Override public void close() { pools.values().forEach(pool -> pool.dataSource.close()); pools.clear(); }

    private record QueryPool(HikariDataSource dataSource, NamedParameterJdbcTemplate query) {}
}
