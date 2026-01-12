package now.calypso.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.rpl.rama.Depot;
import com.rpl.rama.QueryTopologyClient;
import com.rpl.rama.test.InProcessCluster;

import now.calypso.backend.data.AgentMessage;
import now.calypso.backend.data.AgentMessageSender;
import now.calypso.backend.data.AgentSession;
import now.calypso.backend.data.AgentSessionStatus;
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
}
