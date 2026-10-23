package com.jsnx.edsp.sdk;

import com.jsnx.edsp.plan.SqlQuerySupport;
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
        if (!"SQL".equalsIgnoreCase(text(query.get("executionMode")))) {
            throw new IllegalArgumentException("数据库资源 API 尚未配置 SQL 查询模式");
        }
        String sql = text(query.get("sqlTemplate"));
        Set<String> names = SqlQuerySupport.validateAndFindParameters(sql);
        Map<String, Object> values = request == null ? Map.of() : request;
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (String name : names) {
            if (!values.containsKey(name)) throw new IllegalArgumentException("SQL 查询缺少请求参数：" + name);
            Object value = values.get(name);
            if (value instanceof Map<?, ?>) throw new IllegalArgumentException("SQL 参数不支持对象值：" + name);
            parameters.put(name, value);
        }

        Map<String, Object> config = map(connection.get("accessConfig"));
        String url = text(config.get("jdbcUrl"));
        String user = text(config.get("username"));
        if (!url.startsWith("jdbc:") || user.isBlank()) throw new IllegalStateException("数据库连接配置不完整");
        String password = resolveSecret(text(config.get("secretReference")));
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
