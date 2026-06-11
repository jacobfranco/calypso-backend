package now.calypso.backendapi.filters;

import now.calypso.backend.data.*;
import now.calypso.backendapi.pojos.PostFilters;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FiltersValidator {

    private final TagDictionaryService tags;

    private static final Set<String> ALLOWED_RELATIONSHIP_MODES =
            Set.of("focused", "balanced", "exploratory");

    public FiltersValidator(TagDictionaryService tags) {
        this.tags = tags;
    }

    public void validate(PostFilters p) {
        // --- range sanity ---------------------------------------------------
        RangeFilter age = p.age;
        if (age != null && age.isSetMin() && age.isSetMax()) {
            int min = age.getMin();
            int max = age.getMax();
            if (max != 0 && min > max) {
                throw new IllegalArgumentException("age.min cannot exceed age.max");
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

        // --- location sanity (numeric lat/lon/radiusKm) ----------------------
        LocationFilter loc = p.location;
        if (loc != null) {
            if (!loc.isSetLat() || !loc.isSetLon() || !loc.isSetRadiusKm()) {
                throw new IllegalArgumentException(
                        "location.lat, location.lon, and location.radiusKm are all required");
            }
            double lat = loc.getLat();
            double lon = loc.getLon();
            double rkm = loc.getRadiusKm();

            if (Double.isNaN(lat) || lat < -90.0 || lat > 90.0) {
                throw new IllegalArgumentException("location.lat must be in [-90, 90]");
            }
            if (Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
                throw new IllegalArgumentException("location.lon must be in [-180, 180]");
            }
            if (Double.isNaN(rkm) || Double.isInfinite(rkm) || rkm <= 0.0 || rkm > 30000.0) {
                throw new IllegalArgumentException("location.radiusKm must be in (0, 30000]");
            }

            if (loc.isSetScope() && loc.getScope() == LocationScope.COUNTRY) {
                String code = loc.getCountryCode();
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("location.countryCode is required for country scope");
                }
                String trimmed = code.trim();
                if (trimmed.length() != 2) {
                    throw new IllegalArgumentException("location.countryCode must be an ISO-3166 alpha-2 code");
                }
            }
        }

        checkOne(p.gender, tags.gendersFlat(), "gender");
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
}
