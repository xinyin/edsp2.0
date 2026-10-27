package com.jsnx.edsp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.model.ApiModels.FieldMapping;
import com.jsnx.edsp.model.ApiModels.InterfaceDetail;
import com.jsnx.edsp.model.ApiModels.InterfaceSummary;
import com.jsnx.edsp.model.ApiModels.ResponseCodeMapping;
import com.jsnx.edsp.model.ApiModels.SecurityPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jsnx.edsp.model.ApiModels.TestRequest;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.withSettings;

class UpstreamRequestContractValidationTest {
    @Test
    void rejectsAPlanThatDropsAFieldRequiredByTheSupplierContract() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().mockMaker("mock-maker-subclass"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        InterfaceService service = spy(new InterfaceService(jdbc, new JsonSupport(mapper), mapper,
                new PublishedPlanRegistry(), new MockEnvironment()));
        InterfaceSummary summary = new InterfaceSummary(42, 7, "LHZX_QUERY_01_SERVICE_01", "联合征信查询",
                "/lhzx/v1/query/01", "POST", "http://127.0.0.1:8090/vendor/lhzx/query", "/vendor/lhzx/query",
                "OVERRIDE", "DIRECT", null, 3000, "READY", "DRAFT", 4, 3,
                LocalDateTime.now(), 9L, "LHZX_QUERY_01", "联合征信资源查询");
        InterfaceDetail detail = new InterfaceDetail(summary, Map.of(), Map.of(),
                Map.of("query_key", "DEMO-001", "query_reason", "业务查询"),
                Map.of("query_key", "DEMO-001", "query_reason", "业务查询"),
                Map.of("result", Map.of("code", "0000")), Map.of(), 3000, null,
                "DATA_SOURCE", 0, "result.code",
                List.of(new FieldMapping(1L, "REQUEST", "query_reason", "query_reason",
                        "DIRECT", Map.of(), null, true, 10)),
                List.of(new ResponseCodeMapping(1L, "0000", "EXT-SUCCESS", "HIT", true)),
                new SecurityPolicy(42, false, "NONE", "NONE", "", "", "", "signature", ""));
        doReturn(detail).when(service).interfaceDetail(42);

        var result = service.validate(42);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).contains("转换后的上游请求缺少供应商必填字段：query_key");
        assertThatThrownBy(() -> service.test(42,
                new TestRequest(detail.requestExample(), detail.upstreamResponseExample(), false, detail.mappings(), "PLATFORM")))
                .isInstanceOf(InterfaceService.TrackedCallException.class)
                .hasMessageContaining("缺少供应商必填字段：query_key");
    }
}
