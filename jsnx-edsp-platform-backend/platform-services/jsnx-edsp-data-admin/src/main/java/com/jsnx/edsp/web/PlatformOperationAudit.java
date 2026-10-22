package com.jsnx.edsp.web;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlatformOperationAudit implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(PlatformOperationAudit.class);
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final JdbcTemplate jdbc;

    public PlatformOperationAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS platform_operation_audit (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    operator_name VARCHAR(64) NOT NULL,
                    action VARCHAR(16) NOT NULL,
                    target_path VARCHAR(512) NOT NULL,
                    target_type VARCHAR(64) NOT NULL,
                    outcome VARCHAR(16) NOT NULL,
                    http_status INT NOT NULL,
                    trace_id VARCHAR(64),
                    client_ip VARCHAR(64),
                    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    INDEX idx_audit_time (occurred_at, id),
                    INDEX idx_audit_operator_time (operator_name, occurred_at),
                    INDEX idx_audit_target_time (target_type, occurred_at)
                )
                """);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
        String path = request.getRequestURI();
        if (!shouldAudit(request.getMethod(), path)) return;
        var session = request.getSession(false);
        Object operator = session == null ? null : session.getAttribute("edsp.operator.username");
        int status = response.getStatus();
        try {
            jdbc.update("""
                    INSERT INTO platform_operation_audit(operator_name,action,target_path,target_type,outcome,http_status,trace_id,client_ip)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, operator == null ? "UNKNOWN" : String.valueOf(operator), request.getMethod(), path,
                    targetType(path), error == null && status < 400 ? "SUCCESS" : "FAILURE", status,
                    request.getHeader("X-Trace-Id"), request.getRemoteAddr());
        } catch (Exception auditError) {
            log.error("Could not persist operation audit for {} {}", request.getMethod(), path, auditError);
        }
    }

    static boolean shouldAudit(String method, String path) {
        return METHODS.contains(method) && path.startsWith("/api/")
                && !path.startsWith("/api/auth/") && !path.startsWith("/api/internal/")
                && !path.startsWith("/api/test-tools/") && !path.endsWith("/test")
                && !path.endsWith("/validate") && !path.endsWith("/verify")
                && !path.endsWith("/diagnostic");
    }

    static String targetType(String path) {
        String[] segments = path.split("/");
        return segments.length > 2 ? segments[2] : "unknown";
    }

    @RestController
    @RequestMapping("/api/operation-audit")
    public static class QueryController {
        private final JdbcTemplate jdbc;
        public QueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @GetMapping
        public List<Map<String, Object>> list(HttpServletRequest request,
                @RequestParam LocalDate from, @RequestParam LocalDate to,
                @RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "50") int size) {
            var session = request.getSession(false);
            if (session == null || session.getAttribute(ChainTestToolController.SESSION_TOKEN) == null)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
            if (!"系统管理员".equals(session.getAttribute("edsp.operator.role")))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅系统管理员可查看操作审计");
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366 || page < 1 || size < 1 || size > 200
                    || (long) (page - 1) * size > Integer.MAX_VALUE)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审计查询范围或分页参数不合法");
            return jdbc.queryForList("""
                    SELECT id,operator_name operatorName,action,target_path targetPath,target_type targetType,
                           outcome,http_status httpStatus,trace_id traceId,client_ip clientIp,occurred_at occurredAt
                    FROM platform_operation_audit WHERE occurred_at>=? AND occurred_at<?
                    ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?
                    """, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), size, (page - 1) * size);
        }
    }
}
