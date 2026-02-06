package now.calypso.backendapi.filters;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads the static JSON dictionaries from the class‑path once at startup.
 */
public final class TagDictionaryLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Map<String, List<String>> LIFESTYLE = load("/lifestyle-tags.json");
    public static final Map<String, List<String>> GENDER = load("/gender-tags.json");
    public static final Map<String, List<String>> RELIGION = load("/religion-tags.json");
    public static final Map<String, List<String>> POLITICS = load("/politics-tags.json");

    public static final Set<String> LIFESTYLE_FLAT = flat(LIFESTYLE);
    public static final Set<String> GENDER_FLAT = flat(GENDER);
    public static final Set<String> RELIGION_FLAT = flat(RELIGION);
    public static final Set<String> POLITICS_FLAT = flat(POLITICS);

    private TagDictionaryLoader() {
        /* utility */ }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> load(String path) {
        try (InputStream in = TagDictionaryLoader.class.getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("Resource not found: " + path);
            return MAPPER.readValue(in, Map.class); // category → list
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Helper to flatten a category map into a single Set. */
    public static Set<String> flat(Map<String, List<String>> map) {
        return map.values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableSet());
    }
}
