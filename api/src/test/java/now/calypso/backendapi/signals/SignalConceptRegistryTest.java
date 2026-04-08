package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;

class SignalConceptRegistryTest {

    @Test
    void resolve_mapsKnownAliasesToCanonicalConcepts() {
        SignalConceptRegistry.Resolution jojo = SignalConceptRegistry.resolve("jojo_bizarre_adventure");
        assertNotNull(jojo);
        assertEquals("jojos_bizarre_adventure", jojo.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.ALIAS, jojo.kind());

        SignalConceptRegistry.Resolution anime = SignalConceptRegistry.resolve("anime_fan");
        assertNotNull(anime);
        assertEquals("anime", anime.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.ALIAS, anime.kind());

        SignalConceptRegistry.Resolution unknown = SignalConceptRegistry.resolve("traveling_adventures");
        assertNotNull(unknown);
        assertEquals("traveling_adventures", unknown.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.UNKNOWN, unknown.kind());
    }

    @Test
    void normalizeAndCanonicalizeTokens_dedupesEquivalentVariants() {
        List<String> canonical = SignalConceptRegistry.normalizeAndCanonicalizeTokens(
                List.of("jojo_bizarre_adventure", "jojos_bizarre_adventure", "anime_fan", "anime"));
        assertEquals(List.of("jojos_bizarre_adventure", "anime"), canonical);
    }

    @Test
    void expandedConceptWeights_includesHierarchicalParentsWithDecay() {
        var weights = SignalConceptRegistry.expandedConceptWeights("high_fashion", 3);
        assertNotNull(weights);
        assertTrue(weights.containsKey("high_fashion"));
        assertTrue(weights.containsKey("fashion"));
        assertTrue(weights.get("high_fashion") > weights.get("fashion"));
    }

    @Test
    void candidateLifecycle_observePromoteAndReject() {
        String raw = "ultra_specific_unknown_v3_candidate";
        SignalConceptRegistry.observeUnresolved(raw, "test", "sample context");

        List<SignalConceptRegistry.CandidateEntry> before = SignalConceptRegistry.candidateSnapshot(500);
        assertTrue(before.stream().anyMatch(entry -> raw.equals(entry.rawToken)));

        assertTrue(SignalConceptRegistry.promoteAlias(raw, "travel"));
        SignalConceptRegistry.Resolution promoted = SignalConceptRegistry.resolve(raw);
        assertNotNull(promoted);
        assertEquals("travel", promoted.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.ALIAS, promoted.kind());

        List<SignalConceptRegistry.CandidateEntry> afterPromote = SignalConceptRegistry.candidateSnapshot(500);
        assertFalse(afterPromote.stream().anyMatch(entry -> raw.equals(entry.rawToken)));

        String rejected = "another_unknown_v3_candidate";
        SignalConceptRegistry.observeUnresolved(rejected, "test", "reject me");
        SignalConceptRegistry.observeUnresolved("travel_planing", "test", "close variant");
        List<SignalConceptRegistry.CandidateEntry> withSuggestion = SignalConceptRegistry.candidateSnapshot(500);
        assertTrue(withSuggestion.stream().anyMatch(entry -> "travel_planing".equals(entry.rawToken)
                && entry.suggestedCanonical != null
                && entry.suggestionScore != null
                && entry.suggestionScore.doubleValue() > 0.0));
        assertTrue(SignalConceptRegistry.rejectCandidate(rejected));
        List<SignalConceptRegistry.CandidateEntry> afterReject = SignalConceptRegistry.candidateSnapshot(500);
        assertFalse(afterReject.stream().anyMatch(entry -> rejected.equals(entry.rawToken)));
    }

    @Test
    void autoPromoteReadyCandidates_promotesHighConfidenceSuggestions() {
        String raw = "travel_plannng";
        SignalConceptRegistry.observeUnresolved(raw, "test", "trip planning");
        SignalConceptRegistry.observeUnresolved(raw, "test", "trip planning again");
        SignalConceptRegistry.observeUnresolved(raw, "test", "trip planning third");

        int promoted = SignalConceptRegistry.autoPromoteReadyCandidates();
        assertTrue(promoted >= 1);

        SignalConceptRegistry.Resolution resolved = SignalConceptRegistry.resolve(raw);
        assertNotNull(resolved);
        assertEquals("travel", resolved.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.ALIAS, resolved.kind());
    }

    @Test
    void observeUnresolved_tracksAccountIntentObservations() {
        String raw = "candidate_observation_v3";
        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx1", 101L, SignalIntent.SELF, 0.80);
        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx2", 101L, SignalIntent.SELF, 0.60);
        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx3", 202L, SignalIntent.SEEKING, -0.70);

        List<SignalConceptRegistry.CandidateAccountIntentObservation> observations = SignalConceptRegistry
                .candidateAccountIntentObservations(raw);
        assertEquals(2, observations.size());

        SignalConceptRegistry.CandidateAccountIntentObservation self = observations.stream()
                .filter(observation -> observation.accountId == 101L && observation.intent == SignalIntent.SELF)
                .findFirst()
                .orElseThrow();
        assertEquals(2, self.seenCount);
        assertEquals(0.70, self.averageValence, 1.0e-6);

        SignalConceptRegistry.CandidateAccountIntentObservation seeking = observations.stream()
                .filter(observation -> observation.accountId == 202L && observation.intent == SignalIntent.SEEKING)
                .findFirst()
                .orElseThrow();
        assertEquals(1, seeking.seenCount);
        assertEquals(-0.70, seeking.averageValence, 1.0e-6);
    }
}
