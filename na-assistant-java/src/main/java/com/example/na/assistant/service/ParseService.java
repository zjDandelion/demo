package com.example.na.assistant.service;

import com.example.na.assistant.client.ChatCompletionClient;
import com.example.na.assistant.client.UpstreamErrorException;
import com.example.na.assistant.client.UpstreamStatusException;
import com.example.na.assistant.client.UpstreamTimeoutException;
import com.example.na.assistant.config.AssistantProperties;
import com.example.na.assistant.domain.DegradedReason;
import com.example.na.assistant.web.ParseRequest;
import com.example.na.assistant.web.ParseResponse;
import com.example.na.assistant.web.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析编排。
 *
 * <p>流程与 Python 的 {@code parse_with_model} 一致：
 * 未配置模型 → DISABLED；调用上游 → 按异常映射降级；
 * 解析输出 → 清洗 → 组装 OK 响应。
 */
@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final AssistantProperties properties;

    private final ChatCompletionClient client;

    private final JsonExtractor jsonExtractor;

    private final ObjectMapper objectMapper;

    public ParseService(AssistantProperties properties, ChatCompletionClient client,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.client = client;
        this.objectMapper = objectMapper;
        this.jsonExtractor = new JsonExtractor(objectMapper);
    }

    /**
     * 处理一次解析请求。
     *
     * <p>注意：<b>降级也返回 HTTP 200</b>，由调用方按 {@code status} 字段判断。
     */
    public ParseResponse parse(ParseRequest request) {
        long startedAt = System.currentTimeMillis();

        if (!properties.isModelConfigured()) {
            log.warn("assistant.parse requestId={} status=DEGRADED reason=DISABLED",
                    request.getRequestId());
            return degraded(DegradedReason.DISABLED);
        }

        String rawBody;
        try {
            rawBody = client.complete(toPromptMessages(request));
        } catch (UpstreamTimeoutException exception) {
            return logDegraded(request, DegradedReason.TIMEOUT, startedAt);
        } catch (UpstreamStatusException | UpstreamErrorException exception) {
            return logDegraded(request, DegradedReason.UPSTREAM_ERROR, startedAt);
        }

        JsonNode output;
        int promptTokens;
        int completionTokens;
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                throw new JsonExtractor.InvalidOutputException("missing choices");
            }
            String content = choices.get(0).path("message").path("content").asText("");
            output = jsonExtractor.extract(content);
            JsonNode usage = root.path("usage");
            promptTokens = usage.path("prompt_tokens").asInt(0);
            completionTokens = usage.path("completion_tokens").asInt(0);
        } catch (IOException | JsonExtractor.InvalidOutputException | IllegalStateException exception) {
            return logDegraded(request, DegradedReason.INVALID_OUTPUT, startedAt);
        }

        OutputSanitizer.Result sanitized = OutputSanitizer.sanitize(output, request);
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);

        ParseResponse response = new ParseResponse();
        response.setStatus(ParseResponse.STATUS_OK);
        response.setItems(sanitized.getItems());
        response.setUnmatched(sanitized.getUnmatched());
        response.setQuestions(sanitized.getQuestions());
        response.setUsage(new Usage(promptTokens, completionTokens, latencyMs));

        log.info("assistant.parse requestId={} status=OK items={} dropped={} latencyMs={}",
                request.getRequestId(), sanitized.getItems().size(),
                sanitized.getDropped(), latencyMs);
        return response;
    }

    /**
     * 构造降级响应（供 Controller 与异常处理器复用）。
     */
    public ParseResponse degraded(DegradedReason reason) {
        ParseResponse response = new ParseResponse();
        response.setStatus(ParseResponse.STATUS_DEGRADED);
        response.setReason(reason.name());
        response.setItems(new ArrayList<>());
        response.setUnmatched(new ArrayList<>());
        response.setQuestions(new ArrayList<>());
        response.setUsage(Usage.empty(0));
        return response;
    }

    private ParseResponse logDegraded(ParseRequest request, DegradedReason reason, long startedAt) {
        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        log.warn("assistant.parse requestId={} status=DEGRADED reason={} latencyMs={}",
                request.getRequestId(), reason, latencyMs);
        ParseResponse response = degraded(reason);
        response.setUsage(Usage.empty(latencyMs));
        return response;
    }

    private List<ChatCompletionClient.PromptMessage> toPromptMessages(ParseRequest request) {
        List<PromptBuilder.Message> messages = PromptBuilder.buildMessages(request);
        List<ChatCompletionClient.PromptMessage> converted = new ArrayList<>(messages.size());
        for (PromptBuilder.Message message : messages) {
            converted.add(new ChatCompletionClient.PromptMessage(
                    message.getRole(), message.getContent()));
        }
        return converted;
    }
}
