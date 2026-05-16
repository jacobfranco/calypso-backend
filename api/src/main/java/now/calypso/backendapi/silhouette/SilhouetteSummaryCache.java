package now.calypso.backendapi.silhouette;

import java.util.LinkedHashMap;
import java.util.Map;

public class SilhouetteSummaryCache {
    private static final int SILHOUETTE_SUMMARY_MAX = 2800;

    public String silhouette;
    public long generatedFromVersion;
    public long updatedAt;

    public SilhouetteSummaryCache() {
        this.silhouette = "";
        this.generatedFromVersion = 1L;
        this.updatedAt = 0L;
    }

    public SilhouetteSummaryCache(SilhouetteSummaryCache other) {
        this();
        if (other == null) {
            return;
        }
        this.silhouette = other.silhouette;
        this.generatedFromVersion = other.generatedFromVersion;
        this.updatedAt = other.updatedAt;
    }

    public static SilhouetteSummaryCache fromMap(Map<String, Object> map) {
        SilhouetteSummaryCache out = new SilhouetteSummaryCache();
        if (map == null || map.isEmpty()) {
            return out;
        }
        out.silhouette = SilhouetteModelUtils.text(map.get("silhouette"), SILHOUETTE_SUMMARY_MAX);
        if (out.silhouette.isBlank()) {
            out.silhouette = SilhouetteModelUtils.text(map.get("rerankerShort"), SILHOUETTE_SUMMARY_MAX);
        }
        if (out.silhouette.isBlank()) {
            out.silhouette = SilhouetteModelUtils.text(map.get("adminLong"), SILHOUETTE_SUMMARY_MAX);
        }
        out.generatedFromVersion = Math.max(1L,
                SilhouetteModelUtils.parseLong(map.get("generatedFromVersion"), out.generatedFromVersion));
        out.updatedAt = Math.max(0L, SilhouetteModelUtils.parseLong(map.get("updatedAt"), out.updatedAt));
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("silhouette", SilhouetteModelUtils.text(silhouette, SILHOUETTE_SUMMARY_MAX));
        out.put("generatedFromVersion", Math.max(1L, generatedFromVersion));
        out.put("updatedAt", Math.max(0L, updatedAt));
        return out;
    }
}
