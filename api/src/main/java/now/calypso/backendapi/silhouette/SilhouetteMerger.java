package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SilhouetteMerger {
    private static final int STORY_MAX = 600;
    private static final int FACET_MAX = 8;
    private static final int ANCHOR_MAX = 8;
    private static final int META_MAX = 6;
    private static final int EVIDENCE_MAX = 40;
    private static final int HISTORY_MAX = 20;

    private static final Set<String> ALLOWED_FACETS = Set.of(
            "self_core",
            "seeking_core",
            "relationship_dynamic",
            "energy_style",
            "communication_style",
            "emotional_style",
            "trajectory",
            "hard_boundaries");

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

        SilhouetteState.EvidenceRef evidence = buildEvidence(source, sourceId, promptId, eventId, evidenceExcerpt,
                out.updatedAt);
        if (evidence != null) {
            upsertEvidence(out, evidence);
        }
        List<String> defaultEvidenceIds = evidence == null ? List.of() : List.of(evidence.id);

        int appliedOps = 0;
        if (patch != null && patch.ops != null) {
            for (SilhouettePatch.Op op : patch.ops) {
                if (op == null || op.op == null || op.op.isBlank()) {
                    continue;
                }
                String opName = op.op.trim().toLowerCase(Locale.ROOT);
                switch (opName) {
                    case "set_story":
                        appliedOps += applySetStory(out, op, weight);
                        break;
                    case "set_facet":
                    case "reinforce_facet":
                        appliedOps += applySetFacet(out, op, weight, out.updatedAt, defaultEvidenceIds);
                        break;
                    case "add_anchor":
                        appliedOps += applyAnchor(out, op, weight, out.updatedAt, defaultEvidenceIds);
                        break;
                    case "add_meta_observation":
                        appliedOps += applyMeta(out, op, weight, out.updatedAt, defaultEvidenceIds);
                        break;
                    case "add_evidence":
                        appliedOps += applyAdditionalEvidence(out, op, source, sourceId, promptId, out.updatedAt);
                        break;
                    case "prune_stale":
                        pruneStale(out, out.updatedAt);
                        appliedOps += 1;
                        break;
                    default:
                        break;
                }
            }
        }

        trimCollections(out);
        out.maturity = computeMaturity(out);
        appendHistory(out, eventId, source, sourceId, appliedOps);
        return out;
    }

    private static int applySetStory(SilhouetteState out, SilhouettePatch.Op op, double sourceWeight) {
        String candidate = clampText(op == null ? null : op.text, STORY_MAX);
        if (candidate.isEmpty()) {
            candidate = clampText(op == null ? null : op.summary, STORY_MAX);
        }
        if (candidate.isEmpty()) {
            return 0;
        }
        if (out.story == null || out.story.isBlank() || sourceWeight >= 0.75 || candidate.length() > out.story.length()) {
            out.story = candidate;
            return 1;
        }
        return 0;
    }

    private static int applySetFacet(SilhouetteState out, SilhouettePatch.Op op, double sourceWeight, long now,
            List<String> defaultEvidenceIds) {
        String key = normalizeFacetKey(op == null ? null : op.key);
        if (key == null) {
            return 0;
        }
        String summary = clampText(op.summary, 320);
        if (summary.isEmpty()) {
            summary = clampText(op.text, 320);
        }
        if (summary.isEmpty()) {
            return 0;
        }
        double confidence = clamp01((op.confidence == null ? 0.55 : op.confidence.doubleValue()) * sourceWeight);
        List<String> evidenceIds = mergeEvidenceIds(op.evidenceIds, defaultEvidenceIds);

        SilhouetteState.Facet existing = findFacet(out.facets, key);
        if (existing == null) {
            SilhouetteState.Facet created = new SilhouetteState.Facet();
            created.key = key;
            created.summary = summary;
            created.confidence = confidence;
            created.updatedAt = now;
            created.evidenceIds = evidenceIds;
            out.facets.add(created);
            return 1;
        }
        if (confidence >= existing.confidence - 0.04 || existing.summary == null || existing.summary.isBlank()) {
            existing.summary = summary;
        }
        existing.confidence = clamp01(Math.max(existing.confidence * 0.92, confidence));
        existing.updatedAt = now;
        existing.evidenceIds = mergeEvidenceIds(existing.evidenceIds, evidenceIds);
        return 1;
    }

    private static int applyAnchor(SilhouetteState out, SilhouettePatch.Op op, double sourceWeight, long now,
            List<String> defaultEvidenceIds) {
        String label = clampText(op == null ? null : op.label, 40);
        String kind = normalizeAnchorKind(op == null ? null : op.kind);
        String meaning = clampText(op == null ? null : op.summary, 220);
        if (meaning.isEmpty()) {
            meaning = clampText(op == null ? null : op.text, 220);
        }
        if (label.isEmpty() || meaning.isEmpty()) {
            return 0;
        }
        double confidence = clamp01((op.confidence == null ? 0.50 : op.confidence.doubleValue()) * sourceWeight);
        List<String> evidenceIds = mergeEvidenceIds(op.evidenceIds, defaultEvidenceIds);
        String normalizedLabel = label.toLowerCase(Locale.ROOT);
        SilhouetteState.Anchor existing = null;
        for (SilhouetteState.Anchor anchor : out.anchors) {
            if (anchor == null || anchor.label == null) {
                continue;
            }
            String existingKind = normalizeAnchorKind(anchor.kind);
            if (anchor.label.trim().equalsIgnoreCase(normalizedLabel)
                    && Objects.equals(existingKind, kind)) {
                existing = anchor;
                break;
            }
        }
        if (existing == null) {
            SilhouetteState.Anchor created = new SilhouetteState.Anchor();
            created.label = label;
            created.kind = kind;
            created.meaning = meaning;
            created.confidence = confidence;
            created.updatedAt = now;
            created.evidenceIds = evidenceIds;
            out.anchors.add(created);
            return 1;
        }
        if (existing.kind == null || existing.kind.isBlank()) {
            existing.kind = kind;
        }
        if (confidence >= existing.confidence - 0.03 || existing.meaning == null || existing.meaning.isBlank()) {
            existing.meaning = meaning;
        }
        existing.confidence = clamp01(Math.max(existing.confidence * 0.93, confidence));
        existing.updatedAt = now;
        existing.evidenceIds = mergeEvidenceIds(existing.evidenceIds, evidenceIds);
        return 1;
    }

    private static int applyMeta(SilhouetteState out, SilhouettePatch.Op op, double sourceWeight, long now,
            List<String> defaultEvidenceIds) {
        String key = normalizeMetaKey(op == null ? null : op.key);
        if (key == null) {
            key = normalizeMetaKey(op == null ? null : op.kind);
        }
        String summary = clampText(op == null ? null : op.summary, 120);
        if (summary.isEmpty()) {
            summary = clampText(op == null ? null : op.text, 120);
        }
        if (key == null || summary.isEmpty()) {
            return 0;
        }
        double confidence = clamp01((op.confidence == null ? 0.45 : op.confidence.doubleValue()) * sourceWeight);
        List<String> evidenceIds = mergeEvidenceIds(op.evidenceIds, defaultEvidenceIds);

        SilhouetteState.MetaObservation existing = findMeta(out.metaObservations, key);
        if (existing == null) {
            SilhouetteState.MetaObservation created = new SilhouetteState.MetaObservation();
            created.key = key;
            created.summary = summary;
            created.confidence = confidence;
            created.updatedAt = now;
            created.evidenceIds = evidenceIds;
            out.metaObservations.add(created);
            return 1;
        }
        if (confidence >= existing.confidence - 0.03 || existing.summary == null || existing.summary.isBlank()) {
            existing.summary = summary;
        }
        existing.confidence = clamp01(Math.max(existing.confidence * 0.92, confidence));
        existing.updatedAt = now;
        existing.evidenceIds = mergeEvidenceIds(existing.evidenceIds, evidenceIds);
        return 1;
    }

    private static int applyAdditionalEvidence(SilhouetteState out, SilhouettePatch.Op op, String source, String sourceId,
            String promptId, long now) {
        String excerpt = clampText(op == null ? null : op.summary, 160);
        if (excerpt.isEmpty()) {
            excerpt = clampText(op == null ? null : op.text, 160);
        }
        if (excerpt.isEmpty()) {
            return 0;
        }
        String customId = SilhouetteState.normalizeKey(op == null ? null : op.key);
        String generatedId = customId == null ? "ev_" + Long.toHexString(Math.abs((source + "|" + sourceId + "|" + excerpt).hashCode()))
                : customId;
        SilhouetteState.EvidenceRef ref = new SilhouetteState.EvidenceRef();
        ref.id = generatedId;
        ref.source = source == null ? "" : source;
        ref.sourceId = sourceId == null ? "" : sourceId;
        ref.promptId = promptId == null ? "" : promptId;
        ref.excerpt = excerpt;
        ref.createdAt = now;
        upsertEvidence(out, ref);
        return 1;
    }

    private static void pruneStale(SilhouetteState out, long now) {
        long staleCutoff = now - (45L * 24L * 60L * 60L * 1000L);
        out.metaObservations.removeIf(meta -> meta != null && meta.updatedAt > 0L
                && meta.updatedAt < staleCutoff
                && meta.confidence < 0.45);
    }

    private static SilhouetteState.Facet findFacet(List<SilhouetteState.Facet> facets, String key) {
        if (facets == null || facets.isEmpty() || key == null) {
            return null;
        }
        for (SilhouetteState.Facet facet : facets) {
            if (facet == null || facet.key == null) {
                continue;
            }
            if (facet.key.equalsIgnoreCase(key)) {
                return facet;
            }
        }
        return null;
    }

    private static SilhouetteState.MetaObservation findMeta(List<SilhouetteState.MetaObservation> metas, String key) {
        if (metas == null || metas.isEmpty() || key == null) {
            return null;
        }
        for (SilhouetteState.MetaObservation meta : metas) {
            if (meta == null || meta.key == null) {
                continue;
            }
            if (meta.key.equalsIgnoreCase(key)) {
                return meta;
            }
        }
        return null;
    }

    private static String normalizeFacetKey(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null) {
            return null;
        }
        if (!ALLOWED_FACETS.contains(key)) {
            return null;
        }
        return key;
    }

    private static String normalizeMetaKey(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null) {
            return null;
        }
        if ("shallow".equals(key)) {
            return "depth_vs_surface_focus";
        }
        return switch (key) {
            case "preference_specificity", "novelty_bias", "consistency", "depth_vs_surface_focus", "reciprocity_expectation" -> key;
            default -> null;
        };
    }

    private static String normalizeAnchorKind(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "partner_comp" -> key;
            default -> null;
        };
    }

    private static SilhouetteState.EvidenceRef buildEvidence(
            String source,
            String sourceId,
            String promptId,
            String eventId,
            String excerpt,
            long now) {
        String normalizedEventId = SilhouetteState.normalizeKey(eventId);
        if (normalizedEventId == null) {
            normalizedEventId = "ev_" + Long.toHexString(now);
        }
        String normalizedSource = source == null ? "" : source.trim();
        String normalizedSourceId = sourceId == null ? "" : sourceId.trim();
        String normalizedPromptId = promptId == null ? "" : promptId.trim();
        String normalizedExcerpt = clampText(excerpt, 160);
        SilhouetteState.EvidenceRef ref = new SilhouetteState.EvidenceRef();
        ref.id = normalizedEventId;
        ref.source = normalizedSource;
        ref.sourceId = normalizedSourceId;
        ref.promptId = normalizedPromptId;
        ref.excerpt = normalizedExcerpt;
        ref.createdAt = now;
        return ref;
    }

    private static void upsertEvidence(SilhouetteState out, SilhouetteState.EvidenceRef ref) {
        if (out == null || ref == null || ref.id == null || ref.id.isBlank()) {
            return;
        }
        for (int i = 0; i < out.evidence.size(); i++) {
            SilhouetteState.EvidenceRef existing = out.evidence.get(i);
            if (existing == null || existing.id == null) {
                continue;
            }
            if (existing.id.equals(ref.id)) {
                out.evidence.set(i, ref);
                return;
            }
        }
        out.evidence.add(ref);
    }

    private static List<String> mergeEvidenceIds(List<String> left, List<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (left != null) {
            for (String id : left) {
                String normalized = SilhouetteState.normalizeKey(id);
                if (normalized != null) {
                    merged.add(normalized);
                }
            }
        }
        if (right != null) {
            for (String id : right) {
                String normalized = SilhouetteState.normalizeKey(id);
                if (normalized != null) {
                    merged.add(normalized);
                }
            }
        }
        if (merged.size() > 8) {
            ArrayList<String> limited = new ArrayList<>(merged);
            return new ArrayList<>(limited.subList(0, 8));
        }
        return new ArrayList<>(merged);
    }

    private static void appendHistory(SilhouetteState out, String eventId, String source, String sourceId, int opCount) {
        SilhouetteState.HistoryEntry entry = new SilhouetteState.HistoryEntry();
        entry.eventId = SilhouetteState.normalizeKey(eventId == null ? "unknown_event" : eventId);
        entry.source = source == null ? "" : source;
        entry.sourceId = sourceId == null ? "" : sourceId;
        entry.summary = opCount <= 0 ? "no_ops" : ("applied_ops=" + opCount);
        entry.opCount = Math.max(0, opCount);
        entry.updatedAt = out.updatedAt;
        out.history.add(entry);
        if (out.history.size() > HISTORY_MAX) {
            out.history = new ArrayList<>(out.history.subList(out.history.size() - HISTORY_MAX, out.history.size()));
        }
    }

    private static void trimCollections(SilhouetteState out) {
        if (out.story != null && out.story.length() > STORY_MAX) {
            out.story = out.story.substring(0, STORY_MAX).trim();
        }
        out.facets = keepMostRecentFacets(out.facets, FACET_MAX);
        out.anchors = keepMostRecentAnchors(out.anchors, ANCHOR_MAX);
        out.metaObservations = keepMostRecentMeta(out.metaObservations, META_MAX);
        out.evidence = keepMostRecentEvidence(out.evidence, EVIDENCE_MAX);
    }

    private static List<SilhouetteState.Facet> keepMostRecentFacets(List<SilhouetteState.Facet> facets, int limit) {
        LinkedHashMap<String, SilhouetteState.Facet> byKey = new LinkedHashMap<>();
        if (facets != null) {
            for (SilhouetteState.Facet facet : facets) {
                if (facet == null || facet.key == null || facet.key.isBlank()) {
                    continue;
                }
                String key = facet.key.trim().toLowerCase(Locale.ROOT);
                SilhouetteState.Facet prev = byKey.get(key);
                if (prev == null || facet.updatedAt >= prev.updatedAt) {
                    byKey.put(key, facet);
                }
            }
        }
        ArrayList<SilhouetteState.Facet> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> Long.compare(b == null ? 0L : b.updatedAt, a == null ? 0L : a.updatedAt));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private static List<SilhouetteState.Anchor> keepMostRecentAnchors(List<SilhouetteState.Anchor> anchors, int limit) {
        LinkedHashMap<String, SilhouetteState.Anchor> byLabel = new LinkedHashMap<>();
        if (anchors != null) {
            for (SilhouetteState.Anchor anchor : anchors) {
                if (anchor == null || anchor.label == null || anchor.label.isBlank()) {
                    continue;
                }
                String key = anchor.label.trim().toLowerCase(Locale.ROOT);
                SilhouetteState.Anchor prev = byLabel.get(key);
                if (prev == null || anchor.updatedAt >= prev.updatedAt) {
                    byLabel.put(key, anchor);
                }
            }
        }
        ArrayList<SilhouetteState.Anchor> out = new ArrayList<>(byLabel.values());
        out.sort((a, b) -> Long.compare(b == null ? 0L : b.updatedAt, a == null ? 0L : a.updatedAt));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private static List<SilhouetteState.MetaObservation> keepMostRecentMeta(
            List<SilhouetteState.MetaObservation> metas,
            int limit) {
        LinkedHashMap<String, SilhouetteState.MetaObservation> byKey = new LinkedHashMap<>();
        if (metas != null) {
            for (SilhouetteState.MetaObservation meta : metas) {
                if (meta == null || meta.key == null || meta.key.isBlank()) {
                    continue;
                }
                String key = meta.key.trim().toLowerCase(Locale.ROOT);
                SilhouetteState.MetaObservation prev = byKey.get(key);
                if (prev == null || meta.updatedAt >= prev.updatedAt) {
                    byKey.put(key, meta);
                }
            }
        }
        ArrayList<SilhouetteState.MetaObservation> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> Long.compare(b == null ? 0L : b.updatedAt, a == null ? 0L : a.updatedAt));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private static List<SilhouetteState.EvidenceRef> keepMostRecentEvidence(
            List<SilhouetteState.EvidenceRef> evidence,
            int limit) {
        LinkedHashMap<String, SilhouetteState.EvidenceRef> byId = new LinkedHashMap<>();
        if (evidence != null) {
            for (SilhouetteState.EvidenceRef ref : evidence) {
                if (ref == null || ref.id == null || ref.id.isBlank()) {
                    continue;
                }
                SilhouetteState.EvidenceRef prev = byId.get(ref.id);
                if (prev == null || ref.createdAt >= prev.createdAt) {
                    byId.put(ref.id, ref);
                }
            }
        }
        ArrayList<SilhouetteState.EvidenceRef> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> Long.compare(b == null ? 0L : b.createdAt, a == null ? 0L : a.createdAt));
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    public static String computeMaturity(SilhouetteState out) {
        if (out == null) {
            return "empty";
        }
        int facetCount = out.facets == null ? 0 : out.facets.size();
        double confSum = 0.0;
        int confCount = 0;
        if (out.facets != null) {
            for (SilhouetteState.Facet facet : out.facets) {
                if (facet == null) {
                    continue;
                }
                confSum += clamp01(facet.confidence);
                confCount++;
            }
        }
        double avgConf = confCount == 0 ? 0.0 : (confSum / (double) confCount);
        boolean hasStory = out.story != null && !out.story.isBlank();
        if (facetCount >= 3 && avgConf >= 0.55 && hasStory) {
            return "mature";
        }
        if (facetCount > 0 || hasStory || (out.anchors != null && !out.anchors.isEmpty())) {
            return "sparse";
        }
        return "empty";
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
}
