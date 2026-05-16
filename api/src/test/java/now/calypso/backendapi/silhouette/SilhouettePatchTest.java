package now.calypso.backendapi.silhouette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import now.calypso.backendapi.llm.OpenAIJson;

public class SilhouettePatchTest {
    @AfterEach
    void clearOverride() {
        OpenAIJson.clearTestOverride();
    }

    @Test
    void toMap_emitsMutableCollectionsForRamaSerialization() {
        SilhouetteConcept concept = new SilhouetteConcept();
        concept.id = "grounded_observer";
        concept.label = "grounded observer";
        concept.role = "core";
        concept.confidence = 0.78;
        concept.strength = 0.72;

        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = "fictional_comp";
        evidence.target = "self_expression";
        evidence.value = "Frieren";
        evidence.strength = 0.80;
        evidence.confidence = 0.74;

        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(SilhouettePatch.Op.upsertConcept(
                "mode_grounded_whimsy",
                "grounded whimsy",
                "self_expression",
                concept,
                evidence));

        Map<String, Object> serialized = patch.toMap();
        assertNotNull(serialized);
        assertInstanceOf(HashMap.class, serialized);

        Object opsRaw = serialized.get("ops");
        assertNotNull(opsRaw);
        assertInstanceOf(ArrayList.class, opsRaw);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) opsRaw;
        assertEquals(1, ops.size());
        assertInstanceOf(HashMap.class, ops.get(0));

        Object evidenceRaw = ops.get(0).get("evidence");
        assertNotNull(evidenceRaw);
        assertInstanceOf(Map.class, evidenceRaw);

