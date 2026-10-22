package com.example.na.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderSystemOpenApi() {
        return new OpenAPI().info(new Info()
                .title("简易订单系统 API")
                .version("1.0.0")
                .description("先通过 /login 登录，浏览器会自动携带 Session Cookie。"
                        + "/api/** 为调试方便关闭了 CSRF，仅适用于本机/内网演示。"));
    }
}
