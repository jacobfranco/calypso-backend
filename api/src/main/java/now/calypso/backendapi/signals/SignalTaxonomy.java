package now.calypso.backendapi.signals;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SignalTaxonomy {
    public static final String HOBBIES = "hobbies";
    public static final String MEDIA = "media";
    public static final String LIFESTYLE = "lifestyle";
    public static final String VALUES = "values";
    public static final String HARD_FILTERS = "hard_filters";
    public static final String SOCIAL_STYLE = "social_style";
    public static final String OTHER = "other";

    private static final Set<String> VALID_CATEGORIES = Set.of(
            HOBBIES,
            MEDIA,
            LIFESTYLE,
            VALUES,
            HARD_FILTERS,
            SOCIAL_STYLE,
            OTHER);

    private static final Map<String, String> EXPLICIT_OVERRIDES;

    static {
        HashMap<String, String> overrides = new HashMap<>();

        // Media + franchises
        overrides.put("reality_tv", MEDIA);
        overrides.put("country_music", MEDIA);
        overrides.put("red_rising", MEDIA);
        overrides.put("sci_fi", MEDIA);
        overrides.put("frieren_beyond_journeys_end", MEDIA);
        overrides.put("anime", MEDIA);
        overrides.put("manga", MEDIA);
        overrides.put("kpop", MEDIA);
        overrides.put("jpop", MEDIA);
        overrides.put("hip_hop", MEDIA);
        overrides.put("jazz", MEDIA);
        overrides.put("metal", MEDIA);
        overrides.put("edm", MEDIA);
        overrides.put("indie_music", MEDIA);
        overrides.put("classical_music", MEDIA);
        overrides.put("podcasts", MEDIA);
        overrides.put("film", MEDIA);
        overrides.put("cinema", MEDIA);
        overrides.put("books", MEDIA);
        overrides.put("reading", MEDIA);

        // Hobbies
        overrides.put("gaming", HOBBIES);
        overrides.put("video_games", HOBBIES);
        overrides.put("pc_gaming", HOBBIES);
        overrides.put("board_games", HOBBIES);
        overrides.put("dnd", HOBBIES);
        overrides.put("fitness", HOBBIES);
        overrides.put("gym", HOBBIES);
        overrides.put("running", HOBBIES);
        overrides.put("hiking", HOBBIES);
        overrides.put("yoga", HOBBIES);
        overrides.put("pilates", HOBBIES);
        overrides.put("cycling", HOBBIES);
        overrides.put("swimming", HOBBIES);
        overrides.put("cooking", HOBBIES);
        overrides.put("baking", HOBBIES);
        overrides.put("photography", HOBBIES);
        overrides.put("painting", HOBBIES);
        overrides.put("dance", HOBBIES);
        overrides.put("karaoke", HOBBIES);
        overrides.put("soccer", HOBBIES);
        overrides.put("basketball", HOBBIES);
        overrides.put("football", HOBBIES);
        overrides.put("nfl", HOBBIES);
        overrides.put("carolina_panthers", HOBBIES);
        overrides.put("baseball", HOBBIES);
        overrides.put("hockey", HOBBIES);
        overrides.put("nhl", HOBBIES);
        overrides.put("florida_panthers", HOBBIES);
        overrides.put("tennis", HOBBIES);
        overrides.put("f1", HOBBIES);
        overrides.put("ufc", HOBBIES);

        // Lifestyle + concrete compatibility filters
        overrides.put("travel", LIFESTYLE);
        overrides.put("nightlife", LIFESTYLE);
        overrides.put("club", LIFESTYLE);
        overrides.put("coffee", LIFESTYLE);
        overrides.put("tea", LIFESTYLE);
        overrides.put("wine", LIFESTYLE);
        overrides.put("beer", LIFESTYLE);
        overrides.put("vegan", LIFESTYLE);
        overrides.put("vegetarian", LIFESTYLE);
        overrides.put("city_life", LIFESTYLE);
        overrides.put("suburban_life", LIFESTYLE);
        overrides.put("homebody", LIFESTYLE);
        overrides.put("cozy", LIFESTYLE);
        overrides.put("faith", LIFESTYLE);
        overrides.put("spirituality", LIFESTYLE);
        overrides.put("politics", LIFESTYLE);

        // Interpersonal values
        overrides.put("honesty", VALUES);
        overrides.put("loyalty", VALUES);
        overrides.put("empathy", VALUES);
        overrides.put("respect", VALUES);
        overrides.put("kindness", VALUES);
        overrides.put("intelligence", VALUES);
        overrides.put("creativity", VALUES);
        overrides.put("discipline", VALUES);
        overrides.put("consistency", VALUES);
        overrides.put("commitment", VALUES);
        overrides.put("communication", VALUES);
        overrides.put("ambition", VALUES);

        // Social style
        overrides.put("socializing", SOCIAL_STYLE);
        overrides.put("networking", SOCIAL_STYLE);
        overrides.put("public_speaking", SOCIAL_STYLE);
        overrides.put("community", SOCIAL_STYLE);

        EXPLICIT_OVERRIDES = Map.copyOf(overrides);
    }

    private SignalTaxonomy() {
    }

    public static String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (VALID_CATEGORIES.contains(normalized)) {
            return normalized;
        }
        return null;
    }

    public static String categoryForToken(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null || normalized.isBlank()) {
            return OTHER;
        }

        String explicit = EXPLICIT_OVERRIDES.get(normalized);
        if (explicit != null) {
            return explicit;
        }

        if (containsAny(normalized,
                "anime", "manga", "tv", "film", "movie", "cinema", "music", "podcast", "book", "novel",
                "genre", "concert", "stream", "kpop", "jpop", "hip_hop", "jazz", "metal", "edm")) {
            return MEDIA;
        }

        if (containsAny(normalized,
                "gaming", "game", "sports", "soccer", "basketball", "football", "baseball", "hockey", "tennis",
                "fitness", "gym", "run", "hiking", "yoga", "pilates", "cycling", "swimming", "cooking",
                "baking", "photography", "painting", "dance", "karaoke", "cosplay", "museum", "theater", "opera")) {
            return HOBBIES;
        }

        if (containsAny(normalized,
                "travel", "nightlife", "club", "coffee", "tea", "wine", "beer", "vegan", "vegetarian", "meal",
                "city_life", "suburban_life", "homebody", "cozy", "pets", "dogs", "cats", "faith", "spirituality",
                "politics", "relig", "family", "lifestyle", "beach", "mountains")) {
            return LIFESTYLE;
        }

        if (containsAny(normalized,
                "honesty", "loyalty", "empathy", "respect", "kindness", "intelligence", "creativity", "discipline",
                "consistency", "commitment", "ambition", "trust", "integrity")) {
            return VALUES;
        }

        if (containsAny(normalized,
                "social", "network", "community", "public_speaking", "greek_life", "extro", "intro")) {
            return SOCIAL_STYLE;
        }

        return OTHER;
    }

    public static boolean isConcreteCategory(String category) {
        String normalized = normalizeCategory(category);
        if (normalized == null) {
            return false;
        }
        return HOBBIES.equals(normalized)
                || MEDIA.equals(normalized)
                || LIFESTYLE.equals(normalized);
    }

    private static boolean containsAny(String token, String... needles) {
        if (token == null || token.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && token.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
