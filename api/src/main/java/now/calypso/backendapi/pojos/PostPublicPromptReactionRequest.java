package now.calypso.backendapi.pojos;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.PromptReaction;

public class PostPublicPromptReactionRequest {
    public final String reaction;
    public final Integer strength;

    @JsonCreator
    public PostPublicPromptReactionRequest(@JsonProperty("reaction") String reaction,
            @JsonProperty("strength") Integer strength) {
        this.reaction = reaction;
        this.strength = strength;
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

    public Integer parsedPublicPromptStrength() {
        if (strength != null) {
            int value = strength.intValue();
            if (value < -3 || value > 3) {
                return null;
            }
            return value;
        }
        PromptReaction parsed = parsedReaction();
        if (parsed == null) {
            return null;
        }
        switch (parsed) {
            case LIKE:
                return 1;
            case DISLIKE:
                return -1;
            case SKIP:
                return 0;
            default:
                return null;
        }
    }
}
