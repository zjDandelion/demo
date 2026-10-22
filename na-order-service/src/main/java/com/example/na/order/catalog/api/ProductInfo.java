package com.example.na.order.catalog.api;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class ProductInfo {

    private final Long id;
    private final String name;
    private final String description;
    private final BigDecimal price;
    private final int stock;
    private final boolean enabled;

    public ProductInfo(Long id, String name, String description, BigDecimal price, int stock, boolean enabled) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.enabled = enabled;
    }
}
