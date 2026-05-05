package now.calypso.backendapi.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import now.calypso.backendapi.silhouette.SilhouetteDigest;

class MatchRerankerTest {
    @AfterEach
    void clearOverride() {
        MatchReranker.clearTestOverride();
    }

    @Test
    void rerank_sanitizesModePairBatchOutput() {
        MatchReranker.RerankRequest request = new MatchReranker.RerankRequest();
        request.surface = "facecards";
        request.rankingGoal = "discover";
        request.viewer = new SilhouetteDigest();

        MatchReranker.Candidate allowed = new MatchReranker.Candidate();
        allowed.candidateId = "cand_1";
        allowed.digest = new SilhouetteDigest();
        request.candidates.add(allowed);

        MatchReranker.Candidate other = new MatchReranker.Candidate();
        other.candidateId = "cand_2";
        other.digest = new SilhouetteDigest();
        request.candidates.add(other);

        MatchReranker.setTestOverride(req -> {
            MatchReranker.RerankResult result = new MatchReranker.RerankResult();
            MatchReranker.Decision invalid = new MatchReranker.Decision();
            invalid.candidateId = "unknown";
            invalid.finalScore = 1.0;
            result.rankedCandidates.add(invalid);

            MatchReranker.Decision decision = new MatchReranker.Decision();
            decision.candidateId = "cand_1";
            decision.finalScore = 1.8;
            decision.sparkScore = -0.2;
            decision.sustainabilityScore = 0.7;
            decision.learningValueScore = 0.9;
            decision.confidence = 1.4;
            decision.recommendedUse = "rank_high";
            decision.fitSummaryInternal = "Strong spark, sustainability still partly unknown.";
            MatchReranker.BestModePair pair = new MatchReranker.BestModePair();
            pair.viewerModeId = "mode_viewer";
            pair.candidateModeId = "mode_candidate";
            decision.bestModePair = pair;
            decision.missingInfo.add("communication rhythm");
            result.rankedCandidates.add(decision);
            return result;
        });

        MatchReranker.RerankResult result = MatchReranker.rerank(null, request);

        assertNotNull(result);
        assertEquals(1, result.rankedCandidates.size());
        MatchReranker.Decision decision = result.rankedCandidates.get(0);
        assertEquals("cand_1", decision.candidateId);
        assertEquals(1.0, decision.finalScore);
        assertEquals(0.0, decision.sparkScore);
        assertEquals(1.0, decision.confidence);
        assertEquals("rank_high", decision.recommendedUse);
        assertNotNull(decision.bestModePair);
        assertEquals("mode_viewer", decision.bestModePair.viewerModeId);
        assertFalse(decision.missingInfo.isEmpty());
    }
}
