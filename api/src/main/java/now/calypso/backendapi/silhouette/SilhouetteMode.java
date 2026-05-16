package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteMode {
    private static final String LEGACY_FORMATIVE_SEED_AFFINITIES = "formative seed affinities";
    private static final String FORMATIVE_MEDIA_IMPRINTS = "formative media imprints";

    public String id;
    public String label;
    public String status;
    public double weight;
    public double confidence;
    public List<SilhouetteConcept> selfExpression;
    public List<SilhouetteConcept> seekingExpression;
    public List<SilhouetteConcept> sparkTriggers;
    public List<SilhouetteConcept> sustainabilityNeeds;
    public List<SilhouetteConcept> aestheticField;
    public List<SilhouetteConcept> realWorldComps;
    public List<SilhouetteAntiPattern> antiPatterns;
    public List<SilhouetteTension> tensions;
    public List<SilhouetteEvidence> evidence;
    public List<String> openQuestions;
    public long createdAt;
    public long updatedAt;
    public long lastReinforcedAt;

    public SilhouetteMode() {
        this.id = "";
        this.label = "";
        this.status = "emerging";
        this.weight = 0.0;
        this.confidence = 0.0;
        this.selfExpression = new ArrayList<>();
        this.seekingExpression = new ArrayList<>();
        this.sparkTriggers = new ArrayList<>();
        this.sustainabilityNeeds = new ArrayList<>();
        this.aestheticField = new ArrayList<>();
        this.realWorldComps = new ArrayList<>();
        this.antiPatterns = new ArrayList<>();
        this.tensions = new ArrayList<>();
        this.evidence = new ArrayList<>();
        this.openQuestions = new ArrayList<>();
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastReinforcedAt = now;
    }

    public SilhouetteMode(SilhouetteMode other) {
        this();
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.label = canonicalLabel(other.label);
        this.status = other.status;
        this.weight = other.weight;
        this.confidence = other.confidence;
        this.selfExpression = copyConcepts(other.selfExpression);
        this.seekingExpression = copyConcepts(other.seekingExpression);
        this.sparkTriggers = copyConcepts(other.sparkTriggers);
        this.sustainabilityNeeds = copyConcepts(other.sustainabilityNeeds);
        this.aestheticField = copyConcepts(other.aestheticField);
        this.realWorldComps = copyConcepts(other.realWorldComps);
        this.antiPatterns = copyAntiPatterns(other.antiPatterns);
        this.tensions = copyTensions(other.tensions);
        this.evidence = copyEvidence(other.evidence);
        this.openQuestions = SilhouetteModelUtils.mutableList(other.openQuestions);
        this.createdAt = other.createdAt;
        this.updatedAt = other.updatedAt;
        this.lastReinforcedAt = other.lastReinforcedAt;
    }

    public static SilhouetteMode fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String label = SilhouetteModelUtils.text(map.get("label"), 140);
        String id = SilhouetteModelUtils.normalizeId(map.get("id"), "mode", label);
        if (label.isBlank()) {
            label = id.replace('_', ' ');
        }
        label = canonicalLabel(label);
        SilhouetteMode out = new SilhouetteMode();
        out.id = id;
        out.label = label;
        out.status = normalizeStatus(SilhouetteModelUtils.text(map.get("status"), 32));
        out.weight = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("weight"), 0.0));
        out.confidence = SilhouetteModelUtils.clamp01(SilhouetteModelUtils.parseDouble(map.get("confidence"), 0.0));
        out.selfExpression = conceptsFrom(map.get("selfExpression"), map.get("self_expression"));
        out.seekingExpression = conceptsFrom(map.get("seekingExpression"), map.get("seeking_expression"));
        out.sparkTriggers = conceptsFrom(map.get("sparkTriggers"), map.get("spark_triggers"));
        out.sustainabilityNeeds = conceptsFrom(map.get("sustainabilityNeeds"), map.get("sustainability_needs"));
        out.aestheticField = conceptsFrom(map.get("aestheticField"), map.get("aesthetic_field"));
        out.realWorldComps = conceptsFrom(map.get("realWorldComps"), map.get("real_world_comps"));
        out.antiPatterns = antiPatternsFrom(map.get("antiPatterns"), map.get("anti_patterns"));
        out.tensions = tensionsFrom(map.get("tensions"));
        out.evidence = evidenceFrom(map.get("evidence"));
        out.openQuestions = SilhouetteModelUtils.stringList(
                SilhouetteModelUtils.first(map, "openQuestions", "open_questions"), 8, 260);
        long now = System.currentTimeMillis();
        out.createdAt = SilhouetteModelUtils.parseLong(map.get("createdAt"), now);
        out.updatedAt = SilhouetteModelUtils.parseLong(map.get("updatedAt"), out.createdAt);
        out.lastReinforcedAt = SilhouetteModelUtils.parseLong(map.get("lastReinforcedAt"), out.updatedAt);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", SilhouetteModelUtils.normalizeId(id, "mode", label));
        out.put("label", canonicalLabel(label));
        out.put("status", normalizeStatus(status));
        out.put("weight", SilhouetteModelUtils.clamp01(weight));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("selfExpression", conceptMaps(selfExpression));
        out.put("seekingExpression", conceptMaps(seekingExpression));
        out.put("sparkTriggers", conceptMaps(sparkTriggers));
        out.put("sustainabilityNeeds", conceptMaps(sustainabilityNeeds));
        out.put("aestheticField", conceptMaps(aestheticField));
        out.put("realWorldComps", conceptMaps(realWorldComps));
        out.put("antiPatterns", antiPatternMaps(antiPatterns));
        out.put("tensions", tensionMaps(tensions));
        out.put("evidence", evidenceMaps(evidence));
        out.put("openQuestions", SilhouetteModelUtils.stringList(openQuestions, 8, 260));
        out.put("createdAt", createdAt);
        out.put("updatedAt", updatedAt);
        out.put("lastReinforcedAt", lastReinforcedAt);
        return out;
    }

    public List<SilhouetteConcept> conceptsForTarget(String target) {
        return switch (SilhouetteEvidence.normalizeTarget(target)) {
            case "seeking_expression" -> seekingExpression;
            case "spark_triggers" -> sparkTriggers;
            case "sustainability_needs" -> sustainabilityNeeds;
            case "aesthetic_field" -> aestheticField;
            case "real_world_comps" -> realWorldComps;
            default -> selfExpression;
        };
    }

    public static String normalizeStatus(String raw) {
        return SilhouetteModelUtils.oneOf(raw, "emerging",
                "emerging", "active", "mature", "dormant", "deprecated");
    }

    public static String canonicalLabel(String raw) {
        String text = SilhouetteModelUtils.text(raw, 140);
        String normalized = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (LEGACY_FORMATIVE_SEED_AFFINITIES.equals(normalized)
                || "mode formative seed affinities".equals(normalized)) {
            return FORMATIVE_MEDIA_IMPRINTS;
        }
        return text;
    }

    private static List<SilhouetteConcept> conceptsFrom(Object primary, Object secondary) {
        Object raw = primary instanceof List<?> ? primary : secondary;
        ArrayList<SilhouetteConcept> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = SilhouetteModelUtils.objectMap(item);
                SilhouetteConcept parsed = SilhouetteConcept.fromMap(map);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static List<SilhouetteAntiPattern> antiPatternsFrom(Object primary, Object secondary) {
        Object raw = primary instanceof List<?> ? primary : secondary;
        ArrayList<SilhouetteAntiPattern> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = SilhouetteModelUtils.objectMap(item);
                SilhouetteAntiPattern parsed = SilhouetteAntiPattern.fromMap(map);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static List<SilhouetteTension> tensionsFrom(Object raw) {
        ArrayList<SilhouetteTension> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = SilhouetteModelUtils.objectMap(item);
                SilhouetteTension parsed = SilhouetteTension.fromMap(map);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static List<SilhouetteEvidence> evidenceFrom(Object raw) {
        ArrayList<SilhouetteEvidence> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = SilhouetteModelUtils.objectMap(item);
                SilhouetteEvidence parsed = SilhouetteEvidence.fromMap(map);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static List<SilhouetteConcept> copyConcepts(List<SilhouetteConcept> raw) {
        ArrayList<SilhouetteConcept> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteConcept item : raw) {
                if (item != null) {
                    out.add(new SilhouetteConcept(item));
                }
            }
        }
        return out;
    }

    private static List<SilhouetteAntiPattern> copyAntiPatterns(List<SilhouetteAntiPattern> raw) {
        ArrayList<SilhouetteAntiPattern> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteAntiPattern item : raw) {
                if (item != null) {
                    out.add(new SilhouetteAntiPattern(item));
                }
            }
        }
        return out;
    }

    private static List<SilhouetteTension> copyTensions(List<SilhouetteTension> raw) {
        ArrayList<SilhouetteTension> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteTension item : raw) {
                if (item != null) {
                    out.add(new SilhouetteTension(item));
                }
            }
        }
        return out;
    }

    private static List<SilhouetteEvidence> copyEvidence(List<SilhouetteEvidence> raw) {
        ArrayList<SilhouetteEvidence> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteEvidence item : raw) {
                if (item != null) {
                    out.add(new SilhouetteEvidence(item));
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> conceptMaps(List<SilhouetteConcept> raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteConcept item : raw) {
                if (item != null) {
                    out.add(item.toMap());
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> antiPatternMaps(List<SilhouetteAntiPattern> raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteAntiPattern item : raw) {
                if (item != null) {
                    out.add(item.toMap());
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> tensionMaps(List<SilhouetteTension> raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteTension item : raw) {
                if (item != null) {
                    out.add(item.toMap());
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> evidenceMaps(List<SilhouetteEvidence> raw) {
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        if (raw != null) {
            for (SilhouetteEvidence item : raw) {
                if (item != null) {
                    out.add(item.toMap());
                }
            }
        }
        return out;
    }
}
