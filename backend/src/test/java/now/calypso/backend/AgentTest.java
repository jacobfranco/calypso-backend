package now.calypso.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.rpl.rama.Depot;
import com.rpl.rama.QueryTopologyClient;
import com.rpl.rama.test.InProcessCluster;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;
import now.calypso.backend.data.AgentSession;
import now.calypso.backend.data.AgentSessionStatus;
import now.calypso.backend.data.PrivatePromptAnswer;
import now.calypso.backend.data.PrivatePromptAssignment;
import now.calypso.backend.data.PrivatePromptStatus;
import now.calypso.backend.modules.Agent;
import now.calypso.backend.serialization.CalypsoSerialization;

public class AgentTest {

        @Test
        public void agentSessionPersistsAndQueries(TestInfo testInfo) throws Exception {
                List<Class> serializations = Collections.singletonList(CalypsoSerialization.class);

                try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
                        Agent agentModule = new Agent();
                        TestHelpers.launchModule(ipc, agentModule, testInfo);
                        String moduleName = agentModule.getClass().getName();

                        Depot agentDepot = ipc.clusterDepot(moduleName, "*agentSessionDepot");
                        QueryTopologyClient<AgentSession> getSession = ipc.clusterQuery(moduleName,
                                        "getAgentSessionFromAccountId");

                        long accountId = 901L;
                        AgentSession session = new AgentSession();
                        session.setSessionId("session-901");
                        session.setAccountId(accountId);
                        session.setCreatedAt(123L);
                        session.setLastInteractionAt(456L);
                        session.setStatus(AgentSessionStatus.ACTIVE);

                        AgentMessage msg = new AgentMessage();
                        msg.setMessageId("msg-1");
                        msg.setSessionId("session-901");
                        msg.setSender(AgentMessageSender.USER);
                        msg.setText("Hello agent");
                        msg.setTimestamp(456L);

                        session.setMessages(List.of(msg));
                        agentDepot.append(session);

                        TestHelpers.attainConditionPred(
                                        () -> getSession.invoke(accountId, accountId),
                                        s -> s != null && s.isSetMessages() && !s.getMessages().isEmpty());

                        AgentSession stored = getSession.invoke(accountId, accountId);
                        assertNotNull(stored);
                        assertEquals("session-901", stored.getSessionId());
                        assertEquals(accountId, stored.getAccountId());
                        assertEquals(AgentSessionStatus.ACTIVE, stored.getStatus());
                        assertTrue(stored.isSetMessages());
                        AgentMessage storedMsg = stored.getMessages().get(0);
                        assertEquals("msg-1", storedMsg.getMessageId());
                        assertEquals(AgentMessageSender.USER, storedMsg.getSender());
                        assertEquals("Hello agent", storedMsg.getText());
                }
        }

        @Test
        public void privatePromptTopologyPersistsAssignmentAndAnswer(TestInfo testInfo) throws Exception {
                List<Class> serializations = Collections.singletonList(CalypsoSerialization.class);

                try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
                        Agent agentModule = new Agent();
                        TestHelpers.launchModule(ipc, agentModule, testInfo);
                        String moduleName = agentModule.getClass().getName();

                        Depot assignmentDepot = ipc.clusterDepot(moduleName, "*privatePromptAssignmentDepot");
                        Depot answerDepot = ipc.clusterDepot(moduleName, "*privatePromptAnswerDepot");

                        QueryTopologyClient<PrivatePromptAssignment> getAssignment = ipc.clusterQuery(moduleName,
                                        "getPrivatePromptAssignmentByInstanceId");
                        QueryTopologyClient<PrivatePromptAnswer> getAnswer = ipc.clusterQuery(moduleName,
                                        "getPrivatePromptAnswerByInstanceId");
                        QueryTopologyClient<PrivatePromptAssignment> getActive = ipc.clusterQuery(moduleName,
                                        "getActivePrivatePromptAssignment");
                        QueryTopologyClient<Map<String, Object>> getSchedulerState = ipc.clusterQuery(moduleName,
                                        "getPrivatePromptSchedulerState");

                        long accountId = 902L;
                        long now = System.currentTimeMillis();

                        PrivatePromptAssignment assignment = new PrivatePromptAssignment();
                        assignment.setInstanceId("pp-902");
                        assignment.setAccountId(accountId);
                        assignment.setPromptId("private.color.presence");
                        assignment.setScheduledAt(now);
                        assignment.setSurfacedAt(now);
                        assignment.setStatus(PrivatePromptStatus.ACTIVE);
                        assignmentDepot.append(assignment);

                        TestHelpers.attainConditionPred(
                                        () -> getAssignment.invoke("pp-902"),
                                        stored -> stored != null && stored.getStatus() == PrivatePromptStatus.ACTIVE);

                        PrivatePromptAssignment active = getActive.invoke(accountId, accountId);
                        assertNotNull(active);
                        assertEquals("pp-902", active.getInstanceId());
                        assertEquals(PrivatePromptStatus.ACTIVE, active.getStatus());

                        PrivatePromptAnswer answer = new PrivatePromptAnswer();
                        answer.setInstanceId("pp-902");
                        answer.setAccountId(accountId);
                        answer.setPromptId("private.color.presence");
                        answer.setBody("Blue, calm, electric.");
                        answer.setAnsweredAt(now + 1000L);
                        answer.setSignalTokens(List.of("color_blue_presence"));
                        answerDepot.append(answer);

                        TestHelpers.attainConditionPred(
                                        () -> getAnswer.invoke("pp-902"),
                                        stored -> stored != null && stored.isSetSignalTokens()
                                                        && stored.getSignalTokens().contains("color_blue_presence"));

                        Map<String, Object> schedulerState = getSchedulerState.invoke(accountId, accountId);
                        assertNotNull(schedulerState);
                        assertEquals("pp-902", schedulerState.get("activeInstanceId"));
                }
        }

        @Test
        public void privatePromptStatusTransitionsUpdateSchedulerState(TestInfo testInfo) throws Exception {
                List<Class> serializations = Collections.singletonList(CalypsoSerialization.class);

                try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
                        Agent agentModule = new Agent();
                        TestHelpers.launchModule(ipc, agentModule, testInfo);
                        String moduleName = agentModule.getClass().getName();

                        Depot assignmentDepot = ipc.clusterDepot(moduleName, "*privatePromptAssignmentDepot");
                        QueryTopologyClient<Map<String, Object>> getSchedulerState = ipc.clusterQuery(moduleName,
                                        "getPrivatePromptSchedulerState");
                        QueryTopologyClient<PrivatePromptAssignment> getActive = ipc.clusterQuery(moduleName,
                                        "getActivePrivatePromptAssignment");

                        long accountId = 903L;
                        long now = System.currentTimeMillis();
                        String promptId = "private.hobbies";

                        PrivatePromptAssignment active = new PrivatePromptAssignment();
                        active.setInstanceId("pp-903");
                        active.setAccountId(accountId);
                        active.setPromptId(promptId);
                        active.setScheduledAt(now);
                        active.setStatus(PrivatePromptStatus.ACTIVE);
                        assignmentDepot.append(active);

                        PrivatePromptAssignment snoozed = new PrivatePromptAssignment(active);
                        snoozed.setStatus(PrivatePromptStatus.SNOOZED);
                        snoozed.setSnoozeUntil(now + 3600_000L);
                        assignmentDepot.append(snoozed);

                        TestHelpers.attainConditionPred(
                                        () -> getSchedulerState.invoke(accountId, accountId),
                                        state -> {
                                                if (state == null)
                                                        return false;
                                                Object snoozedUntil = state.get("snoozedUntil");
                                                return snoozedUntil instanceof Number
                                                                && ((Number) snoozedUntil).longValue() > now;
                                        });

                        assertEquals(null, getActive.invoke(accountId, accountId));

                        PrivatePromptAssignment skipped = new PrivatePromptAssignment(snoozed);
                        skipped.setStatus(PrivatePromptStatus.SKIPPED);
                        skipped.setCompletedAt(now + 1000L);
                        skipped.unsetSnoozeUntil();
                        assignmentDepot.append(skipped);

                        TestHelpers.attainConditionPred(
                                        () -> getSchedulerState.invoke(accountId, accountId),
                                        state -> {
                                                if (state == null)
                                                        return false;
                                                Object activeInstanceId = state.get("activeInstanceId");
                                                if (activeInstanceId != null)
                                                        return false;
                                                Object skippedPromptIds = state.get("skippedPromptIds");
                                                if (!(skippedPromptIds instanceof List<?> list))
                                                        return false;
                                                return list.stream().anyMatch(v -> promptId.equals(String.valueOf(v)));
                                        });

                        Map<String, Object> state = getSchedulerState.invoke(accountId, accountId);
                        assertNotNull(state);
                        assertEquals(null, state.get("activeInstanceId"));
                        assertInstanceOf(List.class, state.get("skippedPromptIds"));
                        assertFalse(((List<?>) state.get("skippedPromptIds")).isEmpty());
                }
        }
}
