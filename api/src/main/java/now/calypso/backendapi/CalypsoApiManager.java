package now.calypso.backendapi;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import com.openai.client.OpenAIClient;
import com.rpl.rama.*;
import com.rpl.rama.cluster.ClusterManagerBase;

import now.calypso.backendapi.agent.AgentResponder;
import now.calypso.backendapi.agent.PrivatePromptTurnResponder;
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
    private final ConcurrentHashMap<Long, CompletableFuture<AgentSession>> agentSerialByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<?>> privatePromptSerialByAccount = new ConcurrentHashMap<>();

    // Modules
    public static final String CORE_MODULE_NAME = Core.class.getName();
    public static final String AGENT_MODULE_NAME = Agent.class.getName();

    // Core Depots
    private final Depot accountDepot;
    private final Depot applicationDepot;
    private final Depot authCodeDepot;
    private final Depot publicPromptAnswerDepot;
    private final Depot publicPromptReactionDepot;
    private final Depot publicPromptSelectionDepot;

    // Core PStates
    private final PState phoneToUser;
    private final PState authCodeToAccountId;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Application> getApplicationFromClientId;
    private final QueryTopologyClient<PublicPromptAnswer> getPublicPromptAnswerById;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getPublicPromptFeed;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getMyPublicPromptAnswers;
    private final QueryTopologyClient<PublicPromptSelection> getPublicPromptSelection;

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
    private final QueryTopologyClient<PrivatePromptAssignment> getPrivatePromptAssignmentByInstanceId;
    private final QueryTopologyClient<PrivatePromptAnswer> getPrivatePromptAnswerByInstanceId;
    private final QueryTopologyClient<PrivatePromptAssignment> getActivePrivatePromptAssignment;
    private final QueryTopologyClient<Map<String, Object>> getPrivatePromptSchedulerState;

    private final ConcurrentHashMap<String, PhoneVerification> phoneVerificationByNumber = new ConcurrentHashMap<>();
    private static final long PHONE_CODE_TTL_MS = 10 * 60 * 1000;
    private static final long PRIVATE_PROMPT_SKIP_COOLDOWN_MS = 24L * 60 * 60 * 1000;
    private static final long PRIVATE_PROMPT_DEFAULT_SNOOZE_MS = 12L * 60 * 60 * 1000;
    private static final int PRIVATE_PROMPT_DAILY_SPAWN_HOUR = 20;
    private static final int PRIVATE_PROMPT_BODY_LIMIT = 1200;
    private static final SecureRandom PHONE_CODE_RANDOM = new SecureRandom();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String SMS_FALLBACK_ENV = "CALYPSO_SMS_FALLBACK";

    // Agent Depots
    private final Depot agentSessionDepot;
    private final Depot privatePromptAssignmentDepot;
    private final Depot privatePromptAnswerDepot;

    public CalypsoApiManager(ClusterManagerBase cluster, OpenAIClient openAI) {

        this.openAI = openAI;

        // Core Depots
        accountDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*accountDepot");
        applicationDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*applicationDepot");
        authCodeDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*authCodeDepot");
        publicPromptAnswerDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*publicPromptAnswerDepot");
        publicPromptReactionDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*publicPromptReactionDepot");
        publicPromptSelectionDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*publicPromptSelectionDepot");

        // Core PStates
        phoneToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$phoneToUser");
        authCodeToAccountId = cluster.clusterPState(CORE_MODULE_NAME, "$$authCodeToAccountId");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
        getApplicationFromClientId = cluster.clusterQuery(CORE_MODULE_NAME, "getApplicationFromClientId");
        getPublicPromptAnswerById = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptAnswerById");
        getPublicPromptFeed = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptFeed");
        getMyPublicPromptAnswers = cluster.clusterQuery(CORE_MODULE_NAME, "getMyPublicPromptAnswers");
        getPublicPromptSelection = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptSelection");

        // Matches Depots
        signalsDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*signalsDepot");
        filtersDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*filtersDepot");
        matchRefillDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*matchRefillDepot");
        matchesServeDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*matchesServeDepot");
        agentSessionDepot = cluster.clusterDepot(AGENT_MODULE_NAME, "*agentSessionDepot");
        privatePromptAssignmentDepot = cluster.clusterDepot(AGENT_MODULE_NAME, "*privatePromptAssignmentDepot");
        privatePromptAnswerDepot = cluster.clusterDepot(AGENT_MODULE_NAME, "*privatePromptAnswerDepot");

        // Matches Queries
        getFiltersFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getFiltersFromAccountId");
        getMatchesFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getMatchesFromAccountId");
        getSignalsFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getSignalsFromAccountId");
        getAgentSessionFromAccountId = cluster.clusterQuery(AGENT_MODULE_NAME, "getAgentSessionFromAccountId");
        getPrivatePromptAssignmentByInstanceId = cluster.clusterQuery(AGENT_MODULE_NAME,
                "getPrivatePromptAssignmentByInstanceId");
        getPrivatePromptAnswerByInstanceId = cluster.clusterQuery(AGENT_MODULE_NAME, "getPrivatePromptAnswerByInstanceId");
        getActivePrivatePromptAssignment = cluster.clusterQuery(AGENT_MODULE_NAME, "getActivePrivatePromptAssignment");
        getPrivatePromptSchedulerState = cluster.clusterQuery(AGENT_MODULE_NAME, "getPrivatePromptSchedulerState");

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
        String uuid = UUID.randomUUID().toString();
        final CalypsoWebHelpers.SigningKeyPair keys;
        try {
            keys = CalypsoWebHelpers.generateKeys();
        } catch (NoSuchProviderException | NoSuchAlgorithmException | IOException e) {
            return CompletableFuture.completedFuture(false);
        }
        Account account = new Account(params.name, params.phone_number, params.locale, uuid, keys.publicKey,
                System.currentTimeMillis(), false);
        if (params.birthday != null) {
            account.setBirthday(params.birthday);
        }
        return accountDepot
                .appendAsync(account)
                .thenCompose(res -> this.getAccountUUID(params.phone_number))
                .thenApply(accountUUID -> accountUUID.equals(uuid));
    }

    public CompletableFuture<String> getAccountUUID(String phoneNumber) {
        return phoneToUser.selectOneAsync(Path.key(phoneNumber, "uuid"));
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

    public CompletableFuture<Long> getAccountId(String phoneNumber) {
        return phoneToUser.selectOneAsync(Path.key(phoneNumber, "accountId"));
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

    public CompletableFuture<String> requestPhoneCode(String phoneNumber) {
        String code = String.format("%06d", PHONE_CODE_RANDOM.nextInt(1_000_000));
        long expiresAt = System.currentTimeMillis() + PHONE_CODE_TTL_MS;
        PhoneVerification verification = new PhoneVerification(code, null, expiresAt);
        phoneVerificationByNumber.put(phoneNumber, verification);
        return sendSms(phoneNumber, String.format("Your Calypso code is %s", code))
                .whenComplete((ignored, err) -> {
                    if (err != null) {
                        phoneVerificationByNumber.remove(phoneNumber, verification);
                    }
                })
                .thenApply(ignored -> code);
    }

    public CompletableFuture<String> verifyPhoneCode(String phoneNumber, String code) {
        PhoneVerification verification = phoneVerificationByNumber.get(phoneNumber);
        if (verification == null || verification.isExpired()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Code expired"));
        }
        if (!verification.code.equals(code)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid code"));
        }
        String token = CalypsoHelpers.generateSecureRandomString(32);
        verification.token = token;
        phoneVerificationByNumber.put(phoneNumber, verification);
        return CompletableFuture.completedFuture(token);
    }

    public CompletableFuture<Boolean> consumePhoneVerification(String phoneNumber, String token) {
        PhoneVerification verification = phoneVerificationByNumber.get(phoneNumber);
        if (verification == null || verification.isExpired()) {
            return CompletableFuture.completedFuture(false);
        }
        if (!token.equals(verification.token)) {
            return CompletableFuture.completedFuture(false);
        }
        phoneVerificationByNumber.remove(phoneNumber);
        return CompletableFuture.completedFuture(true);
    }

    private static class PhoneVerification {
        private final String code;
        private String token;
        private final long expiresAt;

        private PhoneVerification(String code, String token, long expiresAt) {
            this.code = code;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private CompletableFuture<Void> sendSms(String to, String body) {
        String fallbackValue = System.getenv(SMS_FALLBACK_ENV);
        boolean fallbackEnabled = fallbackValue != null && fallbackValue.trim().equalsIgnoreCase("true");
        if (fallbackEnabled) {
            LOG.warn("SMS fallback enabled ({}): {} -> {}", SMS_FALLBACK_ENV, to, body);
            return CompletableFuture.completedFuture(null);
        }
        String sid = System.getenv("TWILIO_ACCOUNT_SID");
        String authToken = System.getenv("TWILIO_AUTH_TOKEN");
        String from = System.getenv("TWILIO_FROM_NUMBER");
        if (sid == null || sid.isBlank() || authToken == null || authToken.isBlank() || from == null || from.isBlank()) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Twilio credentials not configured"));
            return failed;
        }

        String payload = String.format("From=%s&To=%s&Body=%s",
                urlEncode(from),
                urlEncode(to),
                urlEncode(body));
        String auth = Base64.getEncoder().encodeToString((sid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(String.format("https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json", sid)))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        throw new CompletionException(new IllegalStateException(
                                "Failed to send SMS: " + response.body()));
                    }
                });
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public CompletableFuture<Filters> getFilters(long requesterId, long accountId) {
        return getFiltersFromAccountId.invokeAsync(requesterId, accountId);
    }

    public CompletableFuture<Signals> getSignals(long requesterId, long accountId) {
        return getSignalsFromAccountId.invokeAsync(requesterId, accountId);
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

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> serializePrivatePromptOp(long accountId, Supplier<CompletableFuture<T>> op) {
        return (CompletableFuture<T>) privatePromptSerialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null)
                    ? CompletableFuture.completedFuture(null)
                    : ((CompletableFuture<?>) prev).handle((ignored, err) -> null);
            CompletableFuture<T> next = start.thenCompose(v -> op.get());
            next.whenComplete((r, e) -> privatePromptSerialByAccount.remove(k, next));
            return next;
        });
    }

    private static String asTrimmedString(Object raw) {
        if (raw == null)
            return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long asLong(Object raw) {
        return raw instanceof Number ? ((Number) raw).longValue() : null;
    }

    private static List<String> asStringList(Object raw) {
        if (!(raw instanceof Collection<?>))
            return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (Object item : (Collection<?>) raw) {
            String s = asTrimmedString(item);
            if (s != null)
                out.add(s);
        }
        return out;
    }

    private static Map<String, Long> asStringLongMap(Object raw) {
        if (!(raw instanceof Map<?, ?>))
            return Map.of();
        HashMap<String, Long> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (e == null)
                continue;
            String key = asTrimmedString(e.getKey());
            Long val = asLong(e.getValue());
            if (key != null && val != null) {
                out.put(key, val);
            }
        }
        return out;
    }

    private static long currentSpawnSlotStart(long epochMillis) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(zone);
        ZonedDateTime spawn = now.toLocalDate().atTime(PRIVATE_PROMPT_DAILY_SPAWN_HOUR, 0).atZone(zone);
        if (now.isBefore(spawn)) {
            spawn = spawn.minusDays(1);
        }
        return spawn.toInstant().toEpochMilli();
    }

    private static long nextSpawnAfter(long epochMillis) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime at = Instant.ofEpochMilli(epochMillis).atZone(zone);
        ZonedDateTime spawn = at.toLocalDate().atTime(PRIVATE_PROMPT_DAILY_SPAWN_HOUR, 0).atZone(zone);
        if (!at.isBefore(spawn)) {
            spawn = spawn.plusDays(1);
        }
        return spawn.toInstant().toEpochMilli();
    }

    /**
     * Daily private prompts are slot-based (server time, 8pm default):
     * - first prompt can be scheduled immediately
     * - each later prompt requires the previous scheduled prompt to be answered
     * - answer must happen before the current slot opens, otherwise wait for next slot
     */
    private static boolean canScheduleForCurrentSlot(Long lastScheduledAt, Long lastAnsweredAt, long now) {
        if (lastScheduledAt == null) {
            return true;
        }
        long slotStart = currentSpawnSlotStart(now);
        if (lastScheduledAt >= slotStart) {
            return false;
        }
        long nextSlotAfterLast = nextSpawnAfter(lastScheduledAt);
        if (now < nextSlotAfterLast) {
            return false;
        }
        if (lastAnsweredAt == null || lastAnsweredAt.longValue() < lastScheduledAt.longValue()) {
            return false;
        }
        return lastAnsweredAt.longValue() < slotStart;
    }

    private static String clampPrivatePromptBody(String body) {
        return clampPromptText(body, PRIVATE_PROMPT_BODY_LIMIT);
    }

    private static List<String> clampConversationLines(Collection<String> lines, int maxLines, int maxLineChars) {
        if (lines == null || lines.isEmpty())
            return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String raw : lines) {
            if (raw == null)
                continue;
            String line = raw.trim();
            if (line.isEmpty())
                continue;
            if (line.length() > maxLineChars) {
                line = line.substring(0, maxLineChars);
            }
            out.add(line);
            if (out.size() >= maxLines)
                break;
        }
        return out;
    }

    private static String stripConversationPrefix(String line) {
        if (line == null)
            return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty())
            return null;
        int colon = trimmed.indexOf(':');
        if (colon < 0)
            return trimmed;
        String prefix = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        if ("user".equals(prefix) || "agent".equals(prefix)) {
            String remainder = trimmed.substring(colon + 1).trim();
            return remainder.isEmpty() ? null : remainder;
        }
        return trimmed;
    }

    private static String bodyFromConversation(List<String> conversationLines) {
        if (conversationLines == null || conversationLines.isEmpty())
            return null;
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : conversationLines) {
            if (line == null)
                continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            int colon = trimmed.indexOf(':');
            if (colon < 0)
                continue;
            String prefix = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!"user".equals(prefix))
                continue;
            String content = stripConversationPrefix(trimmed);
            if (content != null)
                joiner.add(content);
        }
        String merged = joiner.toString();
        return merged.isBlank() ? null : clampPrivatePromptBody(merged);
    }

    private static boolean isMutablePrivatePromptStatus(PrivatePromptStatus status) {
        return status == PrivatePromptStatus.ACTIVE || status == PrivatePromptStatus.SNOOZED;
    }

    private static Map<String, Object> withAdditionalSkippedPrompt(Map<String, Object> state, String promptId,
            long skippedAt) {
        HashMap<String, Object> out = new HashMap<>();
        if (state != null) {
            out.putAll(state);
        }
        if (promptId != null) {
            HashMap<String, Long> skippedAtById = new HashMap<>(
                    asStringLongMap(state == null ? null : state.get("skippedPromptIdToLastSkippedAt")));
            skippedAtById.put(promptId, skippedAt);
            out.put("skippedPromptIdToLastSkippedAt", skippedAtById);
        }
        return out;
    }

    private CompletableFuture<Map<String, Object>> readPrivatePromptSchedulerState(long accountId) {
        return getPrivatePromptSchedulerState.invokeAsync(accountId, accountId)
                .thenApply(state -> state == null ? new HashMap<>() : new HashMap<>(state));
    }

    private CompletableFuture<ActivePrivatePrompt> hydrateActivePrivatePrompt(PrivatePromptAssignment assignment) {
        if (assignment == null)
            return CompletableFuture.completedFuture(null);
        String promptId = assignment.getPromptId();
        PromptDefinition prompt = PromptLibrary.getById(promptId);
        if (prompt == null || prompt.getBank() != PromptBankKind.PRIVATE) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown private prompt: " + promptId));
        }
        return getPrivatePromptAnswerByInstanceId.invokeAsync(assignment.getInstanceId()).thenApply(answer -> {
            ActivePrivatePrompt out = new ActivePrivatePrompt();
            out.setAssignment(new PrivatePromptAssignment(assignment));
            out.setPrompt(new PromptDefinition(prompt));
            if (answer != null) {
                out.setAnswer(new PrivatePromptAnswer(answer));
            }
            return out;
        });
    }

    private String pickNextPrivatePromptId(long accountId, Set<String> answeredPromptIds,
            Map<String, Long> skippedAtById, Set<String> temporarilyExcludedPromptIds, long now) {
        List<PromptDefinition> bank = PromptLibrary.privateBank();
        if (bank == null || bank.isEmpty())
            return null;
        ArrayList<String> eligiblePromptIds = new ArrayList<>();
        for (PromptDefinition def : bank) {
            if (def == null || def.getPromptId() == null)
                continue;
            String promptId = def.getPromptId();
            if (answeredPromptIds != null && answeredPromptIds.contains(promptId))
                continue;
            if (temporarilyExcludedPromptIds != null && temporarilyExcludedPromptIds.contains(promptId))
                continue;
            Long skippedAt = skippedAtById == null ? null : skippedAtById.get(promptId);
            if (skippedAt != null && (now - skippedAt) < PRIVATE_PROMPT_SKIP_COOLDOWN_MS)
                continue;
            eligiblePromptIds.add(promptId);
        }
        if (eligiblePromptIds.isEmpty())
            return null;
        long seed = System.nanoTime() ^ now ^ accountId ^ eligiblePromptIds.size();
        Collections.shuffle(eligiblePromptIds, new Random(seed));
        return eligiblePromptIds.get(0);
    }

    private CompletableFuture<ActivePrivatePrompt> scheduleNextPrivatePrompt(
            long accountId,
            Map<String, Object> state,
            long now,
            boolean ignoreServerDayLimit,
            Set<String> temporarilyExcludedPromptIds) {
        Long lastScheduledAt = asLong(state == null ? null : state.get("lastScheduledAt"));
        Long lastAnsweredAt = asLong(state == null ? null : state.get("lastAnsweredAt"));
        if (!ignoreServerDayLimit && !canScheduleForCurrentSlot(lastScheduledAt, lastAnsweredAt, now)) {
            return CompletableFuture.completedFuture(null);
        }

        Set<String> answeredPromptIds = new LinkedHashSet<>(asStringList(state == null ? null : state.get("answeredPromptIds")));
        Map<String, Long> skippedAtById = asStringLongMap(
                state == null ? null : state.get("skippedPromptIdToLastSkippedAt"));
        String nextPromptId = pickNextPrivatePromptId(accountId, answeredPromptIds, skippedAtById,
                temporarilyExcludedPromptIds, now);
        if (nextPromptId == null) {
            return CompletableFuture.completedFuture(null);
        }

        PrivatePromptAssignment assignment = new PrivatePromptAssignment();
        assignment.setInstanceId(UUID.randomUUID().toString());
        assignment.setAccountId(accountId);
        assignment.setPromptId(nextPromptId);
        assignment.setScheduledAt(now);
        assignment.setSurfacedAt(now);
        assignment.setStatus(PrivatePromptStatus.ACTIVE);

        return privatePromptAssignmentDepot.appendAsync(assignment)
                .thenCompose(v -> hydrateActivePrivatePrompt(assignment));
    }

    private CompletableFuture<ActivePrivatePrompt> scheduleNextPrivatePrompt(long accountId, Map<String, Object> state,
            long now) {
        return scheduleNextPrivatePrompt(accountId, state, now, false, Collections.emptySet());
    }

    private CompletableFuture<ActivePrivatePrompt> ensureActivePrivatePromptInternal(long accountId) {
        long now = System.currentTimeMillis();
        return readPrivatePromptSchedulerState(accountId).thenCompose(state -> {
            String activeInstanceId = asTrimmedString(state.get("activeInstanceId"));
            if (activeInstanceId != null) {
                return getPrivatePromptAssignmentByInstanceId.invokeAsync(activeInstanceId).thenCompose(assignment -> {
                    if (assignment == null || assignment.getAccountId() != accountId) {
                        return scheduleNextPrivatePrompt(accountId, state, now);
                    }
                    PrivatePromptStatus status = assignment.getStatus();
                    if (status == PrivatePromptStatus.ACTIVE) {
                        return hydrateActivePrivatePrompt(assignment);
                    }
                    if (status == PrivatePromptStatus.SNOOZED) {
                        long snoozeUntil = assignment.isSetSnoozeUntil() ? assignment.getSnoozeUntil() : 0L;
                        if (snoozeUntil > now) {
                            return CompletableFuture.completedFuture(null);
                        }
                        PrivatePromptAssignment resumed = new PrivatePromptAssignment(assignment);
                        resumed.setStatus(PrivatePromptStatus.ACTIVE);
                        resumed.setSurfacedAt(now);
                        if (resumed.isSetSnoozeUntil())
                            resumed.unsetSnoozeUntil();
                        return privatePromptAssignmentDepot.appendAsync(resumed)
                                .thenCompose(v -> hydrateActivePrivatePrompt(resumed));
                    }
                    return scheduleNextPrivatePrompt(accountId, state, now);
                });
            }
            return scheduleNextPrivatePrompt(accountId, state, now);
        });
    }

    private CompletableFuture<PrivatePromptAssignment> requireMutablePrivatePromptAssignment(long accountId,
            String instanceId) {
        String normalizedInstanceId = asTrimmedString(instanceId);
        if (normalizedInstanceId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("instanceId required."));
        }
        return getPrivatePromptAssignmentByInstanceId.invokeAsync(normalizedInstanceId).thenCompose(assignment -> {
            if (assignment == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown private prompt instance."));
            }
            if (assignment.getAccountId() != accountId) {
                return CompletableFuture.failedFuture(new SecurityException("Forbidden"));
            }
            if (!PromptLibrary.isPrivatePromptId(assignment.getPromptId())) {
                return CompletableFuture
                        .failedFuture(new IllegalArgumentException("Unknown private prompt: " + assignment.getPromptId()));
            }
            if (!isMutablePrivatePromptStatus(assignment.getStatus())) {
                return CompletableFuture.failedFuture(new IllegalStateException("Private prompt is not active."));
            }
            return CompletableFuture.completedFuture(assignment);
        });
    }

    public CompletableFuture<ActivePrivatePrompt> getActivePrivatePrompt(long accountId) {
        return serializePrivatePromptOp(accountId, () -> ensureActivePrivatePromptInternal(accountId));
    }

    public CompletableFuture<ActivePrivatePrompt> ensureActivePrivatePrompt(long accountId) {
        return serializePrivatePromptOp(accountId, () -> ensureActivePrivatePromptInternal(accountId));
    }

    public CompletableFuture<GetPrivatePromptChatTurn> postPrivatePromptChatTurn(
            long accountId,
            String instanceId,
            String questionPart,
            String userMessage,
            List<String> conversationLines) {
        String normalizedQuestionPart = clampPromptText(questionPart, 320);
        String normalizedUserMessage = clampPromptText(userMessage, 800);
        if (normalizedUserMessage == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Message text required."));
        }
        List<String> normalizedConversation = clampConversationLines(conversationLines, 24, 320);
        return serializePrivatePromptOp(accountId, () -> requireMutablePrivatePromptAssignment(accountId, instanceId)
                .thenCompose(current -> {
                    PromptDefinition prompt = PromptLibrary.getById(current.getPromptId());
                    if (prompt == null || prompt.getBank() != PromptBankKind.PRIVATE) {
                        return CompletableFuture
                                .failedFuture(new IllegalArgumentException("Unknown private prompt: " + current.getPromptId()));
                    }
                    String effectivePart = normalizedQuestionPart == null ? prompt.getText() : normalizedQuestionPart;
                    PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                            prompt.getText(),
                            effectivePart,
                            normalizedConversation,
                            normalizedUserMessage);
                    return CompletableFuture.supplyAsync(() -> PrivatePromptTurnResponder.generate(openAI, input))
                            .thenApply(result -> new GetPrivatePromptChatTurn(
                                    result == null ? null : result.agentMessage,
                                    result != null && result.needsMoreDetail));
                }));
    }

    public CompletableFuture<ActivePrivatePrompt> postPrivatePromptAnswer(long accountId, String instanceId, String body) {
        return postPrivatePromptAnswer(accountId, instanceId, body, List.of());
    }

    public CompletableFuture<ActivePrivatePrompt> postPrivatePromptAnswer(long accountId, String instanceId, String body,
            List<String> conversationLines) {
        List<String> normalizedConversation = clampConversationLines(conversationLines, 40, 320);
        String bodyCandidate = clampPrivatePromptBody(body);
        if (bodyCandidate == null) {
            bodyCandidate = bodyFromConversation(normalizedConversation);
        }
        if (bodyCandidate == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Answer body required."));
        }
        final String normalizedBody = bodyCandidate;
        return serializePrivatePromptOp(accountId, () -> requireMutablePrivatePromptAssignment(accountId, instanceId)
                .thenCompose(current -> {
                    long now = System.currentTimeMillis();
                    PromptDefinition prompt = PromptLibrary.getById(current.getPromptId());
                    if (prompt == null || prompt.getBank() != PromptBankKind.PRIVATE) {
                        return CompletableFuture
                                .failedFuture(new IllegalArgumentException("Unknown private prompt: " + current.getPromptId()));
                    }

                    PrivatePromptAnswer answer = new PrivatePromptAnswer();
                    answer.setInstanceId(current.getInstanceId());
                    answer.setAccountId(accountId);
                    answer.setPromptId(current.getPromptId());
                    answer.setBody(normalizedBody);
                    answer.setAnsweredAt(now);

                    CompletableFuture<List<String>> signalTokensFuture = extractAndAppendSignalsFromPrompt(
                            accountId,
                            prompt.getText(),
                            normalizedBody,
                            normalizedConversation,
                            "private_prompt",
                            current.getInstanceId()).exceptionally(ex -> {
                                LOG.warn("Signal extraction failed for private prompt answer {}", current.getInstanceId(),
                                        ex);
                                return List.of();
                            });

                    return signalTokensFuture.thenCompose(tokens -> {
                        if (tokens != null && !tokens.isEmpty()) {
                            answer.setSignalTokens(tokens);
                        }
                        PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                        updated.setStatus(PrivatePromptStatus.ANSWERED);
                        updated.setCompletedAt(now);
                        if (updated.isSetSnoozeUntil()) {
                            updated.unsetSnoozeUntil();
                        }
                        return privatePromptAnswerDepot.appendAsync(answer)
                                .thenCompose(v -> privatePromptAssignmentDepot.appendAsync(updated))
                                .thenCompose(v -> hydrateActivePrivatePrompt(updated));
                    });
                }));
    }

    public CompletableFuture<Boolean> postPrivatePromptSkip(long accountId, String instanceId) {
        return serializePrivatePromptOp(accountId, () -> requireMutablePrivatePromptAssignment(accountId, instanceId)
                .thenCompose(current -> {
                    PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                    updated.setStatus(PrivatePromptStatus.SKIPPED);
                    updated.setCompletedAt(System.currentTimeMillis());
                    if (updated.isSetSnoozeUntil()) {
                        updated.unsetSnoozeUntil();
                    }
                    return privatePromptAssignmentDepot.appendAsync(updated).thenApply(v -> true);
                }));
    }

    public CompletableFuture<Boolean> postPrivatePromptSnooze(long accountId, String instanceId, Long snoozeUntilMaybe) {
        return serializePrivatePromptOp(accountId, () -> requireMutablePrivatePromptAssignment(accountId, instanceId)
                .thenCompose(current -> {
                    long now = System.currentTimeMillis();
                    long snoozeUntil = (snoozeUntilMaybe != null && snoozeUntilMaybe.longValue() > now)
                            ? snoozeUntilMaybe.longValue()
                            : now + PRIVATE_PROMPT_DEFAULT_SNOOZE_MS;
                    PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                    updated.setStatus(PrivatePromptStatus.SNOOZED);
                    updated.setSnoozeUntil(snoozeUntil);
                    if (!updated.isSetSurfacedAt()) {
                        updated.setSurfacedAt(now);
                    }
                    return privatePromptAssignmentDepot.appendAsync(updated).thenApply(v -> true);
                }));
    }

    /**
     * Temporary testing helper that retires the current active private prompt (if any)
     * and schedules a new eligible one immediately.
     */
    public CompletableFuture<ActivePrivatePrompt> debugSummonNextPrivatePrompt(long accountId) {
        return serializePrivatePromptOp(accountId, () -> {
            long now = System.currentTimeMillis();
            return readPrivatePromptSchedulerState(accountId).thenCompose(state -> {
                String activeInstanceId = asTrimmedString(state.get("activeInstanceId"));
                if (activeInstanceId == null) {
                    return scheduleNextPrivatePrompt(accountId, state, now, true, Collections.emptySet());
                }
                return getPrivatePromptAssignmentByInstanceId.invokeAsync(activeInstanceId).thenCompose(current -> {
                    if (current == null
                            || current.getAccountId() != accountId
                            || !isMutablePrivatePromptStatus(current.getStatus())) {
                        return scheduleNextPrivatePrompt(accountId, state, now, true, Collections.emptySet());
                    }
                    PrivatePromptAssignment retired = new PrivatePromptAssignment(current);
                    retired.setStatus(PrivatePromptStatus.SKIPPED);
                    retired.setCompletedAt(now);
                    if (retired.isSetSnoozeUntil()) {
                        retired.unsetSnoozeUntil();
                    }
                    Map<String, Object> adjustedState = withAdditionalSkippedPrompt(state, current.getPromptId(), now);
                    Set<String> excludedPromptIds = current.getPromptId() == null
                            ? Collections.emptySet()
                            : Collections.singleton(current.getPromptId());
                    return privatePromptAssignmentDepot.appendAsync(retired)
                            .thenCompose(v -> scheduleNextPrivatePrompt(accountId, adjustedState, now, true,
                                    excludedPromptIds));
                });
            });
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

    public CompletableFuture<PublicPromptAnswer> postPublicPromptAnswer(long accountId, String promptId, String body) {
        if (!PromptLibrary.isPublicPromptId(promptId))
            throw new IllegalArgumentException("Unknown public prompt: " + promptId);
        String promptText = PromptLibrary.publicTextById(promptId);
        if (promptText == null)
            throw new IllegalArgumentException("Unknown public prompt: " + promptId);
        String normalized = clampPromptText(body, 800);
        if (normalized == null)
            throw new IllegalArgumentException("Answer body required.");
        long now = System.currentTimeMillis();
        PublicPromptAnswer base = new PublicPromptAnswer();
        base.setAnswerId(UUID.randomUUID().toString());
        base.setAccountId(accountId);
        base.setPromptId(promptId);
        base.setBody(normalized);
        base.setCreatedAt(now);
        base.setUpdatedAt(now);

        CompletableFuture<List<String>> signals = extractAndAppendSignalsFromPrompt(accountId, promptText, normalized,
                "public_prompt", base.getAnswerId())
                .exceptionally(ex -> {
                    LOG.warn("Signal extraction failed for public prompt answer {}", base.getAnswerId(), ex);
                    return List.of();
                });

        return signals.thenCompose(tokens -> {
            PublicPromptAnswer stored = new PublicPromptAnswer(base);
            if (tokens != null && !tokens.isEmpty())
                stored.setSignalTokens(tokens);
            return publicPromptAnswerDepot.appendAsync(stored)
                    .thenApply(res -> new PublicPromptAnswer(stored));
        });
    }

    public CompletableFuture<List<PublicPromptFeedCard>> getPublicPromptFeed(long accountId, int limit) {
        int clamped = Math.max(1, Math.min(50, limit));
        return getPublicPromptFeed.invokeAsync(accountId, clamped).thenApply(answers -> {
            if (answers == null || answers.isEmpty())
                return List.of();
            List<PublicPromptFeedCard> cards = new ArrayList<>();
            for (PublicPromptAnswer ans : answers) {
                if (ans == null)
                    continue;
                String promptText = PromptLibrary.publicTextById(ans.getPromptId());
                if (promptText == null)
                    continue;
                PublicPromptFeedCard card = new PublicPromptFeedCard();
                card.setAnswerId(ans.getAnswerId());
                card.setPromptId(ans.getPromptId());
                card.setPromptText(promptText);
                card.setBody(ans.getBody());
                card.setCreatedAt(ans.getCreatedAt());
                cards.add(card);
            }
            return cards;
        });
    }

    public CompletableFuture<List<PublicPromptAnswer>> getMyPublicPromptAnswers(long accountId) {
        return getMyPublicPromptAnswers.invokeAsync(accountId, accountId)
                .thenApply(list -> list == null ? List.of() : list);
    }

    public CompletableFuture<Boolean> postPublicPromptReaction(long viewerId, String answerId,
            PromptReaction reaction) {
        if (reaction == null)
            throw new IllegalArgumentException("Reaction required.");
        return getPublicPromptAnswerById.invokeAsync(answerId).thenCompose(answer -> {
            if (answer == null)
                throw new IllegalArgumentException("Unknown answer: " + answerId);
            PublicPromptReactionEvent event = new PublicPromptReactionEvent();
            event.setViewerAccountId(viewerId);
            event.setAnswerId(answerId);
            event.setPromptId(answer.getPromptId());
            event.setReaction(reaction);
            event.setReactedAt(System.currentTimeMillis());
            CompletableFuture<Void> persist = publicPromptReactionDepot.appendAsync(event).thenApply(res -> null);
            if (reaction == PromptReaction.LIKE || reaction == PromptReaction.DISLIKE) {
                String promptText = PromptLibrary.publicTextById(answer.getPromptId());
                if (promptText != null) {
                    return persist.thenCompose(v -> extractAndAppendSignalsFromPrompt(viewerId, promptText,
                            answer.getBody(), "public_prompt_reaction", answerId))
                            .thenApply(tokens -> true);
                }
            }
            return persist.thenApply(v -> true);
        });
    }

    public CompletableFuture<PublicPromptSelection> getPublicPromptSelection(long accountId) {
        return getPublicPromptSelection.invokeAsync(accountId, accountId);
    }

    public CompletableFuture<PublicPromptSelection> postPublicPromptSelection(long accountId,
            List<String> selectedPromptIds) {
        List<String> normalized = new ArrayList<>();
        if (selectedPromptIds != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String promptId : selectedPromptIds) {
                if (promptId == null)
                    continue;
                String trimmed = promptId.trim();
                if (trimmed.isEmpty())
                    continue;
                if (!PromptLibrary.isPublicPromptId(trimmed))
                    throw new IllegalArgumentException("Unknown public prompt: " + trimmed);
                if (seen.add(trimmed))
                    normalized.add(trimmed);
            }
        }
        if (normalized.size() > 12)
            throw new IllegalArgumentException("Too many selected prompts.");
        PublicPromptSelection selection = new PublicPromptSelection();
        selection.setAccountId(accountId);
        selection.setSelectedPromptIds(normalized);
        selection.setUpdatedAt(System.currentTimeMillis());
        return publicPromptSelectionDepot.appendAsync(selection)
                .thenApply(res -> new PublicPromptSelection(selection));
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
        return extractSignalsFromPrompt(question, answer, List.of());
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromPrompt(String question, String answer,
            List<String> conversationLines) {
        return CompletableFuture.supplyAsync(
                () -> SignalExtractor.extractFromPromptAnswer(openAI, question, answer, conversationLines, Set.of()));
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
        return extractAndAppendSignalsFromPrompt(accountId, question, answer, List.of(), source, sourceId);
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String question,
            String answer, List<String> conversationLines, String source, String sourceId) {
        List<String> normalizedConversation = clampConversationLines(conversationLines, 40, 320);
        final String context = normalizedConversation.isEmpty() ? answer : String.join(" | ", normalizedConversation);
        return extractSignalsFromPrompt(question, answer, normalizedConversation).thenCompose(signals -> {
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, sourceId, context).thenApply(ok -> tokens);
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
