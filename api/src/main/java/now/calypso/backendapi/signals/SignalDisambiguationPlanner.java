package now.calypso.backendapi.signals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight detector for ambiguous references that should be clarified in a
 * follow-up question before they become durable canonical concepts.
 */
public final class SignalDisambiguationPlanner {

    public static final class FollowupCandidate {
        public final String key;
        public final String term;
        public final String question;
        public final String promptId;

        public FollowupCandidate(String key, String term, String question, String promptId) {
            this.key = key;
            this.term = term;
            this.question = question;
            this.promptId = promptId;
        }
    }

    private static final Pattern WATCHING_THE_PATTERN = Pattern.compile(
            "\\bwatching\\s+(?:the\\s+)?([a-z][a-z0-9'_-]{2,32})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SPORTS_SPECIFIC_TOKENS = Set.of(
            "soccer", "football", "baseball", "basketball", "hockey", "tennis", "f1", "ufc", "wrestling");
    private static final Map<String, String> SPORTS_AMBIGUOUS_TERM_TO_QUESTION = Map.ofEntries(
            Map.entry("panthers",
                    "Quick clarify: when you said \"Panthers\", did you mean Carolina (NFL), Florida (NHL), or another Panthers team?"),
            Map.entry("bruins",
                    "Quick clarify: when you said \"Bruins\", did you mean Boston Bruins (NHL), UCLA Bruins, or another Bruins team?"),
            Map.entry("rangers",
                    "Quick clarify: when you said \"Rangers\", did you mean New York Rangers (NHL), Texas Rangers (MLB), or another Rangers team?"),
            Map.entry("giants",
                    "Quick clarify: when you said \"Giants\", did you mean New York Giants (NFL), San Francisco Giants (MLB), or another Giants team?"));
    private static final Set<String> GENERIC_WATCHING_STOPWORDS = Set.of(
            "tv", "movie", "movies", "show", "shows", "sports", "game", "games", "news");
    private static final Map<String, String> MEDIA_AMBIGUOUS_TERM_TO_QUESTION = Map.ofEntries(
            Map.entry("joker",
                    "Quick clarify: when you said \"Joker\", did you mean the DC character, Persona's Joker, or something else?"),
            Map.entry("avatar",
                    "Quick clarify: when you said \"Avatar\", did you mean Avatar: The Last Airbender, James Cameron's Avatar, or something else?"));

    private SignalDisambiguationPlanner() {
    }

    public static List<FollowupCandidate> detectPromptAmbiguities(
            String promptId,
            String question,
            String answer,
            Collection<String> conversationLines,
            Collection<ExtractedSignal> extractedSignals) {
        String combinedLower = combinedLowerText(question, answer, conversationLines);
        if (combinedLower.isBlank()) {
            return List.of();
        }
        HashSet<String> extractedTokens = normalizedSignalTokens(extractedSignals);
        LinkedHashMap<String, FollowupCandidate> out = new LinkedHashMap<>();

        addSportsAmbiguities(out, promptId, combinedLower, extractedTokens);
        addMediaAmbiguities(out, promptId, combinedLower, extractedTokens);
        addGenericWatchingAmbiguities(out, promptId, combinedLower, extractedTokens);
        addSelfVsPartnerScopeAmbiguities(out, promptId, combinedLower);

        if (out.isEmpty()) {
            return List.of();
        }
        ArrayList<FollowupCandidate> limited = new ArrayList<>();
        for (FollowupCandidate candidate : out.values()) {
            if (candidate == null) {
                continue;
            }
            limited.add(candidate);
            if (limited.size() >= 3) {
                break;
            }
        }
        return limited;
    }

    private static void addSportsAmbiguities(
            LinkedHashMap<String, FollowupCandidate> out,
            String promptId,
            String combinedLower,
            Set<String> extractedTokens) {
        if (out == null || combinedLower == null || combinedLower.isBlank()) {
            return;
        }
        boolean hasSpecificSport = hasAny(extractedTokens, SPORTS_SPECIFIC_TOKENS);
        boolean likelySportsContext = extractedTokens.contains("sports")
                || combinedLower.contains("world cup")
                || combinedLower.contains("watching")
                || (promptId != null && promptId.toLowerCase(Locale.ROOT).contains("sunday"));
        if (hasSpecificSport || !likelySportsContext) {
            return;
        }
        for (Map.Entry<String, String> entry : SPORTS_AMBIGUOUS_TERM_TO_QUESTION.entrySet()) {
            String term = entry.getKey();
            String question = entry.getValue();
            if (term == null || term.isBlank() || question == null || question.isBlank()) {
                continue;
            }
            if (!containsWholeWord(combinedLower, term)) {
                continue;
            }
            String normalizedTerm = SignalNormalizer.normalizeOne(term);
            if (normalizedTerm == null || normalizedTerm.isBlank()) {
                continue;
            }
            String key = "sports:" + normalizedTerm;
            out.putIfAbsent(key, new FollowupCandidate(key, normalizedTerm, question, promptId));
        }
    }

