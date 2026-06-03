package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;

class ExtractedSignalTest {

    @Test
    void from_sentimentPrefixedTokenOverridesPositiveModelValence() {
        ExtractedSignal signal = ExtractedSignal.from("dislikes_guessing_games", SignalIntent.SELF, 0.91);

        assertNotNull(signal);
        assertEquals("guessing_games", signal.token());
        assertEquals(SignalIntent.SELF, signal.intent());
        assertEquals(-0.91, signal.valence(), 0.000001);
    }

    @Test
    void from_sentimentPrefixedTokenPreservesExplicitNegativeValence() {
        ExtractedSignal signal = ExtractedSignal.from("hate_guessing_games", SignalIntent.SEEKING, -0.72);

        assertNotNull(signal);
        assertEquals("guessing_games", signal.token());
        assertEquals(SignalIntent.SEEKING, signal.intent());
        assertEquals(-0.72, signal.valence(), 0.000001);
    }
}
