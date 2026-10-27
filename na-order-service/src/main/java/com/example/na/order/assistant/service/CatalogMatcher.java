package com.example.na.order.assistant.service;

import com.example.na.order.assistant.config.AssistantProperties;
import com.example.na.order.assistant.domain.DraftCandidate;
import com.example.na.order.assistant.domain.DraftItem;
import com.example.na.order.assistant.domain.DraftQuestion;
import com.example.na.order.assistant.domain.DraftSource;
import com.example.na.order.assistant.domain.DraftUnmatched;
import com.example.na.order.catalog.api.ProductInfo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class CatalogMatcher {

    private static final Logger log = LoggerFactory.getLogger(CatalogMatcher.class);

    private static final Pattern CLAUSE_SEPARATOR =
            Pattern.compile("[，,。；;、!?！？\\n\\r]+|以及|还有|另外|顺便|加上|再加|再|和|跟|加");
    private static final Pattern QUANTITY_WITH_UNIT =
            Pattern.compile("(?<![a-z0-9])([0-9]{1,3})\\s*(份|个|张|把|台|只|套|瓶|包|盒|件|条|块|杯|位|名|种)");
    private static final Pattern CHINESE_QUANTITY =
            Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(份|个|张|把|台|只|套|瓶|包|盒|件|条|块|杯|位|名|种)?");
    private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");
    private static final Pattern EDGE_PUNCTUATION =
            Pattern.compile("^[\\s,，。；;、!?！？]+|[\\s,，。；;、!?！？]+$");

    private static final int MAX_QUESTIONS = 5;
    private static final double NAME_SCORE = 0.9;
    private static final double TAG_SCORE = 0.7;

    private static final String[] LEADING_FILLERS = {
            "帮我", "帮忙", "麻烦", "给我", "我想", "我要", "想要", "请", "来点", "一点", "一些",
            "来", "要", "拿", "买", "加", "上", "点", "份", "个", "只", "张", "把", "台", "套",
            "瓶", "包", "盒", "件", "条", "块"
    };
    private static final String[] TRAILING_PARTICLES = {"吧", "呢", "啊", "哦", "呀", "了"};

    private final AssistantProperties properties;

    public MatchResult match(List<ProductInfo> catalog, String text) {
        Index index = buildIndex(catalog);
        Map<Long, ItemAccumulator> items = new LinkedHashMap<>();
        List<DraftUnmatched> unmatched = new ArrayList<>();
        List<DraftQuestion> questions = new ArrayList<>();
        Set<Long> resolved = new LinkedHashSet<>();

        for (String clause : splitClauses(TextNormalizer.normalize(text))) {
            List<Hit> hits = index.nameHits(clause);
            double score = NAME_SCORE;
            if (hits.isEmpty()) {
                hits = index.tagHits(clause);
                score = TAG_SCORE;
            }

            if (!hits.isEmpty()) {
                if (hits.size() == 1) {
                    Hit hit = hits.get(0);
                    Quantity quantity = extractQuantity(clause, hit);
                    if (quantity.ambiguous) {
                        addQuestion(questions, new DraftQuestion(
                                "无法确认「" + hit.product.getName() + "」的数量，请核对",
                                Collections.emptyList()));
                    }
                    mergeItem(items, hit.product, quantity.value, score);
                    resolved.add(hit.product.getId());
                } else {
                    for (Hit hit : hits) {
                        resolved.add(hit.product.getId());
                    }
                    addQuestion(questions, new DraftQuestion(
                            "「" + hits.get(0).key + "」匹配到多个商品，请选择",
                            candidatesFromHits(hits)));
                }
                continue;
            }

            CategoryHit category = index.categoryHit(clause);
            if (category != null) {
                for (ProductInfo product : category.products) {
                    resolved.add(product.getId());
                }
                addQuestion(questions, new DraftQuestion(
                        "「" + category.name + "」包含多个商品，请选择",
                        candidatesFromProducts(category.products)));
                continue;
            }

            String leftover = cleanupClause(clause);
            if (!leftover.isEmpty()) {
                unmatched.add(new DraftUnmatched(leftover, "暂无此商品"));
            }
        }

        List<DraftItem> draftItems = new ArrayList<>();
        int limit = Math.max(0, properties.getMaxItems());
        for (ItemAccumulator accumulator : items.values()) {
            if (draftItems.size() >= limit) {
                log.debug("Draft items truncated at {}", limit);
                break;
            }
            draftItems.add(accumulator.toDraftItem());
        }
        return new MatchResult(draftItems, unmatched, questions, resolved);
    }

    private Index buildIndex(List<ProductInfo> catalog) {
        Map<String, ProductInfo> names = new LinkedHashMap<>();
        Map<String, List<ProductInfo>> tags = new LinkedHashMap<>();
        Map<String, List<ProductInfo>> categories = new LinkedHashMap<>();

        for (ProductInfo product : catalog) {
            if (product == null || !product.isEnabled()) {
                continue;
            }
            String fullName = TextNormalizer.normalize(product.getName());
            if (!fullName.isEmpty()) {
                names.putIfAbsent(fullName, product);
            }
            String baseName = baseName(fullName);
            if (baseName != null) {
                names.putIfAbsent(baseName, product);
            }
            for (String tag : splitTags(product.getTags())) {
                String key = TextNormalizer.normalize(tag);
                if (!key.isEmpty()) {
                    tags.computeIfAbsent(key, ignored -> new ArrayList<>()).add(product);
                }
            }
            String category = TextNormalizer.normalize(product.getCategory());
            if (!category.isEmpty()) {
                categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(product);
            }
        }

        Map<String, String> extraAliases = properties.getExtraAliases();
        if (extraAliases != null) {
            for (Map.Entry<String, String> entry : extraAliases.entrySet()) {
                ProductInfo product = names.get(TextNormalizer.normalize(entry.getKey()));
                if (product == null) {
                    continue;
                }
                for (String alias : splitTags(entry.getValue())) {
                    String key = TextNormalizer.normalize(alias);
                    if (!key.isEmpty()) {
                        tags.computeIfAbsent(key, ignored -> new ArrayList<>()).add(product);
                    }
                }
            }
        }
        return new Index(names, tags, categories);
    }

    private static List<String> splitClauses(String normalizedText) {
        List<String> clauses = new ArrayList<>();
        for (String clause : CLAUSE_SEPARATOR.split(normalizedText)) {
            String trimmed = clause.trim();
            if (!trimmed.isEmpty()) {
                clauses.add(trimmed);
            }
        }
        return clauses;
    }

    private static String baseName(String fullName) {
        int space = fullName.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        String base = fullName.substring(0, space).trim();
        if (base.length() < 2 || DIGITS_ONLY.matcher(base).matches()) {
            return null;
        }
        return base;
    }

    private static List<String> splitTags(String tags) {
        List<String> result = new ArrayList<>();
        if (tags == null || tags.trim().isEmpty()) {
            return result;
        }
        for (String tag : tags.split("[,，]")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<Hit> findHits(String clause, Map<String, ProductInfo> index) {
        List<Hit> raw = new ArrayList<>();
        for (Map.Entry<String, ProductInfo> entry : index.entrySet()) {
            String key = entry.getKey();
            int from = 0;
            int found;
            while ((found = clause.indexOf(key, from)) >= 0) {
                raw.add(new Hit(entry.getValue(), key, found, found + key.length()));
                from = found + key.length();
            }
        }
        return maximal(raw);
    }

    private static List<Hit> findTagHits(String clause, Map<String, List<ProductInfo>> index) {
        List<Hit> raw = new ArrayList<>();
        for (Map.Entry<String, List<ProductInfo>> entry : index.entrySet()) {
            String key = entry.getKey();
            int from = 0;
            int found;
            while ((found = clause.indexOf(key, from)) >= 0) {
                for (ProductInfo product : entry.getValue()) {
                    raw.add(new Hit(product, key, found, found + key.length()));
                }
                from = found + key.length();
            }
        }
        return maximal(raw);
    }

    private static List<Hit> maximal(List<Hit> raw) {
        raw.sort(Comparator.comparingInt((Hit hit) -> hit.end - hit.start).reversed()
                .thenComparingInt(hit -> hit.start));
        List<Hit> kept = new ArrayList<>();
        Map<Long, Hit> distinct = new LinkedHashMap<>();
        for (Hit candidate : raw) {
            boolean covered = false;
            for (Hit existing : kept) {
                if (candidate.start >= existing.start && candidate.end <= existing.end) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            kept.add(candidate);
            distinct.putIfAbsent(candidate.product.getId(), candidate);
        }
        return new ArrayList<>(distinct.values());
    }

    private static Quantity extractQuantity(String clause, Hit hit) {
        List<Integer> values = new ArrayList<>();

        Matcher arabic = QUANTITY_WITH_UNIT.matcher(clause);
        while (arabic.find()) {
            if (!inside(arabic.start(), arabic.end(), hit)) {
                values.add(Integer.parseInt(arabic.group(1)));
            }
        }
        Matcher chinese = CHINESE_QUANTITY.matcher(clause);
        while (chinese.find()) {
            if (!inside(chinese.start(), chinese.end(), hit)) {
                values.add(parseChineseNumber(chinese.group(1)));
            }
        }

        if (values.isEmpty()) {
            return new Quantity(1, false);
        }
        if (values.size() > 1) {
            return new Quantity(1, true);
        }
        int value = values.get(0);
        if (value <= 0) {
            return new Quantity(1, true);
        }
        return new Quantity(Math.min(99, value), false);
    }

    private static boolean inside(int start, int end, Hit hit) {
        return start >= hit.start && end <= hit.end;
    }

    private static int parseChineseNumber(String value) {
        if (value.contains("十")) {
            int index = value.indexOf('十');
            int tens = index == 0 ? 1 : chineseDigit(value.charAt(0));
            int ones = index == value.length() - 1 ? 0 : chineseDigit(value.charAt(index + 1));
            if (tens < 0 || ones < 0) {
                return -1;
            }
            return tens * 10 + ones;
        }
        if (value.length() != 1) {
            return -1;
        }
        return chineseDigit(value.charAt(0));
    }

    private static int chineseDigit(char character) {
        switch (character) {
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    private static String cleanupClause(String clause) {
        String remaining = QUANTITY_WITH_UNIT.matcher(clause).replaceAll(" ");
        remaining = CHINESE_QUANTITY.matcher(remaining).replaceAll(" ").trim();

        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 10) {
            changed = false;
            for (String filler : LEADING_FILLERS) {
                if (remaining.startsWith(filler)) {
                    remaining = remaining.substring(filler.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        for (String particle : TRAILING_PARTICLES) {
            if (remaining.endsWith(particle)) {
                remaining = remaining.substring(0, remaining.length() - particle.length()).trim();
            }
        }
        return EDGE_PUNCTUATION.matcher(remaining).replaceAll("").trim();
    }

    private static void mergeItem(Map<Long, ItemAccumulator> items, ProductInfo product,
                                  int quantity, double score) {
        ItemAccumulator accumulator = items.get(product.getId());
        if (accumulator == null) {
            items.put(product.getId(), new ItemAccumulator(product, quantity, score));
        } else {
            accumulator.quantity = Math.min(99, accumulator.quantity + quantity);
            accumulator.score = Math.max(accumulator.score, score);
        }
    }

    private static void addQuestion(List<DraftQuestion> questions, DraftQuestion question) {
        if (questions.size() < MAX_QUESTIONS) {
            questions.add(question);
        }
    }

    private static List<DraftCandidate> candidatesFromHits(List<Hit> hits) {
        List<DraftCandidate> candidates = new ArrayList<>();
        for (Hit hit : hits) {
            candidates.add(toCandidate(hit.product));
        }
        return candidates;
    }

    private static List<DraftCandidate> candidatesFromProducts(List<ProductInfo> products) {
        List<DraftCandidate> candidates = new ArrayList<>();
        for (ProductInfo product : products) {
            candidates.add(toCandidate(product));
        }
        return candidates;
    }

    private static DraftCandidate toCandidate(ProductInfo product) {
        return new DraftCandidate(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }

    private static final class Index {

        private final Map<String, ProductInfo> names;
        private final Map<String, List<ProductInfo>> tags;
        private final Map<String, List<ProductInfo>> categories;

        private Index(Map<String, ProductInfo> names, Map<String, List<ProductInfo>> tags,
                      Map<String, List<ProductInfo>> categories) {
            this.names = names;
            this.tags = tags;
            this.categories = categories;
        }

        private List<Hit> nameHits(String clause) {
            return findHits(clause, names);
        }

        private List<Hit> tagHits(String clause) {
            return findTagHits(clause, tags);
        }

        private CategoryHit categoryHit(String clause) {
            CategoryHit best = null;
            for (Map.Entry<String, List<ProductInfo>> entry : categories.entrySet()) {
                if (clause.contains(entry.getKey())
                        && (best == null || entry.getKey().length() > best.name.length())) {
                    best = new CategoryHit(entry.getKey(), entry.getValue());
                }
            }
            return best;
        }
    }

    private static final class Hit {

        private final ProductInfo product;
        private final String key;
        private final int start;
        private final int end;

        private Hit(ProductInfo product, String key, int start, int end) {
            this.product = product;
            this.key = key;
            this.start = start;
            this.end = end;
        }
    }

    private static final class CategoryHit {

        private final String name;
        private final List<ProductInfo> products;

        private CategoryHit(String name, List<ProductInfo> products) {
            this.name = name;
            this.products = products;
        }
    }

    private static final class Quantity {

        private final int value;
        private final boolean ambiguous;

        private Quantity(int value, boolean ambiguous) {
            this.value = value;
            this.ambiguous = ambiguous;
        }
    }

    private static final class ItemAccumulator {

        private final ProductInfo product;
        private int quantity;
        private double score;

        private ItemAccumulator(ProductInfo product, int quantity, double score) {
            this.product = product;
            this.quantity = quantity;
            this.score = score;
        }

        private DraftItem toDraftItem() {
            int finalQuantity = Math.max(1, Math.min(99, quantity));
            BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(finalQuantity));
            return new DraftItem(product.getId(), product.getName(), finalQuantity, product.getPrice(),
                    subtotal, finalQuantity > product.getStock(), DraftSource.RULE, score);
        }
    }
}
