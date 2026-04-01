package now.calypso.backendapi.signals;

import java.util.*;
import java.util.regex.Pattern;

public final class SignalNormalizer {
    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{2,48}$");
    private static final Pattern SINGULAR_POSSESSIVE = Pattern.compile("([a-z0-9])['’]s\\b");
    private static final Pattern PLURAL_POSSESSIVE = Pattern.compile("([a-z0-9])s['’]\\b");
    private static final Pattern UNDERSCORE_POSSESSIVE_SEGMENT = Pattern.compile("(?<=[a-z0-9])_s(?=_[a-z0-9]|$)");
    private static final Set<String> STOP = Set.of("yes", "ok", "okay", "idk", "lol", "maybe", "sure", "no", "nah",
            "yep", "nope");

    private SignalNormalizer() {
    }

    public static String normalizeOne(String raw) {
        if (raw == null)
            return null;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = canonicalizePossessives(s);
        s = s.replaceAll("[\\s\\-]+", "_"); // spaces/hyphens -> _
        s = s.replaceAll("^_+|_+$", ""); // trim _
        s = s.replaceAll("[^a-z0-9_]", ""); // drop other chars
        s = canonicalizeIntentSuffix(s);
        s = canonicalizeNegationPrefix(s);
        if (s.isEmpty() || STOP.contains(s))
            return null;
        if (s.length() > 48)
            s = s.substring(0, 48);
        if (!VALID.matcher(s).matches())
            return null;
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
            }
            if (s.startsWith("not_")) {
                s = "anti_" + s.substring("not_".length());
                changed = true;
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
