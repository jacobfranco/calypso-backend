package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;

class SignalExtractorExplicitMentionTest {

    @Test
    void augmentWithExplicitTitleMentions_extractsConcreteCommunitySignal() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.communities.scene",
                "What communities or scene have you felt the most at home in?",
                "the gym",
                List.of(),
                List.of());

        ExtractedSignal gym = find(out, "gym");
        assertNotNull(gym, "Concrete community mentions should produce filterable signals.");
        assertEquals(SignalIntent.SELF, gym.intent());
        assertNotNull(gym.valence());
        assertTrue(gym.valence().doubleValue() > 0.0);
    }

    @Test
    void augmentWithExplicitTitleMentions_extractsNegativePreferenceAsSeeking() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.popular.dislike",
                "What's something popular that you really don't like?",
                "Reality TV.",
                List.of(),
                List.of());

        ExtractedSignal realityTv = find(out, "reality_tv");
        assertNotNull(realityTv);
        assertEquals(SignalIntent.SEEKING, realityTv.intent());
        assertNotNull(realityTv.valence());
        assertTrue(realityTv.valence().doubleValue() < 0.0);
    }

    @Test
    void augmentWithExplicitTitleMentions_handlesFictionalCharacterRelatabilityIntent() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.fictional.characters",
                "Name up to 3 fictional characters you've felt drawn to romantically.",
                "I relate to Frieren because she is reserved but caring.",
                List.of(),
                List.of());

        ExtractedSignal frieren = find(out, "frieren_beyond_journeys_end");
        assertNotNull(frieren);
        assertEquals(SignalIntent.SELF, frieren.intent(),
                "Relatability framing should map fictional character mentions to self intent.");
        assertNotNull(frieren.valence());
        assertTrue(frieren.valence().doubleValue() > 0.0);
    }

    @Test
    void augmentWithExplicitTitleMentions_handlesDrawnToFranchiseIntent() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.drawn.to",
                "Describe the kind of person you tend to be drawn to.",
                "Someone like Victra from Red Rising.",
                List.of(),
                List.of());

        ExtractedSignal redRising = find(out, "red_rising", SignalIntent.SELF);
        assertNotNull(redRising);
        assertEquals(SignalIntent.SELF, redRising.intent());
        assertNotNull(redRising.valence());
        assertTrue(redRising.valence().doubleValue() > 0.0);
    }

    @Test
    void augmentWithExplicitTitleMentions_extractsRabbitHoleDomainsViaCanonicalAliases() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.rabbit.hole",
                "What topic or niche have you spent way too much time exploring?",
                "I go into rabbit holes about space and leftist leaders.",
                List.of(),
                List.of());

        assertNotNull(find(out, "space", SignalIntent.SELF));
        assertNotNull(find(out, "leftist_politics", SignalIntent.SELF));
    }

    @Test
    void augmentWithExplicitTitleMentions_mirrorsSelfHobbiesIntoSeekingWhenAnswerSaysAllOfThose() {
        List<ExtractedSignal> out = SignalExtractor.augmentWithExplicitTitleMentions(
                "private.hobbies",
                "What are your hobbies? Which hobbies would you like to share with your partner?",
                "The gym and video games mainly, also anime. I'd like to share all of those.",
                List.of(),
                List.of(
                        ExtractedSignal.from("gym", SignalIntent.SELF, 0.76),
                        ExtractedSignal.from("video_games", SignalIntent.SELF, 0.82),
                        ExtractedSignal.from("anime", SignalIntent.SELF, 0.74),
                        ExtractedSignal.from("video_games", SignalIntent.SEEKING, 0.70),
                        ExtractedSignal.from("anime", SignalIntent.SEEKING, 0.68)));

        assertNotNull(find(out, "gym", SignalIntent.SEEKING),
                "When users say they want to share all previously-mentioned hobbies, self hobby concepts should mirror to seeking.");
    }

    private static ExtractedSignal find(List<ExtractedSignal> signals, String token) {
        return find(signals, token, null);
    }

    private static ExtractedSignal find(List<ExtractedSignal> signals, String token, SignalIntent intent) {
        if (signals == null || signals.isEmpty() || token == null || token.isBlank()) {
            return null;
        }
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null) {
                continue;
            }
            if (intent != null && signal.intent() != intent) {
                continue;
            }
            if (token.equals(signal.token())) {
                return signal;
            }
        }
        return null;
    }
}
