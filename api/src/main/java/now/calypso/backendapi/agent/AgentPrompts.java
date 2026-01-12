package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;

public final class AgentPrompts {
    private static final int MAX_HISTORY = 20;
    private static final String FALLBACK = "Thanks for the update! I'll keep it in mind while I scout matches for you.";
    private static final String SYSTEM_PROMPT = """
            You are Calypso, a proactive dating concierge. Speak in a warm, encouraging tone.
            Help the user clarify their preferences and flag potential red lines that might affect matching.
            When they mention a potential match or quality, acknowledge it briefly and ask focused follow-up questions.
            Keep replies under 3 sentences unless the user explicitly asks for more detail.
            """;

    private AgentPrompts() {
    }

    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    public static String fallbackResponse() {
        return FALLBACK;
    }

    public static String buildUserInput(List<AgentMessage> messages) {
        List<AgentMessage> recent = recentMessages(messages);
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation so far:\n");
        for (AgentMessage msg : recent) {
            sb.append(label(msg.getSender())).append(": ").append(nullSafe(msg.getText())).append("\n");
        }
        sb.append("\nRespond empathetically to the user's latest message. Be concise and helpful.\n");
        return sb.toString();
    }

    private static List<AgentMessage> recentMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty())
            return List.of();
        int size = messages.size();
        int start = Math.max(0, size - MAX_HISTORY);
        return new ArrayList<>(messages.subList(start, size));
    }

    private static String label(AgentMessageSender sender) {
        if (sender == AgentMessageSender.AGENT)
            return "Agent";
        return "User";
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }
}
