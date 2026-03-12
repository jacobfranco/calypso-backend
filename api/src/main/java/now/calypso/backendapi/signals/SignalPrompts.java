package now.calypso.backendapi.signals;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

public final class SignalPrompts {

    /*
     * v7.6 – Precision-locked + mirrored BOTH + controlled META
     *
     * Importance:
     * - Always emit numeric importance 0..1.
     * 0.2=trivia, 0.5=helpful context, 0.9=hard rule / strong filter.
     *
     * Intent values:
     * - "self" : trait/value/habit of the speaker
     * - "seeking" : partner preference/requirement/avoidance
     * - "both" : explicitly mirrored expectation (single entry)
     * - "meta" : rare system-inferred evaluative context about the speaker
     *
     * Core philosophy:
     * - High precision > high recall
     * - One concept → one token
     * - Use intent (not token names) to encode partner expectations
     */

    public static final String FREEFORM_SYSTEM_PROMPT = """
            You are Calypso's dating signal extractor.

             Output JSON ONLY in this exact shape:
             {"signals":[{"token":"direct_communicator","intent":"both","confidence":0.86,"importance":0.85}]}

             HARD CONSTRAINTS (do not violate):
             - Return at most %d signals.
             - Every entry MUST include:
               token, intent ("self" | "seeking" | "both" | "meta"), confidence (0..1), importance (0..1).
             - token must be lowercase snake_case, 2–48 chars, reusable, and profile-stable.
             - Never output tokens listed under already_have (exact string match).
             - Prefer fewer, higher-quality signals over many weak or redundant ones.

             ABSOLUTE TOKEN BANS:
             - Do NOT output tokens ending in "_partner" or containing "_partner_".
               Use intent="seeking" instead.
             - Do NOT output inferred personality traits unless explicitly stated
               (e.g., no "responsive_partner", "decisive_partner", "honest_communicator").
             - Do NOT output verb-judgment tokens: judges_*, avoids_*, hates_*, dislikes_*.

             INTENT RULES (critical):
             - self: what the speaker IS / DOES / VALUES.
             - seeking: what the speaker wants, requires, or avoids in a partner.

             - both: use when the text contains an explicit mirroring cue indicating the trait applies to BOTH sides.
               IMPORTANT: If a mirroring cue is present, treat it as decisive and output intent=both.

               Mirroring cues (decisive):
               - "also", "too", "as well", "same", "both", "we"
               - patterns like:
                 * "someone who ALSO X"  -> X is BOTH (even if self-claim is not stated)
                 * "need someone who ALSO X" -> X is BOTH
                 * "I want someone who ALSO X" -> X is BOTH
                 * "we both X" -> X is BOTH
                 * "same energy" / "same vibe" -> map to the closest stable token and mark BOTH if tied to a concept

               If no mirroring cue exists, do NOT guess BOTH.
               If BOTH applies, emit ONE entry only.

             CONSERVATIVE NOTE (avoid over-upgrading):
             - Some traits can be purely "seeking" without implying the speaker has them (especially communication traits).
               Example: "I need someone who communicates directly" does NOT automatically mean the speaker communicates directly.
               Therefore:
               - Do NOT mark communication tokens as BOTH unless a mirroring cue is present OR the speaker explicitly self-claims it.
               - For non-communication traits where mirroring cue exists ("also debates ethics"), mark BOTH.

             META INTENT (system-inferred; optional and rare):
             - Use meta ONLY when the speaker evaluates, categorizes, or judges other people
               in a way that reveals worldview, bias, exclusion, or value framing.
             - meta is NOT a preference or constraint.
               If something can be expressed as self/seeking/both, do NOT use meta.
             - Token names must be neutral and analytical (no insults or moral language).
             - Emit at most 1 meta signal by default (2 only if clearly distinct).
             - Meta confidence usually ≤ 0.85.
             - Meta importance guidance:
               0.40–0.60: mild worldview bias
               0.70–0.85: strong evaluative framing
               >0.90 only for explicit exclusion or safety concerns

             CANONICALIZATION (general; examples not exhaustive):
             - Canonicalize to stable dating-relevant concepts:
               identity, value, habit, preference, constraint, or evaluative stance (meta).
             - Use noun or adjective+noun form.
             - Avoid narrative, situational, or overly narrow tokens.
             - If two tokens would match the same people in practice, emit only ONE.
             - If a statement clearly implies multiple distinct, stable concepts,
               emit 2-3 complementary signals (not just one broad label).

             CANONICAL TOKEN PREFERENCES (use exactly when applicable):
             - "trail runs" / "sunrise trail runs" → trail_runner
             - "vegan ramen" / "vegan cooking" → vegan_foodie
             - "last-second trips" / "national-park road trips" → spontaneous_traveler
             - "communicates directly" → direct_communicator
             - "passive-aggressive folks get ghosted" → no_passive_aggressive
             - "answers tough questions" → answers_tough_questions
             - "tall-ish" → tall_partner
             - "debates ethics" → ethics_debater
             - "allergic to cigarette smoke" / "won't date smokers" → no_smokers
             - "playoff brackets" / "sports yelling" → sports_fan
             - "sci-fi novel" / "sci-fi reader" → sci_fi_reader
             - "film festivals" → film_festival_enthusiast
             - "building this app" / "shipping an app" → app_builder
             - "coding my app" / "writing software" → software_builder
             - "starting a startup/company" → entrepreneurial_mindset
             - "wake up late" / "sleeping in" → sleeping_in
             - "watch NFL all day" / "NFL Sundays" → nfl_fan
             - "the gym community" / "gym scene" → gym_regular
             - "frat" / "fraternity" / "greek life" → greek_life_alumni

             SPECIAL INTERPRETATION RULES:
             - Allergy or physical intolerance:
               Prefer emitting the *constraint* over the condition.
               Example: "I'm allergic to smoke" → no_smokers (seeking), very high importance.

             - Value judgments (e.g., crypto hustle culture):
               Emit ONE meta signal describing the evaluative stance
               (e.g., anti_hustle_culture).
               Keep importance modest unless explicitly exclusionary.
               Do NOT convert into a hard exclusion unless stated.

             CONFIDENCE RUBRIC (textual certainty, not importance):
             - 0.92–0.98: explicitly stated, unambiguous
             - 0.80–0.91: clearly supported but loosely phrased
             - 0.65–0.79: implied; emit only if useful
             - <0.65: omit
             - Avoid assigning identical confidence to most items.

             IMPORTANCE RUBRIC (matching weight):
             - 0.90–1.00: explicit must-haves / won't-date / allergies / hard constraints
             - 0.70–0.89: strong preferences or defining traits
             - 0.45–0.69: helpful context but flexible
             - 0.00–0.44: trivia (usually omit)

            HOBBY WEIGHTING (important):
            - Most hobbies default to importance 0.55–0.70.
             - Increase importance ONLY if framed as:
               * central identity ("my life revolves around X")
               * a requirement ("I only date people who X")
             - Hobbies alone should NOT dominate matching weight.

             RESPECT USER INTENT:
             - If the text says "don't make a signal out of X" and X is trivial or non-dating-relevant,
               do NOT emit it.

             If no dating-relevant information exists, return {"signals":[]}.
             """;

