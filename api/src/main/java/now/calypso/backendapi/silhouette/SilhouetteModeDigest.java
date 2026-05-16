package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SilhouetteModeDigest {
    public String id;
    public String label;
    public String status;
    public double weight;
    public double confidence;
    public List<String> self;
    public List<String> seeking;
    public List<String> sparkTriggers;
    public List<String> sustainabilityNeeds;
    public List<String> aestheticField;
    public List<String> realWorldComps;
    public List<String> antiPatterns;
    public List<String> tensions;
    public List<String> evidenceSummary;

    public SilhouetteModeDigest() {
        this.id = "";
        this.label = "";
        this.status = "emerging";
        this.weight = 0.0;
        this.confidence = 0.0;
        this.self = new ArrayList<>();
        this.seeking = new ArrayList<>();
        this.sparkTriggers = new ArrayList<>();
        this.sustainabilityNeeds = new ArrayList<>();
        this.aestheticField = new ArrayList<>();
        this.realWorldComps = new ArrayList<>();
        this.antiPatterns = new ArrayList<>();
        this.tensions = new ArrayList<>();
        this.evidenceSummary = new ArrayList<>();
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("label", label);
        out.put("status", status);
        out.put("weight", SilhouetteModelUtils.clamp01(weight));
        out.put("confidence", SilhouetteModelUtils.clamp01(confidence));
        out.put("self", safeList(self, 10, 160));
        out.put("seeking", safeList(seeking, 10, 160));
        out.put("sparkTriggers", safeList(sparkTriggers, 10, 160));
        out.put("sustainabilityNeeds", safeList(sustainabilityNeeds, 10, 160));
        out.put("aestheticField", safeList(aestheticField, 10, 160));
        out.put("realWorldComps", safeList(realWorldComps, 10, 160));
        out.put("antiPatterns", safeList(antiPatterns, 6, 160));
        out.put("tensions", safeList(tensions, 3, 140));
        out.put("evidenceSummary", safeList(evidenceSummary, 10, 220));
        return out;
    }

    static List<String> safeList(List<String> raw, int maxItems, int maxChars) {
        return SilhouetteModelUtils.stringList(raw, maxItems, maxChars);
    }
}
