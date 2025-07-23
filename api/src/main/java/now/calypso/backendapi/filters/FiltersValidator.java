package now.calypso.backendapi.filters;

import now.calypso.backend.data.*;
import now.calypso.backendapi.pojos.PostFilters;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FiltersValidator {

    private final TagDictionaryService tags;

    // new allowed‐values sets
    private static final Set<String> ALLOWED_RELATIONSHIP_MODES = Set.of("casual", "serious");
    private static final Set<String> ALLOWED_RADII = Set.of("my_city", "my_state", "worldwide");

    public FiltersValidator(TagDictionaryService tags) {
        this.tags = tags;
    }

    public void validate(PostFilters p) {
        // --- range sanity ---------------------------------------------------
        RangeFilter age = p.age;
        if (age != null) {
            // Only check inverted‐range if both min & max are truly non‐default
            if (age.isSetMin() && age.isSetMax()) {
                int min = age.getMin();
                int max = age.getMax();
                // Skip if max was never really set (Thrift default=0)
                if (max != 0 && min > max) {
                    throw new IllegalArgumentException("age.min cannot exceed age.max");
                }
            }
        }

        // --- relationshipMode sanity ----------------------------------------
        ModeFilter rm = p.relationshipMode;
        if (rm != null) {
            if (rm.getSelf() == null || rm.getSelf().isBlank()) {
                throw new IllegalArgumentException("relationshipMode.self is required");
            }
            if (!ALLOWED_RELATIONSHIP_MODES.contains(rm.getSelf())) {
                throw new IllegalArgumentException("Unknown relationshipMode.self '" + rm.getSelf() + "'");
            }
        }

        // --- location sanity -----------------------------------------------
        LocationFilter loc = p.location;
        if (loc != null) {
            // 1) must supply both
            if (loc.getCity() == null || loc.getCity().isBlank()) {
                throw new IllegalArgumentException("location.city is required");
            }
            if (loc.getRadius() == null || loc.getRadius().isBlank()) {
                throw new IllegalArgumentException("location.radius is required");
            }
            // 2) existing enum‐check
            if (!ALLOWED_RADII.contains(loc.getRadius())) {
                throw new IllegalArgumentException("Unknown location.radius '" + loc.getRadius() + "'");
            }
        }

        // --- tag sanity & duplicates ---------------------------------------
        checkMany(p.lifestyle, tags.lifestyleFlat(), "lifestyle");
        checkMany(p.interests, tags.interestsFlat(), "interests");
        checkOne(p.gender, tags.gendersFlat(), "gender");
        checkOne(p.religion, tags.religionsFlat(), "religion");
        checkOne(p.politics, tags.politicsFlat(), "politics");

        // (gender/religion/politics are OneToMany; you can add similar duplicate checks
        // if you wish)
    }

    private void checkMany(ManyToManyFilter m, Set<String> allowed, String field) {
        if (m == null)
            return;

        if (m.isSetSelf()) {
            ensureNoDuplicates(m.getSelf(), field + ".self");
            for (String tag : m.getSelf()) {
                assertTag(tag, allowed, field + ".self");
            }
        }

        if (m.isSetPreferences()) {
            // ensure tags in preferences are unique
            List<String> prefs = new ArrayList<>();
            for (TagPreference tp : m.getPreferences()) {
                prefs.add(tp.getTag());
            }
            ensureNoDuplicates(prefs, field + ".preferences");

            for (TagPreference tp : m.getPreferences()) {
                assertTag(tp.getTag(), allowed, field + ".preferences");
            }
        }
    }

    private void checkOne(OneToManyFilter m, Set<String> allowed, String field) {
        if (m == null)
            return;
        if (m.getSelf() != null && !allowed.contains(m.getSelf())) {
            throw new IllegalArgumentException("Unknown tag '" + m.getSelf() + "' in " + field + ".self");
        }
        if (m.getSeeking() != null) {
            for (String tag : m.getSeeking()) {
                if (!allowed.contains(tag)) {
                    throw new IllegalArgumentException("Unknown tag '" + tag + "' in " + field + ".seeking");
                }
            }
        }
    }

    private void assertTag(String tag, Set<String> allowed, String src) {
        if (!allowed.contains(tag)) {
            throw new IllegalArgumentException("Unknown tag '" + tag + "' in " + src);
        }
    }

    private void ensureNoDuplicates(List<?> items, String src) {
        Set<Object> seen = new HashSet<>();
        for (Object i : items) {
            if (!seen.add(i)) {
                throw new IllegalArgumentException("Duplicate entry '" + i + "' in " + src);
            }
        }
    }
}
