package com.example.na.order.ordering.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public String nextOrderNo() {
        return "ORD" + LocalDateTime.now().format(FORMATTER)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }
}
