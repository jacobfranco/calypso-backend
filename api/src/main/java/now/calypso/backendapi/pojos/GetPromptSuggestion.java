package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.PromptQuestion;
import now.calypso.backendapi.prompts.PromptSuggestion;

public class GetPromptSuggestion {
    public final String promptId;
    public final String question;
    public final String topic;
    public final List<String> tags;
    public final Long targetAccountId;
    public final Double targetScore;

    public GetPromptSuggestion(PromptSuggestion suggestion) {
        this(suggestion == null ? null : suggestion.question());
    }

    public GetPromptSuggestion(PromptQuestion question) {
        this(question, null, null);
    }

    public GetPromptSuggestion(PromptSuggestion suggestion, Long targetAccountId, Double targetScore) {
        this(suggestion == null ? null : suggestion.question(), targetAccountId, targetScore);
    }

    public GetPromptSuggestion(PromptQuestion q, Long targetAccountId, Double targetScore) {
        this(q == null ? null : q.getPromptId(),
                q == null ? null : q.getQuestion(),
                q == null ? null : q.getTopic(),
                q == null ? null : q.getTags(),
                targetAccountId,
                targetScore);
    }

    @JsonCreator
    public GetPromptSuggestion(
            @JsonProperty("promptId") String promptId,
            @JsonProperty("question") String question,
            @JsonProperty("topic") String topic,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("targetAccountId") Long targetAccountId,
            @JsonProperty("targetScore") Double targetScore) {
        this.promptId = promptId;
        this.question = question;
        this.topic = topic;
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
        this.targetAccountId = targetAccountId;
        this.targetScore = targetScore;
    }
}
