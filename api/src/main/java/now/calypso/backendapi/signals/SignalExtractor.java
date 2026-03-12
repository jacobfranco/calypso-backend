package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.llm.OpenAIJson;

public final class SignalExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();

    // Tunables
    private static final int CHUNK_MAX_CHARS = 800;
    private static final int PER_CALL_MAX = 12; // ask model for up to 12 per call
    private static final int PER_CHUNK_PASSES = 3; // try a few times until saturation
    private static final int PER_PROMPT_PASSES = 2; // allow richer extraction from prompt QA pairs
    private static final int SPECIFICITY_ENRICH_MAX = 4;
    private static final int GLOBAL_SOFT_CAP = 200; // safety stop

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
                        Math.min(PER_CALL_MAX, GLOBAL_SOFT_CAP - acc.size()), already);

                boolean any = merge(acc, raw);
                if (!any)
                    break;
            }
            if (acc.size() >= GLOBAL_SOFT_CAP)
                break;
        }

        return new ArrayList<>(acc.values());
    }

    public static List<ExtractedSignal> extractFromAgentConversation(OpenAIClient openAI, List<String> conversation,
            Collection<String> alreadyHave) {
        if (conversation == null || conversation.isEmpty())
            return List.of();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        List<ExtractedSignal> raw = call(openAI, SignalPrompts.AGENT_CHAT_SYSTEM_PROMPT,
                SignalPrompts.agentChatUserPrompt(conversation, normalizedAlreadyHave),
                PER_CALL_MAX, normalizedAlreadyHave);
        merge(acc, raw);
        return filtered(acc.values(), normalizedAlreadyHave);
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String question, String answer,
            Collection<String> alreadyHave) {
        return extractFromPromptAnswer(openAI, question, answer, List.of(), alreadyHave);
    }

    public static List<ExtractedSignal> extractFromPromptAnswer(OpenAIClient openAI, String question, String answer,
            Collection<String> conversationLines, Collection<String> alreadyHave) {
        if ((question == null || question.isBlank()) && (answer == null || answer.isBlank()))
            return List.of();
        Collection<String> normalizedAlreadyHave = normalizeAlreadyHave(alreadyHave);
        List<String> normalizedConversation = normalizeConversation(conversationLines);
        LinkedHashMap<String, ExtractedSignal> acc = new LinkedHashMap<>();
        for (int i = 0; i < PER_PROMPT_PASSES; i++) {
            ArrayList<String> already = new ArrayList<>(normalizedAlreadyHave);
            already.addAll(tokens(acc.values()));
            List<ExtractedSignal> raw = call(openAI, SignalPrompts.PROMPT_RESPONSE_SYSTEM_PROMPT,
                    SignalPrompts.promptResponseUserPrompt(question, answer, normalizedConversation, already),
                    PER_CALL_MAX, already);
            boolean any = merge(acc, raw);
            if (!any)
                break;
        }
        List<ExtractedSignal> filtered = filtered(acc.values(), normalizedAlreadyHave);
        List<ExtractedSignal> adjusted = applyPromptContextAdjustments(question, answer, normalizedConversation, filtered);
        return enrichPromptSpecificity(openAI, question, answer, normalizedConversation, normalizedAlreadyHave, adjusted);
    }

    private static List<String> chunk(String text, int maxChars) {
        text = text.trim();
        if (text.length() <= maxChars)
            return List.of(text);
        List<String> out = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?])\\s+");
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
     * Merge signals into acc with deterministic intent semantics:
     *
     * - BOTH dominates: if BOTH|token exists, drop SELF|token and SEEKING|token.
     * - If SELF and SEEKING both exist for a token (and BOTH does not), collapse
     * them into BOTH.
     * - If a SELF/SEEKING entry arrives but BOTH|token already exists, ignore it.
     *
     * This prevents model "hedging" and keeps one concept per token.
     */
    private static boolean merge(LinkedHashMap<String, ExtractedSignal> acc, List<ExtractedSignal> raw) {
        boolean any = false;
        if (raw == null)
            return false;

        for (ExtractedSignal sig : raw) {
            if (sig == null || sig.token() == null)
                continue;

            String token = sig.token();
            SignalIntent intent = sig.intent();

            String bothKey = key(token, SignalIntent.BOTH);
            String selfKey = key(token, SignalIntent.SELF);
            String seekingKey = key(token, SignalIntent.SEEKING);

            // 1) If model emits BOTH, it dominates.
            if (intent == SignalIntent.BOTH) {
                boolean removed = (acc.remove(selfKey) != null) | (acc.remove(seekingKey) != null);
                if (!acc.containsKey(bothKey)) {
                    acc.put(bothKey, sig);
                    any = true;
                } else if (removed) {
                    any = true;
                }
                continue;
            }

            // If BOTH already exists, ignore any other intent for same token.
            if (acc.containsKey(bothKey)) {
                continue;
            }

            // 2) Normal insert for non-BOTH
            String k = key(token, intent);
            if (!acc.containsKey(k)) {
                acc.put(k, sig);
                any = true;
            }

            // 3) Optional upgrade: if we now have BOTH self+seeking, collapse to BOTH.
            // Only for SELF+SEEKING. META should not participate.
            if (intent == SignalIntent.SELF || intent == SignalIntent.SEEKING) {
                ExtractedSignal selfSig = acc.get(selfKey);
                ExtractedSignal seekingSig = acc.get(seekingKey);

                if (selfSig != null && seekingSig != null) {
                    Double combinedConfidence = minNonNull(selfSig.confidence(), seekingSig.confidence());
                    Double combinedImportance = maxNonNull(selfSig.importance(), seekingSig.importance());

                    // Remove the two and replace with BOTH
                    acc.remove(selfKey);
                    acc.remove(seekingKey);

                    ExtractedSignal bothSig = ExtractedSignal.from(token, SignalIntent.BOTH,
                            combinedConfidence, combinedImportance);

                    if (bothSig != null) {
                        acc.put(bothKey, bothSig);
                        any = true;
                    }
                }
            }
        }

        return any;
    }

    private static Double minNonNull(Double a, Double b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return Math.min(a.doubleValue(), b.doubleValue());
    }

    private static Double maxNonNull(Double a, Double b) {
        if (a == null)
            return b;
        if (b == null)
            return a;
        return Math.max(a.doubleValue(), b.doubleValue());
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
        String combined = combinedLowerText(question, answer, conversationLines);
        if (isNegativePreferenceContext(question, answer, conversationLines)) {
            adjusted = applyNegativePreferenceAdjustments(adjusted, combined);
        }
        adjusted = applyGoalContextExpansions(question, answer, conversationLines, adjusted);
        adjusted = applySundayContextExpansions(question, answer, conversationLines, adjusted);
        adjusted = applyCommunityContextExpansions(question, answer, conversationLines, adjusted);
        return cleanupSpecificityConflicts(adjusted);
    }

    private static List<ExtractedSignal> enrichPromptSpecificity(OpenAIClient openAI, String question, String answer,
            Collection<String> conversationLines, Collection<String> alreadyHave, List<ExtractedSignal> baseSignals) {
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
                SignalPrompts.promptSpecificityUserPrompt(question, answer, conversationLines,
                        currentSignalsForPrompt(acc.values()), existing),
                SPECIFICITY_ENRICH_MAX, existing);
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

    private static List<ExtractedSignal> applyNegativePreferenceAdjustments(List<ExtractedSignal> signals,
            String combinedText) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        boolean hasPositiveCreatorCaveat = hasPositiveSocialCreatorCaveat(combinedText);
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null)
                continue;
            SignalIntent intent = sig.intent();
            if (intent == null)
                intent = SignalIntent.SELF;

            if (intent == SignalIntent.META) {
                merge(adjusted, List.of(sig));
                continue;
            }

            SignalIntent adjustedIntent = intent == SignalIntent.BOTH ? SignalIntent.SEEKING : intent;
            if (adjustedIntent == SignalIntent.SELF) {
                adjustedIntent = SignalIntent.SEEKING;
            }

            String token = sig.token();
            if (hasPositiveCreatorCaveat && tokenLooksLikeSocialMediaCreator(token)) {
                continue;
            }
            if (shouldForceNegativeToken(token)) {
                token = canonicalizeNegativeToken("anti_" + token);
            } else {
                token = canonicalizeNegativeToken(token);
            }
            addAdjustedSignal(adjusted, token, adjustedIntent, sig.confidence(),
                    bumpImportanceForExclusion(sig.importance()));
        }
        if (adjusted.isEmpty())
            return signals;
        return new ArrayList<>(adjusted.values());
    }

    private static List<ExtractedSignal> applyGoalContextExpansions(String question, String answer,
            Collection<String> conversationLines, List<ExtractedSignal> signals) {
        if (signals == null)
            signals = List.of();
        String questionText = normalizeText(question);
        String combined = combinedLowerText(question, answer, conversationLines);
        boolean goalContext = isGoalContext(questionText, combined);
        boolean appBuild = mentionsAppBuilding(combined);
        if (!goalContext && !appBuild)
            return signals;

        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        merge(adjusted, signals);

        if (appBuild) {
            addAdjustedSignal(adjusted, "app_builder", SignalIntent.SELF, 0.90, goalContext ? 0.82 : 0.75);
            if (mentionsSoftwareCraft(combined)) {
                addAdjustedSignal(adjusted, "software_builder", SignalIntent.SELF, 0.84, 0.74);
            }
            if (mentionsStartupContext(combined)) {
                addAdjustedSignal(adjusted, "entrepreneurial_mindset", SignalIntent.SELF, 0.80, 0.76);
            }
        }

        if (goalContext && (appBuild || mentionsStartupContext(combined)) && !containsToken(adjusted, "ambitious")) {
            addAdjustedSignal(adjusted, "ambitious", SignalIntent.SELF, 0.76, 0.68);
        }

        return new ArrayList<>(adjusted.values());
    }

    private static List<ExtractedSignal> applySundayContextExpansions(String question, String answer,
            Collection<String> conversationLines, List<ExtractedSignal> signals) {
        if (signals == null)
            signals = List.of();
        String questionText = normalizeText(question);
        String combined = combinedLowerText(question, answer, conversationLines);
        boolean sundayContext = questionText.contains("ideal sunday")
                || questionText.contains("sunday looks like")
                || combined.contains("ideal sunday");
        boolean sleepIn = mentionsSleepIn(combined);
        boolean nfl = mentionsNfl(combined);
        if (!sundayContext && !sleepIn && !nfl) {
            return signals;
        }

        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        merge(adjusted, signals);
        if (sleepIn) {
            addAdjustedSignal(adjusted, "sleeping_in", SignalIntent.SELF, 0.88, 0.67);
        }
        if (nfl) {
            addAdjustedSignal(adjusted, "nfl_fan", SignalIntent.SELF, 0.91, 0.78);
            if (!containsToken(adjusted, "sports_fan")) {
                addAdjustedSignal(adjusted, "sports_fan", SignalIntent.SELF, 0.78, 0.66);
            }
        }
        return new ArrayList<>(adjusted.values());
    }

    private static List<ExtractedSignal> applyCommunityContextExpansions(String question, String answer,
            Collection<String> conversationLines, List<ExtractedSignal> signals) {
        if (signals == null)
            signals = List.of();
        String questionText = normalizeText(question);
        String combined = combinedLowerText(question, answer, conversationLines);
        boolean communityContext = questionText.contains("communities")
                || questionText.contains("scene")
                || questionText.contains("at home in");
        boolean gymMention = containsAny(combined, " gym", "gym ", "the gym", "fitness center", "workout");
        boolean greekMention = containsAny(combined, "frat", "fraternity", "sorority", "greek life");
        if (!communityContext && !gymMention && !greekMention) {
            return signals;
        }

        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        merge(adjusted, signals);
        if (gymMention) {
            addAdjustedSignal(adjusted, "gym_regular", SignalIntent.SELF, 0.88, 0.74);
        }
        if (greekMention) {
            addAdjustedSignal(adjusted, "greek_life_alumni", SignalIntent.SELF, 0.85, 0.72);
        }
        return new ArrayList<>(adjusted.values());
    }

    private static void addAdjustedSignal(LinkedHashMap<String, ExtractedSignal> adjusted, String token,
            SignalIntent intent, Double confidence, Double importance) {
        ExtractedSignal normalized = ExtractedSignal.from(token, intent, confidence, importance);
        if (normalized != null) {
            merge(adjusted, List.of(normalized));
        }
    }

    private static boolean containsToken(LinkedHashMap<String, ExtractedSignal> signalsByKey, String token) {
        if (signalsByKey == null || signalsByKey.isEmpty() || token == null)
            return false;
        for (ExtractedSignal sig : signalsByKey.values()) {
            if (sig != null && token.equals(sig.token()))
                return true;
        }
        return false;
    }

    private static List<ExtractedSignal> cleanupSpecificityConflicts(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashMap<String, ExtractedSignal> adjusted = new LinkedHashMap<>();
        merge(adjusted, signals);
        if (containsToken(adjusted, "app_builder")) {
            removeToken(adjusted, "builder");
        }
        if (containsToken(adjusted, "sleeping_in")) {
            removeToken(adjusted, "relaxed_morning");
        }
        removePositiveCounterpartsOfNegativeTokens(adjusted);
        return new ArrayList<>(adjusted.values());
    }

    private static void removePositiveCounterpartsOfNegativeTokens(
            LinkedHashMap<String, ExtractedSignal> adjusted) {
        if (adjusted == null || adjusted.isEmpty())
            return;
        LinkedHashSet<String> positiveTokensToDrop = new LinkedHashSet<>();
        for (ExtractedSignal sig : adjusted.values()) {
            if (sig == null || sig.token() == null)
                continue;
            String token = sig.token();
            if (token.startsWith("anti_") && token.length() > "anti_".length()) {
                positiveTokensToDrop.add(token.substring("anti_".length()));
            }
        }
        if (positiveTokensToDrop.isEmpty())
            return;
        for (String token : positiveTokensToDrop) {
            removeToken(adjusted, token);
        }
    }

    private static void removeToken(LinkedHashMap<String, ExtractedSignal> adjusted, String token) {
        if (adjusted == null || adjusted.isEmpty() || token == null)
            return;
        ArrayList<String> keysToRemove = new ArrayList<>();
        for (Map.Entry<String, ExtractedSignal> entry : adjusted.entrySet()) {
            ExtractedSignal sig = entry.getValue();
            if (sig != null && token.equals(sig.token())) {
                keysToRemove.add(entry.getKey());
            }
        }
        for (String key : keysToRemove) {
            adjusted.remove(key);
        }
    }

    private static Double bumpImportanceForExclusion(Double existing) {
        if (existing == null)
            return 0.72;
        return Math.max(existing.doubleValue(), 0.72);
    }

    private static boolean shouldForceNegativeToken(String token) {
        if (token == null || token.isBlank())
            return false;
        return !(token.startsWith("anti_")
                || token.startsWith("not_")
                || token.startsWith("no_")
                || token.startsWith("avoid_")
                || token.startsWith("exclude_"));
    }

    private static String canonicalizeNegativeToken(String token) {
        if (token == null || token.isBlank())
            return token;
        if (token.startsWith("anti_not_")) {
            return "anti_" + token.substring("anti_not_".length());
        }
        if (token.startsWith("not_")) {
            return "anti_" + token.substring("not_".length());
        }
        return token;
    }

    private static boolean tokenLooksLikeSocialMediaCreator(String token) {
        if (token == null || token.isBlank())
            return false;
        String t = token.toLowerCase(Locale.ROOT);
        return t.contains("creator") && t.contains("social");
    }

    private static boolean hasPositiveSocialCreatorCaveat(String combinedText) {
        if (combinedText == null || combinedText.isBlank())
            return false;
        return combinedText.contains("creator")
                && containsAny(combinedText, "cool", "good", "fine", "unless", "ironically cool");
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
                || text.contains("avoid");
    }

    private static boolean isGoalContext(String questionText, String combinedText) {
        String question = questionText == null ? "" : questionText;
        if (question.contains("life goal")
                || question.contains("goal of mine")
                || question.contains("goal")) {
            return true;
        }
        return combinedText.contains("my goal")
                || combinedText.contains("life goal")
                || combinedText.contains("goal of mine");
    }

    private static boolean mentionsAppBuilding(String text) {
        if (text == null || text.isBlank())
            return false;
        boolean hasBuildVerb = containsAny(text, "build", "building", "built", "make", "making", "create", "creating",
                "ship", "shipping", "launch", "launching");
        boolean hasAppTarget = containsAny(text, "app", "application", "platform", "saas", "product", "startup");
        return hasBuildVerb && hasAppTarget;
    }

    private static boolean mentionsSoftwareCraft(String text) {
        return containsAny(text, "code", "coding", "developer", "software", "engineer", "programming");
    }

    private static boolean mentionsStartupContext(String text) {
        return containsAny(text, "startup", "company", "business", "founder", "entrepreneur", "venture");
    }

    private static boolean mentionsSleepIn(String text) {
        return containsAny(text, "sleep in", "sleeping in", "wake up late", "waking up late", "sleep late");
    }

    private static boolean mentionsNfl(String text) {
        return containsAny(text, " nfl", "nfl ", "nfl", "football sunday", "watch football");
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

    private static String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
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

    @SuppressWarnings("unchecked")
    private static List<ExtractedSignal> call(OpenAIClient openAI, String systemPrompt, String userPrompt,
            int maxSignals, Collection<String> alreadyHave) {
        try {
            String system = maybeFormat(systemPrompt, maxSignals);
            String raw = OpenAIJson.call(openAI, system, userPrompt);
            Map<String, Object> parsed = JSON.readValue(raw, new TypeReference<>() {
            });
            Object arr = parsed.get("signals");
            if (!(arr instanceof List<?> list))
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

    private static ExtractedSignal parseSignal(Object raw) {
        if (raw == null)
            return null;
        if (raw instanceof Map<?, ?> map) {
            Object tokenObj = map.get("token");
            Object intentObj = map.get("intent");
            Object confidenceObj = map.get("confidence");
            Object importanceObj = map.get("importance");
            String token = tokenObj == null ? null : String.valueOf(tokenObj);
            SignalIntent intent = parseIntent(intentObj);
            Double confidence = parseConfidence(confidenceObj);
            Double importance = parseImportance(importanceObj);
            return ExtractedSignal.from(token, intent, confidence, importance);
        }
        return ExtractedSignal.from(String.valueOf(raw), SignalIntent.SELF, null);
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

    private static Double parseConfidence(Object raw) {
        if (raw == null)
            return null;
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseImportance(Object raw) {
        if (raw == null)
            return null;
        try {
            return Double.valueOf(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            return null;
        }
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
     * size
     * and make "already_have" stable.
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
