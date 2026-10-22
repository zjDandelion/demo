package com.example.na.order.paymentclient;

import lombok.Getter;

@Getter
public class PaymentClientException extends RuntimeException {

    private final Integer statusCode;

    public PaymentClientException(Integer statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
