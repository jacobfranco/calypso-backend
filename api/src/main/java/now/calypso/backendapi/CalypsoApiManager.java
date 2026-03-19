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
import java.net.URLDecoder;
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
    private final ConcurrentHashMap<Long, CompletableFuture<?>> matchmakingFollowupSerialByAccount = new ConcurrentHashMap<>();

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
    private final Depot matchmakingFollowupAssignmentDepot;
    private final Depot matchmakingFollowupAnswerDepot;

    // Core PStates
    private final PState phoneToUser;
    private final PState authCodeToAccountId;
    private final PState accountIdToCandidateHeap;
    private final PState viewerIdToTargetIdToFacecardReaction;
    private final PState viewerIdToTargetIdToPromptLikeSeen;
    private final PState viewerIdToReactionByAnswerId;
    private final PState answerIdToPublicPromptAnswer;
    private final PState targetIdToFollowupByViewer;

    // Core Queries
    private final QueryTopologyClient<List<AccountWithId>> getAccountsFromAccountIds;
    private final QueryTopologyClient<Application> getApplicationFromClientId;
    private final QueryTopologyClient<PublicPromptAnswer> getPublicPromptAnswerById;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getPublicPromptFeed;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getMyPublicPromptAnswers;
    private final QueryTopologyClient<PublicPromptSelection> getPublicPromptSelection;
    private final QueryTopologyClient<List<Map<String, Object>>> getMatchmakingFollowupCandidatesForTarget;
    private final QueryTopologyClient<PrivatePromptAssignment> getMatchmakingFollowupAssignmentByInstanceId;
    private final QueryTopologyClient<PrivatePromptAnswer> getMatchmakingFollowupAnswerByInstanceId;
    private final QueryTopologyClient<PrivatePromptAssignment> getActiveMatchmakingFollowupAssignment;
    private final QueryTopologyClient<Map<String, Object>> getMatchmakingFollowupSchedulerState;

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
    private static final long MATCHMAKING_FOLLOWUP_COOLDOWN_MS = 24L * 60 * 60 * 1000;
    private static final int MATCHMAKING_FOLLOWUP_DEFAULT_LIMIT = 20;
    private static final String MATCHMAKING_FOLLOWUP_PROMPT_ID = "private.matchmaking.followup";
    private static final String MATCHMAKING_FOLLOWUP_PROMPT_PREFIX = MATCHMAKING_FOLLOWUP_PROMPT_ID + "|";
    private static final String FACECARD_REACTION_ANSWER_PREFIX = "facecard_target:";
    private static final double MATCH_MIN_EXPLORATORY = 58.0;
    private static final double MATCH_MIN_BALANCED = 64.0;
    private static final double MATCH_MIN_FOCUSED = 72.0;
    private static final double MATCH_AUTOPASS_EXPLORATORY = 66.0;
    private static final double MATCH_AUTOPASS_BALANCED = 72.0;
    private static final double MATCH_AUTOPASS_FOCUSED = 80.0;
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
        matchmakingFollowupAssignmentDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*matchmakingFollowupAssignmentDepot");
        matchmakingFollowupAnswerDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*matchmakingFollowupAnswerDepot");

        // Core PStates
        phoneToUser = cluster.clusterPState(CORE_MODULE_NAME, "$$phoneToUser");
        authCodeToAccountId = cluster.clusterPState(CORE_MODULE_NAME, "$$authCodeToAccountId");
        accountIdToCandidateHeap = cluster.clusterPState(CORE_MODULE_NAME, "$$accountIdToCandidateHeap");
        viewerIdToTargetIdToFacecardReaction = cluster.clusterPState(CORE_MODULE_NAME,
                "$$viewerIdToTargetIdToFacecardReaction");
        viewerIdToTargetIdToPromptLikeSeen = cluster.clusterPState(CORE_MODULE_NAME,
                "$$viewerIdToTargetIdToPromptLikeSeen");
        viewerIdToReactionByAnswerId = cluster.clusterPState(CORE_MODULE_NAME, "$$viewerIdToReactionByAnswerId");
        answerIdToPublicPromptAnswer = cluster.clusterPState(CORE_MODULE_NAME, "$$answerIdToPublicPromptAnswer");
        targetIdToFollowupByViewer = cluster.clusterPState(CORE_MODULE_NAME, "$$targetIdToFollowupByViewer");

        // Core Queries
        getAccountsFromAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getAccountsFromAccountIds");
        getApplicationFromClientId = cluster.clusterQuery(CORE_MODULE_NAME, "getApplicationFromClientId");
        getPublicPromptAnswerById = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptAnswerById");
        getPublicPromptFeed = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptFeed");
        getMyPublicPromptAnswers = cluster.clusterQuery(CORE_MODULE_NAME, "getMyPublicPromptAnswers");
        getPublicPromptSelection = cluster.clusterQuery(CORE_MODULE_NAME, "getPublicPromptSelection");
        getMatchmakingFollowupCandidatesForTarget = cluster.clusterQuery(CORE_MODULE_NAME,
                "getMatchmakingFollowupCandidatesForTarget");
        getMatchmakingFollowupAssignmentByInstanceId = cluster.clusterQuery(CORE_MODULE_NAME,
                "getMatchmakingFollowupAssignmentByInstanceId");
        getMatchmakingFollowupAnswerByInstanceId = cluster.clusterQuery(CORE_MODULE_NAME,
                "getMatchmakingFollowupAnswerByInstanceId");
        getActiveMatchmakingFollowupAssignment = cluster.clusterQuery(CORE_MODULE_NAME,
                "getActiveMatchmakingFollowupAssignment");
        getMatchmakingFollowupSchedulerState = cluster.clusterQuery(CORE_MODULE_NAME,
                "getMatchmakingFollowupSchedulerState");

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

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> serializeMatchmakingFollowupOp(long accountId, Supplier<CompletableFuture<T>> op) {
        return (CompletableFuture<T>) matchmakingFollowupSerialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null)
                    ? CompletableFuture.completedFuture(null)
                    : ((CompletableFuture<?>) prev).handle((ignored, err) -> null);
            CompletableFuture<T> next = start.thenCompose(v -> op.get());
            next.whenComplete((r, e) -> matchmakingFollowupSerialByAccount.remove(k, next));
            return next;
        });
    }

    private static String asTrimmedString(Object raw) {
        if (raw == null)
            return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String encodeFacecardReactionAnswerId(long targetAccountId) {
        return FACECARD_REACTION_ANSWER_PREFIX + targetAccountId;
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

    private static boolean isMatchmakingFollowupPrompt(String promptId) {
        return promptId != null && promptId.startsWith(MATCHMAKING_FOLLOWUP_PROMPT_PREFIX);
    }

    private static String encodeMatchmakingFollowupPromptId(long viewerId, String missingToken, double pairScore,
            double uncertainty) {
        String token = SignalNormalizer.normalizeOne(missingToken);
        if (token == null) {
            token = "unknown";
        }
        return MATCHMAKING_FOLLOWUP_PROMPT_PREFIX
                + "viewer=" + viewerId
                + "&token=" + urlEncode(token)
                + "&score=" + String.format(Locale.ROOT, "%.3f", pairScore)
                + "&unc=" + String.format(Locale.ROOT, "%.3f", uncertainty);
    }

    private static Map<String, String> parseMatchmakingFollowupPromptFields(String promptId) {
        if (!isMatchmakingFollowupPrompt(promptId)) {
            return Map.of();
        }
        String body = promptId.substring(MATCHMAKING_FOLLOWUP_PROMPT_PREFIX.length());
        if (body.isBlank()) {
            return Map.of();
        }
        HashMap<String, String> out = new HashMap<>();
        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx <= 0 || idx >= pair.length() - 1) {
                continue;
            }
            String key = pair.substring(0, idx).trim();
            String rawValue = pair.substring(idx + 1);
            if (key.isEmpty() || rawValue.isEmpty()) {
                continue;
            }
            out.put(key, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String humanizeSignalToken(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null) {
            return "that";
        }
        String phrase = normalized.replace("anti_", "not_").replace('_', ' ').trim();
        if (phrase.isBlank()) {
            return "that";
        }
        return phrase;
    }

    private static String buildMatchmakingFollowupQuestion(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null) {
            return "Quick matchmaking check: can you share a little more about your preferences here?";
        }
        if (normalized.startsWith("anti_")) {
            String phrase = humanizeSignalToken(normalized.substring("anti_".length()));
            return "Quick matchmaking check: how do you feel about " + phrase + " in a partner?";
        }
        String phrase = humanizeSignalToken(normalized);
        return "Quick matchmaking check: how important is " + phrase + " in your lifestyle or dating preferences?";
    }

    private static PromptDefinition matchmakingFollowupPromptDefinition(String questionText) {
        PromptDefinition prompt = new PromptDefinition();
        prompt.setPromptId(MATCHMAKING_FOLLOWUP_PROMPT_ID);
        prompt.setBank(PromptBankKind.PRIVATE);
        prompt.setText(questionText);
        prompt.setTopic("private");
        prompt.setTags(List.of("agent", "matchmaking_followup"));
        return prompt;
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

    private CompletableFuture<Map<String, Object>> readMatchmakingFollowupSchedulerState(long accountId) {
        return getMatchmakingFollowupSchedulerState.invokeAsync(accountId, accountId)
                .thenApply(state -> state == null ? new HashMap<>() : new HashMap<>(state));
    }

    private CompletableFuture<ActivePrivatePrompt> hydrateMatchmakingFollowup(PrivatePromptAssignment assignment) {
        if (assignment == null) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> fields = parseMatchmakingFollowupPromptFields(assignment.getPromptId());
        String questionText = buildMatchmakingFollowupQuestion(fields.get("token"));
        return getMatchmakingFollowupAnswerByInstanceId.invokeAsync(assignment.getInstanceId()).thenApply(answer -> {
            ActivePrivatePrompt out = new ActivePrivatePrompt();
            PrivatePromptAssignment sanitizedAssignment = new PrivatePromptAssignment(assignment);
            sanitizedAssignment.setPromptId(MATCHMAKING_FOLLOWUP_PROMPT_ID);
            out.setAssignment(sanitizedAssignment);
            out.setPrompt(matchmakingFollowupPromptDefinition(questionText));
            if (answer != null) {
                PrivatePromptAnswer sanitizedAnswer = new PrivatePromptAnswer(answer);
                sanitizedAnswer.setPromptId(MATCHMAKING_FOLLOWUP_PROMPT_ID);
                out.setAnswer(sanitizedAnswer);
            }
            return out;
        });
    }

    private CompletableFuture<PrivatePromptAssignment> requireMutableMatchmakingFollowupAssignment(long accountId,
            String instanceId) {
        String normalizedInstanceId = asTrimmedString(instanceId);
        if (normalizedInstanceId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("instanceId required."));
        }
        return getMatchmakingFollowupAssignmentByInstanceId.invokeAsync(normalizedInstanceId).thenCompose(assignment -> {
            if (assignment == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unknown matchmaking followup instance."));
            }
            if (assignment.getAccountId() != accountId) {
                return CompletableFuture.failedFuture(new SecurityException("Forbidden"));
            }
            if (!isMatchmakingFollowupPrompt(assignment.getPromptId())) {
                return CompletableFuture
                        .failedFuture(new IllegalArgumentException("Unknown matchmaking followup prompt."));
            }
            if (!isMutablePrivatePromptStatus(assignment.getStatus())) {
                return CompletableFuture.failedFuture(new IllegalStateException("Matchmaking followup is not active."));
            }
            return CompletableFuture.completedFuture(assignment);
        });
    }

    private CompletableFuture<ActivePrivatePrompt> scheduleNextMatchmakingFollowup(long accountId,
            Map<String, Object> state,
            long now,
            boolean ignoreCooldown) {
        Long lastScheduledAt = asLong(state == null ? null : state.get("lastScheduledAt"));
        if (!ignoreCooldown && lastScheduledAt != null
                && (now - lastScheduledAt.longValue()) < MATCHMAKING_FOLLOWUP_COOLDOWN_MS) {
            return CompletableFuture.completedFuture(null);
        }
        return getMatchmakingFollowupCandidatesForTarget
                .invokeAsync(accountId, MATCHMAKING_FOLLOWUP_DEFAULT_LIMIT)
                .thenCompose(candidates -> {
                    if (candidates == null || candidates.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    Map<String, Object> picked = null;
                    for (Map<String, Object> cand : candidates) {
                        if (cand == null || cand.isEmpty()) {
                            continue;
                        }
                        long viewerId = parseLong(asTrimmedString(cand.get("viewerId")), -1L);
                        String missingToken = asTrimmedString(cand.get("missingToken"));
                        if (viewerId >= 0L && viewerId != accountId && missingToken != null) {
                            picked = cand;
                            break;
                        }
                    }
                    if (picked == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    long viewerId = parseLong(asTrimmedString(picked.get("viewerId")), -1L);
                    String missingToken = asTrimmedString(picked.get("missingToken"));
                    double pairScore = parseDouble(asTrimmedString(picked.get("pairScore")), 0.0);
                    double uncertainty = parseDouble(asTrimmedString(picked.get("uncertainty")), 1.0);
                    String encodedPromptId = encodeMatchmakingFollowupPromptId(viewerId, missingToken, pairScore,
                            uncertainty);

                    PrivatePromptAssignment assignment = new PrivatePromptAssignment();
                    assignment.setInstanceId(UUID.randomUUID().toString());
                    assignment.setAccountId(accountId);
                    assignment.setPromptId(encodedPromptId);
                    assignment.setScheduledAt(now);
                    assignment.setSurfacedAt(now);
                    assignment.setStatus(PrivatePromptStatus.ACTIVE);

                    return matchmakingFollowupAssignmentDepot.appendAsync(assignment)
                            .thenCompose(v -> hydrateMatchmakingFollowup(assignment));
                });
    }

    private CompletableFuture<ActivePrivatePrompt> ensureActiveMatchmakingFollowupInternal(long accountId) {
        long now = System.currentTimeMillis();
        return readMatchmakingFollowupSchedulerState(accountId).thenCompose(state -> {
            String activeInstanceId = asTrimmedString(state.get("activeInstanceId"));
            if (activeInstanceId != null) {
                return getMatchmakingFollowupAssignmentByInstanceId.invokeAsync(activeInstanceId).thenCompose(assignment -> {
                    if (assignment == null || assignment.getAccountId() != accountId
                            || !isMatchmakingFollowupPrompt(assignment.getPromptId())) {
                        return scheduleNextMatchmakingFollowup(accountId, state, now, false);
                    }
                    PrivatePromptStatus status = assignment.getStatus();
                    if (status == PrivatePromptStatus.ACTIVE) {
                        return hydrateMatchmakingFollowup(assignment);
                    }
                    if (status == PrivatePromptStatus.SNOOZED) {
                        long snoozeUntil = assignment.isSetSnoozeUntil() ? assignment.getSnoozeUntil() : 0L;
                        if (snoozeUntil > now) {
                            return CompletableFuture.completedFuture(null);
                        }
                        PrivatePromptAssignment resumed = new PrivatePromptAssignment(assignment);
                        resumed.setStatus(PrivatePromptStatus.ACTIVE);
                        resumed.setSurfacedAt(now);
                        if (resumed.isSetSnoozeUntil()) {
                            resumed.unsetSnoozeUntil();
                        }
                        return matchmakingFollowupAssignmentDepot.appendAsync(resumed)
                                .thenCompose(v -> hydrateMatchmakingFollowup(resumed));
                    }
                    return scheduleNextMatchmakingFollowup(accountId, state, now, false);
                });
            }
            return scheduleNextMatchmakingFollowup(accountId, state, now, false);
        });
    }

    public CompletableFuture<ActivePrivatePrompt> getActiveMatchmakingFollowup(long accountId) {
        return serializeMatchmakingFollowupOp(accountId, () -> ensureActiveMatchmakingFollowupInternal(accountId));
    }

    public CompletableFuture<GetPrivatePromptChatTurn> postMatchmakingFollowupChatTurn(
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
        return serializeMatchmakingFollowupOp(accountId, () -> requireMutableMatchmakingFollowupAssignment(accountId,
                instanceId).thenCompose(current -> {
                    Map<String, String> fields = parseMatchmakingFollowupPromptFields(current.getPromptId());
                    String baseQuestion = buildMatchmakingFollowupQuestion(fields.get("token"));
                    String effectivePart = normalizedQuestionPart == null ? baseQuestion : normalizedQuestionPart;
                    PrivatePromptTurnResponder.TurnInput input = new PrivatePromptTurnResponder.TurnInput(
                            baseQuestion,
                            effectivePart,
                            normalizedConversation,
                            normalizedUserMessage);
                    return CompletableFuture.supplyAsync(() -> PrivatePromptTurnResponder.generate(openAI, input))
                            .thenApply(result -> new GetPrivatePromptChatTurn(
                                    result == null ? null : result.agentMessage,
                                    result != null && result.needsMoreDetail));
                }));
    }

    public CompletableFuture<ActivePrivatePrompt> postMatchmakingFollowupAnswer(long accountId, String instanceId,
            String body, List<String> conversationLines) {
        List<String> normalizedConversation = clampConversationLines(conversationLines, 40, 320);
        String bodyCandidate = clampPrivatePromptBody(body);
        if (bodyCandidate == null) {
            bodyCandidate = bodyFromConversation(normalizedConversation);
        }
        if (bodyCandidate == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Answer body required."));
        }
        final String normalizedBody = bodyCandidate;
        return serializeMatchmakingFollowupOp(accountId, () -> requireMutableMatchmakingFollowupAssignment(accountId,
                instanceId).thenCompose(current -> {
                    long now = System.currentTimeMillis();
                    Map<String, String> fields = parseMatchmakingFollowupPromptFields(current.getPromptId());
                    long viewerId = parseLong(fields.get("viewer"), -1L);
                    String question = buildMatchmakingFollowupQuestion(fields.get("token"));

                    PrivatePromptAnswer answer = new PrivatePromptAnswer();
                    answer.setInstanceId(current.getInstanceId());
                    answer.setAccountId(accountId);
                    answer.setPromptId(MATCHMAKING_FOLLOWUP_PROMPT_ID);
                    answer.setBody(normalizedBody);
                    answer.setAnsweredAt(now);

                    CompletableFuture<List<String>> signalTokensFuture = extractAndAppendSignalsFromPrompt(
                            accountId,
                            question,
                            normalizedBody,
                            normalizedConversation,
                            "matchmaking_followup",
                            current.getInstanceId()).exceptionally(ex -> {
                                LOG.warn("Signal extraction failed for matchmaking followup {}", current.getInstanceId(),
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
                        return matchmakingFollowupAnswerDepot.appendAsync(answer)
                                .thenCompose(v -> matchmakingFollowupAssignmentDepot.appendAsync(updated))
                                .thenCompose(v -> {
                                    CompletableFuture<Void> refillSelf = requestRefill(accountId, 120)
                                            .exceptionally(ex -> null);
                                    CompletableFuture<Void> refillViewer = viewerId < 0L
                                            ? CompletableFuture.completedFuture(null)
                                            : requestRefill(viewerId, 120).exceptionally(ex -> null);
                                    return CompletableFuture.allOf(refillSelf, refillViewer)
                                            .thenCompose(ignored -> hydrateMatchmakingFollowup(updated));
                                });
                    });
                }));
    }

    public CompletableFuture<ActivePrivatePrompt> postMatchmakingFollowupAnswer(long accountId, String instanceId,
            String body) {
        return postMatchmakingFollowupAnswer(accountId, instanceId, body, List.of());
    }

    public CompletableFuture<Boolean> postMatchmakingFollowupSkip(long accountId, String instanceId) {
        return serializeMatchmakingFollowupOp(accountId, () -> requireMutableMatchmakingFollowupAssignment(accountId,
                instanceId).thenCompose(current -> {
                    PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                    updated.setStatus(PrivatePromptStatus.SKIPPED);
                    updated.setCompletedAt(System.currentTimeMillis());
                    if (updated.isSetSnoozeUntil()) {
                        updated.unsetSnoozeUntil();
                    }
                    return matchmakingFollowupAssignmentDepot.appendAsync(updated).thenApply(v -> true);
                }));
    }

    public CompletableFuture<Boolean> postMatchmakingFollowupSnooze(long accountId, String instanceId,
            Long snoozeUntilMaybe) {
        return serializeMatchmakingFollowupOp(accountId, () -> requireMutableMatchmakingFollowupAssignment(accountId,
                instanceId).thenCompose(current -> {
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
                    return matchmakingFollowupAssignmentDepot.appendAsync(updated).thenApply(v -> true);
                }));
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
        int refillTarget = Math.max(80, clamped * 4);
        requestRefill(accountId, refillTarget).exceptionally(ex -> {
            LOG.warn("Failed to enqueue feed refill for account {} (target size {})", accountId, refillTarget, ex);
            return null;
        });
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
                    persist.thenCompose(v -> extractAndAppendSignalsFromPrompt(
                            viewerId,
                            promptText,
                            answer.getBody(),
                            "public_prompt_reaction",
                            answerId)).exceptionally(ex -> {
                                LOG.warn("Signal extraction failed for public prompt reaction viewer={} answer={}",
                                        viewerId, answerId, ex);
                                return List.of();
                            });
                }
            }
            return persist.thenApply(v -> true);
        });
    }

    public CompletableFuture<Boolean> postFacecardReaction(long viewerId, long targetAccountId,
            PromptReaction reaction) {
        if (reaction == null) {
            throw new IllegalArgumentException("Reaction required.");
        }
        if (targetAccountId < 0L) {
            throw new IllegalArgumentException("Target account required.");
        }
        if (viewerId == targetAccountId) {
            throw new IllegalArgumentException("Cannot react to your own facecard.");
        }

        return getAccountWithId(viewerId, targetAccountId).thenCompose(target -> {
            if (target == null || target.account == null) {
                throw new IllegalArgumentException("Unknown facecard target: " + targetAccountId);
            }

            PublicPromptReactionEvent event = new PublicPromptReactionEvent();
            event.setViewerAccountId(viewerId);
            event.setAnswerId(encodeFacecardReactionAnswerId(targetAccountId));
            event.setPromptId("facecard");
            event.setReaction(reaction);
            event.setReactedAt(System.currentTimeMillis());

            int refillTarget = 120;
            return publicPromptReactionDepot.appendAsync(event)
                    .thenCompose(res -> requestRefill(viewerId, refillTarget)
                            .exceptionally(ex -> {
                                LOG.warn("Facecard reaction recorded but refill failed for viewer {}", viewerId, ex);
                                return null;
                            }))
                    .thenApply(ignored -> true);
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

    private static int clampMatchLimit(int limit) {
        if (limit <= 0)
            return 20;
        return Math.min(100, limit);
    }

    private static double modeAwareMatchThreshold(String mode) {
        if ("focused".equalsIgnoreCase(mode))
            return MATCH_MIN_FOCUSED;
        if ("exploratory".equalsIgnoreCase(mode))
            return MATCH_MIN_EXPLORATORY;
        return MATCH_MIN_BALANCED;
    }

    private static double modeAwareAutoPassThreshold(String mode) {
        if ("focused".equalsIgnoreCase(mode))
            return MATCH_AUTOPASS_FOCUSED;
        if ("exploratory".equalsIgnoreCase(mode))
            return MATCH_AUTOPASS_EXPLORATORY;
        return MATCH_AUTOPASS_BALANCED;
    }

    private static Double scoreFromHeap(Object rawHeap, long targetAccountId) {
        if (!(rawHeap instanceof List<?> heap))
            return null;
        for (Object entry : heap) {
            if (!(entry instanceof MatchCandidate))
                continue;
            MatchCandidate candidate = (MatchCandidate) entry;
            if (candidate.getTargetAccountId() == targetAccountId) {
                return candidate.getStage0Score();
            }
        }
        return null;
    }

    private static List<MatchCandidate> normalizeHeap(Object rawHeap, int limit) {
        if (!(rawHeap instanceof List<?> heap) || heap.isEmpty()) {
            return List.of();
        }
        List<MatchCandidate> out = new ArrayList<>();
        for (Object entry : heap) {
            if (!(entry instanceof MatchCandidate)) {
                continue;
            }
            out.add((MatchCandidate) entry);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static boolean isPromptLikeSeen(Object raw) {
        if (raw instanceof Boolean)
            return ((Boolean) raw).booleanValue();
        if (raw instanceof Number)
            return ((Number) raw).intValue() != 0;
        return false;
    }

    private static boolean isLikeReaction(Object raw) {
        if (!(raw instanceof Number))
            return false;
        return ((Number) raw).intValue() == PromptReaction.LIKE.getValue();
    }

    private static List<String> likedAnswerIds(Object rawByAnswer) {
        if (!(rawByAnswer instanceof Map<?, ?> map) || map.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String answerId) || answerId.isBlank()) {
                continue;
            }
            if (!(entry.getValue() instanceof Number reactionValue)) {
                continue;
            }
            if (reactionValue.intValue() == PromptReaction.LIKE.getValue()) {
                out.add(answerId);
            }
        }
        return out;
    }

    private static boolean isActiveFollowupPendingForPair(PrivatePromptAssignment assignment, long accountId,
            long otherAccountId) {
        if (assignment == null || assignment.getAccountId() != accountId) {
            return false;
        }
        PrivatePromptStatus status = assignment.getStatus();
        if (status != PrivatePromptStatus.ACTIVE && status != PrivatePromptStatus.SNOOZED) {
            return false;
        }
        if (!isMatchmakingFollowupPrompt(assignment.getPromptId())) {
            return false;
        }
        Map<String, String> fields = parseMatchmakingFollowupPromptFields(assignment.getPromptId());
        long viewerId = parseLong(fields.get("viewer"), -1L);
        return viewerId == otherAccountId;
    }

    private static long parseTargetAccountId(GetMatch match) {
        if (match == null || match.account == null || match.account.id == null) {
            return -1L;
        }
        try {
            return CalypsoHelpers.parseAccountId(match.account.id);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private CompletableFuture<List<GetMatch>> loadRankedCandidates(long requesterId, long viewerId, int limit,
            boolean recordExposure) {
        CompletableFuture<List<MatchCandidate>> readCandidates = getMatchesFromAccountId
                .invokeAsync(requesterId, viewerId, limit)
                .completeOnTimeout(List.of(), 5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to read ranked candidates for account {}", viewerId, ex);
                    return List.of();
                });

        return readCandidates.thenCompose(cands -> {
            List<MatchCandidate> safeCandidates = cands == null ? List.of() : cands;
            List<Long> ids = new ArrayList<>();
            for (MatchCandidate c : safeCandidates) {
                if (c == null)
                    continue;
                ids.add(c.getTargetAccountId());
            }

            if (ids.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }

            return getAccountsFromAccountIds.invokeAsync(viewerId, ids).thenCompose(accounts -> {
                List<AccountWithId> safeAccounts = accounts == null ? List.of() : accounts;
                List<GetMatch> out = new ArrayList<>(safeCandidates.size());
                List<Long> servedIds = new ArrayList<>(safeCandidates.size());
                Map<Long, MatchCandidate> byId = new HashMap<>();
                for (MatchCandidate c : safeCandidates) {
                    if (c == null)
                        continue;
                    byId.put(c.getTargetAccountId(), c);
                }

                for (AccountWithId aw : safeAccounts) {
                    if (aw == null || aw.account == null)
                        continue;
                    MatchCandidate c = byId.get(aw.accountId);
                    if (c == null)
                        continue;
                    out.add(new GetMatch(new GetAccount(aw), c.getStage0Score(), c.getComputedAt()));
                    servedIds.add(aw.accountId);
                }

                if (!recordExposure || servedIds.isEmpty()) {
                    return CompletableFuture.completedFuture(out);
                }
                ServedPairs sp = new ServedPairs();
                sp.setAccountId(viewerId);
                sp.setTargetIds(servedIds);
                sp.setServedAt(System.currentTimeMillis());
                return matchesServeDepot.appendAsync(sp).thenApply(x -> out);
            });
        });
    }

    private CompletableFuture<List<GetMatch>> loadRawRankedCandidates(long viewerId, int limit) {
        return accountIdToCandidateHeap.selectOneAsync(Path.key(viewerId))
                .completeOnTimeout(null, 5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to read raw candidate heap for account {}", viewerId, ex);
                    return null;
                })
                .thenCompose(rawHeap -> {
                    List<MatchCandidate> top = normalizeHeap(rawHeap, limit);
                    if (top.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<GetMatch>of());
                    }
                    List<Long> ids = new ArrayList<>(top.size());
                    for (MatchCandidate candidate : top) {
                        if (candidate == null) {
                            continue;
                        }
                        ids.add(candidate.getTargetAccountId());
                    }
                    if (ids.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<GetMatch>of());
                    }
                    return getAccountsFromAccountIds.invokeAsync(viewerId, ids).thenApply(accounts -> {
                        List<AccountWithId> safeAccounts = accounts == null ? List.of() : accounts;
                        Map<Long, MatchCandidate> byId = new HashMap<>();
                        for (MatchCandidate candidate : top) {
                            if (candidate == null) {
                                continue;
                            }
                            byId.put(candidate.getTargetAccountId(), candidate);
                        }
                        List<GetMatch> out = new ArrayList<>(safeAccounts.size());
                        for (AccountWithId accountWithId : safeAccounts) {
                            if (accountWithId == null || accountWithId.account == null) {
                                continue;
                            }
                            MatchCandidate candidate = byId.get(accountWithId.accountId);
                            if (candidate == null) {
                                continue;
                            }
                            out.add(new GetMatch(new GetAccount(accountWithId), candidate.getStage0Score(),
                                    candidate.getComputedAt()));
                        }
                        return out;
                    });
                });
    }

    private CompletableFuture<Boolean> hasPromptLikeThroughAnswerHistory(long viewerId, long targetId) {
        return viewerIdToReactionByAnswerId.selectOneAsync(Path.key(viewerId))
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null)
                .thenCompose(rawByAnswer -> {
                    List<String> answerIds = likedAnswerIds(rawByAnswer);
                    if (answerIds.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    List<CompletableFuture<Boolean>> checks = new ArrayList<>(answerIds.size());
                    for (String answerId : answerIds) {
                        CompletableFuture<Boolean> check = answerIdToPublicPromptAnswer.selectOneAsync(Path.key(answerId))
                                .thenApply(rawAnswer -> {
                                    if (!(rawAnswer instanceof PublicPromptAnswer answer)) {
                                        return false;
                                    }
                                    return answer.getAccountId() == targetId;
                                })
                                .completeOnTimeout(false, 1, TimeUnit.SECONDS)
                                .exceptionally(ex -> false);
                        checks.add(check);
                    }
                    CompletableFuture<Void> all = CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]));
                    return all.thenApply(v -> {
                        for (CompletableFuture<Boolean> check : checks) {
                            if (Boolean.TRUE.equals(check.join())) {
                                return true;
                            }
                        }
                        return false;
                    });
                })
                .completeOnTimeout(false, 4, TimeUnit.SECONDS)
                .exceptionally(ex -> false);
    }

    private CompletableFuture<Boolean> resolvePromptLikeEvidence(long viewerId, long targetId) {
        return viewerIdToTargetIdToPromptLikeSeen.selectOneAsync(Path.key(viewerId, targetId))
                .completeOnTimeout(false, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> false)
                .thenCompose(raw -> {
                    if (isPromptLikeSeen(raw)) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return hasPromptLikeThroughAnswerHistory(viewerId, targetId);
                })
                .completeOnTimeout(false, 5, TimeUnit.SECONDS)
                .exceptionally(ex -> false);
    }

    private CompletableFuture<GetMatch> evaluateMutualMatch(long viewerId, String viewerMode, GetMatch ranked) {
        long targetId = parseTargetAccountId(ranked);
        if (targetId < 0L || targetId == viewerId) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Double> targetToViewerScoreFuture = accountIdToCandidateHeap
                .selectOneAsync(Path.key(targetId))
                .thenApply(raw -> scoreFromHeap(raw, viewerId))
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null);

        CompletableFuture<Filters> targetFiltersFuture = getFiltersFromAccountId
                .invokeAsync(viewerId, targetId)
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null);

        CompletableFuture<Boolean> viewerLikedTargetFacecardFuture = viewerIdToTargetIdToFacecardReaction
                .selectOneAsync(Path.key(viewerId, targetId))
                .thenApply(CalypsoApiManager::isLikeReaction)
                .completeOnTimeout(false, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> false);

        CompletableFuture<Boolean> targetLikedViewerFacecardFuture = viewerIdToTargetIdToFacecardReaction
                .selectOneAsync(Path.key(targetId, viewerId))
                .thenApply(CalypsoApiManager::isLikeReaction)
                .completeOnTimeout(false, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> false);

        CompletableFuture<Boolean> viewerPromptLikeSeenFuture = resolvePromptLikeEvidence(viewerId, targetId);
        CompletableFuture<Boolean> targetPromptLikeSeenFuture = resolvePromptLikeEvidence(targetId, viewerId);

        CompletableFuture<Boolean> viewerToTargetFollowupFuture = getActiveMatchmakingFollowupAssignment
                .invokeAsync(viewerId, targetId)
                .thenApply(assignment -> isActiveFollowupPendingForPair(assignment, targetId, viewerId))
                .completeOnTimeout(false, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> false);

        CompletableFuture<Boolean> targetToViewerFollowupFuture = getActiveMatchmakingFollowupAssignment
                .invokeAsync(viewerId, viewerId)
                .thenApply(assignment -> isActiveFollowupPendingForPair(assignment, viewerId, targetId))
                .completeOnTimeout(false, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> false);

        CompletableFuture<Void> all = CompletableFuture.allOf(
                targetToViewerScoreFuture,
                targetFiltersFuture,
                viewerLikedTargetFacecardFuture,
                targetLikedViewerFacecardFuture,
                viewerPromptLikeSeenFuture,
                targetPromptLikeSeenFuture,
                viewerToTargetFollowupFuture,
                targetToViewerFollowupFuture);

        return all.thenApply(v -> {
            Double targetToViewerScore = targetToViewerScoreFuture.join();
            if (targetToViewerScore == null) {
                return null;
            }
            double viewerToTargetScore = ranked.score;
            Filters targetFilters = targetFiltersFuture.join();
            String targetMode = CalypsoHelpers.getModeSelfOrNull(targetFilters);

            if (viewerToTargetScore < modeAwareMatchThreshold(viewerMode)
                    || targetToViewerScore.doubleValue() < modeAwareMatchThreshold(targetMode)) {
                return null;
            }

            boolean viewerLikedTargetFacecard = viewerLikedTargetFacecardFuture.join();
            boolean targetLikedViewerFacecard = targetLikedViewerFacecardFuture.join();
            if (!viewerLikedTargetFacecard || !targetLikedViewerFacecard) {
                return null;
            }

            boolean viewerPromptLikeSeen = viewerPromptLikeSeenFuture.join();
            boolean targetPromptLikeSeen = targetPromptLikeSeenFuture.join();
            if (!viewerPromptLikeSeen || !targetPromptLikeSeen) {
                return null;
            }

            boolean followupPending = viewerToTargetFollowupFuture.join() || targetToViewerFollowupFuture.join();
            if (followupPending
                    && (viewerToTargetScore < modeAwareAutoPassThreshold(viewerMode)
                            || targetToViewerScore.doubleValue() < modeAwareAutoPassThreshold(targetMode))) {
                return null;
            }

            double mutualScore = Math.min(viewerToTargetScore, targetToViewerScore.doubleValue());
            return new GetMatch(ranked.account, mutualScore, ranked.computedAt);
        }).exceptionally(ex -> {
            LOG.warn("Failed to evaluate mutual match {} -> {}", viewerId, targetId, ex);
            return null;
        });
    }

    public CompletableFuture<List<GetMatch>> getMatches(long requesterId, long viewerId, int limit) {
        int clamped = clampMatchLimit(limit);
        int refillTarget = Math.max(80, clamped * 3);
        requestRefill(viewerId, refillTarget).exceptionally(ex -> {
            LOG.warn("Failed to enqueue match refill for account {} (target size {})", viewerId, refillTarget, ex);
            return null;
        });

        CompletableFuture<Filters> viewerFiltersFuture = getFiltersFromAccountId
                .invokeAsync(requesterId, viewerId)
                .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                .exceptionally(ex -> null);

        return loadRawRankedCandidates(viewerId, clamped)
                .thenCompose(ranked -> viewerFiltersFuture.thenCompose(viewerFilters -> {
                    String viewerMode = CalypsoHelpers.getModeSelfOrNull(viewerFilters);
                    if (ranked == null || ranked.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<GetMatch>of());
                    }
                    List<CompletableFuture<GetMatch>> futures = new ArrayList<>(ranked.size());
                    for (GetMatch candidate : ranked) {
                        futures.add(evaluateMutualMatch(viewerId, viewerMode, candidate));
                    }
                    CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                    return all.thenApply(v -> {
                        List<GetMatch> out = new ArrayList<>();
                        for (CompletableFuture<GetMatch> future : futures) {
                            GetMatch match = future.join();
                            if (match != null) {
                                out.add(match);
                            }
                        }
                        return out;
                    });
                }))
                .completeOnTimeout(List.<GetMatch>of(), 10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load mutual matches for account {}", viewerId, ex);
                    return List.<GetMatch>of();
                });
    }

    public CompletableFuture<List<GetMatch>> getFacecards(long requesterId, long viewerId, int limit) {
        int clamped = clampMatchLimit(limit);
        int refillTarget = Math.max(120, clamped * 6);
        requestRefill(viewerId, refillTarget)
                .exceptionally(ex -> {
                    LOG.warn("Failed to enqueue facecard refill for account {} (target size {})", viewerId, refillTarget,
                            ex);
                    return null;
                });
        return loadRankedCandidates(requesterId, viewerId, clamped, true)
                .completeOnTimeout(List.<GetMatch>of(), 8, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load facecards for account {}", viewerId, ex);
                    return List.<GetMatch>of();
                });
    }

}
