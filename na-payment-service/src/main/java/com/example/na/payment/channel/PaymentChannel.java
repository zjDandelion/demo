package com.example.na.payment.channel;

import com.example.na.payment.domain.Payment;

public interface PaymentChannel {

    void charge(Payment payment);

    void refund(Payment payment, String reason);

    void close(Payment payment, String reason);
}
