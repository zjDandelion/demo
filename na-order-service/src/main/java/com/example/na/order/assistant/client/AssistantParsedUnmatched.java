package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AssistantParsedUnmatched {

    private final String text;
    private final String reason;

    @JsonCreator
    public AssistantParsedUnmatched(@JsonProperty("text") String text,
                                    @JsonProperty("reason") String reason) {
        this.text = text;
        this.reason = reason;
    }
}
