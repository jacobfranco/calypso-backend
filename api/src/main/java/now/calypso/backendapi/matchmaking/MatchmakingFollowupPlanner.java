package now.calypso.backendapi.matchmaking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import now.calypso.backendapi.signals.SignalNormalizer;

public final class MatchmakingFollowupPlanner {
    private static final double MIN_PAIR_SCORE = 55.0;
    private static final double MIN_UNCERTAINTY = 0.25;
    private static final double MIN_UTILITY = 0.45;

    private static final Set<String> LOW_SIGNAL_SOCIAL_FILLER = Set.of(
            "brunch",
            "coffee",
            "drinks",
            "bars",
            "restaurants",
            "restaurant",
            "hanging_out",
            "hangouts",
            "small_talk");

    private static final Set<String> BROAD_CATEGORY = Set.of(
            "music",
            "movies",
            "movie",
            "films",
            "film",
            "food",
            "travel",
            "art",
            "sports",
            "books",
            "reading");

    private static final Set<String> CONTEXT_DISCOVERY = Set.of(
            "fitness",
            "fashion",
            "style",
            "live_music",
            "nightlife");

    private static final Set<String> CONCRETE_ACTIVITY = Set.of(
            "hiking",
            "climbing",
            "running",
            "cycling",
            "biking",
            "cooking",
            "dancing",
            "yoga",
            "gym",
            "board_games",
            "chess",
            "video_games",
            "gaming",
            "clubbing");

    private static final Set<String> CONCRETE_MEDIA = Set.of(
            "anime",
            "horror_movies",
            "jazz",
            "techno",
            "kdramas",
            "reality_tv",
            "romance_novels",
            "documentaries");

    private static final Set<String> VALUE_OR_TRAIT = Set.of(
            "ambition",
            "faith",
            "family",
            "politics",
            "curiosity",
            "independence",
            "kindness",
            "stability",
            "adventure",
            "emotional_directness");

    private static final Set<String> SENSITIVE_OR_UNSAFE = Set.of(
            "trauma",
            "depression",
            "anxiety",
            "therapy",
            "sex",
            "kink",
            "race",
            "ethnicity",
            "disability",
            "medical_conditions");

    private static final String[] POSITIVE_SENTIMENT_PREFIXES = {
            "loves_",
            "love_",
            "likes_",
            "like_",
            "enjoys_",
            "enjoy_",
            "into_",
            "interested_in_",
            "drawn_to_"
    };

    private MatchmakingFollowupPlanner() {
    }

    public enum FollowupAction {
        ASK,
        SKIP
    }

    public enum QuestionStrategy {
        DIRECT_VALENCE,
        BROAD_CATEGORY_NARROWING,
        RELATIONSHIP_RELEVANCE,
        NEGATIVE_BOUNDARY,
        CONTEXT_DISCOVERY
    }

    public enum TokenClass {
        CONCRETE_ACTIVITY,
        CONCRETE_MEDIA,
        CONCRETE_PLACE,
        VALUE_OR_TRAIT,
        BROAD_CATEGORY,
        LOW_SIGNAL_SOCIAL_FILLER,
        SENSITIVE_OR_UNSAFE,
        UNKNOWN
    }

    public static final class MissingSignal {
        public final String token;
        public final double valence;
        public final double absWeight;
        public final int rank;

        public MissingSignal(String token, double valence, double absWeight, int rank) {
            this.token = token;
            this.valence = clampSigned(valence);
            this.absWeight = Math.max(0.0, absWeight);
            this.rank = Math.max(1, rank);
        }
    }

    public static final class PairInsight {
        public final String recommendedUse;
        public final double confidence;
        public final List<String> missingInfo;
        public final List<String> conversationSeeds;
        public final List<String> risks;

        public PairInsight(
                String recommendedUse,
                double confidence,
                Collection<String> missingInfo,
                Collection<String> conversationSeeds,
                Collection<String> risks) {
            this.recommendedUse = recommendedUse == null ? "" : recommendedUse.trim();
            this.confidence = clamp01(confidence);
            this.missingInfo = compactStrings(missingInfo, 5);
            this.conversationSeeds = compactStrings(conversationSeeds, 4);
            this.risks = compactStrings(risks, 4);
        }

