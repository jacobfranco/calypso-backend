package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SilhouetteState {
    private static final int CLAIM_TEXT_MAX = 220;
    private static final int CLAIM_KIND_MAX = 48;
    private static final int CLAIM_SOURCE_MAX = 60;
    private static final int CLAIM_SOURCE_ID_MAX = 96;
    private static final int CLAIM_PROMPT_ID_MAX = 96;
    private static final int RERANKER_SUMMARY_MAX = 600;
    private static final int ADMIN_SUMMARY_MAX = 1600;

    public long accountId;
    public long version;
    public String maturity;
    public List<Claim> claims;
    public SummaryCache summaryCache;
    public long updatedAt;

    public SilhouetteState() {
        this.accountId = 0L;
        this.version = 1L;
        this.maturity = "empty";
        this.claims = new ArrayList<>();
        this.summaryCache = new SummaryCache();
        this.updatedAt = System.currentTimeMillis();
    }

    public SilhouetteState(SilhouetteState other) {
        this();
        if (other == null) {
            return;
        }
        this.accountId = other.accountId;
        this.version = Math.max(1L, other.version);
        this.maturity = normalizeMaturity(other.maturity);
        this.updatedAt = other.updatedAt;
        if (other.claims != null) {
            for (Claim claim : other.claims) {
                if (claim != null) {
                    this.claims.add(new Claim(claim));
                }
            }
        }
        this.summaryCache = other.summaryCache == null ? new SummaryCache() : new SummaryCache(other.summaryCache);
    }

    public static SilhouetteState empty(long accountId) {
        SilhouetteState out = new SilhouetteState();
        out.accountId = accountId;
        out.updatedAt = System.currentTimeMillis();
        return out;
    }

    @SuppressWarnings("unchecked")
    public static SilhouetteState fromMap(Map<String, Object> map, long fallbackAccountId) {
        SilhouetteState out = empty(fallbackAccountId);
        if (map == null || map.isEmpty()) {
            return out;
        }
        out.accountId = parseLong(map.get("accountId"), fallbackAccountId);
        out.version = Math.max(1L, parseLong(map.get("version"), 1L));
        out.maturity = normalizeMaturity(asTrimmed(map.get("maturity")));
        out.updatedAt = parseLong(map.get("updatedAt"), System.currentTimeMillis());

        Object rawClaims = map.get("claims");
        if (!(rawClaims instanceof List<?>)) {
            rawClaims = map.get("claimLedger");
        }
        if (rawClaims instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Claim claim = Claim.fromMap((Map<String, Object>) item);
                if (claim != null) {
                    out.claims.add(claim);
                }
            }
        }

        Object rawSummaryCache = map.get("summaryCache");
        if (!(rawSummaryCache instanceof Map<?, ?>)) {
            rawSummaryCache = map.get("summary_cache");
        }
        if (rawSummaryCache instanceof Map<?, ?> m) {
            out.summaryCache = SummaryCache.fromMap((Map<String, Object>) m);
        }
        return out;
    }

    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("version", Math.max(1L, version));
        out.put("maturity", normalizeMaturity(maturity));
        out.put("updatedAt", updatedAt > 0L ? updatedAt : System.currentTimeMillis());

        ArrayList<Map<String, Object>> serializedClaims = new ArrayList<>();
        if (claims != null) {
            for (Claim claim : claims) {
                if (claim != null) {
                    serializedClaims.add(claim.toMap());
                }
            }
        }
        out.put("claims", serializedClaims);
        out.put("summaryCache", summaryCache == null ? new SummaryCache().toMap() : summaryCache.toMap());
        return out;
    }

    public String digest(int maxChars) {
        int cap = Math.max(180, maxChars);
        StringBuilder buf = new StringBuilder(cap + 120);
        buf.append("maturity=").append(normalizeMaturity(maturity)).append('\n');

        String cachedSummary = summaryCache == null ? "" : clampText(summaryCache.rerankerShort, 520);
        if (!cachedSummary.isBlank()) {
            buf.append("summary: ").append(cachedSummary).append('\n');
            String digest = buf.toString().trim();
            if (digest.length() <= cap) {
                return digest;
            }
            return digest.substring(0, cap).trim();
        }

        appendClaimsDigest(buf, 6);
        String digest = buf.toString().trim();
        if (digest.length() <= cap) {
            return digest;
        }
        return digest.substring(0, cap).trim();
    }

    private void appendClaimsDigest(StringBuilder buf, int maxClaims) {
        if (buf == null || maxClaims <= 0 || claims == null || claims.isEmpty()) {
            return;
        }
        buf.append("claims:\n");
        int added = 0;
        for (Claim claim : claims) {
            if (claim == null || claim.text == null || claim.text.isBlank()) {
                continue;
            }
            String facet = claim.facet == null || claim.facet.isBlank() ? "general" : claim.facet;
            buf.append("- ")
                    .append(facet)
                    .append(" (")
                    .append(String.format(Locale.ROOT, "%.2f", clamp01(claim.confidence)))
                    .append("): ")
                    .append(claim.text)
                    .append('\n');
            added += 1;
            if (added >= maxClaims) {
                break;
            }
        }
    }

    public static String normalizeMaturity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "empty";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("mature".equals(normalized)) {
            return "mature";
        }
        if ("sparse".equals(normalized)) {
            return "sparse";
        }
        return "empty";
    }

    private static String asTrimmed(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String clampText(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen).trim();
    }

    private static long parseLong(Object raw, long fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double parseDouble(Object raw, double fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double clampSigned(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < -1.0) {
            return -1.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    public static final class Claim {
        public String id;
        public String facet;
        public String text;
        public String kind;
        public double polarity;
        public double confidence;
        public String source;
        public String sourceId;
        public String promptId;
        public long createdAt;

        public Claim() {
            this.id = "";
            this.facet = "";
            this.text = "";
            this.kind = "";
            this.polarity = 0.0;
            this.confidence = 0.0;
            this.source = "";
            this.sourceId = "";
            this.promptId = "";
            this.createdAt = System.currentTimeMillis();
        }

        public Claim(Claim other) {
            this();
            if (other == null) {
                return;
            }
            this.id = other.id;
            this.facet = other.facet;
            this.text = other.text;
            this.kind = other.kind;
            this.polarity = other.polarity;
            this.confidence = other.confidence;
            this.source = other.source;
            this.sourceId = other.sourceId;
            this.promptId = other.promptId;
            this.createdAt = other.createdAt;
        }

        static Claim fromMap(Map<String, Object> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }
            String text = clampText(asTrimmed(map.get("text")), CLAIM_TEXT_MAX);
            if (text.isBlank()) {
                return null;
            }
            String facet = normalizeKey(asTrimmed(map.get("facet")));
            String id = normalizeKey(asTrimmed(map.get("id")));
            if (id == null) {
                String semantic = (facet == null ? "general" : facet) + "|" + text.toLowerCase(Locale.ROOT);
                id = "cl_" + Long.toHexString(Math.abs(semantic.hashCode()));
            }
            Claim out = new Claim();
            out.id = id;
            out.facet = facet;
            out.text = text;
            out.kind = normalizeKey(asTrimmed(map.get("kind")));
            out.polarity = clampSigned(parseDouble(map.get("polarity"), 0.0));
            out.confidence = clamp01(parseDouble(map.get("confidence"), 0.0));
            out.source = clampText(asTrimmed(map.get("source")), CLAIM_SOURCE_MAX);
            out.sourceId = clampText(asTrimmed(map.get("sourceId")), CLAIM_SOURCE_ID_MAX);
            out.promptId = clampText(asTrimmed(map.get("promptId")), CLAIM_PROMPT_ID_MAX);
            out.createdAt = parseLong(map.get("createdAt"), System.currentTimeMillis());
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("id", normalizeKey(id));
            out.put("facet", normalizeKey(facet));
            out.put("text", clampText(text, CLAIM_TEXT_MAX));
            out.put("kind", clampText(kind, CLAIM_KIND_MAX));
            out.put("polarity", clampSigned(polarity));
            out.put("confidence", clamp01(confidence));
            out.put("source", clampText(source, CLAIM_SOURCE_MAX));
            out.put("sourceId", clampText(sourceId, CLAIM_SOURCE_ID_MAX));
            out.put("promptId", clampText(promptId, CLAIM_PROMPT_ID_MAX));
            out.put("createdAt", createdAt);
            return out;
        }
    }

    public static final class SummaryCache {
        public String rerankerShort;
        public String adminLong;
        public long generatedFromVersion;
        public long updatedAt;

        public SummaryCache() {
            this.rerankerShort = "";
            this.adminLong = "";
            this.generatedFromVersion = 0L;
            this.updatedAt = 0L;
        }

        public SummaryCache(SummaryCache other) {
            this();
            if (other == null) {
                return;
            }
            this.rerankerShort = other.rerankerShort;
            this.adminLong = other.adminLong;
            this.generatedFromVersion = other.generatedFromVersion;
            this.updatedAt = other.updatedAt;
        }

        static SummaryCache fromMap(Map<String, Object> map) {
            SummaryCache out = new SummaryCache();
            if (map == null || map.isEmpty()) {
                return out;
            }
            out.rerankerShort = clampText(asTrimmed(map.get("rerankerShort")), RERANKER_SUMMARY_MAX);
            out.adminLong = clampText(asTrimmed(map.get("adminLong")), ADMIN_SUMMARY_MAX);
            out.generatedFromVersion = Math.max(0L, parseLong(map.get("generatedFromVersion"), 0L));
            out.updatedAt = Math.max(0L, parseLong(map.get("updatedAt"), 0L));
            return out;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rerankerShort", clampText(rerankerShort, RERANKER_SUMMARY_MAX));
            out.put("adminLong", clampText(adminLong, ADMIN_SUMMARY_MAX));
            out.put("generatedFromVersion", Math.max(0L, generatedFromVersion));
            out.put("updatedAt", Math.max(0L, updatedAt));
            return out;
        }
    }

    public static String normalizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                out.append(c);
            } else if (c == ' ') {
                out.append('_');
            }
        }
        String normalized = out.toString().replaceAll("_+", "_");
        return normalized.isBlank() ? null : normalized;
    }
}
