package com.example.na.order.assistant.service;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.domain.DraftItem;
import com.example.na.order.assistant.domain.DraftQuestion;
import com.example.na.order.assistant.domain.DraftSource;
import com.example.na.order.assistant.domain.DraftUnmatched;
import com.example.na.order.assistant.support.AssistantTestCatalog;
import com.example.na.order.catalog.api.ProductInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogMatcherTest {

    private final AssistantProperties properties = new AssistantProperties();
    private final CatalogMatcher matcher = new CatalogMatcher(properties);
    private final List<ProductInfo> catalog = AssistantTestCatalog.products();

    @Test
    void acceptanceA_matchesNamedProductAndKeepsUnknownClause() {
        MatchResult result = matcher.match(catalog, "帮我拿两个机械键盘，再加一张升降桌");

        assertEquals(1, result.getItems().size());
        DraftItem item = result.getItems().get(0);
        assertEquals(1L, item.getProductId());
        assertEquals(2, item.getQuantity());
        assertEquals(new BigDecimal("598.00"), item.getSubtotal());
        assertEquals(DraftSource.RULE, item.getSource());
        assertEquals(0.9, item.getScore(), 0.0001);
        assertFalse(item.isStockShortage());

        assertEquals(1, result.getUnmatched().size());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
        assertTrue(result.getQuestions().isEmpty());
    }

    @Test
    void acceptanceB_categoryProducesCandidatesAndNoItems() {
        MatchResult result = matcher.match(catalog, "来点外设");

        assertTrue(result.getItems().isEmpty());
        assertEquals(1, result.getQuestions().size());
        DraftQuestion question = result.getQuestions().get(0);
        assertEquals(4, question.getCandidates().size());
        assertTrue(question.getText().contains("外设"));
        assertTrue(result.getUnmatched().isEmpty());
    }

    @Test
    void acceptanceC_quantityAboveStockIsFlagged() {
        MatchResult result = matcher.match(catalog, "来两个限量鼠标垫");

        assertEquals(1, result.getItems().size());
        DraftItem item = result.getItems().get(0);
        assertEquals(8L, item.getProductId());
        assertEquals(2, item.getQuantity());
        assertTrue(item.isStockShortage());
    }

    @Test
    void tagMatchScoresLowerThanNameMatch() {
        MatchResult result = matcher.match(catalog, "来个键盘");

        assertEquals(1, result.getItems().size());
        assertEquals(1L, result.getItems().get(0).getProductId());
        assertEquals(0.7, result.getItems().get(0).getScore(), 0.0001);
    }

    @Test
    void extraAliasResolvesToProduct() {
        properties.getExtraAliases().put("机械键盘 K87", "码字神器");

        MatchResult result = matcher.match(catalog, "来个码字神器");

        assertEquals(1, result.getItems().size());
        assertEquals(1L, result.getItems().get(0).getProductId());
        assertEquals(0.7, result.getItems().get(0).getScore(), 0.0001);
    }

    @Test
    void repeatedProductQuantityIsSummed() {
        MatchResult result = matcher.match(catalog, "一个机械键盘再加一个机械键盘");

        assertEquals(1, result.getItems().size());
        assertEquals(2, result.getItems().get(0).getQuantity());
    }

    @Test
    void twoProductsInOneSentence() {
        MatchResult result = matcher.match(catalog, "一个机械键盘和一个无线鼠标");

        assertEquals(2, result.getItems().size());
        assertEquals(1L, result.getItems().get(0).getProductId());
        assertEquals(2L, result.getItems().get(1).getProductId());
    }

    @Test
    void conflictingQuantitiesFallBackToOneAndAsk() {
        MatchResult result = matcher.match(catalog, "两个三个机械键盘");

        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getItems().get(0).getQuantity());
        assertEquals(1, result.getQuestions().size());
        assertTrue(result.getQuestions().get(0).getText().contains("数量"));
    }

    @Test
    void arabicQuantityIsParsed() {
        MatchResult result = matcher.match(catalog, "3个机械键盘");

        assertEquals(3, result.getItems().get(0).getQuantity());
    }

    @Test
    void skuDigitsAreNotQuantities() {
        MatchResult result = matcher.match(catalog, "帮我拿机械键盘 k87");

        assertEquals(1, result.getItems().size());
        assertEquals(1, result.getItems().get(0).getQuantity());
        assertTrue(result.getQuestions().isEmpty());
    }

    @Test
    void unmatchedTextIsStrippedOfFillersAndQuantifiers() {
        MatchResult result = matcher.match(catalog, "帮我买点水果");

        assertEquals(1, result.getUnmatched().size());
        assertEquals("水果", result.getUnmatched().get(0).getText());
    }

    @Test
    void disabledProductsAreIgnored() {
        List<ProductInfo> withDisabled = new ArrayList<>(catalog);
        withDisabled.add(new ProductInfo(99L, "下架测试商品", "已下架",
                new BigDecimal("10.00"), 5, false, "外设", "下架,测试"));

        MatchResult result = matcher.match(withDisabled, "下架测试商品");

        assertTrue(result.getItems().isEmpty());
        assertEquals(1, result.getUnmatched().size());
        assertEquals("下架测试商品", result.getUnmatched().get(0).getText());
    }

    @Test
    void itemsAreTruncatedToMaxItems() {
        properties.setMaxItems(1);

        MatchResult result = matcher.match(catalog, "一个机械键盘和一个无线鼠标");

        assertEquals(1, result.getItems().size());
    }

    @Test
    void unmatchedListKeepsOrderOfClauses() {
        MatchResult result = matcher.match(catalog, "升降桌，再加电竞椅");

        List<String> texts = new ArrayList<>();
        for (DraftUnmatched unmatched : result.getUnmatched()) {
            texts.add(unmatched.getText());
        }
        assertEquals(java.util.Arrays.asList("升降桌", "电竞椅"), texts);
    }
}
