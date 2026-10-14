package com.jsnx.edsp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.model.ApiModels.TestResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalChainTestControllerTest {
    private final InterfaceService service = mock(InterfaceService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(
                new InternalChainTestController(service, "internal-secret")).build();
    }

    @Test
    void executesSavedDraftWithoutPublicationOrAuthorizationInputs() throws Exception {
        when(service.testDraftChain(eq("TYGJJ_API_01"), anyMap(), eq("TRACE-001")))
                .thenReturn(new TestResult(Map.of(), Map.of(), Map.of("code", "0000", "resultStatus", "HIT"), List.of(), List.of()));

        mvc.perform(post("/api/internal/test-chain/TYGJJ_API_01")
                        .header(InternalChainTestController.TEST_KEY_HEADER, "internal-secret")
                        .header("X-Trace-Id", "TRACE-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("query_key", "DEMO-001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.standardResponse.code").value("0000"));

        verify(service).testDraftChain(eq("TYGJJ_API_01"), anyMap(), eq("TRACE-001"));
    }

    @Test
    void rejectsCallsWithoutInternalCredential() throws Exception {
        mvc.perform(post("/api/internal/test-chain/TYGJJ_API_01")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }
}