        boolean references(String token) {
            String normalized = normalizeForQuestion(token, 1.0);
            if (normalized == null || normalized.isBlank()) {
                return false;
            }
            String phrase = normalized.replace('_', ' ');
            return containsTokenReference(missingInfo, normalized, phrase)
                    || containsTokenReference(conversationSeeds, normalized, phrase)
                    || containsTokenReference(risks, normalized, phrase);
        }
    }

    public static final class Input {
        public final long viewerId;
        public final long targetId;
        public final MissingSignal missingSignal;
        public final double pairScore;
        public final double uncertainty;
        public final PairInsight pairInsight;

        public Input(
                long viewerId,
                long targetId,
                MissingSignal missingSignal,
                double pairScore,
                double uncertainty,
                PairInsight pairInsight) {
            this.viewerId = viewerId;
            this.targetId = targetId;
            this.missingSignal = missingSignal;
            this.pairScore = pairScore;
            this.uncertainty = clamp01(uncertainty);
            this.pairInsight = pairInsight;
        }
    }

    public static final class FollowupPlan {
        public final FollowupAction action;
        public final QuestionStrategy strategy;
        public final TokenClass tokenClass;
        public final String token;
        public final double missingValence;
        public final double absWeight;
        public final String question;
        public final double utility;
        public final String skipReason;

        private FollowupPlan(
                FollowupAction action,
                QuestionStrategy strategy,
                TokenClass tokenClass,
                String token,
                double missingValence,
                double absWeight,
                String question,
                double utility,
                String skipReason) {
            this.action = action;
            this.strategy = strategy;
            this.tokenClass = tokenClass;
            this.token = token;
            this.missingValence = clampSigned(missingValence);
            this.absWeight = Math.max(0.0, absWeight);
            this.question = question;
            this.utility = clamp01(utility);
            this.skipReason = skipReason;
        }

        public static FollowupPlan skip(String token, double missingValence, double absWeight, TokenClass tokenClass,
                String reason) {
            return new FollowupPlan(
                    FollowupAction.SKIP,
                    null,
                    tokenClass == null ? TokenClass.UNKNOWN : tokenClass,
                    token,
                    missingValence,
                    absWeight,
                    null,
                    0.0,
                    reason == null || reason.isBlank() ? "not_useful" : reason);
        }

        public static FollowupPlan ask(
                String token,
                double missingValence,
                double absWeight,
                TokenClass tokenClass,
                QuestionStrategy strategy,
                String question,
                double utility) {
            return new FollowupPlan(
                    FollowupAction.ASK,
                    strategy,
                    tokenClass,
                    token,
                    missingValence,
                    absWeight,
                    question,
                    utility,
                    null);
        }
    }

