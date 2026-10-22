package com.example.na.order.ordering.web;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class OrderItemResponse {

    private final Long productId;
    private final String productName;
    private final BigDecimal unitPrice;
    private final int quantity;
    private final BigDecimal subtotal;

    public OrderItemResponse(Long productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal subtotal) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.subtotal = subtotal;
    }
}
