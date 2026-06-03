package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SignalExtractionPolicyTest {

    @Test
    void silhouetteOnlyExamplesAreNotRegisteredConcepts() {
        assertFalse(SignalConceptRegistry.isCanonicalConcept("mind_games"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("passive_aggression"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("vague_punishment"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("pretending_nothing_happened"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("guessing_games"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("ambition"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("kindness"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("communication"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("discipline"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("consistency"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("commitment"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("loyalty"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("honesty"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("empathy"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("respect"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("humor"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("intelligence"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("creativity"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("leadership"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("community"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("networking"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("self_improvement"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("mental_health"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("therapy"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("career_focus"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("academic_ambition"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("early_morning_activity"));
        assertFalse(SignalConceptRegistry.canonicalConceptsSnapshot().contains("pretending_nothing_happened"));
        assertFalse(SignalConceptRegistry.canonicalConceptsSnapshot().contains("guessing_games"));

        assertTrue(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("guessing_games"));
        assertTrue(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("vague_punishment"));
        assertTrue(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("kindness"));
        assertTrue(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("communication"));
        assertFalse(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("travel"));
        assertFalse(SignalExtractionPolicy.shouldKeepSignalTokenSilhouetteOnly("socializing"));
    }

    @Test
    void formativeAndSilhouetteCleanupPolicyStaysOutsideConceptRegistry() {
        assertTrue(SignalExtractionPolicy.shouldSuppressFormativeSignalToken("secret_agent"));
        assertTrue(SignalExtractionPolicy.shouldSuppressFormativeSignalToken("nostalgic_formative_games"));
        assertFalse(SignalExtractionPolicy.shouldSuppressFormativeSignalToken("okami"));
        assertFalse(SignalExtractionPolicy.shouldSuppressFormativeSignalToken("formative_game_title_probe"));

        assertTrue(SignalExtractionPolicy.shouldSuppressFormativeEvidenceToken("video_games"));
        assertFalse(SignalExtractionPolicy.shouldSuppressFormativeEvidenceToken("okami"));
        assertFalse(SignalExtractionPolicy.shouldSuppressFormativeEvidenceToken("formative_game_title_probe"));

        assertTrue(SignalExtractionPolicy.isAllowedFormativeDerivedParent("video_games"));
        assertFalse(SignalExtractionPolicy.isAllowedFormativeDerivedParent("gaming"));
        assertTrue(SignalExtractionPolicy.isLowValueFormativeConceptWord("formative"));
        assertFalse(SignalExtractionPolicy.isLowValueFormativeConceptWord("surreal"));

        assertTrue(SignalExtractionPolicy.looksLikeSilhouetteAbstractConceptText(
                "emotional reciprocity and steady communication"));
        assertFalse(SignalExtractionPolicy.looksLikeSilhouetteAbstractConceptText("video games and anime"));
        assertTrue(SignalExtractionPolicy.isLowValueSilhouetteMetaObservation(
                "focuses on lifestyle and cultural markers"));
    }
}
