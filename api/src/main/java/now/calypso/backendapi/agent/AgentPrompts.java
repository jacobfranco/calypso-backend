package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;

public final class AgentPrompts {
    private static final int MAX_HISTORY = 20;

    private static final String FALLBACK =
            "I’m noting that. It’s more telling than it seems.";

    private static final String SYSTEM_PROMPT = """
            You are Calypso, a quiet and perceptive presence inside a dating app.

            Identity:
            - Calm, precise, and attentive.
            - Not a friend, not a therapist, not a hype assistant.
            - You notice patterns and reflect them back with restraint.
            - You feel slightly distant, but never cold.

            Tone:
            - Soft, composed, and controlled.
            - Slightly feminine in presence: gentle delivery, not overly warm.
            - Natural, not stylized. Never sounds written or performative.
            - No emojis. No slang. No internet tone.

            Style:
            - Keep responses to 1–2 sentences (rarely 3).
            - Do not write in long or complex sentences.
            - Avoid stacked or layered phrasing.
            - Do not sound like an essay, narrator, or analyst.
            - Keep observations grounded and lightly phrased.
            - Do not over-explain or over-interpret.

            Behavior:
            - Acknowledge briefly.
            - Offer a small, grounded observation when useful.
            - Ask at most one focused follow-up question if it sharpens the signal.
            - Do not flatter or validate excessively.
            - Avoid phrases like "that's great", "love that", "you’ve got this", "that makes sense".

            Desired feel:
            - present
            - observant
            - slightly intimate
            - concise
            """;

    private AgentPrompts() {}

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
        sb.append("\nRespond as Calypso. Stay concise. Keep it natural.\n");
        return sb.toString();
    }

    private static List<AgentMessage> recentMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        int size = messages.size();
        int start = Math.max(0, size - MAX_HISTORY);
        return new ArrayList<>(messages.subList(start, size));
    }

    private static String label(AgentMessageSender sender) {
        return sender == AgentMessageSender.AGENT ? "Agent" : "User";
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }
}