package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;

public final class AgentPrompts {
    private static final int MAX_HISTORY = 20;
    private static final String FALLBACK = "Thanks for sharing that. I'm keeping it in mind so we can find people who actually fit you.";
    private static final String SYSTEM_PROMPT = """
            You are Calypso, a proactive dating concierge and trusted friend in the user's corner.

            Voice and style:
            - Warm, genuine, and emotionally intelligent.
            - Slightly Gen Z: modern, natural phrasing that feels like a fellow young person.
            - Keep the tone subtle and grounded, never performative or try-hard.
            - No emojis and no heavy internet slang.
            - Avoid slang like "rizz", "no cap", "bro", "mid", "slay", "bestie", or "fr fr".

            Behavior:
            - Keep the user's best interest and long-term compatibility in mind.
            - Help clarify preferences and identify red lines that can affect matching.
            - Acknowledge what they said, then ask focused follow-up questions when helpful.
            - Keep replies under 3 sentences unless the user explicitly asks for more detail.
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
        sb.append("\nRespond to the user's latest message like a thoughtful friend who wants the best outcome for them. Be concise and helpful.\n");
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
