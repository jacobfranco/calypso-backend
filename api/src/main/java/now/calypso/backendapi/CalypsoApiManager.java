package now.calypso.backendapi;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.concurrent.*;

import com.openai.client.OpenAIClient;
import com.rpl.rama.*;
import com.rpl.rama.cluster.ClusterManagerBase;

import now.calypso.backendapi.pojos.*;
import now.calypso.backendapi.signals.*;
import now.calypso.backend.*;
import now.calypso.backend.data.*;
import now.calypso.backend.modules.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CalypsoApiManager {

    private static final Logger LOG = LoggerFactory.getLogger(CalypsoApiManager.class);

    private final OpenAIClient openAI;

    private final ConcurrentHashMap<Long, CompletableFuture<Void>> serialByAccount = new ConcurrentHashMap<>();

    // Modules
    public static final String CORE_MODULE_NAME = Core.class.getName();
    public static final String MATCHES_MODULE_NAME = Matches.class.getName();

    // Core Depots
    private final Depot accountDepot;
    private final Depot authCodeDepot;
    private final Depot filtersDepot;
    private final Depot signalsDepot;

    // Core PStates
    private final PState emailToUser;
    private final PState authCodeToAccountId;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Filters> getFiltersFromAccountId;
    private final QueryTopologyClient<Signals> getSignalsFromAccountId;

    // Matches Depots
    private final Depot matchRefillDepot;
    private final Depot matchesServeDepot;

    // Matches Queries
    private final QueryTopologyClient<List<MatchCandidate>> getMatchesFromAccountId;

    public CalypsoApiManager(ClusterManagerBase cluster, OpenAIClient openAI) {

        this.openAI = openAI;

        // Core Depots
        accountDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*accountDepot");
        authCodeDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*authCodeDepot");
        filtersDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*filtersDepot");
        signalsDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*signalsDepot");

        // Core PStates
        emailToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$emailToUser");
        authCodeToAccountId = cluster.clusterPState(CORE_MODULE_NAME, "$$authCodeToAccountId");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
        getFiltersFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getFiltersFromAccountId");
        getSignalsFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getSignalsFromAccountId");

        // Matches Depots
        matchRefillDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchRefillDepot");
        matchesServeDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchesServeDepot");

        // Matches Queries
        getMatchesFromAccountId = cluster.clusterQuery(MATCHES_MODULE_NAME, "getMatchesFromAccountId");

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

    public CompletableFuture<Filters> getFilters(long requesterId, long accountId) {
        return getFiltersFromAccountId.invokeAsync(requesterId, accountId);
    }

    public CompletableFuture<Signals> getSignals(long requesterId, long accountId) {
        return getSignalsFromAccountId.invokeAsync(requesterId, accountId);
    }

    /** Read current signals for the owner (treat null as empty list). */
    private CompletableFuture<List<String>> readCurrentSignals(long accountId) {
        return getSignals(accountId, accountId).thenApply(s -> {
            if (s == null || s.signals == null)
                return new ArrayList<>();
            return new ArrayList<>(s.signals);
        });
    }

    /** Set-semantics union preserving insertion order. */
    private static List<String> union(List<String> current, List<String> delta) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (current != null)
            set.addAll(current);
        if (delta != null)
            set.addAll(delta);
        return new ArrayList<>(set);
    }

    /**
     * Normalize + append (union) tokens, writing a full Signals object. Serialized
     * per account.
     */
    public CompletableFuture<Boolean> postSignals(long accountId, List<String> rawTokens) {
        List<String> tokens = SignalNormalizer.normalizeTokens(rawTokens);
        if (tokens.isEmpty())
            return CompletableFuture.completedFuture(false);

        CompletableFuture<Void> chained = serialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null) ? CompletableFuture.completedFuture(null) : prev;
            CompletableFuture<Void> next = start.thenCompose(v -> readCurrentSignals(accountId).thenCompose(current -> {
                List<String> merged = union(current, tokens);
                if (merged.equals(current))
                    return CompletableFuture.completedFuture(null); // no-op
                Signals updated = new Signals();
                updated.setAccountId(accountId);
                updated.setSignals(merged);

                return signalsDepot.appendAsync(updated).thenApply(res -> null);
            }));
            next.whenComplete((r, e) -> serialByAccount.remove(k, next));
            return next;
        });

        return chained.thenApply(v -> true);
    }

    /** Robust extraction (LLM + normalization). Returns tokens only; no write. */
    public CompletableFuture<List<String>> extractSignalsFromText(String text) {
        return CompletableFuture.supplyAsync(() -> SignalExtractor.extract(openAI, text));
    }

    /**
     * Convenience: extract from text, append, and return the tokens that were
     * attempted.
     */
    public CompletableFuture<List<String>> extractAndAppendSignals(long accountId, String text, String source) {
        return extractSignalsFromText(text).thenCompose(tokens -> {
            if (tokens.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            return postSignals(accountId, tokens).thenApply(ok -> tokens);
        });
    }

    private CompletableFuture<Void> requestRefill(long viewerId, int targetSize) {
        MatchRefillRequest req = new MatchRefillRequest();
        req.setAccountId(viewerId);
        req.setTargetSize(targetSize);
        return matchRefillDepot.appendAsync(req).thenApply(x -> null);
    }

    public CompletableFuture<List<GetMatch>> getMatches(long requesterId, long viewerId, int limit) {
        if (limit <= 0)
            limit = 20;
        if (limit > 100)
            limit = 100;

        // 1) opportunistic refill (non-blocking)
        int refillTarget = Math.max(60, limit * 2);
        requestRefill(viewerId, refillTarget)
                .exceptionally(ex -> {
                    LOG.warn("Failed to enqueue match refill for account {} (target size {})", viewerId, refillTarget,
                            ex);
                    return null;
                });

        // 2) read top candidates (query is read-only & already filters
        // exposure/exclusions)
        return getMatchesFromAccountId.invokeAsync(requesterId, viewerId, limit)
                .thenCompose(cands -> {
                    // Collect target ids in order
                    List<Long> ids = new ArrayList<>();
                    for (MatchCandidate c : cands)
                        ids.add(c.getTargetAccountId());

                    // 3) fetch account cards in the same order
                    return getAccountsFromAccountIds.invokeAsync(viewerId, ids)
                            .thenCompose(accounts -> {
                                // Build DTOs aligned with cands
                                List<GetMatch> out = new ArrayList<>(cands.size());
                                Map<Long, MatchCandidate> byId = new HashMap<>();
                                for (MatchCandidate c : cands)
                                    byId.put(c.getTargetAccountId(), c);

                                for (AccountWithId aw : accounts) {
                                    MatchCandidate c = byId.get(aw.accountId);
                                    if (c == null)
                                        continue; // safety
                                    GetAccount ga = new GetAccount(aw);
                                    out.add(new GetMatch(ga, c.getStage0Score(), c.getComputedAt()));
                                }

                                // 4) log exposure (so refills/query skip these soon)
                                if (!ids.isEmpty()) {
                                    ServedPairs sp = new ServedPairs();
                                    sp.setAccountId(viewerId);
                                    sp.setTargetIds(ids);
                                    sp.setServedAt(System.currentTimeMillis());
                                    return matchesServeDepot.appendAsync(sp).thenApply(x -> out);
                                } else {
                                    return CompletableFuture.completedFuture(out);
                                }
                            });
                });
    }

}
