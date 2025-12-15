package now.calypso.backendapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rpl.rama.Depot;
import com.rpl.rama.PState;
import com.rpl.rama.QueryTopologyClient;
import com.rpl.rama.cluster.ClusterManagerBase;
import com.rpl.rama.test.InProcessCluster;
import com.rpl.rama.test.LaunchConfig;

import now.calypso.backend.data.Filters;
import now.calypso.backend.data.SignalRecord;
import now.calypso.backend.data.Signals;
import now.calypso.backend.modules.Core;
import now.calypso.backend.modules.Matches;
import now.calypso.backend.serialization.CalypsoSerialization;
import now.calypso.backendapi.llm.OpenAIJson;

class CalypsoApiIntegrationTest {

    @AfterEach
    void clearOverride() {
        OpenAIJson.clearTestOverride();
    }

    @Test
    void extractAndAppendSignals_persistsNormalizedRecords() throws Exception {
        try (InProcessCluster ipc = newCluster()) {
            CalypsoApiManager mgr = newManager(ipc);
            long accountId = 900L;
            OpenAIJson.setTestOverride(
                    (system, user) -> "{\"signals\":[\"Likes NFL Sundays\",\"coffee!!!\",\"likes nfl sundays\"]}");
            try {
                List<String> tokens = mgr.extractAndAppendSignals(accountId, "prompt", "prompt_like", "ctx")
                        .get(5, TimeUnit.SECONDS);
                assertEquals(List.of("likes_nfl_sundays", "coffee"), tokens);
            } finally {
                OpenAIJson.clearTestOverride();
            }

            Signals stored = mgr.getSignals(accountId, accountId).get(5, TimeUnit.SECONDS);
            Map<String, SignalRecord> byToken = mapByToken(stored);
            SignalRecord nfl = byToken.get("likes_nfl_sundays");
            assertNotNull(nfl);
            assertEquals("prompt_like", nfl.getSource());
            assertEquals("ctx", nfl.getLastContext());
            assertEquals(1, nfl.getCount());

            SignalRecord coffee = byToken.get("coffee");
            assertNotNull(coffee);
            assertEquals("prompt_like", coffee.getSource());
        }
    }

    @Test
    void extractAndAppendSignals_mergesCounts() throws Exception {
        try (InProcessCluster ipc = newCluster()) {
            CalypsoApiManager mgr = newManager(ipc);
            long accountId = 901L;

            OpenAIJson.setTestOverride((s, u) -> "{\"signals\":[\"matcha\"]}");
            try {
                mgr.extractAndAppendSignals(accountId, "first", "agent_dm", "first ctx").get(5, TimeUnit.SECONDS);
            } finally {
                OpenAIJson.clearTestOverride();
            }

            OpenAIJson.setTestOverride((s, u) -> "{\"signals\":[\"matcha\"]}");
            try {
                mgr.extractAndAppendSignals(accountId, "second", "agent_dm", "second ctx").get(5, TimeUnit.SECONDS);
            } finally {
                OpenAIJson.clearTestOverride();
            }

            Signals stored = mgr.getSignals(accountId, accountId).get(5, TimeUnit.SECONDS);
            SignalRecord record = mapByToken(stored).get("matcha");
            assertNotNull(record);
            assertEquals(2, record.getCount());
            assertEquals("second ctx", record.getLastContext());
            assertEquals("agent_dm", record.getSource());
            assertTrue(record.getLastSeen() >= record.getFirstSeen());
        }
    }

    private Map<String, SignalRecord> mapByToken(Signals stored) {
        List<SignalRecord> records = (stored != null && stored.isSetRecords()) ? stored.getRecords() : List.of();
        return records.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(SignalRecord::getToken, Function.identity()));
    }

    private InProcessCluster newCluster() {
        return InProcessCluster.create(List.of(CalypsoSerialization.class));
    }

    private CalypsoApiManager newManager(InProcessCluster ipc) {
        LaunchConfig cfg = new LaunchConfig(2, 1);
        ipc.launchModule(new Core(), cfg);
        ipc.launchModule(new Matches(), cfg);
        return new CalypsoApiManager(new RoutingCluster(ipc), null);
    }

    private static final class RoutingCluster implements ClusterManagerBase {
        private final ClusterManagerBase delegate;

        RoutingCluster(ClusterManagerBase delegate) {
            this.delegate = delegate;
        }

        @Override
        public PState clusterPState(String module, String name) {
            return delegate.clusterPState(module, name);
        }

        @Override
        public Depot clusterDepot(String module, String name) {
            if (module.equals(CalypsoApiManager.CORE_MODULE_NAME) && "*filtersDepot".equals(name)) {
                return delegate.clusterDepot(CalypsoApiManager.MATCHES_MODULE_NAME, name);
            }
            return delegate.clusterDepot(module, name);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> QueryTopologyClient<T> clusterQuery(String module, String name) {
            if (module.equals(CalypsoApiManager.CORE_MODULE_NAME) && "getFiltersFromAccountId".equals(name)) {
                return (QueryTopologyClient<T>) NOOP_FILTERS_QUERY;
            }
            return delegate.clusterQuery(module, name);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final QueryTopologyClient<Filters> NOOP_FILTERS_QUERY = new QueryTopologyClient<Filters>() {
        @Override
        public Filters invoke(Object... args) {
            return null;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Filters> invokeAsync(Object... args) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    };
}
