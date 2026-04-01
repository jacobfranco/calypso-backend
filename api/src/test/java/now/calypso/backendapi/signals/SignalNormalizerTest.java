package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SignalNormalizerTest {

    @Test
    void normalizeOne_collapsesPossessiveVariants() {
        assertEquals("jojos_bizarre_adventure", SignalNormalizer.normalizeOne("Jojo's bizarre adventure"));
        assertEquals("jojo_bizarre_adventure", SignalNormalizer.normalizeOne("jojo_s_bizarre_adventure"));
        assertEquals("jojo_bizarre_adventure", SignalNormalizer.normalizeOne("jojo bizarre adventure"));
    }

    @Test
    void normalizeOne_keepsNonPossessivePluralForms() {
        assertEquals("fitness_goals", SignalNormalizer.normalizeOne("fitness goals"));
        assertEquals("users_club", SignalNormalizer.normalizeOne("users club"));
    }

    @Test
    void normalizeOne_keepsNearPossessiveVariantsDistinct() {
        assertEquals("jojos_bizarre_adventure", SignalNormalizer.normalizeOne("jojos bizarre adventure"));
        assertEquals("jojo_bizarre_adventure", SignalNormalizer.normalizeOne("jojo bizarre adventure"));
    }

    @Test
    void normalizeOne_preservesSemanticVariantsForLlmCanonicalization() {
        assertEquals("wanderlust", SignalNormalizer.normalizeOne("wanderlust"));
        assertEquals("traveling_adventures", SignalNormalizer.normalizeOne("traveling_adventures"));
        assertEquals("travel_the_world", SignalNormalizer.normalizeOne("travel_the_world"));
        assertEquals("phd", SignalNormalizer.normalizeOne("phd"));
        assertEquals("graduate_degree", SignalNormalizer.normalizeOne("graduate_degree"));
    }

    @Test
    void normalizeOne_preservesWrapperConceptShapesForPromptLevelControl() {
        assertEquals("cooking_homemade_meals", SignalNormalizer.normalizeOne("cooking_homemade_meals"));
        assertEquals("cozy_homebody", SignalNormalizer.normalizeOne("cozy_homebody"));
        assertEquals("reading_books", SignalNormalizer.normalizeOne("reading_books"));
        assertEquals("sports_fandom", SignalNormalizer.normalizeOne("sports_fandom"));
        assertEquals("casual_gaming", SignalNormalizer.normalizeOne("casual_gaming"));
        assertEquals("career_direction", SignalNormalizer.normalizeOne("career_direction"));
    }

    @Test
    void normalizeOne_preservesTokensThatRequirePromptLevelFiltering() {
        assertEquals("relaxing_rest_of_day", SignalNormalizer.normalizeOne("relaxing_rest_of_day"));
        assertEquals("bucket_list", SignalNormalizer.normalizeOne("bucket_list"));
    }

    @Test
    void normalizeOne_stripsIntentSuffixesOnly() {
        assertEquals("weeb", SignalNormalizer.normalizeOne("weebself"));
        assertEquals("anime", SignalNormalizer.normalizeOne("anime_seeking"));
    }
}
