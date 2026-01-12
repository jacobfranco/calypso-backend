package now.calypso.backendapi.pojos;

public class PostAgentMessageRequest {
    public String text;

    public String safeText() {
        if (text == null)
            return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
