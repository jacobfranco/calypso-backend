package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class SilhouettePatch {
    private static final ObjectMapper JSON = new ObjectMapper();

    public final List<Op> ops = new ArrayList<>();

    public static SilhouettePatch empty() {
        return new SilhouettePatch();
    }

    @SuppressWarnings("unchecked")
    public static SilhouettePatch fromRawJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return empty();
        }
        try {
            Object parsed = JSON.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                return fromMap((Map<String, Object>) map);
            }
        } catch (Exception ignored) {
            // return empty on malformed output; caller can fallback heuristically.
        }
        return empty();
    }

    @SuppressWarnings("unchecked")
    public static SilhouettePatch fromMap(Map<String, Object> map) {
        SilhouettePatch out = new SilhouettePatch();
        if (map == null || map.isEmpty()) {
            return out;
        }
        Object rawOps = map.get("ops");
        if (!(rawOps instanceof List<?> list) || list.isEmpty()) {
            return out;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> opMap = (Map<String, Object>) item;
            Op op = Op.fromMap(opMap);
            if (op != null) {
                out.ops.add(op);
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> outOps = new ArrayList<>();
        for (Op op : ops) {
            if (op == null) {
                continue;
            }
            Map<String, Object> opMap = op.toMap();
            if (!opMap.isEmpty()) {
                outOps.add(opMap);
            }
        }
        HashMap<String, Object> out = new HashMap<>();
        out.put("ops", outOps);
        return out;
    }

    public static final class Op {
        public final String op;
        public final String key;
        public final String summary;
        public final String text;
        public final String label;
        public final String kind;
        public final Double confidence;
        public final List<String> evidenceIds;

        public Op(String op, String key, String summary, String text, String label, String kind,
                Double confidence, List<String> evidenceIds) {
            this.op = op;
            this.key = key;
            this.summary = summary;
            this.text = text;
            this.label = label;
            this.kind = kind;
            this.confidence = confidence;
            this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
        }

        @SuppressWarnings("unchecked")
        static Op fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String op = normalizeToken(map.get("op"));
            if (op == null) {
                return null;
            }
            String key = normalizeToken(map.get("key"));
            String summary = normalizeText(map.get("summary"), 320);
            String text = normalizeText(map.get("text"), 900);
            String label = normalizeText(map.get("label"), 96);
            String kind = normalizeToken(map.get("kind"));
            Double confidence = normalizeConfidence(map.get("confidence"));

            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            Object rawEvidence = map.get("evidenceIds");
            if (rawEvidence instanceof List<?> list) {
                for (Object item : list) {
                    String normalized = normalizeToken(item);
                    if (normalized != null) {
                        evidence.add(normalized);
                    }
                    if (evidence.size() >= 8) {
                        break;
                    }
                }
            } else if (rawEvidence instanceof String one) {
                String normalized = normalizeToken(one);
                if (normalized != null) {
                    evidence.add(normalized);
                }
            }
            return new Op(op, key, summary, text, label, kind, confidence, new ArrayList<>(evidence));
        }

        Map<String, Object> toMap() {
            HashMap<String, Object> out = new HashMap<>();
            if (op != null && !op.isBlank()) {
                out.put("op", op);
            }
            if (key != null && !key.isBlank()) {
                out.put("key", key);
            }
            if (summary != null && !summary.isBlank()) {
                out.put("summary", summary);
            }
            if (text != null && !text.isBlank()) {
                out.put("text", text);
            }
            if (label != null && !label.isBlank()) {
                out.put("label", label);
            }
            if (kind != null && !kind.isBlank()) {
                out.put("kind", kind);
            }
            if (confidence != null && Double.isFinite(confidence.doubleValue())) {
                out.put("confidence", confidence.doubleValue());
            }
            if (evidenceIds != null && !evidenceIds.isEmpty()) {
                out.put("evidenceIds", new ArrayList<>(evidenceIds));
            }
            return out;
        }

        private static String normalizeToken(Object raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.toString().trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                return null;
            }
            StringBuilder out = new StringBuilder(trimmed.length());
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                    out.append(c);
                } else if (c == ' ') {
                    out.append('_');
                }
            }
            String normalized = out.toString().replaceAll("_+", "_");
            return normalized.isEmpty() ? null : normalized;
        }

        private static String normalizeText(Object raw, int maxLen) {
            if (raw == null) {
                return null;
            }
            String text = raw.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            if (text.length() > maxLen) {
                return text.substring(0, maxLen).trim();
            }
            return text;
        }

        private static Double normalizeConfidence(Object raw) {
            if (raw == null) {
                return null;
            }
            try {
                double value = Double.parseDouble(raw.toString());
                if (!Double.isFinite(value)) {
                    return null;
                }
                if (value < 0.0) {
                    value = 0.0;
                } else if (value > 1.0) {
                    value = 1.0;
                }
                return value;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
