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

    public Map<String, List<String>> genders() {
        return TagDictionaryLoader.GENDER;
    }

    public Map<String, List<String>> religions() {
        return TagDictionaryLoader.RELIGION;
    }

    public Map<String, List<String>> politics() {
        return TagDictionaryLoader.POLITICS;
    }

    // flattened versions for fast membership checks
    public Set<String> lifestyleFlat() {
        return TagDictionaryLoader.LIFESTYLE_FLAT;
    }

    public Set<String> interestsFlat() {
        return TagDictionaryLoader.INTEREST_FLAT;
    }

    public Set<String> gendersFlat() {
        return TagDictionaryLoader.GENDER_FLAT;
    }

    public Set<String> religionsFlat() {
        return TagDictionaryLoader.RELIGION_FLAT;
    }

    public Set<String> politicsFlat() {
        return TagDictionaryLoader.POLITICS_FLAT;
    }
}
