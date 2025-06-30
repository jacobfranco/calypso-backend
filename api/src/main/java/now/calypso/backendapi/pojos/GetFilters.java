package now.calypso.backendapi.pojos;

import now.calypso.backend.data.Filters;

public class GetFilters {
    public Filters filters;

    public GetFilters() {
    }

    public GetFilters(Filters filters) {
        this.filters = filters;
    }
}