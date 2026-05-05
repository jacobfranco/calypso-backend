package now.calypso.backendapi.signals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small per-prompt extraction profiles used to keep signal extraction focused
 * and cheap. Unknown prompts fall back to a richer default policy.
 */
public final class PromptSignalProfiles {

    public static final class PromptSignalProfile {
        private final String promptId;
        private final String extractionHint;
        private final int maxSignals;
        private final int promptPasses;
        private final boolean runSpecificityPass;

        private PromptSignalProfile(String promptId, String extractionHint, int maxSignals, int promptPasses,
                boolean runSpecificityPass) {
            this.promptId = promptId;
            this.extractionHint = extractionHint;
            this.maxSignals = Math.max(1, maxSignals);
            this.promptPasses = Math.max(1, promptPasses);
            this.runSpecificityPass = runSpecificityPass;
        }

        public String promptId() {
            return promptId;
        }

        public String extractionHint() {
            return extractionHint;
        }

        public int maxSignals() {
            return maxSignals;
        }

        public int promptPasses() {
            return promptPasses;
        }

        public boolean runSpecificityPass() {
            return runSpecificityPass;
        }
    }

    private static final PromptSignalProfile DEFAULT_PROFILE = profile(
            "default",
            "Extract stable dating-relevant concepts from this answer. Output final canonical concept tags directly, with precision over coverage.",
            12,
            2,
            true);

    private static final Map<String, PromptSignalProfile> BY_PROMPT_ID;

