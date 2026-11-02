package com.example.na.assistant.contract;

import com.example.na.assistant.web.CatalogItem;
import com.example.na.assistant.web.ParseRequest;
import com.example.na.assistant.web.ParseResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨语言契约测试。
 *
 * <p>与订单侧 {@code AssistantContractTest}、Python 侧 {@code test_contract.py}
 * 共用 {@code tests/contract/*.json}。任何一方改动契约，三方都会失败直到对齐。
 */
class AssistantContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void requestFixtureMatchesJavaModel() throws Exception {
        JsonNode fixture = read("assistant-parse.request.json");
        ParseRequest request = mapper.treeToValue(fixture, ParseRequest.class);

        assertEquals("drf_5f2c9a1b", request.getRequestId());
        assertEquals(1L, request.getUserId());
        assertEquals("帮我拿两个机械键盘，再加一张升降桌", request.getText());
        assertEquals(Collections.singletonList(1L), request.getExcludeProductIds());
        assertEquals(1, request.getCatalog().size());

        CatalogItem item = request.getCatalog().get(0);
        assertEquals(1L, item.getId());
        assertEquals("机械键盘 K87", item.getName());
        assertEquals("外设", item.getCategory());
        assertEquals(0, new BigDecimal("299.00").compareTo(item.getPrice()));
        assertEquals(25, item.getStock());
    }

    @Test
    void okFixtureIsSuccessfulParse() throws Exception {
        JsonNode fixture = read("assistant-parse.ok.json");
        ParseResponse response = mapper.treeToValue(fixture, ParseResponse.class);

        assertTrue(response.isSuccess());
        assertEquals(1, response.getItems().size());
        assertEquals(6L, response.getItems().get(0).getProductId());
        assertEquals(1, response.getItems().get(0).getQuantity());
        assertEquals("升降桌", response.getUnmatched().get(0).getText());
        assertEquals(620, response.getUsage().getPromptTokens());
        assertEquals(48, response.getUsage().getCompletionTokens());
        assertEquals(870, response.getUsage().getLatencyMs());
    }

    @Test
    void degradedFixtureIsFailureWithReason() throws Exception {
        JsonNode fixture = read("assistant-parse.degraded.json");
        ParseResponse response = mapper.treeToValue(fixture, ParseResponse.class);

        assertFalse(response.isSuccess());
        assertEquals("TIMEOUT", response.getReason());
        assertTrue(response.getItems().isEmpty());
        assertTrue(response.getUnmatched().isEmpty());
        assertTrue(response.getQuestions().isEmpty());
        assertEquals(4000, response.getUsage().getLatencyMs());
    }

    @Test
    void serializedResponseKeepsContractFields() throws Exception {
        JsonNode fixture = read("assistant-parse.ok.json");
        ParseResponse response = mapper.treeToValue(fixture, ParseResponse.class);

        JsonNode serialized = mapper.valueToTree(response);
        assertContainsContractFields(fixture, serialized, "$");
    }

    @Test
    void unknownFieldInRequestIsRejected() throws Exception {
        String payload = "{\"requestId\":\"r1\",\"text\":\"x\",\"unexpected\":1}";
        boolean rejected;
        try {
            mapper.readValue(payload, ParseRequest.class);
            rejected = false;
        } catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException exception) {
            rejected = true;
        }
        // Python 用 extra="forbid"，未知字段必须拒绝
        assertTrue(rejected, "unknown field must be rejected");
    }

    @Test
    void unknownFieldInCatalogItemIsIgnored() throws Exception {
        String payload = "{\"requestId\":\"r1\",\"text\":\"x\",\"catalog\":["
                + "{\"id\":1,\"name\":\"n\",\"price\":1.00,\"stock\":1,\"extra\":\"ok\"}]}";
        ParseRequest request = mapper.readValue(payload, ParseRequest.class);
        // CatalogItem 用 extra="ignore"
        assertEquals(1, request.getCatalog().size());
    }

    private JsonNode read(String name) throws IOException {
        return mapper.readTree(contractDirectory().resolve(name).toFile());
    }

    private static Path contractDirectory() {
        Path moduleRelative = Paths.get("..", "tests", "contract");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repoRelative = Paths.get("tests", "contract");
        if (Files.isDirectory(repoRelative)) {
            return repoRelative;
        }
        throw new IllegalStateException("tests/contract not found; run tests from the repo or module root");
    }

    private void assertContainsContractFields(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path + " should be an object");
            expected.fields().forEachRemaining(entry -> {
                assertTrue(actual.has(entry.getKey()), path + "." + entry.getKey() + " is missing");
                assertContainsContractFields(entry.getValue(), actual.get(entry.getKey()),
                        path + "." + entry.getKey());
            });
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path + " should be an array");
            assertEquals(expected.size(), actual.size(), path + " length");
            for (int index = 0; index < expected.size(); index++) {
                assertContainsContractFields(expected.get(index), actual.get(index),
                        path + "[" + index + "]");
            }
        } else if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path);
        } else {
            assertEquals(expected, actual, path);
        }
    }
}
