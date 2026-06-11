package now.calypso.backend;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import now.calypso.backend.data.MatchStandardAnswer;
import now.calypso.backend.data.Importance;

public final class MatchStandardScorer {
    private MatchStandardScorer() {
    }

    public static Breakdown score(Map<?, ?> viewerAnswersRaw, Map<?, ?> targetAnswersRaw) {
        Map<?, ?> viewerAnswers = viewerAnswersRaw == null ? Map.of() : viewerAnswersRaw;
        Map<?, ?> targetAnswers = targetAnswersRaw == null ? Map.of() : targetAnswersRaw;
        LinkedHashSet<String> questionIds = new LinkedHashSet<>();
        collectQuestionIds(questionIds, viewerAnswers.keySet());
        collectQuestionIds(questionIds, targetAnswers.keySet());

        if (questionIds.isEmpty()) {
            return new Breakdown(0.62, 0, 0, 0, 0, 1.0, false, 0);
        }

        double weightedScore = 0.0;
        double totalWeight = 0.0;
        int sharedCount = 0;
        int knownDealbreakerConflicts = 0;
        int knownSoftConflicts = 0;
        int unknownImportantCount = 0;

        for (String questionId : questionIds) {
            MatchStandardAnswer viewer = asAnswer(viewerAnswers.get(questionId));
            MatchStandardAnswer target = asAnswer(targetAnswers.get(questionId));
            boolean viewerAnswered = hasOwnAnswer(viewer);
            boolean targetAnswered = hasOwnAnswer(target);

            if (viewerAnswered && targetAnswered) {
                sharedCount++;
                boolean viewerAcceptsTarget = accepts(viewer, target);
                boolean targetAcceptsViewer = accepts(target, viewer);
                boolean viewerDealbreaker = importance(viewer) == Importance.DEALBREAKER;
                boolean targetDealbreaker = importance(target) == Importance.DEALBREAKER;
                if ((viewerDealbreaker && !viewerAcceptsTarget) || (targetDealbreaker && !targetAcceptsViewer)) {
                    knownDealbreakerConflicts++;
                }
                if (!viewerAcceptsTarget || !targetAcceptsViewer) {
                    knownSoftConflicts++;
                }

                double viewerWeight = weight(importance(viewer));
                double targetWeight = weight(importance(target));
                weightedScore += (viewerAcceptsTarget ? 1.0 : 0.0) * viewerWeight;
                weightedScore += (targetAcceptsViewer ? 1.0 : 0.0) * targetWeight;
                totalWeight += viewerWeight + targetWeight;
            } else if (viewerAnswered || targetAnswered) {
                MatchStandardAnswer answered = viewerAnswered ? viewer : target;
                Importance importance = importance(answered);
                if (importance == Importance.PREFERENCE || importance == Importance.DEALBREAKER) {
                    unknownImportantCount++;
                }
            }
        }

        double score = totalWeight <= 0.0 ? 0.62 : clamp01(weightedScore / totalWeight);
        double coverage = questionIds.isEmpty() ? 1.0 : clamp01((double) sharedCount / (double) questionIds.size());
        return new Breakdown(
                score,
                sharedCount,
                questionIds.size(),
                knownDealbreakerConflicts,
                unknownImportantCount,
                coverage,
                knownDealbreakerConflicts > 0,
                knownSoftConflicts);
    }

    private static void collectQuestionIds(Set<String> out, Collection<?> rawKeys) {
        if (rawKeys == null) {
            return;
        }
        for (Object raw : rawKeys) {
            if (raw == null) {
                continue;
            }
            String key = raw.toString().trim();
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
    }

    private static MatchStandardAnswer asAnswer(Object raw) {
        return raw instanceof MatchStandardAnswer answer ? answer : null;
    }

    private static boolean hasOwnAnswer(MatchStandardAnswer answer) {
        return answer != null
                && answer.isSetOwnAnswerOptionIds()
                && answer.getOwnAnswerOptionIds() != null
                && !answer.getOwnAnswerOptionIds().isEmpty();
    }

    private static Importance importance(MatchStandardAnswer answer) {
        if (answer == null || !answer.isSetImportance() || answer.getImportance() == null) {
            return Importance.NOT_IMPORTANT;
        }
        return answer.getImportance();
    }

    private static boolean accepts(MatchStandardAnswer preferenceHolder, MatchStandardAnswer other) {
        if (!hasOwnAnswer(preferenceHolder) || !hasOwnAnswer(other)) {
            return true;
        }
        if (importance(preferenceHolder) == Importance.NOT_IMPORTANT) {
            return true;
        }
        if (!preferenceHolder.isSetAcceptableAnswerOptionIds()
                || preferenceHolder.getAcceptableAnswerOptionIds() == null
                || preferenceHolder.getAcceptableAnswerOptionIds().isEmpty()) {
            return true;
        }
        Set<String> acceptable = new LinkedHashSet<>(preferenceHolder.getAcceptableAnswerOptionIds());
        for (String optionId : other.getOwnAnswerOptionIds()) {
            if (optionId == null || optionId.isBlank()) {
                continue;
            }
            if (!acceptable.contains(optionId)) {
                return false;
            }
        }
        return true;
    }

    private static double weight(Importance importance) {
        if (importance == Importance.DEALBREAKER) {
            return 1.75;
        }
        if (importance == Importance.PREFERENCE) {
            return 1.0;
        }
        return 0.35;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    public record Breakdown(
            double score,
            int sharedCount,
            int totalAnsweredUnion,
            int knownDealbreakerConflicts,
            int unknownImportantCount,
            double coverage,
            boolean hardBlocked,
            int knownSoftConflicts) {
    }
}