    static {
        LinkedHashMap<String, PromptSignalProfile> byId = new LinkedHashMap<>();

        // Public prompt profiles.
        add(byId, profile("prompt.talk.hours",
                "Treat the named subject as explicit affinity. Keep specific explicit entities as primary signals and any umbrella concepts weaker. Use canonical media title spelling in snake_case.",
                6, 1, false));
        add(byId, profile("prompt.life.goal",
                "Prioritize durable ambition and direction signals. If the goal centers on doing activities in a destination, include travel plus the specific destination concept. If the answer references World Cup context, include soccer.",
                6, 1, false));
        add(byId, profile("prompt.same.weird",
                "Look for quirky identity and compatibility-style traits that are stable in dating context.",
                5, 1, false));
        add(byId, profile("prompt.gas.station",
                "Treat as light preference context. Emit only clear lifestyle or food preference concepts.",
                4, 1, false));
        add(byId, profile("prompt.hill.die.on",
                "This is conviction framing. Prioritize explicit values and strong stances.",
                5, 1, false));
        add(byId, profile("prompt.controversial.opinion",
                "Capture worldview and value stance. Use meta sparingly and only when clearly evaluative.",
                5, 1, false));
        add(byId, profile("prompt.ideal.sunday",
                "Extract lifestyle cadence and social energy preferences. Keep weights moderate unless explicitly rigid. For low-specificity answers, emit at most one useful lifestyle signal and avoid literal objects.",
                5, 1, false));
        add(byId, profile("prompt.no.compromise",
                "This is non-negotiable framing. Prioritize explicit habits or routines.",
                5, 1, false));
        add(byId, profile("prompt.respect.people",
                "Interpret as valued traits in others; prefer seeking intent when partner preference is implied.",
                5, 1, false));
        add(byId, profile("prompt.small.thing",
                "Treat as subtle value framing. Prefer explicit stable concepts.",
                4, 1, false));
        add(byId, profile("prompt.disappeared.year",
                "Extract durable interests and lifestyle signals, not one-time travel narratives.",
                5, 1, false));
        add(byId, profile("prompt.rabbit.hole",
                "Prioritize explicit niche interests. Keep broad umbrella concepts lower than the explicit niche.",
                6, 1, false));

        // Private prompt profiles.
        add(byId, profile("private.fictional.characters",
                "Extract attraction patterns and relational archetype preferences. Keep only context-free durable concepts as signals; preserve explicit franchise/work titles, but avoid character-name-only tags.",
                7, 1, false));
        add(byId, profile("private.color.presence",
                "Treat this as vibe and social energy preference context; avoid literal color-only tokens.",
                5, 1, false));
        add(byId, profile("private.hobbies",
                "Signal-first prompt: extract concrete hobby/activity concepts. Split into self hobbies and partner-shared hobby preferences. Avoid abstract narrative labels.",
                7, 1, false));
        add(byId, profile("private.popular.dislike",
                "This is exclusion framing. Convert clear dislikes into seeking constraints with negative valence. Preserve explicit concrete categories (for example reality_tv). Also preserve explicitly named people, shows, brands, or artists as separate tokens alongside any category they suggest (for example 'taylor_swift' AND 'mainstream_pop').",
                7, 1, false));
        add(byId, profile("private.most.myself",
                "Extract identity and comfort-state signals with self intent.",
                6, 1, false));
        add(byId, profile("private.political.issues",
                "Extract stable political/value priorities from explicit issue mentions.",
                6, 1, false));
        add(byId, profile("private.not.my.person",
                "Hard exclusion framing. Prefer seeking intent with clearly negative valence. Preserve explicitly named people, shows, brands, or artists as separate tokens alongside any category they suggest (for example 'taylor_swift' AND 'mainstream_pop').",
                7, 1, false));
        add(byId, profile("private.drawn.to",
                "Primary output should be context-free seeking preferences for partner traits and lifestyle. Preserve explicit franchise/work titles as self-side taste context when named (for example red_rising), but avoid character-name-only tags.",
                7, 1, false));
        add(byId, profile("private.music.feels.like",
                "Extract identity and taste signals; keep derived umbrella tags weaker than explicit artists/genres.",
                6, 1, false));
        add(byId, profile("private.media.revisit",
                "Explicit repeated media preference implies stable affinity. Preserve specific titles when provided.",
                7, 1, false));
        add(byId, profile("private.makes.you.laugh",
                "Extract humor style and social tone preferences.",
                5, 1, false));
        add(byId, profile("private.rabbit.hole",
                "Extract specific curiosity domains and keep broad umbrella tags weaker.",
                6, 1, false));
        add(byId, profile("private.places.home",
                "Extract place-style and environment preferences as stable lifestyle context.",
                5, 1, false));
        add(byId, profile("private.great.night",
                "Extract durable social style and activity preferences. Avoid seasonal, weather, or time-of-day specifics (not 'warm_summer_night'). For balanced or flexible answers ('X but also good with Y'), emit both X and Y as separate signals. Prefer stable concepts like 'home_socializing', 'social_drinking', 'nightlife'.",
                5, 1, false));
        add(byId, profile("private.visual.aesthetic",
                "Translate aesthetic language into stable vibe/style concepts, not decorative literal terms.",
                5, 1, false));
        add(byId, profile("private.stuck.with",
                "Long-term consistency framing. Preserve concrete activities as self signals (for example gym -> gym/fitness) and add durable commitment/discipline only when clearly supported.",
                5, 1, false));
        add(byId, profile("private.communities.scene",
                "Signal-first prompt: extract concrete communities/scenes (for example gym, gaming, dance) as durable signals. Avoid generic social-belonging abstractions.",
                6, 1, false));

        // Synthetic prompt ids.
        add(byId, profile("matchmaking.followup",
                "Matchmaking follow-up context. Prefer the explicitly missing concept and align intent to compatibility.",
                4, 1, false));
        add(byId, profile("private.matchmaking.followup",
                "Matchmaking follow-up context. Prefer the explicitly missing concept and align intent to compatibility.",
                4, 1, false));

        BY_PROMPT_ID = Collections.unmodifiableMap(byId);
    }

    private PromptSignalProfiles() {
    }

    public static PromptSignalProfile forPromptId(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return DEFAULT_PROFILE;
        }
        PromptSignalProfile profile = BY_PROMPT_ID.get(promptId.trim());
        return profile == null ? DEFAULT_PROFILE : profile;
    }

    public static PromptSignalProfile defaultProfile() {
        return DEFAULT_PROFILE;
    }

    private static PromptSignalProfile profile(String promptId, String extractionHint, int maxSignals, int promptPasses,
            boolean runSpecificityPass) {
        return new PromptSignalProfile(promptId, extractionHint == null ? "" : extractionHint.trim(), maxSignals,
                promptPasses, runSpecificityPass);
    }

    private static void add(Map<String, PromptSignalProfile> byId, PromptSignalProfile profile) {
        if (byId == null || profile == null) {
            return;
        }
        byId.put(profile.promptId(), profile);
    }
}
