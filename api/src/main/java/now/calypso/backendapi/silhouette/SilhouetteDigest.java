package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteDigest {
    public long accountId;
    public String maturity;
    public List<SilhouetteModeDigest> topModes;
    public List<String> openQuestions;

    public SilhouetteDigest() {
        this.accountId = 0L;
        this.maturity = "empty";
        this.topModes = new ArrayList<>();
        this.openQuestions = new ArrayList<>();
    }

    public static SilhouetteDigest fromState(SilhouetteState state) {
        SilhouetteDigest out = new SilhouetteDigest();
        if (state == null) {
            return out;
        }
        out.accountId = state.accountId;
        out.maturity = SilhouetteState.normalizeMaturity(state.maturity);
        List<SilhouetteMode> modes = state.modes == null ? List.of() : state.modes;
        ArrayList<SilhouetteMode> active = new ArrayList<>();
        for (SilhouetteMode mode : modes) {
            if (mode == null) {
                continue;
            }
            String status = SilhouetteMode.normalizeStatus(mode.status);
            if ("deprecated".equals(status) || "dormant".equals(status)) {
                continue;
            }
            active.add(mode);
        }
        active.sort((a, b) -> {
            double aScore = SilhouetteModelUtils.clamp01(a.weight) * SilhouetteModelUtils.clamp01(a.confidence);
            double bScore = SilhouetteModelUtils.clamp01(b.weight) * SilhouetteModelUtils.clamp01(b.confidence);
            int byScore = Double.compare(bScore, aScore);
            if (byScore != 0) {
                return byScore;
            }
            return Long.compare(b.lastReinforcedAt, a.lastReinforcedAt);
        });
        int modeCount = Math.min(3, active.size());
        for (int i = 0; i < modeCount; i++) {
            out.topModes.add(modeDigest(active.get(i)));
        }
        out.openQuestions = collectOpenQuestions(active);
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("maturity", SilhouetteState.normalizeMaturity(maturity));
        ArrayList<Map<String, Object>> modes = new ArrayList<>();
        if (topModes != null) {
            for (SilhouetteModeDigest mode : topModes) {
                if (mode != null) {
                    modes.add(mode.toMap());
                }
            }
        }
        out.put("topModes", modes);
        out.put("openQuestions", SilhouetteModelUtils.stringList(openQuestions, 5, 180));
        return out;
    }

    private static SilhouetteModeDigest modeDigest(SilhouetteMode mode) {
        SilhouetteModeDigest out = new SilhouetteModeDigest();
        out.id = mode.id;
        out.label = mode.label;
        out.status = SilhouetteMode.normalizeStatus(mode.status);
        out.weight = SilhouetteModelUtils.clamp01(mode.weight);
        out.confidence = SilhouetteModelUtils.clamp01(mode.confidence);
        out.self = conceptLabels(mode.selfExpression);
        out.seeking = conceptLabels(mode.seekingExpression);
        out.sparkTriggers = conceptLabels(mode.sparkTriggers);
        out.sustainabilityNeeds = conceptLabels(mode.sustainabilityNeeds);
        out.aestheticField = conceptLabels(mode.aestheticField);
        out.antiPatterns = antiPatternLabels(mode.antiPatterns);
        out.tensions = tensionLabels(mode.tensions);
        out.evidenceSummary = evidenceSummaries(mode.evidence);
        return out;
    }

    private static List<String> conceptLabels(List<SilhouetteConcept> concepts) {
        ArrayList<SilhouetteConcept> ranked = new ArrayList<>();
        if (concepts != null) {
            for (SilhouetteConcept concept : concepts) {
                if (concept != null && concept.label != null && !concept.label.isBlank()) {
                    ranked.add(concept);
                }
            }
        }
        ranked.sort((a, b) -> {
            int byRole = Integer.compare(SilhouetteModeMerger.rolePriority(a.role), SilhouetteModeMerger.rolePriority(b.role));
            if (byRole != 0) {
                return byRole;
            }
            int byStrength = Double.compare(SilhouetteModelUtils.clamp01(b.strength),
                    SilhouetteModelUtils.clamp01(a.strength));
            if (byStrength != 0) {
                return byStrength;
            }
            return Double.compare(SilhouetteModelUtils.clamp01(b.confidence),
                    SilhouetteModelUtils.clamp01(a.confidence));
        });
        ArrayList<String> out = new ArrayList<>();
        for (SilhouetteConcept concept : ranked) {
            out.add(SilhouetteModelUtils.text(concept.label, 120));
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private static List<String> antiPatternLabels(List<SilhouetteAntiPattern> antiPatterns) {
        ArrayList<SilhouetteAntiPattern> ranked = new ArrayList<>();
        if (antiPatterns != null) {
            for (SilhouetteAntiPattern anti : antiPatterns) {
                if (anti != null && anti.label != null && !anti.label.isBlank()) {
                    ranked.add(anti);
                }
            }
        }
        ranked.sort((a, b) -> Double.compare(SilhouetteModelUtils.clamp01(b.confidence),
                SilhouetteModelUtils.clamp01(a.confidence)));
        ArrayList<String> out = new ArrayList<>();
        for (SilhouetteAntiPattern anti : ranked) {
            out.add(SilhouetteModelUtils.text(anti.label, 120));
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    private static List<String> tensionLabels(List<SilhouetteTension> tensions) {
        ArrayList<SilhouetteTension> ranked = new ArrayList<>();
        if (tensions != null) {
            for (SilhouetteTension tension : tensions) {
                if (tension != null && tension.a != null && !tension.a.isBlank()
                        && tension.b != null && !tension.b.isBlank()) {
                    ranked.add(tension);
                }
            }
        }
        ranked.sort(Comparator.comparingDouble((SilhouetteTension t) -> SilhouetteModelUtils.clamp01(t.confidence))
                .reversed());
        ArrayList<String> out = new ArrayList<>();
        for (SilhouetteTension tension : ranked) {
            String label = SilhouetteModelUtils.text(tension.a + " vs " + tension.b, 140);
            if (!label.isBlank()) {
                out.add(label);
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static List<String> evidenceSummaries(List<SilhouetteEvidence> evidence) {
        ArrayList<SilhouetteEvidence> ranked = new ArrayList<>();
        if (evidence != null) {
            for (SilhouetteEvidence item : evidence) {
                if (item != null && item.value != null && !item.value.isBlank()) {
                    ranked.add(item);
                }
            }
        }
        ranked.sort((a, b) -> {
            int byCreated = Long.compare(b.createdAt, a.createdAt);
            if (byCreated != 0) {
                return byCreated;
            }
            return Double.compare(b.strength * b.confidence * b.sourceWeight, a.strength * a.confidence * a.sourceWeight);
        });
        ArrayList<String> out = new ArrayList<>();
        for (SilhouetteEvidence item : ranked) {
            String source = SilhouetteEvidence.normalizeSource(item.source);
            String value = SilhouetteModelUtils.text(item.value, 120);
            if (!value.isBlank()) {
                out.add(source + ": " + value);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return out;
    }

    private static List<String> collectOpenQuestions(List<SilhouetteMode> modes) {
        ArrayList<String> out = new ArrayList<>();
        if (modes != null) {
            for (SilhouetteMode mode : modes) {
                if (mode == null || mode.openQuestions == null) {
                    continue;
                }
                for (String question : mode.openQuestions) {
                    String text = SilhouetteModelUtils.text(question, 180);
                    if (!text.isBlank() && !containsIgnoreCase(out, text)) {
                        out.add(text);
                    }
                    if (out.size() >= 5) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (values == null || candidate == null) {
            return false;
        }
        String normalized = candidate.trim().toLowerCase(java.util.Locale.ROOT);
        for (String value : values) {
            if (value != null && value.trim().toLowerCase(java.util.Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
