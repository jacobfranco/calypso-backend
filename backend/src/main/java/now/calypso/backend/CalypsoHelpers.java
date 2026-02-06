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

import java.security.SecureRandom;

public class CalypsoHelpers {

    public static final ConcurrentHashMap<Class, Map<String, TFieldIdEnum>> TFIELD_CACHE = new ConcurrentHashMap<>();

    private static final Pattern WS = Pattern.compile("\\s+");

     private static final String ALPHA_NUMERIC_STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom secureRandom = new SecureRandom();
  private static final Random random = new Random();

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

    public static class ExtractPhoneNumber extends ExtractField {
        public ExtractPhoneNumber() {
            super("phone_number");
        }
    }

    public static class ExtractAccountId extends ExtractField {
        public ExtractAccountId() {
            super("accountId");
        }
    }

    public static class ExtractClientId extends ExtractField {
    public ExtractClientId() {
      super("client_id");
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
        return new ArrayList<>(s);
    }

    public static List<String> getTagDealbreakers(Filters f) {
        List<String> out = new ArrayList<>();
        if (f != null && f.isSetLifestyle() && f.getLifestyle().isSetPreferences())
            for (TagPreference p : f.getLifestyle().getPreferences())
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

    private static OneToManyFilter getPolitics(Filters f) {
        return (f != null && f.isSetPolitics()) ? f.getPolitics() : null;
    }

    private static OneToManyFilter getReligion(Filters f) {
        return (f != null && f.isSetReligion()) ? f.getReligion() : null;
    }

    private static ManyToManyFilter getLifestyleFilter(Filters f) {
        return (f != null && f.isSetLifestyle()) ? f.getLifestyle() : null;
    }

    private static Set<String> getManySelfTags(ManyToManyFilter filter) {
        if (filter == null || !filter.isSetSelf())
            return Collections.emptySet();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String tag : filter.getSelf()) {
            if (tag != null)
                tags.add(tag);
        }
        return tags;
    }

