package com.example.na.order.assistant.domain;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class DraftItem {

    private final Long productId;
    private final String name;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal subtotal;
    private final boolean stockShortage;
    private final DraftSource source;
    private final double score;

    public DraftItem(Long productId, String name, int quantity, BigDecimal unitPrice, BigDecimal subtotal,
                     boolean stockShortage, DraftSource source, double score) {
        this.productId = productId;
        this.name = name;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.subtotal = subtotal;
        this.stockShortage = stockShortage;
        this.source = source;
        this.score = score;
    }
}
