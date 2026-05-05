package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteEvidence {
    public String id;
    public String source;
    public String target;
    public String value;
    public List<String> derivedConceptIds;
    public double strength;
    public double confidence;
    public double sourceWeight;
    public String sourceId;
    public String promptId;
    public long createdAt;

    public SilhouetteEvidence() {
        this.id = "";
        this.source = "fallback";
        this.target = "self_expression";
        this.value = "";
        this.derivedConceptIds = new ArrayList<>();
        this.strength = 0.0;
        this.confidence = 0.0;
        this.sourceWeight = 0.30;
        this.sourceId = "";
        this.promptId = "";
        this.createdAt = System.currentTimeMillis();
    }

    public SilhouetteEvidence(SilhouetteEvidence other) {
        this();
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.source = other.source;
        this.target = other.target;
        this.value = other.value;
        this.derivedConceptIds = SilhouetteModelUtils.mutableList(other.derivedConceptIds);
        this.strength = other.strength;
        this.confidence = other.confidence;
        this.sourceWeight = other.sourceWeight;
        this.sourceId = other.sourceId;
        this.promptId = other.promptId;
        this.createdAt = other.createdAt;
    }

    public static SilhouetteEvidence fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String value = SilhouetteModelUtils.text(map.get("value"), 220);
        if (value.isBlank()) {
            return null;
        }
        long createdAt = SilhouetteModelUtils.parseLong(map.get("createdAt"), System.currentTimeMillis());
        SilhouetteEvidence out = new SilhouetteEvidence();
        out.value = value;
        out.source = normalizeSource(SilhouetteModelUtils.text(map.get("source"), 48));
        out.target = normalizeTarget(SilhouetteModelUtils.text(map.get("target"), 48));
        out.derivedConceptIds = SilhouetteConcept.normalizedIds(map.get("derivedConceptIds"), 16);
        out.strength = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("strength"), 0.50));
        out.confidence = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("confidence"), 0.50));
        double sourceWeight = SilhouetteModelUtils.parseDouble(map.get("sourceWeight"), defaultSourceWeight(out.source));
        out.sourceWeight = Math.max(0.0, Math.min(1.25, sourceWeight));
        out.sourceId = SilhouetteModelUtils.text(map.get("sourceId"), 96);
        out.promptId = SilhouetteModelUtils.text(map.get("promptId"), 96);
        out.createdAt = createdAt;
        out.id = SilhouetteModelUtils.normalizeId(map.get("id"), "ev",
                out.source + "_" + out.target + "_" + value + "_" + createdAt);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", SilhouetteModelUtils.normalizeId(id, "ev", source + "_" + target + "_" + value + "_" + createdAt));
        out.put("source", normalizeSource(source));
        out.put("target", normalizeTarget(target));
        out.put("value", SilhouetteModelUtils.text(value, 220));
        out.put("derivedConceptIds", SilhouetteConcept.normalizedIds(derivedConceptIds, 16));
        out.put("strength", SilhouetteModelUtils.clamp01(strength));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("sourceWeight", Math.max(0.0, Math.min(1.25, sourceWeight)));
        out.put("sourceId", SilhouetteModelUtils.text(sourceId, 96));
        out.put("promptId", SilhouetteModelUtils.text(promptId, 96));
        out.put("createdAt", createdAt > 0L ? createdAt : System.currentTimeMillis());
        return out;
    }

    public static String normalizeSource(String raw) {
        return SilhouetteModelUtils.oneOf(raw, "fallback",
                "fictional_comp", "visual_aesthetic", "music", "prompt_answer",
                "private_prompt", "matchmaking_followup", "prompt_reaction", "behavior",
                "public_prompt", "fallback");
    }

    public static String normalizeTarget(String raw) {
        return SilhouetteModelUtils.oneOf(raw, "self_expression",
                "self_expression", "seeking_expression", "spark_triggers",
                "sustainability_needs", "aesthetic_field", "anti_patterns", "tensions");
    }

    public static double defaultSourceWeight(String source) {
        return switch (normalizeSource(source)) {
            case "private_prompt" -> 1.0;
            case "matchmaking_followup" -> 0.85;
            case "prompt_answer" -> 0.65;
            case "prompt_reaction" -> 0.45;
            case "fictional_comp" -> 0.80;
            case "visual_aesthetic" -> 0.70;
            case "music" -> 0.60;
            case "behavior" -> 1.10;
            case "public_prompt" -> 0.45;
            default -> 0.30;
        };
    }
}
