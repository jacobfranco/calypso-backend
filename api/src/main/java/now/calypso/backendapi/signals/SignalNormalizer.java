package now.calypso.backendapi.signals;

import java.util.*;
import java.util.regex.Pattern;

public final class SignalNormalizer {
    private static final int MAX_TOKEN_CHARS = 96;
    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{2," + MAX_TOKEN_CHARS + "}$");
    private static final Pattern SINGULAR_POSSESSIVE = Pattern.compile("([a-z0-9])['’]s\\b");
    private static final Pattern PLURAL_POSSESSIVE = Pattern.compile("([a-z0-9])s['’]\\b");
    private static final Pattern UNDERSCORE_POSSESSIVE_SEGMENT = Pattern.compile("(?<=[a-z0-9])_s(?=_[a-z0-9]|$)");
    private static final Set<String> STOP = Set.of("yes", "ok", "okay", "idk", "lol", "maybe", "sure", "no", "nah",
            "yep", "nope");
    private static final String[] NEGATIVE_SENTIMENT_PREFIXES = {
            "dislike_of_",
            "dislikes_of_",
            "dislike_",
            "dislikes_",
            "disliked_",
            "disliking_",
            "hate_",
            "hates_",
            "hated_",
            "hating_",
            "dont_like_",
            "doesnt_like_",
            "do_not_like_",
            "does_not_like_",
            "not_into_",
            "cant_stand_",
            "cannot_stand_"
    };

    private SignalNormalizer() {
    }

    public static String normalizeOne(String raw) {
        if (raw == null)
            return null;
        String s = collapseMappingNotation(raw).toLowerCase(Locale.ROOT).trim();
        s = canonicalizePossessives(s);
        s = s.replaceAll("[\\s\\-]+", "_"); // spaces/hyphens -> _
        s = s.replaceAll("^_+|_+$", ""); // trim _
        s = s.replaceAll("[^a-z0-9_]", ""); // drop other chars
        s = canonicalizeIntentSuffix(s);
        s = canonicalizeNegationPrefix(s);
        if (s.isEmpty() || STOP.contains(s))
            return null;
        if (s.length() > MAX_TOKEN_CHARS)
            s = s.substring(0, MAX_TOKEN_CHARS);
        if (!VALID.matcher(s).matches())
            return null;
        return s;
    }

    private static String collapseMappingNotation(String raw) {
        if (raw == null)
            return null;
        String s = raw.trim();
        if (s.isBlank())
            return s;
        String[] separators = { "->", "=>", "→" };
        for (String separator : separators) {
            int idx = s.indexOf(separator);
            if (idx < 0)
                continue;
            String rhs = s.substring(idx + separator.length()).trim();
            if (!rhs.isBlank()) {
                return rhs;
            }
        }
        return s;
    }

    private static String canonicalizePossessives(String token) {
        if (token == null || token.isBlank())
            return token;
        // Keep lexical "s" when collapsing apostrophes (jojo's -> jojos).
        String out = SINGULAR_POSSESSIVE.matcher(token).replaceAll("$1s");
        out = PLURAL_POSSESSIVE.matcher(out).replaceAll("$1s");
        // Handle model outputs that encode possessives as "_s" segments.
        out = UNDERSCORE_POSSESSIVE_SEGMENT.matcher(out).replaceAll("");
        return out;
    }

    private static String canonicalizeNegationPrefix(String token) {
        if (token == null || token.isBlank())
            return token;
        String s = token;
        boolean changed;
        do {
            changed = false;
            if (s.startsWith("anti_not_")) {
                s = "anti_" + s.substring("anti_not_".length());
                changed = true;
            } else if (s.startsWith("anti_")) {
                String negativeRemainder = negativeSentimentRemainder(s.substring("anti_".length()));
                if (negativeRemainder != null) {
                    s = "anti_" + negativeRemainder;
                    changed = true;
                }
            }
            if (s.startsWith("not_")) {
                s = "anti_" + s.substring("not_".length());
                changed = true;
            } else {
                String negativeRemainder = negativeSentimentRemainder(s);
                if (negativeRemainder != null) {
                    s = "anti_" + negativeRemainder;
                    changed = true;
                }
            }
            if (s.startsWith("anti_anti_")) {
                s = "anti_" + s.substring("anti_anti_".length());
                changed = true;
            }
        } while (changed);
        if ("anti_".equals(s))
            return null;
        return s;
    }

    private static String negativeSentimentRemainder(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        for (String prefix : NEGATIVE_SENTIMENT_PREFIXES) {
            if (prefix != null && token.startsWith(prefix) && token.length() > prefix.length()) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }

    private static String canonicalizeIntentSuffix(String token) {
        if (token == null || token.isBlank())
            return token;
        String s = token;
        if (s.endsWith("_self") && s.length() > "_self".length()) {
            s = s.substring(0, s.length() - "_self".length());
        } else if (s.endsWith("self") && s.length() > "self".length()) {
            s = s.substring(0, s.length() - "self".length());
        }
        if (s.endsWith("_seeking") && s.length() > "_seeking".length()) {
            s = s.substring(0, s.length() - "_seeking".length());
        } else if (s.endsWith("seeking") && s.length() > "seeking".length()) {
            s = s.substring(0, s.length() - "seeking".length());
        }
        return s.replaceAll("^_+|_+$", "");
    }

    public static List<String> normalizeTokens(Collection<String> raws) {
        if (raws == null || raws.isEmpty())
            return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String r : raws) {
            String n = normalizeOne(r);
            if (n != null && !n.isBlank())
                out.add(n);
        }
        return new ArrayList<>(out);
    }

}
