package com.example.na.order.assistant.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.assistant")
@Getter
@Setter
public class AssistantProperties {

    private boolean enabled = false;

    private String baseUrl = "http://na-assistant-service:8092";

    private String serviceToken;

    private int connectTimeoutMs = 2000;

    private int readTimeoutMs = 4500;

    private int maxTextLength = 300;

    private int maxItems = 20;

    private int maxCatalogSize = 100;

    private int prefilterTop = 50;

    private long catalogCacheTtlMs = 60000;

    private int rateLimitPerMinute = 10;

    private Map<String, String> extraAliases = new HashMap<>();
}
