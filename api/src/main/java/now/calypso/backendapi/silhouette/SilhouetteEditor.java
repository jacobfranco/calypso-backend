package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openai.client.OpenAIClient;

import now.calypso.backendapi.llm.OpenAIJson;

public final class SilhouetteEditor {
    private static final int MIN_LLM_ANSWER_CHARS = 36;
    private static final long LLM_MAX_OUTPUT_TOKENS_PRIMARY = 120L;
    private static final long LLM_MAX_OUTPUT_TOKENS_RETRY = 190L;
    private static final Pattern COMPARATIVE_FROM_PATTERN = Pattern.compile(
            "(?i)([a-z0-9'\\- ]{2,84})\\s+from\\s+([a-z0-9'\\- ]{2,56})");
    private static final String SYSTEM_PROMPT = """
            You are Calypso's silhouette claim editor.

            Task:
            - Update the user's silhouette claim ledger with compact, high-signal claims.
            - Silhouette captures context-dependent personality, relational dynamics, and trajectory.
            - Signals capture context-free facts (hobbies, explicit media/franchise titles, concrete interests).

            Output JSON ONLY in shape:
            {"ops":[{"op":"upsert_claim","key":"seeking_core","text":"...","kind":"preference","confidence":0.72}]}

            Allowed ops:
            - upsert_claim
            - reinforce_claim
            - retract_claim

            Constraints:
            - Keep ops minimal and high precision (0-6 ops typically).
            - Prefer these facet keys:
              self_core, seeking_core, relationship_dynamic, energy_style,
              communication_style, emotional_style, trajectory, hard_boundaries,
              partner_comps, meta_observation, narrative.
            - Keep each claim text concise (about 6-16 words).
            - Avoid repeating concrete tags (hobbies/media/franchises) already captured as signals.
            - Comparative references (characters/figures/examples) should be `key=partner_comps` and `kind=partner_comp`.
            - Meta observations should be neutral and non-moralizing.
            - Confidence in [0,1].
            - No markdown, no prose outside JSON.
            """;

    private SilhouetteEditor() {
    }

