package now.calypso.backendapi.signals;

import java.util.*;
import java.util.regex.Pattern;

public final class SignalNormalizer {
    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{2,48}$");
    private static final Set<String> STOP = Set.of("yes", "ok", "okay", "idk", "lol", "maybe", "sure", "no", "nah",
            "yep", "nope");

    private SignalNormalizer() {
    }

    public static String normalizeOne(String raw) {
        if (raw == null)
            return null;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("[\\s\\-]+", "_"); // spaces/hyphens -> _
        s = s.replaceAll("^_+|_+$", ""); // trim _
        s = s.replaceAll("[^a-z0-9_]", ""); // drop other chars
        if (s.isEmpty() || STOP.contains(s))
            return null;
        if (s.length() > 48)
            s = s.substring(0, 48);
        if (!VALID.matcher(s).matches())
            return null;
        return s;
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
