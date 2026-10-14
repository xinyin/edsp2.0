package com.jsnx.edsp.web;

import com.jsnx.edsp.service.InterfaceService;
import com.jsnx.edsp.model.ApiModels.TestResult;
import com.jsnx.edsp.model.ApiModels.TraceNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Internal target for the ESB/Gateway full-chain test tool. */
@RestController
@RequestMapping("/api/internal/test-chain")
public class InternalChainTestController {
    public static final String TEST_KEY_HEADER = "X-Edsp-Internal-Test-Key";

    private final InterfaceService service;
    private final String internalTestKey;

    public InternalChainTestController(InterfaceService service,
            @Value("${edsp.test-tool.internal-key:demo-internal-test-key}") String internalTestKey) {
        this.service = service;
        this.internalTestKey = internalTestKey;
    }

    @PostMapping("/{apiCode}")
    public TestResult execute(@PathVariable String apiCode,
            @RequestHeader(value = TEST_KEY_HEADER, required = false) String suppliedKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody Map<String, Object> request) {
        if (!constantTimeEquals(internalTestKey, suppliedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "非法的内部链路测试请求");
        }
        return service.testDraftChain(apiCode, request, traceId);
    }

    @ExceptionHandler(InterfaceService.TrackedCallException.class)
    public ResponseEntity<Map<String, Object>> failedExecution(InterfaceService.TrackedCallException error) {
        List<TraceNode> nodes = List.of();
        try { nodes = service.callTrace(error.traceId()).nodes(); }
        catch (RuntimeException ignored) { /* Preserve the original execution failure. */ }
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "TEST_EXECUTION_FAILED", "resultStatus", "ERROR",
                "message", error.getMessage() == null ? "链路测试执行失败" : error.getMessage(),
                "traceId", error.traceId(), "traceNodes", nodes));
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank() || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
