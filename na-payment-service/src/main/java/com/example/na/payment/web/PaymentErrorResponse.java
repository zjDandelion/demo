package com.example.na.payment.web;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class PaymentErrorResponse {

    private final String code;

    private final String message;
}
