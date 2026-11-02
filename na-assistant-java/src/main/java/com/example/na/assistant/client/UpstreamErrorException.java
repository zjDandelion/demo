package com.example.na.assistant.client;

/**
 * 上游网络或其他调用错误。
 */
public class UpstreamErrorException extends RuntimeException {

    public UpstreamErrorException(String message) {
        super(message);
    }
}
