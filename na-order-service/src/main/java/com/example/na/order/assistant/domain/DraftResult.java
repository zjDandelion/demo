package com.example.na.order.assistant.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class DraftResult {

    private final String draftId;
    private final List<DraftItem> items;
    private final List<DraftUnmatched> unmatched;
    private final List<DraftQuestion> questions;
    private final boolean degraded;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final DegradedReason degradedReason;

    private final double confidence;

    public DraftResult(String draftId, List<DraftItem> items, List<DraftUnmatched> unmatched,
                       List<DraftQuestion> questions, DegradedReason degradedReason, double confidence) {
        this.draftId = draftId;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.unmatched = Collections.unmodifiableList(new ArrayList<>(unmatched));
        this.questions = Collections.unmodifiableList(new ArrayList<>(questions));
        this.degraded = degradedReason != null;
        this.degradedReason = degradedReason;
        this.confidence = confidence;
    }
}
