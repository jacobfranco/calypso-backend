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

    @Test
    void generate_forcesScopeClarifierWhenTraitDirectionIsAmbiguous() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "Who are some people (historical or living) that you find fascinating? Why?",
                "Who are some people (historical or living) that you find fascinating? Why?",
                List.of(),
                "Nikola Tesla for his independence and obsessive focus.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail, "Ambiguous admired-trait direction should trigger a quick scope clarifier.");
        assertTrue(
                result.agentMessage.toLowerCase().contains("mostly about you")
                        || result.agentMessage.toLowerCase().contains("in a partner"),
                "Clarifier should ask self-vs-partner scope.");
    }

    @Test
    void generate_skipsScopeClarifierWhenUserAlreadySpecifiesBothScopes() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "Who are some people (historical or living) that you find fascinating? Why?",
                "Who are some people (historical or living) that you find fascinating? Why?",
                List.of(),
                "Tesla is fascinating because I see that focus in myself and I also want it in a partner.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail, "Explicit self+partner scope should not be re-clarified.");
    }
}
