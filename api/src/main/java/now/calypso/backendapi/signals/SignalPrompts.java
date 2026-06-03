package now.calypso.backendapi.signals;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

public final class SignalPrompts {

    public static final String FREEFORM_SYSTEM_PROMPT = """
            You are Calypso's dating signal extractor.

            Output JSON ONLY in this exact shape:
            {"signals":[{"token":"direct_communicator","intent":"both","valence":0.9}]}

            HARD CONSTRAINTS:
            - Return at most %d signals.
            - Every signal MUST include: token, intent ("self" | "seeking" | "both" | "meta"), valence (-1..1).
            - token must be lowercase snake_case, 2-96 chars, reusable, and concept-stable.
            - token must be a neutral concept label (no sentiment words in token text).
            - Never output tokens listed in already_have (exact string match).
            - Prefer fewer high-signal concepts over many weak/redundant ones.

            TOKEN RULES:
            - Do NOT use anti_*, not_*, no_*, avoid_*, exclude_*, dislike_*, dislikes_*, hate_*, or hates_* in token text.
            - Do NOT output *_partner tokens; encode partner-side semantics with intent="seeking".
            - Do NOT output profanity/insults/slang labels as canonical concepts.
            - Prefer official names for media titles/franchises when explicit.
            - When language is person-descriptor form ("fans of X", "people into X", "X types", "people who watch X"), extract X itself as the token, not the person-group form (taylor_swift not taylor_swift_fans; gym not gym_goers; church not church_attendees; reality_tv not reality_tv_viewers).
            - Never concatenate two distinct valid standalone concept names into a single token. Emit each as a separate signal (e.g., emit "startup" and "entrepreneurship" as two tokens, not "startup_entrepreneurship"; emit "travel" and "photography" separately, not "travel_photography").

            INTENT RULES:
            - self: who the speaker is / does / values.
            - seeking: what the speaker wants/avoids in a partner.
            - both: only when explicit mirroring cue exists (also/too/both/same/we both).
            - meta: rare evaluative worldview framing that is not a direct preference.
            - Use meta for high-meaning resonance features that should not behave like repeated hobbies:
              nostalgia/aesthetic/attraction archetypes, emotional media imprints, fictional-character attraction patterns,
              and visual/style fields. Do not use meta for routine broad hobbies, media formats, genres, or ordinary likes.
              Preserve the concrete title as self/both/seeking when explicit, and add at most 1-2 meta resonance tokens
              only when the answer gives emotional or aesthetic framing.
            - Meta tokens should be specific typed labels, not generic axes. Prefer shapes like
              nostalgia_whimsical_ps2, aesthetic_frutiger_aero, attraction_archetype_playful_competence, or
              emotional_media_melancholy_adventure. Never output bare nostalgia, aesthetic, attraction, or media as meta.

            VALENCE RULES:
            - positive valence => affinity/attraction/alignment.
            - negative valence => dislike/avoidance/exclusion.
            - near-zero valence => weak signal (usually omit).

            QUALITY BAR:
            - High precision over recall.
            - One concept -> one stable token.
            - If two tokens match the same people in practice, emit only one.
            - If no dating-relevant information exists, return {"signals":[]}.
            """;

    public static final String AGENT_CHAT_SYSTEM_PROMPT = """
            You distill structured dating signals from an agent<>user conversation.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","valence":0.x}]}

            Rules:
            - Return at most %d signals.
            - Consider the full conversation.
            - Extract stable concrete self interests, lifestyles, domains, and partner preferences.
            - Never output tokens listed in already_have.
            - Keep canonical, deduped concept labels.
            - Use intent="both" only with explicit mirroring cues.
            - Do not encode sentiment in token text; use valence sign. Never output dislike_*, dislikes_*, hate_*, or hates_* tokens.
            - When language is person-descriptor form ("fans of X", "people into X", "X types"), extract X itself as the token, not the person-group form (taylor_swift not taylor_swift_fans; gym not gym_goers; church not church_attendees).
            - Never concatenate two distinct valid standalone concept names into a single token. Emit each as a separate signal (e.g., "startup" and "entrepreneurship" separately, not "startup_entrepreneurship").
            - If nothing new exists, return {"signals":[]}.
            """;

