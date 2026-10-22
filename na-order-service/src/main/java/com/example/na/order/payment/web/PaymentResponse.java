package com.example.na.order.payment.web;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
public class PaymentResponse {

    private final String paymentNo;
    private final String orderNo;
    private final BigDecimal amount;
    private final String channel;
    private final String status;
    private final LocalDateTime succeededAt;

    public PaymentResponse(String paymentNo, String orderNo, BigDecimal amount, String channel,
                           String status, LocalDateTime succeededAt) {
        this.paymentNo = paymentNo;
        this.orderNo = orderNo;
        this.amount = amount;
        this.channel = channel;
        this.status = status;
        this.succeededAt = succeededAt;
    }
}
