package now.calypso.backendapi.matchmaking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatchmakingFollowupPlannerTest {
    @Test
    void concretePositiveActivityUsesDirectQuestion() {
        MatchmakingFollowupPlanner.FollowupPlan plan = plan("loves_hiking", 1.0, 0.9, 72.0, 0.7);

        assertEquals(MatchmakingFollowupPlanner.FollowupAction.ASK, plan.action);
        assertEquals(MatchmakingFollowupPlanner.QuestionStrategy.DIRECT_VALENCE, plan.strategy);
        assertEquals("hiking", plan.token);
        assertEquals("How do you feel about hiking?", plan.question);
    }

    @Test
    void broadMusicDoesNotUseGenericDirectQuestion() {
        MatchmakingFollowupPlanner.FollowupPlan plan = plan("music", 1.0, 0.9, 72.0, 0.7);

        assertEquals(MatchmakingFollowupPlanner.FollowupAction.ASK, plan.action);
        assertEquals(MatchmakingFollowupPlanner.QuestionStrategy.BROAD_CATEGORY_NARROWING, plan.strategy);
        assertNotEquals("How do you feel about music?", plan.question);
        assertTrue(plan.question.toLowerCase().contains("music"));
    }

    @Test
    void lowSignalSocialFillerSkipsByDefault() {
        MatchmakingFollowupPlanner.FollowupPlan plan = plan("brunch", 1.0, 1.0, 80.0, 0.9);

        assertEquals(MatchmakingFollowupPlanner.FollowupAction.SKIP, plan.action);
        assertEquals("low_signal_social_filler", plan.skipReason);
    }

    @Test
    void negativeValenceUsesBoundaryQuestionWithoutAntiToken() {
        MatchmakingFollowupPlanner.FollowupPlan plan = plan("clubbing", -1.0, 0.8, 72.0, 0.7);

        assertEquals(MatchmakingFollowupPlanner.FollowupAction.ASK, plan.action);
        assertEquals(MatchmakingFollowupPlanner.QuestionStrategy.NEGATIVE_BOUNDARY, plan.strategy);
        assertEquals("clubbing", plan.token);
        assertNotNull(plan.question);
        assertTrue(plan.question.toLowerCase().contains("clubbing"));
        assertTrue(plan.question.toLowerCase().contains("turns you off"));
    }

    @Test
    void weakPairScoreSkipsEvenGoodToken() {
        MatchmakingFollowupPlanner.FollowupPlan plan = plan("hiking", 1.0, 0.9, 40.0, 0.8);

        assertEquals(MatchmakingFollowupPlanner.FollowupAction.SKIP, plan.action);
        assertEquals("pair_score_too_low", plan.skipReason);
    }

    private static MatchmakingFollowupPlanner.FollowupPlan plan(
            String token,
            double valence,
            double absWeight,
            double pairScore,
            double uncertainty) {
        return MatchmakingFollowupPlanner.plan(new MatchmakingFollowupPlanner.Input(
                1L,
                2L,
                new MatchmakingFollowupPlanner.MissingSignal(token, valence, absWeight, 1),
                pairScore,
                uncertainty,
                null));
    }
}
