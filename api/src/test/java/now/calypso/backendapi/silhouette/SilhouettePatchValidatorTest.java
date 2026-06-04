package now.calypso.backendapi.silhouette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SilhouettePatchValidatorTest {
    @Test
    void validateDropsGenericConceptWithoutEvidence() {
        SilhouettePatch patch = new SilhouettePatch();
        SilhouettePatch.Op op = new SilhouettePatch.Op();
        op.op = "upsert_concept";
        op.label = "mode";
        op.target = "self_expression";
        op.concept = new SilhouetteConcept();
        op.concept.label = "hobbies";
        patch.ops.add(op);

        SilhouettePatchValidator.ValidationResult result = SilhouettePatchValidator.validate(
                "private.hobbies",
                patch,
                "");

        assertEquals(1, result.inputOps);
        assertEquals(0, result.outputOps);
        assertTrue(result.droppedByReason.containsKey("generic_label_without_evidence"));
    }

    @Test
    void validateKeepsSpecificConceptWithEvidence() {
        SilhouettePatch patch = new SilhouettePatch();
        SilhouettePatch.Op op = new SilhouettePatch.Op();
        op.op = "upsert_concept";
        op.label = "mode";
        op.target = "self_expression";
        op.concept = new SilhouetteConcept();
        op.concept.label = "long quiet hikes";
        op.evidence = new SilhouetteEvidence();
        op.evidence.value = "The user said long quiet hikes help them reset after crowded weeks.";
        patch.ops.add(op);

        SilhouettePatchValidator.ValidationResult result = SilhouettePatchValidator.validate(
                "private.hobbies",
                patch,
                "long quiet hikes help them reset");

        assertEquals(1, result.outputOps);
        assertFalse(result.patch.isEmpty());
    }
}
