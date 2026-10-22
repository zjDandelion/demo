package com.example.na.order.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.Map;

@Getter
public class ErrorResponse {

    private final String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Map<String, String> fieldErrors;

    public ErrorResponse(String message) {
        this(message, null);
    }

    public ErrorResponse(String message, Map<String, String> fieldErrors) {
        this.message = message;
        this.fieldErrors = fieldErrors;
    }
}
