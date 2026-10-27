package com.example.na.order.assistant.domain;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class DraftCandidate {

    private final Long productId;
    private final String name;
    private final BigDecimal price;
    private final int stock;

    public DraftCandidate(Long productId, String name, BigDecimal price, int stock) {
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }
}
