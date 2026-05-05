package now.calypso.backendapi.silhouette;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openai.client.OpenAIClient;

import now.calypso.backendapi.llm.OpenAIJson;

public final class SilhouetteEditor {
    private static final int MIN_LLM_ANSWER_CHARS = 15;
    private static final long LLM_MAX_OUTPUT_TOKENS_PRIMARY = 520L;
    private static final long LLM_MAX_OUTPUT_TOKENS_RETRY = 720L;
    private static final Pattern COMPARATIVE_FROM_PATTERN = Pattern.compile(
            "(?i)(?:^|\\b(?:like|as|also|and)\\s+)(?:someone\\s+like\\s+)?([a-z0-9'\\- ]{2,48})\\s+from\\s+([a-z0-9'\\- ]{2,56}?)(?=\\s*(?:where|who|because|that|which|,|\\.|;|$))");
    private static final Set<String> SEEKING_PRIVATE_PROMPT_IDS = Set.of(
            "private.drawn.to",
            "private.fictional.characters",
            "private.not.my.person",
            "private.popular.dislike",
            "private.matchmaking.followup",
            "matchmaking.followup");

    private static final String SYSTEM_PROMPT = """
            You are Calypso's silhouette mode editor.

            Task:
            - Update the user's internal relationship-mode silhouette with compact, high-signal mode ops.
            - Do not treat the user as one fixed personality.
            - A mode is a coherent cluster of how the user may show up, what they are drawn to, spark triggers, sustainability needs, aesthetic resonance, anti-patterns, tensions, evidence, and open questions.
            - Signals capture context-free facts. Silhouette captures context-dependent relationship meaning.

            Output JSON ONLY in shape:
            {"ops":[{"op":"upsert_concept","modeId":"mode_compact_label","label":"compact mode label","target":"self_expression","concept":{"id":"specific_relational_concept","label":"specific relational concept","role":"context","confidence":0.62,"strength":0.58},"evidence":{"source":"private_prompt","target":"self_expression","value":"compact derived evidence","strength":0.60,"confidence":0.62}}]}

            Allowed ops:
            - upsert_mode
            - reinforce_mode
            - deprecate_mode
            - upsert_concept
            - reinforce_concept
            - retract_concept
            - upsert_anti_pattern
            - upsert_tension
            - add_evidence
            - add_open_question

            Targets:
            - self_expression
            - seeking_expression
            - spark_triggers
            - sustainability_needs
            - aesthetic_field
            - anti_patterns
            - tensions

            Rules:
            - Keep ops minimal and high precision (0-8 ops typically).
            - Fictional characters, music, aesthetics, prompt answers, and reactions are evidence. They are not the mode itself.
            - If new evidence adds texture, reinforce or extend the nearest compatible mode.
            - If new evidence implies a distinct relationship configuration, create a new mode.
            - Separate spark from sustainability.
            - Spark means chemistry, attraction, intrigue, aesthetic pull, curiosity, or romantic energy.
            - Sustainability means consistency, emotional workability, patience, autonomy, reassurance, communication rhythm, or long-term fit.
            - Do not over-infer sustainability early. Use open questions when unsure.
            - Keep concept labels concise, neutral, and non-clinical.
            - Do not use sensitive psychological labels.
            - For dislike, turn-off, and not-my-person prompts, prefer anti_patterns or open_questions; do not create self_expression concepts from dislikes.
            - Concrete hobbies, titles, artists, games, and shows are evidence/signals, not mode labels.
            - Do not generate concept labels that simply echo the prompt category (e.g., "media revisit affinity" for a media revisit prompt, "hobby interest" for a hobbies prompt). Concept labels must name a relational pattern derived from WHAT the specific content reveals, not the prompt type itself.
            - When an answer explicitly names political movements, ideological positions, or specific intellectual domains, derive that orientation directly as the concept label (e.g., "progressive political identity", "leftist alignment", "cosmological curiosity", "startup founder identity") rather than abstracting to general traits like "curiosity", "intellectual interest", or "openness".
            - Use source=fictional_comp for fictional/character comparisons.
            - Use source=visual_aesthetic for visual aesthetics.
            - Use source=music for music taste/resonance.
            - Confidence, strength, and sourceWeight must be numeric when present.
            - No markdown, no prose outside JSON.
            - Do not reuse example IDs or labels from this prompt.

            Signal snapshot synthesis (when signal_snapshot is present):
            - signal_snapshot shows the user's current full signal picture: self+, self-, seeking+, seeking-.
            - Use signal_snapshot to synthesize gestalt aesthetic identities that no single event reveals on its own.
            - If self+ signals cluster into a coherent aesthetic (e.g., bladee + cloud_rap + anime + soulslike games), generate a named aesthetic identity concept ("underground subculture identity", "y2k soft club aesthetic", "niche internet aesthetic") targeting aesthetic_field.
            - If self+ signals strongly imply aesthetic incompatibility with recognizable archetypes (e.g., underground-niche cluster implies incompatibility with mainstream-pop / tradwife / church-social worlds), generate an anti_pattern without requiring explicit dislike statements — name the archetype incompatibility specifically, not generically.
            - If signal_snapshot shows contradictions between self+ signals (e.g., home_socializing AND nightlife, gym AND late_night), generate a upsert_tension op naming the contradiction.
            - Fictional character signals (spark_triggers) translate to relational archetypes: extract the relational quality the character embodies (intelligence+independence, moral courage, fierce loyalty) as the concept, not just the character name. Add the character name as evidence value only.
            - Do not restate individual signals from signal_snapshot as separate concept ops — synthesize up to pattern level. Individual signals are already stored; the silhouette's value is the gestalt interpretation above them.
            - When the current event adds evidence that completes a partial cluster (e.g., the new event is the third niche-aesthetic signal), synthesize the cluster concept now rather than waiting.
            - Mode differentiation: prefer reinforcing an existing mode over creating a new one unless the new evidence implies a genuinely distinct relationship configuration.
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
        int retryMinLen = isExclusionEvent(event) ? 15 : 80;
        if ((parsed == null || parsed.isEmpty()) && answer != null && answer.trim().length() >= retryMinLen) {
            parsed = llmPatch(client, user, source, promptId, LLM_MAX_OUTPUT_TOKENS_RETRY);
        }
        if (parsed != null && !parsed.isEmpty()) {
            maybeAugmentFictionalCompEvidence(parsed, promptId, question, answer);
            return parsed;
        }
        SilhouettePatch fallback = heuristicFallbackPatch(source, promptId, question, answer);
        maybeAugmentFictionalCompEvidence(fallback, promptId, question, answer);
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
        if (normalizedSource.contains("facecard_behavior")) {
            return true;
        }
        if (normalizedSource.contains("public_prompt_reaction")) {
            return true;
        }
        if ("private.popular.dislike".equals(normalizedPrompt) && answerLen < 64) {
            return true;
        }
        // Identity-forward prompts carry high relational signal even in short answers;
        // always use the LLM path so the answer gets proper interpretive weight.
        boolean isIdentityForwardPrompt = "private.most.myself".equals(normalizedPrompt)
                || "private.stuck.with".equals(normalizedPrompt)
                || "private.communities.scene".equals(normalizedPrompt);
        if (isIdentityForwardPrompt && answerLen >= 5) {
            return false;
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
        String summary = normalizeHeuristicText(answer);
        if (summary.isBlank()) {
            return fallback;
        }

        boolean explicitDislike = "private.popular.dislike".equals(normalizedPrompt)
                || normalizedQuestion.contains("don't like")
                || normalizedQuestion.contains("don't get")
                || normalizedQuestion.contains("turn off")
                || normalizedQuestion.contains("not my person")
                || normalizedSource.contains("dislike");
        String modeLabel = heuristicModeLabel(normalizedSource, normalizedPrompt, explicitDislike);
        String modeId = SilhouetteModelUtils.normalizeId(null, "mode", modeLabel);
        String evidenceSource = SilhouetteModeMerger.normalizeEvidenceSource(null, source, promptId);

        if (explicitDislike) {
            SilhouetteAntiPattern anti = new SilhouetteAntiPattern();
            anti.label = summary;
            anti.id = SilhouetteModelUtils.normalizeId(null, "anti", summary);
            anti.scope = "relational";
            anti.severity = "moderate";
            anti.confidence = 0.60;
            fallback.ops.add(SilhouettePatch.Op.upsertAntiPattern(
                    modeId,
                    modeLabel,
                    anti,
                    evidence(evidenceSource, "anti_patterns", summary, 0.62, 0.60)));
            return fallback;
        }

        String target = heuristicTarget(normalizedSource, normalizedPrompt, normalizedQuestion);
        SilhouetteConcept concept = new SilhouetteConcept();
        concept.label = summary;
        concept.id = SilhouetteModelUtils.normalizeId(null, "concept", summary);
        concept.role = "context";
        concept.confidence = heuristicConfidenceForPrompt(normalizedPrompt, target);
        concept.strength = 0.52;
        fallback.ops.add(SilhouettePatch.Op.upsertConcept(
                modeId,
                modeLabel,
                target,
                concept,
                evidence(evidenceSource, target, summary, 0.56, concept.confidence)));
        if ("spark_triggers".equals(target) || "aesthetic_field".equals(target)) {
            fallback.ops.add(SilhouettePatch.Op.addOpenQuestion(
                    modeId,
                    modeLabel,
                    "Is this mostly aesthetic/media resonance, or something this user wants in real partners?"));
        }
        return fallback;
    }

    private static String heuristicTarget(String normalizedSource, String normalizedPrompt, String normalizedQuestion) {
        if ("private.visual.aesthetic".equals(normalizedPrompt) || "private.color.presence".equals(normalizedPrompt)) {
            return "aesthetic_field";
        }
        if ("private.music.feels.like".equals(normalizedPrompt)) {
            return "aesthetic_field";
        }
        if ("private.fictional.characters".equals(normalizedPrompt) || "private.drawn.to".equals(normalizedPrompt)) {
            return "spark_triggers";
        }
        if (normalizedSource != null && normalizedSource.contains("matchmaking_followup")) {
            return "seeking_expression";
        }
        if (normalizedPrompt != null && SEEKING_PRIVATE_PROMPT_IDS.contains(normalizedPrompt)) {
            return "seeking_expression";
        }
        if (normalizedQuestion != null
                && (normalizedQuestion.contains("drawn to")
                        || normalizedQuestion.contains("looking for")
                        || normalizedQuestion.contains("in a partner"))) {
            return "seeking_expression";
        }
        return "self_expression";
    }

    private static String heuristicModeLabel(String normalizedSource, String normalizedPrompt, boolean explicitDislike) {
        if (explicitDislike) {
            return "boundary clarity";
        }
        if ("private.visual.aesthetic".equals(normalizedPrompt) || "private.color.presence".equals(normalizedPrompt)) {
            return "aesthetic resonance";
        }
        if ("private.music.feels.like".equals(normalizedPrompt)) {
            return "music-shaped presence";
        }
        if ("private.fictional.characters".equals(normalizedPrompt) || "private.drawn.to".equals(normalizedPrompt)) {
            return "romantic pull";
        }
        if ("private.most.myself".equals(normalizedPrompt) || "private.stuck.with".equals(normalizedPrompt)
                || "private.communities.scene".equals(normalizedPrompt)) {
            return "core identity";
        }
        if (normalizedSource != null && normalizedSource.contains("matchmaking_followup")) {
            return "relationship fit";
        }
        return "emerging pattern";
    }

    private static double heuristicConfidenceForTarget(String target) {
        return switch (SilhouetteEvidence.normalizeTarget(target)) {
            case "seeking_expression" -> 0.52;
            case "spark_triggers" -> 0.50;
            case "aesthetic_field" -> 0.50;
            default -> 0.48;
        };
    }

    private static double heuristicConfidenceForPrompt(String normalizedPrompt, String target) {
        boolean isIdentityForward = "private.most.myself".equals(normalizedPrompt)
                || "private.stuck.with".equals(normalizedPrompt)
                || "private.communities.scene".equals(normalizedPrompt);
        if (isIdentityForward && "self_expression".equals(SilhouetteEvidence.normalizeTarget(target))) {
            return 0.62;
        }
        return heuristicConfidenceForTarget(target);
    }

    private static SilhouetteEvidence evidence(String source, String target, String value, double strength, double confidence) {
        SilhouetteEvidence evidence = new SilhouetteEvidence();
        evidence.source = SilhouetteEvidence.normalizeSource(source);
        evidence.target = SilhouetteEvidence.normalizeTarget(target);
        evidence.value = SilhouetteModelUtils.text(value, 180);
        evidence.strength = strength;
        evidence.confidence = confidence;
        evidence.sourceWeight = SilhouetteEvidence.defaultSourceWeight(evidence.source);
        evidence.createdAt = System.currentTimeMillis();
        return evidence;
    }

    private static String normalizeHeuristicText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.length() > 220) {
            normalized = normalized.substring(0, 220).trim();
        }
        normalized = normalized.replaceAll("(?i)^i\\s*(?:'d|d|would)?\\s*say\\s*(?:like\\s*)?", "");
        normalized = normalized.replaceAll("(?i)^it\\s*(?:is|'s)\\s*(?:like\\s*)?", "");
        normalized = normalized.replaceAll("(?i)^i\\s*(?:am|'m)\\s+", "");
        normalized = normalized.replaceAll("(?i)^like\\s+", "");
        normalized = normalized.replaceAll("(?i)^kind of\\s+", "");
        normalized = normalized.replaceAll("(?i)^sort of\\s+", "");
        normalized = normalized.replaceAll("^[\\s,:;\\-]+", "").trim();
        return normalized.isBlank() ? raw.trim() : normalized;
    }

    private static void maybeAugmentFictionalCompEvidence(
            SilhouettePatch patch,
            String promptId,
            String question,
            String answer) {
        if (patch == null || answer == null || answer.isBlank() || !isComparativePrompt(promptId, question)) {
            return;
        }
        LinkedHashSet<String> existing = new LinkedHashSet<>();
        for (SilhouettePatch.Op op : patch.ops) {
            if (op != null && op.evidence != null && op.evidence.value != null) {
                existing.add(op.evidence.value.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String comp : extractComparativeEvidence(answer)) {
            String key = comp.trim().toLowerCase(Locale.ROOT);
            if (key.isBlank() || existing.contains(key)) {
                continue;
            }
            SilhouetteEvidence evidence = evidence("fictional_comp", "spark_triggers", comp, 0.72, 0.62);
            SilhouettePatch.Op op = new SilhouettePatch.Op();
            op.op = "add_evidence";
            op.modeId = SilhouetteModelUtils.normalizeId(null, "mode", "romantic pull");
            op.label = "romantic pull";
            op.target = "spark_triggers";
            op.evidence = evidence;
            patch.ops.add(op);
            existing.add(key);
            if (existing.size() >= 4) {
                break;
            }
        }
    }

    private static List<String> extractComparativeEvidence(String answer) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Matcher matcher = COMPARATIVE_FROM_PATTERN.matcher(answer);
        while (matcher.find()) {
            String rawComparisons = matcher.group(1);
            String rawSource = matcher.group(2);
            String sourceLabel = displayLabel(SilhouetteModelUtils.normalizeKey(trimComparisonSource(rawSource)));
            if (sourceLabel.isBlank()) {
                continue;
            }
            String comparisons = compactComparisons(rawComparisons);
            String value = comparisons.isBlank() ? sourceLabel : comparisons + " (" + sourceLabel + ")";
            value = SilhouetteModelUtils.text(value, 160);
            String key = value.toLowerCase(Locale.ROOT);
            if (!value.isBlank() && !seen.contains(key)) {
                out.add(value);
                seen.add(key);
            }
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
                .replaceAll("(?i)\\b(i'?d\\s+say|id\\s+say|i\\s+would\\s+say|also)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.split("(?i)\\s*(?:,|/|\\bor\\b|\\band\\b)\\s*");
        ArrayList<String> kept = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim();
            if (cleaned.isBlank()) {
                continue;
            }
            kept.add(SilhouetteModelUtils.text(cleaned, 32));
            if (kept.size() >= 3) {
                break;
            }
        }
        return String.join(", ", kept);
    }

    private static String trimComparisonSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("(?i)\\b(where|who|because|that|which)\\b.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isComparativePrompt(String promptId, String question) {
        String prompt = promptId == null ? "" : promptId.toLowerCase(Locale.ROOT);
        if ("private.drawn.to".equals(prompt)
                || "private.fictional.characters".equals(prompt)) {
            return true;
        }
        if (question == null || question.isBlank()) {
            return false;
        }
        String lowered = question.toLowerCase(Locale.ROOT);
        return lowered.contains("drawn to")
                || lowered.contains("fictional character");
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

    private static boolean isExclusionEvent(Map<String, Object> event) {
        String promptId = asTrimmed(event.get("promptId"));
        String question = asTrimmed(event.get("question"));
        String normalizedPrompt = promptId == null ? "" : promptId.toLowerCase(Locale.ROOT);
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT).replace('’', '\'');
        return normalizedPrompt.contains("not.my.person")
                || normalizedPrompt.contains("popular.dislike")
                || normalizedQuestion.contains("not my person")
                || normalizedQuestion.contains("don't like")
                || normalizedQuestion.contains("turn off")
                || normalizedQuestion.contains("turn-off")
                || normalizedQuestion.contains("dealbreaker");
    }

    private static String buildUserPrompt(SilhouetteState current, Map<String, Object> event) {
        StringBuilder buf = new StringBuilder();
        buf.append("current_silhouette:\n");
        buf.append(current == null ? "maturity=empty\n" : current.digest(900)).append('\n');
        String signalSummary = asTrimmed(event.get("signalSummary"));
        if (signalSummary != null && !signalSummary.isBlank()) {
            buf.append("signal_snapshot:\n");
            String clamped = signalSummary.length() > 500 ? signalSummary.substring(0, 500) : signalSummary;
            buf.append(clamped).append('\n');
        }
        buf.append("event:\n");
        appendField(buf, "event_id", event.get("eventId"), 40);
        appendField(buf, "source", event.get("source"), 48);
        appendField(buf, "source_id", event.get("sourceId"), 96);
        appendField(buf, "prompt_id", event.get("promptId"), 96);
        appendField(buf, "question", event.get("question"), 180);
        appendField(buf, "answer", event.get("answer"), 260);
        appendField(buf, "conversation", event.get("conversation"), 220);
        appendField(buf, "context", event.get("context"), 180);
        appendField(buf, "delta", event.get("delta"), 180);
        if (isExclusionEvent(event)) {
            buf.append("framing: \"exclusion\" — this answer describes what the user rejects or avoids in a partner. Generate anti_patterns ops, not self_expression. Use seeking_expression only if the concept implies a positive requirement.\n");
        }
        return buf.toString();
    }

    private static void appendField(StringBuilder buf, String key, Object raw, int maxChars) {
        String value = SilhouetteModelUtils.text(raw, maxChars);
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
}
