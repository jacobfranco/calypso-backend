package now.calypso.backendapi.pojos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PostPrivatePromptChatTurnRequest {
    public String questionPart;
    public String userMessage;
    public List<String> conversation;

    public String safeQuestionPart() {
        if (questionPart == null)
            return null;
        String trimmed = questionPart.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String safeUserMessage() {
        if (userMessage == null)
            return null;
        String trimmed = userMessage.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public List<String> safeConversation() {
        if (conversation == null || conversation.isEmpty())
            return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        for (String line : conversation) {
            if (line == null)
                continue;
            String trimmed = line.trim();
            if (!trimmed.isEmpty())
                out.add(trimmed);
        }
        return out;
    }
}
