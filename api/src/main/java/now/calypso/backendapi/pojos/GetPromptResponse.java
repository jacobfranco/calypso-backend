package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.AttachmentWithId;
import now.calypso.backend.data.PromptQuestion;
import now.calypso.backend.data.PromptReaction;
import now.calypso.backend.data.PromptResponse;

public class GetPromptResponse {
    public final String responseId;
    public final String promptId;
    public final String question;
    public final String topic;
    public final String reaction;
    public final String answerText;
    public final String comment;
    public final Long targetAccountId;
    public final Long servedAt;
    public final Long answeredAt;
    public final List<AttachmentWithId> attachments;

    public GetPromptResponse(PromptResponse response) {
        this(response == null ? null : response.getResponseId(),
                toPromptId(response),
                toQuestion(response),
                toTopic(response),
                toReaction(response),
                response == null ? null : response.getAnswerText(),
                response == null ? null : response.getComment(),
                response == null ? null : response.getRelatedTargetAccountId(),
                response == null || !response.isSetServedAt() ? null : response.getServedAt(),
                response == null || !response.isSetAnsweredAt() ? null : response.getAnsweredAt(),
                response == null || !response.isSetAttachments() ? null : response.getAttachments());
    }

    @JsonCreator
    public GetPromptResponse(
            @JsonProperty("responseId") String responseId,
            @JsonProperty("promptId") String promptId,
            @JsonProperty("question") String question,
            @JsonProperty("topic") String topic,
            @JsonProperty("reaction") String reaction,
            @JsonProperty("answerText") String answerText,
            @JsonProperty("comment") String comment,
            @JsonProperty("targetAccountId") Long targetAccountId,
            @JsonProperty("servedAt") Long servedAt,
            @JsonProperty("answeredAt") Long answeredAt,
            @JsonProperty("attachments") List<AttachmentWithId> attachments) {
        this.responseId = responseId;
        this.promptId = promptId;
        this.question = question;
        this.topic = topic;
        this.reaction = reaction;
        this.answerText = answerText;
        this.comment = comment;
        this.targetAccountId = targetAccountId;
        this.servedAt = servedAt;
        this.answeredAt = answeredAt;
        this.attachments = attachments == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(attachments));
    }

    private static String toPromptId(PromptResponse response) {
        PromptQuestion q = response == null ? null : response.getQuestion();
        return q == null ? null : q.getPromptId();
    }

    private static String toQuestion(PromptResponse response) {
        PromptQuestion q = response == null ? null : response.getQuestion();
        return q == null ? null : q.getQuestion();
    }

    private static String toTopic(PromptResponse response) {
        PromptQuestion q = response == null ? null : response.getQuestion();
        return q == null ? null : q.getTopic();
    }

    private static String toReaction(PromptResponse response) {
        if (response == null || !response.isSetReaction())
            return null;
        PromptReaction reaction = response.getReaction();
        return reaction == null ? null : reaction.name();
    }
}
