package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import now.calypso.backendapi.signals.SignalConceptRegistry;
import now.calypso.backendapi.signals.SignalTaxonomy;

public final class SilhouetteMerger {
    private static final int CLAIM_MAX = 96;

    private static final Set<String> CANONICAL_FACETS = Set.of(
            "self_core",
            "seeking_core",
            "relationship_dynamic",
            "energy_style",
            "communication_style",
            "emotional_style",
            "trajectory",
            "hard_boundaries",
            "partner_comps",
            "meta_observation",
            "narrative",
            "general");
    private static final Set<String> GENERIC_META_SUBSTRINGS = Set.of(
            "focuses on lifestyle",
            "lifestyle and cultural markers",
            "cultural markers",
            "primary filters",
            "relationship compatibility",
            "filter for compatibility",
            "activity-based community",
            "social belonging",
            "specific activity-based community");
    private static final Set<String> ABSTRACT_CUE_TERMS = Set.of(
            "ambition",
            "reciprocity",
            "communication",
            "emotional",
            "trajectory",
            "intellectual",
            "values",
            "character",
            "reliab",
            "integrity",
            "growth");

    private SilhouetteMerger() {
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
        SilhouetteState out = base == null ? new SilhouetteState() : new SilhouetteState(base);
        out.updatedAt = now > 0L ? now : System.currentTimeMillis();
        out.version = Math.max(1L, out.version + 1L);

        double weight = clamp01(sourceWeight);
        if (weight <= 1e-6) {
            weight = 0.15;
        }

        if (patch != null && patch.ops != null) {
            for (SilhouettePatch.Op op : patch.ops) {
                if (op == null || op.op == null || op.op.isBlank()) {
                    continue;
                }
                String opName = canonicalOpName(op.op);
                ClaimDraft draft = buildClaimDraft(opName, op, source, sourceId, promptId, weight, out.updatedAt);
                if (draft == null) {
                    continue;
                }
                upsertClaim(out, draft);
            }
        }

        out.claims = keepMostRecentClaims(out.claims, CLAIM_MAX);
        out.maturity = computeMaturity(out);
        refreshSummaryCache(out);
        return out;
    }

