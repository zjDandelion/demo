package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class AssistantParsedItem {

    private final Long productId;
    private final Integer quantity;

    @JsonCreator
    public AssistantParsedItem(@JsonProperty("productId") Long productId,
                               @JsonProperty("quantity") Integer quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }
}
