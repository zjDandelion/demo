package com.example.na.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型输出提取测试。
 *
 * <p>用例与 Python 的 {@code _extract_json} 行为逐条对应。
 */
class JsonExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final JsonExtractor extractor = new JsonExtractor(mapper);

    @Test
    void plainJsonObject() {
        JsonNode node = extractor.extract("{\"items\":[],\"unmatched\":[]}");

        assertTrue(node.isObject());
        assertTrue(node.get("items").isArray());
    }

    @Test
    void jsonInsideCodeFence() {
        JsonNode node = extractor.extract("```json\n{\"items\":[{\"productId\":1}]}\n```");

        assertEquals(1, node.get("items").size());
    }

    @Test
    void jsonInsideBareCodeFence() {
        JsonNode node = extractor.extract("```\n{\"items\":[]}\n```");

        assertTrue(node.isObject());
    }

    @Test
    void jsonWithSurroundingProse() {
        JsonNode node = extractor.extract(
                "好的，结果如下：{\"items\":[{\"productId\":6,\"quantity\":1}]} 希望有帮助");

        assertEquals(6L, node.get("items").get(0).get("productId").asLong());
    }

    @Test
    void bracesInsideStringsDoNotBreakScanning() {
        JsonNode node = extractor.extract(
                "prefix {\"unmatched\":[{\"text\":\"含 } 和 { 的文字\",\"reason\":\"x\"}]} suffix");

        assertEquals("含 } 和 { 的文字", node.get("unmatched").get(0).get("text").asText());
    }

    @Test
    void escapedQuoteInsideString() {
        JsonNode node = extractor.extract(
                "{\"unmatched\":[{\"text\":\"带\\\"引号\\\"的文本\",\"reason\":\"x\"}]}");

        assertEquals("带\"引号\"的文本", node.get("unmatched").get(0).get("text").asText());
    }

    @Test
    void noJsonObjectThrows() {
        assertThrows(JsonExtractor.InvalidOutputException.class,
                () -> extractor.extract("这不是 JSON"));
    }

    @Test
    void unbalancedObjectThrows() {
        assertThrows(JsonExtractor.InvalidOutputException.class,
                () -> extractor.extract("{\"items\":[1,2"));
    }

    @Test
    void nonObjectJsonThrows() {
        // 顶层是数组，不是对象
        assertThrows(JsonExtractor.InvalidOutputException.class,
                () -> extractor.extract("[1,2,3]"));
    }

    @Test
    void nullContentThrows() {
        assertThrows(JsonExtractor.InvalidOutputException.class, () -> extractor.extract(null));
    }
}
