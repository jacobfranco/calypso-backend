package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SilhouetteState {
    private static final int RERANKER_DIGEST_MAX = 1600;

    public long accountId;
    public int version;
    public String maturity;
    public long updatedAt;
    public List<SilhouetteMode> modes;
    public SilhouetteSummaryCache summaryCache;

    public SilhouetteState() {
        this.accountId = 0L;
        this.version = 1;
        this.maturity = "empty";
        this.updatedAt = System.currentTimeMillis();
        this.modes = new ArrayList<>();
        this.summaryCache = new SilhouetteSummaryCache();
    }

    public SilhouetteState(SilhouetteState other) {
        this();
        if (other == null) {
            return;
        }
        this.accountId = other.accountId;
        this.version = Math.max(1, other.version);
        this.maturity = normalizeMaturity(other.maturity);
        this.updatedAt = other.updatedAt;
        this.modes = new ArrayList<>();
        if (other.modes != null) {
            for (SilhouetteMode mode : other.modes) {
                if (mode != null) {
                    this.modes.add(new SilhouetteMode(mode));
                }
            }
        }
        this.summaryCache = other.summaryCache == null
                ? new SilhouetteSummaryCache()
                : new SilhouetteSummaryCache(other.summaryCache);
    }

    public static SilhouetteState empty(long accountId) {
        SilhouetteState out = new SilhouetteState();
        out.accountId = accountId;
        out.updatedAt = System.currentTimeMillis();
        out.summaryCache.generatedFromVersion = 1L;
        return out;
    }

    @SuppressWarnings("unchecked")
    public static SilhouetteState fromMap(Map<String, Object> map, long fallbackAccountId) {
        SilhouetteState out = empty(fallbackAccountId);
        if (map == null || map.isEmpty()) {
            return out;
        }
        out.accountId = SilhouetteModelUtils.parseLong(map.get("accountId"), fallbackAccountId);
        out.version = Math.max(1, SilhouetteModelUtils.parseInt(map.get("version"), 1));
        out.maturity = normalizeMaturity(SilhouetteModelUtils.text(map.get("maturity"), 32));
        out.updatedAt = SilhouetteModelUtils.parseLong(map.get("updatedAt"), System.currentTimeMillis());

        Object rawModes = map.get("modes");
        if (rawModes instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> modeMap = SilhouetteModelUtils.objectMap(item);
                SilhouetteMode mode = SilhouetteMode.fromMap(modeMap);
                if (mode != null) {
                    out.modes.add(mode);
                }
            }
        }

        Object rawSummaryCache = map.get("summaryCache");
        if (!(rawSummaryCache instanceof Map<?, ?>)) {
            rawSummaryCache = map.get("summary_cache");
        }
        if (rawSummaryCache instanceof Map<?, ?> m) {
            out.summaryCache = SilhouetteSummaryCache.fromMap((Map<String, Object>) m);
        }
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("version", Math.max(1, version));
        out.put("maturity", normalizeMaturity(maturity));
        out.put("updatedAt", updatedAt > 0L ? updatedAt : System.currentTimeMillis());
        ArrayList<Map<String, Object>> serializedModes = new ArrayList<>();
        if (modes != null) {
            for (SilhouetteMode mode : modes) {
                if (mode != null) {
                    serializedModes.add(mode.toMap());
                }
            }
        }
        out.put("modes", serializedModes);
        out.put("summaryCache", summaryCache == null ? new SilhouetteSummaryCache().toMap() : summaryCache.toMap());
        return out;
    }

    public String digest(int maxChars) {
        int cap = Math.max(240, maxChars);
        String cachedSummary = summaryCache == null ? "" : SilhouetteModelUtils.text(summaryCache.rerankerShort,
                RERANKER_DIGEST_MAX);
        if (!cachedSummary.isBlank()) {
            return clampDigest("maturity=" + normalizeMaturity(maturity) + "\nsummary: " + cachedSummary, cap);
        }
        SilhouetteDigest digest = SilhouetteDigest.fromState(this);
        StringBuilder buf = new StringBuilder();
        buf.append("maturity=").append(normalizeMaturity(maturity)).append('\n');
        if (digest.topModes != null) {
            for (SilhouetteModeDigest mode : digest.topModes) {
                if (mode == null) {
                    continue;
                }
                buf.append("mode: ").append(mode.label == null ? mode.id : mode.label)
                        .append(" w=").append(String.format(Locale.ROOT, "%.2f", SilhouetteModelUtils.clamp01(mode.weight)))
                        .append(" c=").append(String.format(Locale.ROOT, "%.2f", SilhouetteModelUtils.clamp01(mode.confidence)))
                        .append('\n');
                appendDigestList(buf, "self", mode.self);
                appendDigestList(buf, "seeking", mode.seeking);
                appendDigestList(buf, "spark", mode.sparkTriggers);
                appendDigestList(buf, "sustain", mode.sustainabilityNeeds);
            }
        }
        return clampDigest(buf.toString().trim(), cap);
    }

    public static String normalizeMaturity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "empty";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mature" -> "mature";
            case "emerging" -> "emerging";
            case "sparse" -> "sparse";
            default -> "empty";
        };
    }

    public static String normalizeKey(String raw) {
        return SilhouetteModelUtils.normalizeKey(raw);
    }

    private static void appendDigestList(StringBuilder buf, String label, List<String> values) {
        if (buf == null || values == null || values.isEmpty()) {
            return;
        }
        ArrayList<String> kept = new ArrayList<>();
        for (String value : values) {
            String text = SilhouetteModelUtils.text(value, 80);
            if (!text.isBlank()) {
                kept.add(text);
            }
            if (kept.size() >= 4) {
                break;
            }
        }
        if (!kept.isEmpty()) {
            buf.append("  ").append(label).append(": ").append(String.join(", ", kept)).append('\n');
        }
    }

    private static String clampDigest(String raw, int cap) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= cap) {
            return trimmed;
        }
        return trimmed.substring(0, cap).trim();
    }
}
