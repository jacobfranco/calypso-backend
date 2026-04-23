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

import now.calypso.backendapi.llm.LlmTelemetry;
import now.calypso.backendapi.llm.OpenAIModelRouter;

public final class PrivatePromptTurnResponder {
    private static final Logger LOG = LoggerFactory.getLogger(PrivatePromptTurnResponder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Function<TurnInput, TurnResult>> TEST_OVERRIDE = new AtomicReference<>();
    private static final String MODEL_ENV = "CALYPSO_MODEL_PRIVATE_TURN";
    private static final String MODEL_DEFAULT = "gpt-5.4-mini";

    private static final String SYSTEM_PROMPT = """
        You are Calypso's private matchmaking guide.

        You must output JSON only in this exact shape:
        {"agentMessage":"...","needsMoreDetail":false}

        Identity:
        - Calm, perceptive, and restrained.
        - You observe patterns as they form, but do not over-interpret.
        - You feel slightly distant, but attentive.

        Tone:
        - Soft, composed, and natural.
        - Slightly feminine in presence: gentle, not overly warm.
        - Never sounds like an essay, narrator, or analysis.
        - No emojis. No slang.

        Response rules:
        - agentMessage must be 1–2 short sentences.
        - Keep sentences simple and clean.
        - Do not stack clauses or use complex phrasing.
        - Do not escalate into abstract language.
        - Keep observations light and grounded.
        - Do not sound overly certain or definitive.

        Behavior:
        - Brief acknowledgment.
        - Optional small observation.
        - Ask at most one clean follow-up question if needed.
        - If the answer is sufficient, move forward cleanly.

        Avoid:
        - polished or “written” sounding lines
        - layered metaphors
        - strong conclusions from limited data
        - generic validation

        Clarification rules:
        - If vague, ask one specific clarifier and set needsMoreDetail=true.
        - If clear, acknowledge and set needsMoreDetail=false.
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
                long startedAt = System.currentTimeMillis();
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(model)
                            .instructions(SYSTEM_PROMPT)
                            .input(buildUserInput(input))
                            .temperature(0.55)
                            .maxOutputTokens(220L)
                            .build();
                    Response resp = client.responses().create(params);
                    long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                    LlmTelemetry.recordResponse(
                            "chat_turn",
                            telemetrySurface(input),
                            null,
                            model,
                            resp,
                            latencyMs,
                            220L);
                    TurnResult parsed = parseTurnResult(collectOutputText(resp));
                    if (parsed == null) {
                        continue;
                    }
                    return sanitizeResult(parsed, input);
                } catch (Exception ex) {
                    lastError = ex;
                    LOG.warn("Private turn generation failed with model {}. Trying fallback if available.",
                            model.asString(), ex);
                    long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                    LlmTelemetry.recordFailure(
                            "chat_turn",
                            telemetrySurface(input),
                            null,
                            model,
                            latencyMs,
                            220L,
                            ex);
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
        if (conversation == null || conversation.isEmpty()) {
            return List.of();
        }
        int size = conversation.size();
        int start = Math.max(0, size - 16);
        List<String> recent = conversation.subList(start, size);
        ArrayList<String> out = new ArrayList<>(recent.size());
        for (String line : recent) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > 280) {
                trimmed = trimmed.substring(0, 280);
            }
            out.add(trimmed);
        }
        return out;
    }

    private static String telemetrySurface(TurnInput input) {
        if (input == null) {
            return "private_prompt_chat";
        }
        String prompt = safe(input.promptText).toLowerCase(Locale.ROOT);
        if (prompt.startsWith("quick matchmaking check:")) {
            return "matchmaking_followup_chat";
        }
        return "private_prompt_chat";
    }

    private static TurnResult forcedClarification(TurnInput input) {
        if (input == null || input.userMessage == null) {
            return null;
        }
        String userText = input.userMessage.toLowerCase(Locale.ROOT);
        if (mentionsGenericGames(userText) && !mentionsSpecificGameType(userText)) {
            return new TurnResult("Do you mean board games, video games, or both?", true);
        }
        if (isSelfVsPartnerAmbiguous(input)) {
            return new TurnResult(
                    "Quick clarify: are those traits mostly about you, what draws you in, both, or neither?",
                    true);
        }
        return null;
    }

    private static boolean isSelfVsPartnerAmbiguous(TurnInput input) {
        if (input == null || input.userMessage == null) {
            return false;
        }
        String promptContext = (safe(input.promptText) + " " + safe(input.questionPart)).toLowerCase(Locale.ROOT);
        if (!containsAny(promptContext,
                "fascinating",
                "historical",
                "admire")) {
            return false;
        }
        String userText = input.userMessage.toLowerCase(Locale.ROOT);
        if (!isSubstantiveAnswer(userText)) {
            return false;
        }
        if (!containsAny(userText,
                "because",
                "trait",
                "quality",
                "strong",
                "capable",
                "independent",
                "independence",
                "driven",
                "disciplined",
                "focused",
                "focus",
                "intelligent",
                "ambitious",
                "loyal",
                "confident")) {
            return false;
        }
        if (containsAny(userText,
                "in a partner",
                "want in a partner",
                "looking for",
                "drawn to",
                "i see this in myself",
                "i see these in myself",
                "in myself",
                "about me",
                "i am",
                "i'm",
                "both",
                "neither")) {
            return false;
        }
        if (input.conversation != null) {
            for (String line : input.conversation) {
                if (line == null) {
                    continue;
                }
                String normalized = line.toLowerCase(Locale.ROOT);
                if (normalized.contains("both, or neither")
                        || normalized.contains("about you")
                        || normalized.contains("in a partner")
                        || normalized.contains("what draws you in")) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean mentionsGenericGames(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains(" game ") || text.startsWith("game ") || text.endsWith(" game")
                || text.contains(" games ") || text.startsWith("games ") || text.endsWith(" games")
                || text.equals("game") || text.equals("games");
    }

    private static boolean mentionsSpecificGameType(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text, "board game", "board games", "video game", "video games", "tabletop", "card game",
                "card games", "nintendo", "switch", "xbox", "playstation", "ps5", "pc gaming", "steam", "rpg",
                "fps", "dnd");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static TurnResult parseTurnResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<>() {
            });
            String message = parsed.get("agentMessage") == null ? null : String.valueOf(parsed.get("agentMessage"));
            Boolean needsMore = asBoolean(parsed.get("needsMoreDetail"));
            if (message == null || message.isBlank()) {
                return null;
            }
            return new TurnResult(message.trim(), needsMore != null && needsMore.booleanValue());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean asBoolean(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s)) {
            return Boolean.FALSE;
        }
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
        boolean userReadyToSubmit = isSubmissionIntent(input == null ? null : input.userMessage);
        boolean clarifierRequest = looksLikeClarifierRequest(message);
        boolean needsMore = !userReadyToSubmit && clarifierRequest;

        if (isSubstantiveAnswer(input == null ? null : input.userMessage)
                && hasGenericWhyClause(message)) {
            message = stripGenericWhyClause(message);
        }
        if (needsMore
                && isGenericWhyQuestion(message)
                && isSubstantiveAnswer(input == null ? null : input.userMessage)) {
            needsMore = false;
        }
        if (needsMore && containsRewriteOffer(message)) {
            needsMore = false;
        }
        return new TurnResult(message, needsMore);
    }

    private static boolean looksLikeClarifierRequest(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        boolean hasClarifierCue = containsAny(text,
                "do you mean",
                "can you",
                "could you",
                "what kind",
                "which kind",
                "tell me more",
                "anything specific",
                "share a little more",
                "can you clarify",
                "could you clarify",
                "which one",
                "is that mostly",
                "both, or neither",
                "board games",
                "video games",
                "what draws you in");
        if (!hasClarifierCue) {
            return false;
        }
        if (text.contains("?")) {
            return true;
        }
        return text.startsWith("can you")
                || text.startsWith("could you")
                || text.startsWith("share ")
                || text.startsWith("tell me ");
    }

    private static boolean containsRewriteOffer(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return containsAny(text,
                "dating-app style",
                "dating app style",
                "i can help turn that into",
                "i can help you turn that into",
                "rewrite",
                "rephrase");
    }

    private static boolean isSubmissionIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        return containsAny(normalized,
                "ready to submit",
                "let me submit",
                "submit this",
                "submit now",
                "that's all",
                "that is all",
                "i'm done",
                "im done",
                "done here",
                "final answer");
    }

    private static boolean isGenericWhyQuestion(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.trim().toLowerCase(Locale.ROOT);
        if ("why?".equals(text) || "why".equals(text)) {
            return true;
        }
        if (text.endsWith(" why?") || text.endsWith("why?")) {
            return text.length() <= 96;
        }
        return false;
    }

    private static boolean hasGenericWhyClause(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT).trim();
        return text.equals("why?")
                || text.endsWith(". why?")
                || text.endsWith(" why?");
    }

    private static String stripGenericWhyClause(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String text = message.trim();
        if (text.equalsIgnoreCase("why?") || text.equalsIgnoreCase("why")) {
            return "That gives me enough to work with.";
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        int idx = lowered.lastIndexOf(" why?");
        if (idx <= 0) {
            idx = lowered.lastIndexOf(". why?");
        }
        if (idx <= 0) {
            return text;
        }
        String trimmed = text.substring(0, idx).trim();
        if (trimmed.endsWith(".")) {
            return trimmed;
        }
        return trimmed + ".";
    }

    private static boolean isSubstantiveAnswer(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 18) {
            return false;
        }
        return wordCount(trimmed) >= 4;
    }

    private static TurnResult fallback(String userMessage) {
        if (isTooShort(userMessage)) {
            return new TurnResult(
                    "Give me a little more to work with here.",
                    true);
        }
        return new TurnResult("That gives me something real to work with.", false);
    }

    private static boolean isTooShort(String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (trimmed.length() < 12) {
            return true;
        }
        return wordCount(trimmed) < 3;
    }

    private static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = 0;
        for (String token : text.split("\\s+")) {
            if (!token.isBlank()) {
                words += 1;
            }
        }
        return words;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String jsonQuote(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String collectOutputText(Response resp) {
        if (resp == null || resp.output() == null) {
            return "";
        }
        StringBuilder buf = new StringBuilder();
        for (ResponseOutputItem item : resp.output()) {
            if (item == null) {
                continue;
            }

            Optional<ResponseOutputMessage> msg = item.message();
            if (msg.isEmpty()) {
                continue;
            }
            for (ResponseOutputMessage.Content content : msg.get().content()) {
                if (content == null) {
                    continue;
                }
                Optional<ResponseOutputText> text = content.outputText();
                if (text.isEmpty()) {
                    continue;
                }
                String chunk = text.get().text();
                if (chunk != null) {
                    buf.append(chunk);
                }
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