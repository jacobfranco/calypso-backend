package now.calypso.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.rpl.rama.Depot;
import com.rpl.rama.PState;
import com.rpl.rama.Path;
import com.rpl.rama.test.InProcessCluster;

import now.calypso.backend.data.AddAuthCode;
import now.calypso.backend.data.RemoveAuthCode;
import now.calypso.backend.modules.Relationships;
import now.calypso.backend.serialization.CalypsoSerialization;

public class RelationshipsTest {

    @Test
    public void authCodeTopologyTest(TestInfo testInfo) throws Exception {
        List<Class> serializations = Collections.singletonList(CalypsoSerialization.class);

        try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
            Relationships relationshipsModule = new Relationships();
            TestHelpers.launchModule(ipc, relationshipsModule, testInfo);
            String relName = relationshipsModule.getClass().getName();

            Depot authCodeDepot = ipc.clusterDepot(relName, "*authCodeDepot");
            PState authCodeToAccountId = ipc.clusterPState(relName, "$$authCodeToAccountId");

            // Add Auth Code mapping
            AddAuthCode add = new AddAuthCode();
            add.setCode("code123");
            add.setAccountId(42L);
            authCodeDepot.append(add);

            TestHelpers.attainConditionPred(
                    () -> authCodeToAccountId.selectOne(Path.key("code123")),
                    id -> Objects.equals(id, 42L));
            assertEquals((Long) 42L, authCodeToAccountId.selectOne(Path.key("code123")),
                    "Auth code should map to account ID 42");

            // Remove Auth Code mapping
            RemoveAuthCode rem = new RemoveAuthCode();
            rem.setCode("code123");
            authCodeDepot.append(rem);

            TestHelpers.attainConditionPred(
                    () -> authCodeToAccountId.selectOne(Path.key("code123")),
                    id -> id == null);
            assertNull(authCodeToAccountId.selectOne(Path.key("code123")),
                    "Auth code should be removed and no longer mapped");
        }
    }

}
