package com.example.na.order.assistant.domain;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class DraftQuestion {

    private static final int MAX_CANDIDATES = 5;

    private final String text;
    private final List<DraftCandidate> candidates;

    public DraftQuestion(String text, List<DraftCandidate> candidates) {
        this.text = text;
        if (candidates == null || candidates.isEmpty()) {
            this.candidates = Collections.emptyList();
        } else {
            this.candidates = Collections.unmodifiableList(new ArrayList<>(
                    candidates.subList(0, Math.min(MAX_CANDIDATES, candidates.size()))));
        }
    }
}
