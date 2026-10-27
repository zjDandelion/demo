package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AssistantUsage {

    private final Long promptTokens;
    private final Long completionTokens;
    private final Long latencyMs;

    @JsonCreator
    public AssistantUsage(@JsonProperty("promptTokens") Long promptTokens,
                          @JsonProperty("completionTokens") Long completionTokens,
                          @JsonProperty("latencyMs") Long latencyMs) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
    }
}
