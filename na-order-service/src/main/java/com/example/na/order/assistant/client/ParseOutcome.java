package com.example.na.order.assistant.client;

import com.example.na.order.assistant.domain.DegradedReason;
import lombok.Getter;

import java.util.Locale;

@Getter
public final class ParseOutcome {

    private final AssistantParseResponse response;
    private final DegradedReason failureReason;

    private ParseOutcome(AssistantParseResponse response, DegradedReason failureReason) {
        this.response = response;
        this.failureReason = failureReason;
    }

    public static ParseOutcome success(AssistantParseResponse response) {
        return new ParseOutcome(response, null);
    }

    public static ParseOutcome failed(DegradedReason reason) {
        return new ParseOutcome(null, reason == null ? DegradedReason.UPSTREAM_ERROR : reason);
    }

    public static ParseOutcome of(AssistantParseResponse response) {
        if (response == null) {
            return failed(DegradedReason.INVALID_OUTPUT);
        }
        if ("OK".equalsIgnoreCase(response.getStatus())) {
            return success(response);
        }
        return failed(toReason(response.getReason()));
    }

    public boolean isSuccess() {
        return failureReason == null;
    }

    private static DegradedReason toReason(String reason) {
        if (reason != null) {
            try {
                return DegradedReason.valueOf(reason.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the generic upstream error
            }
        }
        return DegradedReason.UPSTREAM_ERROR;
    }
}
