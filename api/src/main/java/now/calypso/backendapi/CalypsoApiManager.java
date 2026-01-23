package now.calypso.backendapi;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;
import java.util.concurrent.*;

import com.openai.client.OpenAIClient;
import com.rpl.rama.*;
import com.rpl.rama.cluster.ClusterManagerBase;

import now.calypso.backendapi.agent.AgentResponder;
import now.calypso.backendapi.pojos.*;
import now.calypso.backendapi.prompts.*;
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
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> promptSerialByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<AgentSession>> agentSerialByAccount = new ConcurrentHashMap<>();

    // Modules
    public static final String CORE_MODULE_NAME = Core.class.getName();
    public static final String MATCHES_MODULE_NAME = Matches.class.getName();
    public static final String AGENT_MODULE_NAME = Agent.class.getName();

    // Core Depots
    private final Depot accountDepot;
    private final Depot applicationDepot;
    private final Depot authCodeDepot;
    private final Depot promptsDepot;

    // Core PStates
    private final PState emailToUser;
    private final PState authCodeToAccountId;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Application> getApplicationFromClientId;
    private final QueryTopologyClient<PromptState> getPromptsStateFromAccountId;
    

    // Matches Depots
    private final Depot signalsDepot;
    private final Depot filtersDepot;
    private final Depot matchRefillDepot;
    private final Depot matchesServeDepot;

    // Matches Queries
    private final QueryTopologyClient<Filters> getFiltersFromAccountId;
    private final QueryTopologyClient<List<MatchCandidate>> getMatchesFromAccountId;
    private final QueryTopologyClient<Signals> getSignalsFromAccountId;
    private final QueryTopologyClient<AgentSession> getAgentSessionFromAccountId;

    // Agent Depots
    private final Depot agentSessionDepot;

    public CalypsoApiManager(ClusterManagerBase cluster, OpenAIClient openAI) {

        this.openAI = openAI;

        // Core Depots
        accountDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*accountDepot");
        applicationDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*applicationDepot");
        authCodeDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*authCodeDepot");
        promptsDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*promptsDepot");

        // Core PStates
        emailToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$emailToUser");
        authCodeToAccountId = cluster.clusterPState(CORE_MODULE_NAME, "$$authCodeToAccountId");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
         getApplicationFromClientId = cluster.clusterQuery(CORE_MODULE_NAME, "getApplicationFromClientId");
        getPromptsStateFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getPromptsStateFromAccountId");

        // Matches Depots
        signalsDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*signalsDepot");
        filtersDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*filtersDepot");
        matchRefillDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchRefillDepot");
        matchesServeDepot = cluster.clusterDepot(MATCHES_MODULE_NAME, "*matchesServeDepot");
        agentSessionDepot = cluster.clusterDepot(AGENT_MODULE_NAME, "*agentSessionDepot");

        // Matches Queries
        getFiltersFromAccountId = cluster.clusterQuery(MATCHES_MODULE_NAME, "getFiltersFromAccountId");
        getMatchesFromAccountId = cluster.clusterQuery(MATCHES_MODULE_NAME, "getMatchesFromAccountId");
        getSignalsFromAccountId = cluster.clusterQuery(MATCHES_MODULE_NAME, "getSignalsFromAccountId");
        getAgentSessionFromAccountId = cluster.clusterQuery(AGENT_MODULE_NAME, "getAgentSessionFromAccountId");

    }

    public CompletableFuture<Application> postApplication(PostApplication params) {
        Application newApp = new Application(
                CalypsoHelpers.randomString(16), // client_id
                CalypsoHelpers.generateSecureRandomString(32), // client_secret
                params.client_name,
                params.redirect_uris,
                params.scopes);

        return applicationDepot
                .appendAsync(newApp)
                .thenApply(v -> newApp);
    }

    public CompletableFuture<Application> getApplication(String clientId) {
        return getApplicationFromClientId.invokeAsync(clientId);
    }

    public CompletableFuture<Boolean> postRemoveAuthCode(String code) {
        return authCodeDepot.appendAsync(new RemoveAuthCode(code)).thenApply(res -> true);
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

    public CompletableFuture<PromptState> getPrompts(long requesterId, long accountId) {
        return getPromptsStateFromAccountId.invokeAsync(requesterId, accountId);
    }

    public CompletableFuture<AgentSession> getAgentSessionSnapshot(long accountId) {
        CompletableFuture<AgentSession> inflight = agentSerialByAccount.get(accountId);
        if (inflight != null)
            return inflight.thenApply(AgentSession::new);
        return ensureAgentSession(accountId).thenApply(AgentSession::new);
    }

    public CompletableFuture<AgentSession> postAgentMessage(long accountId, String text) {
        String normalized = clampAgentText(text);
        if (normalized == null)
            throw new IllegalArgumentException("Message text required.");
        return agentSerialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<AgentSession> base = (prev == null) ? ensureAgentSession(accountId) : prev;
            CompletableFuture<AgentSession> next = base.thenCompose(session -> processAgentMessage(accountId, session,
                    normalized));
            next.whenComplete((r, e) -> agentSerialByAccount.remove(k, next));
            return next;
        });
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

    private CompletableFuture<PromptState> readCurrentPromptState(long accountId) {
        return getPrompts(accountId, accountId).thenApply(state -> {
            if (state == null) {
                PromptState empty = new PromptState();
                empty.setAccountId(accountId);
                empty.setResponses(new ArrayList<>());
                return empty;
            }
            PromptState copy = new PromptState(state);
            if (!copy.isSetResponses())
                copy.setResponses(new ArrayList<>());
            return copy;
        });
    }

    private CompletableFuture<AgentSession> ensureAgentSession(long accountId) {
        return getAgentSessionFromAccountId.invokeAsync(accountId, accountId).thenCompose(existing -> {
            if (existing != null) {
                AgentSession copy = new AgentSession(existing);
                if (!copy.isSetMessages())
                    copy.setMessages(new ArrayList<>());
                return CompletableFuture.completedFuture(copy);
            }
            AgentSession fresh = new AgentSession();
            fresh.setSessionId(UUID.randomUUID().toString());
            fresh.setAccountId(accountId);
            long now = System.currentTimeMillis();
            fresh.setCreatedAt(now);
            fresh.setLastInteractionAt(now);
            fresh.setStatus(AgentSessionStatus.ACTIVE);
            fresh.setMessages(new ArrayList<>());
            return agentSessionDepot.appendAsync(fresh).thenApply(res -> fresh);
        });
    }

    private CompletableFuture<Void> persistAgentSession(AgentSession session) {
        AgentSession copy = new AgentSession(session);
        return agentSessionDepot.appendAsync(copy).thenApply(res -> null);
    }

    private static void appendMessage(AgentSession session, AgentMessageSender sender, String text, long timestamp) {
        if (!session.isSetMessages() || session.getMessages() == null)
            session.setMessages(new ArrayList<>());
        AgentMessage msg = new AgentMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setSessionId(session.getSessionId());
        msg.setSender(sender);
        msg.setText(text);
        msg.setTimestamp(timestamp);
        session.getMessages().add(msg);
    }

    private static String clampAgentText(String text) {
        if (text == null)
            return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() > 800 ? trimmed.substring(0, 800) : trimmed;
    }

    private static List<String> toConversation(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty())
            return List.of();
        List<String> out = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (msg == null || msg.getText() == null)
                continue;
            String prefix = (msg.getSender() == AgentMessageSender.AGENT) ? "agent" : "user";
            out.add(prefix + ": " + msg.getText());
        }
        return out;
    }

    private void emitAgentSignals(long accountId, AgentSession session) {
        List<String> conversation = toConversation(session.getMessages());
        if (conversation.isEmpty())
            return;
        extractAndAppendSignalsFromAgentConversation(accountId, conversation, "agent_chat", session.getSessionId(),
                "session:" + session.getSessionId()).exceptionally(ex -> {
                    LOG.warn("Agent signal extraction failed for account {}", accountId, ex);
                    return List.of();
                });
    }

    private CompletableFuture<AgentSession> processAgentMessage(long accountId, AgentSession current, String text) {
        AgentSession updated = (current == null) ? new AgentSession() : new AgentSession(current);
        if (!updated.isSetSessionId() || updated.getSessionId() == null) {
            updated.setSessionId(UUID.randomUUID().toString());
            updated.setAccountId(accountId);
            updated.setCreatedAt(System.currentTimeMillis());
            updated.setStatus(AgentSessionStatus.ACTIVE);
            updated.setMessages(new ArrayList<>());
        }
        long now = System.currentTimeMillis();
        appendMessage(updated, AgentMessageSender.USER, text, now);
        updated.setLastInteractionAt(now);

        CompletableFuture<String> replyFuture = CompletableFuture
                .supplyAsync(() -> AgentResponder.generate(openAI, updated));

        return replyFuture.thenCompose(reply -> {
            long replyTs = System.currentTimeMillis();
            appendMessage(updated, AgentMessageSender.AGENT, reply, replyTs);
            updated.setLastInteractionAt(replyTs);
            return persistAgentSession(updated).thenApply(v -> {
                emitAgentSignals(accountId, updated);
                return new AgentSession(updated);
            });
        });
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

    private static MatchCandidate selectCandidate(List<MatchCandidate> candidates) {
        if (candidates == null)
            return null;
        MatchCandidate best = null;
        for (MatchCandidate c : candidates) {
            if (c == null)
                continue;
            if (best == null || c.getStage0Score() > best.getStage0Score()) {
                best = c;
            }
        }
        return best;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank())
            return "manual";
        return source.trim();
    }

    private static String normalizeSourceId(String sourceId) {
        if (sourceId == null)
            return null;
        String trimmed = sourceId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clampContext(String context) {
        if (context == null)
            return null;
        String trimmed = context.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() > 280 ? trimmed.substring(0, 280) : trimmed;
    }

    private static String clampPromptText(String text, int limit) {
        if (text == null)
            return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
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

    public CompletableFuture<PromptSuggestion> nextPrompt(long accountId) {
        CompletableFuture<PromptState> stateFuture = getPrompts(accountId, accountId);
        CompletableFuture<List<MatchCandidate>> matchesFuture = getMatchesFromAccountId.invokeAsync(accountId,
                accountId, 5);
        return stateFuture.thenCombine(matchesFuture, (state, matches) -> {
            List<PromptQuestion> prompts = PromptLibrary.all();
            if (prompts.isEmpty())
                throw new IllegalStateException("No prompts available");
            Set<String> answered = new HashSet<>();
            if (state != null && state.getResponses() != null) {
                for (PromptResponse resp : state.getResponses()) {
                    if (resp == null || resp.getQuestion() == null)
                        continue;
                    String pid = resp.getQuestion().getPromptId();
                    if (pid != null)
                        answered.add(pid);
                }
            }
            List<PromptQuestion> candidates = new ArrayList<>();
            for (PromptQuestion q : prompts) {
                if (q != null && !answered.contains(q.getPromptId()))
                    candidates.add(q);
            }
            if (candidates.isEmpty())
                candidates = prompts;
            PromptQuestion choice = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            PromptQuestion question = new PromptQuestion(choice);
            MatchCandidate best = selectCandidate(matches);
            Long targetId = best == null ? null : best.getTargetAccountId();
            Double score = best == null ? null : best.getStage0Score();
            return new PromptSuggestion(question, targetId, score);
        });
    }

    public CompletableFuture<PromptResponse> postPromptResponse(long accountId, String promptId,
            PostPromptResponseRequest request) {
        PromptQuestion prompt = PromptLibrary.getById(promptId);
        if (prompt == null)
            throw new IllegalArgumentException("Unknown prompt: " + promptId);
        PostPromptResponseRequest payload = request == null
                ? new PostPromptResponseRequest(null, null, null, null, null)
                : request;
        String responseId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        PromptResponse response = new PromptResponse();
        response.setResponseId(responseId);
        response.setAccountId(accountId);
        response.setQuestion(new PromptQuestion(prompt));
        response.setServedAt(now);
        response.setAnsweredAt(now);
        if (payload.targetAccountId != null)
            response.setRelatedTargetAccountId(payload.targetAccountId);
        PromptReaction reaction = payload.parsedReaction();
        if (reaction != null)
            response.setReaction(reaction);
        String answerText = clampPromptText(payload.answerText, 512);
        if (answerText != null)
            response.setAnswerText(answerText);
        String comment = clampPromptText(payload.comment, 512);
        if (comment != null)
            response.setComment(comment);
        List<AttachmentWithId> attachments = payload.toThriftAttachments();
        if (!attachments.isEmpty())
            response.setAttachments(attachments);

        CompletableFuture<Void> persist = persistPromptResponse(accountId, response);
        String signalText = (answerText == null) ? comment
                : (comment == null ? answerText : answerText + "\n" + comment);
        CompletableFuture<List<String>> signals = (signalText == null)
                ? CompletableFuture.completedFuture(List.of())
                : extractAndAppendSignalsFromPrompt(accountId, prompt.getQuestion(), signalText, "prompt",
                        responseId);

        return persist.thenCombine(signals, (v, tokens) -> new PromptResponse(response));
    }

    private CompletableFuture<Void> persistPromptResponse(long accountId, PromptResponse response) {
        CompletableFuture<Void> chained = promptSerialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null) ? CompletableFuture.completedFuture(null) : prev;
            CompletableFuture<Void> next = start.thenCompose(
                    v -> readCurrentPromptState(accountId).thenCompose(state -> {
                        List<PromptResponse> responses = state.getResponses() == null
                                ? new ArrayList<>()
                                : new ArrayList<>(state.getResponses());
                        responses.add(new PromptResponse(response));
                        if (responses.size() > 200) {
                            responses = new ArrayList<>(responses.subList(responses.size() - 200, responses.size()));
                        }
                        state.setResponses(responses);
                        return promptsDepot.appendAsync(state).thenApply(res -> null);
                    }));
            next.whenComplete((r, e) -> promptSerialByAccount.remove(k, next));
            return next;
        });
        return chained;
    }

    /**
     * Normalize + append tokens coming directly from the client.
     */
    public CompletableFuture<Boolean> postSignals(long accountId, List<String> rawTokens, String source,
            String sourceId, String contextMaybe) {
        List<String> tokens = SignalNormalizer.normalizeTokens(rawTokens);
        if (tokens.isEmpty())
            return CompletableFuture.completedFuture(false);
        List<ExtractedSignal> manual = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            ExtractedSignal sig = ExtractedSignal.manual(token);
            if (sig != null)
                manual.add(sig);
        }
        return persistSignals(accountId, manual, source, sourceId, contextMaybe);
    }

    private CompletableFuture<Boolean> persistSignals(long accountId, List<ExtractedSignal> signals, String source,
            String sourceId, String contextMaybe) {
        List<ExtractedSignal> sanitized = sanitizeSignals(signals);
        if (sanitized.isEmpty())
            return CompletableFuture.completedFuture(false);

        final long now = System.currentTimeMillis();
        final String normalizedSource = normalizeSource(source);
        final String normalizedSourceId = normalizeSourceId(sourceId);
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
                            if (normalizedSourceId != null)
                                record.setSourceId(normalizedSourceId);
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
            String sourceId, String contextMaybe) {
        return extractSignalsFromText(text).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, sourceId, contextMaybe).thenApply(ok -> tokens);
        });
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromAgentConversation(long accountId,
            List<String> conversation, String source, String sourceId, String contextMaybe) {
        return extractSignalsFromAgentConversation(conversation).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, sourceId, contextMaybe).thenApply(ok -> tokens);
        });
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String question,
            String answer, String source, String sourceId) {
        return extractSignalsFromPrompt(question, answer).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, sourceId, answer).thenApply(ok -> tokens);
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
