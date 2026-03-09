package now.calypso.backendapi.pojos;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PostPublicPromptAnswerRequest {
    public final String body;

    @JsonCreator
    public PostPublicPromptAnswerRequest(@JsonProperty("body") String body) {
        this.body = body;
    }
}
