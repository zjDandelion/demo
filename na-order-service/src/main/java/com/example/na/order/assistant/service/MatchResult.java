package com.example.na.order.assistant.service;

import com.example.na.order.assistant.domain.DraftItem;
import com.example.na.order.assistant.domain.DraftQuestion;
import com.example.na.order.assistant.domain.DraftUnmatched;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Getter
public class MatchResult {

    private final List<DraftItem> items;
    private final List<DraftUnmatched> unmatched;
    private final List<DraftQuestion> questions;
    private final Set<Long> resolvedProductIds;

    public MatchResult(List<DraftItem> items, List<DraftUnmatched> unmatched,
                       List<DraftQuestion> questions, Set<Long> resolvedProductIds) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.unmatched = Collections.unmodifiableList(new ArrayList<>(unmatched));
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
        this.resolvedProductIds = Collections.unmodifiableSet(new LinkedHashSet<>(resolvedProductIds));
    }
}
