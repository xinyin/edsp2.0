package com.jsnx.edsp.esb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.time.Duration;

@Service
public class EsbMessageAdapter {
    private static final TypeReference<Map<String,Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper json;
    private final EsbXmlProtocol xmlProtocol;
    private final WebClient gateway;
    private final WebClient admin;
    private final KafkaTemplate<String,String> kafka;
    private final String responseTopic;
    private final Duration adminTimeout;
    private final Duration gatewayTimeout;
    private final String environment;
    private final String internalTestKey;

    public EsbMessageAdapter(ObjectMapper json, EsbXmlProtocol xmlProtocol,
                             WebClient.Builder builder, KafkaTemplate<String,String> kafka,
                             @Value("${edsp.gateway-uri:http://127.0.0.1:8080}") String gatewayUri,
                             @Value("${edsp.admin-uri:http://127.0.0.1:8081}") String adminUri,
                             @Value("${edsp.esb.response-topic:edsp.esb.response}") String responseTopic,
                             @Value("${edsp.esb.environment:TEST}") String environment,
                             @Value("${edsp.esb.admin-timeout-ms:3000}") long adminTimeoutMs,
                             @Value("${edsp.esb.gateway-timeout-ms:8000}") long gatewayTimeoutMs,
                             @Value("${edsp.test-tool.internal-key:demo-internal-test-key}") String internalTestKey) {
        this.json=json; this.xmlProtocol=xmlProtocol; this.gateway=builder.baseUrl(gatewayUri).build(); this.admin=builder.baseUrl(adminUri).build(); this.kafka=kafka; this.responseTopic=responseTopic; this.environment=environment;
        this.adminTimeout = Duration.ofMillis(Math.max(500, adminTimeoutMs));
        this.gatewayTimeout = Duration.ofMillis(Math.max(1000, gatewayTimeoutMs));
        this.internalTestKey = internalTestKey;
    }

    public EsbForwardResult forward(String servicePath, String appId, String apiKey, String xmlRequest) {
        return forward(servicePath, appId, apiKey, xmlRequest,
                java.util.UUID.randomUUID().toString().replace("-", ""), false);
    }

    public EsbForwardResult forward(String servicePath, String appId, String apiKey,
                                       String xmlRequest, String traceId) {
        return forward(servicePath, appId, apiKey, xmlRequest, traceId, false);
    }

