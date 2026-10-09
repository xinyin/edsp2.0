package com.jsnx.edsp.esb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/esb")
public class EsbAdapterController {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final MockEsbQueue queue;
    private final ObjectMapper json;

    public EsbAdapterController(MockEsbQueue queue, ObjectMapper json) {
        this.queue = queue;
        this.json = json;
    }

    @PostMapping(value = {"/queue/request", "/forward"},
            consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> enqueue(@RequestParam(required = false, defaultValue = "") String servicePath,
                                       @RequestHeader("X-App-Id") String appId,
                                       @RequestHeader("X-Api-Key") String apiKey,
                                       @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                       @RequestParam(defaultValue = "false") boolean diagnostic,
                                       @RequestBody String xml) {
        return queue.requestReply(servicePath, appId, apiKey, traceId, xml, diagnostic);
    }

    @GetMapping("/queue/status")
    public Map<String, Object> status() { return queue.status(); }

    @ExceptionHandler(EsbForwardException.class)
    public ResponseEntity<Map<String, Object>> handleForwardFailure(EsbForwardException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "ESB_FORWARD_" + error.status().value());
        payload.put("message", error.getMessage());
        payload.put("resultStatus", "ERROR");
        if (error.responseBody() != null && !error.responseBody().isBlank()) {
            try {
                payload.put("gatewayError", json.readValue(error.responseBody(), MAP));
            } catch (Exception ignored) {
                payload.put("gatewayError", error.responseBody());
            }
        }
        return ResponseEntity.status(error.status()).body(payload);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleAdapterFailure(RuntimeException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "ESB_ADAPTER_ERROR",
                "message", error.getMessage() == null ? "ESB 报文处理失败" : error.getMessage(),
                "resultStatus", "ERROR"));
    }
}
