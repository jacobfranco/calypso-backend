package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.HashMap;
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
            // Caller can fallback heuristically.
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
            Op op = Op.fromMap((Map<String, Object>) item);
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
        ArrayList<Map<String, Object>> outOps = new ArrayList<>();
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
        public String op;
        public String modeId;
        public String target;
        public String label;
        public String status;
        public Double weight;
        public Double confidence;
        public Double strength;
        public SilhouetteMode mode;
        public SilhouetteConcept concept;
        public SilhouetteAntiPattern antiPattern;
        public SilhouetteTension tension;
        public SilhouetteEvidence evidence;
        public String openQuestion;

        public Op() {
        }

        public static Op upsertConcept(String modeId, String modeLabel, String target, SilhouetteConcept concept,
                SilhouetteEvidence evidence) {
            Op out = new Op();
            out.op = "upsert_concept";
            out.modeId = modeId;
            out.label = modeLabel;
            out.target = target;
            out.concept = concept;
            out.evidence = evidence;
            return out;
        }

        public static Op upsertAntiPattern(String modeId, String modeLabel, SilhouetteAntiPattern antiPattern,
                SilhouetteEvidence evidence) {
            Op out = new Op();
            out.op = "upsert_anti_pattern";
            out.modeId = modeId;
            out.label = modeLabel;
            out.target = "anti_patterns";
            out.antiPattern = antiPattern;
            out.evidence = evidence;
            return out;
        }

        public static Op addOpenQuestion(String modeId, String modeLabel, String question) {
            Op out = new Op();
            out.op = "add_open_question";
            out.modeId = modeId;
            out.label = modeLabel;
            out.openQuestion = question;
            return out;
        }

        static Op fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String op = canonicalOpName(SilhouetteModelUtils.text(map.get("op"), 64));
            if (op.isBlank()) {
                return null;
            }
            Op out = new Op();
            out.op = op;
            out.modeId = SilhouetteModelUtils.normalizeKey(
                    SilhouetteModelUtils.first(map, "modeId", "mode_id"));
            out.target = SilhouetteEvidence.normalizeTarget(SilhouetteModelUtils.text(map.get("target"), 64));
            out.label = SilhouetteModelUtils.text(map.get("label"), 140);
            out.status = SilhouetteMode.normalizeStatus(SilhouetteModelUtils.text(map.get("status"), 32));
            out.weight = optional01(map.get("weight"));
            out.confidence = optional01(map.get("confidence"));
            out.strength = optional01(map.get("strength"));
            out.openQuestion = SilhouetteModelUtils.text(
                    SilhouetteModelUtils.first(map, "openQuestion", "open_question", "question"), 260);

            Map<String, Object> modeMap = SilhouetteModelUtils.objectMap(map.get("mode"));
            out.mode = SilhouetteMode.fromMap(modeMap);
            Map<String, Object> conceptMap = SilhouetteModelUtils.objectMap(map.get("concept"));
            out.concept = SilhouetteConcept.fromMap(conceptMap);
            Map<String, Object> antiMap = SilhouetteModelUtils.objectMap(
                    SilhouetteModelUtils.first(map, "antiPattern", "anti_pattern"));
            out.antiPattern = SilhouetteAntiPattern.fromMap(antiMap);
            Map<String, Object> tensionMap = SilhouetteModelUtils.objectMap(map.get("tension"));
            out.tension = SilhouetteTension.fromMap(tensionMap);
            Map<String, Object> evidenceMap = SilhouetteModelUtils.objectMap(map.get("evidence"));
            out.evidence = SilhouetteEvidence.fromMap(evidenceMap);

            // Allow compact add_evidence ops without a nested evidence object.
            if (out.evidence == null && "add_evidence".equals(out.op)) {
                SilhouetteEvidence evidence = SilhouetteEvidence.fromMap(map);
                out.evidence = evidence;
            }
            return out;
        }

        Map<String, Object> toMap() {
            HashMap<String, Object> out = new HashMap<>();
            if (op != null && !op.isBlank()) {
                out.put("op", op);
            }
            if (modeId != null && !modeId.isBlank()) {
                out.put("modeId", modeId);
            }
            if (target != null && !target.isBlank()) {
                out.put("target", SilhouetteEvidence.normalizeTarget(target));
            }
            if (label != null && !label.isBlank()) {
                out.put("label", SilhouetteModelUtils.text(label, 140));
            }
            if (status != null && !status.isBlank()) {
                out.put("status", SilhouetteMode.normalizeStatus(status));
            }
            if (weight != null && Double.isFinite(weight.doubleValue())) {
                out.put("weight", SilhouetteModelUtils.clamp01(weight.doubleValue()));
            }
            if (confidence != null && Double.isFinite(confidence.doubleValue())) {
                out.put("confidence", SilhouetteModelUtils.clamp01(confidence.doubleValue()));
            }
            if (strength != null && Double.isFinite(strength.doubleValue())) {
                out.put("strength", SilhouetteModelUtils.clamp01(strength.doubleValue()));
            }
            if (mode != null) {
                out.put("mode", mode.toMap());
            }
            if (concept != null) {
                out.put("concept", concept.toMap());
            }
            if (antiPattern != null) {
                out.put("antiPattern", antiPattern.toMap());
            }
            if (tension != null) {
                out.put("tension", tension.toMap());
            }
            if (evidence != null) {
                out.put("evidence", evidence.toMap());
            }
            if (openQuestion != null && !openQuestion.isBlank()) {
                out.put("openQuestion", SilhouetteModelUtils.text(openQuestion, 260));
            }
            return out;
        }

        private static Double optional01(Object raw) {
            if (raw == null) {
                return null;
            }
            double value = SilhouetteModelUtils.parseDouble(raw, Double.NaN);
            if (!Double.isFinite(value)) {
                return null;
            }
            return SilhouetteModelUtils.clamp01(value);
        }

        private static String canonicalOpName(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            String op = raw.trim().toLowerCase(Locale.ROOT);
            return switch (op) {
                case "upsert_mode", "reinforce_mode", "deprecate_mode",
                        "upsert_concept", "reinforce_concept", "retract_concept",
                        "upsert_anti_pattern", "upsert_antipattern",
                        "upsert_tension", "add_evidence", "add_open_question" -> {
                    if ("upsert_antipattern".equals(op)) {
                        yield "upsert_anti_pattern";
                    }
                    yield op;
                }
                default -> "";
            };
        }
    }
}
