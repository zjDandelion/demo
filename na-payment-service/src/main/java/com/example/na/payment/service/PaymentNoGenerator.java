package com.example.na.payment.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class PaymentNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public String nextPaymentNo() {
        return "PAY" + LocalDateTime.now().format(FORMATTER)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }
}
