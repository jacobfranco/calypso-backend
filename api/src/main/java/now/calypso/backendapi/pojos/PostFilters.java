package now.calypso.backendapi.pojos;

import now.calypso.backend.data.Filters;
import now.calypso.backend.data.LocationFilter;
import now.calypso.backend.data.ManyToManyFilter;
import now.calypso.backend.data.ModeFilter;
import now.calypso.backend.data.OneToManyFilter;
import now.calypso.backend.data.RangeFilter;

public class PostFilters {
    public ModeFilter relationshipMode;
    public OneToManyFilter gender;
    public RangeFilter age;
    public LocationFilter location;
    public OneToManyFilter religion;
    public OneToManyFilter politics;
    public ManyToManyFilter lifestyle;

    public PostFilters() {
    }

    /**
     * Convert this POJO into a Thrift Filters object, binding the path accountId.
     */
    public Filters toThrift(long accountId) {
        Filters f = new Filters();
        // Thrift struct defines accountId as a string
        f.setAccountId(accountId);
        if (religion != null) {
            religion.unsetSeeking();
        }
        if (politics != null) {
            politics.unsetSeeking();
        }
        f.setRelationshipMode(relationshipMode);
        f.setGender(gender);
        f.setAge(age);
        f.setLocation(location);
        f.setReligion(religion);
        f.setPolitics(politics);
        f.setLifestyle(lifestyle);
        return f;
    }
}
