package com.jsnx.edsp.esb;

import org.springframework.http.HttpStatusCode;

/** Preserves the downstream HTTP failure instead of turning every ESB failure into HTTP 500. */
public class EsbForwardException extends RuntimeException {
    private final HttpStatusCode status;
    private final String responseBody;

    public EsbForwardException(HttpStatusCode status, String message, String responseBody) {
        super(message);
        this.status = status;
        this.responseBody = responseBody;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String responseBody() {
        return responseBody;
    }
}
