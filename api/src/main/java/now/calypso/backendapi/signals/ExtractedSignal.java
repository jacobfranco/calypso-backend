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
    private final Double importance;

    private ExtractedSignal(String token, SignalIntent intent, Double confidence, Double importance) {
        this.token = token;
        this.intent = intent;
        this.confidence = confidence;
        this.importance = importance;
    }

    public static ExtractedSignal manual(String rawToken) {
        return from(rawToken, SignalIntent.SELF, 1.0);
    }

    public static ExtractedSignal from(String rawToken, SignalIntent intentMaybe, Double confidenceMaybe) {
        return from(rawToken, intentMaybe, confidenceMaybe, null);
    }

    public static ExtractedSignal from(String rawToken, SignalIntent intentMaybe, Double confidenceMaybe,
            Double importanceMaybe) {
        String normalized = SignalNormalizer.normalizeOne(rawToken);
        if (normalized == null)
            return null;
        SignalIntent intent = intentMaybe == null ? SignalIntent.SELF : intentMaybe;
        Double confidence = clamp01(confidenceMaybe);
        if (confidence == null)
            confidence = 0.65;
        Double importance = clamp01(importanceMaybe);
        return new ExtractedSignal(normalized, intent, confidence, importance);
    }

    private static Double clamp01(Double value) {
        if (value == null)
            return null;
        double c = value.doubleValue();
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

    public Double importance() {
        return importance;
    }
}
