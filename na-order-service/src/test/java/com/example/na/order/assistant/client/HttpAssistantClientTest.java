package com.example.na.order.assistant.client;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.domain.DegradedReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAssistantClientTest {

    private static final String PATH = "/internal/v1/assistant/parse";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void sendsTokenAndMapsOkResponse() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String baseUrl = startServer(exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"status\":\"OK\",\"items\":[{\"productId\":6,\"quantity\":1}],"
                    + "\"unmatched\":[],\"questions\":[],\"usage\":{\"promptTokens\":1,\"completionTokens\":1,\"latencyMs\":5}}");
        });

        ParseOutcome outcome = client(baseUrl, "secret", 500, 500).parse(request());

        assertTrue(outcome.isSuccess());
        assertEquals(6L, outcome.getResponse().getItems().get(0).getProductId());
        assertEquals("secret", token.get());
        assertTrue(body.get().contains("drf_client-test"));
    }

    @Test
    void non200IsUpstreamError() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 401, "{\"message\":\"unauthorized\"}"));

        ParseOutcome outcome = client(baseUrl, "wrong", 500, 500).parse(request());

        assertFalse(outcome.isSuccess());
        assertEquals(DegradedReason.UPSTREAM_ERROR, outcome.getFailureReason());
    }

    @Test
    void unreadableJsonIsInvalidOutput() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 200, "not-json"));

        ParseOutcome outcome = client(baseUrl, "secret", 500, 500).parse(request());

        assertEquals(DegradedReason.INVALID_OUTPUT, outcome.getFailureReason());
    }

    @Test
    void degradedReasonIsPropagated() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 200,
                "{\"status\":\"DEGRADED\",\"reason\":\"INVALID_OUTPUT\",\"items\":[],\"unmatched\":[],\"questions\":[]}"));

        ParseOutcome outcome = client(baseUrl, "secret", 500, 500).parse(request());

        assertEquals(DegradedReason.INVALID_OUTPUT, outcome.getFailureReason());
    }

    @Test
    void slowResponseTimesOut() throws Exception {
        String baseUrl = startServer(exchange -> {
            sleep(1000);
            respond(exchange, 200, "{\"status\":\"OK\"}");
        });

        long startedAt = System.currentTimeMillis();
        ParseOutcome outcome = client(baseUrl, "secret", 300, 250).parse(request());
        long elapsed = System.currentTimeMillis() - startedAt;

        assertEquals(DegradedReason.TIMEOUT, outcome.getFailureReason());
        assertTrue(elapsed < 1500, "should give up on the read timeout, was " + elapsed + "ms");
    }

    @Test
    void closedPortIsUnreachable() throws Exception {
        String baseUrl = startServer(exchange -> respond(exchange, 200, "{}"));
        server.stop(0);
        server = null;

        ParseOutcome outcome = client(baseUrl, "secret", 300, 300).parse(request());

        assertEquals(DegradedReason.UNREACHABLE, outcome.getFailureReason());
    }

    private HttpAssistantClient client(String baseUrl, String token, int connectTimeoutMs, int readTimeoutMs) {
        AssistantProperties properties = new AssistantProperties();
        properties.setBaseUrl(baseUrl);
        properties.setServiceToken(token);
        properties.setConnectTimeoutMs(connectTimeoutMs);
        properties.setReadTimeoutMs(readTimeoutMs);
        return new HttpAssistantClient(properties, new ObjectMapper());
    }

    private static AssistantParseRequest request() {
        return new AssistantParseRequest("drf_client-test", 1L, "机械键盘",
                Collections.emptyList(), Collections.emptyList());
    }

    private String startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(PATH, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
