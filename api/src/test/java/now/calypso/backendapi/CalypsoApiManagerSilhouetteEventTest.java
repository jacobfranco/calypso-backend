package now.calypso.backendapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.signals.ExtractedSignal;
import now.calypso.backendapi.pojos.GetAccount;
import now.calypso.backendapi.pojos.GetMatch;
import now.calypso.backendapi.silhouette.SilhouetteConcept;
import now.calypso.backendapi.silhouette.SilhouetteEvidence;
import now.calypso.backendapi.silhouette.SilhouetteMode;
import now.calypso.backendapi.silhouette.SilhouettePatch;
import now.calypso.backendapi.silhouette.SilhouetteState;

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

    @Test
    void sanitizeSilhouettePatch_repairBoundaryConceptBecomesAntiPattern() throws Exception {
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(conceptOp(
                "ignoring or pretending conflict didn't happen",
                "dislikes pretending nothing happened as it leads to unresolved tension",
                "self_expression"));

        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "sanitizeSilhouettePatchForResidualSemantics",
                SilhouettePatch.class,
                List.class,
                String.class);
        method.setAccessible(true);
        SilhouettePatch sanitized = (SilhouettePatch) method.invoke(
                null,
                patch,
                List.of(),
                "private.repair.rhythm");

        assertEquals(1, sanitized.ops.size());
        SilhouettePatch.Op op = sanitized.ops.get(0);
        assertEquals("upsert_anti_pattern", op.op);
        assertEquals("anti_patterns", op.target);
        assertNotNull(op.antiPattern);
        assertTrue(op.antiPattern.label.contains("pretending conflict"));
        assertEquals("relational", op.antiPattern.scope);
        assertEquals("medium", op.antiPattern.severity);
        assertNotNull(op.evidence);
        assertEquals("anti_patterns", op.evidence.target);
        assertEquals("boundary_pattern", op.evidence.source);
        assertFalse(sanitized.ops.stream()
                .anyMatch(item -> item != null
                        && "upsert_concept".equals(item.op)
                        && "self_expression".equals(item.target)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void silhouetteReadiness_allowsSingleConceptPrivatePromptSilhouette() throws Exception {
        SilhouetteState state = SilhouetteState.empty(4242L);
        state.maturity = "sparse";
        state.summaryCache.silhouette = "Prefers direct, calm repair with a real return point after tension.";

        SilhouetteMode mode = new SilhouetteMode();
        mode.id = "mode_repair_boundaries";
        mode.label = "repair boundaries";
        mode.status = "emerging";
        mode.weight = 0.72;
        mode.confidence = 0.56;

        SilhouetteConcept concept = new SilhouetteConcept();
        concept.id = "direct_calm_repair";
        concept.label = "direct calm repair";
        concept.role = "need";
        concept.strength = 0.68;
        concept.confidence = 0.58;
        mode.sustainabilityNeeds.add(concept);

        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = "private_prompt";
        evidence.promptId = "private.repair.rhythm";
        evidence.target = "sustainability_needs";
        evidence.value = "wants direct but calm repair with a planned return point";
        evidence.strength = 0.70;
        evidence.confidence = 0.62;
        evidence.sourceWeight = 1.0;
        mode.evidence.add(evidence);

        state.modes.add(mode);

        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "silhouetteRerankAuditDetails",
                String.class,
                Map.class);
        method.setAccessible(true);
        Map<String, Object> details = (Map<String, Object>) method.invoke(null, "candidate", state.toMap());

        assertEquals(Boolean.TRUE, details.get("candidateSilhouetteReady"));
        assertEquals(1, details.get("candidateSilhouetteConceptCount"));
        assertEquals(1, details.get("candidateSilhouetteEvidenceCount"));
        assertEquals(1, details.get("candidatePrivateSilhouetteEvidenceCount"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void silhouetteRerankAuditDetails_marksUnavailableSnapshotAsPendingNotSparse() throws Exception {
        Class<?> snapshotClass = nestedClass("RerankSilhouetteSnapshot");
        Method unavailable = snapshotClass.getDeclaredMethod("unavailable", long.class, String.class);
        unavailable.setAccessible(true);
        Object snapshot = unavailable.invoke(null, 4242L, "silhouette_read_timeout");

        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "silhouetteRerankAuditDetails",
                String.class,
                snapshotClass);
        method.setAccessible(true);
        Map<String, Object> details = (Map<String, Object>) method.invoke(null, "viewer", snapshot);

        assertEquals(Boolean.FALSE, details.get("viewerSilhouetteAvailable"));
        assertEquals("silhouette_read_timeout", details.get("viewerSilhouetteUnavailableReason"));
        assertEquals(Boolean.FALSE, details.get("viewerSilhouetteReady"));
        assertEquals("empty", details.get("viewerSilhouetteMaturity"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void markTier3Pending_preservesFallbackMatchAndAddsRetryDebug() throws Exception {
        GetAccount account = new GetAccount();
        account.id = "0000000000000000424-a";
        account.name = "Candidate";
        GetMatch match = new GetMatch(account, 71.5, 123L, Map.of("finalScore", 71.5));

        Method mark = CalypsoApiManager.class.getDeclaredMethod("markTier3Pending", List.class, String.class);
        mark.setAccessible(true);
        List<GetMatch> pending = (List<GetMatch>) mark.invoke(null, List.of(match), "viewer_silhouette_pending");

        assertEquals(1, pending.size());
        assertEquals(match.account, pending.get(0).account);
        assertEquals(match.score, pending.get(0).score);
        assertEquals(Boolean.TRUE, pending.get(0).scorerDebug.get("tier3Pending"));
        assertEquals("viewer_silhouette_pending", pending.get(0).scorerDebug.get("tier3PendingReason"));

        Method hasPending = CalypsoApiManager.class.getDeclaredMethod("hasTier3Pending", List.class);
        hasPending.setAccessible(true);
        assertEquals(Boolean.TRUE, hasPending.invoke(null, pending));
    }

    @Test
    void facecardRerankStatus_keepsMixedAppliedAndPendingDeckRetryable() throws Exception {
        Method method = CalypsoApiManager.class.getDeclaredMethod(
                "facecardRerankStatus",
                boolean.class,
                boolean.class);
        method.setAccessible(true);

        assertEquals("rerank_pending", method.invoke(null, true, true));
        assertEquals("reranked", method.invoke(null, true, false));
        assertEquals("rerank_attempted", method.invoke(null, false, false));
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

    private static Class<?> nestedClass(String simpleName) {
        for (Class<?> candidate : CalypsoApiManager.class.getDeclaredClasses()) {
            if (candidate.getSimpleName().equals(simpleName)) {
                return candidate;
            }
        }
        throw new AssertionError("Missing nested class " + simpleName);
    }
}
