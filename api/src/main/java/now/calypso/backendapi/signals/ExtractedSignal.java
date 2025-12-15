package now.calypso.backendapi.signals;

import now.calypso.backend.data.SignalIntent;

/**
 * Lightweight container for a normalized token plus the intent metadata we
 * persist inside {@link SignalRecord}.
 */
public final class ExtractedSignal {

    private final String token;
    private final SignalIntent intent;
    private final Double confidence;

    private ExtractedSignal(String token, SignalIntent intent, Double confidence) {
        this.token = token;
        this.intent = intent;
        this.confidence = confidence;
    }

    public static ExtractedSignal manual(String rawToken) {
        return from(rawToken, SignalIntent.SELF, null);
    }

    public static ExtractedSignal from(String rawToken, SignalIntent intentMaybe, Double confidenceMaybe) {
        String normalized = SignalNormalizer.normalizeOne(rawToken);
        if (normalized == null)
            return null;
        SignalIntent intent = intentMaybe == null ? SignalIntent.SELF : intentMaybe;
        Double confidence = clamp(confidenceMaybe);
        return new ExtractedSignal(normalized, intent, confidence);
    }

    private static Double clamp(Double confidence) {
        if (confidence == null)
            return null;
        double c = confidence.doubleValue();
        if (Double.isNaN(c))
            return null;
        if (c < 0.0)
            c = 0.0;
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

    public Double confidence() {
        return confidence;
    }
}
