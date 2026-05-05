package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteAntiPattern {
    public String id;
    public String label;
    public String scope;
    public String severity;
    public double confidence;
    public List<String> evidenceIds;

    public SilhouetteAntiPattern() {
        this.id = "";
        this.label = "";
        this.scope = "relational";
        this.severity = "low";
        this.confidence = 0.0;
        this.evidenceIds = new ArrayList<>();
    }

    public SilhouetteAntiPattern(SilhouetteAntiPattern other) {
        this();
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.label = other.label;
        this.scope = other.scope;
        this.severity = other.severity;
        this.confidence = other.confidence;
        this.evidenceIds = SilhouetteModelUtils.mutableList(other.evidenceIds);
    }

    public static SilhouetteAntiPattern fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String label = SilhouetteModelUtils.text(map.get("label"), 140);
        String id = SilhouetteModelUtils.normalizeId(map.get("id"), "anti", label);
        if (label.isBlank()) {
            label = id.replace('_', ' ');
        }
        SilhouetteAntiPattern out = new SilhouetteAntiPattern();
        out.id = id;
        out.label = label;
        out.scope = SilhouetteModelUtils.oneOf(
                SilhouetteModelUtils.text(map.get("scope"), 32),
                "relational",
                "self", "seeking", "aesthetic", "relational");
        out.severity = SilhouetteModelUtils.oneOf(
                SilhouetteModelUtils.text(map.get("severity"), 32),
                "low",
                "low", "medium", "high");
        out.confidence = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("confidence"), 0.45));
        out.evidenceIds = SilhouetteConcept.normalizedIds(map.get("evidenceIds"), 16);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", SilhouetteModelUtils.normalizeId(id, "anti", label));
        out.put("label", SilhouetteModelUtils.text(label, 140));
        out.put("scope", SilhouetteModelUtils.oneOf(scope, "relational", "self", "seeking", "aesthetic", "relational"));
        out.put("severity", SilhouetteModelUtils.oneOf(severity, "low", "low", "medium", "high"));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("evidenceIds", SilhouetteConcept.normalizedIds(evidenceIds, 16));
        return out;
    }
}
