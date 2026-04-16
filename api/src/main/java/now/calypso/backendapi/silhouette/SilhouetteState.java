package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SilhouetteState {
    public long accountId;
    public long version;
    public String maturity;
    public String story;
    public List<Facet> facets;
    public List<Anchor> anchors;
    public List<MetaObservation> metaObservations;
    public List<EvidenceRef> evidence;
    public List<HistoryEntry> history;
    public long updatedAt;

    public SilhouetteState() {
        this.accountId = 0L;
        this.version = 1L;
        this.maturity = "empty";
        this.story = "";
        this.facets = new ArrayList<>();
        this.anchors = new ArrayList<>();
        this.metaObservations = new ArrayList<>();
        this.evidence = new ArrayList<>();
        this.history = new ArrayList<>();
        this.updatedAt = System.currentTimeMillis();
    }

    public SilhouetteState(SilhouetteState other) {
        this();
        if (other == null) {
            return;
        }
        this.accountId = other.accountId;
        this.version = other.version;
        this.maturity = normalizeMaturity(other.maturity);
        this.story = clampText(other.story, 600);
        this.updatedAt = other.updatedAt;
        for (Facet facet : other.facets) {
            this.facets.add(new Facet(facet));
        }
        for (Anchor anchor : other.anchors) {
            this.anchors.add(new Anchor(anchor));
        }
        for (MetaObservation observation : other.metaObservations) {
            this.metaObservations.add(new MetaObservation(observation));
        }
        for (EvidenceRef ref : other.evidence) {
            this.evidence.add(new EvidenceRef(ref));
        }
        for (HistoryEntry entry : other.history) {
            this.history.add(new HistoryEntry(entry));
        }
    }

    public static SilhouetteState empty(long accountId) {
        SilhouetteState out = new SilhouetteState();
        out.accountId = accountId;
        out.updatedAt = System.currentTimeMillis();
        return out;
    }

    @SuppressWarnings("unchecked")
    public static SilhouetteState fromMap(Map<String, Object> map, long fallbackAccountId) {
        SilhouetteState out = empty(fallbackAccountId);
        if (map == null || map.isEmpty()) {
            return out;
        }
        out.accountId = parseLong(map.get("accountId"), fallbackAccountId);
        out.version = Math.max(1L, parseLong(map.get("version"), 1L));
        out.maturity = normalizeMaturity(asTrimmed(map.get("maturity")));
        out.story = clampText(asTrimmed(map.get("story")), 600);
        out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());

        Object rawFacets = map.get("facets");
        if (rawFacets instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Facet facet = Facet.fromMap((Map<String, Object>) item);
                if (facet != null) {
                    out.facets.add(facet);
                }
            }
        }
        Object rawAnchors = map.get("anchors");
        if (rawAnchors instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Anchor anchor = Anchor.fromMap((Map<String, Object>) item);
                if (anchor != null) {
                    out.anchors.add(anchor);
                }
            }
        }
        Object rawMeta = map.get("metaObservations");
        if (rawMeta instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                MetaObservation observation = MetaObservation.fromMap((Map<String, Object>) item);
                if (observation != null) {
                    out.metaObservations.add(observation);
                }
            }
        }
        Object rawEvidence = map.get("evidence");
        if (rawEvidence instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                EvidenceRef ref = EvidenceRef.fromMap((Map<String, Object>) item);
                if (ref != null) {
                    out.evidence.add(ref);
                }
            }
        }
        Object rawHistory = map.get("history");
        if (rawHistory instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                HistoryEntry entry = HistoryEntry.fromMap((Map<String, Object>) item);
                if (entry != null) {
                    out.history.add(entry);
                }
            }
        }
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("version", Math.max(1L, version));
        out.put("maturity", normalizeMaturity(maturity));
        out.put("story", clampText(story, 600));
        out.put("updatedAt", updatedAt > 0L ? updatedAt : System.currentTimeMillis());

        ArrayList<Map<String, Object>> serializedFacets = new ArrayList<>();
        for (Facet facet : facets) {
            if (facet != null) {
                serializedFacets.add(facet.toMap());
            }
        }
        out.put("facets", serializedFacets);

        ArrayList<Map<String, Object>> serializedAnchors = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (anchor != null) {
                serializedAnchors.add(anchor.toMap());
            }
        }
        out.put("anchors", serializedAnchors);

        ArrayList<Map<String, Object>> serializedMeta = new ArrayList<>();
        for (MetaObservation observation : metaObservations) {
            if (observation != null) {
                serializedMeta.add(observation.toMap());
            }
        }
        out.put("metaObservations", serializedMeta);

        ArrayList<Map<String, Object>> serializedEvidence = new ArrayList<>();
        for (EvidenceRef ref : evidence) {
            if (ref != null) {
                serializedEvidence.add(ref.toMap());
            }
        }
        out.put("evidence", serializedEvidence);

        ArrayList<Map<String, Object>> serializedHistory = new ArrayList<>();
        for (HistoryEntry entry : history) {
            if (entry != null) {
                serializedHistory.add(entry.toMap());
            }
        }
        out.put("history", serializedHistory);
        return out;
    }

    public String digest(int maxChars) {
        int cap = Math.max(200, maxChars);
        StringBuilder buf = new StringBuilder(cap + 120);
        String maturityLabel = normalizeMaturity(maturity);
        buf.append("maturity=").append(maturityLabel).append('\n');
        if (story != null && !story.isBlank()) {
            buf.append("story: ").append(story.trim()).append('\n');
        }
        if (!facets.isEmpty()) {
            buf.append("facets:\n");
            int count = 0;
            for (Facet facet : facets) {
                if (facet == null || facet.key == null || facet.key.isBlank()) {
                    continue;
                }
                buf.append("- ").append(facet.key).append(" (")
                        .append(String.format(Locale.ROOT, "%.2f", clamp01(facet.confidence)))
                        .append("): ")
                        .append(facet.summary == null ? "" : facet.summary)
                        .append('\n');
                count++;
                if (count >= 6) {
                    break;
                }
            }
        }
        if (!anchors.isEmpty()) {
            buf.append("anchors:\n");
            int count = 0;
            for (Anchor anchor : anchors) {
                if (anchor == null || anchor.label == null || anchor.label.isBlank()) {
                    continue;
                }
                buf.append("- ").append(anchor.label).append(": ")
                        .append(anchor.meaning == null ? "" : anchor.meaning)
                        .append('\n');
                count++;
                if (count >= 5) {
                    break;
                }
            }
        }
        String digest = buf.toString().trim();
        if (digest.length() <= cap) {
            return digest;
        }
        return digest.substring(0, cap).trim();
    }

    public static String normalizeMaturity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "empty";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("mature".equals(normalized)) {
            return "mature";
        }
        if ("sparse".equals(normalized)) {
            return "sparse";
        }
        return "empty";
    }

    private static String asTrimmed(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
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

    private static long parseLong(Object raw, long fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double parseDouble(Object raw, double fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    public static final class Facet {
        public String key;
        public String summary;
        public double confidence;
        public long updatedAt;
        public List<String> evidenceIds;

        public Facet() {
            this.key = "";
            this.summary = "";
            this.confidence = 0.0;
            this.updatedAt = System.currentTimeMillis();
            this.evidenceIds = new ArrayList<>();
        }

        public Facet(Facet other) {
            this();
            if (other == null) {
                return;
            }
            this.key = other.key;
            this.summary = other.summary;
            this.confidence = other.confidence;
            this.updatedAt = other.updatedAt;
            this.evidenceIds = new ArrayList<>(other.evidenceIds == null ? List.of() : other.evidenceIds);
        }

        @SuppressWarnings("unchecked")
        static Facet fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String key = normalizeKey(asTrimmed(map.get("key")));
            if (key == null) {
                return null;
            }
            Facet out = new Facet();
            out.key = key;
            out.summary = clampText(asTrimmed(map.get("summary")), 360);
            out.confidence = clamp01(parseDouble(map.get("confidence"), 0.0));
            out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());
            Object rawEvidence = map.get("evidenceIds");
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            if (rawEvidence instanceof List<?> list) {
                for (Object item : list) {
                    String normalized = normalizeKey(asTrimmed(item));
                    if (normalized != null) {
                        evidence.add(normalized);
                    }
                    if (evidence.size() >= 8) {
                        break;
                    }
                }
            }
            out.evidenceIds = new ArrayList<>(evidence);
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("key", normalizeKey(key));
            out.put("summary", clampText(summary, 360));
            out.put("confidence", clamp01(confidence));
            out.put("updatedAt", updatedAt);
            out.put("evidenceIds", new ArrayList<>(evidenceIds == null ? List.of() : evidenceIds));
            return out;
        }
    }

    public static final class Anchor {
        public String label;
        public String kind;
        public String meaning;
        public double confidence;
        public long updatedAt;
        public List<String> evidenceIds;

        public Anchor() {
            this.label = "";
            this.kind = "";
            this.meaning = "";
            this.confidence = 0.0;
            this.updatedAt = System.currentTimeMillis();
            this.evidenceIds = new ArrayList<>();
        }

        public Anchor(Anchor other) {
            this();
            if (other == null) {
                return;
            }
            this.label = other.label;
            this.kind = other.kind;
            this.meaning = other.meaning;
            this.confidence = other.confidence;
            this.updatedAt = other.updatedAt;
            this.evidenceIds = new ArrayList<>(other.evidenceIds == null ? List.of() : other.evidenceIds);
        }

        @SuppressWarnings("unchecked")
        static Anchor fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String label = clampText(asTrimmed(map.get("label")), 60);
            if (label.isBlank()) {
                return null;
            }
            Anchor out = new Anchor();
            out.label = label;
            out.kind = normalizeAnchorKind(asTrimmed(map.get("kind")));
            out.meaning = clampText(asTrimmed(map.get("meaning")), 260);
            out.confidence = clamp01(parseDouble(map.get("confidence"), 0.0));
            out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());
            Object rawEvidence = map.get("evidenceIds");
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            if (rawEvidence instanceof List<?> list) {
                for (Object item : list) {
                    String normalized = normalizeKey(asTrimmed(item));
                    if (normalized != null) {
                        evidence.add(normalized);
                    }
                }
            }
            out.evidenceIds = new ArrayList<>(evidence);
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("label", clampText(label, 60));
            out.put("kind", normalizeAnchorKind(kind));
            out.put("meaning", clampText(meaning, 260));
            out.put("confidence", clamp01(confidence));
            out.put("updatedAt", updatedAt);
            out.put("evidenceIds", new ArrayList<>(evidenceIds == null ? List.of() : evidenceIds));
            return out;
        }
    }

    public static final class MetaObservation {
        public String key;
        public String summary;
        public double confidence;
        public long updatedAt;
        public List<String> evidenceIds;

        public MetaObservation() {
            this.key = "";
            this.summary = "";
            this.confidence = 0.0;
            this.updatedAt = System.currentTimeMillis();
            this.evidenceIds = new ArrayList<>();
        }

        public MetaObservation(MetaObservation other) {
            this();
            if (other == null) {
                return;
            }
            this.key = other.key;
            this.summary = other.summary;
            this.confidence = other.confidence;
            this.updatedAt = other.updatedAt;
            this.evidenceIds = new ArrayList<>(other.evidenceIds == null ? List.of() : other.evidenceIds);
        }

        @SuppressWarnings("unchecked")
        static MetaObservation fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String key = normalizeKey(asTrimmed(map.get("key")));
            if (key == null) {
                return null;
            }
            MetaObservation out = new MetaObservation();
            out.key = key;
            out.summary = clampText(asTrimmed(map.get("summary")), 140);
            out.confidence = clamp01(parseDouble(map.get("confidence"), 0.0));
            out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());
            Object rawEvidence = map.get("evidenceIds");
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            if (rawEvidence instanceof List<?> list) {
                for (Object item : list) {
                    String normalized = normalizeKey(asTrimmed(item));
                    if (normalized != null) {
                        evidence.add(normalized);
                    }
                }
            }
            out.evidenceIds = new ArrayList<>(evidence);
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("key", normalizeKey(key));
            out.put("summary", clampText(summary, 140));
            out.put("confidence", clamp01(confidence));
            out.put("updatedAt", updatedAt);
            out.put("evidenceIds", new ArrayList<>(evidenceIds == null ? List.of() : evidenceIds));
            return out;
        }
    }

    public static final class EvidenceRef {
        public String id;
        public String source;
        public String sourceId;
        public String promptId;
        public String excerpt;
        public long createdAt;

        public EvidenceRef() {
            this.id = "";
            this.source = "";
            this.sourceId = "";
            this.promptId = "";
            this.excerpt = "";
            this.createdAt = System.currentTimeMillis();
        }

        public EvidenceRef(EvidenceRef other) {
            this();
            if (other == null) {
                return;
            }
            this.id = other.id;
            this.source = other.source;
            this.sourceId = other.sourceId;
            this.promptId = other.promptId;
            this.excerpt = other.excerpt;
            this.createdAt = other.createdAt;
        }

        static EvidenceRef fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String id = normalizeKey(asTrimmed(map.get("id")));
            if (id == null) {
                return null;
            }
            EvidenceRef out = new EvidenceRef();
            out.id = id;
            out.source = clampText(asTrimmed(map.get("source")), 60);
            out.sourceId = clampText(asTrimmed(map.get("sourceId")), 96);
            out.promptId = clampText(asTrimmed(map.get("promptId")), 96);
            out.excerpt = clampText(asTrimmed(map.get("excerpt")), 180);
            out.createdAt = parseLong(map.get("createdAt"), System.currentTimeMillis());
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("id", normalizeKey(id));
            out.put("source", clampText(source, 60));
            out.put("sourceId", clampText(sourceId, 96));
            out.put("promptId", clampText(promptId, 96));
            out.put("excerpt", clampText(excerpt, 180));
            out.put("createdAt", createdAt);
            return out;
        }
    }

    public static final class HistoryEntry {
        public String eventId;
        public String source;
        public String sourceId;
        public String summary;
        public int opCount;
        public long updatedAt;

        public HistoryEntry() {
            this.eventId = "";
            this.source = "";
            this.sourceId = "";
            this.summary = "";
            this.opCount = 0;
            this.updatedAt = System.currentTimeMillis();
        }

        public HistoryEntry(HistoryEntry other) {
            this();
            if (other == null) {
                return;
            }
            this.eventId = other.eventId;
            this.source = other.source;
            this.sourceId = other.sourceId;
            this.summary = other.summary;
            this.opCount = other.opCount;
            this.updatedAt = other.updatedAt;
        }

        static HistoryEntry fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String eventId = normalizeKey(asTrimmed(map.get("eventId")));
            if (eventId == null) {
                return null;
            }
            HistoryEntry out = new HistoryEntry();
            out.eventId = eventId;
            out.source = clampText(asTrimmed(map.get("source")), 60);
            out.sourceId = clampText(asTrimmed(map.get("sourceId")), 96);
            out.summary = clampText(asTrimmed(map.get("summary")), 180);
            out.opCount = (int) parseLong(map.get("opCount"), 0L);
            out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("eventId", normalizeKey(eventId));
            out.put("source", clampText(source, 60));
            out.put("sourceId", clampText(sourceId, 96));
            out.put("summary", clampText(summary, 180));
            out.put("opCount", opCount);
            out.put("updatedAt", updatedAt);
            return out;
        }
    }

    public static String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            } else if (c == ' ') {
                out.append('_');
            }
        }
        String normalized = out.toString().replaceAll("_+", "_");
        return normalized.isBlank() ? null : normalized;
    }

    private static String normalizeAnchorKind(String raw) {
        String key = normalizeKey(raw);
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "partner_comp" -> key;
            default -> null;
        };
    }
}
