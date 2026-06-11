package now.calypso.backendapi.matchstandards;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import now.calypso.backend.data.MatchStandardAnswer;
import now.calypso.backend.data.MatchStandardOption;
import now.calypso.backend.data.MatchStandardQuestion;
import now.calypso.backend.data.MatchStandardQuestionAnswerType;
import now.calypso.backend.data.Importance;
import now.calypso.backendapi.pojos.PostMatchStandardAnswerRequest;

@Component
public class MatchStandardAnswerValidator {
    public MatchStandardAnswer toAnswer(long accountId, String questionId, PostMatchStandardAnswerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Match standard answer payload is required");
        }
        String normalizedQuestionId = questionId == null ? "" : questionId.trim();
        MatchStandardQuestion question = MatchStandardQuestionLibrary.getById(normalizedQuestionId);
        if (question == null) {
            throw new IllegalArgumentException("Unknown match standard question");
        }
        Importance importance = request.importance == null ? Importance.NOT_IMPORTANT : request.importance;
        ArrayList<String> own = normalizeList(request.ownAnswerOptionIds, "ownAnswerOptionIds");
        ArrayList<String> acceptable = normalizeList(request.acceptableAnswerOptionIds, "acceptableAnswerOptionIds");
        Set<String> validOptions = optionIds(question);

        ensureValidOptions(own, validOptions, "ownAnswerOptionIds");
        ensureValidOptions(acceptable, validOptions, "acceptableAnswerOptionIds");

        if (question.getAnswerType() == MatchStandardQuestionAnswerType.SINGLE_CHOICE && own.size() != 1) {
            throw new IllegalArgumentException("single-choice questions require exactly one own answer");
        }
        if (question.getAnswerType() == MatchStandardQuestionAnswerType.MULTI_CHOICE && own.isEmpty()) {
            throw new IllegalArgumentException("multi-choice questions require at least one own answer");
        }
        if (importance != Importance.NOT_IMPORTANT && acceptable.isEmpty()) {
            throw new IllegalArgumentException("acceptable answers are required when a question matters");
        }

        MatchStandardAnswer answer = new MatchStandardAnswer();
        answer.setAccountId(accountId);
        answer.setQuestionId(normalizedQuestionId);
        answer.setOwnAnswerOptionIds(own);
        answer.setAcceptableAnswerOptionIds(acceptable);
        answer.setImportance(importance);
        answer.setUpdatedAt(System.currentTimeMillis());
        return answer;
    }

    private static ArrayList<String> normalizeList(java.util.List<String> raw, String field) {
        LinkedHashSet<String> deduped = new LinkedHashSet<>();
        if (raw != null) {
            for (String value : raw) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                deduped.add(value.trim());
            }
        }
        if (deduped.size() > 20) {
            throw new IllegalArgumentException(field + " has too many values");
        }
        return new ArrayList<>(deduped);
    }

    private static Set<String> optionIds(MatchStandardQuestion question) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (question != null && question.isSetOptions() && question.getOptions() != null) {
            for (MatchStandardOption option : question.getOptions()) {
                if (option != null && option.isSetOptionId() && option.getOptionId() != null) {
                    out.add(option.getOptionId());
                }
            }
        }
        return out;
    }

    private static void ensureValidOptions(java.util.List<String> values, Set<String> validOptions, String field) {
        for (String value : values) {
            if (!validOptions.contains(value)) {
                throw new IllegalArgumentException("Unknown option '" + value + "' in " + field);
            }
        }
    }
}
