package com.example.na.order.assistant.contract;

import com.example.na.order.assistant.client.AssistantParseRequest;
import com.example.na.order.assistant.client.AssistantParseResponse;
import com.example.na.order.assistant.client.ParseOutcome;
import com.example.na.order.assistant.domain.DegradedReason;
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

class AssistantContractTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test
    void requestFixtureMatchesJavaModel() throws Exception {
        JsonNode fixture = read("assistant-parse.request.json");
        AssistantParseRequest request = mapper.treeToValue(fixture, AssistantParseRequest.class);

        assertEquals("drf_5f2c9a1b", request.getRequestId());
        assertEquals(1L, request.getUserId());
        assertEquals("帮我拿两个机械键盘，再加一张升降桌", request.getText());
        assertEquals(Collections.singletonList(1L), request.getExcludeProductIds());
        assertEquals(1, request.getCatalog().size());
        assertEquals(1L, request.getCatalog().get(0).getId());
        assertEquals("机械键盘 K87", request.getCatalog().get(0).getName());
        assertEquals("外设", request.getCatalog().get(0).getCategory());
        assertEquals(0, new BigDecimal("299.00").compareTo(request.getCatalog().get(0).getPrice()));
        assertEquals(25, request.getCatalog().get(0).getStock());

        assertContainsContractFields(fixture, request, "$");
    }

    @Test
    void okFixtureIsSuccessfulParse() throws Exception {
        JsonNode fixture = read("assistant-parse.ok.json");
        AssistantParseResponse response = mapper.treeToValue(fixture, AssistantParseResponse.class);

        ParseOutcome outcome = ParseOutcome.of(response);
        assertTrue(outcome.isSuccess());
        assertEquals(1, response.getItems().size());
        assertEquals(6L, response.getItems().get(0).getProductId());
        assertEquals(1, response.getItems().get(0).getQuantity());
        assertEquals("升降桌", response.getUnmatched().get(0).getText());
        assertEquals(620L, response.getUsage().getPromptTokens());

        assertContainsContractFields(fixture, response, "$");
    }

    @Test
    void degradedFixtureIsFailureWithReason() throws Exception {
        JsonNode fixture = read("assistant-parse.degraded.json");
        AssistantParseResponse response = mapper.treeToValue(fixture, AssistantParseResponse.class);

        ParseOutcome outcome = ParseOutcome.of(response);
        assertFalse(outcome.isSuccess());
        assertEquals(DegradedReason.TIMEOUT, outcome.getFailureReason());
        assertTrue(response.getItems().isEmpty());
        assertTrue(response.getUnmatched().isEmpty());
        assertTrue(response.getQuestions().isEmpty());

        assertContainsContractFields(fixture, response, "$");
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

    private void assertContainsContractFields(JsonNode expected, Object value, String path) {
        JsonNode actual = mapper.valueToTree(value);
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path + " should be an object");
            expected.fields().forEachRemaining(entry -> {
                assertTrue(actual.has(entry.getKey()), path + "." + entry.getKey() + " is missing");
                assertContainsContractFields(entry.getValue(), actual.get(entry.getKey()), path + "." + entry.getKey());
            });
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path + " should be an array");
            assertEquals(expected.size(), actual.size(), path + " length");
            for (int index = 0; index < expected.size(); index++) {
                assertContainsContractFields(expected.get(index), actual.get(index), path + "[" + index + "]");
            }
        } else if (expected.isNumber() && actual.isNumber()) {
            assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path);
        } else {
            assertEquals(expected, actual, path);
        }
    }
}
