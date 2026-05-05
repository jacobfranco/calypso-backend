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

import org.junit.jupiter.api.Test;

public class SilhouettePatchTest {
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
}