    private static boolean manyDealbreakersSatisfied(ManyToManyFilter prefsHolder, Set<String> otherSelfTags) {
        if (prefsHolder == null || !prefsHolder.isSetPreferences())
            return true;
        Set<String> tags = (otherSelfTags == null) ? Collections.emptySet() : otherSelfTags;
        for (TagPreference pref : prefsHolder.getPreferences()) {
            if (pref == null || !pref.isSetTag() || !pref.isSetImportance())
                continue;
            if (pref.getImportance() == Importance.DEALBREAKER) {
                String tag = pref.getTag();
                if (tag == null || !tags.contains(tag)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double preferenceBonusFrom(ManyToManyFilter prefsHolder, Set<String> otherSelfTags, double weight) {
        if (prefsHolder == null || !prefsHolder.isSetPreferences() || weight <= 0.0)
            return 0.0;
        Set<String> tags = (otherSelfTags == null) ? Collections.emptySet() : otherSelfTags;
        double bonus = 0.0;
        for (TagPreference pref : prefsHolder.getPreferences()) {
            if (pref == null || !pref.isSetTag() || !pref.isSetImportance())
                continue;
            if (pref.getImportance() == Importance.PREFERENCE && tags.contains(pref.getTag())) {
                bonus += weight;
            }
        }
        return bonus;
    }

    public static boolean politicsCompatible(Filters viewer, Filters target) {
        OneToManyFilter vp = getPolitics(viewer);
        OneToManyFilter tp = getPolitics(target);

        Importance vImp = getOneToManyImportance(vp);
        Importance tImp = getOneToManyImportance(tp);

        // If nobody cares, allow
        boolean vCares = (vp != null && vImp == Importance.DEALBREAKER);
        boolean tCares = (tp != null && tImp == Importance.DEALBREAKER);
        if (!vCares && !tCares) {
            return true;
        }

        // Viewer dealbreaker: target.self must be in viewer.seeking
        if (vCares) {
            String tSelf = getOneToManySelf(tp);
            List<String> vSeek = getOneToManySeeking(vp);
            if (tSelf == null || vSeek == null || vSeek.isEmpty() || !vSeek.contains(tSelf)) {
                return false;
            }
        }

        // Target dealbreaker: viewer.self must be in target.seeking
        if (tCares) {
            String vSelf = getOneToManySelf(vp);
            List<String> tSeek = getOneToManySeeking(tp);
            if (vSelf == null || tSeek == null || tSeek.isEmpty() || !tSeek.contains(vSelf)) {
                return false;
            }
        }

        return true;
    }

    public static boolean religionCompatible(Filters viewer, Filters target) {
        OneToManyFilter vr = getReligion(viewer);
        OneToManyFilter tr = getReligion(target);

        Importance vImp = getOneToManyImportance(vr);
        Importance tImp = getOneToManyImportance(tr);

        boolean vCares = (vr != null && vImp == Importance.DEALBREAKER);
        boolean tCares = (tr != null && tImp == Importance.DEALBREAKER);

        // Nobody marked religion as a dealbreaker → allow
        if (!vCares && !tCares) {
            return true;
        }

        // Viewer dealbreaker: target.self must be in viewer.seeking
        if (vCares) {
            String tSelf = getOneToManySelf(tr);
            List<String> vSeek = getOneToManySeeking(vr);
            if (tSelf == null || vSeek == null || vSeek.isEmpty() || !vSeek.contains(tSelf)) {
                return false;
            }
        }

        // Target dealbreaker: viewer.self must be in target.seeking
        if (tCares) {
            String vSelf = getOneToManySelf(vr);
            List<String> tSeek = getOneToManySeeking(tr);
            if (vSelf == null || tSeek == null || tSeek.isEmpty() || !tSeek.contains(vSelf)) {
                return false;
            }
        }

        return true;
    }

    public static boolean lifestyleCompatible(Filters viewer, Filters target) {
        ManyToManyFilter vl = getLifestyleFilter(viewer);
        ManyToManyFilter tl = getLifestyleFilter(target);

        Set<String> viewerTags = getManySelfTags(vl);
        Set<String> targetTags = getManySelfTags(tl);

        if (!manyDealbreakersSatisfied(vl, targetTags))
            return false;
        if (!manyDealbreakersSatisfied(tl, viewerTags))
            return false;
        return true;
    }

    public static double computeLifestyleBonus(Filters viewer, Filters target) {
        ManyToManyFilter vl = getLifestyleFilter(viewer);
        ManyToManyFilter tl = getLifestyleFilter(target);

        Set<String> viewerTags = getManySelfTags(vl);
        Set<String> targetTags = getManySelfTags(tl);

        double bonus = 0.0;
        bonus += preferenceBonusFrom(vl, targetTags, 6.0);
        bonus += preferenceBonusFrom(tl, viewerTags, 3.0);
        return bonus;
    }


    public static double computePoliticsBonus(Filters viewer, Filters target) {
        OneToManyFilter vp = getPolitics(viewer);
        OneToManyFilter tp = getPolitics(target);

        double bonus = 0.0;

        if (vp != null) {
            Importance vImp = getOneToManyImportance(vp);
            if (vImp == Importance.PREFERENCE) {
                String tSelf = getOneToManySelf(tp);
                List<String> vSeek = getOneToManySeeking(vp);
                if (tSelf != null && vSeek != null && vSeek.contains(tSelf)) {
                    bonus += 10.0;
                }
            }
        }

        if (tp != null) {
            Importance tImp = getOneToManyImportance(tp);
            if (tImp == Importance.PREFERENCE) {
                String vSelf = getOneToManySelf(vp);
                List<String> tSeek = getOneToManySeeking(tp);
                if (vSelf != null && tSeek != null && tSeek.contains(vSelf)) {
                    bonus += 5.0;
                }
            }
        }

        return bonus;
    }

    public static double computeReligionBonus(Filters viewer, Filters target) {
        OneToManyFilter vr = getReligion(viewer);
        OneToManyFilter tr = getReligion(target);

        double bonus = 0.0;

        if (vr != null) {
            Importance vImp = getOneToManyImportance(vr);
            if (vImp == Importance.PREFERENCE) {
                String tSelf = getOneToManySelf(tr);
                List<String> vSeek = getOneToManySeeking(vr);
                if (tSelf != null && vSeek != null && vSeek.contains(tSelf)) {
                    bonus += 10.0;
                }
            }
        }

        if (tr != null) {
            Importance tImp = getOneToManyImportance(tr);
            if (tImp == Importance.PREFERENCE) {
                String vSelf = getOneToManySelf(vr);
                List<String> tSeek = getOneToManySeeking(tr);
                if (vSelf != null && tSeek != null && tSeek.contains(vSelf)) {
                    bonus += 5.0;
                }
            }
        }

        return bonus;
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
        if (!politicsCompatible(viewer, target))
            return -1.0;
        if (!religionCompatible(viewer, target))
            return -1.0;
        if (!lifestyleCompatible(viewer, target))
            return -1.0;
        return 100.0;
    }

    public static String generateSecureRandomString(int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      int index = secureRandom.nextInt(ALPHA_NUMERIC_STRING.length());
      builder.append(ALPHA_NUMERIC_STRING.charAt(index));
    }
    return builder.toString();
  }

  public static String randomString(int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      int index = random.nextInt(ALPHA_NUMERIC_STRING.length());
      builder.append(ALPHA_NUMERIC_STRING.charAt(index));
    }
    return builder.toString();
  }
}
