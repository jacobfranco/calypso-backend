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

        ExtractedSignal redRising = find(out, "red_rising");
        assertNotNull(redRising);
        assertEquals(SignalIntent.SEEKING, redRising.intent());
        assertNotNull(redRising.valence());
        assertTrue(redRising.valence().doubleValue() > 0.0);
    }

    private static ExtractedSignal find(List<ExtractedSignal> signals, String token) {
        if (signals == null || signals.isEmpty() || token == null || token.isBlank()) {
            return null;
        }
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null) {
                continue;
            }
            if (token.equals(signal.token())) {
                return signal;
            }
        }
        return null;
    }
}

