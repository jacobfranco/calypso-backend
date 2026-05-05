package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteTension {
    public String id;
    public String a;
    public String b;
    public String status;
    public double confidence;
    public List<String> evidenceIds;

    public SilhouetteTension() {
        this.id = "";
        this.a = "";
        this.b = "";
        this.status = "productive_tension";
        this.confidence = 0.0;
        this.evidenceIds = new ArrayList<>();
    }

    public SilhouetteTension(SilhouetteTension other) {
        this();
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.a = other.a;
        this.b = other.b;
        this.status = other.status;
        this.confidence = other.confidence;
        this.evidenceIds = SilhouetteModelUtils.mutableList(other.evidenceIds);
    }

    public static SilhouetteTension fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String a = SilhouetteModelUtils.text(map.get("a"), 120);
        String b = SilhouetteModelUtils.text(map.get("b"), 120);
        if (a.isBlank() || b.isBlank()) {
            return null;
        }
        SilhouetteTension out = new SilhouetteTension();
        out.a = a;
        out.b = b;
        out.id = SilhouetteModelUtils.normalizeId(map.get("id"), "tension", a + "_" + b);
        out.status = SilhouetteModelUtils.oneOf(
                SilhouetteModelUtils.text(map.get("status"), 48),
                "productive_tension",
                "productive_tension", "unresolved_conflict");
        out.confidence = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("confidence"), 0.45));
        out.evidenceIds = SilhouetteConcept.normalizedIds(map.get("evidenceIds"), 16);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", SilhouetteModelUtils.normalizeId(id, "tension", a + "_" + b));
        out.put("a", SilhouetteModelUtils.text(a, 120));
        out.put("b", SilhouetteModelUtils.text(b, 120));
        out.put("status", SilhouetteModelUtils.oneOf(status, "productive_tension",
                "productive_tension", "unresolved_conflict"));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("evidenceIds", SilhouetteConcept.normalizedIds(evidenceIds, 16));
        return out;
    }
}
