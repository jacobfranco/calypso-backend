package now.calypso.backendapi.pojos;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class PostPublicPromptSelectionRequest {
    public final List<String> selectedPromptIds;

    @JsonCreator
    public PostPublicPromptSelectionRequest(
            @JsonProperty("selectedPromptIds") List<String> selectedPromptIds) {
        this.selectedPromptIds = selectedPromptIds;
    }
}
