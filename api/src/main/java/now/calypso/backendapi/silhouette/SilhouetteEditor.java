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
    private static final Pattern COMPARATIVE_FROM_PATTERN = Pattern.compile(
            "(?i)([a-z0-9'\\- ]{2,72})\\s+from\\s+([a-z0-9'\\- ]{2,56})");
    private static final String SYSTEM_PROMPT = """
            You are Calypso's silhouette editor.

            Task:
            - Update the user's silhouette with compact patch ops.
            - Silhouette captures context-dependent psychology and relational dynamics.
            - Signals capture context-free facts (hobbies, interests, explicit media/franchises).

            Output JSON ONLY in shape:
            {"ops":[{"op":"set_facet","key":"relationship_dynamic","summary":"...","confidence":0.72,"evidenceIds":["ev_x"]}]}

            Allowed ops:
            - set_story
            - set_facet
            - reinforce_facet
            - add_anchor
            - add_meta_observation
            - add_evidence
            - prune_stale

            Constraints:
            - Keep ops minimal and high precision (0-6 ops typically).
            - Prefer only these facet keys:
              self_core, seeking_core, relationship_dynamic, energy_style,
              communication_style, emotional_style, trajectory, hard_boundaries.
            - When the user references comparative examples (characters/figures/titles), add `add_anchor` ops with kind `partner_comp`.
            - Anchor labels can include contextual names (characters/examples), but do not turn them into generic signal tags.
            - Meta observations must be neutral (no moralizing language).
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
        String raw = OpenAIJson.call(client, SYSTEM_PROMPT, user);
        SilhouettePatch parsed = SilhouettePatch.fromRawJson(raw);
        if (parsed != null && !parsed.isEmpty()) {
            maybeAugmentPartnerCompAnchors(parsed, promptId, question, answer);
            return parsed;
        }
        SilhouettePatch fallback = heuristicFallbackPatch(source, promptId, question, answer);
        maybeAugmentPartnerCompAnchors(fallback, promptId, question, answer);
        return fallback;
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

        boolean explicitDislike = "private.popular.dislike".equals(normalizedPrompt)
                || normalizedQuestion.contains("don't like")
                || normalizedQuestion.contains("don't get");
        if (explicitDislike) {
            fallback.ops.add(new SilhouettePatch.Op(
                    "set_facet",
                    "hard_boundaries",
                    summary,
                    null,
                    null,
                    null,
                    0.56,
                    List.of()));
        } else if (question != null && question.toLowerCase(Locale.ROOT).contains("drawn")) {
            fallback.ops.add(new SilhouettePatch.Op(
                    "set_facet",
                    "relationship_dynamic",
                    summary,
                    null,
                    null,
                    null,
                    0.58,
                    List.of()));
        } else if (normalizedSource.contains("private")) {
            fallback.ops.add(new SilhouettePatch.Op(
                    "set_facet",
                    "seeking_core",
                    summary,
                    null,
                    null,
                    null,
                    0.52,
                    List.of()));
        } else {
            fallback.ops.add(new SilhouettePatch.Op(
                    "set_facet",
                    "self_core",
                    summary,
                    null,
                    null,
                    null,
                    0.42,
                    List.of()));
        }
        fallback.ops.add(new SilhouettePatch.Op(
                "add_evidence",
                null,
                summary,
                null,
                null,
                null,
                0.40,
                List.of()));
        maybeAugmentPartnerCompAnchors(fallback, promptId, question, answer);
        return fallback;
    }

    private static void maybeAugmentPartnerCompAnchors(
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
        LinkedHashSet<String> existingLabels = new LinkedHashSet<>();
        for (SilhouettePatch.Op op : patch.ops) {
            if (op == null || op.label == null || op.label.isBlank()) {
                continue;
            }
            String normalized = normalizeAnchorLabel(op.label);
            if (normalized != null) {
                existingLabels.add(normalized);
            }
        }
        for (SilhouettePatch.Op op : extractPartnerCompAnchors(answer)) {
            if (op == null || op.label == null || op.label.isBlank()) {
                continue;
            }
            String normalizedLabel = normalizeAnchorLabel(op.label);
            if (normalizedLabel == null || existingLabels.contains(normalizedLabel)) {
                continue;
            }
            existingLabels.add(normalizedLabel);
            patch.ops.add(op);
            if (existingLabels.size() >= 4) {
                break;
            }
        }
    }

    private static List<SilhouettePatch.Op> extractPartnerCompAnchors(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        ArrayList<SilhouettePatch.Op> out = new ArrayList<>();
        LinkedHashSet<String> seenLabels = new LinkedHashSet<>();
        Matcher matcher = COMPARATIVE_FROM_PATTERN.matcher(answer);
        while (matcher.find()) {
            String rawComparisons = matcher.group(1);
            String rawSource = matcher.group(2);
            String sourceLabel = normalizeAnchorLabel(rawSource);
            if (sourceLabel == null || seenLabels.contains(sourceLabel)) {
                continue;
            }
            String sourceDisplay = displayLabel(sourceLabel);
            String comparisons = compactComparisons(rawComparisons);
            String summary = comparisons.isBlank()
                    ? "Comparative reference from " + sourceDisplay + "."
                    : ("Comparative reference: " + comparisons + " from " + sourceDisplay + ".");
            out.add(new SilhouettePatch.Op(
                    "add_anchor",
                    null,
                    summary,
                    null,
                    sourceLabel,
                    "partner_comp",
                    0.62,
                    List.of()));
            seenLabels.add(sourceLabel);
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

    private static String normalizeAnchorLabel(String raw) {
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
        buf.append(current == null ? "maturity=empty\nstory:\n" : current.digest(520)).append('\n');
        buf.append("event:\n");
        appendField(buf, "event_id", event.get("eventId"), 40);
        appendField(buf, "source", event.get("source"), 48);
        appendField(buf, "source_id", event.get("sourceId"), 96);
        appendField(buf, "prompt_id", event.get("promptId"), 96);
        appendField(buf, "question", event.get("question"), 180);
        appendField(buf, "answer", event.get("answer"), 260);
        appendField(buf, "conversation", event.get("conversation"), 220);
        appendField(buf, "context", event.get("context"), 180);
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
