package com.example.na.order.assistant.client;

import com.example.na.order.assistant.domain.DegradedReason;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantDegradationTest {

    @Test
    void okStatusIsSuccess() {
        ParseOutcome outcome = ParseOutcome.of(response("OK", null));

        assertTrue(outcome.isSuccess());
        assertNull(outcome.getFailureReason());
    }

    @Test
    void degradedStatusMapsReason() {
        ParseOutcome outcome = ParseOutcome.of(response("DEGRADED", "TIMEOUT"));

        assertFalse(outcome.isSuccess());
        assertEquals(DegradedReason.TIMEOUT, outcome.getFailureReason());
    }

    @Test
    void degradedReasonIsCaseInsensitive() {
        assertEquals(DegradedReason.UNREACHABLE,
                ParseOutcome.of(response("DEGRADED", "unreachable")).getFailureReason());
    }

    @Test
    void unknownReasonFallsBackToUpstreamError() {
        assertEquals(DegradedReason.UPSTREAM_ERROR,
                ParseOutcome.of(response("DEGRADED", "boom")).getFailureReason());
        assertEquals(DegradedReason.UPSTREAM_ERROR,
                ParseOutcome.of(response("DEGRADED", null)).getFailureReason());
    }

    @Test
    void unrecognizedStatusUsesReasonButUnknownReasonFallsBack() {
        assertEquals(DegradedReason.TIMEOUT,
                ParseOutcome.of(response("ERROR", "TIMEOUT")).getFailureReason());
        assertEquals(DegradedReason.UPSTREAM_ERROR,
                ParseOutcome.of(response("ERROR", "unexpected")).getFailureReason());
    }

    @Test
    void nullResponseIsInvalidOutput() {
        assertEquals(DegradedReason.INVALID_OUTPUT, ParseOutcome.of(null).getFailureReason());
    }

    @Test
    void failedWithoutReasonIsUpstreamError() {
        assertEquals(DegradedReason.UPSTREAM_ERROR, ParseOutcome.failed(null).getFailureReason());
    }

    @Test
    void disabledClientStillReportsDisabled() {
        ParseOutcome outcome = new DisabledAssistantClient().parse(null);

        assertFalse(outcome.isSuccess());
        assertEquals(DegradedReason.DISABLED, outcome.getFailureReason());
    }

    private AssistantParseResponse response(String status, String reason) {
        return new AssistantParseResponse(status, reason, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), null);
    }
}
