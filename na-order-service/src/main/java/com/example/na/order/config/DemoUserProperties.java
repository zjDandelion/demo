package com.example.na.order.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.demo-user")
@Getter
@Setter
public class DemoUserProperties {

    private String username = "demo";
    private String password = "123456";
    private String displayName = "演示用户";
}
