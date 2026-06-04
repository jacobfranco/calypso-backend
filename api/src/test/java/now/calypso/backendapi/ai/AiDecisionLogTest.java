package now.calypso.backendapi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiDecisionLogTest {
    @AfterEach
    void clear() {
        AiDecisionLog.clearForTests();
    }

    @Test
    void snapshotAggregatesAndSanitizesRecentEvents() {
        AiDecisionLog.record(
                "Private Prompt",
                "Sufficiency",
                "Needs More Detail",
                42L,
                null,
                Map.of(
                        "missing", List.of("personal_meaning"),
                        "raw field", "  useful detail  "));

        Map<String, Object> snapshot = AiDecisionLog.snapshot(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) snapshot.get("totals");
        assertEquals(1L, totals.get("decisions"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) snapshot.get("events");
        assertEquals(1, events.size());
        assertEquals("private_prompt", events.get(0).get("surface"));
        assertEquals("sufficiency", events.get(0).get("stage"));
        assertEquals("needs_more_detail", events.get(0).get("action"));
        assertFalse(events.get(0).isEmpty());
    }
}
