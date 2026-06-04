package now.calypso.backendapi.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class QuestionBudgetPlannerTest {
    @Test
    void selectPrefersUtilityThenPriorityThenStableOrder() {
        QuestionBudgetPlanner.Selection selection = QuestionBudgetPlanner.select(List.of(
                new QuestionBudgetPlanner.QuestionCandidate(
                        "matchmaking_followup",
                        "low",
                        "How do you feel about hiking?",
                        0.40,
                        100,
                        1L,
                        Map.of()),
                new QuestionBudgetPlanner.QuestionCandidate(
                        "matchmaking_followup",
                        "best",
                        "What kind of music do you actually connect with?",
                        0.70,
                        10,
                        2L,
                        Map.of()),
                new QuestionBudgetPlanner.QuestionCandidate(
                        "matchmaking_followup",
                        "empty",
                        "",
                        1.00,
                        100,
                        0L,
                        Map.of())));

        assertNotNull(selection.selected);
        assertEquals("best", selection.selected.key);
        assertEquals(3, selection.consideredCount);
        assertEquals(2, selection.eligibleCount());
    }
}
