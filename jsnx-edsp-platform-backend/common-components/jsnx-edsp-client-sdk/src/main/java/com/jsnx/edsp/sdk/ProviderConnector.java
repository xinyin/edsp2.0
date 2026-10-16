package com.jsnx.edsp.sdk;

import java.util.Map;

/** Provider-local protocol extension. Implementations must enforce timeouts and resolve secret references locally. */
public interface ProviderConnector {
    String accessMode();

    Map<String, Object> execute(Map<String, Object> connection, String method, String endpoint,
                                Map<String, Object> request, String traceId, int timeoutMs);
}