    public static FollowupPlan plan(Input input) {
        if (input == null || input.missingSignal == null) {
            return FollowupPlan.skip(null, 0.0, 0.0, TokenClass.UNKNOWN, "missing_signal_required");
        }
        String token = normalizeForQuestion(input.missingSignal.token, input.missingSignal.valence);
        if (token == null || token.isBlank()) {
            return FollowupPlan.skip(null, input.missingSignal.valence, input.missingSignal.absWeight, TokenClass.UNKNOWN,
                    "missing_token_required");
        }
        TokenClass tokenClass = classify(token);
        double valence = clampSigned(input.missingSignal.valence);
        double absWeight = Math.max(0.0, input.missingSignal.absWeight);

        if (tokenClass == TokenClass.SENSITIVE_OR_UNSAFE) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "sensitive_or_unsafe");
        }
        if (input.pairScore < MIN_PAIR_SCORE) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "pair_score_too_low");
        }
        if (input.uncertainty < MIN_UNCERTAINTY) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "uncertainty_too_low");
        }
        if (tokenClass == TokenClass.LOW_SIGNAL_SOCIAL_FILLER) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "low_signal_social_filler");
        }

        QuestionStrategy strategy = chooseStrategy(token, tokenClass, valence);
        String question = questionFor(token, tokenClass, strategy, valence);
        if (question == null || question.isBlank()) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "no_useful_question");
        }

        double utility = utility(input, token, tokenClass, absWeight);
        if (utility < MIN_UTILITY) {
            return FollowupPlan.skip(token, valence, absWeight, tokenClass, "utility_too_low");
        }
        return FollowupPlan.ask(token, valence, absWeight, tokenClass, strategy, question, utility);
    }

    public static String fallbackQuestion(String token, double valence) {
        String normalized = normalizeForQuestion(token, valence);
        if (normalized == null || normalized.isBlank()) {
            return "Quick check: say a little more about what matters here.";
        }
        TokenClass tokenClass = classify(normalized);
        QuestionStrategy strategy = chooseStrategy(normalized, tokenClass, clampSigned(valence));
        String question = questionFor(normalized, tokenClass, strategy, clampSigned(valence));
        if (question == null || question.isBlank()) {
            return "How do you feel about " + displayPhrase(normalized) + "?";
        }
        return question;
    }

    private static double utility(Input input, String token, TokenClass tokenClass, double absWeight) {
        double utility = switch (tokenClass) {
            case CONCRETE_ACTIVITY, CONCRETE_MEDIA, CONCRETE_PLACE -> 0.36;
            case VALUE_OR_TRAIT -> 0.34;
            case BROAD_CATEGORY -> 0.28;
            case UNKNOWN -> token.contains("_") ? 0.32 : 0.26;
            case LOW_SIGNAL_SOCIAL_FILLER -> -0.30;
            case SENSITIVE_OR_UNSAFE -> -1.00;
        };
        utility += clamp01(input.pairScore / 100.0) * 0.22;
        utility += clamp01(input.uncertainty) * 0.22;
        utility += Math.min(1.0, Math.max(0.0, absWeight)) * 0.16;
        if (input.pairInsight != null && input.pairInsight.references(token)) {
            utility += 0.12 * Math.max(0.35, input.pairInsight.confidence);
        }
        return clamp01(utility);
    }

    private static QuestionStrategy chooseStrategy(String token, TokenClass tokenClass, double valence) {
        if (valence < -0.05) {
            return QuestionStrategy.NEGATIVE_BOUNDARY;
        }
        if (tokenClass == TokenClass.BROAD_CATEGORY) {
            return QuestionStrategy.BROAD_CATEGORY_NARROWING;
        }
        if (CONTEXT_DISCOVERY.contains(token)) {
            return QuestionStrategy.CONTEXT_DISCOVERY;
        }
        if (tokenClass == TokenClass.VALUE_OR_TRAIT) {
            return QuestionStrategy.RELATIONSHIP_RELEVANCE;
        }
        return QuestionStrategy.DIRECT_VALENCE;
    }

    private static String questionFor(String token, TokenClass tokenClass, QuestionStrategy strategy, double valence) {
        if (token == null || token.isBlank() || strategy == null) {
            return null;
        }
        if (strategy == QuestionStrategy.NEGATIVE_BOUNDARY) {
            return negativeQuestion(token);
        }
        if (strategy == QuestionStrategy.BROAD_CATEGORY_NARROWING) {
            return broadQuestion(token);
        }
        if (strategy == QuestionStrategy.CONTEXT_DISCOVERY) {
            return contextQuestion(token);
        }
        if (strategy == QuestionStrategy.RELATIONSHIP_RELEVANCE) {
            return relationshipQuestion(token);
        }
        return "How do you feel about " + displayPhrase(token) + "?";
    }

    private static String negativeQuestion(String token) {
        String phrase = displayPhrase(token);
        if ("smoking".equals(token)) {
            return "Is smoking a real no for you, or just not usually your thing?";
        }
        if ("party_scene".equals(token) || "nightlife".equals(token)) {
            return "What about party-heavy scenes turns you off, if anything?";
        }
        return "What about " + phrase + " turns you off, if anything?";
    }

    private static String broadQuestion(String token) {
        return switch (token) {
            case "music" -> "What kind of music do you actually connect with?";
            case "movies", "movie", "films", "film" -> "What kinds of movies tend to stick with you?";
            case "food" -> "What kinds of food do you actually get excited about?";
            case "travel" -> "What kind of travel actually appeals to you?";
            case "art" -> "What kind of art are you drawn to?";
            case "sports" -> "Which sports, if any, actually matter to you?";
            case "books", "reading" -> "What kinds of books or writing tend to stay with you?";
            default -> "What parts of " + displayPhrase(token) + " actually matter to you?";
        };
    }

    private static String contextQuestion(String token) {
        return switch (token) {
            case "fitness" -> "What kind of fitness actually feels good to you?";
            case "fashion", "style" -> "What kind of style are you drawn to?";
            case "live_music" -> "What makes live music good for you?";
            case "nightlife" -> "What kind of nightlife, if any, actually works for you?";
            default -> "What makes " + displayPhrase(token) + " good for you?";
        };
    }

    private static String relationshipQuestion(String token) {
        return switch (token) {
            case "family" -> "What role does family play in what you want with someone?";
            case "faith" -> "What role does faith play in what you want with someone?";
            case "politics" -> "How much do politics matter in what you want with someone?";
            case "ambition" -> "What does ambition mean to you in a person?";
            case "independence" -> "What does independence look like to you in a relationship?";
            case "stability" -> "What kind of stability do you look for in someone?";
            default -> "What does " + displayPhrase(token) + " mean to you in a person?";
        };
    }

    private static TokenClass classify(String token) {
        if (token == null || token.isBlank()) {
            return TokenClass.UNKNOWN;
        }
        if (SENSITIVE_OR_UNSAFE.contains(token)) {
            return TokenClass.SENSITIVE_OR_UNSAFE;
        }
        if (LOW_SIGNAL_SOCIAL_FILLER.contains(token)) {
            return TokenClass.LOW_SIGNAL_SOCIAL_FILLER;
        }
        if (BROAD_CATEGORY.contains(token)) {
            return TokenClass.BROAD_CATEGORY;
        }
        if (VALUE_OR_TRAIT.contains(token)) {
            return TokenClass.VALUE_OR_TRAIT;
        }
        if (CONCRETE_ACTIVITY.contains(token)) {
            return TokenClass.CONCRETE_ACTIVITY;
        }
        if (CONCRETE_MEDIA.contains(token)
                || token.endsWith("_movies")
                || token.endsWith("_music")
                || token.endsWith("_novels")
                || token.endsWith("_games")) {
            return TokenClass.CONCRETE_MEDIA;
        }
        if (token.endsWith("_place") || token.endsWith("_places") || token.endsWith("_city")) {
            return TokenClass.CONCRETE_PLACE;
        }
        return TokenClass.UNKNOWN;
    }

    private static String normalizeForQuestion(String raw, double valence) {
        String normalized = SignalNormalizer.normalizeOne(raw);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("anti_") && normalized.length() > "anti_".length()) {
            normalized = normalized.substring("anti_".length());
        }
        normalized = stripPositivePrefix(normalized);
        normalized = normalized.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? null : normalized;
    }

    private static String stripPositivePrefix(String token) {
        if (token == null || token.isBlank()) {
            return token;
        }
        String out = token;
        boolean changed;
        do {
            changed = false;
            for (String prefix : POSITIVE_SENTIMENT_PREFIXES) {
                if (out.startsWith(prefix) && out.length() > prefix.length()) {
                    out = out.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return out;
    }

    private static String displayPhrase(String token) {
        String normalized = normalizeForQuestion(token, 1.0);
        if (normalized == null || normalized.isBlank()) {
            return "that";
        }
        return normalized.replace('_', ' ').trim();
    }

    private static boolean containsTokenReference(Collection<String> values, String token, String phrase) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String lowerToken = token == null ? "" : token.toLowerCase(Locale.ROOT);
        String lowerPhrase = phrase == null ? "" : phrase.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (!lowerToken.isBlank() && lower.contains(lowerToken)) {
                return true;
            }
            if (!lowerPhrase.isBlank() && lower.contains(lowerPhrase)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> compactStrings(Collection<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        int bounded = Math.max(1, limit);
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.length() > 180) {
                trimmed = trimmed.substring(0, 180).trim();
            }
            kept.add(trimmed);
            if (kept.size() >= bounded) {
                break;
            }
        }
        return new ArrayList<>(kept);
    }

    private static double clampSigned(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < -1.0) {
            return -1.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