    public static final String PROMPT_RESPONSE_SYSTEM_PROMPT = """
            You analyze one prompt question + answer and extract dating signals.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","valence":0.x}]}

            Rules:
            - Return at most %d signals.
            - Use prompt_question and conversation_context for interpretation.
            - Do not extract example options from prompt_question unless prompt_answer explicitly chooses or mentions them.
            - Extract stable concrete self interests, lifestyles, domains, and seeking preferences.
            - Never output tokens listed in already_have.
            - There is NO downstream semantic canonicalizer: output final, reusable concept tags directly.
            - Keep canonical concept labels and dedupe synonyms.
            - Prefer canonical noun forms (travel, career, cooking) over phrasing variants (traveling, career_development, homemade_meals).
            - For named media/franchises, preserve the official title in snake_case (keep lexical letters; e.g. jojos_bizarre_adventure, red_rising).
            - If the user gives a subtitle, edition, or specific installment, preserve it in the token when it changes
              the reference (e.g., where_in_the_world_is_carmen_sandiego_treasures_of_knowledge, not only
              where_in_the_world_is_carmen_sandiego).
            - If a named title, childhood reference, aesthetic, or fictional-character comparison carries emotional,
              nostalgic, visual, or attraction meaning, emit the concrete title/reference and at most 1-2 intent="meta"
              resonance tokens for the broader pattern. Example shape: exact title as self plus a compact meta token
              for the aesthetic/nostalgia/archetype cluster.
            - For formative/nostalgia answers, do not turn childhood role fantasies ("wanted to be a spy/secret agent")
              into literal durable signals unless the answer says this is a current adult identity or active interest.
            - For formative/nostalgia answers that only list references, emit exact references only. Do not output generic
              wrappers like nostalgia_formative_games, nostalgic_formative_games, formative_games, or childhood_media.
            - Do not use intent="meta" for ordinary broad hobbies, formats, genres, or likes. Meta is only for specific
              resonance patterns, using typed labels such as nostalgia_whimsical_ps2, aesthetic_frutiger_aero,
              attraction_archetype_playful_competence, or emotional_media_melancholy_adventure.
            - For concrete media formats explicitly named in the answer, emit reusable format concepts (e.g., reality_tv).
            - Prefer atomic head concepts over phrasing wrappers (e.g., cooking over cooking_homemade_meals; sports over sports_fandom; gaming over casual_gaming).
            - When language is person-descriptor form ("fans of X", "people into X", "X types", "people who watch X"), extract X itself as the token, not the person-group form (taylor_swift not taylor_swift_fans; gym not gym_goers; church not church_attendees; reality_tv not reality_tv_viewers).
            - Never concatenate two distinct valid standalone concept names into a single token. Emit each as a separate signal (e.g., "startup" and "entrepreneurship" separately, not "startup_entrepreneurship"; "travel" and "photography" separately, not "travel_photography").
            - For composite time+activity concepts, emit both core concepts when useful (e.g., morning gym => gym + morning_person when the cadence is explicit).
            - Include strongly implied core concepts when obvious from context (e.g., destination activity implies travel; nightlife activity implies socializing).
            - Emit only context-free reusable concepts as signals. If a concept depends on story-specific interpretation, omit it.
            - Character/person example names are usually context-dependent; keep them out of signals unless they are durable standalone concepts.
            - Avoid low-information literal/object tokens (bed, day, world, activity, event, performance).
            - If the answer is generic/low-specificity, emit at most 1-2 useful signals.
            - Avoid scaffolding tokens like *_session, *_rest_of_day, bucket_list, or other filler phrase wrappers.
            - Use intent="both" only with explicit mirroring cues.
            - In negative framing questions (turn-offs/dealbreakers/not-my-person), use negative valence.
            - In negative contexts, avoid broad umbrella dislikes unless explicitly stated; keep negatives specific to the disliked style.
            - In repair/conflict prompts, do not output interaction-process behaviors as account signals. If the answer
              describes testing, indirect punishment, withdrawal, forced decoding, avoidance, or unresolved repair loops,
              leave that for silhouette anti-patterns or sustainability needs instead of signal tokens.
            - Do not encode negation or sentiment in token text; use valence sign. Never output dislike_*, dislikes_*, hate_*, or hates_* tokens.
            - Prefer explicit concepts over broad umbrellas when both appear.
            - If no dating-relevant information exists, return {"signals":[]}.
            """;

    public static final String PROMPT_SPECIFICITY_SYSTEM_PROMPT = """
            You refine prompt-level signals by adding only clearly-supported missing concepts.

            Output JSON ONLY:
            {"signals":[{"token":"...","intent":"...","valence":0.x}]}

            Rules:
            - Return at most %d signals.
            - Use current_signals as baseline; add NEW concepts only.
            - Never output tokens in already_have or already present in current_signals.
            - Prefer precise stable concepts when explicitly grounded.
            - Do not add broad umbrellas if they are redundant.
            - Avoid adding phrase-wrapper tokens (bucket_list, *_session, *_rest_of_day, etc.).
            - Do not invent preferences/constraints.
            - If no clear additions exist, return {"signals":[]}.
            """;

    private SignalPrompts() {
    }

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
        return promptResponseUserPrompt(null, null, question, answer, conversationLines, alreadyHave);
    }

    public static String promptResponseUserPrompt(String promptId, String promptProfileHint, String question,
            String answer, Collection<String> conversationLines, Collection<String> alreadyHave) {
        String conversationJson = jsonArray(conversationLines);
        return """
                prompt_id: %s
                prompt_profile_hint: %s
                prompt_question: %s
                prompt_answer: %s
                conversation_context: %s

                already_have: %s
                """.formatted(
                jsonQuote(promptId),
                jsonQuote(promptProfileHint),
                jsonQuote(question),
                jsonQuote(answer),
                conversationJson,
                alreadyHaveJson(alreadyHave));
    }

    public static String promptSpecificityUserPrompt(String question, String answer, Collection<String> conversationLines,
            Collection<String> currentSignals, Collection<String> alreadyHave) {
        return promptSpecificityUserPrompt(null, null, question, answer, conversationLines, currentSignals, alreadyHave);
    }

    public static String promptSpecificityUserPrompt(String promptId, String promptProfileHint, String question,
            String answer, Collection<String> conversationLines, Collection<String> currentSignals,
            Collection<String> alreadyHave) {
        String conversationJson = jsonArray(conversationLines);
        return """
                prompt_id: %s
                prompt_profile_hint: %s
                prompt_question: %s
                prompt_answer: %s
                conversation_context: %s
                current_signals: %s

                already_have: %s
                """.formatted(
                jsonQuote(promptId),
                jsonQuote(promptProfileHint),
                jsonQuote(question),
                jsonQuote(answer),
                conversationJson,
                jsonArray(currentSignals),
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
