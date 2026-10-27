package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class AssistantParseRequest {

    private final String requestId;
    private final Long userId;
    private final String text;
    private final List<Long> excludeProductIds;
    private final List<AssistantCatalogItem> catalog;

    @JsonCreator
    public AssistantParseRequest(@JsonProperty("requestId") String requestId,
                                 @JsonProperty("userId") Long userId,
                                 @JsonProperty("text") String text,
                                 @JsonProperty("excludeProductIds") List<Long> excludeProductIds,
                                 @JsonProperty("catalog") List<AssistantCatalogItem> catalog) {
        this.requestId = requestId;
        this.userId = userId;
        this.text = text;
        this.excludeProductIds = excludeProductIds == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(excludeProductIds));
        this.catalog = catalog == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(catalog));
    }
}
