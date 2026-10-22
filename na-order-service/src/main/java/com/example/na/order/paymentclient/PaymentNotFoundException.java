package com.example.na.order.paymentclient;

public class PaymentNotFoundException extends PaymentClientException {

    public PaymentNotFoundException(String message) {
        super(404, message);
    }
}
