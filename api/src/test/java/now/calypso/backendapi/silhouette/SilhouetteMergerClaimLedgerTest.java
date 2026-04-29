package now.calypso.backendapi.silhouette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SilhouetteMergerClaimLedgerTest {
    @Test
    void apply_populatesClaimLedgerAndSummaryCache() {
        SilhouetteState base = SilhouetteState.empty(42L);
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "seeking_core",
                null,
                "Drawn to ambitious, intellectually curious partners.",
                null,
                "preference",
                0.78,
                List.of("ev_1")));
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "partner_comps",
                null,
                "Mustang from Red Rising energy",
                null,
                "partner_comp",
                0.72,
                List.of("ev_1")));

        SilhouetteState merged = SilhouetteMerger.apply(
                base,
                patch,
                1.0,
                "private_prompt",
                "instance_1",
                "private.drawn_to",
                "event_1",
                "Drawn to protagonist-like equals.",
                System.currentTimeMillis());

        assertNotNull(merged);
        assertNotNull(merged.claims);
        assertFalse(merged.claims.isEmpty(), "claim ledger should append applied patch semantics");
        assertNotNull(merged.summaryCache);
        assertFalse(merged.summaryCache.rerankerShort.isBlank(), "reranker cache should be materialized");
        assertEquals(merged.version, merged.summaryCache.generatedFromVersion);
        String digest = merged.digest(500);
        assertTrue(digest.contains("summary:"), "digest should prefer cache-based summary");
    }

    @Test
    void toMapFromMap_roundTripsClaimsAndSummaryCache() {
        SilhouetteState state = SilhouetteState.empty(99L);
        SilhouetteState.Claim claim = new SilhouetteState.Claim();
        claim.id = "cl_1";
        claim.facet = "seeking_core";
        claim.text = "Values reciprocity and ambition.";
        claim.confidence = 0.66;
        claim.source = "private_prompt";
        claim.sourceId = "instance_9";
        claim.promptId = "private.values";
        state.claims.add(claim);
        state.summaryCache.rerankerShort = "seeking: reciprocity and ambition";
        state.summaryCache.adminLong = "recent_claims: ...";
        state.summaryCache.generatedFromVersion = state.version;
        state.summaryCache.updatedAt = System.currentTimeMillis();

        Map<String, Object> serialized = state.toMap();
        SilhouetteState decoded = SilhouetteState.fromMap(serialized, state.accountId);

        assertNotNull(decoded.claims);
        assertFalse(decoded.claims.isEmpty());
        assertEquals("seeking_core", decoded.claims.get(0).facet);
        assertNotNull(decoded.summaryCache);
        assertTrue(decoded.summaryCache.rerankerShort.contains("reciprocity"));
    }

    @Test
    void apply_dropsConcreteSignalEchoAndGenericMetaClaims() {
        SilhouetteState base = SilhouetteState.empty(77L);
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "hard_boundaries",
                null,
                "Prefers to exclude partners into reality tv and country music.",
                null,
                "preference",
                0.72,
                List.of()));
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "meta_observation",
                null,
                "Focuses on lifestyle and cultural markers as primary filters for relationship compatibility.",
                null,
                "depth_vs_surface_focus",
                0.60,
                List.of()));
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "seeking_core",
                null,
                "Wants a partner who challenges them intellectually and is emotionally steady.",
                null,
                "preference",
                0.75,
                List.of()));

        SilhouetteState merged = SilhouetteMerger.apply(
                base,
                patch,
                1.0,
                "private_prompt",
                "instance_77",
                "private.drawn_to",
                "event_77",
                "answer",
                System.currentTimeMillis());

        assertNotNull(merged);
        assertNotNull(merged.claims);
        assertEquals(1, merged.claims.size(), "only the residual abstract claim should remain");
        assertEquals("seeking_core", merged.claims.get(0).facet);
        assertTrue(merged.claims.get(0).text.toLowerCase().contains("intellect"));
    }

    @Test
    void apply_dropsConcreteCommunityEchoClaims() {
        SilhouetteState base = SilhouetteState.empty(78L);
        SilhouettePatch patch = new SilhouettePatch();
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "self_core",
                null,
                "Identifies strongly with the gym community as a primary social scene.",
                null,
                "preference",
                0.74,
                List.of()));
        patch.ops.add(new SilhouettePatch.Op(
                "upsert_claim",
                "seeking_core",
                null,
                "Wants emotionally grounded people who push each other to grow.",
                null,
                "preference",
                0.76,
                List.of()));

        SilhouetteState merged = SilhouetteMerger.apply(
                base,
                patch,
                1.0,
                "private_prompt",
                "instance_78",
                "private.communities.scene",
                "event_78",
                "answer",
                System.currentTimeMillis());

        assertNotNull(merged);
        assertNotNull(merged.claims);
        assertEquals(1, merged.claims.size(), "Concrete community echoes should be filtered out of silhouette.");
        assertEquals("seeking_core", merged.claims.get(0).facet);
    }

    @Test
    void summaryCache_usesSharedRankedClaimBasisForRerankerAndAdmin() {
        long now = System.currentTimeMillis();
        SilhouetteState base = SilhouetteState.empty(79L);

        SilhouetteState.Claim c1 = new SilhouetteState.Claim();
        c1.id = "cl_a";
        c1.facet = "seeking_core";
        c1.text = "Revisits Jojo and Elden Ring often.";
        c1.confidence = 0.78;
        c1.createdAt = now - 5_000L;
        base.claims.add(c1);

        SilhouetteState.Claim c2 = new SilhouetteState.Claim();
        c2.id = "cl_b";
        c2.facet = "seeking_core";
        c2.text = "Y2K soft-club visual aesthetic.";
        c2.confidence = 0.74;
        c2.createdAt = now - 4_000L;
        base.claims.add(c2);

        SilhouetteState.Claim c3 = new SilhouetteState.Claim();
        c3.id = "cl_c";
        c3.facet = "self_core";
        c3.text = "Keeps a consistent gym routine.";
        c3.confidence = 0.71;
        c3.createdAt = now - 3_000L;
        base.claims.add(c3);

        SilhouetteState merged = SilhouetteMerger.apply(
                base,
                new SilhouettePatch(),
                1.0,
                "private_prompt",
                "instance_79",
                "private.media.revisit",
                "event_79",
                "answer",
                now);

        assertNotNull(merged.summaryCache);
        assertTrue(merged.summaryCache.rerankerShort.toLowerCase().contains("jojo"));
        assertTrue(merged.summaryCache.rerankerShort.toLowerCase().contains("y2k"));
        assertTrue(merged.summaryCache.adminLong.contains("ranked_claims:"));
        assertTrue(merged.summaryCache.adminLong.toLowerCase().contains("jojo"));
        assertTrue(merged.summaryCache.adminLong.toLowerCase().contains("y2k"));
    }
}
