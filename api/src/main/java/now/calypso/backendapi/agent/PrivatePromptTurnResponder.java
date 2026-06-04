package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

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
    private static final String FORMATIVE_IMPRINT_PROMPT_ID = "private.formative.imprints";

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
        - Reserved, like a cool friend who is paying attention.
        - Human and plain. Small acknowledgments are enough.
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
        - therapy-ish language
        - exposing your internal reasoning or saying what information you do not have

        Clarification rules:
        - If vague, ask one specific clarifier and set needsMoreDetail=true.
        - If clear, acknowledge and set needsMoreDetail=false.

        Prompt-specific sufficiency:
        - For prompt_id "private.formative.imprints", separate references from imprint.
        - References are childhood titles, objects, places, media, toys, websites, scenes, or memories.
        - The imprint is what those references left the user drawn toward, curious about, or still thinking about later.
        - If references are present but the imprint is missing, set needsMoreDetail=true.
        - In that case, ask one contextual follow-up grounded in the references. Ask about the imprint, not for more references.
        - If the user already gave references, do not ask which thing comes to mind first.
        - If the user asks what you mean or says they do not know how to answer, reframe with concrete lanes like taste, aesthetics, places, culture, mood, or interests. Do not repeat the same wording.
        - Good formative follow-up style: "Okay cool. What part of that stayed with you?"
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
        if (client == null) {
            return sanitizeResult(fallback(input), input);
        }
        try {
            Exception lastError = null;
            for (ChatModel model : OpenAIModelRouter.modelChain(MODEL_ENV, MODEL_DEFAULT)) {
                long startedAt = System.currentTimeMillis();
                String userInput = buildUserInput(input);
                long promptChars = (long) SYSTEM_PROMPT.length() + (long) userInput.length();
                try {
                    ResponseCreateParams params = ResponseCreateParams.builder()
                            .model(model)
                            .instructions(SYSTEM_PROMPT)
                            .input(userInput)
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
                            220L,
                            promptChars);
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
                            ex,
                            promptChars);
                }
            }
            if (lastError != null) {
                LOG.warn("Private turn generation exhausted model chain; using fallback response.");
            }
            return sanitizeResult(fallback(input), input);
        } catch (Exception ex) {
            LOG.warn("Private prompt turn generation failed; using fallback response.", ex);
            return sanitizeResult(fallback(input), input);
        }
    }

    private static String buildUserInput(TurnInput input) {
        String promptText = safe(input.promptText);
        String promptId = safe(input.promptId);
        String questionPart = safe(input.questionPart);
        String userMessage = safe(input.userMessage);
        List<String> conversation = trimConversation(input.conversation);

        StringBuilder buf = new StringBuilder();
        buf.append("prompt_id: ").append(jsonQuote(promptId)).append("\n");
        buf.append("private_prompt: ").append(jsonQuote(promptText)).append("\n");
        buf.append("current_question_part: ").append(jsonQuote(questionPart)).append("\n");
        buf.append("latest_user_message: ").append(jsonQuote(userMessage)).append("\n");
        appendSufficiencyGuidance(buf, input);
        buf.append("conversation:\n");
        int idx = 1;
        for (String line : conversation) {
            buf.append(idx++).append(") ").append(line).append("\n");
        }
        return buf.toString();
    }

    private static void appendSufficiencyGuidance(StringBuilder buf, TurnInput input) {
        PrivatePromptSufficiencyPlanner.SufficiencyPlan plan = PrivatePromptSufficiencyPlanner.plan(input);
        if (plan == null || (plan.complete && plan.guidance.isBlank() && plan.missing.isEmpty())) {
            return;
        }
        buf.append("answer_sufficiency:\n");
        buf.append("- prompt_type: ").append(plan.promptType).append("\n");
        buf.append("- complete: ").append(plan.complete).append("\n");
        buf.append("- needs_more_detail: ").append(plan.needsMoreDetail).append("\n");
        if (plan.strategy != null && !plan.strategy.isBlank()) {
            buf.append("- strategy: ").append(plan.strategy).append("\n");
        }
        if (!plan.missing.isEmpty()) {
            buf.append("- missing: ").append(String.join(", ", plan.missing)).append("\n");
        }
        if (plan.dimensions != null && !plan.dimensions.isEmpty()) {
            for (Map.Entry<String, Object> entry : plan.dimensions.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                buf.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        if (plan.guidance != null && !plan.guidance.isBlank()) {
            buf.append("- guidance: ").append(plan.guidance).append("\n");
        }
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
            return fallback(input);
        }
        String message = raw.agentMessage.trim();
        if (message.length() > 320) {
            message = message.substring(0, 320).trim();
        }
        FormativeSufficiency formative = formativeSufficiency(input);
        boolean formativeRequiredFollowup = formative != null
                && !formative.complete
                && !formative.followupAlreadyAsked;
        boolean formativeReframeFollowup = formative != null
                && !formative.complete
                && formative.followupAlreadyAsked
                && formative.latestNeedsReframe;
        boolean promptSpecificFollowup = formativeRequiredFollowup || formativeReframeFollowup;
        boolean hasPendingPromptPart = hasPendingPromptPart(input);
        boolean userReadyToSubmit = !hasPendingPromptPart && isSubmissionIntent(input == null ? null : input.userMessage);
        boolean clarifierRequest = looksLikeClarifierRequest(message);
        boolean needsMore = !userReadyToSubmit
                && (clarifierRequest || promptSpecificFollowup);
        boolean substantiveUserAnswer = isSubstantiveAnswer(input == null ? null : input.userMessage);
        boolean conciseConcreteUserAnswer = isConciseConcreteAnswer(input == null ? null : input.userMessage);

        if (!promptSpecificFollowup && substantiveUserAnswer && hasGenericWhyClause(message)) {
            message = stripGenericWhyClause(message);
        }
        if (needsMore
                && isGenericWhyQuestion(message)
                && substantiveUserAnswer
                && !promptSpecificFollowup) {
            needsMore = false;
        }
        if (needsMore && hasPendingPromptPart && substantiveUserAnswer && !promptSpecificFollowup) {
            needsMore = false;
            message = toAcknowledgement(message);
        }
        if (needsMore && containsRewriteOffer(message) && !promptSpecificFollowup) {
            needsMore = false;
        }
        if (needsMore && substantiveUserAnswer && !promptSpecificFollowup) {
            needsMore = false;
        }
        if (needsMore
                && conciseConcreteUserAnswer
                && !isVeryShortAnswer(input == null ? null : input.userMessage)
                && !promptSpecificFollowup) {
            needsMore = false;
        }

        if (needsMore) {
            if (promptSpecificFollowup) {
                message = neutralizeFormativeFollowupMessage(message, input, formative);
            } else {
                message = neutralizeFollowupMessage(message, input);
            }
        } else if (shouldReplaceCompletionMessage(message, input, userReadyToSubmit, hasPendingPromptPart)) {
            message = completionMessage(userReadyToSubmit, hasPendingPromptPart, input);
        } else {
            message = completionMessageFromModel(message, userReadyToSubmit, hasPendingPromptPart, input);
        }
        return new TurnResult(message, needsMore);
    }

    private static FormativeSufficiency formativeSufficiency(TurnInput input) {
        if (!isFormativeImprintPrompt(input)) {
            return null;
        }
        String userText = combinedUserText(input);
        boolean followupAlreadyAsked = formativeFollowupAlreadyAsked(input);
        boolean latestNeedsReframe = latestNeedsFormativeReframe(input);
        boolean hasReference = hasFormativeReference(userText);
        if (isSubmissionIntent(userText)) {
            return new FormativeSufficiency(
                    true,
                    hasReference,
                    true,
                    followupAlreadyAsked,
                    latestNeedsReframe);
        }
        boolean hasImprint = hasReference && hasFormativeImprint(userText);
        return new FormativeSufficiency(
                hasReference && hasImprint,
                hasReference,
                hasImprint,
                followupAlreadyAsked,
                latestNeedsReframe);
    }

    private static boolean isFormativeImprintPrompt(TurnInput input) {
        if (input == null) {
            return false;
        }
        String promptId = safe(input.promptId).toLowerCase(Locale.ROOT);
        if (FORMATIVE_IMPRINT_PROMPT_ID.equals(promptId)) {
            return true;
        }
        String prompt = safe(input.promptText).toLowerCase(Locale.ROOT);
        return prompt.contains("growing up") && prompt.contains("nostalgia");
    }

    private static String combinedUserText(TurnInput input) {
        if (input == null) {
            return "";
        }
        ArrayList<String> parts = new ArrayList<>();
        if (input.conversation != null) {
            for (String line : input.conversation) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                String lowered = trimmed.toLowerCase(Locale.ROOT);
                if (lowered.startsWith("user:")) {
                    parts.add(trimmed.substring(5).trim());
                }
            }
        }
        String latest = safe(input.userMessage);
        if (!latest.isBlank()) {
            parts.add(latest);
        }
        return String.join(" ", parts).trim();
    }

    private static boolean formativeFollowupAlreadyAsked(TurnInput input) {
        if (input == null || input.conversation == null) {
            return false;
        }
        boolean seenUserAnswer = false;
        for (String line : input.conversation) {
            String lowered = line == null ? "" : line.toLowerCase(Locale.ROOT);
            if (lowered.startsWith("user:")) {
                String user = line == null ? "" : line.substring(Math.min(5, line.length())).trim();
                if (hasFormativeReference(user) || isSubstantiveAnswer(user)) {
                    seenUserAnswer = true;
                }
                continue;
            }
            if (seenUserAnswer && lowered.startsWith("agent:") && lowered.contains("?")) {
                return true;
            }
        }
        return false;
    }

    private static boolean latestNeedsFormativeReframe(TurnInput input) {
        if (input == null || input.userMessage == null) {
            return false;
        }
        String normalized = input.userMessage.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return containsAny(normalized,
                "what do you mean",
                "what does that mean",
                "what are you asking",
                "how do i answer",
                "how should i answer",
                "idk how",
                "i don't know how",
                "i dont know how",
                "dont know how",
                "not sure how");
    }

    private static boolean hasFormativeReference(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return false;
        }
        if (isFormativeMetaClarificationOnly(normalized)) {
            return false;
        }
        String withoutUncertainty = normalized
                .replace("i don't know", " ")
                .replace("i dont know", " ")
                .replace("don't know", " ")
                .replace("dont know", " ")
                .replace("not sure", " ")
                .replace("nothing comes to mind", " ")
                .replace("nothing really", " ")
                .replaceAll("\\b(idk|nothing|maybe|like|lowkey|honestly|uh|um|hmm|exactly)\\b", " ")
                .replaceAll("\\b(i|i'm|im|am|just|really|kind|sort|of|a|the|this|that|it|how|should|do|does|are|you|mean|asking|answer)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return wordCount(withoutUncertainty) >= 1;
    }

    private static boolean isFormativeMetaClarificationOnly(String normalized) {
        if (normalized == null || normalized.isBlank() || wordCount(normalized) > 10) {
            return false;
        }
        return containsAny(normalized,
                "what do you mean",
                "what does that mean",
                "what are you asking",
                "how do i answer",
                "how should i answer",
                "idk how",
                "i don't know how",
                "i dont know how",
                "dont know how",
                "not sure how");
    }

    private static boolean hasFormativeImprint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized,
                "made me interested in",
                "made me curious about",
                "got me into",
                "got me interested",
                "left me with",
                "stuck with me because",
                "shaped my",
                "shaped me",
                "influenced my",
                "led me to",
                "led to",
                "sparked",
                "i still",
                "now i",
                "as an adult",
                "my taste",
                "my aesthetics",
                "my aesthetic",
                "asian culture",
                "visual culture",
                "international travel",
                "other countries",
                "lived culture",
                "ordinary culture",
                "mundane",
                "everyday",
                "aesthetics",
                "curiosity",
                "curious about",
                "drawn to",
                "it taught me",
                "they taught me");
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
                return idx < (parts.size() - 1)
                        && !substantiveAnswerCanCoverRemainingParts(input.userMessage);
            }
        }
        return false;
    }

    private static boolean substantiveAnswerCanCoverRemainingParts(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.length() >= 140 || wordCount(trimmed) >= 24;
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

    private static TurnResult fallback(TurnInput input) {
        String userMessage = input == null ? null : input.userMessage;
        if (isTooShort(userMessage)) {
            return new TurnResult(
                    contextualDetailRequest(input),
                    true);
        }
        return new TurnResult(completionMessage(false, hasPendingPromptPart(input), input), false);
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

    private static boolean isVeryShortAnswer(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return wordCount(text.trim()) <= 1;
    }

    private static String neutralizeFormativeFollowupMessage(
            String message,
            TurnInput input,
            FormativeSufficiency formative) {
        String question = toQuestionSentence(message);
        if (isUnhelpfulFormativeFollowup(question) || repeatsPriorAgentQuestion(question, input)) {
            return fallbackFormativeFollowup(formative);
        }
        return question;
    }

    private static boolean isUnhelpfulFormativeFollowup(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String text = message.toLowerCase(Locale.ROOT);
        if (!text.contains("?")) {
            return true;
        }
        if (isGenericWhyQuestion(message)
                || containsAny(text,
                        "what thing comes to mind first",
                        "what comes to mind first",
                        "which thing comes to mind first",
                        "which one comes to mind first",
                        "first thing that comes to mind",
                        "i do not have",
                        "i don't have",
                        "i dont have",
                        "missing the part",
                        "part that stayed with you is missing")) {
            return true;
        }
        return containsAny(text,
                "can you share a little more detail",
                "could you share a little more detail",
                "share a little more detail",
                "tell me more",
                "share more",
                "anything else you want to share")
                && !hasFormativeFollowupCue(text);
    }

    private static boolean hasFormativeFollowupCue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return containsAny(text,
                "left",
                "drawn",
                "curious",
                "taste",
                "aesthetic",
                "culture",
                "place",
                "mood",
                "interest",
                "shaped",
                "affected",
                "feel",
                "feeling",
                "bring back",
                "later");
    }

    private static boolean repeatsPriorAgentQuestion(String message, TurnInput input) {
        if (input == null || input.conversation == null) {
            return false;
        }
        String current = normalizeQuestionForRepeat(message);
        if (current.isBlank()) {
            return false;
        }
        for (String line : input.conversation) {
            if (line == null) {
                continue;
            }
            String lowered = line.toLowerCase(Locale.ROOT).trim();
            if (!lowered.startsWith("agent:")) {
                continue;
            }
            String prior = line.substring(Math.min(6, line.length())).trim();
            if (current.equals(normalizeQuestionForRepeat(prior))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeQuestionForRepeat(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return toQuestionSentence(message)
                .toLowerCase(Locale.ROOT)
                .replaceAll("^(got it|understood|noted)[,.]?\\s+", "")
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String fallbackFormativeFollowup(FormativeSufficiency formative) {
        if (formative != null && formative.latestNeedsReframe && formative.hasReference) {
            return "Think of it as taste, places, mood, culture, or interests. Which part of those references stayed with you?";
        }
        if (formative != null && formative.latestNeedsReframe) {
            return "Think of something from growing up that still points to a taste, place, mood, culture, or interest. What memory fits that?";
        }
        if (formative != null && formative.hasReference) {
            return "Okay cool. What part of that stayed with you?";
        }
        return "What from that time still has a hold on you?";
    }

    private static String neutralizeFollowupMessage(String message, TurnInput input) {
        String topic = inferNeutralTopic(input);
        if (topic != null && !topic.isBlank()) {
            return "How do you feel about " + topic + "?";
        }
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.contains("turn you off")
                || lower.contains("dealbreaker")
                || lower.contains("how much does")
                || lower.contains("how much do you")) {
            return "How do you feel about that?";
        }
        if (message == null || message.isBlank()) {
            return contextualDetailRequest(input);
        }
        return toQuestionSentence(message);
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
            return contextualNextPartAcknowledgement(input);
        }
        if (userReadyToSubmit) {
            return contextualReadyToSubmitMessage(input);
        }
        return contextualForwardPromptMessage(input);
    }

    private static String completionMessageFromModel(
            String message,
            boolean userReadyToSubmit,
            boolean hasPendingPromptPart,
            TurnInput input) {
        if (hasPendingPromptPart) {
            return stripSubmitCue(ensureSentenceEnding(message));
        }
        if (containsSubmitCue(message)) {
            return ensureSentenceEnding(message);
        }
        String suffix = userReadyToSubmit
                ? "You can press submit now."
                : submitSuffix(input);
        return ensureSentenceEnding(message) + " " + suffix;
    }

    private static boolean shouldReplaceCompletionMessage(
            String message,
            TurnInput input,
            boolean userReadyToSubmit,
            boolean hasPendingPromptPart) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String normalized = message.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("?") && !containsSubmitCue(normalized)) {
            return true;
        }
        if (isGenericAcknowledgement(normalized)) {
            return true;
        }
        if (containsRewriteOffer(message)) {
            return true;
        }
        if (hasPendingPromptPart && containsSubmitCue(normalized)) {
            return true;
        }
        if (!hasPendingPromptPart && !userReadyToSubmit && parrotsUserAnswer(message, input == null ? null : input.userMessage)) {
            return true;
        }
        return false;
    }

    private static boolean isGenericAcknowledgement(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return true;
        }
        String text = normalized.replaceAll("[.!\\s]+$", "").trim();
        return text.equals("got it")
                || text.equals("noted")
                || text.equals("makes sense")
                || text.equals("understood")
                || text.equals("that makes sense")
                || text.equals("that tracks")
                || text.equals("that works")
                || text.equals("sounds good");
    }

    private static boolean parrotsUserAnswer(String message, String userMessage) {
        if (message == null || userMessage == null) {
            return false;
        }
        String normalizedMessage = normalizeForParrotCheck(message);
        String normalizedUser = normalizeForParrotCheck(userMessage);
        if (normalizedMessage.isBlank() || normalizedUser.isBlank() || normalizedUser.length() < 18) {
            return false;
        }
        if (normalizedMessage.contains(normalizedUser)) {
            return true;
        }
        String[] userWords = normalizedUser.split("\\s+");
        if (userWords.length < 4) {
            return false;
        }
        int overlappingContentWords = 0;
        for (String word : userWords) {
            if (word.length() < 5 || isParrotStopword(word)) {
                continue;
            }
            if (normalizedMessage.matches(".*\\b" + Pattern.quote(word) + "\\b.*")) {
                overlappingContentWords += 1;
            }
        }
        return overlappingContentWords >= 3;
    }

    private static String normalizeForParrotCheck(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isParrotStopword(String word) {
        return switch (word) {
            case "about", "after", "again", "because", "could", "their", "there", "these", "those",
                    "thing", "things", "would", "really", "maybe", "exactly", "answer", "share",
                    "submit", "ready" -> true;
            default -> false;
        };
    }

    private static boolean containsSubmitCue(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("submit") || normalized.contains("send this");
    }

    private static String stripSubmitCue(String message) {
        if (message == null || message.isBlank()) {
            return contextualNextPartAcknowledgement(null);
        }
        String cleaned = message.replaceAll("(?i)\\s*(?:you can|go ahead and|feel free to)?\\s*press submit(?: whenever you're ready| when you're ready| now)?[.!]?", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return contextualNextPartAcknowledgement(null);
        }
        return ensureSentenceEnding(cleaned);
    }

    private static String contextualDetailRequest(TurnInput input) {
        if (isFormativeImprintPrompt(input)) {
            return fallbackFormativeFollowup(formativeSufficiency(input));
        }
        String topic = inferNeutralTopic(input);
        if (topic != null && !topic.isBlank()) {
            return "What part of " + topic + " matters most here?";
        }
        return "Can you share a little more detail?";
    }

    private static String contextualNextPartAcknowledgement(TurnInput input) {
        String category = promptCategory(input);
        return switch (category) {
            case "formative" -> "That thread has enough shape.";
            case "boundary" -> "That boundary is clear enough.";
            case "drawn" -> "The pull is clear enough.";
            case "community" -> "That social-world piece is clear.";
            case "home" -> "That place-feeling is clear.";
            case "hobby" -> "That hobby piece is clear.";
            case "humor" -> "That humor thread is clear.";
            case "repair" -> "That gives the repair rhythm some shape.";
            default -> switch (variantIndex(input, 3)) {
                case 0 -> "That part has enough shape.";
                case 1 -> "There is enough there for this part.";
                default -> "That gives this part a clear enough read.";
            };
        };
    }

    private static String contextualReadyToSubmitMessage(TurnInput input) {
        return switch (promptCategory(input)) {
            case "formative" -> "That gives the imprint enough shape. You can press submit now.";
            case "boundary" -> "That boundary is clear enough to use. You can press submit now.";
            case "drawn" -> "The pull is clear enough to use. You can press submit now.";
            case "community" -> "That social-world thread is clear enough to use. You can press submit now.";
            case "home" -> "That place-feeling is clear enough to use. You can press submit now.";
            case "hobby" -> "That gives the hobby rhythm enough shape. You can press submit now.";
            case "humor" -> "That humor thread is clear enough to use. You can press submit now.";
            case "repair" -> "That repair rhythm is clear enough to use. You can press submit now.";
            default -> "That gives the answer enough shape. You can press submit now.";
        };
    }

    private static String contextualForwardPromptMessage(TurnInput input) {
        return switch (promptCategory(input)) {
            case "formative" -> formativeForwardPromptMessage(input);
            case "boundary" -> "That boundary is clear enough to use. Add any last angle if it matters, or press submit when you're ready.";
            case "drawn" -> "The pull is clear enough to use. Add any last detail if it changes the shape, or press submit when you're ready.";
            case "community" -> "That social-world thread is clear enough to use. Add another detail if it matters, or press submit when you're ready.";
            case "home" -> "That place-feeling is clear enough to use. Add any last texture if it matters, or press submit when you're ready.";
            case "hobby" -> "That gives a clear read on how you spend your time. Add another detail if it matters, or press submit when you're ready.";
            case "humor" -> "That humor thread is clear enough to use. Add another beat if it matters, or press submit when you're ready.";
            case "repair" -> "That gives the repair rhythm enough shape. Add anything important if it is missing, or press submit when you're ready.";
            default -> defaultForwardPromptMessage(input);
        };
    }

    private static String formativeForwardPromptMessage(TurnInput input) {
        String answer = safe(input == null ? null : input.userMessage).toLowerCase(Locale.ROOT);
        if (containsAny(answer, "travel", "international", "countries", "country", "secret agent", "spy")) {
            return "The world-travel thread has shape now. Add any last detail if it matters, or press submit when you're ready.";
        }
        if (containsAny(answer, "aesthetic", "aesthetics", "asian", "eastern", "japanese", "surreal", "playful")) {
            return "The taste-world part is coming through. Add any last detail if it matters, or press submit when you're ready.";
        }
        return "The reference and what it left behind are both coming through. Add any last thread if there is one, or press submit when you're ready.";
    }

    private static String defaultForwardPromptMessage(TurnInput input) {
        return switch (variantIndex(input, 3)) {
            case 0 -> "That gives this answer enough shape. Add any last detail if it feels important, or press submit when you're ready.";
            case 1 -> "There is enough here to use. Add one more detail if it changes the answer, or press submit when you're ready.";
            default -> "This has enough shape for now. Add anything important if it is missing, or press submit when you're ready.";
        };
    }

    private static String submitSuffix(TurnInput input) {
        return switch (promptCategory(input)) {
            case "formative" -> "Add any last thread if there is one, or press submit when you're ready.";
            case "boundary" -> "Add any last angle if it matters, or press submit when you're ready.";
            case "drawn" -> "Add any last detail if it changes the shape, or press submit when you're ready.";
            case "hobby" -> "Add another detail if it matters, or press submit when you're ready.";
            default -> "Add anything important if it is missing, or press submit when you're ready.";
        };
    }

    private static String promptCategory(TurnInput input) {
        String promptId = safe(input == null ? null : input.promptId).toLowerCase(Locale.ROOT);
        String prompt = (safe(input == null ? null : input.promptText) + " "
                + safe(input == null ? null : input.questionPart)).toLowerCase(Locale.ROOT);
        if (FORMATIVE_IMPRINT_PROMPT_ID.equals(promptId)
                || (prompt.contains("growing up") && prompt.contains("nostalgia"))) {
            return "formative";
        }
        if (promptId.contains("popular.dislike") || promptId.contains("not.my.person")
                || containsAny(prompt, "don't like", "dont like", "turn off", "not my person", "dealbreaker")) {
            return "boundary";
        }
        if (promptId.contains("drawn.to") || promptId.contains("fictional.characters")
                || containsAny(prompt, "drawn to", "attracted", "romantic", "chemistry")) {
            return "drawn";
        }
        if (promptId.contains("communities") || containsAny(prompt, "communities", "scenes", "social world")) {
            return "community";
        }
        if (promptId.contains("places.home") || promptId.contains("home.texture")
                || containsAny(prompt, "home", "place", "room", "city")) {
            return "home";
        }
        if (promptId.contains("hobbies") || containsAny(prompt, "hobby", "hobbies", "free time")) {
            return "hobby";
        }
        if (promptId.contains("laugh") || promptId.contains("humor") || containsAny(prompt, "laugh", "humor", "funny")) {
            return "humor";
        }
        if (promptId.contains("repair") || promptId.contains("stuck.with")
                || containsAny(prompt, "repair", "conflict", "hard moment", "stuck with")) {
            return "repair";
        }
        return "general";
    }

    private static int variantIndex(TurnInput input, int count) {
        if (count <= 1) {
            return 0;
        }
        int seed = 17;
        if (input != null) {
            seed = 31 * seed + safe(input.promptText).hashCode();
            seed = 31 * seed + safe(input.questionPart).hashCode();
            seed = 31 * seed + safe(input.userMessage).hashCode();
            seed = 31 * seed + (input.conversation == null ? 0 : input.conversation.size());
        }
        return Math.floorMod(seed, count);
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
        public final String promptId;
        public final String promptText;
        public final String questionPart;
        public final List<String> conversation;
        public final String userMessage;

        public TurnInput(String promptText, String questionPart, List<String> conversation, String userMessage) {
            this(null, promptText, questionPart, conversation, userMessage);
        }

        public TurnInput(String promptId, String promptText, String questionPart, List<String> conversation, String userMessage) {
            this.promptId = promptId;
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

    private static final class FormativeSufficiency {
        final boolean complete;
        final boolean hasReference;
        final boolean hasImprint;
        final boolean followupAlreadyAsked;
        final boolean latestNeedsReframe;

        FormativeSufficiency(
                boolean complete,
                boolean hasReference,
                boolean hasImprint,
                boolean followupAlreadyAsked,
                boolean latestNeedsReframe) {
            this.complete = complete;
            this.hasReference = hasReference;
            this.hasImprint = hasImprint;
            this.followupAlreadyAsked = followupAlreadyAsked;
            this.latestNeedsReframe = latestNeedsReframe;
        }
    }
}
