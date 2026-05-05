package now.calypso.backendapi.pojos;

import java.util.List;

public class PostSignalConceptPromoteRequest {
    public String rawToken;
    public String canonicalToken;
    public String category;
    public List<String> parentConcepts;
}
