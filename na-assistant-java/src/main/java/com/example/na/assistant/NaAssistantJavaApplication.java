package com.example.na.assistant;

import com.example.na.assistant.config.AssistantProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Java 版下单助手服务。
 *
 * <p>与 {@code na-assistant-service}（Python/FastAPI）保持完全相同的内部契约：
 * {@code POST /internal/v1/assistant/parse}。两个服务可以同时运行，
 * 由订单侧的 {@code APP_ASSISTANT_URL} 决定使用哪一个。
 */
@SpringBootApplication
@EnableConfigurationProperties(AssistantProperties.class)
public class NaAssistantJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(NaAssistantJavaApplication.class, args);
    }
}
