package now.calypso.backendapi.filters;

import now.calypso.backend.data.ManyToManyFilter;
import now.calypso.backend.data.RangeFilter;
import now.calypso.backend.data.TagPreference;
import now.calypso.backendapi.pojos.PostFilters;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Lightweight, synchronous validation for a PostFilters payload.
 * Throws IllegalArgumentException on any invalid field.
 */
@Component
public class FiltersValidator {

    private final TagDictionaryService tags;

    public FiltersValidator(TagDictionaryService tags) {
        this.tags = tags;
    }

    public void validate(PostFilters p) {
        // --- range sanity ---------------------------------------------------
        RangeFilter age = p.age;
        if (age != null && age.isSetMin() && age.isSetMax() && age.getMin() > age.getMax()) {
            throw new IllegalArgumentException("age.min cannot exceed age.max");
        }

        // --- tag sanity -----------------------------------------------------
        checkMany(p.lifestyle, tags.lifestyleFlat(), "lifestyle");
        checkMany(p.interests, tags.interestsFlat(), "interests");
    }

    private void checkMany(ManyToManyFilter m, Set<String> allowed, String field) {
        if (m == null)
            return;

        if (m.isSetSelf()) {
            for (String tag : m.getSelf()) {
                assertTag(tag, allowed, field + ".self");
            }
        }
        if (m.isSetPreferences()) {
            for (TagPreference tp : m.getPreferences()) {
                assertTag(tp.getTag(), allowed, field + ".preferences");
            }
        }
    }

    private void assertTag(String tag, Set<String> allowed, String src) {
        if (!allowed.contains(tag)) {
            throw new IllegalArgumentException("Unknown tag '" + tag + "' in " + src);
        }
    }
}
