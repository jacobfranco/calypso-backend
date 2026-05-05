package now.calypso.backendapi.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;

import now.calypso.backend.data.SignalIntent;
import now.calypso.backendapi.signals.ExtractedSignal;
import now.calypso.backendapi.signals.PromptSignalProfiles;
import now.calypso.backendapi.signals.SignalExtractor;
import now.calypso.backendapi.signals.SignalNormalizer;
import now.calypso.backendapi.silhouette.SilhouettePatch;

public final class PrivatePromptUnderstanding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_PROMPT = """
            You are Calypso's private prompt understanding engine.

            Return JSON only in this exact shape:
            {
              "signals":[{"token":"stable_reusable_concept","intent":"self","valence":0.72}],
              "silhouettePatch":{"ops":[{"op":"upsert_concept","modeId":"mode_compact_label","label":"compact mode label","target":"self_expression","concept":{"id":"specific_relational_concept","label":"specific relational concept","role":"context","confidence":0.62,"strength":0.58},"evidence":{"source":"private_prompt","target":"self_expression","value":"compact derived evidence","strength":0.60,"confidence":0.62}}]},
              "metaObservations":[{"key":"depth_vs_surface_focus","summary":"...","confidence":0.44}]
            }

            Goals:
            - Extract context-free durable signals for retrieval/filtering.
            - Produce a compact silhouette patch for context-dependent relationship modes.

            Signal constraints:
            - max 8 signals.
            - token: lowercase snake_case, reusable concept labels only.
            - intent: "self" | "seeking" | "both" | "meta".
            - valence: [-1,1].
            - Include explicit media/franchise titles when named (e.g., red_rising).
            - Include explicit concrete media formats when named (e.g., reality_tv).
            - Avoid character-name-only tags unless context-free durability is clear.
            - Preserve explicit concrete activities as signals even when an abstract trait is also implied (e.g., "the gym" => gym, not only discipline).
            - In dislike prompts, keep negative signals specific; do not turn dislike of a genre/artist/show format into dislike of all music, TV, or media.

            Silhouette patch constraints:
            - Keep ops minimal and high-precision (0-8 typically).
            - Do not treat the user as one fixed personality.
            - Extract coherent relationship modes when possible.
            - Fictional characters, music, aesthetics, prompt answers, and reactions are evidence. They are not the mode itself.
            - A mode is a living concept cluster.
            - Do not overwrite existing modes unless evidence clearly retracts or contradicts them.
            - If new evidence adds texture, reinforce or extend the nearest compatible mode.
            - If new evidence implies a distinct relationship configuration, create a new mode.
            - Separate how the user may show up, what they are drawn to, what creates spark, what sustains connection, what repels them, and what remains uncertain.
            - Allowed targets: self_expression, seeking_expression, spark_triggers, sustainability_needs, aesthetic_field, anti_patterns, tensions.
            - Allowed ops: upsert_mode, reinforce_mode, deprecate_mode, upsert_concept, reinforce_concept, retract_concept, upsert_anti_pattern, upsert_tension, add_evidence, add_open_question.
            - Use spark_triggers for chemistry, intrigue, attraction, aesthetic pull, curiosity, or romantic energy.
            - Use sustainability_needs for consistency, emotional workability, patience, autonomy, reassurance, communication rhythm, or long-term fit.
            - Do not over-infer sustainability from sparse evidence. Use add_open_question when uncertain.
            - Keep concept labels concise and non-clinical.
            - Concrete hobbies/titles should usually be signals and/or evidence values, not identity concepts.
            - Comparative fictional references should be evidence with source=fictional_comp.
            - Visual aesthetic evidence should use source=visual_aesthetic.
            - Music evidence should use source=music.
            - For dislike, turn-off, and not-my-person prompts, prefer anti_patterns or open_questions; do not create self_expression concepts from dislikes.
            - Meta observations must be neutral and non-moralizing.
            - confidence in [0,1].
            - No markdown, no prose outside JSON.
            - Do not reuse example IDs or labels from this prompt.
            """;

    private PrivatePromptUnderstanding() {
    }

    public static Result generate(
            OpenAIClient client,
            String promptId,
            String question,
            String answer,
            Collection<String> conversationLines,
            Collection<String> alreadyHave) {
        if (client == null) {
            return Result.empty(false);
        }
        String normalizedPromptId = promptId == null ? null : promptId.trim();
        PromptSignalProfiles.PromptSignalProfile profile = PromptSignalProfiles.forPromptId(normalizedPromptId);
        String userPrompt = buildUserPrompt(normalizedPromptId, profile.extractionHint(), question, answer, conversationLines,
                alreadyHave);
        try {
            String raw = OpenAIJson.call(
                    client,
                    SYSTEM_PROMPT,
                    userPrompt,
                    OpenAIJson.CallSpec.custom(
                            "private_understanding",
                            normalizedPromptId == null || normalizedPromptId.isBlank() ? "private_prompt_answer"
                                    : normalizedPromptId,
                            normalizedPromptId,
                            520L));
            ParsedPayload parsed = parse(raw);
            if (!parsed.parsed) {
                return Result.empty(false);
            }
            List<ExtractedSignal> signalsWithTitles = SignalExtractor.augmentWithExplicitTitleMentions(
                    normalizedPromptId,
                    question,
                    answer,
                    alreadyHave,
                    parsed.signals);
            return new Result(signalsWithTitles, parsed.patch, true);
        } catch (Exception ignored) {
            return Result.empty(false);
        }
    }

    private static String buildUserPrompt(
            String promptId,
            String profileHint,
            String question,
            String answer,
            Collection<String> conversationLines,
            Collection<String> alreadyHave) {
        String conversation = jsonArray(conversationLines, 10, 140);
        String already = jsonArray(alreadyHave, 12, 40);
        return """
                prompt_id: %s
                prompt_profile_hint: %s
                prompt_question: %s
                prompt_answer: %s
                conversation_context: %s
                already_have: %s
                """.formatted(
                jsonQuote(promptId),
                jsonQuote(clampForPrompt(profileHint, 140)),
                jsonQuote(clampForPrompt(question, 220)),
                jsonQuote(clampForPrompt(answer, 260)),
                conversation,
                already);
    }

    private static String clampForPrompt(String raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen).trim();
    }

    @SuppressWarnings("unchecked")
    private static ParsedPayload parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ParsedPayload.empty(false);
        }
        try {
            Object parsed = JSON.readValue(raw, Object.class);
            if (!(parsed instanceof Map<?, ?> mapRaw)) {
                return ParsedPayload.empty(false);
            }
            Map<String, Object> map = (Map<String, Object>) mapRaw;
            List<ExtractedSignal> signals = parseSignals(map.get("signals"));
            SilhouettePatch patch = parsePatch(map);
            mergeMetaObservationsIntoPatch(map.get("metaObservations"), patch);
            return new ParsedPayload(signals, patch, true);
        } catch (Exception ignored) {
            return ParsedPayload.empty(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static SilhouettePatch parsePatch(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return SilhouettePatch.empty();
        }
        Object rawPatch = payload.get("silhouettePatch");
        if (!(rawPatch instanceof Map<?, ?>)) {
            rawPatch = payload.get("silhouette_patch");
        }
        if (!(rawPatch instanceof Map<?, ?>)) {
            rawPatch = payload.get("patch");
        }
        if (rawPatch instanceof Map<?, ?> map) {
            return SilhouettePatch.fromMap((Map<String, Object>) map);
        }
        if (payload.containsKey("ops")) {
            return SilhouettePatch.fromMap(payload);
        }
        return SilhouettePatch.empty();
    }

    @SuppressWarnings("unchecked")
    private static void mergeMetaObservationsIntoPatch(Object rawMeta, SilhouettePatch patch) {
        if (!(rawMeta instanceof List<?> list) || patch == null) {
            return;
        }
        int added = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> mapRaw)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) mapRaw;
            String key = normalizeToken(map.get("key"));
            String summary = normalizeText(map.get("summary"), 120);
            Double confidence = parseSigned01(map.get("confidence"));
            if (key == null || summary == null) {
                continue;
            }
            if (!looksLikeOpenQuestion(summary)) {
                continue;
            }
            patch.ops.add(SilhouettePatch.Op.addOpenQuestion(
                    null,
                    null,
                    summary));
            added += 1;
            if (added >= 3) {
                break;
            }
        }
    }

    private static boolean looksLikeOpenQuestion(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith("?")) {
            return true;
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        return lowered.startsWith("whether ")
                || lowered.startsWith("does ")
                || lowered.startsWith("do ")
                || lowered.startsWith("is ")
                || lowered.startsWith("are ")
                || lowered.startsWith("should ")
                || lowered.startsWith("would ");
    }

    @SuppressWarnings("unchecked")
    private static List<ExtractedSignal> parseSignals(Object rawSignals) {
        if (!(rawSignals instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ExtractedSignal> dedup = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> mapRaw)) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) mapRaw;
            String token = normalizeToken(map.get("token"));
            SignalIntent intent = parseIntent(map.get("intent"));
            Double valence = parseSigned01(map.get("valence"));
            ExtractedSignal signal = ExtractedSignal.from(token, intent, valence);
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String key = signal.intent().name() + "|" + signal.token();
            ExtractedSignal existing = dedup.get(key);
            if (existing == null || priority(signal) > priority(existing)) {
                dedup.put(key, signal);
            }
            if (dedup.size() >= 10) {
                break;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static double priority(ExtractedSignal signal) {
        if (signal == null || signal.valence() == null) {
            return 0.0;
        }
        return Math.abs(signal.valence().doubleValue());
    }

    private static SignalIntent parseIntent(Object raw) {
        if (raw == null) {
            return SignalIntent.SELF;
        }
        String normalized = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return SignalIntent.SELF;
        }
        try {
            return SignalIntent.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return SignalIntent.SELF;
        }
    }

    private static Double parseSigned01(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.toString().trim());
            if (!Double.isFinite(value)) {
                return null;
            }
            if (value < -1.0) {
                value = -1.0;
            } else if (value > 1.0) {
                value = 1.0;
            }
            return value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalizeToken(Object raw) {
        if (raw == null) {
            return null;
        }
        return SignalNormalizer.normalizeOne(raw.toString());
    }

    private static String normalizeText(Object raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isBlank()) {
            return null;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen).trim();
    }

    private static String jsonArray(Collection<String> values, int maxItems, int maxCharsPerItem) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        int added = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.length() > maxCharsPerItem) {
                trimmed = trimmed.substring(0, maxCharsPerItem).trim();
            }
            if (trimmed.isBlank()) {
                continue;
            }
            joiner.add(jsonQuote(trimmed));
            added += 1;
            if (added >= maxItems) {
                break;
            }
        }
        return joiner.toString();
    }

    private static String jsonQuote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static final class ParsedPayload {
        final List<ExtractedSignal> signals;
        final SilhouettePatch patch;
        final boolean parsed;

        ParsedPayload(List<ExtractedSignal> signals, SilhouettePatch patch, boolean parsed) {
            this.signals = signals == null ? List.of() : List.copyOf(signals);
            this.patch = patch == null ? SilhouettePatch.empty() : patch;
            this.parsed = parsed;
        }

        static ParsedPayload empty(boolean parsed) {
            return new ParsedPayload(List.of(), SilhouettePatch.empty(), parsed);
        }
    }

    public static final class Result {
        public final List<ExtractedSignal> signals;
        public final SilhouettePatch patch;
        public final boolean parsed;

        Result(List<ExtractedSignal> signals, SilhouettePatch patch, boolean parsed) {
            this.signals = signals == null ? List.of() : List.copyOf(signals);
            this.patch = patch == null ? SilhouettePatch.empty() : patch;
            this.parsed = parsed;
        }

        static Result empty(boolean parsed) {
            return new Result(List.of(), SilhouettePatch.empty(), parsed);
        }
    }
}
