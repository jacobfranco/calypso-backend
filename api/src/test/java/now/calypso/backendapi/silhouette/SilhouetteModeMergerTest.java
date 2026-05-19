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
        assertFalse(merged.summaryCache.silhouette.isBlank());
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
        assertTrue(decoded.summaryCache.silhouette.toLowerCase().contains("romantic"));
        assertFalse(decoded.summaryCache.toMap().containsKey("rerankerShort"));
        assertFalse(decoded.summaryCache.toMap().containsKey("adminLong"));
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

    @Test
    void apply_formativePromptSuppressesGenericConceptButKeepsEvidenceAsLightSeed() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_imprint", "formative imprint", "self_expression",
                "nostalgic formative games", "context", "Okami", "formative_imprint", 0.60, 0.58));
        patch.ops.add(evidenceOp("mode_formative_imprint", "formative imprint", "self_expression",
                "Okami", "formative_imprint", 0.68, 0.62));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(48L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Okami and Katamari Damacy.",
                System.currentTimeMillis());

        assertEquals(1, merged.modes.size());
        assertEquals("formative media imprints", merged.modes.get(0).label);
        assertTrue(merged.modes.get(0).selfExpression.isEmpty());
        assertEquals(1, merged.modes.get(0).evidence.size());
        assertFalse(merged.summaryCache.silhouette.toLowerCase().contains("nostalgic formative games"));
        assertTrue(merged.summaryCache.silhouette.toLowerCase().contains("formative media imprints"));
        assertTrue(merged.modes.get(0).evidence.stream().anyMatch(e -> e.value.equals("Okami")),
                "Evidence-only formative references should stay as stored evidence without becoming summary concepts.");
    }

    @Test
    void apply_formativePromptRepairsGenericModeAndPreservesDistinctFacetsInSilhouette() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media_nostalgia", "formative media nostalgia", "aesthetic_field",
                "playful surreal asian aesthetic affinity", "context", "Okami and Katamari Damacy",
                "formative_imprint", 0.78, 0.72));
        patch.ops.add(conceptOp("mode_formative_media_nostalgia", "formative media nostalgia", "self_expression",
                "travel adventure curiosity", "context", "Carmen Sandiego secret agent travel fantasy",
                "formative_imprint", 0.74, 0.70));
        patch.ops.add(conceptOp("mode_formative_media_nostalgia", "formative media nostalgia", "aesthetic_field",
                "early 2000s game-world texture", "context", "Bugdom and Nanosaur",
                "formative_imprint", 0.68, 0.64));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(49L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Okami and Katamari exposed me to Asian aesthetics. Carmen Sandiego made me want to be a secret agent and travel the world. Bugdom and Nanosaur are old early-2000s games.",
                System.currentTimeMillis());

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertEquals(1, merged.modes.size());
        assertEquals("formative media imprints", merged.modes.get(0).label);
        assertFalse(silhouette.contains("formative media nostalgia"));
        assertTrue(silhouette.contains("playful surreal asian aesthetic affinity"));
        assertTrue(silhouette.contains("travel adventure curiosity"));
        assertTrue(silhouette.contains("early 2000s game-world texture"));
        assertFalse(silhouette.contains("matching:"));
        assertTrue(merged.modes.get(0).evidence.stream().anyMatch(e -> e.value.contains("Okami and Katamari")));
        assertTrue(merged.modes.get(0).evidence.stream().anyMatch(e -> e.value.contains("Carmen Sandiego")));
        assertTrue(merged.modes.get(0).evidence.stream().anyMatch(e -> e.value.contains("Bugdom and Nanosaur")));
    }

    @Test
    void apply_formativePromptShowsInterpretiveImprintsSeparatelyFromReferenceSeeds() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media_nostalgia", "formative media nostalgia", "self_expression",
                "ordinary-life world travel curiosity", "context",
                "Carmen Sandiego made international travel feel exciting, ordinary, and livable",
                "formative_imprint", 0.78, 0.74));
        patch.ops.add(evidenceOp("mode_formative_media_nostalgia", "formative media nostalgia", "self_expression",
                "Okami", "formative_imprint", 0.62, 0.68));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(50L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Okami gave me playful aesthetics. Carmen Sandiego made me want to travel internationally and notice ordinary life in other countries.",
                System.currentTimeMillis());

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertTrue(silhouette.contains("self:ordinary-life world travel curiosity"));
        assertFalse(silhouette.contains("imprint:"));
        assertFalse(silhouette.contains("seed:"));
        assertTrue(merged.modes.get(0).evidence.stream()
                .anyMatch(e -> e.value.toLowerCase().contains("carmen sandiego made international travel feel exciting")));
        assertTrue(merged.modes.get(0).evidence.stream().anyMatch(e -> e.value.equals("Okami")));
    }

    @Test
    void apply_formativePromptDowngradesLossyUmbrellaConceptButKeepsImprintEvidence() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media and aesthetic imprint", "self_expression",
                "nostalgic formative media and worldview shaping", "context",
                "Carmen Sandiego made international travel feel exciting, ordinary, and livable",
                "formative_imprint", 0.58, 0.56));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(51L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Carmen Sandiego made me want to travel internationally and experience mundane normal life in other countries.",
                System.currentTimeMillis());

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertTrue(merged.modes.get(0).selfExpression.isEmpty());
        assertFalse(silhouette.contains("nostalgic formative media and worldview shaping"));
        assertFalse(silhouette.contains("imprint:"));
        assertTrue(merged.modes.get(0).evidence.stream()
                .anyMatch(e -> e.value.toLowerCase().contains("carmen sandiego made international travel feel exciting")));
    }

    @Test
    void apply_formativePromptRepairsChildhoodCrushWordingIntoAdultPhysicalTypeCue() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "childhood crush shaping adult attraction", "context",
                "Karate Kid (Jaden Smith) female lead crush influenced adult attraction type",
                "formative_imprint", 0.75, 0.70));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(52L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "The Karate Kid made me notice that the female lead is physically my type as an adult.",
                System.currentTimeMillis());

        assertTrue(merged.modes.get(0).selfExpression.isEmpty());
        assertTrue(merged.modes.get(0).sparkTriggers.isEmpty());
        assertTrue(merged.modes.get(0).realWorldComps.stream()
                .anyMatch(c -> "Wenwen Han / Meiying".equals(c.label)));
        assertTrue(merged.modes.get(0).evidence.stream()
                .anyMatch(e -> e.value.contains("Meiying / Wenwen Han")));
    }

    @Test
    void apply_formativePromptDropsBareLadyGagaMovieEraFromSilhouette() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "real_world_comps",
                "Wenwen Han / Meiying", "context",
                "Lady Gaga music influence tied to Karate Kid movie era and image",
                "formative_imprint", 0.70, 0.65));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(53L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Lady Gaga was also part of that Karate Kid movie era.",
                System.currentTimeMillis());

        assertTrue(merged.modes.isEmpty());
        assertFalse(merged.summaryCache.silhouette.toLowerCase().contains("lady gaga"));
    }

    @Test
    void apply_formativePromptRepairsEasyTravelMisframeIntoDurableFascination() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "childhood sense that international travel is easy and common", "context",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge made user think international travel was easy and frequent, inspiring travel aspirations",
                "formative_imprint", 0.80, 0.75));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(54L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Carmen Sandiego made me think travel happened all the time, so I knew as a kid I wanted to do that just to see the world.",
                System.currentTimeMillis());

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertTrue(silhouette.contains("world travel fascination since childhood"));
        assertFalse(silhouette.contains("childhood sense"));
        assertFalse(silhouette.contains("easy and common"));
        assertTrue(merged.modes.get(0).evidence.stream()
                .anyMatch(e -> e.value.toLowerCase().contains("sparked a childhood desire to see the world")));
    }

    @Test
    void apply_formativePromptDoesNotConvertAllImprintsToRealWorldCompWhenAnswerMentionsKarateKid() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "world travel fascination since childhood", "context",
                "Carmen Sandiego sparked a childhood desire to see the world through international travel",
                "formative_imprint", 0.76, 0.74));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "secret-agent adventure fantasy", "context",
                "Johnny Quest and Carmen Sandiego gave the travel fantasy a secret-agent adventure flavor",
                "formative_imprint", 0.72, 0.70));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "aesthetic_field",
                "early Asian aesthetic exposure", "context",
                "Okami, Katamari Damacy, DBZ, and Yu Yu Hakusho exposed the user early to Japanese/Asian aesthetics, influencing later tastes",
                "formative_imprint", 0.78, 0.74));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "real_world_comps",
                "Wenwen Han / Meiying", "context",
                "Where in the World Is Carmen Sandiego made travel feel easy, Okami shaped aesthetics, and The Karate Kid (2010)'s female lead shaped attraction patterns",
                "formative_imprint", 0.74, 0.70));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(55L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Carmen Sandiego made me want to see the world. Johnny Quest added secret-agent fantasy. Okami, Katamari, DBZ, and Yu Yu Hakusho shaped Japanese/Asian aesthetic taste. The Karate Kid with Jaden Smith made Meiying feel like a physical type reference.",
                System.currentTimeMillis());

        SilhouetteMode mode = merged.modes.get(0);
        assertTrue(mode.selfExpression.stream()
                .anyMatch(c -> "world travel fascination since childhood".equals(c.label)));
        assertTrue(mode.selfExpression.stream()
                .anyMatch(c -> "secret-agent adventure fantasy".equals(c.label)));
        assertTrue(mode.aestheticField.stream()
                .anyMatch(c -> "early Asian aesthetic exposure".equals(c.label)));
        assertTrue(mode.realWorldComps.stream()
                .anyMatch(c -> "Wenwen Han / Meiying".equals(c.label)));
        SilhouetteConcept comp = mode.realWorldComps.stream()
                .filter(c -> "Wenwen Han / Meiying".equals(c.label))
                .findFirst()
                .orElseThrow();
        List<String> compEvidence = mode.evidence.stream()
                .filter(e -> comp.evidenceIds.contains(e.id))
                .map(e -> e.value)
                .toList();
        assertTrue(compEvidence.stream()
                .anyMatch(value -> value.contains("non-exclusive physical-type reference point")));
        assertFalse(compEvidence.stream()
                .anyMatch(value -> value.toLowerCase().contains("carmen sandiego")));

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertTrue(silhouette.contains("self:world travel fascination since childhood,secret-agent adventure fantasy"));
        assertTrue(silhouette.contains("aesthetic:early asian aesthetic exposure"));
        assertTrue(silhouette.contains("comps:wenwen han / meiying"));
    }

    @Test
    void apply_formativePromptKeepsSpecificAestheticLabelAsConceptNotModeHeading() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_early_asian_aesthetic_exposure", "early Asian aesthetic exposure", "aesthetic_field",
                "early Asian aesthetic exposure", "context",
                "Okami, Katamari Damacy, DBZ, and Yu Yu Hakusho exposed the user early to Japanese/Asian aesthetics, influencing later tastes",
                "formative_imprint", 0.78, 0.74));
        patch.ops.add(conceptOp("mode_early_asian_aesthetic_exposure", "early Asian aesthetic exposure", "self_expression",
                "world travel fascination since childhood", "context",
                "Carmen Sandiego sparked a childhood desire to see the world through international travel",
                "formative_imprint", 0.76, 0.74));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(56L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Okami, Katamari, DBZ, and Yu Yu Hakusho shaped Japanese/Asian aesthetics. Carmen Sandiego made me want to see the world.",
                System.currentTimeMillis());

        String silhouette = merged.summaryCache.silhouette.toLowerCase();
        assertEquals("formative media imprints", merged.modes.get(0).label);
        assertFalse(silhouette.contains("mode: early asian aesthetic exposure"));
        assertTrue(silhouette.contains("aesthetic:early asian aesthetic exposure"));
        assertTrue(silhouette.contains("self:world travel fascination since childhood"));
    }

    @Test
    void apply_formativePromptCollapsesEquivalentTravelAndSecretAgentAliases() {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "childhood world travel fascination", "context",
                "Where in the World Is Carmen Sandiego game made user think international travel was easy and common, sparking desire to see the world",
                "formative_imprint", 0.76, 0.74));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "world travel fascination since childhood", "context",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge and Johnny Quest sparked a childhood desire to see the world through international travel",
                "formative_imprint", 0.76, 0.74));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "secret agent adventure fantasy", "context",
                "Johnny Quest and Carmen Sandiego inspired user's childhood secret agent fantasy",
                "formative_imprint", 0.72, 0.70));
        patch.ops.add(conceptOp("mode_formative_media", "formative media imprints", "self_expression",
                "secret-agent adventure fantasy", "context",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge, Johnny Quest, and Tintin gave the travel fantasy a secret-agent adventure flavor",
                "formative_imprint", 0.72, 0.70));

        SilhouetteState merged = SilhouetteModeMerger.apply(
                SilhouetteState.empty(57L),
                patch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Carmen Sandiego Treasures of Knowledge made me want to see the world. Johnny Quest and Tintin added a secret-agent adventure fantasy.",
                System.currentTimeMillis());

        SilhouetteMode mode = merged.modes.get(0);
        long travelConcepts = mode.selfExpression.stream()
                .filter(c -> "world travel fascination since childhood".equals(c.label))
                .count();
        long secretAgentConcepts = mode.selfExpression.stream()
                .filter(c -> "secret-agent adventure fantasy".equals(c.label))
                .count();
        assertEquals(1, travelConcepts);
        assertEquals(1, secretAgentConcepts);
        assertFalse(mode.selfExpression.stream().anyMatch(c -> "childhood world travel fascination".equals(c.label)));
        assertFalse(mode.selfExpression.stream().anyMatch(c -> "secret agent adventure fantasy".equals(c.label)));
        assertEquals(2, mode.selfExpression.size());
        assertTrue(mode.evidence.stream()
                .anyMatch(e -> e.value.contains("sparked a childhood desire to see the world through international travel")));
        assertTrue(mode.evidence.stream()
                .anyMatch(e -> e.value.contains("Tintin gave the travel fantasy a secret-agent adventure flavor")));
        assertEquals(2, mode.evidence.size());
    }

    @Test
    void apply_dedupesIdenticalEvidenceTextAcrossDifferentEvents() {
        SilhouettePatch firstPatch = patchWith(conceptOp("mode_formative_media", "formative media imprints",
                "self_expression",
                "secret-agent adventure fantasy",
                "context",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge, Johnny Quest, and Tintin gave the travel fantasy a secret-agent adventure flavor",
                "formative_imprint",
                0.72,
                0.70));
        SilhouetteState first = SilhouetteModeMerger.apply(
                SilhouetteState.empty(58L),
                firstPatch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event_a",
                "answer",
                System.currentTimeMillis());

        SilhouettePatch secondPatch = patchWith(conceptOp("mode_formative_media", "formative media imprints",
                "self_expression",
                "secret-agent adventure fantasy",
                "context",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge, Johnny Quest, and Tintin gave the travel fantasy a secret-agent adventure flavor",
                "formative_imprint",
                0.72,
                0.70));
        SilhouetteState merged = SilhouetteModeMerger.apply(
                first,
                secondPatch,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event_b",
                "answer",
                System.currentTimeMillis());

        SilhouetteMode mode = merged.modes.get(0);
        assertEquals(1, mode.selfExpression.size());
        assertEquals(1, mode.evidence.size());
        assertTrue(mode.evidence.get(0).value.contains("secret-agent adventure flavor"));
        assertTrue(mode.evidence.get(0).derivedConceptIds.contains(mode.selfExpression.get(0).id));
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

    private static SilhouettePatch.Op evidenceOp(
            String modeId,
            String modeLabel,
            String target,
            String evidenceValue,
            String evidenceSource,
            double confidence,
            double strength) {
        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = evidenceSource;
        evidence.target = target;
        evidence.value = evidenceValue;
        evidence.strength = strength;
        evidence.confidence = confidence;
        evidence.sourceWeight = SilhouetteEvidence.defaultSourceWeight(evidenceSource);
        evidence.createdAt = System.currentTimeMillis();

        SilhouettePatch.Op op = new SilhouettePatch.Op();
        op.op = "add_evidence";
        op.modeId = modeId;
        op.label = modeLabel;
        op.target = target;
        op.evidence = evidence;
        return op;
    }
}
