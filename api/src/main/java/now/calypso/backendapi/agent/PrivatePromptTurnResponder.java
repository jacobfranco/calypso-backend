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
    private static final String[] NEXT_PART_ACK_MESSAGES = {
            "Got it.",
            "Noted.",
            "Makes sense."
    };
    private static final String[] FORWARD_PROMPT_MESSAGES = {
            "Got it. Anything else you want to share? If not, you can press submit.",
            "Understood. Add anything else if you want, or press submit when you're ready.",
            "Noted. Share more if you want, or press submit when you're ready."
    };
    private static final String[] READY_TO_SUBMIT_MESSAGES = {
            "Got it. You can go ahead and press submit.",
            "Understood. You can press submit whenever you're ready."
    };

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
        return null;
    }

    private static boolean mentionsGenericGames(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.contains(" game ") || normalized.startsWith("game ") || normalized.endsWith(" game")
                || normalized.contains(" games ") || normalized.startsWith("games ") || normalized.endsWith(" games")
                || normalized.equals("game") || normalized.equals("games");
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
        boolean hasPendingPromptPart = hasPendingPromptPart(input);
        boolean userReadyToSubmit = !hasPendingPromptPart && isSubmissionIntent(input == null ? null : input.userMessage);
        boolean clarifierRequest = looksLikeClarifierRequest(message);
        boolean needsMore = !userReadyToSubmit && clarifierRequest;
        boolean substantiveUserAnswer = isSubstantiveAnswer(input == null ? null : input.userMessage);
        boolean conciseConcreteUserAnswer = isConciseConcreteAnswer(input == null ? null : input.userMessage);

        if (substantiveUserAnswer && hasGenericWhyClause(message)) {
            message = stripGenericWhyClause(message);
        }
        if (needsMore
                && isGenericWhyQuestion(message)
                && substantiveUserAnswer) {
            needsMore = false;
        }
        if (needsMore && hasPendingPromptPart && substantiveUserAnswer) {
            needsMore = false;
            message = toAcknowledgement(message);
        }
        if (needsMore && containsRewriteOffer(message)) {
            needsMore = false;
        }
        if (needsMore && (substantiveUserAnswer || conciseConcreteUserAnswer) && !requiresForcedClarifier(input)) {
            needsMore = false;
        }

        if (needsMore) {
            message = neutralizeFollowupMessage(message, input);
        } else {
            message = completionMessage(userReadyToSubmit, hasPendingPromptPart, input);
        }
        return new TurnResult(message, needsMore);
    }

    private static boolean hasPendingPromptPart(TurnInput input) {
        if (input == null) {
            return false;
        }
        List<String> parts = splitPromptIntoParts(input.promptText);
        if (parts.size() < 2) {
            return false;
        }
        String current = normalizePartForMatch(input.questionPart);
        if (current.isEmpty()) {
            return false;
        }
        for (int idx = 0; idx < parts.size(); idx++) {
            if (normalizePartForMatch(parts.get(idx)).equals(current)) {
                return idx < (parts.size() - 1);
            }
        }
        return false;
    }

    private static List<String> splitPromptIntoParts(String promptText) {
        String trimmed = safe(promptText);
        if (trimmed.isEmpty()) {
            return List.of();
        }
        List<String> questions = mergeTrailingWhyFragments(splitAndTrim(trimmed, "(?<=\\?)\\s+"));
        if (questions.size() > 1) {
            return questions;
        }
        List<String> sentences = mergeTrailingWhyFragments(splitAndTrim(trimmed, "(?<=\\.)\\s+"));
        if (sentences.size() > 1) {
            return sentences;
        }
        if (!questions.isEmpty()) {
            return questions;
        }
        return List.of(trimmed);
    }

    private static List<String> splitAndTrim(String text, String delimiterRegex) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] raw = text.split(delimiterRegex);
        ArrayList<String> out = new ArrayList<>(raw.length);
        for (String part : raw) {
            String trimmed = safe(part);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> mergeTrailingWhyFragments(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        ArrayList<String> merged = new ArrayList<>(parts.size());
        for (String part : parts) {
            String trimmed = safe(part);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (isStandaloneWhyFragment(trimmed) && !merged.isEmpty()) {
                int lastIdx = merged.size() - 1;
                merged.set(lastIdx, (merged.get(lastIdx) + " " + trimmed).trim());
                continue;
            }
            merged.add(trimmed);
        }
        return merged;
    }

    private static boolean isStandaloneWhyFragment(String part) {
        if (part == null || part.isBlank()) {
            return false;
        }
        String normalized = part.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!normalized.startsWith("why")) {
            return false;
        }
        String wordsOnly = normalized.replaceAll("[^a-z0-9'\\s]", " ").trim();
        if (wordsOnly.isEmpty()) {
            return false;
        }
        return wordCount(wordsOnly) <= 4;
    }

    private static String normalizePartForMatch(String part) {
        return safe(part).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String toAcknowledgement(String message) {
        if (message == null || message.isBlank()) {
            return "That gives me enough to work with.";
        }
        String text = message.trim();
        int questionIdx = text.indexOf('?');
        if (questionIdx < 0) {
            return ensureSentenceEnding(text);
        }
        String beforeQuestion = text.substring(0, questionIdx).trim();
        int sentenceBoundary = Math.max(beforeQuestion.lastIndexOf(". "), beforeQuestion.lastIndexOf("! "));
        if (sentenceBoundary >= 0) {
            String candidate = beforeQuestion.substring(0, sentenceBoundary + 1).trim();
            if (!candidate.isEmpty()) {
                return ensureSentenceEnding(candidate);
            }
        }
        String lowered = beforeQuestion.toLowerCase(Locale.ROOT);
        if (!beforeQuestion.isEmpty()
                && wordCount(beforeQuestion) >= 2
                && !lowered.startsWith("can you")
                && !lowered.startsWith("could you")
                && !lowered.startsWith("do you mean")
                && !lowered.startsWith("what kind")
                && !lowered.startsWith("which kind")
                && !lowered.startsWith("tell me")
                && !lowered.startsWith("share ")) {
            return ensureSentenceEnding(beforeQuestion);
        }
        return "That gives me enough to work with.";
    }

    private static String ensureSentenceEnding(String text) {
        if (text == null || text.isBlank()) {
            return "That gives me enough to work with.";
        }
        String trimmed = text.trim();
        if (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")) {
            return trimmed;
        }
        return trimmed + ".";
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
                "what draws you in",
                "how much does",
                "turn you off",
                "dealbreaker");
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
                    "Can you share a little more detail?",
                    true);
        }
        return new TurnResult(FORWARD_PROMPT_MESSAGES[0], false);
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

    private static boolean isConciseConcreteAnswer(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (containsAny(trimmed.toLowerCase(Locale.ROOT),
                "idk",
                "i don't know",
                "dont know",
                "not sure",
                "maybe",
                "whatever",
                "anything",
                "nothing",
                "n/a")) {
            return false;
        }
        int words = wordCount(trimmed);
        if (words < 1 || words > 5) {
            return false;
        }
        int alphaChars = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetter(trimmed.charAt(i))) {
                alphaChars++;
            }
        }
        return alphaChars >= 4;
    }

    private static boolean requiresForcedClarifier(TurnInput input) {
        if (input == null || input.userMessage == null) {
            return false;
        }
        String userText = input.userMessage.toLowerCase(Locale.ROOT);
        if (!(mentionsGenericGames(userText) && !mentionsSpecificGameType(userText))) {
            return false;
        }
        String question = safe(input.questionPart).toLowerCase(Locale.ROOT);
        return question.contains("what kind of games") || question.contains("which kind of games");
    }

    private static String neutralizeFollowupMessage(String message, TurnInput input) {
        String topic = inferNeutralTopic(input);
        if (topic != null && !topic.isBlank()) {
            return "Got it. How do you feel about " + topic + "?";
        }
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("turn you off")
                || lower.contains("dealbreaker")
                || lower.contains("how much does")
                || lower.contains("how much do you")) {
            return "Got it. How do you feel about that?";
        }
        if (message == null || message.isBlank()) {
            return "Got it. Can you share a little more detail?";
        }
        return "Got it. " + toQuestionSentence(message);
    }

    private static String inferNeutralTopic(TurnInput input) {
        if (input == null || input.questionPart == null) {
            return null;
        }
        String question = input.questionPart.toLowerCase(Locale.ROOT);
        String user = safe(input.userMessage);
        if (user.isBlank()) {
            return null;
        }
        String loweredUser = user.toLowerCase(Locale.ROOT);
        if (containsAny(loweredUser, "idk", "i don't know", "dont know", "not sure", "maybe", "i guess")) {
            return null;
        }
        String cleanedUser = user.replaceAll("[^A-Za-z0-9'\\-\\s]", " ").replaceAll("\\s+", " ").trim();
        if (cleanedUser.isEmpty()) {
            return null;
        }
        if (question.contains("feel about")) {
            return cleanedUser;
        }
        if (question.contains("turn off")
                || question.contains("dealbreaker")
                || question.contains("not my person")
                || question.contains("popular that you don't like")
                || question.contains("popular that you dont like")) {
            return cleanedUser;
        }
        return null;
    }

    private static String toQuestionSentence(String message) {
        if (message == null || message.isBlank()) {
            return "Can you share a little more detail?";
        }
        String trimmed = message.trim();
        int questionIdx = trimmed.indexOf('?');
        if (questionIdx >= 0) {
            String candidate = trimmed.substring(0, questionIdx + 1).trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        if (trimmed.endsWith("?")) {
            return trimmed;
        }
        return "Can you share a little more detail?";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String completionMessage(boolean userReadyToSubmit, boolean hasPendingPromptPart, TurnInput input) {
        if (hasPendingPromptPart) {
            return pickVariant(NEXT_PART_ACK_MESSAGES, input);
        }
        if (userReadyToSubmit) {
            return pickVariant(READY_TO_SUBMIT_MESSAGES, input);
        }
        return pickVariant(FORWARD_PROMPT_MESSAGES, input);
    }

    private static String pickVariant(String[] variants, TurnInput input) {
        if (variants == null || variants.length == 0) {
            return "Got it.";
        }
        if (variants.length == 1) {
            return variants[0];
        }
        int seed = 17;
        if (input != null) {
            seed = 31 * seed + safe(input.promptText).hashCode();
            seed = 31 * seed + safe(input.questionPart).hashCode();
            seed = 31 * seed + safe(input.userMessage).hashCode();
            seed = 31 * seed + (input.conversation == null ? 0 : input.conversation.size());
        }
        int idx = Math.floorMod(seed, variants.length);
        return variants[idx];
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
