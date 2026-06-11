package now.calypso.backendapi.pojos;

import now.calypso.backend.data.Filters;
import now.calypso.backend.data.LocationFilter;
import now.calypso.backend.data.ModeFilter;
import now.calypso.backend.data.OneToManyFilter;
import now.calypso.backend.data.RangeFilter;

public class PostFilters {
    private static final double DEFAULT_WORLD_LAT = 0.0;
    private static final double DEFAULT_WORLD_LON = 0.0;
    private static final double DEFAULT_WORLD_RADIUS_KM = 30000.0;

    public ModeFilter relationshipMode;
    public OneToManyFilter gender;
    public RangeFilter age;
    public LocationFilter location;

    public PostFilters() {
    }

    /**
     * Convert this POJO into a Thrift Filters object, binding the path accountId.
     */
    public Filters toThrift(long accountId) {
        Filters f = new Filters();
        // Thrift struct defines accountId as a string
        f.setAccountId(accountId);
        f.setRelationshipMode(relationshipMode);
        f.setGender(gender);
        f.setAge(age);
        f.setLocation(location != null ? location : defaultWorldwideLocation());
        return f;
    }

    private static LocationFilter defaultWorldwideLocation() {
        return new LocationFilter()
                .setLat(DEFAULT_WORLD_LAT)
                .setLon(DEFAULT_WORLD_LON)
                .setRadiusKm(DEFAULT_WORLD_RADIUS_KM)
                .setScope(now.calypso.backend.data.LocationScope.WORLDWIDE);
    }
}