    private static void addMediaAmbiguities(
            LinkedHashMap<String, FollowupCandidate> out,
            String promptId,
            String combinedLower,
            Set<String> extractedTokens) {
        if (out == null || combinedLower == null || combinedLower.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> entry : MEDIA_AMBIGUOUS_TERM_TO_QUESTION.entrySet()) {
            String term = entry.getKey();
            if (term == null || term.isBlank()) {
                continue;
            }
            if (!containsWholeWord(combinedLower, term)) {
                continue;
            }
            // If extraction already captured a specific canonical title, skip this follow-up.
            if (hasAnyPrefix(extractedTokens, term + "_") || extractedTokens.contains(term)) {
                continue;
            }
            String normalizedTerm = SignalNormalizer.normalizeOne(term);
            if (normalizedTerm == null || normalizedTerm.isBlank()) {
                continue;
            }
            String key = "media:" + normalizedTerm;
            out.putIfAbsent(key, new FollowupCandidate(key, normalizedTerm, entry.getValue(), promptId));
        }
    }

    private static void addGenericWatchingAmbiguities(
            LinkedHashMap<String, FollowupCandidate> out,
            String promptId,
            String combinedLower,
            Set<String> extractedTokens) {
        if (out == null || combinedLower == null || combinedLower.isBlank()) {
            return;
        }
        Matcher matcher = WATCHING_THE_PATTERN.matcher(combinedLower);
        while (matcher.find()) {
            String rawTerm = matcher.group(1);
            String normalized = SignalNormalizer.normalizeOne(rawTerm);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            if (GENERIC_WATCHING_STOPWORDS.contains(normalized)) {
                continue;
            }
            if (out.containsKey("sports:" + normalized) || out.containsKey("media:" + normalized)) {
                continue;
            }
            if (extractedTokens.contains(normalized) || hasAnyPrefix(extractedTokens, normalized + "_")) {
                continue;
            }
            String key = "entity:" + normalized;
            if (out.containsKey(key)) {
                continue;
            }
            String question = "Quick clarify: when you said \"" + displayToken(normalized)
                    + "\", what exactly did you mean?";
            out.put(key, new FollowupCandidate(key, normalized, question, promptId));
        }
    }

    private static void addSelfVsPartnerScopeAmbiguities(
            LinkedHashMap<String, FollowupCandidate> out,
            String promptId,
            String combinedLower) {
        if (out == null || combinedLower == null || combinedLower.isBlank()) {
            return;
        }
        String normalizedPromptId = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        boolean promptSupportsScopeClarification = "private.fascinating.people".equals(normalizedPromptId)
                || "private.fictional.characters".equals(normalizedPromptId)
                || "private.drawn.to".equals(normalizedPromptId);
        if (!promptSupportsScopeClarification) {
            return;
        }
        if (!containsAny(
                combinedLower,
                "because",
                "quality",
                "qualities",
                "trait",
                "traits",
                "strong",
                "capable",
                "independent",
                "independence",
                "driven",
                "disciplined",
                "focused",
                "focus",
                "intelligent",
                "ambitious",
                "loyal",
                "confident")) {
            return;
        }
        if (containsAny(
                combinedLower,
                "in a partner",
                "want in a partner",
                "looking for",
                "drawn to",
                "i see this in myself",
                "i see these in myself",
                "in myself",
                "both",
                "neither")) {
            return;
        }
        String key = "scope:self_vs_partner";
        String question = "Quick clarify: are those traits mostly about you, what you want in a partner, both, or neither?";
        out.putIfAbsent(key, new FollowupCandidate(key, "self_vs_partner_scope", question, promptId));
    }

    private static String combinedLowerText(String question, String answer, Collection<String> conversationLines) {
        StringBuilder buf = new StringBuilder();
        if (question != null) {
            buf.append(question).append(' ');
        }
        if (answer != null) {
            buf.append(answer).append(' ');
        }
        if (conversationLines != null) {
            for (String line : conversationLines) {
                if (line != null) {
                    buf.append(line).append(' ');
                }
            }
        }
        return buf.toString().toLowerCase(Locale.ROOT);
    }

    private static HashSet<String> normalizedSignalTokens(Collection<ExtractedSignal> extractedSignals) {
        HashSet<String> out = new HashSet<>();
        if (extractedSignals == null || extractedSignals.isEmpty()) {
            return out;
        }
        for (ExtractedSignal signal : extractedSignals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String normalized = SignalNormalizer.normalizeOne(signal.token());
            if (normalized != null && !normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static boolean hasAny(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return false;
        }
        for (String candidate : right) {
            if (left.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyPrefix(Set<String> values, String prefix) {
        if (values == null || values.isEmpty() || prefix == null || prefix.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWholeWord(String text, String rawWord) {
        if (text == null || text.isBlank() || rawWord == null || rawWord.isBlank()) {
            return false;
        }
        String normalized = SignalNormalizer.normalizeOne(rawWord);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        String pattern = "\\b" + Pattern.quote(normalized.replace('_', ' ')) + "\\b";
        return Pattern.compile(pattern).matcher(text).find()
                || Pattern.compile("\\b" + Pattern.quote(normalized) + "\\b").matcher(text).find();
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle == null || needle.isBlank()) {
                continue;
            }
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String displayToken(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String[] parts = normalized.replace('_', ' ').trim().split("\\s+");
        if (parts.length == 0) {
            return normalized;
        }
        StringBuilder buf = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (buf.length() > 0) {
                buf.append(' ');
            }
            buf.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                buf.append(part.substring(1));
            }
        }
        return buf.toString();
    }
}
