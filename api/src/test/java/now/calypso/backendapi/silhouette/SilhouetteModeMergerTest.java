package now.calypso.backendapi.silhouette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SilhouetteModeMergerTest {
    @Test
    void apply_emptySilhouetteCreatesFirstModeAndSummaryCache() {
        SilhouetteState base = SilhouetteState.empty(42L);
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp(
                "mode_grounded_whimsy",
                "grounded whimsy",
                "self_expression",
                "grounded observer",
                "core",
                "Frieren",
                "fictional_comp",
                0.78,
                0.72));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                base,
                patch,
                1.0,
                "private_prompt",
                "instance_1",
                "private.fictional.characters",
                "event_1",
                "Frieren",
                System.currentTimeMillis());

        assertNotNull(merged);
        assertEquals(1, merged.modes.size());
        assertEquals("grounded whimsy", merged.modes.get(0).label);
        assertFalse(merged.summaryCache.rerankerShort.isBlank());
        assertEquals(merged.version, merged.summaryCache.generatedFromVersion);
    }

    @Test
    void apply_compatibleConceptReinforcesExistingModeAndDedupes() {
        SilhouetteState base = SilhouetteState.empty(43L);
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp(
                "mode_grounded_whimsy",
                "grounded whimsy",
                "self_expression",
                "grounded observer",
                "core",
                "Frieren",
                "fictional_comp",
                0.60,
                0.55));
        patch.ops.add(conceptOp(
                "mode_grounded_whimsy",
                "grounded whimsy",
                "self_expression",
                "grounded observer",
                "core",
                "quietly patient presence",
                "private_prompt",
                0.82,
                0.78));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                base,
                patch,
                1.0,
                "private_prompt",
                "instance_2",
                "private.most_myself",
                "event_2",
                "answer",
                System.currentTimeMillis());

        assertEquals(1, merged.modes.size());
        assertEquals(1, merged.modes.get(0).selfExpression.size());
        assertTrue(merged.modes.get(0).selfExpression.get(0).confidence >= 0.82);
        assertEquals(2, merged.modes.get(0).evidence.size());
    }

    @Test
    void apply_distinctExplicitModeCreatesSeparateMode() {
        SilhouetteState first = SilhouetteModeMerger.apply(
                SilhouetteState.empty(44L),
                patchWith(conceptOp("mode_calm_devotion", "calm devotion", "seeking_expression",
                        "steady loyal presence", "core", "drawn to steadiness", "private_prompt", 0.74, 0.70)),
                1.0,
                "private_prompt",
                "instance_a",
                "private.drawn.to",
                "event_a",
                "answer",
                System.currentTimeMillis());

        SilhouetteState merged = SilhouetteModeMerger.apply(
                first,
                patchWith(conceptOp("mode_playful_volatility", "playful volatility", "seeking_expression",
                        "chaotic playful charge", "core", "drawn to chaotic energy", "private_prompt", 0.72, 0.68)),
                1.0,
                "private_prompt",
                "instance_b",
                "private.drawn.to",
                "event_b",
                "answer",
                System.currentTimeMillis());

        assertEquals(2, merged.modes.size());
        assertTrue(merged.modes.stream().anyMatch(m -> "calm devotion".equals(m.label)));
        assertTrue(merged.modes.stream().anyMatch(m -> "playful volatility".equals(m.label)));
    }

    @Test
    void digest_excludesDeprecatedModeAndCapsTopModes() {
        SilhouetteState state = SilhouetteState.empty(45L);
        SilhouettePatch patch = new SilhouettePatch();
        for (int i = 0; i < 5; i++) {
            patch.ops.add(conceptOp("mode_" + i, "mode " + i, "self_expression",
                    "concept " + i, "core", "evidence " + i, "private_prompt", 0.70 + (i * 0.02), 0.70));
        }
        SilhouettePatch.Op deprecated = new SilhouettePatch.Op();
        deprecated.op = "deprecate_mode";
        deprecated.modeId = "mode_4";
        patch.ops.add(deprecated);

        SilhouetteState merged = SilhouetteModeMerger.apply(
                state,
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.most_myself",
                "event",
                "answer",
                System.currentTimeMillis());
        SilhouetteDigest digest = SilhouetteDigest.fromState(merged);

        assertEquals(3, digest.topModes.size());
        assertFalse(digest.topModes.stream().anyMatch(m -> "mode_4".equals(m.id)));
    }

    @Test
    void toMapFromMap_roundTripsModesAndSummaryCache() {
        SilhouetteState state = SilhouetteModeMerger.apply(
                SilhouetteState.empty(46L),
                patchWith(conceptOp("mode_romantic_pull", "romantic pull", "spark_triggers",
                        "composed competence", "accent", "Mustang from Red Rising", "fictional_comp", 0.75, 0.72)),
                1.0,
                "private_prompt",
                "instance",
                "private.fictional.characters",
                "event",
                "answer",
                System.currentTimeMillis());

        Map<String, Object> serialized = state.toMap();
        SilhouetteState decoded = SilhouetteState.fromMap(serialized, state.accountId);

        assertEquals(1, decoded.modes.size());
        assertEquals("romantic pull", decoded.modes.get(0).label);
        assertEquals(1, decoded.modes.get(0).sparkTriggers.size());
        assertNotNull(decoded.summaryCache);
        assertTrue(decoded.summaryCache.rerankerShort.toLowerCase().contains("romantic"));
    }

    @Test
    void maturity_updatesFromSparseToEmergingWithCoverageAndEvidence() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_fit", "relationship fit", "self_expression",
                "steady initiator", "core", "self evidence", "private_prompt", 0.70, 0.70));
        patch.ops.add(conceptOp("mode_fit", "relationship fit", "seeking_expression",
                "emotionally direct partner", "core", "seeking evidence", "private_prompt", 0.70, 0.70));
        patch.ops.add(conceptOp("mode_fit", "relationship fit", "spark_triggers",
                "playful verbal charge", "accent", "spark evidence", "private_prompt", 0.64, 0.62));
        patch.ops.add(conceptOp("mode_fit", "relationship fit", "aesthetic_field",
                "soft city-night aesthetic", "context", "aesthetic evidence", "visual_aesthetic", 0.60, 0.60));
        patch.ops.add(conceptOp("mode_fit", "relationship fit", "self_expression",
                "patient observer", "accent", "extra evidence", "private_prompt", 0.62, 0.61));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(47L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.most_myself",
                "event",
                "answer",
                System.currentTimeMillis());

        assertEquals("emerging", merged.maturity);
    }

    private static SilhouettePatch patchWith(SilhouettePatch.Op op) {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(op);
        return patch;
    }

    private static SilhouettePatch.Op conceptOp(
            String modeId,
            String modeLabel,
            String target,
            String label,
            String role,
            String evidenceValue,
            String evidenceSource,
            double confidence,
            double strength) {
        SilhouetteConcept concept = new SilhouetteConcept();
        concept.id = SilhouetteState.normalizeKey(label);
        concept.label = label;
        concept.role = role;
        concept.confidence = confidence;
        concept.strength = strength;

        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = evidenceSource;
        evidence.target = target;
        evidence.value = evidenceValue;
        evidence.strength = strength;
        evidence.confidence = confidence;
        evidence.sourceWeight = SilhouetteEvidence.defaultSourceWeight(evidenceSource);
        evidence.createdAt = System.currentTimeMillis();

        return SilhouettePatch.Op.upsertConcept(modeId, modeLabel, target, concept, evidence);
    }
}
