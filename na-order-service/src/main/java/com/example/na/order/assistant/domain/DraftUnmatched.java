package com.example.na.order.assistant.domain;

import lombok.Getter;

@Getter
public class DraftUnmatched {

    private final String text;
    private final String reason;

    public DraftUnmatched(String text, String reason) {
        this.text = text;
        this.reason = reason;
    }
}
