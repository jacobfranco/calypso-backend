package now.calypso.backendapi.filters;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Simple read‑only bean that exposes the tag dictionaries
 * (grouped form for UI, flat form for validation logic).
 */
@Service
public class TagDictionaryService {

    // grouped versions for the onboarding UI
    public Map<String, List<String>> lifestyle() {
        return TagDictionaryLoader.LIFESTYLE;
    }

    public Map<String, List<String>> interests() {
        return TagDictionaryLoader.INTEREST;
    }

    // flattened versions for fast membership checks
    public Set<String> lifestyleFlat() {
        return TagDictionaryLoader.flat(lifestyle());
    }

    public Set<String> interestsFlat() {
        return TagDictionaryLoader.flat(interests());
    }
}
