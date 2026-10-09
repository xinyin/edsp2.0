package com.jsnx.edsp.esb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Set;
import java.time.Duration;

@Service
public class EsbMessageAdapter {
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    private final XmlMapper xml = new XmlMapper();
    private final ObjectMapper json;
    private final WebClient gateway;
    private final WebClient admin;
    private final KafkaTemplate<String,String> kafka;
    private final String responseTopic;
    private final Duration adminTimeout;
    private final Duration gatewayTimeout;

    public EsbMessageAdapter(ObjectMapper json, WebClient.Builder builder, KafkaTemplate<String,String> kafka,
                             @Value("${edsp.gateway-uri:http://127.0.0.1:8080}") String gatewayUri,
                             @Value("${edsp.admin-uri:http://127.0.0.1:8081}") String adminUri,
                             @Value("${edsp.esb.response-topic:edsp.esb.response}") String responseTopic,
                             @Value("${edsp.esb.admin-timeout-ms:3000}") long adminTimeoutMs,
                             @Value("${edsp.esb.gateway-timeout-ms:8000}") long gatewayTimeoutMs) {
        this.json=json; this.gateway=builder.baseUrl(gatewayUri).build(); this.admin=builder.baseUrl(adminUri).build(); this.kafka=kafka; this.responseTopic=responseTopic;
        this.adminTimeout = Duration.ofMillis(Math.max(500, adminTimeoutMs));
        this.gatewayTimeout = Duration.ofMillis(Math.max(1000, gatewayTimeoutMs));
    }

    public Map<String, Object> forward(String servicePath, String appId, String apiKey, String xmlRequest) {
        return forward(servicePath, appId, apiKey, xmlRequest,
                java.util.UUID.randomUUID().toString().replace("-", ""), false);
    }

    public Map<String, Object> forward(String servicePath, String appId, String apiKey,
                                       String xmlRequest, String traceId) {
        return forward(servicePath, appId, apiKey, xmlRequest, traceId, false);
    }