    public EsbForwardResult forward(String servicePath, String appId, String apiKey,
                                       String xmlRequest, String traceId, boolean diagnostic) {
        try {
            long parseStarted = System.nanoTime();
            EsbRequestEnvelope envelope = xmlProtocol.parse(xmlRequest);
            String apiCode = envelope.serviceId();
            String effectiveTraceId = envelope.serviceSn();
            Map<String, Object> request = envelope.requestBody();
            long parseDurationMs = elapsedMs(parseStarted);
            String effectiveServicePath = servicePath;
            if (effectiveServicePath == null || effectiveServicePath.isBlank()) {
                if (apiCode == null || apiCode.isBlank()) throw new IllegalArgumentException("ESB XML 必须包含 service_id");
                Map<?, ?> route = admin.get().uri(uriBuilder -> uriBuilder.path("/api/internal/interfaces/resolve")
                                .queryParam("apiCode", apiCode)
                                .queryParam("includeDraft", diagnostic).build())
                        .retrieve().bodyToMono(Map.class).timeout(adminTimeout).block();
                effectiveServicePath = route == null ? null : String.valueOf(route.get("service_path"));
                if (effectiveServicePath == null || effectiveServicePath.isBlank()) {
                    throw new IllegalArgumentException("平台未解析到 apiCode 对应的服务路径：" + apiCode);
                }
            }
            WebClient.RequestBodySpec gatewayRequest = gateway.post().uri(effectiveServicePath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-App-Id",appId).header("X-Api-Key",apiKey)
                    .header("X-Trace-Id", effectiveTraceId)
                    .header("X-Api-Code", apiCode == null ? "" : apiCode)
                    .header("X-Environment", environment)
                    .header("X-Entry-Channel", "ESB");
            if (diagnostic) {
                gatewayRequest = gatewayRequest
                        .header("X-Edsp-Execution-Scope", "DRAFT_TEST")
                        .header("X-Edsp-Internal-Test-Key", internalTestKey);
            }
            long gatewayStarted = System.nanoTime();
            Map<?,?> response=gatewayRequest
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).defaultIfEmpty("").map(body ->
                                    new EsbForwardException(clientResponse.statusCode(),
                                            downstreamErrorMessage(clientResponse.statusCode().value(), body), body)))
                    .bodyToMono(Map.class).timeout(gatewayTimeout).block();
            long gatewayDurationMs = elapsedMs(gatewayStarted);
            Map<String, Object> execution = response == null ? Map.of() : json.convertValue(response, MAP);
            Map<String, Object> standardResponse = diagnostic && execution.get("standardResponse") instanceof Map<?, ?> body
                    ? json.convertValue(body, MAP) : execution;
            long xmlStarted = System.nanoTime();
            String xmlResponse = xmlProtocol.success(envelope, standardResponse);
            long xmlDurationMs = elapsedMs(xmlStarted);
            Map<String, Object> diagnosticResponse = new java.util.LinkedHashMap<>();
            diagnosticResponse.put("standardResponse", standardResponse);
            diagnosticResponse.put("xmlResponse", xmlResponse);
            diagnosticResponse.put("apiCode", apiCode);
            diagnosticResponse.put("servicePath", effectiveServicePath);
            diagnosticResponse.put("traceId", effectiveTraceId);
            diagnosticResponse.put("parsedRequest", request);
            diagnosticResponse.put("xmlRequest", xmlRequest);
            diagnosticResponse.put("upstreamRequest", execution.getOrDefault("upstreamRequest", Map.of()));
            diagnosticResponse.put("upstreamResponse", execution.getOrDefault("upstreamResponse", Map.of()));
            String diagnosticApiCode = apiCode == null ? "" : apiCode;
            String diagnosticServicePath = effectiveServicePath == null ? "" : effectiveServicePath;
            var nodes = new java.util.ArrayList<Map<String, Object>>();
            nodes.add(node("ESB_ADAPTER", "ESB Adapter", "SUCCESS", parseDurationMs,
                    Map.of("apiCode", diagnosticApiCode, "xml", xmlRequest), Map.of("servicePath", diagnosticServicePath, "json", request)));
            nodes.add(node("GATEWAY", "统一 Gateway", "SUCCESS", gatewayDurationMs,
                    Map.of("apiCode", diagnosticApiCode, "body", request),
                    Map.of("route", diagnosticServicePath, "executionScope", diagnostic ? "DRAFT_TEST" : "PUBLISHED")));
            if (diagnostic && execution.get("traceNodes") instanceof java.util.List<?> actualNodes) {
                for (Object raw : actualNodes) {
                    if (raw instanceof Map<?, ?> actual && !java.util.Set.of("DOWNSTREAM", "GATEWAY").contains(actual.get("key"))) {
                        nodes.add(json.convertValue(actual, MAP));
                    }
                }
            }
            nodes.add(node("STANDARD_RESPONSE", "ESB XML 响应", "SUCCESS", xmlDurationMs, standardResponse,
                    Map.of("contentType", MediaType.APPLICATION_XML_VALUE, "xml", xmlResponse)));
            diagnosticResponse.put("nodes", nodes);
            return new EsbForwardResult(envelope, standardResponse, xmlResponse,
                    diagnostic ? diagnosticResponse : Map.of());
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

    private long elapsedMs(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }

    private Map<String, Object> node(String key, String name, String status, long durationMs, Object input, Object output) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("key", key);
        node.put("name", name);
        node.put("category", "PLATFORM");
        node.put("status", status);
        node.put("detail", "ESB 真实链路诊断节点");
        node.put("durationMs", durationMs);
        node.put("input", input == null ? Map.of() : input);
        node.put("output", output == null ? Map.of() : output);
        return node;
    }

    @KafkaListener(topics="${edsp.esb.request-topic:edsp.esb.request}", groupId="${spring.application.name}", autoStartup="${KAFKA_ENABLED:false}")
    public void consume(String payload) throws Exception {
        Map<String,Object> envelope=json.readValue(payload,MAP);
        EsbForwardResult result=forward(String.valueOf(envelope.get("servicePath")),String.valueOf(envelope.get("appId")),String.valueOf(envelope.get("apiKey")),String.valueOf(envelope.get("xml")),String.valueOf(envelope.getOrDefault("requestId",java.util.UUID.randomUUID().toString().replace("-", ""))));
        kafka.send(responseTopic,String.valueOf(envelope.getOrDefault("requestId","unknown")),json.writeValueAsString(result.standardResponse()));
    }
}
