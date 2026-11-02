package com.example.na.assistant.service;

import com.example.na.assistant.web.CatalogItem;
import com.example.na.assistant.web.ParseRequest;
import com.example.na.assistant.web.ParsedItem;
import com.example.na.assistant.web.ParsedQuestion;
import com.example.na.assistant.web.ParsedUnmatched;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 清洗模型输出。
 *
 * <p>与 Python 的 {@code _sanitize} 完全一致。模型可能返回越界 id、重复条目、
 * 超长文本或数量，这里统一收敛到安全范围。
 */
public final class OutputSanitizer {

    static final int MAX_ITEMS = 20;

    static final int MAX_QUESTIONS = 5;

    static final int MAX_QUESTION_LENGTH = 100;

    static final int MAX_UNMATCHED = 10;

    static final int MIN_QUANTITY = 1;

    static final int MAX_QUANTITY = 99;

    static final String DEFAULT_UNMATCHED_REASON = "暂无此商品";

    private OutputSanitizer() {
    }

    /**
     * 清洗结果。
     */
    public static final class Result {

        private final List<ParsedItem> items;

        private final List<ParsedUnmatched> unmatched;

        private final List<ParsedQuestion> questions;

        private final int dropped;

        Result(List<ParsedItem> items, List<ParsedUnmatched> unmatched,
               List<ParsedQuestion> questions, int dropped) {
            this.items = items;
            this.unmatched = unmatched;
            this.questions = questions;
            this.dropped = dropped;
        }

        public List<ParsedItem> getItems() {
            return items;
        }

        public List<ParsedUnmatched> getUnmatched() {
            return unmatched;
        }

        public List<ParsedQuestion> getQuestions() {
            return questions;
        }

        public int getDropped() {
            return dropped;
        }
    }

    /**
     * 清洗模型输出的 {@code ModelOutput} 节点。
     */
    public static Result sanitize(JsonNode output, ParseRequest request) {
        Set<Long> catalogIds = new HashSet<>();
        if (request.getCatalog() != null) {
            for (CatalogItem item : request.getCatalog()) {
                if (item.getId() != null) {
                    catalogIds.add(item.getId());
                }
            }
        }
        Set<Long> excluded = new HashSet<>();
        if (request.getExcludeProductIds() != null) {
            excluded.addAll(request.getExcludeProductIds());
        }

        int dropped = 0;
        Map<Long, Integer> quantities = new LinkedHashMap<>();

        // ---- items
        JsonNode itemsNode = output.path("items");
        if (itemsNode.isArray()) {
            for (JsonNode node : itemsNode) {
                Long productId = asLong(node.get("productId"));
                if (productId == null) {
                    dropped++;
                    continue;
                }
                if (excluded.contains(productId) || !catalogIds.contains(productId)) {
                    dropped++;
                    continue;
                }
                int quantity = clampQuantity(asInt(node.get("quantity")));
                Integer existing = quantities.get(productId);
                if (existing == null) {
                    quantities.put(productId, quantity);
                } else {
                    quantities.put(productId, Math.min(MAX_QUANTITY, existing + quantity));
                }
            }
        }

        List<ParsedItem> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            items.add(new ParsedItem(entry.getKey(), entry.getValue()));
        }

        // ---- unmatched
        List<ParsedUnmatched> unmatched = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        JsonNode unmatchedNode = output.path("unmatched");
        if (unmatchedNode.isArray()) {
            for (JsonNode node : unmatchedNode) {
                String text = trimToEmpty(asText(node.get("text")));
                if (text.isEmpty() || !seenTexts.add(text)) {
                    continue;
                }
                String reason = trimToEmpty(asText(node.get("reason")));
                if (reason.isEmpty()) {
                    reason = DEFAULT_UNMATCHED_REASON;
                }
                unmatched.add(new ParsedUnmatched(text, reason));
                if (unmatched.size() >= MAX_UNMATCHED) {
                    break;
                }
            }
        }

        // ---- questions
        List<ParsedQuestion> questions = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();
        JsonNode questionsNode = output.path("questions");
        if (questionsNode.isArray()) {
            for (JsonNode node : questionsNode) {
                String text = truncate(trimToEmpty(asText(node.get("text"))), MAX_QUESTION_LENGTH);
                if (text.isEmpty() || !seenQuestions.add(text)) {
                    continue;
                }
                questions.add(new ParsedQuestion(text));
                if (questions.size() >= MAX_QUESTIONS) {
                    break;
                }
            }
        }

        return new Result(items, unmatched, questions, dropped);
    }

    private static int clampQuantity(Integer quantity) {
        if (quantity == null) {
            return MIN_QUANTITY;
        }
        return Math.max(MIN_QUANTITY, Math.min(MAX_QUANTITY, quantity));
    }

    private static Long asLong(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Integer asInt(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