    public static SilhouettePatch buildPatch(OpenAIClient client, SilhouetteState current, Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return SilhouettePatch.empty();
        }
        String source = asTrimmed(event.get("source"));
        String promptId = asTrimmed(event.get("promptId"));
        String answer = asTrimmed(event.get("answer"));
        String question = asTrimmed(event.get("question"));
        if (answer == null && question == null) {
            return SilhouettePatch.empty();
        }
        if (shouldUseHeuristicOnly(source, promptId, answer)) {
            return heuristicFallbackPatch(source, promptId, question, answer);
        }
        String user = buildUserPrompt(current, event);
        SilhouettePatch parsed = llmPatch(client, user, source, promptId, LLM_MAX_OUTPUT_TOKENS_PRIMARY);
        if ((parsed == null || parsed.isEmpty()) && answer != null && answer.trim().length() >= 80) {
            parsed = llmPatch(client, user, source, promptId, LLM_MAX_OUTPUT_TOKENS_RETRY);
        }
        if (parsed != null && !parsed.isEmpty()) {
            maybeAugmentPartnerCompClaims(parsed, promptId, question, answer);
            return parsed;
        }
        SilhouettePatch fallback = heuristicFallbackPatch(source, promptId, question, answer);
        maybeAugmentPartnerCompClaims(fallback, promptId, question, answer);
        return fallback;
    }

    private static SilhouettePatch llmPatch(
            OpenAIClient client,
            String userPrompt,
            String source,
            String promptId,
            long maxOutputTokens) {
        String surface = source == null || source.isBlank() ? "silhouette_event" : source.trim();
        String raw = OpenAIJson.call(
                client,
                SYSTEM_PROMPT,
                userPrompt,
                OpenAIJson.CallSpec.silhouettePatch(surface, promptId, maxOutputTokens));
        return SilhouettePatch.fromRawJson(raw);
    }

    private static boolean shouldUseHeuristicOnly(String source, String promptId, String answer) {
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String normalizedPrompt = promptId == null ? "" : promptId.toLowerCase(Locale.ROOT);
        int answerLen = answer == null ? 0 : answer.trim().length();
        if (answerLen <= 0) {
            return true;
        }
        if (normalizedSource.contains("public_prompt_reaction")) {
            return true;
        }
        if ("private.popular.dislike".equals(normalizedPrompt) && answerLen < 64) {
            return true;
        }
        return answerLen < MIN_LLM_ANSWER_CHARS;
    }

    private static SilhouettePatch heuristicFallbackPatch(String source, String promptId, String question, String answer) {
        SilhouettePatch fallback = new SilhouettePatch();
        if (answer == null || answer.isBlank()) {
            return fallback;
        }
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        String normalizedPrompt = promptId == null ? "" : promptId.toLowerCase(Locale.ROOT);
        String normalizedQuestion = question == null
                ? ""
                : question.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        String summary = answer.trim();
        if (summary.length() > 280) {
            summary = summary.substring(0, 280).trim();
        }
        if (summary.isBlank()) {
            return fallback;
        }

        String facet;
        double confidence;
        boolean explicitDislike = "private.popular.dislike".equals(normalizedPrompt)
                || normalizedQuestion.contains("don't like")
                || normalizedQuestion.contains("don't get")
                || normalizedQuestion.contains("turn off");
        if (explicitDislike) {
            facet = "hard_boundaries";
            confidence = 0.60;
        } else if (normalizedQuestion.contains("drawn") || normalizedQuestion.contains("looking for")) {
            facet = "seeking_core";
            confidence = 0.58;
        } else if (normalizedSource.contains("private") || normalizedSource.contains("matchmaking_followup")) {
            facet = "seeking_core";
            confidence = 0.54;
        } else {
            facet = "self_core";
            confidence = 0.44;
        }

        fallback.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                facet,
                null,
                summary,
                null,
                "heuristic",
                confidence,
                List.of()));
        maybeAugmentPartnerCompClaims(fallback, promptId, question, answer);
        return fallback;
    }

    private static void maybeAugmentPartnerCompClaims(
            SilhouettePatch patch,
            String promptId,
            String question,
            String answer) {
        if (patch == null || answer == null || answer.isBlank()) {
            return;
        }
        if (!isComparativePrompt(promptId, question)) {
            return;
        }
        LinkedHashSet<String> existing = new LinkedHashSet<>();
        for (SilhouettePatch.Op op : patch.ops) {
            if (op == null || op.key == null || op.text == null || op.text.isBlank()) {
                continue;
            }
            if (!"partner_comps".equals(SilhouetteState.normalizeKey(op.key))) {
                continue;
            }
            existing.add(op.text.trim().toLowerCase(Locale.ROOT));
        }

        for (SilhouettePatch.Op op : extractPartnerCompClaims(answer)) {
            if (op == null || op.text == null || op.text.isBlank()) {
                continue;
            }
            String key = op.text.trim().toLowerCase(Locale.ROOT);
            if (existing.contains(key)) {
                continue;
            }
            existing.add(key);
            patch.ops.add(op);
            if (existing.size() >= 4) {
                break;
            }
        }
    }

    private static List<SilhouettePatch.Op> extractPartnerCompClaims(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        ArrayList<SilhouettePatch.Op> out = new ArrayList<>();
        LinkedHashSet<String> seenClaims = new LinkedHashSet<>();
        Matcher matcher = COMPARATIVE_FROM_PATTERN.matcher(answer);
        while (matcher.find()) {
            String rawComparisons = matcher.group(1);
            String rawSource = matcher.group(2);
            String sourceLabel = displayLabel(normalizeLabel(rawSource));
            if (sourceLabel.isBlank()) {
                continue;
            }
            String comparisons = compactComparisons(rawComparisons);
            String claimText = comparisons.isBlank()
                    ? sourceLabel
                    : comparisons + " (" + sourceLabel + ")";
            claimText = clamp(claimText, 180);
            if (claimText == null || claimText.isBlank()) {
                continue;
            }
            String dedupKey = claimText.toLowerCase(Locale.ROOT);
            if (seenClaims.contains(dedupKey)) {
                continue;
            }
            out.add(new SilhouettePatch.Op(
                    "upsert_claim",
                    "partner_comps",
                    null,
                    claimText,
                    null,
                    "partner_comp",
                    0.64,
                    List.of()));
            seenClaims.add(dedupKey);
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    private static String compactComparisons(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replaceAll("(?i)\\b(like|someone|people|person|characters?|figures?|protagonists?)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("(?i)\\s*(?:,|/|\\bor\\b|\\band\\b)\\s*");
        ArrayList<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String cleaned = part.trim();
            if (cleaned.isBlank()) {
                continue;
            }
            if (cleaned.length() > 28) {
                cleaned = cleaned.substring(0, 28).trim();
            }
            if (!cleaned.isBlank()) {
                kept.add(cleaned);
            }
            if (kept.size() >= 3) {
                break;
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        return String.join(", ", kept);
    }

    private static boolean isComparativePrompt(String promptId, String question) {
        String prompt = promptId == null ? "" : promptId.toLowerCase(Locale.ROOT);
        if ("private.drawn.to".equals(prompt)
                || "private.fictional.characters".equals(prompt)
                || "private.fascinating.people".equals(prompt)) {
            return true;
        }
        if (question == null || question.isBlank()) {
            return false;
        }
        String lowered = question.toLowerCase(Locale.ROOT);
        return lowered.contains("drawn to")
                || lowered.contains("fictional character")
                || lowered.contains("historical")
                || lowered.contains("fascinating");
    }

    private static String normalizeLabel(String raw) {
        String key = SilhouetteState.normalizeKey(raw);
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() > 40) {
            key = key.substring(0, 40).trim();
        }
        return key.isBlank() ? null : key;
    }

    private static String displayLabel(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        String[] parts = label.replace('_', ' ').split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString().trim();
    }

    private static String buildUserPrompt(SilhouetteState current, Map<String, Object> event) {
        StringBuilder buf = new StringBuilder();
        buf.append("current_silhouette:\n");
        buf.append(current == null ? "maturity=empty\n" : current.digest(320)).append('\n');
        buf.append("event:\n");
        appendField(buf, "event_id", event.get("eventId"), 40);
        appendField(buf, "source", event.get("source"), 48);
        appendField(buf, "source_id", event.get("sourceId"), 96);
        appendField(buf, "prompt_id", event.get("promptId"), 96);
        appendField(buf, "question", event.get("question"), 140);
        appendField(buf, "answer", event.get("answer"), 180);
        appendField(buf, "conversation", event.get("conversation"), 160);
        appendField(buf, "context", event.get("context"), 140);
        appendField(buf, "delta", event.get("delta"), 140);
        return buf.toString();
    }

    private static void appendField(StringBuilder buf, String key, Object raw, int maxChars) {
        if (buf == null || key == null || key.isBlank()) {
            return;
        }
        String value = clamp(asTrimmed(raw), maxChars);
        if (value == null) {
            value = "";
        }
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        buf.append(key).append(": \"").append(escaped).append("\"\n");
    }

    private static String asTrimmed(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static String clamp(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        int bounded = Math.max(16, maxChars);
        if (raw.length() <= bounded) {
            return raw;
        }
        return raw.substring(0, bounded).trim();
    }
}
