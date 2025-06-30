package now.calypso.backendapi;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.rpl.rama.*;
import com.rpl.rama.cluster.ClusterManagerBase;

import now.calypso.backendapi.pojos.*;
import now.calypso.backend.*;
import now.calypso.backend.data.*;
import now.calypso.backend.modules.*;

public class CalypsoApiManager {

    // Modules
    public static final String CORE_MODULE_NAME = Core.class.getName();
    public static final String RELATIONSHIPS_MODULE_NAME = Relationships.class.getName();

    // Core Depots
    private final Depot accountDepot;
    private final Depot filtersDepot;

    // Core PStates
    private final PState emailToUser;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Filters> getFiltersFromAccountId;

    // Relationships Depots
    private final Depot authCodeDepot;

    // Relationships PStates
    private final PState authCodeToAccountId;

    public CalypsoApiManager(ClusterManagerBase cluster) {

        // Core Depots
        accountDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*accountDepot");
        filtersDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*filtersDepot");

        // Core PStates
        emailToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$emailToUser");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
        getFiltersFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getFiltersFromAccountId");

        // Relationships Depots
        authCodeDepot = cluster.clusterDepot(RELATIONSHIPS_MODULE_NAME, "*authCodeDepot");

        // Relationships PStates
        authCodeToAccountId = cluster.clusterPState(RELATIONSHIPS_MODULE_NAME, "$$authCodeToAccountId");
    }

    public CompletableFuture<Boolean> postAccount(PostAccount params) {
        String pwdHash = CalypsoApiHelpers.encodePassword(params.password);
        String uuid = UUID.randomUUID().toString();
        final CalypsoWebHelpers.SigningKeyPair keys;
        try {
            keys = CalypsoWebHelpers.generateKeys();
        } catch (NoSuchProviderException | NoSuchAlgorithmException | IOException e) {
            return CompletableFuture.completedFuture(false);
        }
        return accountDepot
                .appendAsync(new Account(params.name, params.email, pwdHash, params.locale, uuid, keys.publicKey,
                        System.currentTimeMillis(), false))
                .thenCompose(res -> this.getAccountUUID(params.email))
                .thenApply(accountUUID -> accountUUID.equals(uuid));
    }

    public CompletableFuture<String> getAccountUUID(String email) {
        return emailToUser.selectOneAsync(Path.key(email, "uuid"));
    }

    public CompletableFuture<AccountWithId> getAccountWithId(Long requestAccountIdMaybe, long accountId) {
        return getAccountsFromAccountIds.invokeAsync(requestAccountIdMaybe, Arrays.asList(accountId))
                .thenApply(accountWithIds -> {
                    if (accountWithIds.size() == 0)
                        return null;
                    return accountWithIds.get(0);
                });
    }

    public CompletableFuture<AccountWithId> getAccountWithId(long accountId) {
        return this.getAccountWithId(null, accountId);
    }

    public CompletableFuture<Long> getAccountId(String email) {
        return emailToUser.selectOneAsync(Path.key(email, "accountId"));
    }

    public CompletableFuture<Boolean> postAuthCode(long accountId, String code) {
        return authCodeDepot.appendAsync(new AddAuthCode(code, accountId)).thenApply(res -> true);
    }

    public CompletableFuture<Long> getAccountIdFromAuthCode(String code) {
        return authCodeToAccountId.selectOneAsync(Path.key(code));
    }

    public CompletableFuture<Boolean> postFilters(PostFilters p, long accountId) {
        Filters thrift = p.toThrift(accountId);
        return filtersDepot.appendAsync(thrift)
                .thenApply(res -> true);
    }

    /** Fetch the latest Filters for accountId */
    public CompletableFuture<Filters> getFilters(long requesterId, long accountId) {
        return getFiltersFromAccountId.invokeAsync(requesterId, accountId);
    }

}
