package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AssistantParsedQuestion {

    private final String text;

    @JsonCreator
    public AssistantParsedQuestion(@JsonProperty("text") String text) {
        this.text = text;
    }
}
