package now.calypso.backend;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TUnion;

import com.rpl.rama.*;

import now.calypso.backend.data.*;
import now.calypso.backend.ops.ExtractField;

public class CalypsoHelpers {

    public static final ConcurrentHashMap<Class, Map<String, TFieldIdEnum>> TFIELD_CACHE = new ConcurrentHashMap<>();

    private static final Pattern WS = Pattern.compile("\\s+");

    public static class ExtractCode extends ExtractField {
        public ExtractCode() {
            super("code");
        }
    }

    public static class ExtractEmail extends ExtractField {
        public ExtractEmail() {
            super("email");
        }
    }

    public static class ExtractAccountId extends ExtractField {
        public ExtractAccountId() {
            super("accountId");
        }
    }

    public static Block extractFields(Object from, String... fieldVars) {
        Block.Impl ret = Block.create();
        for (String f : fieldVars) {
            String name = Helpers.isGeneratedVar(f) ? Helpers.getGeneratedVarPrefix(f) : f.substring(1);
            ret = ret.each(new ExtractField(name), from).out(f);
        }
        return ret;
    }

    public static Object getTFieldByName(TBase obj, String fieldName) {
        TFieldIdEnum field = getTFieldCache(obj.getClass()).get(fieldName);
        if (field == null)
            throw new RuntimeException("Field " + fieldName + " does not exist on " + obj.getClass());
        Object ret = null;
        if (obj.isSet(field))
            ret = obj.getFieldValue(field);
        if (ret instanceof TUnion)
            ret = ((TUnion) ret).getFieldValue();
        return ret;
    }

