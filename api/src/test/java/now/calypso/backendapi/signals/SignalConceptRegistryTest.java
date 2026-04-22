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

        SignalConceptRegistry.Resolution redRising = SignalConceptRegistry.resolve("red rising");
        assertNotNull(redRising);
        assertEquals("red_rising", redRising.canonicalToken());
        assertEquals(SignalConceptRegistry.ResolutionKind.CANONICAL, redRising.kind());

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

    @Test
    void blockAndUnblockCandidate_suppressesAndRestoresQueueEntry() {
        String raw = "blocked_candidate_v3_token";
        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx-a");
        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx-b");

        SignalConceptRegistry.CandidateEntry before = SignalConceptRegistry.candidateSnapshot(500).stream()
                .filter(entry -> entry != null && raw.equals(entry.rawToken))
                .findFirst()
                .orElseThrow();
        assertEquals(2, before.seenCount);

        assertTrue(SignalConceptRegistry.blockCandidate(raw));
        assertFalse(SignalConceptRegistry.candidateSnapshot(500).stream()
                .anyMatch(entry -> entry != null && raw.equals(entry.rawToken)));

        SignalConceptRegistry.BlockedCandidateEntry blocked = SignalConceptRegistry.blockedSnapshot(500).stream()
                .filter(entry -> entry != null && raw.equals(entry.rawToken))
                .findFirst()
                .orElseThrow();
        assertEquals(2, blocked.seenCount);
        assertTrue(blocked.blockedAt > 0L);

        SignalConceptRegistry.observeUnresolved(raw, "test", "ctx-c");
        SignalConceptRegistry.BlockedCandidateEntry blockedAfterObserve = SignalConceptRegistry.blockedSnapshot(500)
                .stream()
                .filter(entry -> entry != null && raw.equals(entry.rawToken))
                .findFirst()
                .orElseThrow();
        assertEquals(2, blockedAfterObserve.seenCount, "Blocked candidates should ignore fresh observations.");

        assertTrue(SignalConceptRegistry.unblockCandidate(raw));
        assertFalse(SignalConceptRegistry.blockedSnapshot(500).stream()
                .anyMatch(entry -> entry != null && raw.equals(entry.rawToken)));

        SignalConceptRegistry.CandidateEntry restored = SignalConceptRegistry.candidateSnapshot(500).stream()
                .filter(entry -> entry != null && raw.equals(entry.rawToken))
                .findFirst()
                .orElseThrow();
        assertEquals(2, restored.seenCount, "Unblocking should restore previous candidate evidence.");
    }

    @Test
    void isCanonicalConcept_returnsTrueForCanonicalAndFalseForAliasOrUnknown() {
        assertTrue(SignalConceptRegistry.isCanonicalConcept("travel"));
        assertTrue(SignalConceptRegistry.isCanonicalConcept("anime"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("anime_fan"));
        assertFalse(SignalConceptRegistry.isCanonicalConcept("unknown_canonical_v3"));
    }

    @Test
    void categoryForConcept_resolvesCanonicalAliasAndHeuristics() {
        assertEquals(SignalTaxonomy.MEDIA, SignalConceptRegistry.categoryForConcept("red_rising"));
        assertEquals(SignalTaxonomy.MEDIA, SignalConceptRegistry.categoryForConcept("anime_fan"));
        assertEquals(SignalTaxonomy.HOBBIES, SignalConceptRegistry.categoryForConcept("hiking"));

        String raw = "category_probe_tv_" + System.nanoTime();
        SignalConceptRegistry.observeUnresolved(raw, "test", "Reality TV mention");
        SignalConceptRegistry.CandidateEntry candidate = SignalConceptRegistry.candidateSnapshot(500).stream()
                .filter(entry -> entry != null && raw.equals(entry.rawToken))
                .findFirst()
                .orElseThrow();
        assertEquals(SignalTaxonomy.MEDIA, candidate.suggestedCategory,
                "Unknown drift candidates should carry heuristic category suggestions.");
    }

    @Test
    void promoteAlias_withCategoryPersistsCategoryOnCanonicalConcept() {
        String suffix = Long.toString(System.nanoTime());
        String raw = "raw_category_alias_" + suffix;
        String canonical = "canonical_category_concept_" + suffix;

        assertTrue(SignalConceptRegistry.promoteAlias(raw, canonical, SignalTaxonomy.VALUES));
        assertEquals(SignalTaxonomy.VALUES, SignalConceptRegistry.categoryForConcept(canonical));
        assertEquals(SignalTaxonomy.VALUES, SignalConceptRegistry.categoryForConcept(raw));

        SignalConceptRegistry.ConceptEntry concept = SignalConceptRegistry.conceptsSnapshot().stream()
                .filter(entry -> entry != null && canonical.equals(entry.concept))
                .findFirst()
                .orElseThrow();
        assertEquals(SignalTaxonomy.VALUES, concept.category);

        assertTrue(SignalConceptRegistry.setCanonicalCategory(canonical, SignalTaxonomy.SOCIAL_STYLE));
        assertEquals(SignalTaxonomy.SOCIAL_STYLE, SignalConceptRegistry.categoryForConcept(canonical));
    }

    @Test
    void expandedConceptWeights_propagatesFranchiseToGenreAndBooks() {
        var weights = SignalConceptRegistry.expandedConceptWeights("red_rising", 3);
        assertNotNull(weights);
        assertTrue(weights.containsKey("red_rising"));
        assertTrue(weights.containsKey("sci_fi"));
        assertTrue(weights.containsKey("books"));
        assertTrue(weights.get("red_rising") > weights.get("sci_fi"));
        assertTrue(weights.get("sci_fi") > weights.get("books"));
    }

    @Test
    void expandedConceptWeights_propagatesTeamToLeagueToSport() {
        var weights = SignalConceptRegistry.expandedConceptWeights("carolina_panthers", 3);
        assertNotNull(weights);
        assertTrue(weights.containsKey("carolina_panthers"));
        assertTrue(weights.containsKey("nfl"));
        assertTrue(weights.containsKey("sports"));
        assertTrue(weights.get("carolina_panthers") > weights.get("nfl"));
        assertTrue(weights.get("nfl") > weights.get("sports"));
    }
}
