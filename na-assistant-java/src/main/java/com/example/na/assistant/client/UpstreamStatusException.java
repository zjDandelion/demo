package com.example.na.assistant.client;

/**
 * 上游返回 4xx/5xx。
 */
public class UpstreamStatusException extends RuntimeException {

    private final int statusCode;

    public UpstreamStatusException(int statusCode) {
        super("upstream status " + statusCode);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
