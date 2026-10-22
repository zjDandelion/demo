package com.example.na.order.paymentclient;

public class PaymentUnavailableException extends PaymentClientException {

    public PaymentUnavailableException(String message) {
        super(503, message);
    }
}
