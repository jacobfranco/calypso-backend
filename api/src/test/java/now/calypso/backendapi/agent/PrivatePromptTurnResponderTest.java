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
    void generate_asksFormativeImprintFollowupForReferenceOnlyTitles() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Those sound like a few different threads. What did they leave you drawn toward later?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "okami and katamari damacy. also lowkey this carmen sandiego game, treasures of knowledge, also like for some reason affected me.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail,
                "Reference-only formative answers should ask for the missing imprint before submit.");
        assertTrue(result.agentMessage.toLowerCase().contains("drawn toward"));
        assertFalse(result.agentMessage.toLowerCase().contains("board games"));
        assertFalse(result.agentMessage.toLowerCase().contains("what thing comes to mind first"));
    }

    @Test
    void generate_treatsVerboseFormativeAnswerWithIdkAsReferences() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "i'd say like katamari damacy and okami stand out. also where in the world is carmen sandiego treasures of knowledge. also lowkey bugdom and nanosaur. idk a lot of old video games from the early 2000s.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("stayed with you"));
        assertFalse(result.agentMessage.toLowerCase().contains("what thing comes to mind first"));
    }

    @Test
    void generate_replacesUnhelpfulFormativeFirstThingQuestion() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "What thing comes to mind first?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "i'd say like katamari damacy and okami stand out. also where in the world is carmen sandiego treasures of knowledge. also lowkey bugdom and nanosaur. idk a lot of old video games from the early 2000s.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("stayed with you"));
        assertFalse(result.agentMessage.toLowerCase().contains("what thing comes to mind first"));
    }

    @Test
    void generate_replacesFormativeFollowupThatExposesMissingInternalState() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Those point to a certain kind of nostalgia, but I do not have the part that stayed with you. What did those games leave you drawn toward, or still carrying now?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "Katamari Damacy, Okami, Carmen Sandiego, Bugdom, and Nanosaur.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        String lowered = result.agentMessage.toLowerCase();
        assertTrue(result.needsMoreDetail);
        assertTrue(lowered.contains("okay cool"));
        assertTrue(lowered.contains("stayed with you"));
        assertFalse(lowered.contains("i do not have"));
        assertFalse(lowered.contains("carrying"));
    }

    @Test
    void generate_reframesFormativeFollowupWhenUserAsksWhatItMeans() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Think of it as what changed in your taste or curiosity. Did it point more toward style, places, or mood?",
                true));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(
                        "agent: What things from growing up still have a hold on you or bring you nostalgia?",
                        "user: Katamari Damacy, Okami, Carmen Sandiego, Bugdom, and Nanosaur.",
                        "agent: What did those references leave you drawn toward or curious about later?"),
                "what do you mean?");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("style"));
        assertFalse(result.agentMessage.toLowerCase().contains("what did those references leave you drawn toward"));
    }

    @Test
    void generate_reframesInitialFormativeClarificationWithoutInventingReferences() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of("agent: What things from growing up still have a hold on you or bring you nostalgia?"),
                "what do you mean?");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertTrue(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("memory"));
        assertFalse(result.agentMessage.toLowerCase().contains("those references"));
    }

    @Test
    void generate_allowsFormativeImprintAnswerWhenReferenceAndImprintArePresent() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "Okami and Katamari Damacy made me interested in Asian culture and playful surreal aesthetics.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail);
    }

    @Test
    void generate_allowsFormativeImprintAfterSingleFollowupEvenWhenStillSparse() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia? What do they bring back for you?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(
                        "agent: What things from growing up still have a hold on you or bring you nostalgia?",
                        "user: Okami and Katamari Damacy.",
                        "agent: What did those leave you interested in or drawn toward later?"),
                "I'm not sure exactly.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail,
                "The prompt should ask once for the imprint but not trap users who cannot explain it.");
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
    void generate_treatsLongFirstPartAnswerAsCoveringRemainingPromptParts() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "Noted.",
                false));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What thing from growing up still has a strange emotional hold on you? What feeling does it bring back?",
                "What thing from growing up still has a strange emotional hold on you?",
                List.of(),
                "Okami and Katamari Damacy, plus this Carmen Sandiego game, made the world feel huge and strange. As a kid it gave me a sense that there were places and histories far beyond my room, and that travel could make the future feel open instead of fixed.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);
        assertFalse(result.needsMoreDetail);
        assertTrue(result.agentMessage.toLowerCase().contains("submit"),
                "A rich answer should be allowed to submit instead of mechanically advancing to the second prompt part.");
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

    @Test
    void generate_usesContextualCompletionInsteadOfCannedDefaults() {
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "What are your hobbies?",
                "What are your hobbies?",
                List.of(),
                "Bouldering, reading sci-fi, and weekend cooking projects.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);

        String lowered = result.agentMessage.toLowerCase();
        assertFalse(result.needsMoreDetail);
        assertTrue(lowered.contains("spend your time"));
        assertTrue(lowered.contains("submit"));
        assertFalse(lowered.startsWith("got it"));
        assertFalse(lowered.startsWith("noted"));
        assertFalse(lowered.startsWith("understood"));
    }

    @Test
    void generate_preservesUsefulModelObservationWithSubmitCue() {
        PrivatePromptTurnResponder.setTestOverride(input -> new PrivatePromptTurnResponder.TurnResult(
                "The travel thread is doing a lot here.",
                false));
        PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                "private.formative.imprints",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                "What things from growing up still have a hold on you or bring you nostalgia?",
                List.of(),
                "Carmen Sandiego made me want to travel internationally, be like a secret agent, and notice mundane normal life in other countries.");

        PrivatePromptTurnResponder.TurnResult result = PrivatePromptTurnResponder.generate(null, input);

        String lowered = result.agentMessage.toLowerCase();
        assertFalse(result.needsMoreDetail);
        assertTrue(lowered.contains("travel thread"));
        assertTrue(lowered.contains("submit"));
        assertFalse(lowered.startsWith("got it"));
    }
}