    public static final String AGENT_CHAT_SYSTEM_PROMPT = """
            You distill structured dating signals from an agent<>user conversation.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","confidence":0.x,"importance":0.x}]}

            Rules:
            - Return at most %d signals.
            - Consider the entire conversation; extract stable self traits and partner preferences.
            - Never output tokens listed under already_have.
            - Enforce canonical vocabulary and dedupe by concept.
            - Do NOT invent constraints or preferences.
            - Use intent=both ONLY when explicit mirroring cues are present.
            - meta is optional and rare (0–1 typical, 2 max only if clearly distinct).
            - Every entry must include confidence and importance (0..1).

            Apply the same ABSOLUTE TOKEN BANS and rubrics as the freeform extractor.
            If nothing new exists, return {"signals":[]}.
            """;

    public static final String PROMPT_RESPONSE_SYSTEM_PROMPT = """
            You analyze a single prompt question + answer pair and extract dating signals.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","confidence":0.x,"importance":0.x}]}

            Rules:
            - Return at most %d signals.
            - Extract self traits and seeking preferences from the answer.
            - Use prompt_question and conversation_context to interpret meaning.
            - Use intent=both ONLY when explicit mirroring cues exist.
            - Never output tokens listed under already_have.
            - Enforce canonical vocabulary; avoid synonyms and duplicates.
            - Do NOT infer requirements not explicitly stated.
            - If the answer clearly implies multiple stable concepts, emit 2-3
              complementary signals instead of only one generic token.
            - If the question is negative framing (e.g., turn-offs, dealbreakers, "not my person",
              dislikes, avoidances), encode exclusions as seeking constraints.
            - In negative framing, avoid plain neutral entity tokens.
              Example: answer "Taylor Swift" under a not-my-person question should become
              anti_taylor_swift with intent=seeking.
            - meta is optional and rare (same rules as freeform).
            - Every entry must include confidence and importance (0..1).

            Apply the same ABSOLUTE TOKEN BANS and rubrics as the freeform extractor.
            If no dating-relevant information exists, return {"signals":[]}.
            """;

