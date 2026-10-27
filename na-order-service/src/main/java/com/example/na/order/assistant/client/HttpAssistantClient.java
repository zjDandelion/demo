package com.example.na.order.assistant.client;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.domain.DegradedReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

@Component
@ConditionalOnProperty(prefix = "app.assistant", name = "enabled", havingValue = "true")
public class HttpAssistantClient implements AssistantClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAssistantClient.class);

    private static final String PARSE_PATH = "/internal/v1/assistant/parse";

    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;

    public HttpAssistantClient(AssistantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ParseOutcome parse(AssistantParseRequest request) {
        String requestId = request.getRequestId();
        long startedAt = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint()).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(Math.max(100, properties.getConnectTimeoutMs()));
            connection.setReadTimeout(Math.max(100, properties.getReadTimeoutMs()));
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            String token = properties.getServiceToken();
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("X-Service-Token", token);
            }

            byte[] body = objectMapper.writeValueAsBytes(request);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == 400 || status == 401) {
                    log.error("Assistant parse rejected: requestId={}, status={} (check token or contract)",
                            requestId, status);
                } else {
                    log.warn("Assistant parse failed: requestId={}, status={}", requestId, status);
                }
                return ParseOutcome.failed(DegradedReason.UPSTREAM_ERROR);
            }

            try (InputStream input = connection.getInputStream()) {
                AssistantParseResponse response = objectMapper.readValue(input, AssistantParseResponse.class);
                ParseOutcome outcome = ParseOutcome.of(response);
                log.debug("Assistant parse finished: requestId={}, degraded={}, elapsedMs={}",
                        requestId, !outcome.isSuccess(), System.currentTimeMillis() - startedAt);
                return outcome;
            }
        } catch (SocketTimeoutException timeout) {
            log.warn("Assistant parse timed out: requestId={}, elapsedMs={}",
                    requestId, System.currentTimeMillis() - startedAt);
            return ParseOutcome.failed(DegradedReason.TIMEOUT);
        } catch (JsonProcessingException error) {
            log.warn("Assistant returned unreadable JSON: requestId={}", requestId);
            return ParseOutcome.failed(DegradedReason.INVALID_OUTPUT);
        } catch (IOException error) {
            log.warn("Assistant unreachable: requestId={}, error={}, elapsedMs={}",
                    requestId, error.getClass().getSimpleName(), System.currentTimeMillis() - startedAt);
            return ParseOutcome.failed(DegradedReason.UNREACHABLE);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String endpoint() {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + PARSE_PATH;
    }
}
