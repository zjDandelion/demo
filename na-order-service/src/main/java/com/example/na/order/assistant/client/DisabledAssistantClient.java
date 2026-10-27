package com.example.na.order.assistant.client;

import com.example.na.order.assistant.domain.DegradedReason;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.assistant", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledAssistantClient implements AssistantClient {

    @Override
    public ParseOutcome parse(AssistantParseRequest request) {
        return ParseOutcome.failed(DegradedReason.DISABLED);
    }
}
