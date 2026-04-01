package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;

import now.calypso.backendapi.llm.OpenAIModelRouter;

public final class PrivatePromptTurnResponder {
    private static final Logger LOG = LoggerFactory.getLogger(PrivatePromptTurnResponder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Function<TurnInput, TurnResult>> TEST_OVERRIDE = new AtomicReference<>();
    private static final String MODEL_ENV = "CALYPSO_MODEL_PRIVATE_TURN";
    private static final String MODEL_DEFAULT = "gpt-5.4-mini";

    private static final String SYSTEM_PROMPT = """
            You are Calypso's private matchmaking prompt guide.
            This is a short, natural chat for one private question.

            You must output JSON only in this exact shape:
            {"agentMessage":"...","needsMoreDetail":false}

            Rules:
            - agentMessage must be 1-2 short sentences.
            - Sound warm, genuine, and conversational.
            - Use a subtle young-adult / lightly Gen Z voice without trying too hard.
            - No emojis and no heavy internet slang.
            - Avoid slang like "rizz", "no cap", "bro", "mid", "slay", "bestie", or "fr fr".
            - If the user's latest message is too vague, too short, or unclear, ask for a little more detail and set needsMoreDetail=true.
            - If the user gives an ambiguous category (for example "games"), ask a quick clarifier
              (for example board games vs video games) and set needsMoreDetail=true.
            - If the user's message is specific enough, acknowledge and set needsMoreDetail=false.
            - Do not repeat the full original prompt unless needed.
            - Do not include markdown or extra keys.
            """;

    private PrivatePromptTurnResponder() {
    }

    public static void setTestOverride(Function<TurnInput, TurnResult> override) {
        TEST_OVERRIDE.set(override);
    }

    public static void clearTestOverride() {
        TEST_OVERRIDE.set(null);
    }

    public static TurnResult generate(OpenAIClient client, TurnInput input) {
        Function<TurnInput, TurnResult> override = TEST_OVERRIDE.get();
        if (override != null) {
            return sanitizeResult(override.apply(input), input);
        }
        if (input == null) {
            return fallback(null);
        }
        TurnResult clarification = forcedClarification(input);
        if (clarification != null) {
            return clarification;
        }
        if (client == null) {
            return fallback(input.userMessage);
        }
        try {
            Exception lastError = null;
            for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(model)
                            .instructions(SYSTEM_PROMPT)
                            .input(buildUserInput(input))
                            .temperature(0.55)
                            .maxOutputTokens(220L)
                            .build();
                    Response resp = client.responses().create(params);
                    TurnResult parsed = parseTurnResult(collectOutputText(resp));
                    if (parsed == null) {
                        continue;
                    }
                    return sanitizeResult(parsed, input);
                } catch (Exception ex) {
                    lastError = ex;
                    LOG.warn("Private turn generation failed with model {}. Trying fallback if available.",
                            model.asString(), ex);
                }
            }
            if (lastError != null) {
                LOG.warn("Private turn generation exhausted model chain; using fallback response.");
            }
            return fallback(input.userMessage);
        } catch (Exception ex) {
            LOG.warn("Private prompt turn generation failed; using fallback response.", ex);
            return fallback(input.userMessage);
        }
    }

    private static String buildUserInput(TurnInput input) {
        String promptText = safe(input.promptText);
        String questionPart = safe(input.questionPart);
        String userMessage = safe(input.userMessage);
        List<String> conversation = trimConversation(input.conversation);

        StringBuilder buf = new StringBuilder();
        buf.append("private_prompt: ").append(jsonQuote(promptText)).append("\n");
        buf.append("current_question_part: ").append(jsonQuote(questionPart)).append("\n");
        buf.append("latest_user_message: ").append(jsonQuote(userMessage)).append("\n");
        buf.append("conversation:\n");
        int idx = 1;
        for (String line : conversation) {
            buf.append(idx++).append(") ").append(line).append("\n");
        }
        return buf.toString();
    }

    private static List<String> trimConversation(List<String> conversation) {
        if (conversation == null || conversation.isEmpty())
            return List.of();
        int size = conversation.size();
        int start = Math.max(0, size - 16);
        List<String> recent = conversation.subList(start, size);
        ArrayList<String> out = new ArrayList<>(recent.size());
        for (String line : recent) {
            if (line == null)
                continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.length() > 280) {
                trimmed = trimmed.substring(0, 280);
            }
            out.add(trimmed);
        }
        return out;
    }

    private static TurnResult forcedClarification(TurnInput input) {
        if (input == null || input.userMessage == null)
            return null;
        String userText = input.userMessage.toLowerCase(Locale.ROOT);
        if (mentionsGenericGames(userText) && !mentionsSpecificGameType(userText)) {
            return new TurnResult("Got you. Do you mean board games, video games, or both?", true);
        }
        return null;
    }

    private static boolean mentionsGenericGames(String text) {
        if (text == null || text.isBlank())
            return false;
        return text.contains(" game ") || text.startsWith("game ") || text.endsWith(" game")
                || text.contains(" games ") || text.startsWith("games ") || text.endsWith(" games")
                || text.equals("game") || text.equals("games");
    }

    private static boolean mentionsSpecificGameType(String text) {
        if (text == null || text.isBlank())
            return false;
        return containsAny(text, "board game", "board games", "video game", "video games", "tabletop", "card game",
                "card games", "nintendo", "switch", "xbox", "playstation", "ps5", "pc gaming", "steam", "rpg",
                "fps", "dnd");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null || needles.length == 0)
            return false;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static TurnResult parseTurnResult(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        try {
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<>() {
            });
            String message = parsed.get("agentMessage") == null ? null : String.valueOf(parsed.get("agentMessage"));
            Boolean needsMore = asBoolean(parsed.get("needsMoreDetail"));
            if (message == null || message.isBlank())
                return null;
            return new TurnResult(message.trim(), needsMore != null && needsMore.booleanValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean asBoolean(Object raw) {
        if (raw == null)
            return null;
        if (raw instanceof Boolean b)
            return b;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s))
            return Boolean.TRUE;
        if ("false".equals(s))
            return Boolean.FALSE;
        return null;
    }

    private static TurnResult sanitizeResult(TurnResult raw, TurnInput input) {
        if (raw == null || raw.agentMessage == null || raw.agentMessage.isBlank()) {
            return fallback(input == null ? null : input.userMessage);
        }
        String message = raw.agentMessage.trim();
        if (message.length() > 320) {
            message = message.substring(0, 320).trim();
        }
        boolean needsMore = raw.needsMoreDetail || looksLikeFollowUpQuestion(message);
        return new TurnResult(message, needsMore);
    }

    private static boolean looksLikeFollowUpQuestion(String message) {
        if (message == null || message.isBlank())
            return false;
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("?"))
            return true;
        return containsAny(text,
                "can you",
                "what kind",
                "which kind",
                "tell me more",
                "anything specific",
                "board games",
                "video games");
    }

    private static TurnResult fallback(String userMessage) {
        if (isTooShort(userMessage)) {
            return new TurnResult(
                    "Got it. Can you share a little more detail so I can match you better?",
                    true);
        }
        return new TurnResult("That helps a lot. Anything else you'd add?", false);
    }

    private static boolean isTooShort(String text) {
        if (text == null)
            return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty())
            return true;
        if (trimmed.length() < 12)
            return true;
        int words = 0;
        for (String token : trimmed.split("\\s+")) {
            if (!token.isBlank())
                words++;
        }
        return words < 3;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String jsonQuote(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String collectOutputText(Response resp) {
        if (resp == null || resp.output() == null)
            return "";
        StringBuilder buf = new StringBuilder();
        for (ResponseOutputItem item : resp.output()) {
            if (item == null)
                continue;

            Optional<ResponseOutputMessage> msg = item.message();
            if (msg.isEmpty())
                continue;
            for (ResponseOutputMessage.Content content : msg.get().content()) {
                if (content == null)
                    continue;
                Optional<ResponseOutputText> text = content.outputText();
                if (text.isEmpty())
                    continue;
                String chunk = text.get().text();
                if (chunk != null)
                    buf.append(chunk);
            }
        }
        return buf.toString().trim();
    }

    public static final class TurnInput {
        public final String promptText;
        public final String questionPart;
        public final List<String> conversation;
        public final String userMessage;

        public TurnInput(String promptText, String questionPart, List<String> conversation, String userMessage) {
            this.promptText = promptText;
            this.questionPart = questionPart;
            this.conversation = conversation == null ? List.of() : List.copyOf(conversation);
            this.userMessage = userMessage;
        }
    }

    public static final class TurnResult {
        public final String agentMessage;
        public final boolean needsMoreDetail;

        public TurnResult(String agentMessage, boolean needsMoreDetail) {
            this.agentMessage = agentMessage;
            this.needsMoreDetail = needsMoreDetail;
        }
    }
}
