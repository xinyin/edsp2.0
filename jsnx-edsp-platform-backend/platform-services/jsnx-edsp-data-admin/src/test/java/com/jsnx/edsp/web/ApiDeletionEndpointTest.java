package com.jsnx.edsp.web;

import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.service.PublishedPlanRegistry;
import com.jsnx.edsp.model.ApiModels.CallTraceDetail;
import com.jsnx.edsp.model.ApiModels.TraceNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class ApiDeletionEndpointTest {
    private InterfaceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InterfaceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApiController(service, new PublishedPlanRegistry())).build();
    }

    @Test
    void deleteIsAnAcceptedHttpMethod() throws Exception {
        mvc.perform(options("/api/interfaces/42"))
                .andExpect(status().isOk())
                .andExpect(header().string("Allow", containsString("DELETE")));
        mvc.perform(delete("/api/interfaces/42"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).deleteInterface(42L);
    }

    @Test
    void deletionGuardReturnsAnActionableMessage() throws Exception {
        doThrow(new IllegalStateException("仅支持删除从未发布的草稿 API"))
                .when(service).deleteInterface(42L);
        mvc.perform(delete("/api/interfaces/42"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("message").value("仅支持删除从未发布的草稿 API"));
    }

    @Test
    void directPublishIsExposedWithoutRequiringAnApprovalRequest() throws Exception {
        mvc.perform(post("/api/interfaces/42/direct-publish")
                        .contentType("application/json")
                        .content("{\"releaseNote\":\"联调通过\",\"releasedBy\":\"平台管理员\"}"))
                .andExpect(status().isOk());
        verify(service).publishDirect(eq(42L), any());
        verify(service, never()).submitApproval(eq(42L), any());
    }

    @Test
    void dataSourceSettingsCanUpdateTheSharedEndpointAndTimeout() throws Exception {
        mvc.perform(put("/api/data-sources/7/settings")
                        .contentType("application/json")
                        .content("{\"providerId\":3,\"sourceName\":\"企业数据源\",\"protocolType\":\"HTTP_JSON\",\"endpoint\":\"https://provider.example.com/open\",\"defaultTimeoutMs\":5000}"))
                .andExpect(status().isOk());
        verify(service).saveDataSourceSettings(eq(7L), argThat(request ->
                request.providerId() == 3L
                        && request.sourceName().equals("企业数据源")
                        && request.protocolType().equals("HTTP_JSON")
                        && request.endpoint().equals("https://provider.example.com/open")
                        && request.defaultTimeoutMs() == 5000));
    }

    @Test
    void resourceApiMetadataCanBeEditedAfterCreation() throws Exception {
        mvc.perform(put("/api/resource-apis/7/metadata")
                        .contentType("application/json")
                        .content("{\"resourceApiCode\":\"4093_QUERY_V2\",\"resourceApiName\":\"工商查询新版\",\"httpMethod\":\"GET\"}"))
                .andExpect(status().isOk());
        verify(service).updateResourceApiMetadata(eq(7L), argThat(request ->
                request.resourceApiCode().equals("4093_QUERY_V2")
                        && request.resourceApiName().equals("工商查询新版")
                        && request.httpMethod().equals("GET")));
    }

    @Test
    void resourceApiMetadataRejectsSpecialCharactersInCode() throws Exception {
        mvc.perform(put("/api/resource-apis/7/metadata")
                        .contentType("application/json")
                        .content("{\"resourceApiCode\":\"4093 API@V2\",\"resourceApiName\":\"工商查询新版\",\"httpMethod\":\"GET\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).updateResourceApiMetadata(eq(7L), any());
    }

    @Test
    void testExecutionFailureReturnsTraceAndActionableStatusInsteadOfGeneric500() throws Exception {
        String traceId = "trace-123";
        when(service.test(eq(42L), any())).thenThrow(
                new InterfaceService.TrackedCallException(traceId, "上游连接超时", new RuntimeException("timeout")));
        when(service.callTrace(traceId)).thenReturn(new CallTraceDetail(null, java.util.List.of(
                new TraceNode("VENDOR", "上游供应商", "EXTERNAL", "ERROR", "上游连接超时", 1000,
                        java.util.Map.of("url", "https://provider.example"), java.util.Map.of("message", "timeout")))));

        mvc.perform(post("/api/interfaces/42/test")
                        .contentType("application/json")
                        .content("{\"request\":{},\"testMode\":\"PLATFORM\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("code").value("TEST_EXECUTION_FAILED"))
                .andExpect(jsonPath("message").value("上游连接超时"))
                .andExpect(jsonPath("traceId").value(traceId))
                .andExpect(jsonPath("traceNodes[0].key").value("VENDOR"))
                .andExpect(jsonPath("traceNodes[0].status").value("ERROR"));
    }
}
