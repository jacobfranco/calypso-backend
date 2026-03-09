package now.calypso.backend.modules;

import org.apache.thrift.protocol.TField;

import com.rpl.rama.*;
import com.rpl.rama.module.*;
import com.rpl.rama.ops.*;
import com.rpl.rama.helpers.*;

import now.calypso.backend.*;
import now.calypso.backend.CalypsoHelpers.ExtractCode;
import now.calypso.backend.data.*;

import static now.calypso.backend.CalypsoHelpers.extractFields;

import java.util.*;
import java.util.stream.Collectors;

public class Core implements RamaModule {

      // ------- Tunables -------
      private static final int HEAP_K = 400;
      private static final long EXPOSURE_TTL_MS = 14L * 24 * 60 * 60 * 1000L; // 14 days
      private static final double MIN_SCORE_EXPLORATORY = 50.0;
      private static final double MIN_SCORE_BALANCED = 60.0;
      private static final double MIN_SCORE_FOCUSED = 75.0;
      private static final String ALL_ACCOUNTS_KEY = "all";

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
                        // current score < new score -> insert before
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
                  return new ArrayList<>();
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

      private static void declareAccountsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("accounts");
            ModuleUniqueIdPState accountIdGen = new ModuleUniqueIdPState("$$accountIdGen");
            accountIdGen.declarePState(stream);
            stream.pstate("$$phoneToUser", PState.mapSchema(String.class,
                        PState.fixedKeysSchema("accountId", Long.class,
                                    "uuid", String.class)));
            stream.pstate("$$accountIdToAccount", PState.mapSchema(Long.class, Account.class));

