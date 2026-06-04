package now.calypso.backendapi.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PrivatePromptSufficiencyPlanner {
    private static final String FORMATIVE_IMPRINT_PROMPT_ID = "private.formative.imprints";

    private PrivatePromptSufficiencyPlanner() {
    }

    public static SufficiencyPlan plan(PrivatePromptTurnResponder.TurnInput input) {
        if (input == null) {
            return SufficiencyPlan.empty("unknown");
        }
        String promptId = safe(input.promptId).toLowerCase(Locale.ROOT);
        if (FORMATIVE_IMPRINT_PROMPT_ID.equals(promptId)
                || safe(input.promptText).toLowerCase(Locale.ROOT).contains("nostalgia")) {
            return formativePlan(input);
        }
        PromptProfile profile = PromptProfile.forPrompt(promptId, input.promptText);
        return profilePlan(input, profile);
    }

    private static SufficiencyPlan formativePlan(PrivatePromptTurnResponder.TurnInput input) {
        String text = combinedUserText(input);
        boolean followupAlreadyAsked = followupAlreadyAsked(input);
        boolean latestNeedsReframe = latestNeedsReframe(input);
        boolean hasReference = hasConcreteReference(text);
        boolean submitted = isSubmissionIntent(text);
        boolean hasImprint = hasReference && containsAny(normalized(text),
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
                "sparked",
                "i still",
                "now i",
                "as an adult",
                "my taste",
                "my aesthetics",
                "my aesthetic",
                "aesthetics",
                "curiosity",
                "curious about",
                "drawn to",
                "it taught me",
                "they taught me");
        boolean complete = submitted || (hasReference && hasImprint) || (followupAlreadyAsked && !latestNeedsReframe);
        ArrayList<String> missing = new ArrayList<>();
        String guidance;
        if (!complete && hasReference) {
            missing.add("lasting_imprint");
            guidance = "Ask what part of the named references stayed with the user, or what they left the user drawn toward later. Do not ask for more references or the first thing that comes to mind.";
        } else if (!complete) {
            missing.add("concrete_reference_or_memory");
            guidance = "Ask for one concrete childhood reference or memory without repeating prior wording.";
        } else {
            guidance = "Acknowledge briefly and move forward.";
        }
        return new SufficiencyPlan(
                "formative_imprints",
                complete,
                !complete && (!followupAlreadyAsked || latestNeedsReframe),
                missing,
                guidance,
                "reference_then_imprint",
                Map.of(
                        "referencesPresent", hasReference,
                        "imprintPresent", hasImprint,
                        "followupAlreadyAsked", followupAlreadyAsked,
                        "latestMessageNeedsReframe", latestNeedsReframe));
    }

    private static SufficiencyPlan profilePlan(PrivatePromptTurnResponder.TurnInput input, PromptProfile profile) {
        String text = combinedUserText(input);
        String latest = safe(input.userMessage);
        int words = wordCount(text);
        boolean followupAlreadyAsked = followupAlreadyAsked(input);
        boolean submission = isSubmissionIntent(latest) || isSubmissionIntent(text);
        boolean concrete = hasConcreteReference(text);
        boolean personalMeaning = hasPersonalMeaning(text);
        boolean boundary = hasBoundaryOrValence(text);
        boolean sparse = words < 5 || isLowInformation(text);
        ArrayList<String> missing = new ArrayList<>();
        if (sparse) {
            missing.add("substantive_answer");
        }
        if (profile.needsConcreteReference && !concrete) {
            missing.add("concrete_example");
        }
        if (profile.needsPersonalMeaning && !personalMeaning) {
            missing.add("personal_meaning");
        }
        if (profile.needsBoundary && !boundary) {
            missing.add("boundary_or_valence");
        }
        boolean complete = submission || missing.isEmpty() || followupAlreadyAsked;
        boolean needsMore = !complete && !missing.isEmpty();
        String guidance = complete
                ? "Acknowledge briefly and move forward."
                : guidanceFor(profile, missing, concrete);
        return new SufficiencyPlan(
                profile.promptType,
                complete,
                needsMore,
                missing,
                guidance,
                profile.strategy,
                Map.of(
                        "wordCount", words,
                        "concreteReferencePresent", concrete,
                        "personalMeaningPresent", personalMeaning,
                        "boundaryOrValencePresent", boundary,
                        "followupAlreadyAsked", followupAlreadyAsked));
    }

    private static String guidanceFor(PromptProfile profile, List<String> missing, boolean hasConcrete) {
        if (profile == PromptProfile.HOBBIES) {
            if (hasConcrete) {
                return "If the answer is only a list, ask one natural question about the strongest named activity, like how the user feels about it or whether they want to share it with a partner. Do not ask generic category questions.";
            }
            return "Ask for one or two real activities they actually spend time on.";
        }
        if (profile == PromptProfile.NEGATIVE_BOUNDARY) {
            return "Ask what about the named thing turns the user off, or whether it is a true boundary versus something they can tolerate. Keep it grounded in what they named.";
        }
        if (profile == PromptProfile.ATTRACTION_PATTERN) {
            return "Ask for one recurring quality or concrete example that makes the pull clear. Avoid abstract analysis.";
        }
        if (profile == PromptProfile.PLACE_OR_SCENE) {
            return "Ask for the setting, pace, people, or texture that makes the place or scene feel right. Keep the question concrete.";
        }
        if (profile == PromptProfile.MEDIA_OR_AESTHETIC) {
            return "Ask what part of the named media, sound, humor, or aesthetic lands for the user. Do not ask how they feel about the whole broad category.";
        }
        if (missing.contains("personal_meaning")) {
            return "Ask what the named thing does for the user or what it says about what they want around them.";
        }
        if (missing.contains("concrete_example")) {
            return "Ask for one concrete example before drawing conclusions.";
        }
        return "Ask one grounded follow-up that collects relationship-relevant detail without exposing internal criteria.";
    }

    private static String combinedUserText(PrivatePromptTurnResponder.TurnInput input) {
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
                    parts.add(trimmed.substring(Math.min(5, trimmed.length())).trim());
                }
            }
        }
        String latest = safe(input.userMessage);
        if (!latest.isBlank()) {
            parts.add(latest);
        }
        return String.join(" ", parts).trim();
    }

    private static boolean followupAlreadyAsked(PrivatePromptTurnResponder.TurnInput input) {
        if (input == null || input.conversation == null) {
            return false;
        }
        boolean seenUserAnswer = false;
        for (String line : input.conversation) {
            String lowered = line == null ? "" : line.toLowerCase(Locale.ROOT);
            if (lowered.startsWith("user:")) {
                if (wordCount(line) >= 3 || hasConcreteReference(line)) {
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

    private static boolean latestNeedsReframe(PrivatePromptTurnResponder.TurnInput input) {
        String normalized = normalized(input == null ? null : input.userMessage);
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

    private static boolean hasConcreteReference(String text) {
        String normalized = normalized(text);
        if (normalized.isBlank() || isLowInformation(normalized)) {
            return false;
        }
        String cleaned = normalized
                .replaceAll("\\b(i|i'm|im|am|just|really|kind|sort|of|a|the|this|that|it|how|should|do|does|are|you|mean|asking|answer|like|maybe|idk)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (wordCount(cleaned) >= 2) {
            return true;
        }
        return cleaned.length() >= 4 && !containsAny(cleaned, "anything", "nothing", "whatever");
    }

    private static boolean hasPersonalMeaning(String text) {
        String normalized = normalized(text);
        return containsAny(normalized,
                "because",
                "makes me",
                "made me",
                "feels",
                "feel",
                "felt",
                "i like",
                "i love",
                "i hate",
                "drawn to",
                "turns me",
                "matters",
                "important",
                "safe",
                "home",
                "connected",
                "understood",
                "excited",
                "calm",
                "energized",
                "overwhelmed");
    }

    private static boolean hasBoundaryOrValence(String text) {
        String normalized = normalized(text);
        return containsAny(normalized,
                "don't",
                "dont",
                "do not",
                "not my",
                "can't",
                "cant",
                "avoid",
                "hate",
                "dislike",
                "turns me off",
                "no for me",
                "dealbreaker",
                "boundary",
                "love",
                "like",
                "drawn",
                "want",
                "need");
    }

    private static boolean isLowInformation(String text) {
        String normalized = normalized(text);
        if (normalized.isBlank()) {
            return true;
        }
        return wordCount(normalized) <= 3 && containsAny(normalized,
                "idk",
                "i don't know",
                "i dont know",
                "not sure",
                "nothing",
                "anything",
                "whatever",
                "no idea");
    }

    private static boolean isSubmissionIntent(String text) {
        String normalized = normalized(text);
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

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int wordCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int words = 0;
        for (String token : text.trim().split("\\s+")) {
            if (!token.isBlank()) {
                words += 1;
            }
        }
        return words;
    }

    private static String normalized(String raw) {
        return safe(raw).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String safe(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static final class SufficiencyPlan {
        public final String promptType;
        public final boolean complete;
        public final boolean needsMoreDetail;
        public final List<String> missing;
        public final String guidance;
        public final String strategy;
        public final Map<String, Object> dimensions;

        SufficiencyPlan(
                String promptType,
                boolean complete,
                boolean needsMoreDetail,
                List<String> missing,
                String guidance,
                String strategy,
                Map<String, Object> dimensions) {
            this.promptType = promptType == null || promptType.isBlank() ? "unknown" : promptType;
            this.complete = complete;
            this.needsMoreDetail = needsMoreDetail;
            this.missing = missing == null ? List.of() : List.copyOf(missing);
            this.guidance = guidance == null ? "" : guidance;
            this.strategy = strategy == null ? "" : strategy;
            this.dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        }

        static SufficiencyPlan empty(String promptType) {
            return new SufficiencyPlan(promptType, true, false, List.of(), "", "", Map.of());
        }
    }

    private enum PromptProfile {
        HOBBIES("hobbies", true, true, false, "activity_shareability"),
        NEGATIVE_BOUNDARY("negative_boundary", true, true, true, "boundary_precision"),
        ATTRACTION_PATTERN("attraction_pattern", true, true, false, "pattern_specificity"),
        PLACE_OR_SCENE("place_or_scene", true, true, false, "context_discovery"),
        MEDIA_OR_AESTHETIC("media_or_aesthetic", true, true, false, "felt_quality"),
        VALUES("values", false, true, false, "relationship_relevance"),
        GENERAL("general", false, false, false, "substantive_answer");

        final String promptType;
        final boolean needsConcreteReference;
        final boolean needsPersonalMeaning;
        final boolean needsBoundary;
        final String strategy;

        PromptProfile(
                String promptType,
                boolean needsConcreteReference,
                boolean needsPersonalMeaning,
                boolean needsBoundary,
                String strategy) {
            this.promptType = promptType;
            this.needsConcreteReference = needsConcreteReference;
            this.needsPersonalMeaning = needsPersonalMeaning;
            this.needsBoundary = needsBoundary;
            this.strategy = strategy;
        }

        static PromptProfile forPrompt(String promptId, String promptText) {
            String id = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
            if ("private.hobbies".equals(id) || "private.stuck.with".equals(id)) {
                return HOBBIES;
            }
            if ("private.not.my.person".equals(id)
                    || "private.popular.dislike".equals(id)
                    || "private.repair.rhythm".equals(id)) {
                return NEGATIVE_BOUNDARY;
            }
            if ("private.drawn.to".equals(id)
                    || "private.gravitational.pull".equals(id)
                    || "private.fictional.characters".equals(id)) {
                return ATTRACTION_PATTERN;
            }
            if ("private.places.home".equals(id)
                    || "private.home.texture".equals(id)
                    || "private.great.night".equals(id)
                    || "private.communities.scene".equals(id)
                    || "private.most.myself".equals(id)) {
                return PLACE_OR_SCENE;
            }
            if ("private.inner.weather".equals(id)
                    || "private.color.presence".equals(id)
                    || "private.music.feels.like".equals(id)
                    || "private.media.revisit".equals(id)
                    || "private.makes.you.laugh".equals(id)
                    || "private.humor.language".equals(id)
                    || "private.visual.aesthetic".equals(id)
                    || "private.rabbit.hole".equals(id)) {
                return MEDIA_OR_AESTHETIC;
            }
            if ("private.political.issues".equals(id)) {
                return VALUES;
            }
            String text = safe(promptText).toLowerCase(Locale.ROOT);
            if (text.contains("hobbies")) {
                return HOBBIES;
            }
            if (text.contains("don't like") || text.contains("not my person")) {
                return NEGATIVE_BOUNDARY;
            }
            if (text.contains("drawn to") || text.contains("pull")) {
                return ATTRACTION_PATTERN;
            }
            if (text.contains("home") || text.contains("scene") || text.contains("night with friends")) {
                return PLACE_OR_SCENE;
            }
            return GENERAL;
        }
    }
}
