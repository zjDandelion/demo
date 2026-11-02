package com.example.na.assistant.client;

/**
 * 上游调用超时。
 */
public class UpstreamTimeoutException extends RuntimeException {

    public UpstreamTimeoutException(String message) {
        super(message);
    }
}
