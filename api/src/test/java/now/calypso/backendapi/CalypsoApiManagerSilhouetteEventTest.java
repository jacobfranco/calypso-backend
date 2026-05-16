package now.calypso.backendapi;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.signals.ExtractedSignal;
import now.calypso.backendapi.silhouette.SilhouetteConcept;
import now.calypso.backendapi.silhouette.SilhouetteEvidence;
import now.calypso.backendapi.silhouette.SilhouettePatch;

class CalypsoApiManagerSilhouetteEventTest {
    @SuppressWarnings("unchecked")
    @Test
    void buildSilhouetteEvent_keepsLongPrivatePromptAnswerForSilhouetteExtraction() throws Exception {
        String answer = "Okami and Katamari Damacy made me interested in Asian culture and playful surreal aesthetics. "
                + "The old computer games felt vivid and strange in a way that stuck with me. ".repeat(5)
                + "Carmen Sandiego made me want to travel internationally, be like a secret agent, "
                + "and experience the mundane normal life in other countries.";
        assertTrue(answer.indexOf("mundane normal life") > 360);

        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "buildSilhouetteEvent",
                long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                List.class,
                String.class,
                SilhouettePatch.class,
                String.class);
        method.setAccessible(true);
        Map<String, Object> event = (Map<String, Object>) method.invoke(
                null,
                123L,
                "private_prompt",
                "instance",
                "private.formative.imprints",
                "What things from growing up still have a hold on you?",
                answer,
                List.of(),
                answer,
                SilhouettePatch.empty(),
                null);

        String storedAnswer = (String) event.get("answer");
        assertTrue(storedAnswer.contains("mundane normal life in other countries"));
    }

    @Test
    void augmentFormativePatch_addsExactSeedEvidenceWithoutLinkingItToEveryConcept() throws Exception {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp(
                "ordinary-life world travel curiosity",
                "Carmen Sandiego made international travel feel exciting and close to ordinary life",
                "self_expression"));
        patch.ops.add(conceptOp(
                "90s anime and eastern game aesthetic affinity",
                "Yu Yu Hakusho, Okami, and Katamari Damacy shaped later aesthetic preferences",
                "aesthetic_field"));

        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "augmentFormativePatchWithSignalEvidence",
                SilhouettePatch.class,
                List.class,
                String.class,
                String.class);
        method.setAccessible(true);
        SilhouettePatch augmented = (SilhouettePatch) method.invoke(
                null,
                patch,
                List.of(ExtractedSignal.from("okami", SignalIntent.SELF, 0.74)),
                "private.formative.imprints",
                "Okami and Carmen Sandiego are formative references.");

        SilhouetteEvidence exactSeed = augmented.ops.stream()
                .filter(op -> op != null && "add_evidence".equals(op.op))
                .filter(op -> op.evidence != null && "okami".equals(op.evidence.value))
                .map(op -> op.evidence)
                .findFirst()
                .orElse(null);

        assertNotNull(exactSeed);
        assertTrue(exactSeed.derivedConceptIds == null || exactSeed.derivedConceptIds.isEmpty(),
                "Exact seed evidence should stay unassigned instead of appearing under every concept.");
    }

    private static SilhouettePatch.Op conceptOp(String label, String evidenceValue, String target) {
        SilhouetteConcept concept = new SilhouetteConcept();
        concept.label = label;
        concept.id = label.replaceAll("[^a-zA-Z0-9]+", "_").toLowerCase();
        concept.role = "context";
        concept.confidence = 0.80;
        concept.strength = 0.76;

        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = "formative_imprint";
        evidence.target = target;
        evidence.value = evidenceValue;
        evidence.confidence = 0.80;
        evidence.strength = 0.76;

        return SilhouettePatch.Op.upsertConcept(
                "mode_formative_media_imprints",
                "formative media imprints",
                target,
                concept,
                evidence);
    }
}
