package now.calypso.backendapi.pojos;

import java.util.List;

public class PostSignalConceptActionRequest {
    public String rawToken;
    public String canonicalToken;
    public String category;
    public List<String> parentConcepts;
    public String action;
}
