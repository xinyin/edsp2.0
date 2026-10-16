package com.jsnx.edsp.sdk;

import com.jsnx.edsp.contracts.CapabilityContracts.CompleteResponse;
import com.jsnx.edsp.contracts.CapabilityContracts.PrepareResponse;
import com.jsnx.edsp.contracts.CapabilityContracts.StandardResponse;
import com.jsnx.edsp.plan.PlanSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed local pipeline used by every independently deployed data-source application. */
public final class StandardDataApplicationExecutor {
    private final LocalPlanRuntime plans;
    private final UpstreamClient upstream;
    private final DemoEventClient events;

    public StandardDataApplicationExecutor(LocalPlanRuntime plans, UpstreamClient upstream, DemoEventClient events) {
        this.plans = plans;
        this.upstream = upstream;
        this.events = events;
    }

    public StandardResponse<Map<String, Object>> execute(String interfaceCode, String application,
                                                          Map<String, Object> request, String traceId,
                                                          String clientCode, String entryChannel) {
        return execute(interfaceCode, application, request, traceId, clientCode, entryChannel, null);
    }

    public StandardResponse<Map<String, Object>> execute(String interfaceCode, String application,
                                                          Map<String, Object> request, String traceId,
                                                          String clientCode, String entryChannel,
                                                          Integer expectedPlanVersion) {
        long started = System.nanoTime();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("traceId", traceId);
        event.put("interfaceCode", interfaceCode);
        event.put("clientCode", clientCode);
        event.put("application", application);
        event.put("entryChannel", entryChannel);
        event.put("standardRequest", request);
        Map<String, Object> preparedRequest = null;
        String upstreamUrl = null;
        try {
            long planStarted = System.nanoTime();
            PlanSnapshot selectedPlan = expectedPlanVersion == null
                    ? plans.current(interfaceCode) : plans.current(interfaceCode, expectedPlanVersion);
            PrepareResponse prepared = plans.prepare(selectedPlan, request);
            preparedRequest = prepared.upstreamRequest();
            upstreamUrl = prepared.upstreamUrl();
            long planDuration = elapsed(planStarted);
            long upstreamStarted = System.nanoTime();
            Map<String, Object> upstreamResponse = upstream.execute(selectedPlan.providerConnection(),
                    selectedPlan.upstreamHttpMethod(), prepared.upstreamUrl(), prepared.upstreamRequest(), traceId, prepared.timeoutMs());
            long upstreamDuration = elapsed(upstreamStarted);
            long responseStarted = System.nanoTime();
            // Keep the same immutable plan for the complete request. A concurrent
            // V3 -> V4 publication only affects requests that start afterwards.
            CompleteResponse completed = plans.complete(selectedPlan, upstreamResponse);
            long responseDuration = elapsed(responseStarted);
            String message = "HIT".equals(completed.resultStatus()) ? "查询成功"
                    : "MISS".equals(completed.resultStatus()) ? "未查询到符合条件的数据" : "供应商返回异常";
            StandardResponse<Map<String, Object>> result = new StandardResponse<>(completed.standardCode(), message,
                    completed.resultStatus(), traceId, completed.data());
            event.put("planVersion", prepared.planVersion());
            event.put("upstreamUrl", prepared.upstreamUrl());
            event.put("upstreamRequest", prepared.upstreamRequest());
            event.put("upstreamResponse", upstreamResponse);
            event.put("standardResponse", result);
            event.put("responseCode", completed.standardCode());
            event.put("resultStatus", completed.resultStatus());
            event.put("billable", completed.billable());
            event.put("requestStatus", "ERROR".equals(completed.resultStatus()) ? "FAILED" : "SUCCESS");
            event.put("durationMs", elapsed(started));
            event.put("upstreamDurationMs", upstreamDuration);
            event.put("nodes", List.of(
                    node("ESB_QUEUE", "模拟 ESB 请求队列", "SUCCESS", 1, request, Map.of("traceId", traceId)),
                    node("ESB_ADAPTER", "ESB Adapter", "SUCCESS", 1, request, Map.of("protocol", "JSON", "traceId", traceId)),
                    node("GATEWAY", "统一 Gateway", "SUCCESS", 1, Map.of("clientCode", clientCode), Map.of("application", application)),
                    node("PLAN", "本地 PlanSnapshot", "SUCCESS", planDuration, Map.of("interfaceCode", interfaceCode), Map.of("version", prepared.planVersion())),
                    node("REQUEST_MAPPING", "请求转换与安全处理", "SUCCESS", planDuration, request, prepared.upstreamRequest()),
                    node("UPSTREAM", "上游供应商", "SUCCESS", upstreamDuration, prepared.upstreamRequest(), upstreamResponse),
                    node("RESPONSE_MAPPING", "响应标准化", "ERROR".equals(completed.resultStatus()) ? "ERROR" : "SUCCESS", responseDuration, upstreamResponse, result)
            ));
            events.publishCall(event);
            return result;
        } catch (Exception error) {
            StandardResponse<Map<String, Object>> result = StandardResponse.failure(traceId,
                    "EXT-PLATFORM-ERROR", error.getMessage());
            event.put("standardResponse", result);
            event.put("responseCode", result.code());
            event.put("resultStatus", "ERROR");
            event.put("billable", false);
            event.put("requestStatus", "FAILED");
            event.put("durationMs", elapsed(started));
            event.put("upstreamDurationMs", 0);
            event.put("errorMessage", error.getMessage());
            if (error instanceof UpstreamClient.UpstreamCallException upstreamError) {
                event.put("upstreamUrl", upstreamUrl);
                event.put("upstreamRequest", preparedRequest == null ? Map.of() : preparedRequest);
                event.put("upstreamResponse", upstreamError.responseBody());
                event.put("upstreamHttpStatus", upstreamError.statusCode());
                event.put("nodes", List.of(
                        node("REQUEST_MAPPING", "请求转换与安全处理", "SUCCESS", 1,
                                request, preparedRequest == null ? Map.of() : preparedRequest),
                        node("UPSTREAM", "上游供应商", "ERROR", elapsed(started),
                                preparedRequest == null ? Map.of() : preparedRequest,
                                upstreamError.responseBody())));
            } else {
                event.put("nodes", List.of(node("DATA_APPLICATION", application, "ERROR", elapsed(started),
                        request, Map.of("message", error.getMessage() == null ? "unknown" : error.getMessage()))));
            }
            events.publishCall(event);
            return result;
        }
    }

    private Map<String, Object> node(String key, String name, String status, long duration,
                                     Object input, Object output) {
        return Map.of("nodeKey", key, "nodeName", name, "status", status,
                "durationMs", Math.max(0, duration), "input", input, "output", output);
    }

    private long elapsed(long started) { return Math.max(1, (System.nanoTime() - started) / 1_000_000); }
}
