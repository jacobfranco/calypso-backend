package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class SilhouetteConcept {
    public String id;
    public String label;
    public String role;
    public double confidence;
    public double strength;
    public List<String> evidenceIds;

    public SilhouetteConcept() {
        this.id = "";
        this.label = "";
        this.role = "context";
        this.confidence = 0.0;
        this.strength = 0.0;
        this.evidenceIds = new ArrayList<>();
    }

    public SilhouetteConcept(SilhouetteConcept other) {
        this();
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.label = other.label;
        this.role = other.role;
        this.confidence = other.confidence;
        this.strength = other.strength;
        this.evidenceIds = SilhouetteModelUtils.mutableList(other.evidenceIds);
    }

    public static SilhouetteConcept fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String label = SilhouetteModelUtils.text(map.get("label"), 120);
        String id = SilhouetteModelUtils.normalizeId(map.get("id"), "concept", label);
        if (label.isBlank()) {
            label = id.replace('_', ' ');
        }
        SilhouetteConcept out = new SilhouetteConcept();
        out.id = id;
        out.label = label;
        out.role = SilhouetteModelUtils.oneOf(
                SilhouetteModelUtils.text(map.get("role"), 32),
                "context",
                "core", "accent", "context", "experimental");
        out.confidence = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("confidence"), 0.50));
        out.strength = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("strength"), 0.50));
        out.evidenceIds = normalizedIds(map.get("evidenceIds"), 16);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", SilhouetteModelUtils.normalizeId(id, "concept", label));
        out.put("label", SilhouetteModelUtils.text(label, 120));
        out.put("role", SilhouetteModelUtils.oneOf(role, "context", "core", "accent", "context", "experimental"));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("strength", SilhouetteModelUtils.clamp01(strength));
        out.put("evidenceIds", normalizedIds(evidenceIds, 16));
        return out;
    }

    static List<String> normalizedIds(Object raw, int maxItems) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String id = SilhouetteModelUtils.normalizeKey(item);
                if (id != null) {
                    ids.add(id);
                }
                if (ids.size() >= maxItems) {
                    break;
                }
            }
        } else {
            String id = SilhouetteModelUtils.normalizeKey(raw);
            if (id != null) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }
}
