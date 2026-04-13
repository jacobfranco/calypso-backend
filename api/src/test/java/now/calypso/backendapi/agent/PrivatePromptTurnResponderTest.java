package now.calypso.backendapi.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PrivatePromptTurnResponderTest {

    @AfterEach
    void clearOverride() {
        PrivatePromptTurnResponder.clearTestOverride();
    }

    @Test
    void generate_suppressesGenericWhyLoopAfterSubstantiveReply() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "That totally makes sense. Why?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What's a historical figure you admire?",
                "What's a historical figure you admire?",
                List.of("agent: What's a historical figure you admire?"),
                "Nikola Tesla because he pursued bold ideas and kept building even when people doubted him.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail, "A substantive answer should not trigger a redundant generic why loop.");
    }

    @Test
    void generate_marksClarifierRequestsAsNeedsMoreDetail() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Got you. Do you mean board games, video games, or both?",
                false));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What kind of games are you into?",
                "What kind of games are you into?",
                List.of(),
                "Games.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail, "Explicit clarification should keep the turn in follow-up mode.");
    }

    @Test
    void generate_doesNotForceFollowupForAnyQuestionMark() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "That tracks. What happened next?",
                false));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "Tell me about something you're proud of.",
                "Tell me about something you're proud of.",
                List.of(),
                "I built a little app that helped my friends split chores each week.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail, "A plain question mark should not automatically force another turn.");
    }
}