    private static String canonicalOpName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String op = raw.trim().toLowerCase(Locale.ROOT);
        return switch (op) {
            case "set_facet", "reinforce_facet", "set_story", "add_anchor", "add_meta_observation" -> "upsert_claim";
            case "set_claim", "reinforce_claim", "upsert_claim", "retract_claim" -> op;
            default -> "";
        };
    }

    private static ClaimDraft buildClaimDraft(
            String opName,
            SilhouettePatch.Op op,
            String source,
            String sourceId,
            String promptId,
            double sourceWeight,
            long now) {
        if (opName == null || opName.isBlank() || op == null) {
            return null;
        }
        String facet = normalizeFacet(op.key);
        String kind = normalizeKind(op.kind);
        String text = "";
        double polarity = 1.0;

        if ("set_story".equals(normalizeLegacyOp(op.op))) {
            facet = "narrative";
        }
        if ("add_anchor".equals(normalizeLegacyOp(op.op))) {
            facet = "partner_comps";
            kind = kind == null ? "partner_comp" : kind;
            text = clampText(op.label, 100);
            if (text.isEmpty()) {
                text = clampText(op.summary, 180);
            }
            if (text.isEmpty()) {
                text = clampText(op.text, 180);
            }
            if (!text.isEmpty() && op.summary != null && !op.summary.isBlank() && op.label != null && !op.label.isBlank()) {
                String merged = clampText(op.label + ": " + op.summary, 220);
                if (!merged.isBlank()) {
                    text = merged;
                }
            }
        } else if ("add_meta_observation".equals(normalizeLegacyOp(op.op))) {
            facet = "meta_observation";
            if (kind == null) {
                kind = normalizeKind(op.key);
            }
        }

        if (text.isEmpty()) {
            text = clampText(op.summary, 220);
        }
        if (text.isEmpty()) {
            text = clampText(op.text, 220);
        }
        if (text.isEmpty()) {
            text = clampText(op.label, 120);
        }
        if (text.isEmpty()) {
            return null;
        }

        if (facet == null) {
            facet = inferFacetFromText(text, normalizeLegacyOp(op.op));
        }
        if (facet == null) {
            facet = "general";
        }
        if ("meta_observation".equals(facet) && isLowValueMetaObservationText(text)) {
            return null;
        }
        if (isConcreteSignalEchoClaim(facet, text)) {
            return null;
        }

        if ("hard_boundaries".equals(facet) || "retract_claim".equals(opName)) {
            polarity = -1.0;
        }

        double baseConfidence = op.confidence == null ? defaultConfidenceForFacet(facet) : clamp01(op.confidence.doubleValue());
        double confidence = clamp01(baseConfidence * sourceWeight);
        if (sourceWeight >= 0.95) {
            confidence = clamp01(Math.max(confidence, baseConfidence * 0.85));
        }

        ClaimDraft draft = new ClaimDraft();
        draft.facet = facet;
        draft.kind = kind;
        draft.text = text;
        draft.polarity = polarity;
        draft.confidence = confidence;
        draft.source = clampText(source, 60);
        draft.sourceId = clampText(sourceId, 96);
        draft.promptId = clampText(promptId, 96);
        draft.createdAt = now;
        return draft;
    }

    private static String normalizeLegacyOp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String inferFacetFromText(String text, String opName) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        if (lowered.contains("drawn to") || lowered.contains("looking for") || lowered.contains("partner")) {
            return "seeking_core";
        }
        if (lowered.contains("don't") || lowered.contains("dont") || lowered.contains("won't")
                || lowered.contains("boundary") || lowered.contains("turn off") || lowered.contains("dealbreaker")) {
            return "hard_boundaries";
        }
        if ("set_story".equals(opName)) {
            return "narrative";
        }
        return "self_core";
    }

    private static double defaultConfidenceForFacet(String facet) {
        if (facet == null) {
            return 0.50;
        }
        return switch (facet) {
            case "hard_boundaries" -> 0.62;
            case "partner_comps" -> 0.58;
            case "meta_observation" -> 0.46;
            case "narrative" -> 0.50;
            default -> 0.56;
        };
    }

    private static String normalizeFacet(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null) {
            return null;
        }
        if ("partner_comp".equals(key)) {
            return "partner_comps";
        }
        if ("meta".equals(key)) {
            return "meta_observation";
        }
        if (CANONICAL_FACETS.contains(key)) {
            return key;
        }
        return "general";
    }

    private static String normalizeKind(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null || key.isBlank()) {
            return null;
        }
        return clampText(key, 48);
    }

    private static void upsertClaim(SilhouetteState out, ClaimDraft draft) {
        if (out == null || draft == null || draft.text == null || draft.text.isBlank()) {
            return;
        }
        if (out.claims == null) {
            out.claims = new ArrayList<>();
        }
        String semanticKey = semanticKey(draft.facet, draft.kind, draft.text);
        for (SilhouetteState.Claim existing : out.claims) {
            if (existing == null || existing.text == null || existing.text.isBlank()) {
                continue;
            }
            if (!semanticKey.equals(semanticKey(existing.facet, existing.kind, existing.text))) {
                continue;
            }
            existing.confidence = clamp01(Math.max(existing.confidence * 0.92, draft.confidence));
            existing.polarity = draft.polarity;
            existing.createdAt = draft.createdAt;
            if (existing.facet == null || existing.facet.isBlank()) {
                existing.facet = draft.facet;
            }
            if (existing.kind == null || existing.kind.isBlank()) {
                existing.kind = draft.kind;
            }
            if (existing.source == null || existing.source.isBlank()) {
                existing.source = draft.source;
            }
            if (existing.sourceId == null || existing.sourceId.isBlank()) {
                existing.sourceId = draft.sourceId;
            }
            if (existing.promptId == null || existing.promptId.isBlank()) {
                existing.promptId = draft.promptId;
            }
            existing.text = draft.text;
            return;
        }

        SilhouetteState.Claim claim = new SilhouetteState.Claim();
        claim.id = "cl_" + Long.toHexString(Math.abs((semanticKey + "|" + draft.createdAt).hashCode()))
                + "_" + Long.toHexString(draft.createdAt);
        claim.facet = draft.facet;
        claim.kind = draft.kind == null ? "" : draft.kind;
        claim.text = draft.text;
        claim.polarity = draft.polarity;
        claim.confidence = draft.confidence;
        claim.source = draft.source == null ? "" : draft.source;
        claim.sourceId = draft.sourceId == null ? "" : draft.sourceId;
        claim.promptId = draft.promptId == null ? "" : draft.promptId;
        claim.createdAt = draft.createdAt;
        out.claims.add(claim);
    }

    private static String semanticKey(String facet, String kind, String text) {
        String safeFacet = facet == null ? "general" : facet.trim().toLowerCase(Locale.ROOT);
        String safeKind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        String safeText = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        return safeFacet + "|" + safeKind + "|" + safeText;
    }

    private static List<SilhouetteState.Claim> keepMostRecentClaims(List<SilhouetteState.Claim> claims, int limit) {
        LinkedHashMap<String, SilhouetteState.Claim> bySemantic = new LinkedHashMap<>();
        if (claims != null) {
            for (SilhouetteState.Claim claim : claims) {
                if (claim == null || claim.text == null || claim.text.isBlank()) {
                    continue;
                }
                String key = semanticKey(claim.facet, claim.kind, claim.text);
                SilhouetteState.Claim prev = bySemantic.get(key);
                if (prev == null || claim.createdAt >= prev.createdAt) {
                    bySemantic.put(key, claim);
                }
            }
        }
        ArrayList<SilhouetteState.Claim> out = new ArrayList<>(bySemantic.values());
        out.sort((a, b) -> Long.compare(b == null ? 0L : b.createdAt, a == null ? 0L : a.createdAt));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public static String computeMaturity(SilhouetteState out) {
        if (out == null || out.claims == null || out.claims.isEmpty()) {
            return "empty";
        }
        int claimCount = 0;
        int strongCount = 0;
        double sumConf = 0.0;
        LinkedHashSet<String> facets = new LinkedHashSet<>();
        for (SilhouetteState.Claim claim : out.claims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            claimCount += 1;
            double conf = clamp01(claim.confidence);
            sumConf += conf;
            if (conf >= 0.60) {
                strongCount += 1;
            }
            String facet = claim.facet == null || claim.facet.isBlank() ? "general" : claim.facet;
            facets.add(facet);
        }
        if (claimCount == 0) {
            return "empty";
        }
        double avg = sumConf / (double) claimCount;
        if (claimCount >= 6 && strongCount >= 3 && facets.size() >= 3 && avg >= 0.55) {
            return "mature";
        }
        return "sparse";
    }

    private static void refreshSummaryCache(SilhouetteState out) {
        if (out == null) {
            return;
        }
        SilhouetteState.SummaryCache cache = out.summaryCache == null
                ? new SilhouetteState.SummaryCache()
                : new SilhouetteState.SummaryCache(out.summaryCache);
        cache.rerankerShort = buildRerankerSummary(out);
        cache.adminLong = buildAdminSummary(out);
        cache.generatedFromVersion = Math.max(1L, out.version);
        cache.updatedAt = out.updatedAt > 0L ? out.updatedAt : System.currentTimeMillis();
        out.summaryCache = cache;
    }

    private static String buildRerankerSummary(SilhouetteState out) {
        if (out == null || out.claims == null || out.claims.isEmpty()) {
            return "";
        }
        ArrayList<String> segments = new ArrayList<>();
        addSummarySegment(segments, "self", joinFacetClaims(out.claims, "self_core", 1, false));
        addSummarySegment(segments, "seeking", joinFacetClaims(out.claims, "seeking_core", 1, false));
        addSummarySegment(segments, "dynamic", joinFacetClaims(out.claims, "relationship_dynamic", 1, false));
        addSummarySegment(segments, "boundaries", joinFacetClaims(out.claims, "hard_boundaries", 1, false));
        addSummarySegment(segments, "comps", joinFacetClaims(out.claims, "partner_comps", 2, true));
        if (segments.isEmpty()) {
            addSummarySegment(segments, "notes", joinFacetClaims(out.claims, "general", 2, false));
        }
        return clampText(String.join(" | ", segments), 260);
    }

    private static String buildAdminSummary(SilhouetteState out) {
        if (out == null || out.claims == null || out.claims.isEmpty()) {
            return "";
        }
        StringBuilder buf = new StringBuilder(760);
        String reranker = buildRerankerSummary(out);
        if (!reranker.isBlank()) {
            buf.append("summary: ").append(reranker).append('\n');
        }
        buf.append("recent_claims: ");
        int added = 0;
        ArrayList<String> rows = new ArrayList<>();
        for (SilhouetteState.Claim claim : out.claims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            StringBuilder row = new StringBuilder(140);
            row.append(claim.facet == null || claim.facet.isBlank() ? "general" : claim.facet)
                    .append("@")
                    .append(String.format(Locale.ROOT, "%.2f", clamp01(claim.confidence)))
                    .append(":")
                    .append(clampText(claim.text, 96));
            if (claim.kind != null && !claim.kind.isBlank()) {
                row.append(" [").append(clampText(claim.kind, 24)).append("]");
            }
            rows.add(row.toString());
            added += 1;
            if (added >= 8) {
                break;
            }
        }
        buf.append(String.join(" | ", rows));
        return clampText(buf.toString().trim(), 700);
    }

    private static void addSummarySegment(List<String> segments, String label, String value) {
        if (segments == null || label == null || label.isBlank() || value == null || value.isBlank()) {
            return;
        }
        segments.add(label + "=" + value);
    }

    private static String joinFacetClaims(
            List<SilhouetteState.Claim> claims,
            String facet,
            int maxItems,
            boolean preferLabelBeforeColon) {
        if (claims == null || claims.isEmpty() || facet == null || facet.isBlank() || maxItems <= 0) {
            return "";
        }
        ArrayList<SilhouetteState.Claim> pool = new ArrayList<>();
        for (SilhouetteState.Claim claim : claims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            String claimFacet = claim.facet == null ? "" : claim.facet.trim().toLowerCase(Locale.ROOT);
            if (!facet.equals(claimFacet)) {
                continue;
            }
            pool.add(claim);
        }
        if (pool.isEmpty()) {
            return "";
        }
        pool.sort((a, b) -> {
            int byConf = Double.compare(clamp01(b.confidence), clamp01(a.confidence));
            if (byConf != 0) {
                return byConf;
            }
            return Long.compare(b.createdAt, a.createdAt);
        });

        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (SilhouetteState.Claim claim : pool) {
            String text = clampText(claim.text, preferLabelBeforeColon ? 54 : 84);
            if (text.isBlank()) {
                continue;
            }
            if (preferLabelBeforeColon && text.contains(":")) {
                text = text.substring(0, text.indexOf(':')).trim();
            }
            lines.add(text);
            if (lines.size() >= maxItems) {
                break;
            }
        }
        return String.join("; ", lines);
    }

    private static boolean isLowValueMetaObservationText(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String generic : GENERIC_META_SUBSTRINGS) {
            if (generic != null && !generic.isBlank() && lowered.contains(generic)) {
                return true;
            }
        }
        return lowered.contains("focuses on")
                && (lowered.contains("lifestyle")
                        || lowered.contains("filters")
                        || lowered.contains("compatibility"));
    }

    private static boolean isConcreteSignalEchoClaim(String facet, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if ("partner_comps".equals(facet) || "narrative".equals(facet) || "meta_observation".equals(facet)) {
            return false;
        }
        String normalizedText = normalizePhrase(text);
        if (normalizedText.isBlank()) {
            return false;
        }
        for (String cue : ABSTRACT_CUE_TERMS) {
            if (cue != null && !cue.isBlank() && normalizedText.contains(cue)) {
                return false;
            }
        }
        LinkedHashSet<String> mentionedConcreteTokens = new LinkedHashSet<>();
        for (String concept : SignalConceptRegistry.canonicalConceptsSnapshot()) {
            if (concept == null || concept.isBlank()) {
                continue;
            }
            String category = SignalConceptRegistry.categoryForConcept(concept);
            if (!SignalTaxonomy.isConcreteCategory(category)) {
                continue;
            }
            String phrase = concept.replace('_', ' ');
            if (!containsPhrase(normalizedText, phrase)) {
                continue;
            }
            mentionedConcreteTokens.add(concept);
        }
        if (mentionedConcreteTokens.isEmpty()) {
            return false;
        }
        String residual = normalizedText;
        for (String token : mentionedConcreteTokens) {
            residual = residual.replace(token.replace('_', ' '), " ");
        }
        residual = residual
                .replaceAll(
                        "\\b(prefers?|prefer|exclude|excluding|dislike|dislikes|disliked|hate|hates|avoid|avoids|turn\\s+off|people|person|partners?|who|engage|with|into|culture|scene|vibes?|social|community|belonging|home|primary|identif(?:y|ies)|strongly|around|for|to|and|or|the|a|an|of|on|in|is|are|be|not|no|get|dont|don't|just)\\b",
                        " ")
                .replaceAll("\\s+", " ")
                .trim();
        if ("hard_boundaries".equals(facet)) {
            return residual.length() <= 28;
        }
        return residual.length() <= 16;
    }

    private static String normalizePhrase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsPhrase(String normalizedText, String phrase) {
        if (normalizedText == null || normalizedText.isBlank() || phrase == null || phrase.isBlank()) {
            return false;
        }
        String normalizedPhrase = phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalizedPhrase.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static String clampText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen).trim();
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static final class ClaimDraft {
        String facet;
        String kind;
        String text;
        double polarity;
        double confidence;
        String source;
        String sourceId;
        String promptId;
        long createdAt;
    }
}