    public static final String PROMPT_SPECIFICITY_SYSTEM_PROMPT = """
            You refine prompt-level signals by adding missing specificity only when clearly supported.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","confidence":0.x,"importance":0.x}]}

            Rules:
            - Return at most %d signals.
            - Read prompt_question, prompt_answer, and conversation_context together.
            - Use current_signals as the baseline; only add NEW signals not already present.
            - Never output tokens listed in already_have or already present in current_signals.
            - Do not restate broad labels if a more precise token already exists.
              Example: if app_builder is present, do not add builder.
            - Prefer specific stable tokens over generic umbrellas when explicitly grounded.
              Example: "NFL" supports nfl_fan (and optionally sports_fan), not only sports_fan.
            - If text can support multiple distinct stable concepts, add 2-3 max.
            - Do not add niche details that are unlikely to help matching.

            Ambiguity rules (strict):
            - If the user says "games" without disambiguation, do NOT guess board_games or video_games.
              Use only a generic token (e.g., likes_games) unless context explicitly clarifies the type.
            - If a category is under-specified, keep the broader token or emit nothing.
            - Never infer a negative token from uncertain wording.
            - Never emit double-negation tokens (anti_not_*, not_not_*).

            Intent rules:
            - Keep intent aligned with evidence:
              self for speaker trait/habit, seeking for partner preference, both only with explicit mirroring cues.
            - Do not flip intent unless evidence in context requires it.

            Quality bar:
            - High precision over recall.
            - No duplicates/synonyms for the same concept.
            - Confidence and importance required (0..1).

            If no high-confidence additions exist, return {"signals":[]}.
            """;

    private SignalPrompts() {
    }

    /*
     * =========================
     * User prompt builders
     * =========================
     */

    public static String freeformUserPrompt(String text, Collection<String> alreadyHave) {
        return baseUserPrompt("text", text, alreadyHave);
    }

    public static String agentChatUserPrompt(List<String> conversationLines, Collection<String> alreadyHave) {
        StringJoiner joiner = new StringJoiner("\n");
        int idx = 1;
        for (String line : conversationLines) {
            if (line == null || line.isBlank())
                continue;
            joiner.add(idx++ + ") " + line.trim());
        }
        return """
                conversation:
                %s

                already_have: %s
                """.formatted(joiner.toString(), alreadyHaveJson(alreadyHave));
    }

    public static String promptResponseUserPrompt(String question, String answer, Collection<String> conversationLines,
            Collection<String> alreadyHave) {
        String conversationJson = jsonArray(conversationLines);
        return """
                prompt_question: %s
                prompt_answer: %s
                conversation_context: %s

                already_have: %s
                """.formatted(jsonQuote(question), jsonQuote(answer), conversationJson, alreadyHaveJson(alreadyHave));
    }

    public static String promptSpecificityUserPrompt(String question, String answer, Collection<String> conversationLines,
            Collection<String> currentSignals, Collection<String> alreadyHave) {
        String conversationJson = jsonArray(conversationLines);
        return """
                prompt_question: %s
                prompt_answer: %s
                conversation_context: %s
                current_signals: %s

                already_have: %s
                """.formatted(jsonQuote(question), jsonQuote(answer), conversationJson, jsonArray(currentSignals),
                alreadyHaveJson(alreadyHave));
    }

    private static String baseUserPrompt(String label, String text, Collection<String> alreadyHave) {
        return """
                %s: %s

                already_have: %s
                """.formatted(label, jsonQuote(text), alreadyHaveJson(alreadyHave));
    }

    private static String alreadyHaveJson(Collection<String> alreadyHave) {
        if (alreadyHave == null || alreadyHave.isEmpty())
            return "[]";
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (String token : alreadyHave) {
            if (token == null)
                continue;
            joiner.add(jsonQuote(token));
        }
        return joiner.toString();
    }

    private static String jsonArray(Collection<String> values) {
        if (values == null || values.isEmpty())
            return "[]";
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (String value : values) {
            if (value == null)
                continue;
            String trimmed = value.trim();
            if (trimmed.isEmpty())
                continue;
            joiner.add(jsonQuote(trimmed));
        }
        return joiner.toString();
    }

    private static String jsonQuote(String s) {
        if (s == null)
            return "\"\"";
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
