package com.jsnx.edsp.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MySQL-backed consumer for the local Kafka simulation used by the Demo. */
@Service
public class DemoEventIngestionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DemoEventIngestionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void consumeCall(Map<String, Object> event) {
        String traceId = text(event.get("traceId"));
        String interfaceCode = text(event.get("interfaceCode"));
        if (traceId.isBlank() || interfaceCode.isBlank()) {
            throw new IllegalArgumentException("traceId 和 interfaceCode 不能为空");
        }
        Long interfaceId = jdbc.queryForObject(
                "SELECT id FROM api_interface WHERE interface_code=?", Long.class, interfaceCode);
        List<Long> clientIds = jdbc.queryForList(
                "SELECT id FROM api_client WHERE client_code=?", Long.class, text(event.get("clientCode")));
        Long clientId = clientIds.isEmpty() ? null : clientIds.get(0);
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT unit_price,hit_billable,miss_billable FROM billing_rule WHERE interface_id=? AND status='ACTIVE'",
                interfaceId);
        BigDecimal unitPrice = rules.isEmpty() ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(rules.get(0).get("unit_price")));
        boolean billable = bool(event.get("billable"));
        BigDecimal amount = billable ? unitPrice : BigDecimal.ZERO;
        jdbc.update("""
                INSERT INTO usage_record(trace_id,client_id,interface_id,result_status,standard_code,billable,
                unit_price,amount,duration_ms,upstream_duration_ms,gateway_duration_ms,request_status,node_name,called_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(3))
                ON DUPLICATE KEY UPDATE result_status=VALUES(result_status),standard_code=VALUES(standard_code),
                billable=VALUES(billable),unit_price=VALUES(unit_price),amount=VALUES(amount),
                duration_ms=VALUES(duration_ms),upstream_duration_ms=VALUES(upstream_duration_ms),
                request_status=VALUES(request_status),node_name=VALUES(node_name)
                """, traceId, clientId, interfaceId, text(event.get("resultStatus")), text(event.get("responseCode")),
                billable, unitPrice, amount, number(event.get("durationMs")), number(event.get("upstreamDurationMs")),
                1, text(event.get("requestStatus")), text(event.get("application")));

        List<Map<String, Object>> nodes = event.get("nodes") instanceof List<?> values
                ? mapper.convertValue(values, List.class) : new ArrayList<>();
        int order = 10;
        for (Map<String, Object> node : nodes) {
            saveNode(traceId, node, order);
            order += 10;
        }
        saveNode(traceId, Map.of(
                "nodeKey", "BILLING", "nodeName", "计量计费", "status", "SUCCESS", "durationMs", 1,
                "input", Map.of("resultStatus", text(event.get("resultStatus")), "billable", billable),
                "output", Map.of("unitPrice", unitPrice, "amount", amount)), order);
        saveEvent("DATA_APPLICATION_CALL", traceId, event);
    }

    public void consumeGateway(Map<String, Object> event) {
        saveEvent("GATEWAY_API_CALL", text(event.get("traceId")), event);
    }

    private void saveNode(String traceId, Map<String, Object> node, int order) {
        String status = text(node.get("status"));
        jdbc.update("""
                INSERT INTO api_call_node_log(trace_id,node_key,node_name,node_category,node_status,node_detail,
                duration_ms,input_data,output_data,sort_order,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,NOW(3))
                ON DUPLICATE KEY UPDATE node_status=VALUES(node_status),node_detail=VALUES(node_detail),
                duration_ms=VALUES(duration_ms),input_data=VALUES(input_data),output_data=VALUES(output_data),
                sort_order=VALUES(sort_order)
                """, traceId, text(node.get("nodeKey")), text(node.get("nodeName")), category(node),
                status, "ERROR".equals(status) ? "节点执行失败" : "节点执行完成", number(node.get("durationMs")),
                json(node.get("input")), json(node.get("output")), order);
    }

    private void saveEvent(String type, String traceId, Map<String, Object> event) {
        jdbc.update("INSERT INTO demo_async_event(event_type,trace_id,payload,consume_status) VALUES(?,?,?,'CONSUMED')",
                type, traceId.isBlank() ? null : traceId, json(event));
    }

    private String category(Map<String, Object> node) {
        String key = text(node.get("nodeKey"));
        if (key.startsWith("ESB")) return "ESB";
        if ("GATEWAY".equals(key)) return "GATEWAY";
        if ("UPSTREAM".equals(key)) return "EXTERNAL";
        return "DATA_APPLICATION";
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception error) { return "{}"; }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean bool(Object value) { return Boolean.parseBoolean(text(value)); }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
}
