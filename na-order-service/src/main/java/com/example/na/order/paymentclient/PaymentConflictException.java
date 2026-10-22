package com.example.na.order.paymentclient;

public class PaymentConflictException extends PaymentClientException {

    public PaymentConflictException(String message) {
        super(409, message);
    }
}
