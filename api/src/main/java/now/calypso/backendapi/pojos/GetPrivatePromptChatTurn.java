package now.calypso.backendapi.pojos;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class GetPrivatePromptChatTurn {
    public final String agentMessage;
    public final boolean needsMoreDetail;

    @JsonCreator
    public GetPrivatePromptChatTurn(
            @JsonProperty("agentMessage") String agentMessage,
            @JsonProperty("needsMoreDetail") boolean needsMoreDetail) {
        this.agentMessage = agentMessage;
        this.needsMoreDetail = needsMoreDetail;
    }
}
