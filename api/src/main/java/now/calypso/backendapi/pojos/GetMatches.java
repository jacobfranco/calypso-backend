package now.calypso.backendapi.pojos;

import java.util.List;

public class GetMatches {
    public final List<GetMatch> matches;

    public GetMatches(List<GetMatch> matches) {
        this.matches = matches;
    }
}
