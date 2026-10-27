package com.example.na.order.assistant.api;

import com.example.na.order.assistant.domain.DraftResult;

public interface AssistantPort {

    DraftResult createDraft(String username, Long userId, String text);
}
