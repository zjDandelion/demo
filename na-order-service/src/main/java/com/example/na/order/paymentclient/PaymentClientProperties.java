package com.example.na.order.paymentclient;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.payment")
public class PaymentClientProperties {

    private String baseUrl = "http://localhost:8091";

    private String serviceToken;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 5000;
}
