package com.jsnx.edsp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class ApiSchemaMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ApiSchemaMigration.class);
    private static final String MIGRATION_LOCK = "edsp_admin_schema_migration";

    private final JdbcTemplate jdbc;

    public ApiSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                lock.setString(1, MIGRATION_LOCK);
                try (ResultSet result = lock.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new SQLException("无法获取 EDSP 数据库结构迁移锁");
                    }
                }
            }
            try {
                int columnCount;
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("""
                             SELECT COUNT(*) FROM information_schema.columns
                             WHERE table_schema = DATABASE()
                               AND table_name = 'api_interface'
                               AND column_name = 'upstream_request_example'
                             """)) {
                    result.next();
                    columnCount = result.getInt(1);
                }
                if (columnCount == 0) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE api_interface ADD COLUMN upstream_request_example JSON NULL AFTER request_example");
                        statement.executeUpdate("UPDATE api_interface SET upstream_request_example=request_example WHERE upstream_request_example IS NULL");
                    }
                    log.info("Migrated api_interface.upstream_request_example without changing existing interface data");
                }
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, MIGRATION_LOCK);
                    release.executeQuery().close();
                }
            }
            return null;
        });
    }
}