            stream.source("*accountDepot").out("*data")
                        .macro(extractFields("*data", "*phone_number", "*uuid"))
                        .localSelect("$$phoneToUser", Path.key("*phone_number")).out("*currInfo")
                        .each(Ops.GET, "*currInfo", "uuid").out("*currUUID")
                        // Accept either first write or an idempotent retry from the same UUID
                        .ifTrue(new Expr(Ops.OR, new Expr(Ops.IS_NULL, "*currInfo"),
                                    new Expr(Ops.EQUAL, "*uuid", "*currUUID")),
                                    Block.macro(accountIdGen.genId("*accountId"))
                                                .localTransform("$$phoneToUser",
                                                            Path.key("*phone_number").multiPath(
                                                                        Path.key("accountId").termVal("*accountId"),
                                                                        Path.key("uuid").termVal("*uuid")))
                                                .hashPartition("*accountId")
                                                .localTransform("$$accountIdToAccount",
                                                            Path.key("*accountId").termVal("*data"))
                                                .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new,
                                                            "*accountId", "*data")
                                                .out("*accountWithId")
                                                .depotPartitionAppend("*accountWithIdDepot", "*accountWithId"));
      }

      private void declareAuthTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("relationshipsStream");

            stream.pstate(
                        "$$authCodeToAccountId",
                        PState.mapSchema(String.class, Long.class));

            stream.source("*authCodeDepot").out("*data")
                        .subSource("*data",
                                    SubSource.create(AddAuthCode.class)
                                                .macro(extractFields("*data", "*code", "*accountId"))
                                                .localTransform(
                                                            "$$authCodeToAccountId",
                                                            Path.key("*code").termVal("*accountId")),
                                    SubSource.create(RemoveAuthCode.class)
                                                .macro(extractFields("*data", "*code"))
                                                .localTransform(
                                                            "$$authCodeToAccountId",
                                                            Path.key("*code").termVoid()));
      }

      private static void declareApplicationTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("applications");
                // Declare a PState to map client IDs to Application objects
                stream.pstate("$$clientIdToApplication", PState.mapSchema(String.class, Application.class));
                // Source from the application depot
                stream.source("*applicationDepot").out("*application")
                                .localTransform("$$clientIdToApplication",
                                                Path.key(new Expr(Application::getClient_id, "*application"))
                                                                .termVal("*application"));
        }

      private static void declareFiltersTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("filters");

            stream.pstate("$$accountIdToFiltersProjection",
                        PState.mapSchema(Long.class, Filters.class));
            stream.pstate("$$allAccountIdsGlobal",
                        PState.mapSchema(String.class, Map.class));

            stream.source("*filtersDepot").out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToFiltersProjection",
                                    Path.key("*aidL").termVal("*data"))
                        .each((Long aid) -> ALL_ACCOUNTS_KEY, "*aidL").out("*allKey")
                        .hashPartition("*allKey")
                        .localTransform("$$allAccountIdsGlobal",
                                    Path.key("*allKey", "*aidL").termVal("*aidL"));
      }

      private static void declarePublicPromptsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("publicPrompts");

            stream.pstate("$$answerIdToPublicPromptAnswer",
                        PState.mapSchema(String.class, PublicPromptAnswer.class));
            stream.pstate("$$accountIdToPublicAnswerIdByPromptId",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$promptIdToAnswerIds",
                        PState.mapSchema(String.class, Map.class));
            stream.pstate("$$viewerIdToReactedAnswerIds",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToReactedPromptIds",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToReactionByAnswerId",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$viewerIdToTasteByToken",
                        PState.mapSchema(Long.class, Map.class));
            stream.pstate("$$accountIdToPublicPromptSelection",
                        PState.mapSchema(Long.class, PublicPromptSelection.class));

            stream.source("*publicPromptAnswerDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId", "*promptId", "*answerId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*answerId")
                        .localTransform("$$answerIdToPublicPromptAnswer",
                                    Path.key("*answerId").termVal("*data"))
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToPublicAnswerIdByPromptId",
                                    Path.key("*aidL", "*promptId").termVal("*answerId"))
                        .hashPartition("*promptId")
                        .localTransform("$$promptIdToAnswerIds",
                                    Path.key("*promptId", "*answerId").termVal(true));

            stream.source("*publicPromptReactionDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*viewerAccountId", "*promptId", "*answerId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerAccountId").out("*viewerIdL")
                        .each((PublicPromptReactionEvent event) -> {
                              if (event == null || !event.isSetReaction() || event.getReaction() == null)
                                    return 0;
                              return event.getReaction().getValue();
                        }, "*data")
                        .out("*reactionValue")
                        .localTransform("$$viewerIdToReactedAnswerIds",
                                    Path.key("*viewerIdL", "*answerId").termVal(true))
                        .localTransform("$$viewerIdToReactedPromptIds",
                                    Path.key("*viewerIdL", "*promptId").termVal(true))
                        .localTransform("$$viewerIdToReactionByAnswerId",
                                    Path.key("*viewerIdL", "*answerId").termVal("*reactionValue"))
                        .hashPartition("*answerId")
                        .localSelect("$$answerIdToPublicPromptAnswer", Path.key("*answerId")).out("*answer")
                        .each((PublicPromptAnswer answer) -> {
                              if (answer == null || !answer.isSetSignalTokens())
                                    return new ArrayList<String>();
                              return answer.getSignalTokens();
                        }, "*answer").out("*tokens")
                        .each((Integer reactionValue) -> {
                              if (reactionValue != null && reactionValue.intValue() == PromptReaction.LIKE.getValue())
                                    return 1.0;
                              if (reactionValue != null
                                          && reactionValue.intValue() == PromptReaction.DISLIKE.getValue())
                                    return -1.0;
                              return 0.0;
                        }, "*reactionValue").out("*delta")
                        .each((Double delta, List<String> tokens) -> delta != null
                                    && delta.doubleValue() != 0.0
                                    && tokens != null
                                    && !tokens.isEmpty(),
                                    "*delta", "*tokens")
                        .out("*shouldUpdateTaste")
                        .hashPartition("*viewerIdL")
                        .ifTrue("*shouldUpdateTaste",
                                    Block.create()
                                                .each(Ops.EXPLODE, "*tokens").out("*token")
                                                .localSelect("$$viewerIdToTasteByToken",
                                                            Path.key("*viewerIdL", "*token").nullToVal(0.0))
                                                .out("*prevTaste")
                                                .each((Double prev, Double delta) -> {
                                                      double base = prev == null ? 0.0 : prev;
                                                      double inc = delta == null ? 0.0 : delta;
                                                      return base + inc;
                                                }, "*prevTaste", "*delta")
                                                .out("*nextTaste")
                                                .localTransform("$$viewerIdToTasteByToken",
                                                            Path.key("*viewerIdL", "*token").termVal("*nextTaste")));

            stream.source("*publicPromptSelectionDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .localTransform("$$accountIdToPublicPromptSelection",
                                    Path.key("*aidL").termVal("*data"));
      }

      private static void declareMatchesSignalsTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("signals");

            stream.pstate("$$accountIdToSignals", PState.mapSchema(Long.class, Signals.class));

            stream.source("*signalsDepot")
                        .out("*data")
                        .macro(extractFields("*data", "*accountId"))
                        .localTransform("$$accountIdToSignals",
                                    Path.key("*accountId").termVal("*data"));
      }

      private static void declareMatchesServeAndCursorTopology(Topologies topologies) {
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
                        .hashPartition("*aidL")
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
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToCursor",
                                    Path.key("*aidL", "lastIndex").termVal("*lastIndexI"))
                        .localTransform("$$accountIdToCursor",
                                    Path.key("*aidL", "wrappedOnce").termVal("*wrappedOnce"));
      }

      private static void declareMatchesRefillTopology(Topologies topologies) {
            StreamTopology stream = topologies.stream("refill");

            // viewer -> sorted heap (List<MatchCandidate>)
            stream.pstate("$$accountIdToCandidateHeap",
                        PState.mapSchema(Long.class, List.class));
            stream.pstate("$$accountIdToRefillPending",
                        PState.mapSchema(Long.class, Boolean.class));
            stream.pstate("$$accountIdToLastRefillAt",
                        PState.mapSchema(Long.class, Long.class));

            stream.source("*matchRefillDepot").out("*data")
                        .macro(extractFields("*data", "*accountId", "*targetSize"))
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*aidL")
                        .hashPartition("*aidL")
                        .localSelect("$$accountIdToRefillPending", Path.key("*aidL").nullToVal(false))
                        .out("*isPending")
                        .each((Boolean pending) -> pending == null || !pending, "*isPending")
                        .out("*shouldProcess")
                        .ifTrue("*shouldProcess",
                                    Block.create()
                                                .localTransform("$$accountIdToRefillPending",
                                                            Path.key("*aidL").termVal(true))
                                                // Load viewer filters and exposures once
                                                .localSelect("$$accountIdToFiltersProjection",
                                                            Path.key("*aidL"))
                                                .out("*viewerFilters")
                                                .localSelect("$$accountIdToExposure", Path.key("*aidL"))
                                                .out("*exposures")
                                                .each((Long aid) -> ALL_ACCOUNTS_KEY, "*aidL").out("*allKey")
                                                .hashPartition("*allKey")
                                                // Iterate over all known accountIds:
                                                .localSelect("$$allAccountIdsGlobal", Path.key("*allKey"))
                                                .out("*allIdsRaw")
                                                .each((Map<?, ?> ids) -> ids == null ? new HashMap<>() : ids, "*allIdsRaw")
                                                .out("*allIds")
                                                .each(Ops.EXPLODE, "*allIds").out("*entry")
                                                // entry is a MapEntry; we just want the key
                                                // (accountId)
                                                .each((Object e) -> {
                                                      if (e instanceof java.util.Map.Entry) {
                                                            return ((java.util.Map.Entry<?, ?>) e)
                                                                        .getKey();
                                                      }
                                                      return null;
                                                }, "*entry").out("*tidObj")
                                                .each((Object n) -> (n instanceof Number)
                                                            ? ((Number) n).longValue()
                                                            : 0L,
                                                            "*tidObj")
                                                .out("*tidL")

                                                // Skip self (viewer == target)
                                                .each((Long vid, Long tid) -> vid != null
                                                            && tid != null
                                                            && !Objects.equals(vid, tid),
                                                            "*aidL", "*tidL")
                                                .out("*isOther")

                                                .ifTrue("*isOther",
                                                            Block.create()
                                                                        .hashPartition("*tidL")
                                                                        // For each targetId,
                                                                        // load its Filters
                                                                        .localSelect(
                                                                                    "$$accountIdToFiltersProjection",
                                                                                    Path.key(
                                                                                                "*tidL"))
                                                                        .out("*targetFilters")

                                                                        // Cast
                                                                        // viewer/target
                                                                        // filters and
                                                                        // exposures cleanly
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
                                                                              if (viewer == null
                                                                                          || target == null) {
                                                                                    return null;
                                                                              }

                                                                              long now = System
                                                                                          .currentTimeMillis();

                                                                              double baseScore = CalypsoHelpers
                                                                                          .computeMatchesBaseScore(
                                                                                                      viewer,
                                                                                                      target);
                                                                              if (baseScore < 0.0) {
                                                                                    return null; // incompatible
                                                                                                 // on
                                                                                                 // hard
                                                                                                 // constraints
                                                                              }

                                                                              // Soft bonuses:
                                                                              // lifestyle +
                                                                              // politics +
                                                                              // religion
                                                                              double lifestyleBonus = CalypsoHelpers
                                                                                          .computeLifestyleBonus(
                                                                                                      viewer,
                                                                                                      target);
                                                                              double politicsBonus = CalypsoHelpers
                                                                                          .computePoliticsBonus(
                                                                                                      viewer,
                                                                                                      target);
                                                                              double religionBonus = CalypsoHelpers
                                                                                          .computeReligionBonus(
                                                                                                      viewer,
                                                                                                      target);
                                                                              double finalScore = baseScore
                                                                                          + lifestyleBonus
                                                                                          + politicsBonus
                                                                                          + religionBonus;

                                                                              // Relationship
                                                                              // mode floor
                                                                              // applies to
                                                                              // final score
                                                                              String viewerMode = CalypsoHelpers
                                                                                          .getModeSelfOrNull(
                                                                                                      viewer);
                                                                              double floor;
                                                                              if ("focused".equalsIgnoreCase(viewerMode)) {
                                                                                    floor = MIN_SCORE_FOCUSED;
                                                                              } else if ("exploratory".equalsIgnoreCase(viewerMode)) {
                                                                                    floor = MIN_SCORE_EXPLORATORY;
                                                                              } else {
                                                                                    floor = MIN_SCORE_BALANCED;
                                                                              }
                                                                              if (finalScore < floor) {
                                                                                    return null;
                                                                              }

                                                                              return mkCandidate(
                                                                                          tid,
                                                                                          finalScore,
                                                                                          now);
                                                                        }, "*viewerFiltersC",
                                                                                    "*tidL",
                                                                                    "*targetFiltersC")
                                                                        .out("*candMaybe")
                                                                        // Read current heap,
                                                                        // defaulting to empty
                                                                        // list
                                                                        .hashPartition("*aidL")
                                                                        .localSelect(
                                                                                    "$$accountIdToCandidateHeap",
                                                                                    Path.key(
                                                                                                "*aidL"))
                                                                        .out("*heapRaw")
                                                                        .each((Object hObj) -> {
                                                                              if (hObj == null)
                                                                                    return new ArrayList<MatchCandidate>();
                                                                              return (List<MatchCandidate>) hObj;
                                                                        }, "*heapRaw")
                                                                        .out("*currHeap")

                                                                        // Upsert candidate if
                                                                        // non-null; otherwise
                                                                        // keep heap as-is
                                                                        .each((List<MatchCandidate> heap,
                                                                                    MatchCandidate cand) -> {
                                                                              if (cand == null)
                                                                                    return heap;
                                                                              return upsertIntoHeap(
                                                                                          heap,
                                                                                          cand);
                                                                        },
                                                                                    "*currHeap",
                                                                                    "*candMaybe")
                                                                        .out("*newHeap")

                                                                        .localTransform(
                                                                                    "$$accountIdToCandidateHeap",
                                                                                    Path.key(
                                                                                                "*aidL")
                                                                                                .termVal(
                                                                                                            "*newHeap"))))
                        .each(() -> System.currentTimeMillis())
                        .out("*refillDoneTs")
                        .hashPartition("*aidL")
                        .localTransform("$$accountIdToLastRefillAt",
                                    Path.key("*aidL").termVal(
                                                "*refillDoneTs"))
                        .localTransform("$$accountIdToRefillPending",
                                    Path.key("*aidL").termVal(false));
      }

      private void declareQueries(Topologies topologies) {
            topologies
                        .query("getAccountsFromAccountIds", "*requestAccountId", "*accountIds")
                        .out("*results")
                        .each(Ops.EXPLODE_INDEXED, "*accountIds").out("*index", "*accountId")
                        .select("$$accountIdToAccount", Path.key("*accountId")).out("*account")
                        .each((RamaFunction2<Long, Account, AccountWithId>) AccountWithId::new,
                                    "*accountId", "*account")
                        .out("*accountWithId")
                        .each((RamaFunction2<Integer, AccountWithId, IndexedAccountWithId>) IndexedAccountWithId::new,
                                    "*index", "*accountWithId")
                        .out("*indexedAccountWithId")
                        .originPartition()
                        .agg(Agg.list("*indexedAccountWithId")).out("*unsortedResults")
                        .each((RamaFunction1<List<IndexedAccountWithId>, List<AccountWithId>>) unsorted -> {
                              if (unsorted == null || unsorted.isEmpty())
                                    return new ArrayList<>();
                              List<IndexedAccountWithId> sorted = new ArrayList<>(unsorted);
                              sorted.sort(Comparator.comparingLong(o -> o.index));
                              return sorted.stream()
                                          .map(o -> o.accountWithId)
                                          .collect(Collectors.toList());
                        }, "*unsortedResults").out("*results");

            topologies.query("getApplicationFromClientId", "*client_id").out("*result")
                                .hashPartition("*client_id")
                                .localSelect("$$clientIdToApplication", Path.key("*client_id"))
                                .out("*application")
                                .ifTrue(new Expr(Ops.IS_NULL, "*application"),
                                                Block.each(() -> null).out("*result"),
                                                Block.each(Ops.IDENTITY, "*application").out("*result"))
                                .originPartition();

            topologies.query("getPublicPromptAnswerById", "*answerId").out("*answer")
                        .hashPartition("*answerId")
                        .localSelect("$$answerIdToPublicPromptAnswer", Path.key("*answerId")).out("*answer")
                        .originPartition();

            topologies.query("getPublicPromptSelection", "*requesterId", "*accountId").out("*selection")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToPublicPromptSelection", Path.key("*accountIdL"))
                        .out("*selection")
                        .originPartition();

            topologies.query("getMyPublicPromptAnswers", "*requesterId", "*accountId").out("*answers")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToPublicAnswerIdByPromptId", Path.key("*accountIdL"))
                        .out("*promptToAnswer")
                        .each((Map<?, ?> promptToAnswer) -> {
                              if (promptToAnswer == null || promptToAnswer.isEmpty())
                                    return new ArrayList<>();
                              return new ArrayList<>(promptToAnswer.values());
                        }, "*promptToAnswer").out("*answerIdObjs")
                        .each(Ops.EXPLODE, "*answerIdObjs").out("*answerIdObj")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*answerIdObj")
                        .out("*answerId")
                        .hashPartition("*answerId")
                        .localSelect("$$answerIdToPublicPromptAnswer",
                                    Path.key("*answerId"))
                        .out("*answer")
                        .each((PublicPromptAnswer ans) -> {
                              if (ans == null)
                                    return null;
                              if (ans.isSetDeleted() && ans.isDeleted())
                                    return null;
                              return ans;
                        }, "*answer").out("*candidate")
                        .originPartition()
                        .agg(Agg.list("*candidate")).out("*candidates")
                        .each((List<PublicPromptAnswer> candidates) -> {
                              if (candidates == null || candidates.isEmpty())
                                    return new ArrayList<>();
                              List<PublicPromptAnswer> filtered = new ArrayList<>();
                              for (PublicPromptAnswer candidate : candidates) {
                                    if (candidate != null)
                                          filtered.add(candidate);
                              }
                              if (filtered.isEmpty())
                                    return new ArrayList<>();
                              List<PublicPromptAnswer> sorted = new ArrayList<>(filtered);
                              sorted.sort((a, b) -> {
                                    long at = a == null ? 0L : (a.isSetUpdatedAt() ? a.getUpdatedAt() : a.getCreatedAt());
                                    long bt = b == null ? 0L : (b.isSetUpdatedAt() ? b.getUpdatedAt() : b.getCreatedAt());
                                    return Long.compare(bt, at);
                              });
                              return sorted;
                        }, "*candidates").out("*answers");

            topologies.query("getPublicPromptFeed", "*viewerId", "*limit").out("*results")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                        .hashPartition("*viewerIdL")
                        .localSelect("$$accountIdToFiltersProjection", Path.key("*viewerIdL")).out("*viewerFilters")
                        .localSelect("$$viewerIdToReactedAnswerIds", Path.key("*viewerIdL"))
                        .out("*reactedAnswerIdsRaw")
                        .each((Map<?, ?> reacted) -> reacted == null ? new HashMap<>() : reacted,
                                    "*reactedAnswerIdsRaw")
                        .out("*reactedAnswerIds")
                        .localSelect("$$viewerIdToReactedPromptIds", Path.key("*viewerIdL"))
                        .out("*reactedPromptIdsRaw")
                        .each((Map<?, ?> reacted) -> reacted == null ? new HashMap<>() : reacted,
                                    "*reactedPromptIdsRaw")
                        .out("*reactedPromptIds")
                        .localSelect("$$viewerIdToTasteByToken", Path.key("*viewerIdL"))
                        .out("*tasteMapRaw")
                        .each((Map<?, ?> taste) -> taste == null ? new HashMap<>() : taste,
                                    "*tasteMapRaw")
                        .out("*tasteMap")
                        .each((Long vid) -> ALL_ACCOUNTS_KEY, "*viewerIdL").out("*allKey")
                        .hashPartition("*allKey")
                        .localSelect("$$allAccountIdsGlobal", Path.key("*allKey")).out("*allIdsRaw")
                        .each((Map<?, ?> ids) -> ids == null ? new HashMap<>() : ids, "*allIdsRaw")
                        .out("*allIds")
                        .each(Ops.EXPLODE, "*allIds").out("*entry")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e).getKey();
                              }
                              return null;
                        }, "*entry").out("*targetIdObj")
                        .each((Object n) -> (n instanceof Number) ? ((Number) n).longValue() : 0L,
                                    "*targetIdObj")
                        .out("*targetIdL")
                        .each((Long vid, Long tid) -> tid != null && !Objects.equals(vid, tid),
                                    "*viewerIdL", "*targetIdL")
                        .out("*isOther")
                        .hashPartition("*targetIdL")
                        .localSelect("$$accountIdToFiltersProjection",
                                    Path.key("*targetIdL"))
                        .out("*targetFilters")
                        .each((Filters v, Filters t) -> {
                              if (v == null || t == null)
                                    return null;
                              return CalypsoHelpers.computeMatchesBaseScore(v, t);
                        }, "*viewerFilters", "*targetFilters")
                        .out("*score")
                        .each((Double s) -> s != null && s >= 0.0, "*score")
                        .out("*isCompatible")
                        .localSelect(
                                    "$$accountIdToPublicAnswerIdByPromptId",
                                    Path.key("*targetIdL"))
                        .out("*promptToAnswer")
                        .each((Map<?, ?> promptToAnswer) -> promptToAnswer == null
                                    ? new HashMap<>()
                                    : promptToAnswer,
                                    "*promptToAnswer")
                        .out("*promptToAnswerSafe")
                        .each(Ops.EXPLODE, "*promptToAnswerSafe")
                        .out("*promptEntry")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e)
                                                .getKey();
                              }
                              return null;
                        }, "*promptEntry")
                        .out("*promptIdObj")
                        .each((Object e) -> {
                              if (e instanceof Map.Entry) {
                                    return ((Map.Entry<?, ?>) e)
                                                .getValue();
                              }
                              return null;
                        }, "*promptEntry")
                        .out("*answerIdObj")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*promptIdObj")
                        .out("*promptId")
                        .each((Object s) -> s == null ? null : s.toString(),
                                    "*answerIdObj")
                        .out("*answerId")
                        .each((Map<?, ?> reacted, String pid) -> reacted != null
                                    && pid != null
                                    && reacted.containsKey(pid),
                                    "*reactedPromptIds",
                                    "*promptId")
                        .out("*promptAlreadyReacted")
                        .each((Map<?, ?> reacted, String aid) -> reacted != null
                                    && aid != null
                                    && reacted.containsKey(aid),
                                    "*reactedAnswerIds",
                                    "*answerId")
                        .out("*answerAlreadyReacted")
                        .each((Boolean a, Boolean b) -> (a != null && a)
                                    || (b != null && b),
                                    "*promptAlreadyReacted",
                                    "*answerAlreadyReacted")
                        .out("*shouldSkip")
                        .hashPartition("*answerId")
                        .localSelect(
                                    "$$answerIdToPublicPromptAnswer",
                                    Path.key(
                                                "*answerId"))
                        .out("*answer")
                        .each((Boolean isOther,
                                    Boolean isCompatible,
                                    Boolean shouldSkip,
                                    PublicPromptAnswer ans,
                                    Map<String, Double> taste) -> {
                              if (isOther == null || !isOther)
                                    return null;
                              if (isCompatible == null || !isCompatible)
                                    return null;
                              if (shouldSkip != null && shouldSkip)
                                    return null;
                              if (ans == null)
                                    return null;
                              if (ans.isSetDeleted() && ans.isDeleted())
                                    return null;
                              double score = 0.0;
                              if (taste != null && ans.isSetSignalTokens()) {
                                    for (String token : ans.getSignalTokens()) {
                                          if (token == null)
                                                continue;
                                          Double val = taste.get(token);
                                          if (val != null)
                                                score += val;
                                    }
                              }
                              ArrayList<Object> candidate = new ArrayList<>(2);
                              candidate.add(ans);
                              candidate.add(score);
                              return candidate;
                        }, "*isOther", "*isCompatible", "*shouldSkip", "*answer", "*tasteMap")
                        .out("*candidate")
                        .originPartition()
                        .agg(Agg.list("*candidate")).out("*candidates")
                        .each((List<List<Object>> candidates, Object limitObj) -> {
                              if (candidates == null || candidates.isEmpty())
                                    return new ArrayList<>();
                              List<List<Object>> filtered = new ArrayList<>();
                              for (List<Object> candidate : candidates) {
                                    if (candidate == null || candidate.size() < 2)
                                          continue;
                                    if (!(candidate.get(0) instanceof PublicPromptAnswer))
                                          continue;
                                    filtered.add(candidate);
                              }
                              if (filtered.isEmpty())
                                    return new ArrayList<>();
                              int lim = 1;
                              if (limitObj instanceof Number) {
                                    int val = ((Number) limitObj).intValue();
                                    if (val < 1)
                                          lim = 1;
                                    else if (val > 50)
                                          lim = 50;
                                    else
                                          lim = val;
                              }
                              List<List<Object>> sorted = new ArrayList<>(filtered);
                              sorted.sort((a, b) -> {
                                    double as = (a != null && a.size() > 1 && a.get(1) instanceof Number)
                                                ? ((Number) a.get(1)).doubleValue()
                                                : 0.0;
                                    double bs = (b != null && b.size() > 1 && b.get(1) instanceof Number)
                                                ? ((Number) b.get(1)).doubleValue()
                                                : 0.0;
                                    int cmp = Double.compare(bs, as);
                                    if (cmp != 0)
                                          return cmp;
                                    PublicPromptAnswer aAns = (a != null
                                                && !a.isEmpty()
                                                && a.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) a.get(0)
                                                            : null;
                                    PublicPromptAnswer bAns = (b != null
                                                && !b.isEmpty()
                                                && b.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) b.get(0)
                                                            : null;
                                    long at = aAns == null
                                                ? 0L
                                                : (aAns.isSetUpdatedAt() ? aAns.getUpdatedAt()
                                                            : aAns.getCreatedAt());
                                    long bt = bAns == null
                                                ? 0L
                                                : (bAns.isSetUpdatedAt() ? bAns.getUpdatedAt()
                                                            : bAns.getCreatedAt());
                                    return Long.compare(bt, at);
                              });
                              LinkedHashMap<String, PublicPromptAnswer> deduped = new LinkedHashMap<>();
                              for (List<Object> candidate : sorted) {
                                    PublicPromptAnswer ans = (candidate != null
                                                && !candidate.isEmpty()
                                                && candidate.get(0) instanceof PublicPromptAnswer)
                                                            ? (PublicPromptAnswer) candidate.get(0)
                                                            : null;
                                    if (ans == null || ans.getPromptId() == null)
                                          continue;
                                    if (!deduped.containsKey(ans.getPromptId())) {
                                          deduped.put(ans.getPromptId(), ans);
                                    }
                                    if (deduped.size() >= lim)
                                          break;
                              }
                              return new ArrayList<>(deduped.values());
                        }, "*candidates", "*limit").out("*results");

            topologies.query("getFiltersFromAccountId", "*requesterId", "*accountId").out("*filters")
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*accountId").out("*accountIdL")
                        .hashPartition("*accountIdL")
                        .localSelect("$$accountIdToFiltersProjection", Path.key("*accountIdL"))
                        .out("*filtersRaw")
                        .originPartition()
                        .each((Filters f) -> f, "*filtersRaw").out("*filters");

            topologies.query("getMatchesFromAccountId", "*viewerId", "*startIdx", "*limit").out("*results")
                        // Normalize viewer id to Long before partitioning/reads
                        .each((Number n) -> n == null ? 0L : n.longValue(), "*viewerId").out("*viewerIdL")
                        .hashPartition("*viewerIdL")
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
                        .hashPartition("*viewerIdL")
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

            topologies.query("getSignalsFromAccountId", "*requestAccountId", "*accountId").out("*signals")
                        .hashPartition("*accountId")
                        .localSelect("$$accountIdToSignals", Path.key("*accountId")).out("*signals")
                        .originPartition();
      }

      @Override
      public void define(Setup setup, Topologies topologies) {
            setup.declareDepot("*accountDepot", Depot.hashBy(CalypsoHelpers.ExtractPhoneNumber.class));
            setup.declareDepot("*accountWithIdDepot", Depot.disallow());
            setup.declareDepot("*applicationDepot", Depot.hashBy(CalypsoHelpers.ExtractClientId.class));
            setup.declareDepot("*authCodeDepot", Depot.hashBy(ExtractCode.class));
            setup.declareDepot("*filtersDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchRefillDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchesServeDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*matchesCursorAckDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*signalsDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*publicPromptAnswerDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
            setup.declareDepot("*publicPromptReactionDepot",
                        Depot.hashBy(CalypsoHelpers.ExtractViewerAccountId.class));
            setup.declareDepot("*publicPromptSelectionDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

            declareAccountsTopology(topologies);
            declareApplicationTopology(topologies);
            declareAuthTopology(topologies);
            declareFiltersTopology(topologies);
            declareMatchesServeAndCursorTopology(topologies);
            declareMatchesRefillTopology(topologies);
            declareMatchesSignalsTopology(topologies);
            declarePublicPromptsTopology(topologies);

            declareQueries(topologies);
      }

}
