package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssistantParseResponse {

    private final String status;
    private final String reason;
    private final List<AssistantParsedItem> items;
    private final List<AssistantParsedUnmatched> unmatched;
    private final List<AssistantParsedQuestion> questions;
    private final AssistantUsage usage;

    @JsonCreator
    public AssistantParseResponse(@JsonProperty("status") String status,
                                  @JsonProperty("reason") String reason,
                                  @JsonProperty("items") List<AssistantParsedItem> items,
                                  @JsonProperty("unmatched") List<AssistantParsedUnmatched> unmatched,
                                  @JsonProperty("questions") List<AssistantParsedQuestion> questions,
                                  @JsonProperty("usage") AssistantUsage usage) {
        this.status = status;
        this.reason = reason;
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.unmatched = unmatched == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(unmatched));
        this.questions = questions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(questions));
        this.usage = usage;
    }
}
