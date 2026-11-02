package com.example.na.assistant.service;

import com.example.na.assistant.web.CatalogItem;
import com.example.na.assistant.web.ParseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出清洗测试。
 *
 * <p>覆盖 Python {@code _sanitize} 的每一条规则。
 */
class OutputSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void dropsProductOutsideCatalog() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L, 2L));
        JsonNode output = mapper.readTree("{\"items\":[{\"productId\":1,\"quantity\":1},"
                + "{\"productId\":99,\"quantity\":1}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(1, result.getItems().size());
        assertEquals(1L, result.getItems().get(0).getProductId());
        assertEquals(1, result.getDropped());
    }

    @Test
    void dropsExcludedProduct() throws Exception {
        ParseRequest request = request(Collections.singletonList(1L), catalog(1L, 2L));
        JsonNode output = mapper.readTree("{\"items\":[{\"productId\":1,\"quantity\":1},"
                + "{\"productId\":2,\"quantity\":1}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(1, result.getItems().size());
        assertEquals(2L, result.getItems().get(0).getProductId());
        assertEquals(1, result.getDropped());
    }

    @Test
    void mergesDuplicateProductIdsAndCapsAt99() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L));
        JsonNode output = mapper.readTree("{\"items\":["
                + "{\"productId\":1,\"quantity\":60},"
                + "{\"productId\":1,\"quantity\":60}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(1, result.getItems().size());
        // 60+60 夹到 99
        assertEquals(99, result.getItems().get(0).getQuantity());
    }

    @Test
    void clampsQuantityToValidRange() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L, 2L));
        JsonNode output = mapper.readTree("{\"items\":["
                + "{\"productId\":1,\"quantity\":0},"
                + "{\"productId\":2,\"quantity\":500}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(1, result.getItems().get(0).getQuantity());
        assertEquals(99, result.getItems().get(1).getQuantity());
    }

    @Test
    void capsItemsAt20() throws Exception {
        Long[] ids = new Long[25];
        for (int index = 0; index < 25; index++) {
            ids[index] = (long) (index + 1);
        }
        ParseRequest request = request(Collections.emptyList(), catalog(ids));

        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int index = 0; index < 25; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"productId\":").append(index + 1).append(",\"quantity\":1}");
        }
        json.append("]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(mapper.readTree(json.toString()), request);

        assertEquals(OutputSanitizer.MAX_ITEMS, result.getItems().size());
    }

    @Test
    void keepsFirstSeenOrder() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L, 2L, 3L));
        JsonNode output = mapper.readTree("{\"items\":["
                + "{\"productId\":3,\"quantity\":1},"
                + "{\"productId\":1,\"quantity\":1},"
                + "{\"productId\":3,\"quantity\":2}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(3L, result.getItems().get(0).getProductId());
        assertEquals(1L, result.getItems().get(1).getProductId());
        assertEquals(3, result.getItems().get(0).getQuantity());
    }

    @Test
    void deduplicatesUnmatchedAndFillsDefaultReason() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L));
        JsonNode output = mapper.readTree("{\"unmatched\":["
                + "{\"text\":\"升降桌\",\"reason\":\"\"},"
                + "{\"text\":\"升降桌\",\"reason\":\"重复\"},"
                + "{\"text\":\"  \",\"reason\":\"空白\"},"
                + "{\"text\":\"显示器支架\",\"reason\":\"暂无\"}]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(output, request);

        assertEquals(2, result.getUnmatched().size());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
        // reason 为空时用默认值
        assertEquals(OutputSanitizer.DEFAULT_UNMATCHED_REASON,
                result.getUnmatched().get(0).getReason());
    }

    @Test
    void capsUnmatchedAt10() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L));
        StringBuilder json = new StringBuilder("{\"unmatched\":[");
        for (int index = 0; index < 15; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"text\":\"item").append(index).append("\",\"reason\":\"x\"}");
        }
        json.append("]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(mapper.readTree(json.toString()), request);

        assertEquals(OutputSanitizer.MAX_UNMATCHED, result.getUnmatched().size());
    }

    @Test
    void truncatesQuestionsTo100CharsAndCapsAt5() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L));
        StringBuilder longQuestion = new StringBuilder();
        for (int index = 0; index < 150; index++) {
            longQuestion.append('问');
        }
        StringBuilder json = new StringBuilder("{\"questions\":[");
        json.append("{\"text\":\"").append(longQuestion).append("\"}");
        for (int index = 0; index < 8; index++) {
            json.append(",{\"text\":\"q").append(index).append("\"}");
        }
        json.append("]}");

        OutputSanitizer.Result result = OutputSanitizer.sanitize(mapper.readTree(json.toString()), request);

        assertEquals(OutputSanitizer.MAX_QUESTIONS, result.getQuestions().size());
        assertEquals(OutputSanitizer.MAX_QUESTION_LENGTH,
                result.getQuestions().get(0).getText().length());
    }

    @Test
    void emptyOutputYieldsEmptyLists() throws Exception {
        ParseRequest request = request(Collections.emptyList(), catalog(1L));

        OutputSanitizer.Result result = OutputSanitizer.sanitize(
                mapper.readTree("{}"), request);

        assertTrue(result.getItems().isEmpty());
        assertTrue(result.getUnmatched().isEmpty());
        assertTrue(result.getQuestions().isEmpty());
        assertEquals(0, result.getDropped());
    }

    // ---------------------------------------------------------------- helpers

    private static ParseRequest request(java.util.List<Long> exclude, java.util.List<CatalogItem> catalog) {
        ParseRequest request = new ParseRequest();
        request.setRequestId("drf_test");
        request.setText("test");
        request.setExcludeProductIds(new ArrayList<>(exclude));
        request.setCatalog(new ArrayList<>(catalog));
        return request;
    }

    private static java.util.List<CatalogItem> catalog(Long... ids) {
        java.util.List<CatalogItem> items = new ArrayList<>();
        for (Long id : ids) {
            items.add(new CatalogItem(id, "name" + id, null, "cat", "tag",
                    new BigDecimal("1.00"), 10));
        }
        return items;
    }
}
