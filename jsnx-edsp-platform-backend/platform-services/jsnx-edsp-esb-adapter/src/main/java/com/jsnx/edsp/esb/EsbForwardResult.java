package com.jsnx.edsp.esb;

import java.util.Map;

/** Keeps the platform JSON result separate from the XML returned to the bank ESB. */
public record EsbForwardResult(
        EsbRequestEnvelope request,
        Map<String, Object> standardResponse,
        String xmlResponse,
        Map<String, Object> diagnosticResponse) {
}
