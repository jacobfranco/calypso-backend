package now.calypso.backend;

import now.calypso.backend.data.MatchStandardAnswer;
import now.calypso.backend.data.Importance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchStandardScorerTest {
    @Test
    void unansweredDealbreakerAddsUncertaintyWithoutHardBlocking() {
        MatchStandardAnswer viewer = answer(
                1L,
                "standard.kids.future",
                "wants_kids",
                List.of("wants_kids"),
                Importance.DEALBREAKER);

        MatchStandardScorer.Breakdown breakdown = MatchStandardScorer.score(
                Map.of(viewer.getQuestionId(), viewer),
                Map.of());

        assertFalse(breakdown.hardBlocked());
        assertTrue(breakdown.unknownImportantCount() > 0);
        assertTrue(breakdown.coverage() < 1.0);
    }

    @Test
    void answeredDealbreakerConflictHardBlocks() {
        MatchStandardAnswer viewer = answer(
                1L,
                "standard.kids.future",
                "wants_kids",
                List.of("wants_kids"),
                Importance.DEALBREAKER);
        MatchStandardAnswer target = answer(
                2L,
                "standard.kids.future",
                "does_not_want_kids",
                List.of("does_not_want_kids"),
                Importance.NOT_IMPORTANT);

        MatchStandardScorer.Breakdown breakdown = MatchStandardScorer.score(
                Map.of(viewer.getQuestionId(), viewer),
                Map.of(target.getQuestionId(), target));

        assertTrue(breakdown.hardBlocked());
        assertTrue(breakdown.knownDealbreakerConflicts() > 0);
    }

    private static MatchStandardAnswer answer(
            long accountId,
            String questionId,
            String ownAnswer,
            List<String> acceptableAnswers,
            Importance importance) {
        return new MatchStandardAnswer()
                .setAccountId(accountId)
                .setQuestionId(questionId)
                .setOwnAnswerOptionIds(List.of(ownAnswer))
                .setAcceptableAnswerOptionIds(acceptableAnswers)
                .setImportance(importance)
                .setUpdatedAt(100L);
    }
}
