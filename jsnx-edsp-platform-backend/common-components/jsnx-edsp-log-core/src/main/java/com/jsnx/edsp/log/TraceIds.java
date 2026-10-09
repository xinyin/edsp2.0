package com.jsnx.edsp.log;

import java.util.UUID;

public final class TraceIds {
    private TraceIds() {}

    public static String ensure(String traceId) {
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
