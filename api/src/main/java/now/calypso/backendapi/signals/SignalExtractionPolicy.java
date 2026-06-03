package now.calypso.backendapi.signals;

import java.util.Locale;
import java.util.Set;

/**
 * Routing and cleanup policy for extracted model tags.
 *
 * This intentionally lives outside {@link SignalConceptRegistry}: these
 * surfaces are not matchable signal concepts, they only decide whether an
 * extracted tag is eligible for account-signal persistence.
 */
public final class SignalExtractionPolicy {
    private static final Set<String> FORMATIVE_SIGNAL_SUPPRESSED = Set.of(
            "secret_agent",
            "spy",
            "detective",
            "books",
            "book",
            "reading",
            "game",
            "games",
            "video_games",
            "board_games",
            "media",
            "nostalgia_formative_games",
            "nostalgic_formative_games",
            "formative_games",
            "formative_media",
            "nostalgic_media",
            "childhood_media",
            "childhood_games");

    private static final Set<String> FORMATIVE_EVIDENCE_SUPPRESSED = Set.of(
            "nostalgia",
            "nostalgic",
            "nostalgia_formative_games",
            "nostalgic_formative_games",
            "formative_games",
            "formative_media",
            "childhood",
            "gaming",
            "game",
            "video_games",
            "board_games",
            "games",
            "media",
            "book",
            "books",
            "reading",
            "website",
            "websites",
            "travel",
            "adventure");

    private static final Set<String> FORMATIVE_DERIVED_PARENT_ALLOWED = Set.of(
            "video_games",
            "anime",
            "music",
            "travel");

    private static final Set<String> FORMATIVE_LABEL_WORD_IGNORED = Set.of(
            "nostalgia",
            "nostalgic",
            "formative",
            "imprint",
            "imprints",
            "emotional",
            "media",
            "game",
            "games",
            "book",
            "books",
            "toy",
            "toys",
            "website",
            "websites",
            "place",
            "places",
            "show",
            "shows",
            "movie",
            "movies",
            "thing",
            "things",
            "childhood",
            "growing",
            "up",
            "self",
            "expression",
            "interest",
            "interests",
            "affinity",
            "and",
            "or",
            "of",
            "from",
            "via",
            "with",
            "toward",
            "towards",
            "worldview",
            "worldviews",
            "shaping",
            "shaped",
            "influence",
            "influences",
            "influenced",
            "emerging",
            "resonance",
            "resonant",
            "pattern",
            "patterns",
            "mode");

    private SignalExtractionPolicy() {
    }

    public static boolean shouldSuppressFormativeSignalToken(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        return normalized != null && FORMATIVE_SIGNAL_SUPPRESSED.contains(normalized);
    }

    public static boolean shouldSuppressFormativeEvidenceToken(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        return normalized != null
                && (FORMATIVE_EVIDENCE_SUPPRESSED.contains(normalized)
                        || shouldSuppressFormativeSignalToken(normalized));
    }

    public static boolean isAllowedFormativeDerivedParent(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        return normalized != null && FORMATIVE_DERIVED_PARENT_ALLOWED.contains(normalized);
    }

    public static boolean isLowValueFormativeConceptWord(String word) {
        String normalized = SignalNormalizer.normalizeOne(word);
        return normalized != null && FORMATIVE_LABEL_WORD_IGNORED.contains(normalized);
    }

    public static boolean looksLikeSilhouetteAbstractConceptText(String text) {
        String normalized = normalizeSurfaceText(text);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (isInterpretiveCategory(SignalConceptRegistry.categoryForConcept(normalized))) {
            return true;
        }
        String[] parts = normalized.split("_+");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (isInterpretiveCategory(SignalConceptRegistry.categoryForConcept(part))) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldKeepSignalTokenSilhouetteOnly(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (SignalConceptRegistry.isCanonicalConcept(normalized)) {
            return false;
        }
        return isInterpretiveCategory(SignalConceptRegistry.categoryForConcept(normalized));
    }

    public static boolean isLowValueSilhouetteMetaObservation(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lowered = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (lowered.isBlank()) {
            return true;
        }
        boolean genericFrame = lowered.contains("focuses on")
                || lowered.contains("marker")
                || lowered.contains("primary filter")
                || lowered.contains("filter for")
                || lowered.contains("compatibility")
                || lowered.contains("belonging");
        boolean genericDomain = lowered.contains("lifestyle")
                || lowered.contains("cultural")
                || lowered.contains("activity-based")
                || lowered.contains("relationship")
                || lowered.contains("social");
        return genericFrame && genericDomain;
    }

    private static boolean isInterpretiveCategory(String category) {
        String normalized = SignalTaxonomy.normalizeCategory(category);
        return SignalTaxonomy.VALUES.equals(normalized)
                || SignalTaxonomy.SOCIAL_STYLE.equals(normalized)
                || SignalTaxonomy.HARD_FILTERS.equals(normalized);
    }

    private static String normalizeSurfaceText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? null : normalized;
    }
}
