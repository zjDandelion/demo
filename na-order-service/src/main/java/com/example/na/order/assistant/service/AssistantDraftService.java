package com.example.na.order.assistant.service;

import com.example.na.order.assistant.api.AssistantPort;
import com.example.na.order.assistant.client.AssistantCatalogItem;
import com.example.na.order.assistant.client.AssistantClient;
import com.example.na.order.assistant.client.AssistantParseRequest;
import com.example.na.order.assistant.client.AssistantParseResponse;
import com.example.na.order.assistant.client.AssistantParsedItem;
import com.example.na.order.assistant.client.AssistantParsedQuestion;
import com.example.na.order.assistant.client.AssistantParsedUnmatched;
import com.example.na.order.assistant.client.ParseOutcome;
import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.config.UserRateLimiter;
import com.example.na.order.assistant.domain.DegradedReason;
import com.example.na.order.assistant.domain.DraftItem;
import com.example.na.order.assistant.domain.DraftQuestion;
import com.example.na.order.assistant.domain.DraftResult;
import com.example.na.order.assistant.domain.DraftSource;
import com.example.na.order.assistant.domain.DraftUnmatched;
import com.example.na.order.catalog.api.ProductInfo;
import com.example.na.order.shared.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AssistantDraftService implements AssistantPort {

    private static final Logger log = LoggerFactory.getLogger(AssistantDraftService.class);

    private static final int MAX_QUESTIONS = 5;
    private static final int MAX_QUESTION_LENGTH = 100;
    private static final int MIN_PREFILTER_ITEMS = 10;
    private static final int MAX_DESCRIPTION_LENGTH = 80;
    private static final double MODEL_SCORE = 0.6;
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private final AssistantProperties properties;
    private final CatalogSnapshotCache catalogCache;
    private final CatalogMatcher catalogMatcher;
    private final AssistantClient assistantClient;
    private final UserRateLimiter rateLimiter;

    @Override
    public DraftResult createDraft(String username, Long userId, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.length() > properties.getMaxTextLength()) {
            throw BusinessException.badRequest(
                    "请输入 1–" + properties.getMaxTextLength() + " 字的商品描述");
        }
        if (!rateLimiter.tryAcquire(username)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试");
        }

        String draftId = newDraftId();
        List<ProductInfo> catalog = catalogCache.get();
        MatchResult match = catalogMatcher.match(catalog, trimmed);

        List<DraftItem> items = new ArrayList<>(match.getItems());
        List<DraftQuestion> questions = new ArrayList<>(match.getQuestions());
        List<DraftUnmatched> unmatched = new ArrayList<>(match.getUnmatched());
        DegradedReason degradedReason = null;
        long startedAt = System.currentTimeMillis();

        if (!properties.isEnabled()) {
            degradedReason = DegradedReason.DISABLED;
        } else if (!match.getUnmatched().isEmpty()) {
            AssistantParseRequest request = buildRequest(draftId, userId, trimmed, catalog,
                    match.getResolvedProductIds());
            ParseOutcome outcome = assistantClient.parse(request);
            if (outcome.isSuccess()) {
                mergeModelResult(items, questions, unmatched, outcome.getResponse(), catalog, match);
            } else {
                degradedReason = outcome.getFailureReason();
            }
        }

        if (degradedReason != null) {
            log.warn("Assistant degraded: draftId={}, reason={}, elapsedMs={}",
                    draftId, degradedReason, System.currentTimeMillis() - startedAt);
        }
        return new DraftResult(draftId, items, unmatched, questions, degradedReason, confidence(items));
    }

    private void mergeModelResult(List<DraftItem> items, List<DraftQuestion> questions,
                                  List<DraftUnmatched> unmatched, AssistantParseResponse response,
                                  List<ProductInfo> catalog, MatchResult match) {
        Map<Long, ProductInfo> byId = new HashMap<>();
        for (ProductInfo product : catalog) {
            byId.put(product.getId(), product);
        }

        Map<Long, Integer> modelQuantities = new LinkedHashMap<>();
        List<DraftUnmatched> unknownProducts = new ArrayList<>();
        for (AssistantParsedItem parsed : response.getItems()) {
            Long productId = parsed.getProductId();
            if (productId == null) {
                continue;
            }
            ProductInfo product = byId.get(productId);
            if (product == null || !product.isEnabled()) {
                unknownProducts.add(new DraftUnmatched("商品#" + productId, "商品不存在或已下架"));
                continue;
            }
            if (match.getResolvedProductIds().contains(productId)) {
                log.debug("Assistant model item ignored, already resolved by rules: productId={}", productId);
                continue;
            }
            int quantity = clampQuantity(parsed.getQuantity());
            modelQuantities.merge(productId, quantity, (left, right) -> Math.min(99, left + right));
        }

        List<DraftItem> modelItems = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : modelQuantities.entrySet()) {
            ProductInfo product = byId.get(entry.getKey());
            int quantity = clampQuantity(entry.getValue());
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            modelItems.add(new DraftItem(product.getId(), product.getName(), quantity, product.getPrice(),
                    subtotal, quantity > product.getStock(), DraftSource.MODEL, MODEL_SCORE));
        }
        int room = Math.max(0, properties.getMaxItems() - items.size());
        if (modelItems.size() > room) {
            modelItems = new ArrayList<>(modelItems.subList(0, room));
        }
        items.addAll(modelItems);

        List<DraftUnmatched> merged = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        for (AssistantParsedUnmatched parsed : response.getUnmatched()) {
            String unmatchedText = parsed.getText() == null ? "" : parsed.getText().trim();
            if (unmatchedText.isEmpty() || !seenTexts.add(unmatchedText)) {
                continue;
            }
            String reason = parsed.getReason() == null || parsed.getReason().trim().isEmpty()
                    ? "暂无此商品"
                    : parsed.getReason().trim();
            merged.add(new DraftUnmatched(unmatchedText, reason));
        }
        for (DraftUnmatched unknown : unknownProducts) {
            if (seenTexts.add(unknown.getText())) {
                merged.add(unknown);
            }
        }
        if (modelQuantities.isEmpty()) {
            for (DraftUnmatched ruleItem : unmatched) {
                if (seenTexts.add(ruleItem.getText())) {
                    merged.add(ruleItem);
                }
            }
        }
        unmatched.clear();
        unmatched.addAll(merged);

        Set<String> seenQuestions = new HashSet<>();
        for (DraftQuestion question : questions) {
            seenQuestions.add(question.getText());
        }
        for (AssistantParsedQuestion parsed : response.getQuestions()) {
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
            String questionText = parsed.getText() == null ? "" : parsed.getText().trim();
            if (questionText.isEmpty() || questionText.length() > MAX_QUESTION_LENGTH
                    || !seenQuestions.add(questionText)) {
                continue;
            }
            questions.add(new DraftQuestion(questionText, Collections.emptyList()));
        }
    }

    private AssistantParseRequest buildRequest(String draftId, Long userId, String text,
                                               List<ProductInfo> catalog, Set<Long> excludedProductIds) {
        List<ProductInfo> selected = prefilterForModel(catalog, text);
        List<AssistantCatalogItem> catalogItems = new ArrayList<>();
        for (ProductInfo product : selected) {
            catalogItems.add(new AssistantCatalogItem(
                    product.getId(),
                    product.getName(),
                    truncate(product.getDescription(), MAX_DESCRIPTION_LENGTH),
                    product.getCategory(),
                    product.getTags(),
                    product.getPrice(),
                    product.getStock()));
        }
        return new AssistantParseRequest(draftId, userId, text,
                new ArrayList<>(new TreeSet<>(excludedProductIds)), catalogItems);
    }

    private List<ProductInfo> prefilterForModel(List<ProductInfo> catalog, String text) {
        int maxCatalogSize = Math.max(1, properties.getMaxCatalogSize());
        int top = Math.max(1, properties.getPrefilterTop());
        if (catalog.size() <= maxCatalogSize) {
            return catalog;
        }

        String normalized = TextNormalizer.normalize(text);
        Map<Long, Integer> scores = new HashMap<>();
        List<ProductInfo> matched = new ArrayList<>();
        for (ProductInfo product : catalog) {
            int score = keywordScore(product, normalized);
            scores.put(product.getId(), score);
            if (score > 0) {
                matched.add(product);
            }
        }
        matched.sort(Comparator.comparingInt((ProductInfo product) -> scores.get(product.getId())).reversed()
                .thenComparing(ProductInfo::getId));

        List<ProductInfo> selected = new ArrayList<>(
                matched.subList(0, Math.min(top, matched.size())));
        int target = Math.min(MIN_PREFILTER_ITEMS, top);
        if (selected.size() < target) {
            Set<Long> chosen = new HashSet<>();
            for (ProductInfo product : selected) {
                chosen.add(product.getId());
            }
            List<ProductInfo> byId = new ArrayList<>(catalog);
            byId.sort(Comparator.comparing(ProductInfo::getId));
            for (ProductInfo product : byId) {
                if (selected.size() >= target) {
                    break;
                }
                if (chosen.add(product.getId())) {
                    selected.add(product);
                }
            }
            log.warn("Assistant catalog prefilter matched too few products: matched={}, sent={}",
                    matched.size(), selected.size());
        }
        selected.sort(Comparator.comparing(ProductInfo::getId));
        return selected;
    }

    private int keywordScore(ProductInfo product, String normalizedText) {
        Set<String> keywords = new LinkedHashSet<>();
        String name = TextNormalizer.normalize(product.getName());
        for (String part : name.split(" ")) {
            if (part.length() >= 2 && !DIGITS.matcher(part).matches()) {
                keywords.add(part);
            }
        }
        if (product.getTags() != null) {
            for (String tag : product.getTags().split("[,，]")) {
                String key = TextNormalizer.normalize(tag);
                if (!key.isEmpty()) {
                    keywords.add(key);
                }
            }
        }
        String category = TextNormalizer.normalize(product.getCategory());
        if (!category.isEmpty()) {
            keywords.add(category);
        }

        int score = 0;
        for (String keyword : keywords) {
            if (normalizedText.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    private static int clampQuantity(Integer quantity) {
        if (quantity == null) {
            return 1;
        }
        return Math.max(1, Math.min(99, quantity));
    }

    private static double confidence(List<DraftItem> items) {
        if (items.isEmpty()) {
            return 0.0;
        }
        double minimum = 1.0;
        for (DraftItem item : items) {
            minimum = Math.min(minimum, item.getScore());
        }
        return minimum;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String newDraftId() {
        return "drf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
