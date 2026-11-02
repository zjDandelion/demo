package com.example.na.assistant.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 请求体大小限制。
 *
 * <p>与 Python 的 {@code limit_body_size} 中间件对齐：超过 {@code ASSISTANT_MAX_BODY_BYTES}
 * 直接返回 413 + {@code {"message": "请求体过大"}}，不进入业务逻辑。
 */
@Configuration
public class BodySizeLimitConfig {

    @Bean
    public FilterRegistrationBean<Filter> bodySizeLimitFilter(AssistantProperties properties) {
        Filter filter = new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response,
                                 FilterChain chain) throws IOException, ServletException {
                if (request instanceof HttpServletRequest) {
                    String lengthHeader = ((HttpServletRequest) request)
                            .getHeader("Content-Length");
                    if (lengthHeader != null && lengthHeader.matches("\\d+")
                            && Long.parseLong(lengthHeader) > properties.getMaxBodyBytes()) {
                        HttpServletResponse httpResponse = (HttpServletResponse) response;
                        httpResponse.setStatus(413);
                        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        httpResponse.setContentType("application/json");
                        httpResponse.getWriter().write("{\"message\":\"请求体过大\"}");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(1);
        return registration;
    }
}
