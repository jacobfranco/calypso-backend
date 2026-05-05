package now.calypso.backendapi.silhouette;

import java.util.LinkedHashMap;
import java.util.Map;

public class SilhouetteSummaryCache {
    private static final int RERANKER_SUMMARY_MAX = 1600;
    private static final int ADMIN_SUMMARY_MAX = 2400;

    public String rerankerShort;
    public String adminLong;
    public long generatedFromVersion;
    public long updatedAt;

    public SilhouetteSummaryCache() {
        this.rerankerShort = "";
        this.adminLong = "";
        this.generatedFromVersion = 1L;
        this.updatedAt = 0L;
    }

    public SilhouetteSummaryCache(SilhouetteSummaryCache other) {
        this();
        if (other == null) {
            return;
        }
        this.rerankerShort = other.rerankerShort;
        this.adminLong = other.adminLong;
        this.generatedFromVersion = other.generatedFromVersion;
        this.updatedAt = other.updatedAt;
    }

    public static SilhouetteSummaryCache fromMap(Map<String, Object> map) {
        SilhouetteSummaryCache out = new SilhouetteSummaryCache();
        if (map == null || map.isEmpty()) {
            return out;
        }
        out.rerankerShort = SilhouetteModelUtils.text(map.get("rerankerShort"), RERANKER_SUMMARY_MAX);
        out.adminLong = SilhouetteModelUtils.text(map.get("adminLong"), ADMIN_SUMMARY_MAX);
        out.generatedFromVersion = Math.max(1L,
                SilhouetteModelUtils.parseLong(map.get("generatedFromVersion"), out.generatedFromVersion));
        out.updatedAt = Math.max(0L, SilhouetteModelUtils.parseLong(map.get("updatedAt"), out.updatedAt));
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rerankerShort", SilhouetteModelUtils.text(rerankerShort, RERANKER_SUMMARY_MAX));
        out.put("adminLong", SilhouetteModelUtils.text(adminLong, ADMIN_SUMMARY_MAX));
        out.put("generatedFromVersion", Math.max(1L, generatedFromVersion));
        out.put("updatedAt", Math.max(0L, updatedAt));
        return out;
    }
}
