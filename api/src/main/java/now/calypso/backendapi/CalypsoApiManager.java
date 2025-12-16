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
    private final Depot signalsDepot;

    // Core PStates
    private final PState emailToUser;
    private final PState authCodeToAccountId;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Signals> getSignalsFromAccountId;

    // Matches Depots
    private final Depot filtersDepot;
    private final Depot matchRefillDepot;
    private final Depot matchesServeDepot;

    // Matches Queries
    private final QueryTopologyClient<Filters> getFiltersFromAccountId;
    private final QueryTopologyClient<List<MatchCandidate>> getMatchesFromAccountId;

    public CalypsoApiManager(ClusterManagerBase cluster, OpenAIClient openAI) {

        this.openAI = openAI;

        // Core Depots
        accountDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*accountDepot");
        authCodeDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*authCodeDepot");
        signalsDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*signalsDepot");

        // Core PStates
        emailToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$emailToUser");
        authCodeToAccountId = cluster.clusterPState(CORE_MODULE_NAME, "$$authCodeToAccountId");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
        getSignalsFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getSignalsFromAccountId");

        // Matches Depots
        filtersDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*filtersDepot");
        matchRefillDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchRefillDepot");
        matchesServeDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchesServeDepot");

        // Matches Queries
        getFiltersFromAccountId = cluster.clusterQuery(MATCHES_MODULE_NAME, "getFiltersFromAccountId");
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
    private CompletableFuture<List<SignalRecord>> readCurrentSignalRecords(long accountId) {
        return getSignals(accountId, accountId).thenApply(s -> {
            List<SignalRecord> copy = new ArrayList<>();
            if (s != null && s.getRecords() != null) {
                for (SignalRecord record : s.getRecords()) {
                    if (record != null) {
                        copy.add(new SignalRecord(record));
                    }
                }
            }
            return copy;
        });
    }

    private static LinkedHashMap<String, SignalRecord> toRecordMap(List<SignalRecord> records) {
        LinkedHashMap<String, SignalRecord> map = new LinkedHashMap<>();
        if (records == null)
            return map;
        for (SignalRecord r : records) {
            if (r == null || r.getToken() == null)
                continue;
            map.put(recordKey(r), r);
        }
        return map;
    }

    private static String recordKey(SignalRecord record) {
        if (record == null || record.getToken() == null)
            return null;
        return recordKey(record.getToken(), record.getIntent());
    }

    private static String recordKey(String token, SignalIntent intent) {
        if (token == null)
            return null;
        String intentPart = (intent == null) ? "NONE" : intent.name();
        return intentPart + "|" + token;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank())
            return "manual";
        return source.trim();
    }

    private static String clampContext(String context) {
        if (context == null)
            return null;
        String trimmed = context.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() > 280 ? trimmed.substring(0, 280) : trimmed;
    }

    private static List<ExtractedSignal> sanitizeSignals(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        List<ExtractedSignal> out = new ArrayList<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null)
                continue;
            if (sig.token() == null || sig.token().isBlank())
                continue;
            out.add(sig);
        }
        return out;
    }

    /**
     * Normalize + append tokens coming directly from the client.
     */
    public CompletableFuture<Boolean> postSignals(long accountId, List<String> rawTokens, String source,
            String contextMaybe) {
        List<String> tokens = SignalNormalizer.normalizeTokens(rawTokens);
        if (tokens.isEmpty())
            return CompletableFuture.completedFuture(false);
        List<ExtractedSignal> manual = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            ExtractedSignal sig = ExtractedSignal.manual(token);
            if (sig != null)
                manual.add(sig);
        }
        return persistSignals(accountId, manual, source, contextMaybe);
    }

    private CompletableFuture<Boolean> persistSignals(long accountId, List<ExtractedSignal> signals, String source,
            String contextMaybe) {
        List<ExtractedSignal> sanitized = sanitizeSignals(signals);
        if (sanitized.isEmpty())
            return CompletableFuture.completedFuture(false);

        final long now = System.currentTimeMillis();
        final String normalizedSource = normalizeSource(source);
        final String context = clampContext(contextMaybe);

        CompletableFuture<Void> chained = serialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null) ? CompletableFuture.completedFuture(null) : prev;
            CompletableFuture<Void> next = start.thenCompose(
                    v -> readCurrentSignalRecords(accountId).thenCompose(current -> {
                        LinkedHashMap<String, SignalRecord> map = toRecordMap(current);
                        for (ExtractedSignal sig : sanitized) {
                            String key = recordKey(sig.token(), sig.intent());
                            SignalRecord record = map.get(key);
                            if (record == null) {
                                record = new SignalRecord();
                                record.setToken(sig.token());
                                record.setFirstSeen(now);
                                record.setCount(1);
                            } else {
                                record.setCount(record.isSetCount() ? record.getCount() + 1 : 1);
                                if (!record.isSetFirstSeen())
                                    record.setFirstSeen(now);
                            }
                            record.setSource(normalizedSource);
                            record.setLastSeen(now);
                            if (context != null)
                                record.setLastContext(context);
                            if (sig.intent() != null)
                                record.setIntent(sig.intent());
                            if (sig.confidence() != null)
                                record.setConfidence(sig.confidence());
                            if (sig.importance() != null)
                                record.setImportance(sig.importance());
                            map.put(key, record);
                        }
                        Signals updated = new Signals();
                        updated.setAccountId(accountId);
                        updated.setRecords(new ArrayList<>(map.values()));
                        return signalsDepot.appendAsync(updated).thenApply(res -> null);
                    }));
            next.whenComplete((r, e) -> serialByAccount.remove(k, next));
            return next;
        });

        return chained.thenApply(v -> true);
    }

    /** Robust extraction (LLM + normalization). Returns tokens only; no write. */
    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromText(String text) {
        return CompletableFuture.supplyAsync(() -> SignalExtractor.extractFreeform(openAI, text));
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromAgentConversation(List<String> conversation) {
        return CompletableFuture.supplyAsync(
                () -> SignalExtractor.extractFromAgentConversation(openAI, conversation, Set.of()));
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromPrompt(String question, String answer) {
        return CompletableFuture.supplyAsync(
                () -> SignalExtractor.extractFromPromptAnswer(openAI, question, answer, Set.of()));
    }

    /**
     * Convenience: extract from text, append, and return the tokens that were
     * attempted.
     */
    public CompletableFuture<List<String>> extractAndAppendSignals(long accountId, String text, String source,
            String contextMaybe) {
        return extractSignalsFromText(text).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, contextMaybe).thenApply(ok -> tokens);
        });
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromAgentConversation(long accountId,
            List<String> conversation, String source, String contextMaybe) {
        return extractSignalsFromAgentConversation(conversation).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, contextMaybe).thenApply(ok -> tokens);
        });
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String question,
            String answer, String source) {
        return extractSignalsFromPrompt(question, answer).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, answer).thenApply(ok -> tokens);
        });
    }

    private static List<String> tokens(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        List<String> out = new ArrayList<>(signals.size());
        for (ExtractedSignal sig : signals) {
            if (sig != null && sig.token() != null)
                out.add(sig.token());
        }
        return out;
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
