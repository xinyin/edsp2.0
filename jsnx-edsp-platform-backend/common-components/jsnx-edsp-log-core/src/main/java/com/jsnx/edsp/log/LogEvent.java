package com.jsnx.edsp.log;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral structured event. The transport layer decides where it is stored. */
public record LogEvent(
        String eventType,
        String traceId,
        Instant eventTime,
        Map<String, Object> fields) {
    public LogEvent {
        eventType = eventType == null || eventType.isBlank() ? "API_CALL" : eventType;
        traceId = TraceIds.ensure(traceId);
        eventTime = eventTime == null ? Instant.now() : eventTime;
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
