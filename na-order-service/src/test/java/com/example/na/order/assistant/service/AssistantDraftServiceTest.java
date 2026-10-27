package com.example.na.order.assistant.service;

import com.example.na.order.assistant.client.AssistantClient;
import com.example.na.order.assistant.client.AssistantParseRequest;
import com.example.na.order.assistant.client.AssistantParseResponse;
import com.example.na.order.assistant.client.AssistantParsedItem;
import com.example.na.order.assistant.client.AssistantParsedUnmatched;
import com.example.na.order.assistant.client.ParseOutcome;
import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.config.UserRateLimiter;
import com.example.na.order.assistant.domain.DegradedReason;
import com.example.na.order.assistant.domain.DraftItem;
import com.example.na.order.assistant.domain.DraftResult;
import com.example.na.order.assistant.domain.DraftSource;
import com.example.na.order.assistant.support.AssistantTestCatalog;
import com.example.na.order.catalog.api.CatalogPort;
import com.example.na.order.catalog.api.ProductInfo;
import com.example.na.order.shared.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantDraftServiceTest {

    private static final String SCENARIO_A = "帮我拿两个机械键盘，再加一张升降桌";

    private AssistantProperties properties;
    private RecordingAssistantClient client;
    private List<ProductInfo> catalog;
    private AssistantDraftService service;

    @BeforeEach
    void setUp() {
        properties = new AssistantProperties();
        catalog = AssistantTestCatalog.products();
        client = new RecordingAssistantClient();
        service = newService();
    }

    @Test
    void disabledAssistantKeepsRuleItemsAndReportsReason() {
        DraftResult result = service.createDraft("disabled-user", 1L, SCENARIO_A);

        assertTrue(result.isDegraded());
        assertEquals(DegradedReason.DISABLED, result.getDegradedReason());
        assertEquals(1, result.getItems().size());
        assertEquals(2, result.getItems().get(0).getQuantity());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
        assertEquals(0.9, result.getConfidence(), 0.0001);
        assertTrue(result.getDraftId().startsWith("drf_"));
        assertEquals(0, client.calls);
    }

    @Test
    void fullRuleCoverageSkipsModel() {
        properties.setEnabled(true);

        DraftResult result = service.createDraft("fast-path", 1L, "一个机械键盘和一个无线鼠标");

        assertEquals(2, result.getItems().size());
        assertTrue(result.getUnmatched().isEmpty());
        assertNull(result.getDegradedReason());
        assertEquals(0, client.calls);
    }

    @Test
    void modelFillsUnmatchedClauses() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.success(okResponse(
                Collections.singletonList(new AssistantParsedItem(6L, 1))));

        DraftResult result = service.createDraft("merge-user", 7L, SCENARIO_A);

        assertEquals(2, result.getItems().size());
        DraftItem modelItem = result.getItems().get(1);
        assertEquals(6L, modelItem.getProductId());
        assertEquals(DraftSource.MODEL, modelItem.getSource());
        assertEquals(0.6, modelItem.getScore(), 0.0001);
        assertEquals(0.6, result.getConfidence(), 0.0001);
        assertTrue(result.getUnmatched().isEmpty());
        assertNull(result.getDegradedReason());

        AssistantParseRequest request = client.request;
        assertNotNull(request);
        assertEquals(result.getDraftId(), request.getRequestId());
        assertEquals(7L, request.getUserId());
        assertEquals(SCENARIO_A, request.getText());
        assertEquals(Collections.singletonList(1L), request.getExcludeProductIds());
        assertEquals(catalog.size(), request.getCatalog().size());
    }

    @Test
    void rulesWinOverModelForAlreadyResolvedProducts() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.success(okResponse(
                Collections.singletonList(new AssistantParsedItem(1L, 5))));

        DraftResult result = service.createDraft("rules-win", 1L, SCENARIO_A);

        assertEquals(1, result.getItems().size());
        assertEquals(2, result.getItems().get(0).getQuantity());
        assertEquals(DraftSource.RULE, result.getItems().get(0).getSource());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
    }

    @Test
    void unknownModelProductIdBecomesUnmatched() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.success(okResponse(
                Collections.singletonList(new AssistantParsedItem(99L, 1))));

        DraftResult result = service.createDraft("unknown-id", 1L, SCENARIO_A);

        assertEquals(1, result.getItems().size());
        List<String> texts = new ArrayList<>();
        for (com.example.na.order.assistant.domain.DraftUnmatched unmatched : result.getUnmatched()) {
            texts.add(unmatched.getText());
        }
        assertTrue(texts.contains("商品#99"));
        assertTrue(texts.contains("升降桌"));
    }

    @Test
    void modelQuantityIsClamped() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.success(okResponse(
                Collections.singletonList(new AssistantParsedItem(6L, 250))));

        DraftResult result = service.createDraft("clamp", 1L, SCENARIO_A);

        DraftItem modelItem = result.getItems().get(1);
        assertEquals(99, modelItem.getQuantity());
        assertTrue(modelItem.isStockShortage());
    }

    @Test
    void degradedModelKeepsRuleResult() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.failed(DegradedReason.TIMEOUT);

        DraftResult result = service.createDraft("degraded-model", 1L, SCENARIO_A);

        assertTrue(result.isDegraded());
        assertEquals(DegradedReason.TIMEOUT, result.getDegradedReason());
        assertEquals(1, result.getItems().size());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
    }

    @Test
    void modelUnmatchedReplacesRuleUnmatched() {
        properties.setEnabled(true);
        client.outcome = ParseOutcome.success(new AssistantParseResponse(
                "OK", null, Collections.<AssistantParsedItem>emptyList(),
                Collections.singletonList(new AssistantParsedUnmatched("升降桌", "没有匹配的升降桌商品")),
                Collections.emptyList(), null));

        DraftResult result = service.createDraft("model-unmatched", 1L, SCENARIO_A);

        assertEquals(1, result.getUnmatched().size());
        assertEquals("升降桌", result.getUnmatched().get(0).getText());
        assertEquals("没有匹配的升降桌商品", result.getUnmatched().get(0).getReason());
    }

    @Test
    void itemsAreCappedAtMaxItems() {
        properties.setMaxItems(1);

        DraftResult result = service.createDraft("cap", 1L, "一个机械键盘和一个无线鼠标");

        assertEquals(1, result.getItems().size());
    }

    @Test
    void oversizedCatalogIsPrefilteredForTheModel() {
        properties.setEnabled(true);
        properties.setMaxCatalogSize(3);
        properties.setPrefilterTop(3);
        client.outcome = ParseOutcome.success(okResponse(
                Collections.singletonList(new AssistantParsedItem(6L, 1))));

        service.createDraft("prefilter", 1L, SCENARIO_A);

        List<Long> ids = new ArrayList<>();
        client.request.getCatalog().forEach(item -> ids.add(item.getId()));
        assertEquals(3, ids.size());
        assertTrue(ids.contains(1L));
    }

    @Test
    void blankTextIsRejected() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createDraft("blank", 1L, "   "));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertEquals("请输入 1–300 字的商品描述", error.getMessage());
        assertEquals(0, client.calls);
    }

    @Test
    void tooLongTextIsRejected() {
        char[] chars = new char[301];
        Arrays.fill(chars, '机');

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createDraft("long", 1L, new String(chars)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void rateLimitIsApplied() {
        properties.setRateLimitPerMinute(2);
        AssistantDraftService limited = newService();

        limited.createDraft("burst", 1L, "机械键盘");
        limited.createDraft("burst", 1L, "机械键盘");

        BusinessException error = assertThrows(BusinessException.class,
                () -> limited.createDraft("burst", 1L, "机械键盘"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("请求过于频繁，请稍后再试", error.getMessage());
    }

    private AssistantDraftService newService() {
        CatalogSnapshotCache cache = new CatalogSnapshotCache(new FixedCatalogPort(catalog), properties);
        return new AssistantDraftService(properties, cache, new CatalogMatcher(properties),
                client, new UserRateLimiter(properties));
    }

    private static AssistantParseResponse okResponse(List<AssistantParsedItem> items) {
        return new AssistantParseResponse("OK", null, items, Collections.emptyList(),
                Collections.emptyList(), null);
    }

    private static final class RecordingAssistantClient implements AssistantClient {

        private int calls;
        private AssistantParseRequest request;
        private ParseOutcome outcome = ParseOutcome.failed(DegradedReason.DISABLED);

        @Override
        public ParseOutcome parse(AssistantParseRequest request) {
            calls++;
            this.request = request;
            return outcome;
        }
    }

    private static final class FixedCatalogPort implements CatalogPort {

        private final List<ProductInfo> products;

        private FixedCatalogPort(List<ProductInfo> products) {
            this.products = products;
        }

        @Override
        public List<ProductInfo> listEnabled() {
            return products;
        }

        @Override
        public ProductInfo getProduct(Long productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deductStock(Long productId, int quantity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void restoreStock(Long productId, int quantity) {
            throw new UnsupportedOperationException();
        }
    }
}
