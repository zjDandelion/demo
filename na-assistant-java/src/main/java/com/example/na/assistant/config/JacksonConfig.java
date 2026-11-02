package com.example.na.assistant.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 配置。
 *
 * <p>两个关键点，都与 Python 的行为对齐：
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = true}：Python 的 {@code ParseRequest}
 *       用 {@code extra="forbid"}，未知字段必须拒绝；</li>
 *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS = true}：Python 用 {@code Decimal}
 *       接 price，避免 double 精度误差。</li>
 * </ul>
 * 允许未知字段的子对象（CatalogItem 等）用 {@code @JsonIgnoreProperties} 单独放开。
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
