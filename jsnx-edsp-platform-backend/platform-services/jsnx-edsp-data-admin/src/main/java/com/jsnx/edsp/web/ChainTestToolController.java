package com.jsnx.edsp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Operator-only entry; service credentials never reach the browser. */
@RestController
@RequestMapping("/api/test-tools")
public class ChainTestToolController {
    static final String SESSION_TOKEN = "edsp.operator.test-token";
    private final RestClient adapter;
    private final String internalKey;
    private final String apiKey;

    public ChainTestToolController(RestClient.Builder builder,
            @Value("${edsp.esb-adapter-url:http://127.0.0.1:8084}") String adapterUrl,
            @Value("${edsp.test-tool.internal-key:demo-internal-test-key}") String internalKey,
            @Value("${edsp.auth.bootstrap-api-key:demo-secret-key}") String apiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(60000);
        this.adapter = builder.requestFactory(factory).baseUrl(adapterUrl).build();
        this.internalKey = internalKey;
        this.apiKey = apiKey;
    }

    @PostMapping(value = "/chain", consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> execute(HttpServletRequest http,
            @RequestHeader(value = "X-Edsp-Operator-Token", required = false) String token,
            @RequestHeader(value = "X-Trace-Id", required = false, defaultValue = "") String traceId,
            @RequestBody String xml) {
        var session = http.getSession(false);
        Object stored = session == null ? null : session.getAttribute(SESSION_TOKEN);
        if (!(stored instanceof String expected) || token == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录后使用链路测试工具");
        }
        return adapter.post().uri("/esb/queue/request?diagnostic=true")
                .contentType(MediaType.APPLICATION_XML)
                .header("X-App-Id", "EDSP_TEST_TOOL")
                .header("X-Api-Key", apiKey)
                .header("X-Edsp-Internal-Test-Key", internalKey)
                .header("X-Trace-Id", traceId)
                .body(xml).exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .contentType(MediaType.APPLICATION_JSON).body(response.bodyTo(String.class)));
    }
}
