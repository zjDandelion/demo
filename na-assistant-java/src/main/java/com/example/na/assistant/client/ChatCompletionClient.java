package com.example.na.assistant.client;

import com.example.na.assistant.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 OpenAI 兼容的 {@code /chat/completions}。
 *
 * <p>只负责「发请求 + 拿到原始响应体」；超时与状态码异常原样抛给上层映射为降级原因。
 */
@Component
public class ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionClient.class);

    private static final int MAX_TOKENS = 512;

    private final AssistantProperties properties;

    private final RestTemplate restTemplate;

    public ChatCompletionClient(AssistantProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .setReadTimeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .build();
    }

    /**
     * 发起一次 chat completion 调用。
     *
     * @return 上游返回的原始 JSON 文本
     * @throws UpstreamTimeoutException 超时
     * @throws UpstreamStatusException  上游返回 4xx/5xx
     * @throws UpstreamErrorException   其他网络/客户端错误
     */
    public String complete(List<PromptMessage> messages) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("max_tokens", MAX_TOKENS);
        payload.put("response_format", singletonFormat());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey());

        String url = trimTrailingSlash(properties.getBaseUrl()) + "/chat/completions";
        long startedAt = System.currentTimeMillis();
        try {
            String body = restTemplate.postForObject(url, new HttpEntity<>(payload, headers), String.class);
            log.debug("Chat completion finished: elapsedMs={}", System.currentTimeMillis() - startedAt);
            return body == null ? "" : body;
        } catch (HttpStatusCodeException exception) {
            log.warn("Chat completion rejected: status={}", exception.getRawStatusCode());
            throw new UpstreamStatusException(exception.getRawStatusCode());
        } catch (ResourceAccessException exception) {
            if (isTimeout(exception)) {
                log.warn("Chat completion timed out: elapsedMs={}", System.currentTimeMillis() - startedAt);
                throw new UpstreamTimeoutException("upstream timeout");
            }
            log.warn("Chat completion unreachable: {}", exception.getMessage());
            throw new UpstreamErrorException("upstream unreachable");
        }
    }

    private static Map<String, String> singletonFormat() {
        Map<String, String> format = new LinkedHashMap<>();
        format.put("type", "json_object");
        return format;
    }

    private static boolean isTimeout(ResourceAccessException exception) {
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 一条消息，序列化为 {@code {"role": "...", "content": "..."}}。
     */
    public static final class PromptMessage {

        private final String role;

        private final String content;

        public PromptMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
