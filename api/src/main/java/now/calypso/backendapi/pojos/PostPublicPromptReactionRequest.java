package now.calypso.backendapi.pojos;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.PromptReaction;

public class PostPublicPromptReactionRequest {
    public final String reaction;

    @JsonCreator
    public PostPublicPromptReactionRequest(@JsonProperty("reaction") String reaction) {
        this.reaction = reaction;
    }

    public PromptReaction parsedReaction() {
        if (reaction == null)
            return null;
        String normalized = reaction.trim().toUpperCase(Locale.ROOT);
        try {
            return PromptReaction.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