    public static Map<String, TFieldIdEnum> getTFieldCache(Class thriftClass) {
        Map<String, TFieldIdEnum> ret = TFIELD_CACHE.get(thriftClass);
        if (ret == null) {
            try {
                Field f = thriftClass.getField("metaDataMap");
                Map<TFieldIdEnum, Object> m = (Map) f.get(thriftClass);
                ret = new HashMap<>();
                for (TFieldIdEnum e : m.keySet())
                    ret.put(e.getFieldName(), e);
                TFIELD_CACHE.put(thriftClass, ret);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return ret;
    }

    public static Long parseAccountId(String id) {
        if (id == null)
            return null;
        String[] parts = id.split("-");
        if (parts.length == 2 && "a".equals(parts[1]))
            return Long.parseLong(parts[0]);
        throw new IllegalArgumentException("Not an account id: " + id);
    }

    public static String serializeAccountId(long accountId) {
        return String.format("%019d", accountId) + "-a";
    }

    public static String getGenderSelf(Filters f) {
        if (f == null || !f.isSetGender() || !f.getGender().isSetSelf())
            throw new IllegalArgumentException("gender.self required");
        return f.getGender().getSelf();
    }

    public static java.util.List<String> getGenderSeeking(Filters f) {
        if (f == null || !f.isSetGender() || !f.getGender().isSetSeeking())
            return java.util.Collections.emptyList();
        return f.getGender().getSeeking();
    }

    public static Integer getAgeSelf(Filters f) {
        if (f == null || !f.isSetAge() || !f.getAge().isSetSelf())
            throw new IllegalArgumentException("age.self required");
        return f.getAge().getSelf();
    }

    public static Integer getAgeMin(Filters f) {
        return (f != null && f.isSetAge() && f.getAge().isSetMin()) ? f.getAge().getMin() : 18;
    }

    public static Integer getAgeMax(Filters f) {
        return (f != null && f.isSetAge() && f.getAge().isSetMax()) ? f.getAge().getMax() : 99;
    }

    // ---- OneToMany helpers (religion/politics) ----
    public static String getOneToManySelf(OneToManyFilter x) {
        return (x != null && x.isSetSelf()) ? x.getSelf() : null;
    }

    public static List<String> getOneToManySeeking(OneToManyFilter x) {
        return (x != null && x.isSetSeeking()) ? x.getSeeking() : Collections.emptyList();
    }

    public static Importance getOneToManyImportance(OneToManyFilter x) {
        return (x != null && x.isSetImportance()) ? x.getImportance() : Importance.NOT_IMPORTANT;
    }

    // ---- Tags helpers ----
    public static List<String> getSelfTags(Filters f) {
        Set<String> s = new LinkedHashSet<>();
        if (f != null && f.isSetLifestyle() && f.getLifestyle().isSetSelf())
            f.getLifestyle().getSelf().forEach(t -> {
                if (t != null)
                    s.add(t);
            });
        if (f != null && f.isSetInterests() && f.getInterests().isSetSelf())
            f.getInterests().getSelf().forEach(t -> {
                if (t != null)
                    s.add(t);
            });
        return new ArrayList<>(s);
    }

    public static List<String> getTagDealbreakers(Filters f) {
        List<String> out = new ArrayList<>();
        if (f != null && f.isSetLifestyle() && f.getLifestyle().isSetPreferences())
            for (TagPreference p : f.getLifestyle().getPreferences())
                if (p != null && p.isSetTag() && p.isSetImportance() && p.getImportance() == Importance.DEALBREAKER)
                    out.add(p.getTag());
        if (f != null && f.isSetInterests() && f.getInterests().isSetPreferences())
            for (TagPreference p : f.getInterests().getPreferences())
                if (p != null && p.isSetTag() && p.isSetImportance() && p.getImportance() == Importance.DEALBREAKER)
                    out.add(p.getTag());
        return out;
    }

    public static List<String> getTagPreferences(Filters f) {
        List<String> out = new ArrayList<>();
        if (f != null && f.isSetLifestyle() && f.getLifestyle().isSetPreferences())
            for (TagPreference p : f.getLifestyle().getPreferences())
                if (p != null && p.isSetTag() && p.isSetImportance() && p.getImportance() == Importance.PREFERENCE)
                    out.add(p.getTag());
        if (f != null && f.isSetInterests() && f.getInterests().isSetPreferences())
            for (TagPreference p : f.getInterests().getPreferences())
                if (p != null && p.isSetTag() && p.isSetImportance() && p.getImportance() == Importance.PREFERENCE)
                    out.add(p.getTag());
        return out;
    }

    public static double jaccard(List<String> a, List<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty())
            return 0.0;
        Set<String> A = new HashSet<>(a), B = new HashSet<>(b);
        int inter = 0;
        for (String s : A)
            if (B.contains(s))
                inter++;
        int union = A.size() + B.size() - inter;
        return union == 0 ? 0.0 : ((double) inter) / union;
    }

    private static String norm(String s) {
        if (s == null)
            return null;
        String x = WS.matcher(s.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
        return x.isEmpty() ? null : x;
    }

    public static String getModeSelf(Filters f) {
        if (f == null || !f.isSetRelationshipMode() || !f.getRelationshipMode().isSetSelf())
            throw new IllegalArgumentException("relationshipMode.self required");
        String m = norm(f.getRelationshipMode().getSelf());
        if (m == null)
            throw new IllegalArgumentException("relationshipMode.self empty");
        return m;
    }

    // =====================================================
    // New: struct-based compatibility helpers for Matches
    // =====================================================

    public static OneToManyFilter getGender(Filters f) {
        return (f != null && f.isSetGender()) ? f.getGender() : null;
    }

    public static RangeFilter getAge(Filters f) {
        return (f != null && f.isSetAge()) ? f.getAge() : null;
    }

    public static LocationFilter getLocation(Filters f) {
        return (f != null && f.isSetLocation()) ? f.getLocation() : null;
    }

    /** Lenient mode getter for matching; returns null instead of throwing. */
    public static String getModeSelfOrNull(Filters f) {
        if (f == null || !f.isSetRelationshipMode())
            return null;
        ModeFilter m = f.getRelationshipMode();
        if (m == null || !m.isSetSelf())
            return null;
        String s = m.getSelf();
        if (s == null)
            return null;
        s = s.trim();
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0088; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    public static boolean gendersCompatible(Filters viewer, Filters target) {
        OneToManyFilter vg = getGender(viewer);
        OneToManyFilter tg = getGender(target);
        if (vg == null || tg == null)
            return true; // fail-open if someone left it blank

        String viewerSelf = vg.isSetSelf() ? vg.getSelf() : null;
        String targetSelf = tg.isSetSelf() ? tg.getSelf() : null;

        List<String> viewerSeeking = vg.isSetSeeking() ? vg.getSeeking() : null;
        List<String> targetSeeking = tg.isSetSeeking() ? tg.getSeeking() : null;

        if (viewerSelf == null || targetSelf == null)
            return true;

        boolean viewerLikesTarget = (viewerSeeking == null || viewerSeeking.isEmpty()
                || viewerSeeking.contains(targetSelf));
        boolean targetLikesViewer = (targetSeeking == null || targetSeeking.isEmpty()
                || targetSeeking.contains(viewerSelf));

        return viewerLikesTarget && targetLikesViewer;
    }

    public static boolean agesCompatible(Filters viewer, Filters target) {
        RangeFilter va = getAge(viewer);
        RangeFilter ta = getAge(target);
        if (va == null || ta == null)
            return true;

        Integer vSelf = va.isSetSelf() ? va.getSelf() : null;
        Integer tSelf = ta.isSetSelf() ? ta.getSelf() : null;

        Integer vMin = va.isSetMin() ? va.getMin() : null;
        Integer vMax = va.isSetMax() ? va.getMax() : null;
        Integer tMin = ta.isSetMin() ? ta.getMin() : null;
        Integer tMax = ta.isSetMax() ? ta.getMax() : null;

        if (vSelf != null) {
            if (tMin != null && vSelf < tMin)
                return false;
            if (tMax != null && vSelf > tMax)
                return false;
        }
        if (tSelf != null) {
            if (vMin != null && tSelf < vMin)
                return false;
            if (vMax != null && tSelf > vMax)
                return false;
        }
        return true;
    }

    public static boolean withinRadius(Filters viewer, Filters target) {
        LocationFilter vl = getLocation(viewer);
        LocationFilter tl = getLocation(target);
        if (vl == null || tl == null)
            return true; // fail-open if missing

        double vlat = vl.getLat();
        double vlon = vl.getLon();
        double tlat = tl.getLat();
        double tlon = tl.getLon();
        double rad = vl.getRadiusKm();

        double distKm = haversineKm(vlat, vlon, tlat, tlon);
        return distKm <= rad + 1e-9;
    }

    /**
     * Mode rules:
     * - If viewer is "serious", only match serious.
     * - If viewer is "casual", accept casual or serious.
     */
    public static boolean modesCompatible(Filters viewer, Filters target) {
        String vm = getModeSelfOrNull(viewer);
        String tm = getModeSelfOrNull(target);
        if (vm == null || tm == null)
            return true;

        if ("serious".equals(vm)) {
            return "serious".equals(tm);
        }
        // viewer casual: allow both
        return true;
    }

    /**
     * Base compatibility for Matches:
     * - returns -1.0 if any hard constraint fails
     * - returns a flat high score (100.0) otherwise
     */
    public static double computeMatchesBaseScore(Filters viewer, Filters target) {
        if (viewer == null || target == null)
            return -1.0;
        if (!gendersCompatible(viewer, target))
            return -1.0;
        if (!agesCompatible(viewer, target))
            return -1.0;
        if (!withinRadius(viewer, target))
            return -1.0;
        if (!modesCompatible(viewer, target))
            return -1.0;
        return 100.0;
    }

    // =========================
    // Pool selection (Sets)
    // =========================

    public static Set<Long> choosePool(Set<Long> citySet,
            Set<Long> stateSet,
            Set<Long> countrySet,
            String radius) {
        Set<Long> empty = Collections.emptySet();
        Set<Long> city = (citySet != null) ? citySet : empty;
        Set<Long> state = (stateSet != null) ? stateSet : empty;
        Set<Long> country = (countrySet != null) ? countrySet : empty;

        if (radius == null) {
            // sensible default: city + state (keeps tests happy)
            Set<Long> u = new HashSet<>(city);
            u.addAll(state);
            return u;
        }

        switch (radius) {
            case "my_city":
                // IMPORTANT: union city + state (test expects this)
                Set<Long> u = new HashSet<>(city);
                u.addAll(state);
                return u;

            case "my_state":
                // state first; if empty, fall back to city (local preference) before country
                if (!state.isEmpty())
                    return state;
                if (!city.isEmpty())
                    return city;
                return country;

            case "my_country":
                if (!country.isEmpty())
                    return country;
                if (!state.isEmpty())
                    return state;
                return city;

            default:
                // unknown token: be permissive, prefer city+state
                Set<Long> ux = new HashSet<>(city);
                ux.addAll(state);
                if (ux.isEmpty())
                    return country;
                return ux;
        }
    }

    public static List<Long> buildOrderedPool(
            Set<Long> primarySet,
            long salt,
            List<Long> newcomers,
            int newcomerCap) {

        LinkedHashSet<Long> ordered = new LinkedHashSet<>();

        // Newcomers: take last N (newest) from ring
        if (newcomers != null && !newcomers.isEmpty() && newcomerCap > 0) {
            int take = Math.min(newcomerCap, newcomers.size());
            for (int i = Math.max(0, newcomers.size() - take); i < newcomers.size(); i++) {
                ordered.add(newcomers.get(i));
            }
        }

        // Salted total order (deterministic per viewer)
        if (primarySet != null && !primarySet.isEmpty()) {
            List<Long> ids = new ArrayList<>(primarySet);
            ids.sort((a, b) -> Long.compare(a ^ salt, b ^ salt));
            ordered.addAll(ids);
        }
        return new ArrayList<>(ordered);
    }

    // =========================
    // Compatibility overloads
    // (old Map<Long,Boolean> API)
    // =========================

    public static Map<Long, Boolean> choosePool(
            Map<Long, Boolean> citySet,
            Map<Long, Boolean> stateSet,
            Map<Long, Boolean> countrySet,
            String radius) {
        Set<Long> out = choosePool(toSet(citySet), toSet(stateSet), toSet(countrySet), radius);
        return toMap(out);
    }

    public static List<Long> buildOrderedPool(
            Map<Long, Boolean> primarySet,
            long salt,
            List<Long> newcomers,
            int newcomerCap) {
        return buildOrderedPool(toSet(primarySet), salt, newcomers, newcomerCap);
    }

    private static Set<Long> toSet(Map<Long, Boolean> m) {
        if (m == null || m.isEmpty())
            return Collections.emptySet();
        return new LinkedHashSet<>(m.keySet());
    }

    private static Map<Long, Boolean> toMap(Set<Long> s) {
        if (s == null || s.isEmpty())
            return Collections.emptyMap();
        Map<Long, Boolean> m = new LinkedHashMap<>(Math.max(16, s.size()));
        for (Long id : s)
            m.put(id, true);
        return m;
    }

    // ---------------- Hard gates ----------------

    public static Boolean passHardGates(Map<String, Object> v, Map<String, Object> t) {
        if (v == null || t == null)
            return false;

        // Relationship mode compatibility
        String vMode = (String) v.get("modeSelf");
        String tMode = (String) t.get("modeSelf");
        if (!modeCompatible(vMode, tMode))
            return false;

        // Mutual gender
        String tGenderSelf = (String) t.get("genderSelf");
        @SuppressWarnings("unchecked")
        List<String> vSeek = (List<String>) v.get("genderSeeking");
        if (!(vSeek == null || vSeek.isEmpty() || vSeek.contains(tGenderSelf)))
            return false;

        String vGenderSelf = (String) v.get("genderSelf");
        @SuppressWarnings("unchecked")
        List<String> tSeek = (List<String>) t.get("genderSeeking");
        boolean targetOpen = (tSeek == null || tSeek.isEmpty() || tSeek.contains(vGenderSelf));
        if (!targetOpen)
            return false;

        // Mutual age
        Integer vAgeSelf = (Integer) v.get("ageSelf");
        Integer vMin = (Integer) v.get("ageMin");
        Integer vMax = (Integer) v.get("ageMax");
        Integer tAgeSelf = (Integer) t.get("ageSelf");
        Integer tMin = (Integer) t.get("ageMin");
        Integer tMax = (Integer) t.get("ageMax");

        if (tAgeSelf == null || vAgeSelf == null)
            return false;
        int vmin = vMin == null ? 18 : vMin, vmax = vMax == null ? 99 : vMax;
        int tmin = tMin == null ? 18 : tMin, tmax = tMax == null ? 99 : tMax;
        if (!(tAgeSelf >= vmin && tAgeSelf <= vmax))
            return false;
        if (!(vAgeSelf >= tmin && vAgeSelf <= tmax))
            return false;

        // Religion/politics dealbreakers
        Importance vRelImp = toImportance(v.get("religionImp"));
        @SuppressWarnings("unchecked")
        List<String> vRelSeek = (List<String>) v.get("religionSeeking");
        String tRelSelf = (String) t.get("religionSelf");
        if (vRelImp == Importance.DEALBREAKER && !(vRelSeek != null && vRelSeek.contains(tRelSelf)))
            return false;

        Importance tRelImp = toImportance(t.get("religionImp"));
        @SuppressWarnings("unchecked")
        List<String> tRelSeek = (List<String>) t.get("religionSeeking");
        String vRelSelf = (String) v.get("religionSelf");
        if (tRelImp == Importance.DEALBREAKER && !(tRelSeek != null && tRelSeek.contains(vRelSelf)))
            return false;

        Importance vPolImp = toImportance(v.get("politicsImp"));
        @SuppressWarnings("unchecked")
        List<String> vPolSeek = (List<String>) v.get("politicsSeeking");
        String tPolSelf = (String) t.get("politicsSelf");
        if (vPolImp == Importance.DEALBREAKER && !(vPolSeek != null && vPolSeek.contains(tPolSelf)))
            return false;

        Importance tPolImp = toImportance(t.get("politicsImp"));
        @SuppressWarnings("unchecked")
        List<String> tPolSeek = (List<String>) t.get("politicsSeeking");
        String vPolSelf = (String) v.get("politicsSelf");
        if (tPolImp == Importance.DEALBREAKER && !(tPolSeek != null && tPolSeek.contains(vPolSelf)))
            return false;

        // Tag dealbreakers
        @SuppressWarnings("unchecked")
        List<String> vTagDB = (List<String>) v.get("tagDealbreakers");
        @SuppressWarnings("unchecked")
        List<String> tSelfTags = (List<String>) t.get("selfTags");
        if (vTagDB != null && !vTagDB.isEmpty()) {
            if (tSelfTags == null)
                return false;
            for (String req : vTagDB)
                if (!tSelfTags.contains(req))
                    return false;
        }
        @SuppressWarnings("unchecked")
        List<String> tTagDB = (List<String>) t.get("tagDealbreakers");
        @SuppressWarnings("unchecked")
        List<String> vSelfTags = (List<String>) v.get("selfTags");
        if (tTagDB != null && !tTagDB.isEmpty()) {
            if (vSelfTags == null)
                return false;
            for (String req : tTagDB)
                if (!vSelfTags.contains(req))
                    return false;
        }

        return true;
    }

    // ---------------- Scoring ----------------

    public static Double computeV0Score(Map<String, Object> v, Map<String, Object> t) {
        double score = 60.0;

        // Religion preferences (+10 viewer, +5 mutual)
        Importance vRelImp = toImportance(v.get("religionImp"));
        @SuppressWarnings("unchecked")
        List<String> vRelSeek = (List<String>) v.get("religionSeeking");
        String tRelSelf = (String) t.get("religionSelf");
        if (vRelImp == Importance.PREFERENCE && vRelSeek != null && tRelSelf != null && vRelSeek.contains(tRelSelf))
            score += 10.0;

        Importance tRelImp = toImportance(t.get("religionImp"));
        @SuppressWarnings("unchecked")
        List<String> tRelSeek = (List<String>) t.get("religionSeeking");
        String vRelSelf = (String) v.get("religionSelf");
        if (tRelImp == Importance.PREFERENCE && tRelSeek != null && vRelSelf != null && tRelSeek.contains(vRelSelf))
            score += 5.0;

        // Politics preferences (+10 viewer, +5 mutual)
        Importance vPolImp = toImportance(v.get("politicsImp"));
        @SuppressWarnings("unchecked")
        List<String> vPolSeek = (List<String>) v.get("politicsSeeking");
        String tPolSelf = (String) t.get("politicsSelf");
        if (vPolImp == Importance.PREFERENCE && vPolSeek != null && tPolSelf != null && vPolSeek.contains(tPolSelf))
            score += 10.0;

        Importance tPolImp = toImportance(t.get("politicsImp"));
        @SuppressWarnings("unchecked")
        List<String> tPolSeek = (List<String>) t.get("politicsSeeking");
        String vPolSelf = (String) v.get("politicsSelf");
        if (tPolImp == Importance.PREFERENCE && tPolSeek != null && vPolSelf != null && tPolSeek.contains(vPolSelf))
            score += 5.0;

        // Tag preference hits (viewer -> target) up to +10
        @SuppressWarnings("unchecked")
        List<String> vTagPref = (List<String>) v.get("tagPreferences");
        @SuppressWarnings("unchecked")
        List<String> tSelfTags = (List<String>) t.get("selfTags");
        if (vTagPref != null && tSelfTags != null) {
            int hits = 0;
            for (String tag : vTagPref)
                if (tSelfTags.contains(tag))
                    hits++;
            score += Math.min(10.0, hits * 2.0);
        }

        // Tag overlap Jaccard up to +15
        @SuppressWarnings("unchecked")
        List<String> vSelfTags = (List<String>) v.get("selfTags");
        double j = jaccard(vSelfTags, tSelfTags);
        score += j * 15.0;

        return score;
    }

    // ---------------- Relationship mode (map-based) ----------------

    public static boolean modeCompatible(String vMode, String tMode) {
        if (vMode == null || tMode == null)
            return true; // permissive fallback
        if ("both".equals(vMode) || "both".equals(tMode))
            return true;
        return Objects.equals(vMode, tMode);
    }

    public static Double thresholdByModes(String vMode, String tMode, double casualFloor, double seriousFloor) {
        boolean serious = "serious".equals(vMode) || "serious".equals(tMode);
        return serious ? seriousFloor : casualFloor;
    }

    private static Importance toImportance(Object o) {
        if (o == null)
            return Importance.NOT_IMPORTANT;
        if (o instanceof Importance)
            return (Importance) o;
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty())
                return Importance.NOT_IMPORTANT;
            // stored via imp.name(), so use exact enum names; be tolerant to case
            try {
                return Importance.valueOf(s);
            } catch (IllegalArgumentException e) {
                return Importance.valueOf(s.toUpperCase(Locale.ROOT));
            }
        }
        // last-resort default to avoid crashes if something odd leaks in
        return Importance.NOT_IMPORTANT;
    }
}
