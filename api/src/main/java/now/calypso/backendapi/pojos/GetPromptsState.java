package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.PromptResponse;
import now.calypso.backend.data.PromptState;

public class GetPromptsState {
    public final Long accountId;
    public final List<GetPromptResponse> responses;

    public GetPromptsState(PromptState state) {
        this(state == null ? null : state.getAccountId(),
                toResponses(state));
    }

    @JsonCreator
    public GetPromptsState(
            @JsonProperty("accountId") Long accountId,
            @JsonProperty("responses") List<GetPromptResponse> responses) {
        this.accountId = accountId;
        this.responses = responses == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(responses));
    }

    private static List<GetPromptResponse> toResponses(PromptState state) {
        if (state == null || state.getResponses() == null)
            return Collections.emptyList();
        List<GetPromptResponse> out = new ArrayList<>();
        for (PromptResponse response : state.getResponses()) {
            if (response != null)
                out.add(new GetPromptResponse(response));
        }
        return out;
    }
}
