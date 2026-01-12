package now.calypso.backend.modules;

import com.rpl.rama.*;
import com.rpl.rama.module.*;

import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.AgentSession;

import static now.calypso.backend.CalypsoHelpers.extractFields;

public class Agent implements RamaModule {

        private static void declareSessionsTopology(Topologies topologies) {
                StreamTopology stream = topologies.stream("sessions");

                stream.pstate("$$accountIdToAgentSession", PState.mapSchema(Long.class, AgentSession.class));

                stream.source("*agentSessionDepot")
                                .out("*data")
                                .macro(extractFields("*data", "*accountId"))
                                .localTransform("$$accountIdToAgentSession",
                                                Path.key("*accountId").termVal("*data"));
        }

        private static void declareQueries(Topologies topologies) {
                topologies.query("getAgentSessionFromAccountId", "*requestAccountId", "*accountId").out("*session")
                                .hashPartition("*accountId")
                                .localSelect("$$accountIdToAgentSession", Path.key("*accountId")).out("*session")
                                .originPartition();
        }

        @Override
        public void define(Setup setup, Topologies topologies) {
                setup.declareDepot("*agentSessionDepot", Depot.hashBy(CalypsoHelpers.ExtractAccountId.class));

                declareSessionsTopology(topologies);
                declareQueries(topologies);
        }

}
