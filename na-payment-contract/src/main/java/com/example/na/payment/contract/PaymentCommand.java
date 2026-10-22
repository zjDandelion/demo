package com.example.na.payment.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCommand implements Serializable {

    private String orderNo;

    private Long userId;

    private BigDecimal amount;

    private String currency;
}
