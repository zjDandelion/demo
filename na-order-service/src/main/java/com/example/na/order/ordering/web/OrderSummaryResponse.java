package com.example.na.order.ordering.web;

import com.example.na.order.ordering.domain.OrderStatus;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class OrderSummaryResponse {

    private final String orderNo;
    private final OrderStatus status;
    private final BigDecimal totalAmount;
    private final LocalDateTime createdAt;
    private final int itemCount;

    public OrderSummaryResponse(String orderNo, OrderStatus status, BigDecimal totalAmount,
                                LocalDateTime createdAt, int itemCount) {
        this.orderNo = orderNo;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.itemCount = itemCount;
    }
}
