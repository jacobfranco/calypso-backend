package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;
import now.calypso.backend.data.AgentSession;
import now.calypso.backend.data.AgentSessionStatus;

public class GetAgentSession {
    public final String sessionId;
    public final long createdAt;
    public final long lastInteractionAt;
    public final String status;
    public final List<GetAgentMessage> messages;

    public GetAgentSession(AgentSession session) {
        this(session == null ? null : session.getSessionId(),
                session == null ? 0L : session.getCreatedAt(),
                session == null ? 0L : session.getLastInteractionAt(),
                session == null || session.getStatus() == null ? null : session.getStatus().name(),
                convertMessages(session == null ? null : session.getMessages()));
    }

    @JsonCreator
    public GetAgentSession(
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("lastInteractionAt") long lastInteractionAt,
            @JsonProperty("status") String status,
            @JsonProperty("messages") List<GetAgentMessage> messages) {
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.lastInteractionAt = lastInteractionAt;
        this.status = status;
        this.messages = messages == null ? Collections.emptyList() : Collections.unmodifiableList(messages);
    }

    private static List<GetAgentMessage> convertMessages(List<AgentMessage> thriftMessages) {
        if (thriftMessages == null || thriftMessages.isEmpty())
            return Collections.emptyList();
        List<GetAgentMessage> out = new ArrayList<>(thriftMessages.size());
        for (AgentMessage msg : thriftMessages) {
            if (msg == null)
                continue;
            out.add(new GetAgentMessage(msg));
        }
        return out;
    }

    public static final class GetAgentMessage {
        public final String messageId;
        public final String sender;
        public final String text;
        public final long timestamp;

        public GetAgentMessage(AgentMessage msg) {
            this(msg == null ? null : msg.getMessageId(),
                    msg == null || msg.getSender() == null ? null : msg.getSender().name(),
                    msg == null ? null : msg.getText(),
                    msg == null ? 0L : msg.getTimestamp());
        }

        @JsonCreator
        public GetAgentMessage(
                @JsonProperty("messageId") String messageId,
                @JsonProperty("sender") String sender,
                @JsonProperty("text") String text,
                @JsonProperty("timestamp") long timestamp) {
            this.messageId = messageId;
            this.sender = sender;
            this.text = text;
            this.timestamp = timestamp;
        }
    }
}
