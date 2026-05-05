package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SilhouetteModeMerger {
    public static final int MAX_MODES = 5;
    public static final int MAX_CONCEPTS_PER_SECTION = 8;
    public static final int MAX_ANTI_PATTERNS_PER_MODE = 6;
    public static final int MAX_TENSIONS_PER_MODE = 4;
    public static final int MAX_EVIDENCE_PER_MODE = 12;
    public static final int MAX_OPEN_QUESTIONS_PER_MODE = 5;

    private static final int RERANKER_SUMMARY_MAX_CHARS = 1600;
    private static final int ADMIN_SUMMARY_MAX_CHARS = 2400;

    private SilhouetteModeMerger() {
    }

    public static SilhouetteState apply(
            SilhouetteState base,
            SilhouettePatch patch,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        long ts = now > 0L ? now : System.currentTimeMillis();
        SilhouetteState out = base == null ? new SilhouetteState() : new SilhouetteState(base);
        out.updatedAt = ts;
        out.version = Math.max(1, out.version + 1);
        if (out.modes == null) {
            out.modes = new ArrayList<>();
        }

        if (patch != null && patch.ops != null) {
            for (SilhouettePatch.Op op : patch.ops) {
                applyOp(out, op, sourceWeight, source, sourceId, promptId, eventId, evidenceExcerpt, ts);
            }
        }

        normalizeAndCap(out, ts);
        recomputeModeScores(out, ts);
        out.maturity = computeMaturity(out);
        refreshSummaryCache(out);
        return out;
    }

    public static String computeMaturity(SilhouetteState state) {
        if (state == null || state.modes == null || activeModes(state).isEmpty()) {
            return "empty";
        }
        List<SilhouetteMode> active = activeModes(state);
        int evidenceCount = 0;
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        boolean hasSelf = false;
        boolean hasSeeking = false;
        boolean hasSpark = false;
        boolean hasSustainability = false;
        boolean hasAntiPattern = false;
        int developedModeCount = 0;
        boolean highlyDevelopedMode = false;

        for (SilhouetteMode mode : active) {
            int modeEvidence = mode.evidence == null ? 0 : mode.evidence.size();
            evidenceCount += modeEvidence;
            confidenceSum += SilhouetteModelUtils.clamp01(mode.confidence);
            confidenceCount += 1;
            boolean modeHasSelf = hasConcepts(mode.selfExpression);
            boolean modeHasSeeking = hasConcepts(mode.seekingExpression);
            boolean modeHasSpark = hasConcepts(mode.sparkTriggers);
            boolean modeHasSustainability = hasConcepts(mode.sustainabilityNeeds);
            boolean modeHasAnti = mode.antiPatterns != null && !mode.antiPatterns.isEmpty();
            hasSelf |= modeHasSelf;
            hasSeeking |= modeHasSeeking;
            hasSpark |= modeHasSpark;
            hasSustainability |= modeHasSustainability;
            hasAntiPattern |= modeHasAnti;
            int coverage = coverageCount(mode);
            if (("active".equals(mode.status) || "mature".equals(mode.status) || "emerging".equals(mode.status))
                    && coverage >= 3 && modeEvidence >= 3) {
                developedModeCount += 1;
            }
            if (coverage >= 5 && modeEvidence >= 10 && mode.confidence >= 0.62) {
                highlyDevelopedMode = true;
            }
        }

        double avgConfidence = confidenceCount == 0 ? 0.0 : confidenceSum / confidenceCount;
        if (evidenceCount >= 12
                && (developedModeCount >= 2 || highlyDevelopedMode)
                && hasSelf && hasSeeking && hasSpark && hasSustainability && hasAntiPattern
                && avgConfidence >= 0.60) {
            return "mature";
        }
        if (evidenceCount >= 5 && hasSelf && hasSeeking && avgConfidence >= 0.45) {
            return "emerging";
        }
        return "sparse";
    }

    static void refreshSummaryCache(SilhouetteState out) {
        if (out == null) {
            return;
        }
        SilhouetteSummaryCache cache = out.summaryCache == null
                ? new SilhouetteSummaryCache()
                : new SilhouetteSummaryCache(out.summaryCache);
        cache.rerankerShort = buildRerankerSummary(out);
        cache.adminLong = buildAdminSummary(out);
        cache.generatedFromVersion = Math.max(1, out.version);
        cache.updatedAt = out.updatedAt > 0L ? out.updatedAt : System.currentTimeMillis();
        out.summaryCache = cache;
    }

    private static void applyOp(
            SilhouetteState out,
            SilhouettePatch.Op op,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        if (out == null || op == null || op.op == null || op.op.isBlank()) {
            return;
        }
        switch (op.op) {
            case "upsert_mode" -> upsertMode(out, op, now);
            case "reinforce_mode" -> reinforceMode(out, op, now);
            case "deprecate_mode" -> deprecateMode(out, op, now);
            case "upsert_concept", "reinforce_concept" -> upsertConcept(out, op, sourceWeight, source, sourceId,
                    promptId, eventId, evidenceExcerpt, now);
            case "retract_concept" -> retractConcept(out, op, now);
            case "upsert_anti_pattern" -> upsertAntiPattern(out, op, sourceWeight, source, sourceId, promptId, eventId,
                    evidenceExcerpt, now);
            case "upsert_tension" -> upsertTension(out, op, sourceWeight, source, sourceId, promptId, eventId,
                    evidenceExcerpt, now);
            case "add_evidence" -> addEvidenceOnly(out, op, sourceWeight, source, sourceId, promptId, eventId,
                    evidenceExcerpt, now);
            case "add_open_question" -> addOpenQuestion(out, op, now);
            default -> {
            }
        }
    }

    private static void upsertMode(SilhouetteState out, SilhouettePatch.Op op, long now) {
        SilhouetteMode incoming = op.mode == null ? new SilhouetteMode() : new SilhouetteMode(op.mode);
        if (incoming.label == null || incoming.label.isBlank()) {
            incoming.label = op.label == null || op.label.isBlank() ? "emerging mode" : op.label;
        }
        if (incoming.id == null || incoming.id.isBlank()) {
            incoming.id = preferredModeId(op, incoming.label);
        }
        incoming.status = op.status == null || op.status.isBlank() ? SilhouetteMode.normalizeStatus(incoming.status)
                : SilhouetteMode.normalizeStatus(op.status);
        incoming.updatedAt = now;
        incoming.createdAt = incoming.createdAt > 0L ? incoming.createdAt : now;
        incoming.lastReinforcedAt = now;

        SilhouetteMode existing = findModeById(out, incoming.id);
        if (existing == null) {
            existing = findCompatibleMode(out, incoming.id, incoming.label, null, null, true);
        }
        if (existing == null && out.modes.size() < MAX_MODES) {
            out.modes.add(incoming);
            return;
        }
        if (existing == null) {
            existing = weakestNonDeprecatedMode(out);
        }
        if (existing == null) {
            return;
        }
        mergeModeShell(existing, incoming, now);
    }

    private static void reinforceMode(SilhouetteState out, SilhouettePatch.Op op, long now) {
        SilhouetteMode mode = modeFor(out, op, null, null, true, now);
        if (mode == null) {
            return;
        }
        mode.lastReinforcedAt = now;
        mode.updatedAt = now;
        if (op.confidence != null) {
            mode.confidence = reinforceScore(mode.confidence, op.confidence.doubleValue(), 0.20);
        }
        if (op.weight != null) {
            mode.weight = reinforceScore(mode.weight, op.weight.doubleValue(), 0.15);
        }
    }

    private static void deprecateMode(SilhouetteState out, SilhouettePatch.Op op, long now) {
        SilhouetteMode mode = modeFor(out, op, null, null, false, now);
        if (mode == null) {
            return;
        }
        mode.status = "deprecated";
        mode.updatedAt = now;
    }

    private static void upsertConcept(
            SilhouetteState out,
            SilhouettePatch.Op op,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        SilhouetteConcept concept = op.concept == null ? null : new SilhouetteConcept(op.concept);
        if (concept == null || concept.label == null || concept.label.isBlank()) {
            return;
        }
        String target = SilhouetteEvidence.normalizeTarget(op.target);
        if ("anti_patterns".equals(target) || "tensions".equals(target)) {
            target = "self_expression";
        }
        SilhouetteMode mode = modeFor(out, op, target, concept, true, now);
        if (mode == null) {
            return;
        }
        SilhouetteEvidence evidence = normalizedEvidence(op.evidence, sourceWeight, source, sourceId, promptId, eventId,
                evidenceExcerpt, target, concept.label, now);
        if (evidence != null) {
            addEvidence(mode, evidence);
            if (!concept.evidenceIds.contains(evidence.id)) {
                concept.evidenceIds.add(evidence.id);
            }
            if (!evidence.derivedConceptIds.contains(concept.id)) {
                evidence.derivedConceptIds.add(concept.id);
            }
        }
        upsertConceptInto(mode.conceptsForTarget(target), concept, "reinforce_concept".equals(op.op));
        mode.lastReinforcedAt = now;
        mode.updatedAt = now;
    }

    private static void retractConcept(SilhouetteState out, SilhouettePatch.Op op, long now) {
        SilhouetteMode mode = modeFor(out, op, null, op.concept, false, now);
        if (mode == null || op.concept == null) {
            return;
        }
        String conceptId = SilhouetteModelUtils.normalizeId(op.concept.id, "concept", op.concept.label);
        removeConcept(mode.selfExpression, conceptId);
        removeConcept(mode.seekingExpression, conceptId);
        removeConcept(mode.sparkTriggers, conceptId);
        removeConcept(mode.sustainabilityNeeds, conceptId);
        removeConcept(mode.aestheticField, conceptId);
        mode.updatedAt = now;
    }

    private static void upsertAntiPattern(
            SilhouetteState out,
            SilhouettePatch.Op op,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        SilhouetteAntiPattern anti = op.antiPattern == null ? null : new SilhouetteAntiPattern(op.antiPattern);
        if (anti == null || anti.label == null || anti.label.isBlank()) {
            return;
        }
        SilhouetteMode mode = modeFor(out, op, "anti_patterns", null, true, now);
        if (mode == null) {
            return;
        }
        SilhouetteEvidence evidence = normalizedEvidence(op.evidence, sourceWeight, source, sourceId, promptId, eventId,
                evidenceExcerpt, "anti_patterns", anti.label, now);
        if (evidence != null) {
            addEvidence(mode, evidence);
            if (!anti.evidenceIds.contains(evidence.id)) {
                anti.evidenceIds.add(evidence.id);
            }
        }
        upsertAntiPatternInto(mode.antiPatterns, anti);
        mode.lastReinforcedAt = now;
        mode.updatedAt = now;
    }

    private static void upsertTension(
            SilhouetteState out,
            SilhouettePatch.Op op,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        SilhouetteTension tension = op.tension == null ? null : new SilhouetteTension(op.tension);
        if (tension == null || tension.a == null || tension.a.isBlank() || tension.b == null || tension.b.isBlank()) {
            return;
        }
        SilhouetteMode mode = modeFor(out, op, "tensions", null, true, now);
        if (mode == null) {
            return;
        }
        SilhouetteEvidence evidence = normalizedEvidence(op.evidence, sourceWeight, source, sourceId, promptId, eventId,
                evidenceExcerpt, "tensions", tension.a + " / " + tension.b, now);
        if (evidence != null) {
            addEvidence(mode, evidence);
            if (!tension.evidenceIds.contains(evidence.id)) {
                tension.evidenceIds.add(evidence.id);
            }
        }
        upsertTensionInto(mode.tensions, tension);
        mode.lastReinforcedAt = now;
        mode.updatedAt = now;
    }

    private static void addEvidenceOnly(
            SilhouetteState out,
            SilhouettePatch.Op op,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            long now) {
        SilhouetteEvidence evidence = normalizedEvidence(op.evidence, sourceWeight, source, sourceId, promptId, eventId,
                evidenceExcerpt, op.target, null, now);
        if (evidence == null) {
            return;
        }
        SilhouetteMode mode = modeFor(out, op, evidence.target, null, true, now);
        if (mode == null) {
            return;
        }
        addEvidence(mode, evidence);
        mode.lastReinforcedAt = now;
        mode.updatedAt = now;
    }

    private static void addOpenQuestion(SilhouetteState out, SilhouettePatch.Op op, long now) {
        String question = SilhouetteModelUtils.text(op.openQuestion, 180);
        if (question.isBlank()) {
            return;
        }
        SilhouetteMode mode = modeFor(out, op, null, null, true, now);
        if (mode == null) {
            return;
        }
        if (mode.openQuestions == null) {
            mode.openQuestions = new ArrayList<>();
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        for (String existing : mode.openQuestions) {
            if (existing != null && existing.trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return;
            }
        }
        mode.openQuestions.add(question);
        mode.updatedAt = now;
    }

    private static SilhouetteMode modeFor(
            SilhouetteState out,
            SilhouettePatch.Op op,
            String target,
            SilhouetteConcept concept,
            boolean create,
            long now) {
        String modeId = op == null ? null : op.modeId;
        String label = op == null ? null : op.label;
        if ((label == null || label.isBlank()) && op != null && op.mode != null) {
            label = op.mode.label;
        }
        SilhouetteMode existing = modeId == null ? null : findModeById(out, modeId);
        if (existing != null) {
            return existing;
        }
        existing = findCompatibleMode(out, modeId, label, target, concept, modeId == null);
        if (existing != null) {
            return existing;
        }
        if (!create) {
            return null;
        }
        if (out.modes.size() >= MAX_MODES) {
            return weakestNonDeprecatedMode(out);
        }
        SilhouetteMode created = op != null && op.mode != null ? new SilhouetteMode(op.mode) : new SilhouetteMode();
        created.id = modeId == null || modeId.isBlank() ? preferredModeId(op, label) : modeId;
        created.label = label == null || label.isBlank() ? labelForTarget(target, concept) : label;
        created.status = "emerging";
        created.createdAt = now;
        created.updatedAt = now;
        created.lastReinforcedAt = now;
        out.modes.add(created);
        return created;
    }

    private static SilhouetteMode findCompatibleMode(
            SilhouetteState out,
            String modeId,
            String label,
            String target,
            SilhouetteConcept concept,
            boolean loose) {
        if (out == null || out.modes == null || out.modes.isEmpty()) {
            return null;
        }
        SilhouetteMode best = null;
        double bestScore = 0.0;
        String normalizedLabel = SilhouetteModelUtils.normalizeKey(label);
        String conceptId = concept == null ? null : SilhouetteModelUtils.normalizeId(concept.id, "concept", concept.label);
        for (SilhouetteMode mode : out.modes) {
            if (mode == null || "deprecated".equals(mode.status)) {
                continue;
            }
            double score = 0.0;
            if (modeId != null && mode.id != null && mode.id.equals(modeId)) {
                score += 4.0;
            }
            String modeLabel = SilhouetteModelUtils.normalizeKey(mode.label);
            if (normalizedLabel != null && normalizedLabel.equals(modeLabel)) {
                score += 2.0;
            }
            if (conceptId != null && containsConcept(mode, conceptId)) {
                score += 3.0;
            }
            if (target != null && concept != null && nearbyTexture(mode, target, concept)) {
                score += 0.8;
            }
            if (loose && score <= 0.0 && out.modes.size() == 1) {
                score = 0.8;
            }
            if (score > bestScore) {
                bestScore = score;
                best = mode;
            }
        }
        return bestScore >= 0.75 ? best : null;
    }

    private static boolean nearbyTexture(SilhouetteMode mode, String target, SilhouetteConcept concept) {
        List<SilhouetteConcept> section = mode.conceptsForTarget(target);
        if (section == null || section.isEmpty() || concept == null || concept.label == null) {
            return false;
        }
        LinkedHashSet<String> incomingTerms = terms(concept.label);
        if (incomingTerms.isEmpty()) {
            return false;
        }
        for (SilhouetteConcept existing : section) {
            if (existing == null || existing.label == null) {
                continue;
            }
            LinkedHashSet<String> existingTerms = terms(existing.label);
            for (String term : incomingTerms) {
                if (existingTerms.contains(term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static LinkedHashSet<String> terms(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (part.length() >= 4 && !isGenericTextureTerm(part)) {
                out.add(part);
            }
        }
        return out;
    }

    private static boolean isGenericTextureTerm(String term) {
        return "concept".equals(term)
                || "pattern".equals(term)
                || "mode".equals(term)
                || "evidence".equals(term)
                || "signal".equals(term)
                || "presence".equals(term);
    }

    private static SilhouetteEvidence normalizedEvidence(
            SilhouetteEvidence raw,
            double sourceWeight,
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String evidenceExcerpt,
            String target,
            String fallbackValue,
            long now) {
        SilhouetteEvidence evidence = raw == null ? new SilhouetteEvidence() : new SilhouetteEvidence(raw);
        evidence.source = normalizeEvidenceSource(evidence.source, source, promptId);
        evidence.target = SilhouetteEvidence.normalizeTarget(
                target == null || target.isBlank() ? evidence.target : target);
        if (evidence.value == null || evidence.value.isBlank()) {
            evidence.value = SilhouetteModelUtils.text(fallbackValue, 180);
        }
        if (evidence.value == null || evidence.value.isBlank()) {
            evidence.value = SilhouetteModelUtils.text(evidenceExcerpt, 180);
        }
        if (evidence.value == null || evidence.value.isBlank()) {
            return null;
        }
        evidence.strength = SilhouetteModelUtils.clamp01(evidence.strength <= 0.0 ? 0.50 : evidence.strength);
        evidence.confidence = SilhouetteModelUtils.clamp01(evidence.confidence <= 0.0 ? 0.50 : evidence.confidence);
        double effectiveSourceWeight = sourceWeight > 0.0 ? sourceWeight : SilhouetteEvidence.defaultSourceWeight(evidence.source);
        if (raw != null && raw.sourceWeight > 0.0) {
            effectiveSourceWeight = raw.sourceWeight;
        }
        evidence.sourceWeight = Math.max(0.0, Math.min(1.25, effectiveSourceWeight));
        if (evidence.sourceId == null || evidence.sourceId.isBlank()) {
            evidence.sourceId = sourceId == null ? "" : sourceId;
        }
        if (evidence.promptId == null || evidence.promptId.isBlank()) {
            evidence.promptId = promptId == null ? "" : promptId;
        }
        evidence.createdAt = evidence.createdAt > 0L ? evidence.createdAt : now;
        if (evidence.id == null || evidence.id.isBlank()) {
            String basis = evidence.source + "_" + evidence.target + "_" + evidence.value + "_"
                    + (eventId == null ? evidence.createdAt : eventId);
            evidence.id = SilhouetteModelUtils.normalizeId(null, "ev", basis);
        }
        return evidence;
    }

    public static String normalizeEvidenceSource(String rawEvidenceSource, String eventSource, String promptId) {
        String existing = SilhouetteEvidence.normalizeSource(rawEvidenceSource);
        if (rawEvidenceSource != null && !rawEvidenceSource.isBlank() && !"fallback".equals(existing)) {
            return existing;
        }
        String prompt = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        if ("private.fictional.characters".equals(prompt)) {
            return "fictional_comp";
        }
        if ("private.visual.aesthetic".equals(prompt) || "private.color.presence".equals(prompt)) {
            return "visual_aesthetic";
        }
        if ("private.music.feels.like".equals(prompt)) {
            return "music";
        }
        String source = eventSource == null ? "" : eventSource.trim().toLowerCase(Locale.ROOT);
        if (source.contains("facecard") || source.contains("behavior")) {
            return "behavior";
        }
        if (source.contains("matchmaking_followup")) {
            return "matchmaking_followup";
        }
        if (source.contains("public_prompt_reaction")) {
            return "prompt_reaction";
        }
        if (source.contains("public_prompt")) {
            return "public_prompt";
        }
        if (source.contains("private_prompt")) {
            return "private_prompt";
        }
        return "fallback";
    }

    private static void addEvidence(SilhouetteMode mode, SilhouetteEvidence evidence) {
        if (mode == null || evidence == null) {
            return;
        }
        if (mode.evidence == null) {
            mode.evidence = new ArrayList<>();
        }
        String id = SilhouetteModelUtils.normalizeId(evidence.id, "ev", evidence.value);
        for (SilhouetteEvidence existing : mode.evidence) {
            if (existing == null) {
                continue;
            }
            if (id.equals(SilhouetteModelUtils.normalizeId(existing.id, "ev", existing.value))) {
                existing.strength = reinforceScore(existing.strength, evidence.strength, 0.25);
                existing.confidence = reinforceScore(existing.confidence, evidence.confidence, 0.20);
                existing.sourceWeight = Math.max(existing.sourceWeight, evidence.sourceWeight);
                existing.createdAt = Math.max(existing.createdAt, evidence.createdAt);
                return;
            }
        }
        mode.evidence.add(evidence);
    }

    private static void upsertConceptInto(List<SilhouetteConcept> section, SilhouetteConcept incoming, boolean reinforce) {
        if (section == null || incoming == null) {
            return;
        }
        String id = SilhouetteModelUtils.normalizeId(incoming.id, "concept", incoming.label);
        for (SilhouetteConcept existing : section) {
            if (existing == null) {
                continue;
            }
            String existingId = SilhouetteModelUtils.normalizeId(existing.id, "concept", existing.label);
            if (!id.equals(existingId)) {
                continue;
            }
            existing.label = incoming.label == null || incoming.label.isBlank() ? existing.label : incoming.label;
            existing.role = higherRole(existing.role, incoming.role);
            existing.confidence = reinforce
                    ? reinforceScore(existing.confidence, incoming.confidence, 0.25)
                    : Math.max(existing.confidence, incoming.confidence);
            existing.strength = reinforce
                    ? reinforceScore(existing.strength, incoming.strength, 0.25)
                    : Math.max(existing.strength, incoming.strength);
            LinkedHashSet<String> evidenceIds = new LinkedHashSet<>(existing.evidenceIds);
            if (incoming.evidenceIds != null) {
                evidenceIds.addAll(incoming.evidenceIds);
            }
            existing.evidenceIds = new ArrayList<>(evidenceIds);
            return;
        }
        incoming.id = id;
        section.add(incoming);
    }

    private static void upsertAntiPatternInto(List<SilhouetteAntiPattern> section, SilhouetteAntiPattern incoming) {
        if (section == null || incoming == null) {
            return;
        }
        String id = SilhouetteModelUtils.normalizeId(incoming.id, "anti", incoming.label);
        for (SilhouetteAntiPattern existing : section) {
            if (existing == null) {
                continue;
            }
            if (!id.equals(SilhouetteModelUtils.normalizeId(existing.id, "anti", existing.label))) {
                continue;
            }
            existing.label = incoming.label == null || incoming.label.isBlank() ? existing.label : incoming.label;
            existing.scope = incoming.scope;
            existing.severity = higherSeverity(existing.severity, incoming.severity);
            existing.confidence = reinforceScore(existing.confidence, incoming.confidence, 0.20);
            LinkedHashSet<String> evidenceIds = new LinkedHashSet<>(existing.evidenceIds);
            if (incoming.evidenceIds != null) {
                evidenceIds.addAll(incoming.evidenceIds);
            }
            existing.evidenceIds = new ArrayList<>(evidenceIds);
            return;
        }
        incoming.id = id;
        section.add(incoming);
    }

    private static void upsertTensionInto(List<SilhouetteTension> section, SilhouetteTension incoming) {
        if (section == null || incoming == null) {
            return;
        }
        String id = SilhouetteModelUtils.normalizeId(incoming.id, "tension", incoming.a + "_" + incoming.b);
        for (SilhouetteTension existing : section) {
            if (existing == null) {
                continue;
            }
            if (!id.equals(SilhouetteModelUtils.normalizeId(existing.id, "tension", existing.a + "_" + existing.b))) {
                continue;
            }
            existing.status = incoming.status;
            existing.confidence = reinforceScore(existing.confidence, incoming.confidence, 0.20);
            LinkedHashSet<String> evidenceIds = new LinkedHashSet<>(existing.evidenceIds);
            if (incoming.evidenceIds != null) {
                evidenceIds.addAll(incoming.evidenceIds);
            }
            existing.evidenceIds = new ArrayList<>(evidenceIds);
            return;
        }
        incoming.id = id;
        section.add(incoming);
    }

    private static void normalizeAndCap(SilhouetteState state, long now) {
        if (state == null || state.modes == null) {
            return;
        }
        for (SilhouetteMode mode : state.modes) {
            if (mode == null) {
                continue;
            }
            mode.id = SilhouetteModelUtils.normalizeId(mode.id, "mode", mode.label);
            mode.label = SilhouetteModelUtils.text(mode.label, 96);
            mode.status = SilhouetteMode.normalizeStatus(mode.status);
            mode.updatedAt = mode.updatedAt > 0L ? mode.updatedAt : now;
            mode.createdAt = mode.createdAt > 0L ? mode.createdAt : mode.updatedAt;
            mode.lastReinforcedAt = mode.lastReinforcedAt > 0L ? mode.lastReinforcedAt : mode.updatedAt;
            mode.selfExpression = capConcepts(mode.selfExpression);
            mode.seekingExpression = capConcepts(mode.seekingExpression);
            mode.sparkTriggers = capConcepts(mode.sparkTriggers);
            mode.sustainabilityNeeds = capConcepts(mode.sustainabilityNeeds);
            mode.aestheticField = capConcepts(mode.aestheticField);
            mode.antiPatterns = capAntiPatterns(mode.antiPatterns);
            mode.tensions = capTensions(mode.tensions);
            mode.evidence = capEvidence(mode.evidence);
            mode.openQuestions = capOpenQuestions(mode.openQuestions);
        }
        state.modes.removeIf(mode -> mode == null || mode.id == null || mode.id.isBlank());
        state.modes.sort((a, b) -> {
            int byStatus = Integer.compare(modeStatusPriority(a.status), modeStatusPriority(b.status));
            if (byStatus != 0) {
                return byStatus;
            }
            int byWeight = Double.compare(SilhouetteModelUtils.clamp01(b.weight), SilhouetteModelUtils.clamp01(a.weight));
            if (byWeight != 0) {
                return byWeight;
            }
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        if (state.modes.size() > MAX_MODES) {
            state.modes = new ArrayList<>(state.modes.subList(0, MAX_MODES));
        }
    }

    private static void recomputeModeScores(SilhouetteState state, long now) {
        if (state == null || state.modes == null || state.modes.isEmpty()) {
            return;
        }
        double totalRawWeight = 0.0;
        LinkedHashMap<SilhouetteMode, Double> rawWeights = new LinkedHashMap<>();
        for (SilhouetteMode mode : state.modes) {
            if (mode == null || "deprecated".equals(mode.status)) {
                rawWeights.put(mode, 0.0);
                continue;
            }
            double conceptStrength = averageConceptStrength(mode);
            double evidenceStrength = evidenceStrength(mode);
            double raw = 0.20 + (0.40 * conceptStrength) + (0.40 * (1.0 - Math.exp(-evidenceStrength / 3.0)));
            raw = raw * statusWeight(mode.status);
            rawWeights.put(mode, raw);
            totalRawWeight += raw;
        }
        for (Map.Entry<SilhouetteMode, Double> entry : rawWeights.entrySet()) {
            SilhouetteMode mode = entry.getKey();
            if (mode == null) {
                continue;
            }
            mode.weight = totalRawWeight <= 1e-9 ? 0.0 : SilhouetteModelUtils.clamp01(entry.getValue() / totalRawWeight);
            mode.confidence = modeConfidence(mode);
            if (!"deprecated".equals(mode.status) && !"dormant".equals(mode.status)) {
                mode.status = recomputeStatus(mode);
            }
            mode.updatedAt = mode.updatedAt > 0L ? mode.updatedAt : now;
        }
    }

    private static List<SilhouetteConcept> capConcepts(List<SilhouetteConcept> raw) {
        LinkedHashMap<String, SilhouetteConcept> byId = new LinkedHashMap<>();
        if (raw != null) {
            for (SilhouetteConcept concept : raw) {
                if (concept == null || concept.label == null || concept.label.isBlank()) {
                    continue;
                }
                SilhouetteConcept normalized = new SilhouetteConcept(concept);
                normalized.id = SilhouetteModelUtils.normalizeId(normalized.id, "concept", normalized.label);
                normalized.role = SilhouetteModelUtils.oneOf(normalized.role, "context",
                        "core", "accent", "context", "experimental");
                normalized.confidence = SilhouetteModelUtils.clamp01(normalized.confidence);
                normalized.strength = SilhouetteModelUtils.clamp01(normalized.strength);
                SilhouetteConcept existing = byId.get(normalized.id);
                if (existing == null) {
                    byId.put(normalized.id, normalized);
                } else {
                    upsertConceptInto(new ArrayList<>(List.of(existing)), normalized, true);
                    existing.role = higherRole(existing.role, normalized.role);
                    existing.confidence = Math.max(existing.confidence, normalized.confidence);
                    existing.strength = Math.max(existing.strength, normalized.strength);
                }
            }
        }
        ArrayList<SilhouetteConcept> out = new ArrayList<>(byId.values());
        out.sort(conceptComparator());
        if (out.size() > MAX_CONCEPTS_PER_SECTION) {
            return new ArrayList<>(out.subList(0, MAX_CONCEPTS_PER_SECTION));
        }
        return out;
    }

    private static List<SilhouetteAntiPattern> capAntiPatterns(List<SilhouetteAntiPattern> raw) {
        LinkedHashMap<String, SilhouetteAntiPattern> byId = new LinkedHashMap<>();
        if (raw != null) {
            for (SilhouetteAntiPattern anti : raw) {
                if (anti == null || anti.label == null || anti.label.isBlank()) {
                    continue;
                }
                SilhouetteAntiPattern normalized = new SilhouetteAntiPattern(anti);
                normalized.id = SilhouetteModelUtils.normalizeId(normalized.id, "anti", normalized.label);
                normalized.confidence = SilhouetteModelUtils.clamp01(normalized.confidence);
                SilhouetteAntiPattern existing = byId.get(normalized.id);
                if (existing == null) {
                    byId.put(normalized.id, normalized);
                } else {
                    existing.confidence = Math.max(existing.confidence, normalized.confidence);
                    existing.severity = higherSeverity(existing.severity, normalized.severity);
                }
            }
        }
        ArrayList<SilhouetteAntiPattern> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> {
            int bySeverity = Integer.compare(severityPriority(a.severity), severityPriority(b.severity));
            if (bySeverity != 0) {
                return bySeverity;
            }
            return Double.compare(SilhouetteModelUtils.clamp01(b.confidence), SilhouetteModelUtils.clamp01(a.confidence));
        });
        if (out.size() > MAX_ANTI_PATTERNS_PER_MODE) {
            return new ArrayList<>(out.subList(0, MAX_ANTI_PATTERNS_PER_MODE));
        }
        return out;
    }

    private static List<SilhouetteTension> capTensions(List<SilhouetteTension> raw) {
        LinkedHashMap<String, SilhouetteTension> byId = new LinkedHashMap<>();
        if (raw != null) {
            for (SilhouetteTension tension : raw) {
                if (tension == null || tension.a == null || tension.a.isBlank()
                        || tension.b == null || tension.b.isBlank()) {
                    continue;
                }
                SilhouetteTension normalized = new SilhouetteTension(tension);
                normalized.id = SilhouetteModelUtils.normalizeId(normalized.id, "tension",
                        normalized.a + "_" + normalized.b);
                normalized.confidence = SilhouetteModelUtils.clamp01(normalized.confidence);
                SilhouetteTension existing = byId.get(normalized.id);
                if (existing == null) {
                    byId.put(normalized.id, normalized);
                } else {
                    existing.confidence = Math.max(existing.confidence, normalized.confidence);
                    existing.status = normalized.status;
                }
            }
        }
        ArrayList<SilhouetteTension> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> Double.compare(SilhouetteModelUtils.clamp01(b.confidence),
                SilhouetteModelUtils.clamp01(a.confidence)));
        if (out.size() > MAX_TENSIONS_PER_MODE) {
            return new ArrayList<>(out.subList(0, MAX_TENSIONS_PER_MODE));
        }
        return out;
    }

    private static List<SilhouetteEvidence> capEvidence(List<SilhouetteEvidence> raw) {
        LinkedHashMap<String, SilhouetteEvidence> byId = new LinkedHashMap<>();
        if (raw != null) {
            for (SilhouetteEvidence evidence : raw) {
                if (evidence == null || evidence.value == null || evidence.value.isBlank()) {
                    continue;
                }
                SilhouetteEvidence normalized = new SilhouetteEvidence(evidence);
                normalized.source = SilhouetteEvidence.normalizeSource(normalized.source);
                normalized.target = SilhouetteEvidence.normalizeTarget(normalized.target);
                normalized.id = SilhouetteModelUtils.normalizeId(normalized.id, "ev", normalized.value);
                normalized.strength = SilhouetteModelUtils.clamp01(normalized.strength);
                normalized.confidence = SilhouetteModelUtils.clamp01(normalized.confidence);
                normalized.sourceWeight = Math.max(0.0, Math.min(1.25, normalized.sourceWeight));
                SilhouetteEvidence existing = byId.get(normalized.id);
                if (existing == null || normalized.createdAt >= existing.createdAt) {
                    byId.put(normalized.id, normalized);
                }
            }
        }
        ArrayList<SilhouetteEvidence> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> {
            int byCreated = Long.compare(b.createdAt, a.createdAt);
            if (byCreated != 0) {
                return byCreated;
            }
            return Double.compare(b.strength * b.confidence * b.sourceWeight, a.strength * a.confidence * a.sourceWeight);
        });
        if (out.size() > MAX_EVIDENCE_PER_MODE) {
            return new ArrayList<>(out.subList(0, MAX_EVIDENCE_PER_MODE));
        }
        return out;
    }

    private static List<String> capOpenQuestions(List<String> raw) {
        return SilhouetteModelUtils.stringList(raw, MAX_OPEN_QUESTIONS_PER_MODE, 180);
    }

    private static String buildRerankerSummary(SilhouetteState out) {
        SilhouetteDigest digest = SilhouetteDigest.fromState(out);
        StringBuilder buf = new StringBuilder();
        if (digest.topModes != null) {
            for (SilhouetteModeDigest mode : digest.topModes) {
                if (mode == null) {
                    continue;
                }
                appendModeDigestSummary(buf, mode, false);
            }
        }
        return SilhouetteModelUtils.text(buf.toString(), RERANKER_SUMMARY_MAX_CHARS);
    }

    private static String buildAdminSummary(SilhouetteState out) {
        SilhouetteDigest digest = SilhouetteDigest.fromState(out);
        StringBuilder buf = new StringBuilder();
        buf.append("maturity=").append(out == null ? "empty" : SilhouetteState.normalizeMaturity(out.maturity));
        if (digest.topModes != null) {
            for (SilhouetteModeDigest mode : digest.topModes) {
                if (mode == null) {
                    continue;
                }
                buf.append('\n');
                appendModeDigestSummary(buf, mode, true);
            }
        }
        return SilhouetteModelUtils.text(buf.toString(), ADMIN_SUMMARY_MAX_CHARS);
    }

    private static void appendModeDigestSummary(StringBuilder buf, SilhouetteModeDigest mode, boolean includeScores) {
        if (buf == null || mode == null) {
            return;
        }
        if (buf.length() > 0 && !buf.toString().endsWith("\n")) {
            buf.append(" | ");
        }
        buf.append(mode.label == null || mode.label.isBlank() ? mode.id : mode.label);
        if (includeScores) {
            buf.append("@")
                    .append(String.format(Locale.ROOT, "%.2f", SilhouetteModelUtils.clamp01(mode.weight)))
                    .append("/")
                    .append(String.format(Locale.ROOT, "%.2f", SilhouetteModelUtils.clamp01(mode.confidence)));
        }
        appendListSummary(buf, "self", mode.self);
        appendListSummary(buf, "seeking", mode.seeking);
        appendListSummary(buf, "spark", mode.sparkTriggers);
        appendListSummary(buf, "sustain", mode.sustainabilityNeeds);
        appendListSummary(buf, "aesthetic", mode.aestheticField);
        appendListSummary(buf, "anti", mode.antiPatterns);
    }

    private static void appendListSummary(StringBuilder buf, String label, List<String> values) {
        if (buf == null || values == null || values.isEmpty()) {
            return;
        }
        ArrayList<String> kept = new ArrayList<>();
        for (String value : values) {
            String text = SilhouetteModelUtils.text(value, 70);
            if (!text.isBlank()) {
                kept.add(text);
            }
            if (kept.size() >= 3) {
                break;
            }
        }
        if (!kept.isEmpty()) {
            buf.append("; ").append(label).append(":").append(String.join(",", kept));
        }
    }

    private static void mergeModeShell(SilhouetteMode existing, SilhouetteMode incoming, long now) {
        if (existing == null || incoming == null) {
            return;
        }
        if (existing.label == null || existing.label.isBlank()) {
            existing.label = incoming.label;
        }
        existing.status = SilhouetteMode.normalizeStatus(incoming.status);
        existing.confidence = Math.max(existing.confidence, incoming.confidence);
        existing.updatedAt = now;
        existing.lastReinforcedAt = now;
    }

    private static SilhouetteMode findModeById(SilhouetteState out, String id) {
        if (out == null || out.modes == null || id == null || id.isBlank()) {
            return null;
        }
        for (SilhouetteMode mode : out.modes) {
            if (mode != null && id.equals(mode.id)) {
                return mode;
            }
        }
        return null;
    }

    private static SilhouetteMode weakestNonDeprecatedMode(SilhouetteState out) {
        if (out == null || out.modes == null || out.modes.isEmpty()) {
            return null;
        }
        SilhouetteMode weakest = null;
        for (SilhouetteMode mode : out.modes) {
            if (mode == null || "deprecated".equals(mode.status)) {
                continue;
            }
            if (weakest == null || mode.weight < weakest.weight) {
                weakest = mode;
            }
        }
        return weakest;
    }

    private static List<SilhouetteMode> activeModes(SilhouetteState state) {
        ArrayList<SilhouetteMode> out = new ArrayList<>();
        if (state != null && state.modes != null) {
            for (SilhouetteMode mode : state.modes) {
                if (mode != null && !"deprecated".equals(mode.status) && !"dormant".equals(mode.status)) {
                    out.add(mode);
                }
            }
        }
        return out;
    }

    private static boolean containsConcept(SilhouetteMode mode, String conceptId) {
        if (mode == null || conceptId == null) {
            return false;
        }
        return containsConcept(mode.selfExpression, conceptId)
                || containsConcept(mode.seekingExpression, conceptId)
                || containsConcept(mode.sparkTriggers, conceptId)
                || containsConcept(mode.sustainabilityNeeds, conceptId)
                || containsConcept(mode.aestheticField, conceptId);
    }

    private static boolean containsConcept(List<SilhouetteConcept> concepts, String conceptId) {
        if (concepts == null) {
            return false;
        }
        for (SilhouetteConcept concept : concepts) {
            if (concept != null && conceptId.equals(SilhouetteModelUtils.normalizeId(concept.id, "concept", concept.label))) {
                return true;
            }
        }
        return false;
    }

    private static void removeConcept(List<SilhouetteConcept> concepts, String conceptId) {
        if (concepts != null && conceptId != null) {
            concepts.removeIf(c -> c != null && conceptId.equals(SilhouetteModelUtils.normalizeId(c.id, "concept", c.label)));
        }
    }

    private static String preferredModeId(SilhouettePatch.Op op, String label) {
        if (op != null && op.modeId != null && !op.modeId.isBlank()) {
            return op.modeId;
        }
        return SilhouetteModelUtils.normalizeId(null, "mode", label == null || label.isBlank() ? "emerging" : label);
    }

    private static String labelForTarget(String target, SilhouetteConcept concept) {
        if (concept != null && concept.label != null && !concept.label.isBlank()) {
            return concept.label;
        }
        return switch (SilhouetteEvidence.normalizeTarget(target)) {
            case "seeking_expression" -> "seeking pattern";
            case "spark_triggers" -> "spark pattern";
            case "sustainability_needs" -> "connection pattern";
            case "aesthetic_field" -> "aesthetic pattern";
            default -> "emerging mode";
        };
    }

    private static double reinforceScore(double existing, double incoming, double liftRate) {
        double base = SilhouetteModelUtils.clamp01(existing);
        double inc = SilhouetteModelUtils.clamp01(incoming);
        return SilhouetteModelUtils.clamp01(Math.max(base, inc) + ((1.0 - Math.max(base, inc)) * inc * liftRate));
    }

    private static Comparator<SilhouetteConcept> conceptComparator() {
        return (a, b) -> {
            int byRole = Integer.compare(rolePriority(a.role), rolePriority(b.role));
            if (byRole != 0) {
                return byRole;
            }
            int byStrength = Double.compare(SilhouetteModelUtils.clamp01(b.strength),
                    SilhouetteModelUtils.clamp01(a.strength));
            if (byStrength != 0) {
                return byStrength;
            }
            return Double.compare(SilhouetteModelUtils.clamp01(b.confidence),
                    SilhouetteModelUtils.clamp01(a.confidence));
        };
    }

    static int rolePriority(String role) {
        return switch (SilhouetteModelUtils.oneOf(role, "context", "core", "accent", "context", "experimental")) {
            case "core" -> 0;
            case "accent" -> 1;
            case "context" -> 2;
            default -> 3;
        };
    }

    private static String higherRole(String a, String b) {
        return rolePriority(b) < rolePriority(a) ? b : a;
    }

    private static String higherSeverity(String a, String b) {
        return severityPriority(b) < severityPriority(a) ? b : a;
    }

    private static int severityPriority(String severity) {
        return switch (SilhouetteModelUtils.oneOf(severity, "low", "low", "medium", "high")) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private static int modeStatusPriority(String status) {
        return switch (SilhouetteMode.normalizeStatus(status)) {
            case "mature" -> 0;
            case "active" -> 1;
            case "emerging" -> 2;
            case "dormant" -> 3;
            default -> 4;
        };
    }

    private static double statusWeight(String status) {
        return switch (SilhouetteMode.normalizeStatus(status)) {
            case "mature" -> 1.10;
            case "active" -> 1.00;
            case "emerging" -> 0.85;
            case "dormant" -> 0.35;
            default -> 0.0;
        };
    }

    private static boolean hasConcepts(List<SilhouetteConcept> concepts) {
        return concepts != null && !concepts.isEmpty();
    }

    private static int coverageCount(SilhouetteMode mode) {
        int count = 0;
        if (hasConcepts(mode.selfExpression)) {
            count++;
        }
        if (hasConcepts(mode.seekingExpression)) {
            count++;
        }
        if (hasConcepts(mode.sparkTriggers)) {
            count++;
        }
        if (hasConcepts(mode.sustainabilityNeeds)) {
            count++;
        }
        if (hasConcepts(mode.aestheticField)) {
            count++;
        }
        if (mode.antiPatterns != null && !mode.antiPatterns.isEmpty()) {
            count++;
        }
        return count;
    }

    private static double averageConceptStrength(SilhouetteMode mode) {
        ArrayList<SilhouetteConcept> concepts = allConcepts(mode);
        if (concepts.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (SilhouetteConcept concept : concepts) {
            sum += SilhouetteModelUtils.clamp01(concept.strength);
        }
        return sum / concepts.size();
    }

    private static double evidenceStrength(SilhouetteMode mode) {
        if (mode == null || mode.evidence == null || mode.evidence.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (SilhouetteEvidence evidence : mode.evidence) {
            if (evidence == null) {
                continue;
            }
            sum += SilhouetteModelUtils.clamp01(evidence.strength)
                    * SilhouetteModelUtils.clamp01(evidence.confidence)
                    * Math.max(0.0, Math.min(1.25, evidence.sourceWeight));
        }
        return sum;
    }

    private static double modeConfidence(SilhouetteMode mode) {
        double sum = 0.0;
        int count = 0;
        for (SilhouetteConcept concept : allConcepts(mode)) {
            sum += SilhouetteModelUtils.clamp01(concept.confidence);
            count++;
        }
        if (mode != null && mode.antiPatterns != null) {
            for (SilhouetteAntiPattern anti : mode.antiPatterns) {
                if (anti != null) {
                    sum += SilhouetteModelUtils.clamp01(anti.confidence);
                    count++;
                }
            }
        }
        if (mode != null && mode.tensions != null) {
            for (SilhouetteTension tension : mode.tensions) {
                if (tension != null) {
                    sum += SilhouetteModelUtils.clamp01(tension.confidence);
                    count++;
                }
            }
        }
        if (mode != null && mode.evidence != null) {
            for (SilhouetteEvidence evidence : mode.evidence) {
                if (evidence != null) {
                    sum += SilhouetteModelUtils.clamp01(evidence.confidence)
                            * Math.min(1.0, Math.max(0.0, evidence.sourceWeight));
                    count++;
                }
            }
        }
        if (count == 0) {
            return 0.0;
        }
        return SilhouetteModelUtils.clamp01(sum / count);
    }

    private static String recomputeStatus(SilhouetteMode mode) {
        int evidenceCount = mode.evidence == null ? 0 : mode.evidence.size();
        int coverage = coverageCount(mode);
        if (evidenceCount >= 8 && coverage >= 5 && mode.confidence >= 0.62) {
            return "mature";
        }
        if (evidenceCount >= 3 && coverage >= 3 && mode.confidence >= 0.50) {
            return "active";
        }
        return "emerging";
    }

    private static ArrayList<SilhouetteConcept> allConcepts(SilhouetteMode mode) {
        ArrayList<SilhouetteConcept> out = new ArrayList<>();
        if (mode == null) {
            return out;
        }
        addAll(out, mode.selfExpression);
        addAll(out, mode.seekingExpression);
        addAll(out, mode.sparkTriggers);
        addAll(out, mode.sustainabilityNeeds);
        addAll(out, mode.aestheticField);
        return out;
    }

    private static void addAll(ArrayList<SilhouetteConcept> out, List<SilhouetteConcept> raw) {
        if (raw != null) {
            for (SilhouetteConcept concept : raw) {
                if (concept != null) {
                    out.add(concept);
                }
            }
        }
    }
}