        String mapType = serialized.getClass().getName();
        assertFalse(mapType.contains("ImmutableCollections"), mapType);
    }

    @Test
    void editor_addsCleanFictionalComparisonEvidenceWithoutCharacterConcepts() {
        Map<String, Object> event = new HashMap<>();
        event.put("source", "private_prompt");
        event.put("promptId", "private.drawn.to");
        event.put("question", "Describe the kind of person you tend to be drawn to.");
        event.put("answer",
                "I'd say someone like Mustang from Red Rising where they are intelligent and reliable. Also someone like Conduit from Apex Legends, where they are funny and energetic.");

        SilhouettePatch patch = SilhouetteEditor.buildPatch(null, SilhouetteState.empty(99L), event);

        List<String> evidenceValues = patch.ops.stream()
                .filter(op -> op != null && "add_evidence".equals(op.op))
                .filter(op -> op.evidence != null)
                .map(op -> op.evidence.value)
                .toList();
        assertTrue(evidenceValues.contains("Mustang (Red Rising)"));
        assertTrue(evidenceValues.contains("Conduit (Apex Legends)"));
        assertTrue(evidenceValues.stream().noneMatch(value -> value.toLowerCase().contains("d say")));
        assertTrue(patch.ops.stream()
                .filter(op -> op != null && "add_evidence".equals(op.op))
                .allMatch(op -> op.concept == null));
    }

    @Test
    void editor_sendsLongFormativeAnswerToLlmWithoutClippingLateImprint() {
        AtomicReference<String> userPrompt = new AtomicReference<>();
        OpenAIJson.setTestOverride((system, user) -> {
            userPrompt.set(user);
            return """
                    {"ops":[{"op":"upsert_concept","modeId":"mode_playful_worldly_formative_affinity","label":"playful worldly formative affinity","target":"self_expression","concept":{"id":"ordinary_life_world_travel_curiosity","label":"ordinary-life world travel curiosity","role":"context","confidence":0.74,"strength":0.72},"evidence":{"source":"formative_imprint","target":"self_expression","value":"Carmen Sandiego made international travel feel exciting, ordinary, and livable","strength":0.74,"confidence":0.72}}]}
                    """;
        });
        String answer = "Okami and Katamari Damacy made me interested in Asian culture and playful surreal aesthetics. "
                + "The early games were vivid and strange in a way that stuck with me. ".repeat(5)
                + "Carmen Sandiego made me want to travel internationally, be like a secret agent, "
                + "and experience the mundane normal life in other countries.";
        assertTrue(answer.indexOf("mundane normal life") > 360);

        Map<String, Object> event = new HashMap<>();
        event.put("source", "private_prompt");
        event.put("promptId", "private.formative.imprints");
        event.put("question", "What things from growing up still have a hold on you?");
        event.put("answer", answer);

        SilhouettePatch patch = SilhouetteEditor.buildPatch(null, SilhouetteState.empty(101L), event);

        assertFalse(patch.isEmpty());
        assertNotNull(userPrompt.get());
        assertTrue(userPrompt.get().contains("mundane normal life in other countries"));
    }

    @Test
    void editor_augmentsCollapsedFormativePatchWithDistinctImprintConcepts() {
        OpenAIJson.setTestOverride((system, user) -> """
                {"ops":[{"op":"upsert_concept","modeId":"mode_formative_media","label":"formative media and aesthetic imprint","target":"self_expression","concept":{"id":"nostalgic_formative_media_worldview_shaping","label":"nostalgic formative media and worldview shaping","role":"context","confidence":0.54,"strength":0.56},"evidence":{"source":"formative_imprint","target":"self_expression","value":"Okami, Katamari Damacy, Carmen Sandiego, Bugdom, and Nanosaur","strength":0.58,"confidence":0.54}}]}
                """);
        Map<String, Object> event = new HashMap<>();
        event.put("source", "private_prompt");
        event.put("promptId", "private.formative.imprints");
        event.put("question", "What things from growing up still have a hold on you?");
        event.put("answer",
                "Okami and Katamari Damacy made me interested in Asian culture and playful surreal aesthetics. "
                        + "Carmen Sandiego made me want to travel internationally, be like a secret agent, "
                        + "and experience the mundane normal life in other countries. Bugdom and Nanosaur had this old early-2000s game texture.");

        SilhouettePatch patch = SilhouetteEditor.buildPatch(null, SilhouetteState.empty(102L), event);

        List<String> labels = patch.ops.stream()
                .filter(op -> op != null && op.concept != null)
                .map(op -> op.concept.label)
                .toList();
        assertTrue(labels.contains("ordinary-life world travel curiosity"));
        assertTrue(labels.contains("secret-agent adventure fantasy"));
        assertTrue(labels.contains("playful surreal eastern aesthetic affinity"));
        assertTrue(labels.contains("early 2000s game-world texture"));
    }

    @Test
    void editor_keepsTravelAdventureAndAestheticImprintsAsSeparateConcepts() {
        OpenAIJson.setTestOverride((system, user) -> """
                {"ops":[]}
                """);
        Map<String, Object> event = new HashMap<>();
        event.put("source", "private_prompt");
        event.put("promptId", "private.formative.imprints");
        event.put("question", "What things from growing up still have a hold on you?");
        event.put("answer",
                "Where in the World Is Carmen Sandiego? Treasures of Knowledge and Johnny Quest made me want to travel internationally, be like a secret agent, "
                        + "and experience the mundane normal life in other countries. "
                        + "Yuyu Hakusho, Okami, and Katamari Damacy shaped my later Asian and Japanese aesthetic preferences. "
                        + "The Karate Kid with Jaden Smith made me realize as an adult that Meiying is physically my type.");

        SilhouettePatch patch = SilhouetteEditor.buildPatch(null, SilhouetteState.empty(103L), event);

        List<String> labels = patch.ops.stream()
                .filter(op -> op != null && op.concept != null)
                .map(op -> op.concept.label)
                .toList();
        assertTrue(labels.contains("ordinary-life world travel curiosity"));
        assertTrue(labels.contains("secret-agent adventure fantasy"));
        assertTrue(labels.contains("90s anime and eastern game aesthetic affinity"));
        assertTrue(labels.contains("Wenwen Han / Meiying"));

        assertTrue(patch.ops.stream()
                .filter(op -> op != null && op.concept != null)
                .anyMatch(op -> "real_world_comps".equals(op.target)
                        && "Wenwen Han / Meiying".equals(op.concept.label)));

        List<String> evidenceValues = patch.ops.stream()
                .filter(op -> op != null && op.evidence != null)
                .map(op -> op.evidence.value)
                .toList();
        assertTrue(evidenceValues.stream().anyMatch(value -> value.contains("Where in the World Is Carmen Sandiego? Treasures of Knowledge and Johnny Quest")));
        assertTrue(evidenceValues.stream().anyMatch(value -> value.contains("Yu Yu Hakusho, Okami, and Katamari Damacy")));
        assertTrue(evidenceValues.stream().anyMatch(value -> value.contains("Meiying / Wenwen Han")));
    }

    @Test
    void editorIncludesLinkedEvidenceInCurrentSilhouetteContext() {
        SilhouettePatch seed = new SilhouettePatch();
        SilhouetteConcept concept = new SilhouetteConcept();
        concept.id = "ordinary_life_world_travel_curiosity";
        concept.label = "ordinary-life world travel curiosity";
        concept.role = "context";
        concept.confidence = 0.80;
        concept.strength = 0.76;
        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.id = "evidence_carmen";
        evidence.source = "formative_imprint";
        evidence.target = "self_expression";
        evidence.value = "Where in the World Is Carmen Sandiego? Treasures of Knowledge made mundane international travel feel desirable";
        evidence.confidence = 0.80;
        evidence.strength = 0.76;
        seed.ops.add(SilhouettePatch.Op.upsertConcept(
                "mode_formative_media",
                "formative media imprints",
                "self_expression",
                concept,
                evidence));
        SilhouetteState current = SilhouetteModeMerger.apply(
                SilhouetteState.empty(104L),
                seed,
                1.0,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "event",
                "Carmen Sandiego made mundane international travel feel desirable",
                System.currentTimeMillis());

        AtomicReference<String> userPrompt = new AtomicReference<>();
        OpenAIJson.setTestOverride((system, user) -> {
            userPrompt.set(user);
            return """
                    {"ops":[]}
                    """;
        });
        Map<String, Object> event = new HashMap<>();
        event.put("source", "private_prompt");
        event.put("promptId", "private.formative.imprints");
        event.put("question", "What things from growing up still have a hold on you?");
        event.put("answer", "Johnny Quest added a secret-agent adventure layer.");

        SilhouetteEditor.buildPatch(null, current, event);

        assertNotNull(userPrompt.get());
        assertTrue(userPrompt.get().contains("- self: ordinary-life world travel curiosity"));
        assertTrue(userPrompt.get().contains("evidence: Where in the World Is Carmen Sandiego? Treasures of Knowledge made mundane international travel feel desirable"));
    }
}
