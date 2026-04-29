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
    private static final int RERANKER_SUMMARY_MAX_CLAIMS = 10;
    private static final int RERANKER_SUMMARY_MAX_CHARS = 1200;
    private static final int ADMIN_SUMMARY_MAX_CLAIMS = 10;
    private static final int ADMIN_SUMMARY_MAX_CHARS = 1800;
    private static final int RERANKER_SUMMARY_ITEM_TEXT_MAX = 180;
    private static final int ADMIN_SUMMARY_ITEM_TEXT_MAX = 180;

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
        List<SilhouetteState.Claim> ranked = rankClaimsForSummary(out.claims);
        String summary = compactClaimsSummary(
                ranked,
                RERANKER_SUMMARY_MAX_CLAIMS,
                RERANKER_SUMMARY_MAX_CHARS,
                false);
        return clampText(summary, RERANKER_SUMMARY_MAX_CHARS);
    }

    private static String buildAdminSummary(SilhouetteState out) {
        if (out == null || out.claims == null || out.claims.isEmpty()) {
            return "";
        }
        List<SilhouetteState.Claim> ranked = rankClaimsForSummary(out.claims);
        StringBuilder buf = new StringBuilder(760);
        String reranker = buildRerankerSummary(out);
        if (!reranker.isBlank()) {
            buf.append("summary: ").append(reranker).append('\n');
        }
        String rankedSummary = compactClaimsSummary(
                ranked,
                ADMIN_SUMMARY_MAX_CLAIMS,
                ADMIN_SUMMARY_MAX_CHARS - 40,
                true);
        if (!rankedSummary.isBlank()) {
            buf.append("ranked_claims: ").append(rankedSummary);
        }
        return clampText(buf.toString().trim(), ADMIN_SUMMARY_MAX_CHARS);
    }

    private static List<SilhouetteState.Claim> rankClaimsForSummary(List<SilhouetteState.Claim> claims) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        ArrayList<SilhouetteState.Claim> ranked = new ArrayList<>();
        for (SilhouetteState.Claim claim : claims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            ranked.add(claim);
        }
        ranked.sort((a, b) -> {
            int byConfidence = Double.compare(clamp01(b.confidence), clamp01(a.confidence));
            if (byConfidence != 0) {
                return byConfidence;
            }
            int byFacet = Integer.compare(facetPriority(a.facet), facetPriority(b.facet));
            if (byFacet != 0) {
                return byFacet;
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
        return ranked;
    }

    private static int facetPriority(String facet) {
        if (facet == null || facet.isBlank()) {
            return 99;
        }
        return switch (facet) {
            case "hard_boundaries" -> 0;
            case "seeking_core" -> 1;
            case "self_core" -> 2;
            case "relationship_dynamic" -> 3;
            case "partner_comps" -> 4;
            case "communication_style" -> 5;
            case "emotional_style" -> 6;
            case "energy_style" -> 7;
            case "trajectory" -> 8;
            case "narrative" -> 9;
            default -> 10;
        };
    }

    private static String compactClaimsSummary(
            List<SilhouetteState.Claim> rankedClaims,
            int maxClaims,
            int maxChars,
            boolean includeConfidence) {
        if (rankedClaims == null || rankedClaims.isEmpty() || maxClaims <= 0 || maxChars <= 0) {
            return "";
        }
        StringBuilder buf = new StringBuilder(Math.min(480, Math.max(64, maxChars + 24)));
        int added = 0;
        for (SilhouetteState.Claim claim : rankedClaims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            String facetLabel = facetSummaryLabel(claim.facet);
            String text = clampText(
                    claim.text,
                    includeConfidence ? ADMIN_SUMMARY_ITEM_TEXT_MAX : RERANKER_SUMMARY_ITEM_TEXT_MAX);
            if (text.isBlank()) {
                continue;
            }
            String item;
            if (includeConfidence) {
                item = facetLabel
                        + "@"
                        + String.format(Locale.ROOT, "%.2f", clamp01(claim.confidence))
                        + ":"
                        + text;
            } else {
                item = facetLabel + ":" + text;
            }
            if (item.length() > maxChars && buf.length() == 0) {
                return clampText(item, maxChars);
            }
            String prefixed = buf.length() == 0 ? item : " | " + item;
            if (buf.length() + prefixed.length() > maxChars) {
                break;
            }
            buf.append(prefixed);
            added += 1;
            if (added >= maxClaims) {
                break;
            }
        }
        return buf.toString().trim();
    }

    private static String facetSummaryLabel(String facet) {
        if (facet == null || facet.isBlank()) {
            return "general";
        }
        return switch (facet) {
            case "self_core" -> "self";
            case "seeking_core" -> "seeking";
            case "relationship_dynamic" -> "dynamic";
            case "hard_boundaries" -> "boundaries";
            case "partner_comps" -> "comps";
            case "communication_style" -> "comms";
            case "emotional_style" -> "emotional";
            case "energy_style" -> "energy";
            case "trajectory" -> "trajectory";
            case "meta_observation" -> "meta";
            default -> facet;
        };
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
                        "\\b(prefers?|prefer|wants?|wanted|share|shares|shared|interests?|hobb(?:y|ies)|exclude|excluding|dislike|dislikes|disliked|hate|hates|avoid|avoids|turn\\s+off|people|person|partners?|who|engage|with|into|culture|scene|vibes?|social|community|belonging|home|primary|identif(?:y|ies)|strongly|around|for|to|and|or|the|a|an|of|on|in|is|are|be|not|no|get|dont|don't|just)\\b",
                        " ")
                .replaceAll("\\s+", " ")
                .trim();
        if ("hard_boundaries".equals(facet)) {
            return residual.length() <= 40;
        }
        return residual.length() <= 30;
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
        String clipped = trimmed.substring(0, maxLen).trim();
        int lastSpace = clipped.lastIndexOf(' ');
        if (lastSpace >= maxLen * 0.6) {
            return clipped.substring(0, lastSpace).trim();
        }
        return clipped;
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
