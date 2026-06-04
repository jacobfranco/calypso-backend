package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.ai.AiDecisionLog;

class SignalExtractionAuditTest {
    @AfterEach
    void clear() {
        AiDecisionLog.clearForTests();
    }

    @Test
    void recordCapturesNegativeAndIntentCounts() {
        SignalExtractionAudit.record(
                7L,
                "private.not.my.person",
                "private_prompt",
                "instance_1",
                "What are some interests or lifestyles that would make you think not my person?",
                "I really do not like clubbing.",
                List.of(),
                List.of(ExtractedSignal.from("clubbing", SignalIntent.SELF, -0.8)));

        Map<String, Object> snapshot = AiDecisionLog.snapshot(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) snapshot.get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) events.get(0).get("details");
        assertEquals(1, details.get("signalCount"));
        assertEquals(List.of("clubbing"), details.get("negativeTokens"));
    }
}
