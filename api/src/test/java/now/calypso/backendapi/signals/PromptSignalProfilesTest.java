package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PromptSignalProfilesTest {

    @Test
    void forPromptId_returnsConfiguredProfileForKnownPrompt() {
        PromptSignalProfiles.PromptSignalProfile profile = PromptSignalProfiles.forPromptId("prompt.talk.hours");
        assertEquals("prompt.talk.hours", profile.promptId());
        assertEquals(1, profile.promptPasses());
        assertFalse(profile.runSpecificityPass());
        assertTrue(profile.maxSignals() <= 6);
        assertTrue(profile.extractionHint().contains("explicit affinity"));
    }

    @Test
    void forPromptId_usesDefaultProfileForUnknownPrompt() {
        PromptSignalProfiles.PromptSignalProfile profile = PromptSignalProfiles.forPromptId("prompt.unknown.test");
        PromptSignalProfiles.PromptSignalProfile fallback = PromptSignalProfiles.defaultProfile();
        assertEquals(fallback.promptId(), profile.promptId());
        assertEquals(fallback.promptPasses(), profile.promptPasses());
        assertEquals(fallback.maxSignals(), profile.maxSignals());
        assertEquals(fallback.runSpecificityPass(), profile.runSpecificityPass());
    }
}
