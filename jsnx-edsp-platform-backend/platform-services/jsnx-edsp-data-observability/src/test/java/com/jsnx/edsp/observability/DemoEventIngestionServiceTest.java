package com.jsnx.edsp.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoEventIngestionServiceTest {
    @Test
    void repeatedTraceUsesCurrentClientAndInterfaceAndReplacesOldNodes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("api_interface WHERE interface_code"), eq(Long.class), eq("CRYPT_API_03_SERVICE_01")))
                .thenReturn(33L);
        when(jdbc.queryForList(contains("api_client WHERE client_code"), eq(Long.class), eq("LOAN_APPROVAL")))
                .thenReturn(List.of(2L));
        when(jdbc.queryForList(contains("billing_rule"), eq(33L))).thenReturn(List.of());
        var service = new DemoEventIngestionService(jdbc, new ObjectMapper());

        service.consumeCall(Map.of("traceId", "1112223334445", "interfaceCode", "CRYPT_API_03_SERVICE_01",
                "clientCode", "LOAN_APPROVAL", "resultStatus", "HIT", "responseCode", "0000",
                "application", "test-provider", "nodes", List.of(Map.of("nodeKey", "GATEWAY", "nodeName", "Gateway",
                        "status", "SUCCESS", "durationMs", 1))));

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).update(argThat(sql -> sql.contains("client_id=VALUES(client_id)")
                && sql.contains("interface_id=VALUES(interface_id)")
                && sql.contains("called_at=VALUES(called_at)")),
                eq("1112223334445"), eq(2L), eq(33L), anyString(), anyString(), anyBoolean(),
                any(), any(), anyLong(), anyLong(), anyInt(), anyString(), eq("test-provider"));
        ordered.verify(jdbc).update("DELETE FROM api_call_node_log WHERE trace_id=?", "1112223334445");
        ordered.verify(jdbc, times(2)).update(contains("INSERT INTO api_call_node_log"), any(Object[].class));
    }
}
