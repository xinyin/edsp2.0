package com.jsnx.edsp.web;

import com.jsnx.edsp.model.ApiModels.LoginRequest;
import com.jsnx.edsp.model.ApiModels.LoginResult;
import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.service.PublishedPlanRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChainTestToolControllerTest {
    @Test
    void rejectsMissingSessionAndForgedTokenBeforeCallingAdapter() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ChainTestToolController(
                RestClient.builder(), "http://127.0.0.1:1", "internal-key", "api-key")).build();
        mvc.perform(post("/api/test-tools/chain").contentType(MediaType.APPLICATION_XML).content("<Service/>"))
                .andExpect(status().isUnauthorized());
        var session = new MockHttpSession();
        session.setAttribute(ChainTestToolController.SESSION_TOKEN, "issued-token");
        mvc.perform(post("/api/test-tools/chain").session(session)
                        .header("X-Edsp-Operator-Token", "forged-token")
                        .contentType(MediaType.APPLICATION_XML).content("<Service/>"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedOperatorUsesBackendCredentialAndPreservesFailureTrace() throws Exception {
        var credential = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/esb/queue/request", exchange -> {
            credential.set(exchange.getRequestHeaders().getFirst("X-Edsp-Internal-Test-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"code\":\"TEST_EXECUTION_FAILED\",\"traceNodes\":[{\"status\":\"ERROR\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new ChainTestToolController(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "backend-only-key", "api-key")).build();
            var session = new MockHttpSession();
            session.setAttribute(ChainTestToolController.SESSION_TOKEN, "issued-token");
            mvc.perform(post("/api/test-tools/chain").session(session)
                            .header("X-Edsp-Operator-Token", "issued-token")
                            .contentType(MediaType.APPLICATION_XML).content("<Service/>"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.traceNodes[0].status").value("ERROR"));
            assertEquals("backend-only-key", credential.get());
            assertEquals("<Service/>", body.get());
        } finally { server.stop(0); }
    }

    @Test
    void loginRotatesSessionAndIssuesUniqueOperatorToken() {
        var service = mock(InterfaceService.class);
        var request = new LoginRequest("admin", "admin123");
        when(service.login(request)).thenReturn(new LoginResult("old-fixed-token", "管理员", "管理员", "省联社"));
        var controller = new ApiController(service, mock(PublishedPlanRegistry.class));
        var http = new MockHttpServletRequest();
        var oldSession = http.getSession();
        var result = controller.login(request, http);
        assertNotEquals("old-fixed-token", result.token());
        assertNotSame(oldSession, http.getSession());
        assertEquals(result.token(), http.getSession().getAttribute(ChainTestToolController.SESSION_TOKEN));
        assertNotEquals(result.token(), controller.login(request, new MockHttpServletRequest()).token());
    }
}
