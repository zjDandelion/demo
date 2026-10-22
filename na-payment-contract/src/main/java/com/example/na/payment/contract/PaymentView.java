package com.example.na.payment.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentView implements Serializable {

    private String paymentNo;

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String currency;

    private String status;

    private String channel;

    private LocalDateTime createdAt;

    private LocalDateTime succeededAt;

    private LocalDateTime refundedAt;

    private String refundReason;
}
