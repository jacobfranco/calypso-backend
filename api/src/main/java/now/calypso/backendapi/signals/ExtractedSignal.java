package now.calypso.backendapi.signals;

import now.calypso.backend.data.SignalIntent;

/**
 * Lightweight container for a normalized token plus the intent metadata we
 * persist inside {@link SignalRecord}.
 */
public final class ExtractedSignal {

    private final String token;
    private final SignalIntent intent;
    private final Double valence;

    private ExtractedSignal(String token, SignalIntent intent, Double valence) {
        this.token = token;
        this.intent = intent;
        this.valence = valence;
    }

    public static ExtractedSignal manual(String rawToken) {
        return from(rawToken, SignalIntent.SELF, 1.0);
    }

    public static ExtractedSignal from(String rawToken, SignalIntent intentMaybe, Double valenceMaybe) {
        String normalized = SignalNormalizer.normalizeOne(rawToken);
        if (normalized == null)
            return null;
        Double inferredValence = null;
        boolean strippedPrefix;
        do {
            strippedPrefix = false;
            if (normalized.startsWith("anti_") && normalized.length() > "anti_".length()) {
                inferredValence = -1.0;
                normalized = SignalNormalizer.normalizeOne(normalized.substring("anti_".length()));
                strippedPrefix = true;
            } else if (normalized.startsWith("not_") && normalized.length() > "not_".length()) {
                inferredValence = -1.0;
                normalized = SignalNormalizer.normalizeOne(normalized.substring("not_".length()));
                strippedPrefix = true;
            } else if (normalized.startsWith("no_") && normalized.length() > "no_".length()) {
                inferredValence = -1.0;
                normalized = SignalNormalizer.normalizeOne(normalized.substring("no_".length()));
                strippedPrefix = true;
            } else if (normalized.startsWith("avoid_") && normalized.length() > "avoid_".length()) {
                inferredValence = -1.0;
                normalized = SignalNormalizer.normalizeOne(normalized.substring("avoid_".length()));
                strippedPrefix = true;
            } else if (normalized.startsWith("exclude_") && normalized.length() > "exclude_".length()) {
                inferredValence = -1.0;
                normalized = SignalNormalizer.normalizeOne(normalized.substring("exclude_".length()));
                strippedPrefix = true;
            }
            if (normalized == null)
                return null;
        } while (strippedPrefix);
        if (normalized == null) {
            return null;
        }
        SignalIntent intent = intentMaybe == null ? SignalIntent.SELF : intentMaybe;
        Double valence = clampSigned(valenceMaybe);
        if (valence == null) {
            valence = inferredValence == null ? 1.0 : inferredValence;
        }
        return new ExtractedSignal(normalized, intent, valence);
    }

    private static Double clampSigned(Double value) {
        if (value == null)
            return null;
        double c = value.doubleValue();
        if (Double.isNaN(c))
            return null;
        if (c < -1.0)
            c = -1.0;
        if (c > 1.0)
            c = 1.0;
        return c;
    }

    public String token() {
        return token;
    }

    public SignalIntent intent() {
        return intent;
    }

    public Double valence() {
        return valence;
    }
}
