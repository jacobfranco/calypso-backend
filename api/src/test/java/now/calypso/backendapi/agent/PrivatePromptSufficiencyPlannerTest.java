package now.calypso.backendapi.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PrivatePromptSufficiencyPlannerTest {
    @Test
    void hobbiesListNeedsShareabilityOrMeaningGuidance() {
        PrivatePromptSufficiencyPlanner.SufficiencyPlan plan = PrivatePromptSufficiencyPlanner.plan(
                new PrivatePromptTurnResponder.TurnInput(
                        "private.hobbies",
                        "What are your hobbies? Which hobbies would you like to share with your partner?",
                        "What are your hobbies?",
                        List.of(),
                        "hiking, cooking, and board games"));

        assertTrue(plan.needsMoreDetail);
        assertTrue(plan.missing.contains("personal_meaning"));
        assertTrue(plan.guidance.toLowerCase().contains("strongest named activity"));
    }

    @Test
    void formativeReferenceOnlyNeedsImprintOnce() {
        PrivatePromptSufficiencyPlanner.SufficiencyPlan plan = PrivatePromptSufficiencyPlanner.plan(
                new PrivatePromptTurnResponder.TurnInput(
                        "private.formative.imprints",
                        "What things from growing up still have a hold on you or bring you nostalgia?",
                        "What things from growing up still have a hold on you or bring you nostalgia?",
                        List.of(),
                        "Okami, Katamari Damacy, and Carmen Sandiego."));

        assertTrue(plan.needsMoreDetail);
        assertTrue(plan.missing.contains("lasting_imprint"));
    }

    @Test
    void priorFollowupLetsSparseAnswerComplete() {
        PrivatePromptSufficiencyPlanner.SufficiencyPlan plan = PrivatePromptSufficiencyPlanner.plan(
                new PrivatePromptTurnResponder.TurnInput(
                        "private.formative.imprints",
                        "What things from growing up still have a hold on you or bring you nostalgia?",
                        "What things from growing up still have a hold on you or bring you nostalgia?",
                        List.of(
                                "agent: What things from growing up still have a hold on you or bring you nostalgia?",
                                "user: Okami and Katamari.",
                                "agent: What part of those stayed with you?"),
                        "I'm not sure exactly."));

        assertFalse(plan.needsMoreDetail);
    }
}
