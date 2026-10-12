package com.jsnx.edsp.esb;

import java.util.Map;

/** Parsed bank ESB request. The original business request is retained for the response envelope. */
public record EsbRequestEnvelope(
        String serviceSn,
        String serviceId,
        String requesterId,
        String serviceTime,
        String versionId,
        Map<String, Object> requestBody) {
}
