package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.llm.OpenAIJson;

public final class SignalExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Tunables
    private static final int CHUNK_MAX_CHARS = 800;
    private static final int PER_CALL_MAX = 12;
    private static final int PER_CHUNK_PASSES = 3;
    private static final int SPECIFICITY_ENRICH_MAX = 4;
    private static final int GLOBAL_SOFT_CAP = 200;
    private static final double FORCED_MENTION_VALENCE = 0.52;
    private static final double EXPLICIT_ANSWER_FLOOR_YAP_HOURS = 0.68;
    private static final double EXPLICIT_ANSWER_FLOOR_TALK_HOURS = 0.62;
    private static final double NEGATIVE_PREFERENCE_MIN_MAGNITUDE = 0.45;

    private SignalExtractor() {
    }

    public static List<ExtractedSignal> extractFreeform(OpenAIClient openAI, String text) {
        if (text == null || text.isBlank())
            return List.of();

        List<String> chunks = chunk(text, CHUNK_MAX_CHARS);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();

        for (String c : chunks) {
            for (int i = 0; i < PER_CHUNK_PASSES && acc.size() < GLOBAL_SOFT_CAP; i++) {
                List<String> already = tokens(acc.values());
                List<ExtractedSignal> raw = call(openAI, SignalPrompts.FREEFORM_SYSTEM_PROMPT,
                        SignalPrompts.freeformUserPrompt(c, already),
                        Math.min(PER_CALL_MAX, GLOBAL_SOFT_CAP - acc.size()),
                        "freeform",
                        null);

                boolean any = merge(acc, raw);
                if (!any)
                    break;
            }
            if (acc.size() >= GLOBAL_SOFT_CAP)
                break;
        }

        return cleanupSpecificityConflicts(new ArrayList<>(acc.values()));
    }

    public static List<ExtractedSignal> extractFromAgentConversation(OpenAIClient openAI, List<String> conversation,
            Collection<String> alreadyHave) {
        if (conversation == null || conversation.isEmpty())
            return List.of();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        List<String> normalizedConversation = normalizeConversation(conversation);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        List<ExtractedSignal> raw = call(openAI, SignalPrompts.AGENT_CHAT_SYSTEM_PROMPT,
                SignalPrompts.agentChatUserPrompt(normalizedConversation, normalizedAlreadyHave),
                PER_CALL_MAX,
                "agent_chat",
                null);
        merge(acc, raw);
        return cleanupSpecificityConflicts(filtered(acc.values(), normalizedAlreadyHave));
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String question, String answer,
            Collection<String> alreadyHave) {
        return extractFromPromptAnswer(openAI, null, question, answer, List.of(), alreadyHave);
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String promptId, String question,
            String answer, Collection<String> alreadyHave) {
        return extractFromPromptAnswer(openAI, promptId, question, answer, List.of(), alreadyHave);
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String question, String answer,
            Collection<String> conversationLines, Collection<String> alreadyHave) {
        return extractFromPromptAnswer(openAI, null, question, answer, conversationLines, alreadyHave);
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String promptId, String question,
            String answer, Collection<String> conversationLines, Collection<String> alreadyHave) {
        if ((question == null || question.isBlank()) && (answer == null || answer.isBlank()))
            return List.of();

        PromptSignalProfiles.PromptSignalProfile profile = PromptSignalProfiles.forPromptId(promptId);
        String profileHint = profile.extractionHint();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        List<String> normalizedConversation = normalizeConversation(conversationLines);

        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        int promptPasses = profile.promptPasses();
        int maxPerCall = Math.min(PER_CALL_MAX, profile.maxSignals());
        for (int i = 0; i < promptPasses; i++) {
            ArrayList<String> already = new ArrayList<>(normalizedAlreadyHave);
            already.addAll(tokens(acc.values()));
            List<ExtractedSignal> raw = call(openAI, SignalPrompts.PROMPT_RESPONSE_SYSTEM_PROMPT,
                    SignalPrompts.promptResponseUserPrompt(promptId, profileHint, question, answer,
                            normalizedConversation, already),
                    maxPerCall,
                    "prompt_response",
                    promptId);
            boolean any = merge(acc, raw);
            if (!any)
                break;
        }

        injectExplicitTitleMentions(promptId, question, answer, normalizedAlreadyHave, acc);
        injectExplicitCanonicalMentions(promptId, question, answer, normalizedAlreadyHave, acc);

        List<ExtractedSignal> filtered = filtered(acc.values(), normalizedAlreadyHave);
        List<ExtractedSignal> adjusted = applyPromptContextAdjustments(question, answer, normalizedConversation, filtered);
        List<ExtractedSignal> enriched = profile.runSpecificityPass()
                ? enrichPromptSpecificity(openAI, promptId, profileHint, question, answer, normalizedConversation,
                        normalizedAlreadyHave, adjusted, Math.min(SPECIFICITY_ENRICH_MAX, profile.maxSignals()))
                : cleanupSpecificityConflicts(adjusted);
        // Re-apply context guards after enrichment so specificity pass cannot reintroduce
        // broad negatives that should be suppressed for the current prompt framing.
        List<ExtractedSignal> finalAdjusted = applyPromptContextAdjustments(
                question,
                answer,
                normalizedConversation,
                enriched);
        return applyPromptPostProcessing(promptId, question, answer, cleanupSpecificityConflicts(finalAdjusted));
    }

    public static List<ExtractedSignal> augmentWithExplicitTitleMentions(
            String promptId,
            String question,
            String answer,
            Collection<String> alreadyHave,
            List<ExtractedSignal> baseSignals) {
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        if (baseSignals != null && !baseSignals.isEmpty()) {
            merge(acc, baseSignals);
        }
        injectExplicitTitleMentions(promptId, question, answer, normalizedAlreadyHave, acc);
        injectExplicitCanonicalMentions(promptId, question, answer, normalizedAlreadyHave, acc);
        return applyPromptPostProcessing(
                promptId,
                question,
                answer,
                cleanupSpecificityConflicts(filtered(acc.values(), normalizedAlreadyHave)));
    }

    private static void injectExplicitTitleMentions(
            String promptId,
            String question,
            String answer,
            Collection<String> alreadyHave,
            LinkedHashMap<String, ExtractedSignal> acc) {
        if (acc == null || !isTitleInjectionPrompt(promptId) || answer == null || answer.isBlank()) {
            return;
        }
        LinkedHashSet<String> mentions = detectExplicitTitleMentions(answer, 4);
        if (mentions.isEmpty()) {
            return;
        }
        HashSet<String> seen = new HashSet<>();
        if (alreadyHave != null) {
            seen.addAll(alreadyHave);
        }
        seen.addAll(tokens(acc.values()));
        SignalIntent intent = defaultTitleIntent(promptId, question, answer);
        double valence = isNegativePreferenceContext(question, answer, List.of())
                ? -FORCED_MENTION_VALENCE
                : FORCED_MENTION_VALENCE;
        int injected = 0;
        for (String token : mentions) {
            if (token == null || token.isBlank() || seen.contains(token)) {
                continue;
            }
            ExtractedSignal forced = ExtractedSignal.from(token, intent, valence);
            if (forced == null) {
                continue;
            }
            merge(acc, List.of(forced));
            seen.add(token);
            injected += 1;
            if (injected >= 3) {
                break;
            }
        }
    }

    private static final Set<String> SIGNAL_FIRST_PRIVATE_PROMPTS = Set.of(
            "private.hobbies",
            "private.communities.scene",
            "private.great.night",
            "private.places.home");

    private static final Set<String> CANONICAL_MENTION_INJECTION_PROMPTS = Set.of(
            "private.hobbies",
            "private.communities.scene",
            "private.great.night",
            "private.places.home",
            "private.rabbit.hole",
            "private.popular.dislike",
            "private.not.my.person",
            "private.drawn.to",
            "private.fictional.characters",
            "private.media.revisit",
            "matchmaking.followup",
            "private.matchmaking.followup");

    private static void injectExplicitCanonicalMentions(
            String promptId,
            String question,
            String answer,
            Collection<String> alreadyHave,
            LinkedHashMap<String, ExtractedSignal> acc) {
        if (acc == null || answer == null || answer.isBlank()) {
            return;
        }
        String normalizedPromptId = promptId == null ? null : promptId.trim().toLowerCase(Locale.ROOT);
        if (normalizedPromptId == null || !CANONICAL_MENTION_INJECTION_PROMPTS.contains(normalizedPromptId)) {
            return;
        }
        boolean concreteOnly = normalizedPromptId != null && SIGNAL_FIRST_PRIVATE_PROMPTS.contains(normalizedPromptId);
        LinkedHashSet<String> mentions = detectExplicitCanonicalMentions(answer, 5, concreteOnly);
        if (mentions.isEmpty()) {
            return;
        }
        HashSet<String> seen = new HashSet<>();
        if (alreadyHave != null) {
            seen.addAll(alreadyHave);
        }
        seen.addAll(tokens(acc.values()));
        SignalIntent intent = defaultTitleIntent(promptId, question, answer);
        double valence = isNegativePreferenceContext(question, answer, List.of())
                ? -FORCED_MENTION_VALENCE
                : FORCED_MENTION_VALENCE;
        int injected = 0;
        for (String token : mentions) {
            if (token == null || token.isBlank() || seen.contains(token)) {
                continue;
            }
            ExtractedSignal forced = ExtractedSignal.from(token, intent, valence);
            if (forced == null) {
                continue;
            }
            merge(acc, List.of(forced));
            seen.add(token);
            injected += 1;
            if (injected >= 3) {
                break;
            }
        }
    }

    private static boolean isTitleInjectionPrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return false;
        }
        String normalized = promptId.trim().toLowerCase(Locale.ROOT);
        return "private.drawn.to".equals(normalized)
                || "private.fictional.characters".equals(normalized)
                || "private.media.revisit".equals(normalized);
    }

    private static SignalIntent defaultTitleIntent(String promptId, String question, String answer) {
        String normalized = promptId == null || promptId.isBlank() ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        if ("private.drawn.to".equals(normalized)
                || "private.not.my.person".equals(normalized)
                || "private.popular.dislike".equals(normalized)
                || "matchmaking.followup".equals(normalized)
                || "private.matchmaking.followup".equals(normalized)) {
            return SignalIntent.SEEKING;
        }
        if ("private.fictional.characters".equals(normalized)) {
            String context = combinedLowerText(question, answer, List.of());
            boolean selfCue = containsAny(
                    context,
                    "relate to",
                    "related to",
                    "i relate",
                    "see myself",
                    "like me",
                    "i'm like",
                    "im like");
            boolean seekingCue = containsAny(
                    context,
                    "drawn to",
                    "attracted",
                    "pulls you in",
                    "romantically",
                    "into them");
            if (selfCue && seekingCue) {
                return SignalIntent.BOTH;
            }
            if (selfCue) {
                return SignalIntent.SELF;
            }
            if (seekingCue) {
                return SignalIntent.SEEKING;
            }
            return SignalIntent.BOTH;
        }
        if (question != null
                && question.toLowerCase(Locale.ROOT).contains("share with your partner")) {
            return SignalIntent.BOTH;
        }
        return SignalIntent.SELF;
    }

    private static LinkedHashSet<String> detectExplicitTitleMentions(String text, int maxMentions) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return out;
        }
        String[] rawWords = cleaned.split("\\s+");
        ArrayList<String> words = new ArrayList<>(rawWords.length);
        for (String word : rawWords) {
            if (word == null || word.isBlank() || word.length() < 2) {
                continue;
            }
            words.add(word);
        }
        int boundedMax = Math.max(1, maxMentions);
        for (int i = 0; i < words.size(); i++) {
            for (int n = 1; n <= 4; n++) {
                int end = i + n;
                if (end > words.size()) {
                    break;
                }
                String candidate = String.join("_", words.subList(i, end));
                SignalConceptRegistry.Resolution resolution = SignalConceptRegistry.resolve(candidate);
                if (resolution == null || resolution.kind() == SignalConceptRegistry.ResolutionKind.UNKNOWN) {
                    continue;
                }
                String canonical = resolution.canonicalToken();
                if (!looksLikeExplicitTitleToken(canonical)) {
                    continue;
                }
                out.add(canonical);
                if (out.size() >= boundedMax) {
                    return out;
                }
            }
        }
        return out;
    }

    private static LinkedHashSet<String> detectExplicitCanonicalMentions(
            String text,
            int maxMentions,
            boolean concreteOnly) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String cleaned = text.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return out;
        }
        String[] rawWords = cleaned.split("\\s+");
        ArrayList<String> words = new ArrayList<>(rawWords.length);
        for (String word : rawWords) {
            if (word == null || word.isBlank() || word.length() < 2) {
                continue;
            }
            words.add(word);
        }
        int boundedMax = Math.max(1, maxMentions);
        for (int i = 0; i < words.size(); i++) {
            for (int n = 1; n <= 4; n++) {
                int end = i + n;
                if (end > words.size()) {
                    break;
                }
                String candidate = String.join("_", words.subList(i, end));
                SignalConceptRegistry.Resolution resolution = SignalConceptRegistry.resolve(candidate);
                if (resolution == null || resolution.kind() == SignalConceptRegistry.ResolutionKind.UNKNOWN) {
                    continue;
                }
                String canonical = SignalNormalizer.normalizeOne(resolution.canonicalToken());
                if (canonical == null || canonical.isBlank()) {
                    continue;
                }
                if (concreteOnly && !SignalTaxonomy.isConcreteCategory(SignalConceptRegistry.categoryForConcept(canonical))) {
                    continue;
                }
                out.add(canonical);
                if (out.size() >= boundedMax) {
                    return out;
                }
            }
        }
        return out;
    }

    private static boolean looksLikeExplicitTitleToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("_") && normalized.length() >= 6;
    }

    private static List<String> chunk(String text, int maxChars) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty())
            return List.of();
        if (trimmed.length() <= maxChars)
            return List.of(trimmed);
        List<String> out = new ArrayList<>();
        String[] sentences = trimmed.split("(?<=[.!?])\\s+");
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() + s.length() + 1 > maxChars) {
                if (buf.length() > 0) {
                    out.add(buf.toString());
                    buf.setLength(0);
                }
            }
            if (s.length() > maxChars) {
                for (int i = 0; i < s.length(); i += maxChars)
                    out.add(s.substring(i, Math.min(s.length(), i + maxChars)));
            } else {
                if (buf.length() > 0)
                    buf.append(' ');
                buf.append(s);
            }
        }
        if (buf.length() > 0)
            out.add(buf.toString());
        return out;
    }

    private static List<ExtractedSignal> filtered(Collection<ExtractedSignal> signals,
            Collection<String> alreadyHave) {
        if (signals == null || signals.isEmpty())
            return List.of();
        if (alreadyHave == null || alreadyHave.isEmpty())
            return new ArrayList<>(signals);
        List<ExtractedSignal> filtered = new ArrayList<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null)
                continue;
            if (!alreadyHave.contains(sig.token()))
                filtered.add(sig);
        }
        return filtered;
    }

    /**
     * Merge normalized signals into acc, keyed by intent+token.
     *
     * BOTH is expanded into SELF + SEEKING so each side can evolve independently
     * over time.
     */
    private static boolean merge(LinkedHashMap<String, ExtractedSignal> acc, List<ExtractedSignal> raw) {
        boolean any = false;
        if (raw == null)
            return false;

        for (ExtractedSignal sig : raw) {
            if (sig == null || sig.token() == null)
                continue;

            String token = sig.token();
            SignalIntent intent = sig.intent() == null ? SignalIntent.SELF : sig.intent();
            if (intent == SignalIntent.BOTH) {
                any |= putSignal(acc, token, SignalIntent.SELF, sig);
                any |= putSignal(acc, token, SignalIntent.SEEKING, sig);
                continue;
            }
            any |= putSignal(acc, token, intent, sig);
        }

        return any;
    }

    private static boolean putSignal(LinkedHashMap<String, ExtractedSignal> acc, String token, SignalIntent intent,
            ExtractedSignal source) {
        if (token == null || token.isBlank() || intent == null || source == null)
            return false;
        String signalKey = key(token, intent);
        ExtractedSignal out = source.intent() == intent
                ? source
                : ExtractedSignal.from(token, intent, source.valence());
        if (out == null)
            return false;

        ExtractedSignal exactExisting = acc.get(signalKey);
        if (exactExisting == null || signalPriority(out) > signalPriority(exactExisting)) {
            acc.put(signalKey, out);
            return true;
        }
        return false;
    }

    private static double signalPriority(ExtractedSignal sig) {
        if (sig == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return Math.abs(clampSigned(sig.valence() == null ? 0.0 : sig.valence().doubleValue()));
    }

    private static List<String> normalizeConversation(Collection<String> conversationLines) {
        if (conversationLines == null || conversationLines.isEmpty())
            return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String line : conversationLines) {
            if (line == null)
                continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.length() > 320) {
                trimmed = trimmed.substring(0, 320);
            }
            out.add(trimmed);
            if (out.size() >= 40)
                break;
        }
        return out;
    }

    private static List<ExtractedSignal> applyPromptContextAdjustments(String question, String answer,
            Collection<String> conversationLines, List<ExtractedSignal> signals) {
        List<ExtractedSignal> adjusted = (signals == null) ? List.of() : signals;
        adjusted = applyExplicitAnswerFocusBoost(question, answer, adjusted);
        if (isNegativePreferenceContext(question, answer, conversationLines)) {
            adjusted = applyNegativePreferenceAdjustments(adjusted);
            adjusted = suppressBroadNegativeUmbrellas(question, answer, conversationLines, adjusted);
        }
        return cleanupSpecificityConflicts(adjusted);
    }

    private static List<ExtractedSignal> applyPromptPostProcessing(
            String promptId,
            String question,
            String answer,
            List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        List<ExtractedSignal> adjusted = cleanupSpecificityConflicts(signals);
        adjusted = applyHobbyShareMirroring(promptId, question, answer, adjusted);
        adjusted = applyDrawnToMediaIntentRouting(promptId, adjusted);
        return cleanupSpecificityConflicts(adjusted);
    }

    private static List<ExtractedSignal> applyHobbyShareMirroring(
            String promptId,
            String question,
            String answer,
            List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        String normalizedPromptId = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (!"private.hobbies".equals(normalizedPromptId) && !normalizedQuestion.contains("share with your partner")) {
            return signals;
        }
        if (!mentionsMirrorAllPreviouslyMentionedHobbies(answer)) {
            return signals;
        }
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        merge(acc, signals);
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            if (signal.intent() != SignalIntent.SELF) {
                continue;
            }
            if (signal.valence() == null || signal.valence().doubleValue() <= 0.0) {
                continue;
            }
            if (!isConcreteShareableToken(signal.token())) {
                continue;
            }
            ExtractedSignal mirrored = ExtractedSignal.from(signal.token(), SignalIntent.SEEKING, signal.valence());
            if (mirrored != null) {
                merge(acc, List.of(mirrored));
            }
        }
        return new ArrayList<>(acc.values());
    }

    private static boolean mentionsMirrorAllPreviouslyMentionedHobbies(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String lowered = answer.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return containsAny(lowered,
                "all of those",
                "all those",
                "all of them",
                "all of the above",
                "all i mentioned",
                "all i just mentioned",
                "all of what i mentioned",
                "same ones",
                "same as above",
                "everything i mentioned");
    }

    private static List<ExtractedSignal> applyDrawnToMediaIntentRouting(
            String promptId,
            List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        if (!isDrawnToPrompt(promptId)) {
            return signals;
        }
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            if (signal.intent() == SignalIntent.SEEKING && isMediaToken(signal.token())) {
                ExtractedSignal rerouted = ExtractedSignal.from(signal.token(), SignalIntent.SELF, signal.valence());
                if (rerouted != null) {
                    merge(acc, List.of(rerouted));
                }
                continue;
            }
            merge(acc, List.of(signal));
        }
        return new ArrayList<>(acc.values());
    }

    private static boolean isDrawnToPrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return false;
        }
        return "private.drawn.to".equals(promptId.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isMediaToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        String category = SignalTaxonomy.normalizeCategory(SignalConceptRegistry.categoryForConcept(normalized));
        if (category == null) {
            category = SignalTaxonomy.normalizeCategory(SignalTaxonomy.categoryForToken(normalized));
        }
        return SignalTaxonomy.MEDIA.equals(category);
    }

    private static boolean isConcreteShareableToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        String category = SignalTaxonomy.normalizeCategory(SignalConceptRegistry.categoryForConcept(normalized));
        if (category == null) {
            category = SignalTaxonomy.normalizeCategory(SignalTaxonomy.categoryForToken(normalized));
        }
        return SignalTaxonomy.isConcreteCategory(category);
    }

    private static List<ExtractedSignal> applyExplicitAnswerFocusBoost(String question, String answer,
            List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        double floor = explicitAnswerValenceFloor(question);
        if (floor <= 0.0) {
            return signals;
        }
        String normalizedAnswer = SignalNormalizer.normalizeOne(answer);
        if (normalizedAnswer == null || normalizedAnswer.isBlank()) {
            return signals;
        }
        ArrayList<ExtractedSignal> out = new ArrayList<>(signals.size());
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null) {
                continue;
            }
            if (!isExplicitAnswerToken(sig.token(), normalizedAnswer)) {
                out.add(sig);
                continue;
            }
            double baseValence = clampSigned(sig.valence() == null ? 0.0 : sig.valence().doubleValue());
            if (baseValence <= 0.0) {
                out.add(sig);
                continue;
            }
            double boosted = Math.max(baseValence, floor);
            ExtractedSignal normalized = ExtractedSignal.from(sig.token(), sig.intent(), boosted);
            out.add(normalized == null ? sig : normalized);
        }
        return out;
    }

    private static boolean isExplicitAnswerToken(String token, String normalizedAnswer) {
        if (token == null || token.isBlank() || normalizedAnswer == null || normalizedAnswer.isBlank()) {
            return false;
        }
        if (token.equals(normalizedAnswer)) {
            return true;
        }
        String paddedToken = "_" + token + "_";
        String paddedAnswer = "_" + normalizedAnswer + "_";
        if (paddedToken.contains(paddedAnswer) || paddedAnswer.contains(paddedToken)) {
            return true;
        }
        // Be tolerant to possessive/plural drift in explicit entity tokens
        // (e.g., jojo_bizarre_adventure vs jojos_bizarre_adventure).
        String singularishToken = paddedToken.replaceAll("([a-z0-9])s_", "$1_");
        String singularishAnswer = paddedAnswer.replaceAll("([a-z0-9])s_", "$1_");
        return singularishToken.contains(singularishAnswer) || singularishAnswer.contains(singularishToken);
    }

    private static double explicitAnswerValenceFloor(String question) {
        if (question == null || question.isBlank()) {
            return 0.0;
        }
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("yap") && q.contains("hours")) {
            return EXPLICIT_ANSWER_FLOOR_YAP_HOURS;
        }
        if (q.contains("talk for hours")
                || (q.contains("could talk") && q.contains("hours"))
                || q.contains("rabbit hole")) {
            return EXPLICIT_ANSWER_FLOOR_TALK_HOURS;
        }
        return 0.0;
    }

    private static List<ExtractedSignal> enrichPromptSpecificity(OpenAIClient openAI, String promptId,
            String promptProfileHint, String question, String answer, Collection<String> conversationLines,
            Collection<String> alreadyHave, List<ExtractedSignal> baseSignals, int maxExtraSignals) {
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        if (baseSignals != null && !baseSignals.isEmpty()) {
            merge(acc, baseSignals);
        }
        ArrayList<String> existing = new ArrayList<>();
        if (alreadyHave != null && !alreadyHave.isEmpty()) {
            existing.addAll(alreadyHave);
        }
        existing.addAll(tokens(acc.values()));
        List<ExtractedSignal> extra = call(openAI, SignalPrompts.PROMPT_SPECIFICITY_SYSTEM_PROMPT,
                SignalPrompts.promptSpecificityUserPrompt(promptId, promptProfileHint, question, answer, conversationLines,
                        currentSignalsForPrompt(acc.values()), existing),
                Math.max(1, maxExtraSignals),
                "prompt_specificity",
                promptId);
        merge(acc, extra);
        return cleanupSpecificityConflicts(new ArrayList<>(acc.values()));
    }

    private static List<String> currentSignalsForPrompt(Collection<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        ArrayList<String> out = new ArrayList<>(signals.size());
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null)
                continue;
            String intent = sig.intent() == null ? "self" : sig.intent().name().toLowerCase(Locale.ROOT);
            out.add(sig.token() + ":" + intent);
        }
        return out;
    }

    private static List<ExtractedSignal> applyNegativePreferenceAdjustments(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null)
                continue;

            SignalIntent intent = sig.intent() == null ? SignalIntent.SELF : sig.intent();
            if (intent == SignalIntent.META) {
                merge(adjusted, List.of(sig));
                continue;
            }
            SignalIntent adjustedIntent = intent == SignalIntent.BOTH ? SignalIntent.SEEKING : intent;
            if (adjustedIntent == SignalIntent.SELF) {
                adjustedIntent = SignalIntent.SEEKING;
            }

            String token = canonicalizePreferenceToken(sig.token());
            Double adjustedValence = forceNegativeValence(sig.valence());
            ExtractedSignal normalized = ExtractedSignal.from(token, adjustedIntent, adjustedValence);
            if (normalized != null) {
                merge(adjusted, List.of(normalized));
            }
        }
        if (adjusted.isEmpty())
            return signals;
        return new ArrayList<>(adjusted.values());
    }

    private static List<ExtractedSignal> cleanupSpecificityConflicts(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        merge(adjusted, signals);
        return new ArrayList<>(adjusted.values());
    }

    private static Double forceNegativeValence(Double currentValence) {
        if (currentValence == null)
            return -0.9;
        double v = currentValence.doubleValue();
        if (v < 0.0)
            return v;
        double magnitude = Math.max(NEGATIVE_PREFERENCE_MIN_MAGNITUDE, Math.abs(v));
        return -magnitude;
    }

    private static String canonicalizePreferenceToken(String token) {
        if (token == null || token.isBlank())
            return token;
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return token;
        }
        String out = normalized;
        boolean stripped;
        do {
            stripped = false;
            if (out.startsWith("anti_") && out.length() > "anti_".length()) {
                out = out.substring("anti_".length());
                stripped = true;
            } else if (out.startsWith("not_") && out.length() > "not_".length()) {
                out = out.substring("not_".length());
                stripped = true;
            } else if (out.startsWith("no_") && out.length() > "no_".length()) {
                out = out.substring("no_".length());
                stripped = true;
            } else if (out.startsWith("avoid_") && out.length() > "avoid_".length()) {
                out = out.substring("avoid_".length());
                stripped = true;
            } else if (out.startsWith("exclude_") && out.length() > "exclude_".length()) {
                out = out.substring("exclude_".length());
                stripped = true;
            }
            if (stripped) {
                String renormalized = SignalNormalizer.normalizeOne(out);
                out = renormalized == null ? out : renormalized;
            }
        } while (stripped);
        if (out.isBlank()) {
            return normalized;
        }
        return out;
    }

    private static List<ExtractedSignal> suppressBroadNegativeUmbrellas(String question, String answer,
            Collection<String> conversationLines, List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        String context = combinedLowerText(question, answer, conversationLines);
        boolean explicitBroadSocial = containsAny(context,
                "social",
                "socializing",
                "socialising",
                "socialize",
                "socialise",
                "friend",
                "friends");
        ArrayList<ExtractedSignal> out = new ArrayList<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null) {
                continue;
            }
            String token = sig.token();
            if (!explicitBroadSocial
                    && ("socializing".equals(token) || "socializing_with_friends".equals(token))) {
                continue;
            }
            out.add(sig);
        }
        return out;
    }

    private static boolean isNegativePreferenceContext(String question, String answer, Collection<String> conversation) {
        String text = combinedLowerText(question, answer, conversation);
        if (text.isBlank())
            return false;
        return text.contains("not my person")
                || text.contains("dealbreaker")
                || text.contains("deal-breaker")
                || text.contains("turn off")
                || text.contains("turnoff")
                || text.contains("don't like")
                || text.contains("do not like")
                || text.contains("can't stand")
                || text.contains("cannot stand")
                || text.contains("avoid")
                || text.contains("dislike");
    }

    private static String combinedLowerText(String question, String answer, Collection<String> conversation) {
        StringBuilder buf = new StringBuilder();
        if (question != null)
            buf.append(question).append(' ');
        if (answer != null)
            buf.append(answer).append(' ');
        if (conversation != null) {
            for (String line : conversation) {
                if (line != null)
                    buf.append(line).append(' ');
            }
        }
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<ExtractedSignal> call(OpenAIClient openAI, String systemPrompt, String userPrompt,
            int maxSignals, String surface, String promptId) {
        try {
            String system = maybeFormat(systemPrompt, maxSignals);
            String raw = OpenAIJson.call(
                    openAI,
                    system,
                    userPrompt,
                    OpenAIJson.CallSpec.signalExtract(surface, promptId, 320L));
            Object payload = parseSignalsPayload(raw);
            List<?> list = signalsArray(payload);
            if (list.isEmpty())
                return List.of();

            List<ExtractedSignal> out = new ArrayList<>();
            for (Object o : list) {
                ExtractedSignal sig = parseSignal(o);
                if (sig != null)
                    out.add(sig);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Object parseSignalsPayload(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String trimmed = raw.trim();
        try {
            return JSON.readValue(trimmed, Object.class);
        } catch (Exception first) {
            String extracted = extractLikelyJson(trimmed);
            if (extracted == null) {
                throw first;
            }
            return JSON.readValue(extracted, Object.class);
        }
    }

    private static List<?> signalsArray(Object payload) {
        if (payload == null) {
            return List.of();
        }
        if (payload instanceof List<?> list) {
            return list;
        }
        if (!(payload instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object arr = map.get("signals");
        if (arr instanceof List<?> list) {
            return list;
        }
        Object single = map.get("signal");
        if (single instanceof List<?> list) {
            return list;
        }
        if (single != null) {
            return List.of(single);
        }
        return List.of();
    }

    private static String extractLikelyJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int objectStart = raw.indexOf('{');
        int objectEnd = raw.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return raw.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = raw.indexOf('[');
        int arrayEnd = raw.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            String arrayJson = raw.substring(arrayStart, arrayEnd + 1);
            return "{\"signals\":" + arrayJson + "}";
        }
        return null;
    }

    private static ExtractedSignal parseSignal(Object raw) {
        if (raw == null)
            return null;
        if (raw instanceof Map<?, ?> map) {
            Object tokenObj = map.get("token");
            Object intentObj = map.get("intent");
            Object valenceObj = map.get("valence");
            String token = tokenObj == null ? null : String.valueOf(tokenObj);
            SignalIntent intent = parseIntent(intentObj);
            Double valence = parseValence(valenceObj);
            return ExtractedSignal.from(token, intent, valence);
        }
        return ExtractedSignal.from(String.valueOf(raw), SignalIntent.SELF, 1.0);
    }

    private static SignalIntent parseIntent(Object raw) {
        if (raw == null)
            return SignalIntent.SELF;
        if (raw instanceof SignalIntent intent)
            return intent;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty())
            return SignalIntent.SELF;
        try {
            return SignalIntent.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SignalIntent.SELF;
        }
    }

    private static Double parseValence(Object raw) {
        Double parsed = parseNumeric(raw);
        return clampSigned(parsed);
    }

    private static Double parseNumeric(Object raw) {
        if (raw == null)
            return null;
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double clampSigned(Double value) {
        if (value == null)
            return null;
        double c = value.doubleValue();
        if (Double.isNaN(c))
            return null;
        if (c < -1.0)
            c = -1.0;
        if (c > 1.0)
            c = 1.0;
        return c;
    }

    private static String maybeFormat(String template, int maxSignals) {
        if (template.contains("%"))
            return template.formatted(maxSignals);
        return template;
    }

    private static Collection<String> normalizeAlreadyHave(Collection<String> alreadyHave) {
        if (alreadyHave == null || alreadyHave.isEmpty())
            return List.of();
        return SignalNormalizer.normalizeTokens(alreadyHave);
    }

    /**
     * Returns a de-duplicated list of tokens (ignoring intent) to reduce prompt
     * size and keep "already_have" stable.
     */
    private static List<String> tokens(Collection<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashSet<String> toks = new LinkedHashSet<>();
        for (ExtractedSignal sig : signals) {
            if (sig != null && sig.token() != null)
                toks.add(sig.token());
        }
        return new ArrayList<>(toks);
    }

    private static String key(String token, SignalIntent intent) {
        String intentName = (intent == null) ? "UNKNOWN" : intent.name();
        return intentName + "|" + token;
    }
}
