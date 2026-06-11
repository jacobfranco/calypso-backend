package now.calypso.backendapi.pojos;

import java.util.List;

import now.calypso.backend.data.Importance;

public class PostMatchStandardAnswerRequest {
    public List<String> ownAnswerOptionIds;
    public List<String> acceptableAnswerOptionIds;
    public Importance importance;

    public PostMatchStandardAnswerRequest() {
    }
}
