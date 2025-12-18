package now.calypso.backendapi.prompts;

import now.calypso.backend.data.PromptQuestion;

public record PromptSuggestion(PromptQuestion question, Long targetAccountId, Double targetScore) {
}
