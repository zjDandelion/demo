package com.example.na.order.assistant.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class AssistantCatalogItem {

    private final Long id;
    private final String name;
    private final String description;
    private final String category;
    private final String tags;
    private final BigDecimal price;
    private final Integer stock;

    @JsonCreator
    public AssistantCatalogItem(@JsonProperty("id") Long id,
                                @JsonProperty("name") String name,
                                @JsonProperty("description") String description,
                                @JsonProperty("category") String category,
                                @JsonProperty("tags") String tags,
                                @JsonProperty("price") BigDecimal price,
                                @JsonProperty("stock") Integer stock) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.tags = tags;
        this.price = price;
        this.stock = stock;
    }
}
