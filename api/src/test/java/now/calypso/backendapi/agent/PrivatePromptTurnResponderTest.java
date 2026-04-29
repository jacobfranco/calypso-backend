package now.calypso.backendapi.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertNotEquals("That totally makes sense. Why?", result.agentMessage);
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
    void generate_respectsExplicitSubmitIntent() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Want to share one more detail so I can tune this better?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies?",
                "What are your hobbies?",
                List.of(),
                "That's all for now, I'm ready to submit.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail, "Explicit submit intent should allow finishing the flow.");
    }

    @Test
    void generate_doesNotBlockOnRewriteOffer() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "If you want, I can help turn that into a smoother dating-app style answer for you.",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies?",
                "What are your hobbies?",
                List.of(),
                "Bouldering, reading sci-fi, and weekend cooking projects.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail, "Rewrite/coaching offer should not lock the user into follow-up mode.");
    }

    @Test
    void generate_doesNotHoldOptionalClarifierWhenAnotherPartRemains() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Got it. Could you share a little more detail on that?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies? Which hobbies would you like to share with your partner?",
                "What are your hobbies?",
                List.of(),
                "I climb, cook, and play pickup soccer every week.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail,
                "When another explicit prompt part is still pending, optional clarifier loops should not block progress.");
        assertFalse(result.agentMessage.toLowerCase().contains("could you share"));
        assertFalse(result.agentMessage.toLowerCase().contains("submit"),
                "Interim acknowledgements should not tell users to submit before the next prompt part is asked.");
    }

    @Test
    void generate_allowsClarifierOnFinalPromptPart() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Got it. Could you share a little more detail on that?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies? Which hobbies would you like to share with your partner?",
                "Which hobbies would you like to share with your partner?",
                List.of(),
                "I would love to share climbing and weekend cooking projects.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail,
                "Optional clarifier loops should not block completion, even on the final prompt part.");
    }

    @Test
    void generate_replacesParrotingAcknowledgementWithNeutralForwardPrompt() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Got it, you like long walks on the beach and margaritas.",
                false));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies?",
                "What are your hobbies?",
                List.of(),
                "Long walks on the beach and margaritas.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("submit"));
        assertFalse(result.agentMessage.toLowerCase().contains("long walks"));
        assertFalse(result.agentMessage.toLowerCase().contains("margaritas"));
    }

    @Test
    void generate_neutralizesLoadedNegativeValenceFollowupWording() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "How much does reality tv turn you off in a partner?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What's something popular that you really don't like?",
                "What's something popular that you really don't like?",
                List.of(),
                "Idk.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail);
        String lowered = result.agentMessage.toLowerCase();
        assertTrue(lowered.contains("how do you feel about that"));
        assertFalse(lowered.contains("turn you off"));
        assertFalse(lowered.contains("dealbreaker"));
    }

    @Test
    void generate_doesNotAskExtraClarifierForConciseConcreteAnswer() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "What about it makes it feel like home for you?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What communities or scenes have you felt most at home in?",
                "What communities or scenes have you felt most at home in?",
                List.of(),
                "The gym.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("submit"));
    }
}
