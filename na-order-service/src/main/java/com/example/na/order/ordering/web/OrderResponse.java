package com.example.na.order.ordering.web;

import com.example.na.order.ordering.domain.OrderStatus;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class OrderResponse {

    private final String orderNo;
    private final OrderStatus status;
    private final BigDecimal totalAmount;
    private final LocalDateTime createdAt;
    private final LocalDateTime paidAt;
    private final LocalDateTime cancelledAt;
    private final List<OrderItemResponse> items;

    public OrderResponse(String orderNo, OrderStatus status, BigDecimal totalAmount,
                         LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime cancelledAt,
                         List<OrderItemResponse> items) {
        this.orderNo = orderNo;
        this.status = status;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.cancelledAt = cancelledAt;
        this.items = items;
    }
}
