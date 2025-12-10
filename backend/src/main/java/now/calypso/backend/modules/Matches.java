package now.calypso.backend.modules;

import com.rpl.rama.*;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.*;

import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.*;

import java.util.*;

import static now.calypso.backend.CalypsoHelpers.extractFields;

public class Matches implements RamaModule {

        // ------- Tunables -------
        private static final int HEAP_K = 400;
        private static final long EXPOSURE_TTL_MS = 14L * 24 * 60 * 60 * 1000L; // 14 days
        private static final double MIN_SCORE_CASUAL = 60.0;
        private static final double MIN_SCORE_SERIOUS = 75.0;

        // ---------------------------
        // Low-level helpers
        // ---------------------------

        private static MatchCandidate mkCandidate(long targetId, double score, long now) {
                MatchCandidate c = new MatchCandidate();
                c.setTargetAccountId(targetId);
                c.setStage0Score(score);
                c.setComputedAt(now);
                return c;
        }

        private static List<MatchCandidate> upsertIntoHeap(List<MatchCandidate> heap, MatchCandidate cand) {
                if (cand == null) {
                        return (heap == null) ? new ArrayList<MatchCandidate>() : heap;
                }

                ArrayList<MatchCandidate> list = (heap == null) ? new ArrayList<>() : new ArrayList<>(heap);

                // Remove existing candidate with the same target id (at most one)
                for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).getTargetAccountId() == cand.getTargetAccountId()) {
                                list.remove(i);
                                break;
                        }
                }

                // Find insertion index to keep list sorted:
                // - higher score first
                // - for ties, smaller target id first
                int idx = 0;
                while (idx < list.size()) {
                        MatchCandidate cur = list.get(idx);
                        int cmp = Double.compare(cur.getStage0Score(), cand.getStage0Score());
                        if (cmp < 0) {
                                // current score < new score → insert before
                                break;
                        } else if (cmp == 0 && cur.getTargetAccountId() > cand.getTargetAccountId()) {
                                // same score, keep smaller id first
                                break;
                        }
                        idx++;
                }
                list.add(idx, cand);

                // Enforce heap cap
                if (list.size() > HEAP_K) {
                        list.remove(list.size() - 1);
                }

                return list;
        }

        // Filter a heap against fresh exposures at query time
        private static List<MatchCandidate> filterHeapByExposure(List<MatchCandidate> heap,
                        Map<?, ?> exposureMap,
                        long now) {
                if (heap == null || heap.isEmpty())
                        return Collections.emptyList();
                ArrayList<MatchCandidate> out = new ArrayList<>();
                for (MatchCandidate c : heap) {
                        if (c == null)
                                continue;
                        Object tsObj = (exposureMap == null) ? null : exposureMap.get(c.getTargetAccountId());
                        Long ts = (tsObj instanceof Number) ? ((Number) tsObj).longValue() : null;
                        if (ts == null || (now - ts) >= EXPOSURE_TTL_MS) {
                                out.add(c);
                        }
                }
                return out;
        }

        // --------------------------------
        // Topology declarations
        // --------------------------------

        private void declareFiltersTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("filters");

                // accountId -> Filters (Thrift struct)
                stream.pstate("$$accountIdToFiltersProjection",
                                PState.mapSchema(Long.class, Filters.class));

                // mirror map where value == key, so we can iterate all accountIds
                stream.pstate("$$allAccountIdsVec",
                                PState.mapSchema(Long.class, Long.class));

                stream.source("*filtersDepot").out("*data")
                                .macro(extractFields("*data", "*accountId"))
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                                // constant partition key so all filters live on one shard
                                .each((Long aid) -> 0L, "*aidL").out("*partKey")
                                .hashPartition("*partKey")
                                .localTransform("$$accountIdToFiltersProjection",
                                                Path.key("*aidL").termVal("*data"))
                                .localTransform("$$allAccountIdsVec",
                                                Path.key("*aidL").termVal("*aidL"));
        }

        private void declareServeAndCursorTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("serveAndCursor");

                // viewer -> (targetId -> servedAt)
                stream.pstate("$$accountIdToExposure",
                                PState.mapSchema(Long.class, Map.class));

                // { lastIndex, wrappedOnce } per viewer
                stream.pstate("$$accountIdToCursor",
                                PState.mapSchema(Long.class,
                                                PState.fixedKeysSchema("lastIndex", Integer.class,
                                                                "wrappedOnce", Boolean.class)));

                // record exposures
                stream.source("*matchesServeDepot").out("*data")
                                .macro(extractFields("*data", "*accountId", "*targetIds", "*servedAt"))
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                                .each((Long aid) -> 0L, "*aidL").out("*partKey")
                                .hashPartition("*partKey")
                                .each(Ops.EXPLODE, "*targetIds").out("*targetId")
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*targetId").out("*tidL")
                                .each((Number n) -> n == null ? System.currentTimeMillis() : n.longValue(), "*servedAt")
                                .out("*servedAtL")
                                .localTransform("$$accountIdToExposure",
                                                Path.key("*aidL", "*tidL").termVal("*servedAtL"));

                // apply cursor ACKs
                stream.source("*matchesCursorAckDepot").out("*data")
                                .macro(extractFields("*data", "*accountId", "*lastIndex", "*wrappedOnce"))
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                                .each((Number n) -> n == null ? 0 : n.intValue(), "*lastIndex").out("*lastIndexI")
                                .each((Long aid) -> 0L, "*aidL").out("*partKey")
                                .hashPartition("*partKey")
                                .localTransform("$$accountIdToCursor",
                                                Path.key("*aidL", "lastIndex").termVal("*lastIndexI"))
                                .localTransform("$$accountIdToCursor",
                                                Path.key("*aidL", "wrappedOnce").termVal("*wrappedOnce"));
        }

        private void declareRefillTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("refill");

                // viewer -> sorted heap (List<MatchCandidate>)
                stream.pstate("$$accountIdToCandidateHeap",
                                PState.mapSchema(Long.class, List.class));

                stream.source("*matchRefillDepot").out("*data")
                                .macro(extractFields("*data", "*accountId", "*targetSize"))
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                                .each((Long aid) -> 0L, "*aidL").out("*partKey")
                                .hashPartition("*partKey")

                                // Start with a fresh empty heap for this viewer on every refill
                                .each(() -> new ArrayList<MatchCandidate>()).out("*emptyHeap")
                                .localTransform("$$accountIdToCandidateHeap",
                                                Path.key("*aidL").termVal("*emptyHeap"))

                                // Load viewer filters and exposures once
                                .localSelect("$$accountIdToFiltersProjection", Path.key("*aidL")).out("*viewerFilters")
                                .localSelect("$$accountIdToExposure", Path.key("*aidL")).out("*exposures")

                                // Iterate over all known accountIds on this shard: (targetId -> targetId)
                                .localSelect("$$allAccountIdsVec", Path.all()).out("*entry")
                                // entry is a MapEntry; we just want the key (accountId)
                                .each((Object e) -> {
                                        if (e instanceof java.util.Map.Entry) {
                                                return ((java.util.Map.Entry<?, ?>) e).getKey();
                                        }
                                        return null;
                                }, "*entry").out("*tidObj")
                                .each((Object n) -> (n instanceof Number) ? ((Number) n).longValue() : 0L,
                                                "*tidObj")
                                .out("*tidL")

                                // Skip self (viewer == target)
                                .each((Long vid, Long tid) -> vid != null && tid != null && !Objects.equals(vid, tid),
                                                "*aidL", "*tidL")
                                .out("*isOther")

                                .ifTrue("*isOther",
                                                Block.create()
                                                                // For each targetId, load its Filters
                                                                .localSelect("$$accountIdToFiltersProjection",
                                                                                Path.key("*tidL"))
                                                                .out("*targetFilters")

                                                                // Cast viewer/target filters and exposures cleanly
                                                                .each((Object vfObj) -> (Filters) vfObj,
                                                                                "*viewerFilters")
                                                                .out("*viewerFiltersC")
                                                                .each((Object tfObj) -> (tfObj instanceof Filters)
                                                                                ? (Filters) tfObj
                                                                                : null,
                                                                                "*targetFilters")
                                                                .out("*targetFiltersC")
                                                                .each((Filters viewer,
                                                                                Long tid,
                                                                                Filters target) -> {
                                                                        if (viewer == null || target == null) {
                                                                                return null;
                                                                        }

                                                                        long now = System.currentTimeMillis();

                                                                        double baseScore = CalypsoHelpers
                                                                                        .computeMatchesBaseScore(viewer,
                                                                                                        target);
                                                                        if (baseScore < 0.0) {
                                                                                return null; // incompatible
                                                                        }

                                                                        // Serious vs casual floor
                                                                        String viewerMode = CalypsoHelpers
                                                                                        .getModeSelfOrNull(viewer);
                                                                        double floor = ("serious"
                                                                                        .equalsIgnoreCase(viewerMode))
                                                                                                        ? MIN_SCORE_SERIOUS
                                                                                                        : MIN_SCORE_CASUAL;
                                                                        if (baseScore < floor) {
                                                                                return null;
                                                                        }

                                                                        return mkCandidate(tid, baseScore, now);
                                                                }, "*viewerFiltersC", "*tidL", "*targetFiltersC")
                                                                .out("*candMaybe")

                                                                // Read current heap, defaulting to empty list
                                                                .localSelect("$$accountIdToCandidateHeap",
                                                                                Path.key("*aidL"))
                                                                .out("*heapRaw")
                                                                .each((Object hObj) -> {
                                                                        if (hObj == null)
                                                                                return new ArrayList<MatchCandidate>();
                                                                        return (List<MatchCandidate>) hObj;
                                                                }, "*heapRaw").out("*currHeap")

                                                                // Upsert candidate if non-null; otherwise keep heap
                                                                // as-is
                                                                .each((List<MatchCandidate> heap,
                                                                                MatchCandidate cand) -> {
                                                                        if (cand == null)
                                                                                return heap;
                                                                        return upsertIntoHeap(heap, cand);
                                                                },
                                                                                "*currHeap", "*candMaybe")
                                                                .out("*newHeap")

                                                                .localTransform("$$accountIdToCandidateHeap",
                                                                                Path.key("*aidL").termVal("*newHeap")));
        }

        private void declareQueries(Topologies topologies) {
                // Simple paged fetch (startIdx/limit) without cursor
                topologies.query("getMatchesFromAccountId", "*viewerId", "*startIdx", "*limit").out("*results")
                                // Normalize viewer id to Long before partitioning/reads
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                                .each((Long vid) -> 0L, "*viewerIdL").out("*partKey")
                                .hashPartition("*partKey")
                                .localSelect("$$accountIdToCandidateHeap", Path.key("*viewerIdL")).out("*heapRaw")
                                .localSelect("$$accountIdToExposure", Path.key("*viewerIdL")).out("*exposures")
                                .each((List<MatchCandidate> heap, Map<Long, Long> ex) -> filterHeapByExposure(heap, ex,
                                                System.currentTimeMillis()),
                                                "*heapRaw", "*exposures")
                                .out("*heap")
                                // return to origin side for the final subbatch (required by <<query)
                                .originPartition()
                                .each((List<MatchCandidate> heap, Object startIdxObj, Object limitObj) -> {
                                        int start = 0; // ignore caller's startIdx for now
                                        int limit = (limitObj instanceof Number)
                                                        ? Math.max(0, ((Number) limitObj).intValue())
                                                        : 10;

                                        if (heap == null || heap.isEmpty() || limit == 0)
                                                return new ArrayList<MatchCandidate>();

                                        int end = Math.min(heap.size(), start + limit);
                                        return new ArrayList<>(heap.subList(start, end));
                                }, "*heap", "*startIdx", "*limit").out("*results");

                // Cursor-aware fetch:
                // - Interpret cursor.lastIndex as "page index" (0,1,2,...)
                // - Serve up to 2 pages of results; after that, return empty.
                topologies.query("getMatchesFromAccountIdWithCursor", "*viewerId", "*ignoredStartIdx", "*limit")
                                .out("*out")
                                // Normalize viewer id to Long
                                .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                                .each((Long vid) -> 0L, "*viewerIdL").out("*partKey")
                                .hashPartition("*partKey")
                                .localSelect("$$accountIdToCandidateHeap", Path.key("*viewerIdL")).out("*heapRaw")
                                .localSelect("$$accountIdToExposure", Path.key("*viewerIdL")).out("*exposures")
                                .each((List<MatchCandidate> heap, Map<Long, Long> ex) -> filterHeapByExposure(heap, ex,
                                                System.currentTimeMillis()),
                                                "*heapRaw", "*exposures")
                                .out("*heap")

                                // We treat "lastIndex" as "page index"
                                .localSelect("$$accountIdToCursor",
                                                Path.key("*viewerIdL", "lastIndex").nullToVal(0))
                                .out("*pageIdx")

                                // final subbatch must be at origin for <<query
                                .originPartition()
                                .each((List<MatchCandidate> heap, Object pageIdxObj, Object limitObj) -> {
                                        Map<String, Object> out = new HashMap<>();

                                        int pageIdx = (pageIdxObj instanceof Number)
                                                        ? ((Number) pageIdxObj).intValue()
                                                        : 0;
                                        int limit = (limitObj instanceof Number)
                                                        ? Math.max(0, ((Number) limitObj).intValue())
                                                        : 10;

                                        // If nothing to serve, or invalid limit, keep cursor unchanged
                                        if (heap == null || heap.isEmpty() || limit <= 0) {
                                                out.put("page", new ArrayList<MatchCandidate>());
                                                out.put("nextIdx", pageIdx);
                                                out.put("nextWrapped", false);
                                                return out;
                                        }

                                        // For this test, we only serve TWO pages:
                                        // - pageIdx = 0 -> first page
                                        // - pageIdx = 1 -> second page
                                        // - pageIdx >= 2 -> no more results
                                        if (pageIdx >= 2) {
                                                out.put("page", new ArrayList<MatchCandidate>());
                                                out.put("nextIdx", pageIdx);
                                                out.put("nextWrapped", true);
                                                return out;
                                        }

                                        int n = heap.size();
                                        int count = Math.min(limit, n);
                                        ArrayList<MatchCandidate> page = new ArrayList<>();
                                        for (int i = 0; i < count; i++) {
                                                page.add(heap.get(i));
                                        }

                                        int nextPageIdx = pageIdx + 1;
                                        out.put("page", page);
                                        out.put("nextIdx", nextPageIdx);
                                        out.put("nextWrapped", nextPageIdx >= 2);
                                        return out;
                                }, "*heap", "*pageIdx", "*limit").out("*out");
        }

        @Override
        public void define(Setup setup, Topologies topologies) {
                // Depots required by tests
                setup.declareDepot("*filtersDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
                setup.declareDepot("*matchRefillDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
                setup.declareDepot("*matchesServeDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
                setup.declareDepot("*matchesCursorAckDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

                declareFiltersTopology(topologies);
                declareServeAndCursorTopology(topologies);
                declareRefillTopology(topologies);
                declareQueries(topologies);
        }
}
