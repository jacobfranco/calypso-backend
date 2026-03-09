package now.calypso.backend.modules;

import com.rpl.rama.*;
import com.rpl.rama.module.*;

import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.AgentSession;
import now.calypso.backend.data.PrivatePromptAnswer;
import now.calypso.backend.data.PrivatePromptAssignment;
import now.calypso.backend.data.PrivatePromptStatus;

import static now.calypso.backend.CalypsoHelpers.extractFields;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Agent implements RamaModule {

        private static long normalizeAccountId(Number n) {
                return n == null ? 0L : n.longValue();
        }

        private static String computeNextActiveInstanceId(String currentActive, PrivatePromptAssignment assignment) {
                if (assignment == null || assignment.getInstanceId() == null)
                        return currentActive;
                PrivatePromptStatus status = assignment.getStatus();
                if (status == PrivatePromptStatus.ACTIVE || status == PrivatePromptStatus.SNOOZED) {
                        return assignment.getInstanceId();
                }
                if ((status == PrivatePromptStatus.ANSWERED || status == PrivatePromptStatus.SKIPPED)
                                && Objects.equals(currentActive, assignment.getInstanceId())) {
                        return null;
                }
                return currentActive;
        }

        private static boolean shouldAppendHistory(PrivatePromptStatus status) {
                return status == PrivatePromptStatus.ANSWERED
                                || status == PrivatePromptStatus.SKIPPED
                                || status == PrivatePromptStatus.SNOOZED;
        }

        private static List<String> appendIfMissing(List<?> history, String instanceId) {
                ArrayList<String> out = new ArrayList<>();
                if (history != null) {
                        for (Object o : history) {
                                if (o != null)
                                        out.add(o.toString());
                        }
                }
                if (instanceId != null && !out.contains(instanceId)) {
                        out.add(instanceId);
                }
                return out;
        }

        private static long resolveCompletedAt(PrivatePromptAssignment assignment, Number completedAtRaw) {
                if (assignment != null && assignment.isSetCompletedAt()) {
                        return assignment.getCompletedAt();
                }
                if (completedAtRaw != null) {
                        return completedAtRaw.longValue();
                }
                return System.currentTimeMillis();
        }

        private static long resolveScheduledAt(PrivatePromptAssignment assignment, Number scheduledAtRaw) {
                if (assignment != null && assignment.isSetScheduledAt()) {
                        return assignment.getScheduledAt();
                }
                if (scheduledAtRaw != null) {
                        return scheduledAtRaw.longValue();
                }
                return System.currentTimeMillis();
        }

        private static long resolveSnoozeUntil(PrivatePromptAssignment assignment, Number snoozeUntilRaw) {
                if (assignment != null && assignment.isSetSnoozeUntil()) {
                        return assignment.getSnoozeUntil();
                }
                if (snoozeUntilRaw != null) {
                        return snoozeUntilRaw.longValue();
                }
                return System.currentTimeMillis();
        }

        private static boolean isServableNow(PrivatePromptAssignment assignment, long now) {
                if (assignment == null || !assignment.isSetStatus() || assignment.getStatus() == null) {
                        return false;
                }
                if (assignment.getStatus() == PrivatePromptStatus.ACTIVE) {
                        return true;
                }
                if (assignment.getStatus() == PrivatePromptStatus.SNOOZED) {
                        long until = assignment.isSetSnoozeUntil() ? assignment.getSnoozeUntil() : 0L;
                        return until <= now;
                }
                return false;
        }

        private static Map<?, ?> asMap(Object raw) {
                return raw instanceof Map ? (Map<?, ?>) raw : new HashMap<>();
        }

        private static List<?> asList(Object raw) {
                return raw instanceof List ? (List<?>) raw : new ArrayList<>();
        }

        private static List<String> mapKeysToStrings(Map<?, ?> map) {
                ArrayList<String> out = new ArrayList<>();
                if (map == null || map.isEmpty())
                        return out;
                for (Object key : map.keySet()) {
                        if (key != null)
                                out.add(key.toString());
                }
                return out;
        }

        private static Map<String, Long> coerceSkippedAt(Map<?, ?> skippedAtRaw) {
                HashMap<String, Long> out = new HashMap<>();
                if (skippedAtRaw == null || skippedAtRaw.isEmpty()) {
                        return out;
                }
                for (Map.Entry<?, ?> e : skippedAtRaw.entrySet()) {
                        if (e == null || e.getKey() == null)
                                continue;
                        Object value = e.getValue();
                        if (value instanceof Number) {
                                out.put(e.getKey().toString(), ((Number) value).longValue());
                        }
                }
                return out;
        }

        private static Map<String, Object> buildSchedulerState(
                        String activeInstanceId,
                        Object answeredRaw,
                        Object skippedRaw,
                        Object skippedAtRaw,
                        Number lastScheduledAtRaw,
                        Number lastAnsweredAtRaw,
                        Number snoozedUntilRaw,
                        Object historyRaw) {
                HashMap<String, Object> out = new HashMap<>();
                out.put("activeInstanceId", activeInstanceId);
                out.put("answeredPromptIds", mapKeysToStrings(asMap(answeredRaw)));
                out.put("skippedPromptIds", mapKeysToStrings(asMap(skippedRaw)));
                out.put("skippedPromptIdToLastSkippedAt", coerceSkippedAt(asMap(skippedAtRaw)));
                out.put("lastScheduledAt", lastScheduledAtRaw == null ? null : lastScheduledAtRaw.longValue());
                out.put("lastAnsweredAt", lastAnsweredAtRaw == null ? null : lastAnsweredAtRaw.longValue());
                out.put("snoozedUntil", snoozedUntilRaw == null ? null : snoozedUntilRaw.longValue());
                out.put("history", appendIfMissing(asList(historyRaw), null));
                return out;
        }

        private static void declareSessionsTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("sessions");

                stream.pstate("$$accountIdToAgentSession", PState.mapSchema(Long.class, AgentSession.class));

                stream.source("*agentSessionDepot")
                                .out("*data")
                                .macro(extractFields("*data", "*accountId"))
                                .localTransform("$$accountIdToAgentSession",
                                                Path.key("*accountId").termVal("*data"));
        }

        private static void declarePrivatePromptTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("privatePrompts");

                stream.pstate("$$instanceIdToPrivatePromptAssignment",
                                PState.mapSchema(String.class, PrivatePromptAssignment.class));
                stream.pstate("$$accountIdToActivePrivatePromptInstanceId",
                                PState.mapSchema(Long.class, String.class));
                stream.pstate("$$accountIdToPrivatePromptHistory",
                                PState.mapSchema(Long.class, List.class));
                stream.pstate("$$instanceIdToPrivatePromptAnswer",
                                PState.mapSchema(String.class, PrivatePromptAnswer.class));
                stream.pstate("$$accountIdToAnsweredPrivatePromptIds",
                                PState.mapSchema(Long.class, Map.class));
                stream.pstate("$$accountIdToSkippedPrivatePromptIds",
                                PState.mapSchema(Long.class, Map.class));
                stream.pstate("$$accountIdToPromptIdToLastSkippedAt",
                                PState.mapSchema(Long.class, Map.class));
                stream.pstate("$$accountIdToLastPrivatePromptAt",
                                PState.mapSchema(Long.class, Long.class));
                stream.pstate("$$accountIdToLastPrivatePromptScheduledAt",
                                PState.mapSchema(Long.class, Long.class));
                stream.pstate("$$accountIdToLastAnsweredPrivatePromptAt",
                                PState.mapSchema(Long.class, Long.class));
                stream.pstate("$$accountIdToSnoozedUntil",
                                PState.mapSchema(Long.class, Long.class));

                stream.source("*privatePromptAssignmentDepot")
                                .out("*assignment")
                                .macro(extractFields("*assignment", "*accountId", "*instanceId", "*promptId",
                                                "*completedAt", "*snoozeUntil", "*scheduledAt"))
                                .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                                .hashPartition("*instanceId")
                                .localTransform("$$instanceIdToPrivatePromptAssignment",
                                                Path.key("*instanceId").termVal("*assignment"))
                                .hashPartition("*accountIdL")
                                .each((PrivatePromptAssignment assignment, Number scheduledAtRaw) -> resolveScheduledAt(
                                                assignment, scheduledAtRaw), "*assignment", "*scheduledAt")
                                .out("*lastScheduledAt")
                                .localTransform("$$accountIdToLastPrivatePromptScheduledAt",
                                                Path.key("*accountIdL").termVal("*lastScheduledAt"))
                                .localSelect("$$accountIdToActivePrivatePromptInstanceId", Path.key("*accountIdL"))
                                .out("*currentActive")
                                .each((String current, PrivatePromptAssignment assignment) -> computeNextActiveInstanceId(
                                                current, assignment), "*currentActive", "*assignment")
                                .out("*nextActive")
                                .each((String nextActive) -> nextActive == null, "*nextActive").out("*clearActive")
                                .ifTrue("*clearActive",
                                                Block.localTransform("$$accountIdToActivePrivatePromptInstanceId",
                                                                Path.key("*accountIdL").termVoid()),
                                                Block.localTransform("$$accountIdToActivePrivatePromptInstanceId",
                                                                Path.key("*accountIdL").termVal("*nextActive")))
                                .each((PrivatePromptAssignment assignment) -> shouldAppendHistory(
                                                assignment == null ? null : assignment.getStatus()), "*assignment")
                                .out("*appendHistory")
                                .ifTrue("*appendHistory",
                                                Block.localSelect("$$accountIdToPrivatePromptHistory",
                                                                Path.key("*accountIdL")).out("*historyRaw")
                                                                .each((Object history, String instanceId) -> appendIfMissing(
                                                                                asList(history), instanceId),
                                                                                "*historyRaw", "*instanceId")
                                                                .out("*nextHistory")
                                                                .localTransform("$$accountIdToPrivatePromptHistory",
                                                                                Path.key("*accountIdL")
                                                                                                .termVal("*nextHistory")))
                                .each((PrivatePromptAssignment assignment, String promptId) -> assignment != null
                                                && assignment.getStatus() == PrivatePromptStatus.ANSWERED
                                                && promptId != null, "*assignment", "*promptId")
                                .out("*isAnswered")
                                .ifTrue("*isAnswered",
                                                Block.localTransform("$$accountIdToAnsweredPrivatePromptIds",
                                                                Path.key("*accountIdL", "*promptId").termVal(true))
                                                                .each((PrivatePromptAssignment assignment,
                                                                                Number completedAtRaw) -> resolveCompletedAt(
                                                                                                assignment,
                                                                                                completedAtRaw),
                                                                                "*assignment", "*completedAt")
                                                                .out("*lastPromptAt")
                                                                .localTransform("$$accountIdToLastPrivatePromptAt",
                                                                                Path.key("*accountIdL")
                                                                                                .termVal("*lastPromptAt"))
                                                                .localTransform("$$accountIdToLastAnsweredPrivatePromptAt",
                                                                                Path.key("*accountIdL")
                                                                                                .termVal("*lastPromptAt"))
                                                                .localTransform("$$accountIdToSnoozedUntil",
                                                                                Path.key("*accountIdL")
                                                                                                .termVoid()))
                                .each((PrivatePromptAssignment assignment, String promptId) -> assignment != null
                                                && assignment.getStatus() == PrivatePromptStatus.SKIPPED
                                                && promptId != null, "*assignment", "*promptId")
                                .out("*isSkipped")
                                .ifTrue("*isSkipped",
                                                Block.localTransform("$$accountIdToSkippedPrivatePromptIds",
                                                                Path.key("*accountIdL", "*promptId").termVal(true))
                                                                .each((PrivatePromptAssignment assignment,
                                                                                Number completedAtRaw) -> resolveCompletedAt(
                                                                                                assignment,
                                                                                                completedAtRaw),
                                                                                "*assignment", "*completedAt")
                                                                .out("*skippedAt")
                                                                .localTransform("$$accountIdToPromptIdToLastSkippedAt",
                                                                                Path.key("*accountIdL", "*promptId")
                                                                                                .termVal("*skippedAt"))
                                                                .localTransform("$$accountIdToLastPrivatePromptAt",
                                                                                Path.key("*accountIdL")
                                                                                                .termVal("*skippedAt"))
                                                                .localTransform("$$accountIdToSnoozedUntil",
                                                                                Path.key("*accountIdL")
                                                                                                .termVoid()))
                                .each((PrivatePromptAssignment assignment) -> assignment != null
                                                && assignment.getStatus() == PrivatePromptStatus.SNOOZED, "*assignment")
                                .out("*isSnoozed")
                                .ifTrue("*isSnoozed",
                                                Block.each((PrivatePromptAssignment assignment, Number snoozeUntilRaw) -> resolveSnoozeUntil(
                                                                assignment, snoozeUntilRaw), "*assignment", "*snoozeUntil")
                                                                .out("*effectiveSnoozeUntil")
                                                                .localTransform("$$accountIdToSnoozedUntil",
                                                                                Path.key("*accountIdL")
                                                                                                .termVal("*effectiveSnoozeUntil")))
                                .each((PrivatePromptAssignment assignment) -> assignment != null
                                                && assignment.getStatus() == PrivatePromptStatus.ACTIVE, "*assignment")
                                .out("*isActive")
                                .ifTrue("*isActive",
                                                Block.localTransform("$$accountIdToSnoozedUntil",
                                                                Path.key("*accountIdL").termVoid()));

                stream.source("*privatePromptAnswerDepot")
                                .out("*answer")
                                .macro(extractFields("*answer", "*instanceId"))
                                .hashPartition("*instanceId")
                                .localTransform("$$instanceIdToPrivatePromptAnswer",
                                                Path.key("*instanceId").termVal("*answer"));
        }

        private static void declareQueries(Topologies topologies) {
                topologies.query("getAgentSessionFromAccountId", "*requestAccountId", "*accountId").out("*session")
                                .hashPartition("*accountId")
                                .localSelect("$$accountIdToAgentSession", Path.key("*accountId")).out("*session")
                                .originPartition();

                topologies.query("getPrivatePromptAssignmentByInstanceId", "*instanceId").out("*assignment")
                                .hashPartition("*instanceId")
                                .localSelect("$$instanceIdToPrivatePromptAssignment", Path.key("*instanceId"))
                                .out("*assignment")
                                .originPartition();

                topologies.query("getPrivatePromptAnswerByInstanceId", "*instanceId").out("*answer")
                                .hashPartition("*instanceId")
                                .localSelect("$$instanceIdToPrivatePromptAnswer", Path.key("*instanceId"))
                                .out("*answer")
                                .originPartition();

                topologies.query("getPrivatePromptSchedulerState", "*requesterId", "*accountId").out("*state")
                                .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                                .hashPartition("*accountIdL")
                                .localSelect("$$accountIdToActivePrivatePromptInstanceId", Path.key("*accountIdL"))
                                .out("*activeInstanceId")
                                .localSelect("$$accountIdToAnsweredPrivatePromptIds", Path.key("*accountIdL"))
                                .out("*answeredRaw")
                                .localSelect("$$accountIdToSkippedPrivatePromptIds", Path.key("*accountIdL"))
                                .out("*skippedRaw")
                                .localSelect("$$accountIdToPromptIdToLastSkippedAt", Path.key("*accountIdL"))
                                .out("*skippedAtRaw")
                                .localSelect("$$accountIdToLastPrivatePromptScheduledAt", Path.key("*accountIdL"))
                                .out("*lastScheduledAtRaw")
                                .localSelect("$$accountIdToLastAnsweredPrivatePromptAt", Path.key("*accountIdL"))
                                .out("*lastAnsweredAtRaw")
                                .localSelect("$$accountIdToSnoozedUntil", Path.key("*accountIdL"))
                                .out("*snoozedUntilRaw")
                                .localSelect("$$accountIdToPrivatePromptHistory", Path.key("*accountIdL"))
                                .out("*historyRaw")
                                .each((String activeInstanceId,
                                                Object answeredRaw,
                                                Object skippedRaw,
                                                Object skippedAtRaw,
                                                Number lastScheduledAtRaw,
                                                Number lastAnsweredAtRaw,
                                                Number snoozedUntilRaw,
                                                Object historyRaw) -> buildSchedulerState(activeInstanceId, answeredRaw,
                                                                skippedRaw, skippedAtRaw,
                                                                lastScheduledAtRaw, lastAnsweredAtRaw, snoozedUntilRaw,
                                                                historyRaw), "*activeInstanceId", "*answeredRaw", "*skippedRaw",
                                                "*skippedAtRaw", "*lastScheduledAtRaw",
                                                "*lastAnsweredAtRaw", "*snoozedUntilRaw", "*historyRaw")
                                .out("*state")
                                .originPartition();

                topologies.query("getActivePrivatePromptAssignment", "*requesterId", "*accountId").out("*assignment")
                                .each((Number n) -> normalizeAccountId(n), "*accountId").out("*accountIdL")
                                .hashPartition("*accountIdL")
                                .localSelect("$$accountIdToActivePrivatePromptInstanceId", Path.key("*accountIdL"))
                                .out("*instanceId")
                                .each((String instanceId) -> instanceId != null, "*instanceId").out("*hasInstanceId")
                                .ifTrue("*hasInstanceId",
                                                Block.hashPartition("*instanceId")
                                                                .localSelect("$$instanceIdToPrivatePromptAssignment",
                                                                                Path.key("*instanceId"))
                                                                .out("*assignmentRaw")
                                                                .each((PrivatePromptAssignment assignment) -> {
                                                                        if (!isServableNow(assignment,
                                                                                        System.currentTimeMillis())) {
                                                                                return null;
                                                                        }
                                                                        return assignment;
                                                                }, "*assignmentRaw").out("*assignment"),
                                                Block.each(() -> null).out("*assignment"))
                                .originPartition();
        }

        @Override
        public void define(Setup setup, Topologies topologies) {
                setup.declareDepot("*agentSessionDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
                setup.declareDepot("*privatePromptAssignmentDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));
                setup.declareDepot("*privatePromptAnswerDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

                declareSessionsTopology(topologies);
                declarePrivatePromptTopology(topologies);
                declareQueries(topologies);
        }

}
