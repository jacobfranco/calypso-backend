package now.calypso.backend;

import now.calypso.backend.data.*;
import now.calypso.backend.modules.*;
import now.calypso.backend.serialization.CalypsoSerialization;
import com.rpl.rama.*;
import com.rpl.rama.test.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class CoreTest {

    @Test
    public void accountCreationAndQueryTest(TestInfo testInfo) throws Exception {
        // 1. Register serialization and create in-process cluster
        List<Class> serializations = new ArrayList<>();
        serializations.add(CalypsoSerialization.class);

        try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
            // 2. Launch Relationships then Core modules
            Relationships relationshipsModule = new Relationships();
            Core coreModule = new Core();
            TestHelpers.launchModule(ipc, relationshipsModule, testInfo);
            TestHelpers.launchModule(ipc, coreModule, testInfo);
            String coreName = coreModule.getClass().getName();

            // 3. Grab depot, pstate, and query client
            Depot accountDepot = ipc.clusterDepot(coreName, "*accountDepot");
            PState emailToUser = ipc.clusterPState(coreName, "$$emailToUser");
            QueryTopologyClient<List<AccountWithId>> getAccounts = ipc.clusterQuery(coreName,
                    "getAccountsFromAccountIds");

            // 4. Append two accounts
            long ts = 0;
            Account alice = new Account();
            alice.setName("alice");
            alice.setEmail("alice@example.com");
            alice.setPwdHash("hash1");
            alice.setLocale("en_US");
            alice.setUuid("uuid-alice");
            alice.setPublicKey("pubKey1");
            alice.setTimestamp(++ts);
            alice.setAdmin(false);
            accountDepot.append(alice);

            Account bob = new Account();
            bob.setName("bob");
            bob.setEmail("bob@example.com");
            bob.setPwdHash("hash2");
            bob.setLocale("en_US");
            bob.setUuid("uuid-bob");
            bob.setPublicKey("pubKey2");
            bob.setTimestamp(++ts);
            bob.setAdmin(false);
            accountDepot.append(bob);

            // 5. Wait until both entries are in the pstate
            TestHelpers.attainConditionPred(
                    () -> emailToUser.selectOne(Path.key("alice@example.com")),
                    obj -> obj != null);
            TestHelpers.attainConditionPred(
                    () -> emailToUser.selectOne(Path.key("bob@example.com")),
                    obj -> obj != null);

            // 6. Extract generated account IDs
            @SuppressWarnings("unchecked")
            Map<String, Object> aliceInfo = (Map<String, Object>) emailToUser.selectOne(Path.key("alice@example.com"));
            long aliceId = (Long) aliceInfo.get("accountId");
            @SuppressWarnings("unchecked")
            Map<String, Object> bobInfo = (Map<String, Object>) emailToUser.selectOne(Path.key("bob@example.com"));
            long bobId = (Long) bobInfo.get("accountId");

            // 7. Query multiple accounts by IDs
            List<Long> queryIds = Arrays.asList(aliceId, bobId);
            List<AccountWithId> results = getAccounts.invoke(aliceId, queryIds);

            assertEquals(2, results.size(), "Should return two accounts");
            assertEquals(aliceId, results.get(0).getAccountId(), "First result should be Alice");
            assertEquals(bobId, results.get(1).getAccountId(), "Second result should be Bob");
        }
    }

    @Test
    public void filtersTopologyTest(TestInfo testInfo) throws Exception {
        List<Class> serializations = Collections.singletonList(CalypsoSerialization.class);

        try (InProcessCluster ipc = InProcessCluster.create(serializations)) {
            Relationships relationshipsModule = new Relationships();
            Core coreModule = new Core();
            TestHelpers.launchModule(ipc, relationshipsModule, testInfo);
            TestHelpers.launchModule(ipc, coreModule, testInfo);
            String coreName = coreModule.getClass().getName();

            Depot filtersDepot = ipc.clusterDepot(coreName, "*filtersDepot");
            PState accountIdToFilters = ipc.clusterPState(coreName, "$$accountIdToFilters");

            // Append a Filters object for accountId = 1
            Filters f = new Filters();
            f.setAccountId(1);
            filtersDepot.append(f);

            // Wait until pstate is updated
            TestHelpers.attainConditionPred(
                    () -> accountIdToFilters.selectOne(Path.key(1L)),
                    stored -> stored != null);

            Filters stored = (Filters) accountIdToFilters.selectOne(Path.key(1L));
            assertEquals(f.getAccountId(), stored.getAccountId(), "Stored accountId should match");
        }
    }

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

            // Remove Auth Code mapping
            RemoveAuthCode rem = new RemoveAuthCode();
            rem.setCode("code123");
            authCodeDepot.append(rem);
            TestHelpers.attainConditionPred(
                    () -> authCodeToAccountId.selectOne(Path.key("code123")),
                    id -> id == null);
        }
    }
}
