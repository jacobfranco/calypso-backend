package now.calypso.backendapi.signals;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import now.calypso.backend.data.SignalIntent;

class SignalDisambiguationPlannerTest {

    @Test
    void detectPromptAmbiguities_flagsSportsNicknamesWithoutSpecificSport() {
        List<ExtractedSignal> extracted = List.of(
                ExtractedSignal.from("sports", SignalIntent.SELF, 0.7));
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                "prompt.ideal.sunday",
                "My ideal Sunday looks like...",
                "Watching the panthers and some gaming.",
                List.of(),
                extracted);
        assertTrue(candidates.stream().anyMatch(c -> c != null && "sports:panthers".equals(c.key)));
    }

    @Test
    void detectPromptAmbiguities_skipsSportsFollowupWhenSpecificSportAlreadyKnown() {
        List<ExtractedSignal> extracted = List.of(
                ExtractedSignal.from("sports", SignalIntent.SELF, 0.7),
                ExtractedSignal.from("football", SignalIntent.SELF, 0.7));
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                "prompt.ideal.sunday",
                "My ideal Sunday looks like...",
                "Watching the panthers and some gaming.",
                List.of(),
                extracted);
        assertFalse(candidates.stream().anyMatch(c -> c != null && "sports:panthers".equals(c.key)));
    }

    @Test
    void detectPromptAmbiguities_flagsGeneralMediaAmbiguity() {
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                "prompt.talk.hours",
                "I could talk for hours about...",
                "The Joker is peak character writing.",
                List.of(),
                List.of(ExtractedSignal.from("culture", SignalIntent.SELF, 0.5)));
        assertTrue(candidates.stream().anyMatch(c -> c != null && "media:joker".equals(c.key)));
    }

    @Test
    void detectPromptAmbiguities_flagsSelfVsPartnerScopeWhenTraitDirectionIsUnclear() {
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                "private.fascinating.people",
                "Who are some people (historical or living) that you find fascinating? Why?",
                "Nikola Tesla for his independence and obsessive focus.",
                List.of(),
                List.of(ExtractedSignal.from("intelligence", SignalIntent.SELF, 0.6)));
        assertTrue(candidates.stream().anyMatch(c -> c != null && "scope:self_vs_partner".equals(c.key)));
    }

    @Test
    void detectPromptAmbiguities_doesNotFlagScopeForDrawnToPrompt() {
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                "private.drawn.to",
                "Describe the kind of person you tend to be drawn to.",
                "Someone independent, focused, and ambitious.",
                List.of(),
                List.of(ExtractedSignal.from("ambition", SignalIntent.SEEKING, 0.72)));
        assertFalse(candidates.stream().anyMatch(c -> c != null && "scope:self_vs_partner".equals(c.key)));
    }
}