    public Map<String, Object> forward(String servicePath, String appId, String apiKey,
                                       String xmlRequest, String traceId, boolean diagnostic) {
        try {
            JsonNode root = xml.readTree(xmlRequest);
            String apiCode = firstText(root, "apiCode", "api_code", "serviceId", "service_id", "interfaceCode", "interface_code");
            String environment = firstText(root, "environment", "env");
            if (environment == null || environment.isBlank()) environment = "TEST";
            String messageTraceId = firstText(root, "traceId", "trace_id", "globalSerialNo", "global_serial_no");
            String effectiveTraceId = traceId == null || traceId.isBlank() ? messageTraceId : traceId;
            if (effectiveTraceId == null || effectiveTraceId.isBlank()) effectiveTraceId = java.util.UUID.randomUUID().toString().replace("-", "");
            Map<String, Object> request = extractBody(root);
            String effectiveServicePath = servicePath;
            if (effectiveServicePath == null || effectiveServicePath.isBlank()) {
                if (apiCode == null || apiCode.isBlank()) throw new IllegalArgumentException("ESB XML 必须包含 apiCode");
                Map<?, ?> route = admin.get().uri(uriBuilder -> uriBuilder.path("/api/internal/interfaces/resolve")
                                .queryParam("apiCode", apiCode).build())
                        .retrieve().bodyToMono(Map.class).timeout(adminTimeout).block();
                effectiveServicePath = route == null ? null : String.valueOf(route.get("service_path"));
                if (effectiveServicePath == null || effectiveServicePath.isBlank()) {
                    throw new IllegalArgumentException("平台未解析到 apiCode 对应的服务路径：" + apiCode);
                }
            }
            Map<?,?> response=gateway.post().uri(effectiveServicePath).contentType(MediaType.APPLICATION_JSON)
                    .header("X-App-Id",appId).header("X-Api-Key",apiKey)
                    .header("X-Trace-Id", effectiveTraceId)
                    .header("X-Api-Code", apiCode == null ? "" : apiCode)
                    .header("X-Environment", environment)
                    .header("X-Entry-Channel", "ESB")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(body ->
                                    new EsbForwardException(clientResponse.statusCode(),
                                            downstreamErrorMessage(clientResponse.statusCode().value(), body), body)))
                    .bodyToMono(Map.class).timeout(gatewayTimeout).block();
            Map<String, Object> standardResponse = response == null ? Map.of() : json.convertValue(response, MAP);
            if (!diagnostic) return standardResponse;
            Map<String, Object> diagnosticResponse = new java.util.LinkedHashMap<>();
            diagnosticResponse.put("standardResponse", standardResponse);
            diagnosticResponse.put("apiCode", apiCode);
            diagnosticResponse.put("servicePath", effectiveServicePath);
            diagnosticResponse.put("traceId", effectiveTraceId);
            diagnosticResponse.put("parsedRequest", request);
            diagnosticResponse.put("xmlRequest", xmlRequest);
            String diagnosticApiCode = apiCode == null ? "" : apiCode;
            String diagnosticServicePath = effectiveServicePath == null ? "" : effectiveServicePath;
            diagnosticResponse.put("nodes", java.util.List.of(
                    node("ESB_QUEUE", "ESB 请求队列", "SUCCESS", Map.of("protocol", "XML", "queued", true), Map.of("traceId", effectiveTraceId)),
                    node("ESB_ADAPTER", "ESB Adapter", "SUCCESS", Map.of("apiCode", diagnosticApiCode, "xml", xmlRequest), Map.of("servicePath", diagnosticServicePath, "json", request)),
                    node("GATEWAY", "统一 Gateway", "SUCCESS", Map.of("apiCode", diagnosticApiCode, "body", request), Map.of("route", diagnosticServicePath)),
                    node("DATA_APPLICATION", "数据源应用", "SUCCESS", Map.of("apiCode", diagnosticApiCode, "body", request), Map.of("upstreamCall", true)),
                    node("SUPPLIER", "上游供应商", "SUCCESS", request, Map.of("responseReceived", true)),
                    node("STANDARD_RESPONSE", "标准 JSON 响应", "SUCCESS", standardResponse, standardResponse)
            ));
            return diagnosticResponse;
        } catch (EsbForwardException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("ESB 报文转换或转发失败: " + e.getMessage(), e);
        }
    }

    private String downstreamErrorMessage(int status, String body) {
        try {
            Map<String, Object> error = json.readValue(body, MAP);
            Object message = error.get("message");
            if (message != null && !String.valueOf(message).isBlank()) {
                return "Gateway 转发失败（HTTP " + status + "）：" + message;
            }
        } catch (Exception ignored) {
            // Non-JSON responses are still returned in the diagnostic details.
        }
        return "Gateway 转发失败（HTTP " + status + "）";
    }

    private Map<String, Object> node(String key, String name, String status, Object input, Object output) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("key", key);
        node.put("name", name);
        node.put("category", "PLATFORM");
        node.put("status", status);
        node.put("detail", "ESB 真实链路诊断节点");
        node.put("durationMs", 1);
        node.put("input", input == null ? Map.of() : input);
        node.put("output", output == null ? Map.of() : output);
        return node;
    }

    private String firstText(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
            JsonNode request = root.get("request");
            if (request != null && request.isObject()) {
                value = request.get(name);
                if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
            }
        }
        return null;
    }

    private Map<String, Object> extractBody(JsonNode root) {
        for (String name : Set.of("body", "requestBody", "params", "data", "payload")) {
            JsonNode candidate = root.get(name);
            if (candidate != null && candidate.isObject()) return json.convertValue(candidate, MAP);
            JsonNode request = root.get("request");
            if (request != null && request.isObject()) {
                candidate = request.get(name);
                if (candidate != null && candidate.isObject()) return json.convertValue(candidate, MAP);
            }
        }
        Map<String, Object> fallback = json.convertValue(root, MAP);
        for (String metadata : Set.of("apiCode", "api_code", "serviceId", "service_id", "interfaceCode", "interface_code",
                "traceId", "trace_id", "globalSerialNo", "global_serial_no")) fallback.remove(metadata);
        Object nestedRequest = fallback.get("request");
        if (nestedRequest instanceof Map<?, ?> nested) return json.convertValue(nested, MAP);
        return fallback;
    }

    @KafkaListener(topics="${edsp.esb.request-topic:edsp.esb.request}", groupId="${spring.application.name}", autoStartup="${KAFKA_ENABLED:false}")
    public void consume(String payload) throws Exception {
        Map<String,Object> envelope=json.readValue(payload,MAP);
        Map<String,Object> response=forward(String.valueOf(envelope.get("servicePath")),String.valueOf(envelope.get("appId")),String.valueOf(envelope.get("apiKey")),String.valueOf(envelope.get("xml")),String.valueOf(envelope.getOrDefault("requestId",java.util.UUID.randomUUID().toString().replace("-", ""))));
        kafka.send(responseTopic,String.valueOf(envelope.getOrDefault("requestId","unknown")),json.writeValueAsString(response));
    }
}
