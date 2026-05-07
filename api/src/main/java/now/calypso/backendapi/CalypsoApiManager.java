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
import now.calypso.backendapi.llm.MatchReranker;
import now.calypso.backendapi.llm.LlmTelemetry;
import now.calypso.backendapi.llm.PrivatePromptUnderstanding;
import now.calypso.backendapi.pojos.*;
import now.calypso.backendapi.prompts.*;
import now.calypso.backendapi.signals.*;
import now.calypso.backendapi.silhouette.SilhouetteEditor;
import now.calypso.backendapi.silhouette.SilhouetteAntiPattern;
import now.calypso.backendapi.silhouette.SilhouetteConcept;
import now.calypso.backendapi.silhouette.SilhouetteDigest;
import now.calypso.backendapi.silhouette.SilhouetteEvidence;
import now.calypso.backendapi.silhouette.SilhouetteModeMerger;
import now.calypso.backendapi.silhouette.SilhouettePatch;
import now.calypso.backendapi.silhouette.SilhouetteState;
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
    private final ConcurrentHashMap<Long, CompletableFuture<Signals>> seedSignalBootstrapByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<PublicPromptAnswer>> publicPromptSignalBackfillByAnswerId = new ConcurrentHashMap<>();
    private final Set<String> publicPromptOwnerCandidateObservedAnswerIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, DisambiguationCandidateStats>> signalDisambiguationByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> silhouetteSerialByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> matchRefillRequestByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastMatchRefillRequestAtByAccount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> facecardRerankByDeckKey = new ConcurrentHashMap<>();

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
    private final Depot silhouetteDepot;
    private final Depot silhouetteUpdateEventDepot;
    private final Depot silhouetteUpdateAckDepot;

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
    private final QueryTopologyClient<List<String>> getPublicPromptAnswerIdsByPromptId;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getPublicPromptFeed;
    private final QueryTopologyClient<List<PublicPromptAnswer>> getMyPublicPromptAnswers;
    private final QueryTopologyClient<PublicPromptSelection> getPublicPromptSelection;
    private final QueryTopologyClient<List<Map<String, Object>>> getMatchmakingFollowupCandidatesForTarget;
    private final QueryTopologyClient<PrivatePromptAssignment> getMatchmakingFollowupAssignmentByInstanceId;
    private final QueryTopologyClient<PrivatePromptAnswer> getMatchmakingFollowupAnswerByInstanceId;
    private final QueryTopologyClient<PrivatePromptAssignment> getActiveMatchmakingFollowupAssignment;
    private final QueryTopologyClient<Map<String, Object>> getMatchmakingFollowupSchedulerState;
    private final QueryTopologyClient<Map<String, Object>> getSilhouetteFromAccountId;
    private final QueryTopologyClient<List<Map<String, Object>>> getSilhouettePendingUpdates;

    // Facecard daily decks
    private final Depot facecardDeckDepot;
    private final QueryTopologyClient<Map<String, Object>> getFacecardDeck;

    // Direct Messages
    private final Depot directMessageDepot;
    private final QueryTopologyClient<List<DirectMessage>> getDirectMessages;

    // Matches Depots
    private final Depot signalsDepot;
    private final Depot filtersDepot;
    private final Depot matchRefillDepot;
    private final Depot matchesServeDepot;

    // Matches Queries
    private final QueryTopologyClient<Filters> getFiltersFromAccountId;
    private final QueryTopologyClient<List<MatchCandidate>> getMatchesFromAccountId;
    private final QueryTopologyClient<Signals> getSignalsFromAccountId;
    private final QueryTopologyClient<List<Long>> getSignalAccountIds;
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
    private static final boolean FACECARD_RERANK_ENABLED = "true"
            .equalsIgnoreCase(System.getenv("CALYPSO_FACECARD_LLM_RERANK_ENABLED"));
    private static final int MATCH_RERANK_POOL_MULTIPLIER = 3;
    private static final int MATCH_RERANK_POOL_MIN = 12;
    private static final int MATCH_RERANK_POOL_MAX = 40;
    private static final int MATCH_RERANK_SIGNAL_LIMIT_VIEWER = 16;
    private static final int MATCH_RERANK_SIGNAL_LIMIT_CANDIDATE = 12;
    private static final double MATCH_RERANK_MAX_WEIGHT = 0.45;
    private static final double MATCH_RERANK_BLOCKER_CAP = 0.82;
    private static final double MATCH_RERANK_CONFIDENCE_MIN = 0.25;
    private static final long MATCH_RERANK_TIMEOUT_MS = 4500L;
    private static final long MATCH_REFILL_REQUEST_COOLDOWN_MS = 1500L;
    private static final int FACECARD_DAILY_LIMIT = 20;
    private static final String FACECARD_DECK_STATUS_STAGE2 = "stage2";
    private static final String FACECARD_DECK_STATUS_RERANKED = "reranked";
    private static final ZoneId FACECARD_DAY_ZONE = resolveFacecardDayZone();
    private static final double PUBLIC_REACTION_LIKE_VALENCE_FLOOR = 0.44;
    private static final double PUBLIC_REACTION_DISLIKE_VALENCE_FLOOR = 0.54;
    private static final double PUBLIC_REACTION_VALENCE_SCALE = 0.24;
    private static final int PUBLIC_REACTION_STRENGTH_MIN = -3;
    private static final int PUBLIC_REACTION_STRENGTH_MAX = 3;
    private static final String PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX = "|reaction_strength:";
    private static final double PUBLIC_PROMPT_OWNER_CANDIDATE_FALLBACK_VALENCE = 0.30;
    private static final int SIGNAL_HIERARCHY_MAX_DEPTH = 3;
    private static final double SIGNAL_HIERARCHY_MIN_VALENCE_ABS = 0.06;
    private static final double SIGNAL_HIERARCHY_DERIVED_VALENCE_SCALE = 0.68;
    private static final double PRIVATE_PROMPT_FIRST_HIT_VALENCE_SCALE = 0.30;
    private static final double PRIVATE_PROMPT_REPEAT_VALENCE_SCALE = 0.52;
    private static final double MATCHMAKING_FOLLOWUP_FIRST_HIT_VALENCE_SCALE = 0.32;
    private static final double MATCHMAKING_FOLLOWUP_REPEAT_VALENCE_SCALE = 0.54;
    private static final double PUBLIC_PROMPT_FIRST_HIT_VALENCE_SCALE = 0.32;
    private static final double PUBLIC_PROMPT_REPEAT_VALENCE_SCALE = 0.54;
    private static final double DEFAULT_SOURCE_FIRST_HIT_VALENCE_SCALE = 0.28;
    private static final double DEFAULT_SOURCE_REPEAT_VALENCE_SCALE = 0.48;
    // Denominator softness for count-based valence ceiling: ceiling(n) = n / (n + softness).
    // At count=1 → ~0.20; count=5 → ~0.56; count=10 → ~0.71; count=20+ → ~0.83+.
    private static final double VALENCE_COUNT_CEILING_SOFTNESS = 4.0;
    private static final String SIGNAL_HIERARCHY_DERIVED_SOURCE = "signal_hierarchy_derived";
    private static final int DISAMBIGUATION_MAX_PER_ACCOUNT = 200;
    private static final boolean SILHOUETTE_WRITE_ENABLED = !"false"
            .equalsIgnoreCase(System.getenv("CALYPSO_SILHOUETTE_WRITE_ENABLED"));
    private static final boolean SILHOUETTE_RERANK_ENABLED = !"false"
            .equalsIgnoreCase(System.getenv("CALYPSO_SILHOUETTE_RERANK_ENABLED"));
    private static final boolean SILHOUETTE_PUBLIC_REACTION_ENABLED = "true"
            .equalsIgnoreCase(System.getenv("CALYPSO_SILHOUETTE_PUBLIC_REACTION_ENABLED"));
    private static final int SILHOUETTE_PENDING_BATCH_LIMIT = 20;
    private static final int SILHOUETTE_MAX_EVENT_ATTEMPTS = 3;
    private static final int SILHOUETTE_MIN_ANSWER_CHARS = 8;
    private static final int SILHOUETTE_PUBLIC_MIN_ANSWER_CHARS = 28;
    private static final Set<String> SILHOUETTE_GENERIC_META_SUBSTRINGS = Set.of(
            "focuses on lifestyle",
            "lifestyle and cultural markers",
            "cultural markers",
            "primary filters",
            "relationship compatibility",
            "filter for compatibility",
            "activity-based community",
            "social belonging",
            "specific activity-based community");
    private static final Set<String> SILHOUETTE_ABSTRACT_CUE_TERMS = Set.of(
            "ambition",
            "reciprocity",
            "communication",
            "emotional",
            "discipline",
            "consisten",
            "trajectory",
            "intellectual",
            "values",
            "character",
            "reliab",
            "integrity",
            "growth",
            "independent",
            "headstrong",
            "flirty",
            "playful",
            "equal");
    private static final Set<String> SILHOUETTE_SIGNAL_FIRST_PROMPT_IDS = Set.of(
            "private.hobbies",
            "private.communities.scene",
            "private.great.night",
            "private.places.home",
            "private.stuck.with",
            "private.most.myself",
            "private.popular.dislike",
            "private.not.my.person");
    private static final boolean PRIVATE_UNIFIED_UNDERSTANDING_ENABLED = !"false"
            .equalsIgnoreCase(System.getenv("CALYPSO_PRIVATE_UNIFIED_UNDERSTANDING_ENABLED"));
    private static final SecureRandom PHONE_CODE_RANDOM = new SecureRandom();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final String SMS_FALLBACK_ENV = "CALYPSO_SMS_FALLBACK";

    private static ZoneId resolveFacecardDayZone() {
        String configured = System.getenv("CALYPSO_FACECARD_DAY_ZONE");
        if (configured == null || configured.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException e) {
            LOG.warn("Ignoring invalid CALYPSO_FACECARD_DAY_ZONE={}", configured);
            return ZoneId.systemDefault();
        }
    }

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
        silhouetteDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*silhouetteDepot");
        silhouetteUpdateEventDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*silhouetteUpdateEventDepot");
        silhouetteUpdateAckDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*silhouetteUpdateAckDepot");

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
        getPublicPromptAnswerIdsByPromptId = cluster.clusterQuery(CORE_MODULE_NAME,
                "getPublicPromptAnswerIdsByPromptId");
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
        getSilhouetteFromAccountId = cluster.clusterQuery(CORE_MODULE_NAME, "getSilhouetteFromAccountId");
        getSilhouettePendingUpdates = cluster.clusterQuery(CORE_MODULE_NAME, "getSilhouettePendingUpdates");

        // Facecard daily decks — guarded so older running clusters can still serve the legacy path until restart.
        Depot tmpFacecardDeckDepot = null;
        QueryTopologyClient<Map<String, Object>> tmpGetFacecardDeck = null;
        try {
            tmpFacecardDeckDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*facecardDeckDepot");
            tmpGetFacecardDeck = cluster.clusterQuery(CORE_MODULE_NAME, "getFacecardDeck");
        } catch (Exception e) {
            LOG.warn("[facecards] daily deck depot/query not available in running cluster — restart the server to enable async deck reranking", e);
        }
        facecardDeckDepot = tmpFacecardDeckDepot;
        getFacecardDeck = tmpGetFacecardDeck;

        // Direct Messages — guarded: these only exist after a cluster restart with the new topology.
        Depot tmpDmDepot = null;
        QueryTopologyClient<List<DirectMessage>> tmpGetDMs = null;
        try {
            tmpDmDepot = cluster.clusterDepot(CORE_MODULE_NAME, "*directMessageDepot");
            tmpGetDMs = cluster.clusterQuery(CORE_MODULE_NAME, "getDirectMessages");
        } catch (Exception e) {
            LOG.warn("[direct-messages] depot/query not available in running cluster — restart the server to enable DMs", e);
        }
        directMessageDepot = tmpDmDepot;
        getDirectMessages = tmpGetDMs;

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
        getSignalAccountIds = cluster.clusterQuery(CORE_MODULE_NAME, "getSignalAccountIds");
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
                .thenCompose(accountUUID -> {
                    boolean created = accountUUID != null && accountUUID.equals(uuid);
                    if (!created) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return this.getAccountId(params.phone_number)
                            .thenCompose(accountId -> initializeSilhouetteForAccount(accountId == null ? -1L : accountId.longValue())
                                    .exceptionally(ex -> {
                                        LOG.warn("Failed to initialize silhouette for new account {}", accountId, ex);
                                        return null;
                                    })
                                    .thenApply(ignored -> true));
                });
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
        return getSignalsFromAccountId.invokeAsync(requesterId, accountId)
                .thenApply(CalypsoApiManager::canonicalizeSignalSnapshot)
                .thenCompose(snapshot -> maybeBootstrapSeedSignals(requesterId, accountId, snapshot));
    }

    public CompletableFuture<Map<String, Object>> getSilhouette(long requesterId, long accountId) {
        if (accountId < 0L) {
            return CompletableFuture.completedFuture(defaultSilhouetteMap(accountId));
        }
        triggerSilhouetteDrain(accountId);
        return readSilhouetteSnapshot(accountId)
                .thenApply(snapshot -> snapshot == null ? defaultSilhouetteMap(accountId) : snapshot)
                .exceptionally(ex -> {
                    LOG.warn("Failed to read silhouette for account {}", accountId, ex);
                    return defaultSilhouetteMap(accountId);
                });
    }

    public CompletableFuture<Map<String, Object>> getLlmTelemetry(long accountId, int limit) {
        if (accountId < 0L) {
            return CompletableFuture.completedFuture(Map.of(
                    "generatedAt", System.currentTimeMillis(),
                    "totals", Map.of(),
                    "byStage", List.of(),
                    "events", List.of()));
        }
        return CompletableFuture.completedFuture(LlmTelemetry.snapshot(limit));
    }

    private CompletableFuture<Map<String, Object>> readSilhouetteSnapshot(long accountId) {
        if (accountId < 0L) {
            return CompletableFuture.completedFuture(defaultSilhouetteMap(accountId));
        }
        return getSilhouetteFromAccountId.invokeAsync(accountId, accountId)
                .completeOnTimeout(defaultSilhouetteMap(accountId), 2, TimeUnit.SECONDS)
                .thenApply(snapshot -> {
                    if (snapshot == null || snapshot.isEmpty()) {
                        return defaultSilhouetteMap(accountId);
                    }
                    return sanitizeSilhouetteMap(snapshot, accountId);
                })
                .exceptionally(ex -> defaultSilhouetteMap(accountId));
    }

    private CompletableFuture<Void> initializeSilhouetteForAccount(long accountId) {
        if (accountId < 0L) {
            return CompletableFuture.completedFuture(null);
        }
        return silhouetteDepot.appendAsync(defaultSilhouetteMap(accountId)).thenApply(ignored -> null);
    }

    private void queueSilhouetteUpdateAsync(long accountId,
            String source,
            String sourceId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            String contextMaybe) {
        queueSilhouetteUpdateAsync(accountId,
                source,
                sourceId,
                promptId,
                question,
                answer,
                conversationLines,
                contextMaybe,
                null,
                null);
    }

    private void queueSilhouetteUpdateAsync(long accountId,
            String source,
            String sourceId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            String contextMaybe,
            SilhouettePatch precomputedPatch,
            String semanticDelta) {
        if (!SILHOUETTE_WRITE_ENABLED || accountId < 0L) {
            return;
        }
        if (!shouldQueueSilhouetteEvent(source, promptId, answer)) {
            return;
        }
        enqueueSilhouetteUpdate(
                accountId,
                source,
                sourceId,
                promptId,
                question,
                answer,
                conversationLines,
                contextMaybe,
                precomputedPatch,
                semanticDelta)
                .exceptionally(ex -> {
                    LOG.warn("Failed to enqueue silhouette update for account {} ({})", accountId, source, ex);
                    return false;
                });
    }

    private CompletableFuture<Boolean> enqueueSilhouetteUpdate(long accountId,
            String source,
            String sourceId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            String contextMaybe,
            SilhouettePatch precomputedPatch,
            String semanticDelta) {
        if (!SILHOUETTE_WRITE_ENABLED || accountId < 0L) {
            return CompletableFuture.completedFuture(false);
        }
        Map<String, Object> event = buildSilhouetteEvent(accountId, source, sourceId, promptId, question, answer,
                conversationLines, contextMaybe, precomputedPatch, semanticDelta);
        return silhouetteUpdateEventDepot.appendAsync(event)
                .thenApply(ignored -> true);
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> serializeSilhouetteOp(long accountId, Supplier<CompletableFuture<Void>> op) {
        return (CompletableFuture<Void>) silhouetteSerialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<?> start = (prev == null) ? CompletableFuture.completedFuture(null) : prev;
            CompletableFuture<Void> next = start.thenCompose(ignored -> op.get());
            next.whenComplete((r, e) -> silhouetteSerialByAccount.remove(k, next));
            return next;
        });
    }

    private void triggerSilhouetteDrain(long accountId) {
        if (!SILHOUETTE_WRITE_ENABLED || accountId < 0L) {
            return;
        }
        serializeSilhouetteOp(accountId, () -> drainSilhouetteUpdates(accountId))
                .exceptionally(ex -> {
                    LOG.warn("Silhouette drain failed for account {}", accountId, ex);
                    return null;
                });
    }

    private CompletableFuture<Void> drainSilhouetteUpdates(long accountId) {
        if (!SILHOUETTE_WRITE_ENABLED || accountId < 0L) {
            return CompletableFuture.completedFuture(null);
        }
        return getSilhouettePendingUpdates.invokeAsync(accountId, SILHOUETTE_PENDING_BATCH_LIMIT)
                .thenCompose(events -> {
                    if (events == null || events.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    ArrayList<Map<String, Object>> precomputed = new ArrayList<>();
                    ArrayList<Map<String, Object>> unresolved = new ArrayList<>();
                    for (Map<String, Object> event : events) {
                        if (hasPrecomputedSilhouettePatch(event)) {
                            precomputed.add(event);
                        } else {
                            unresolved.add(event);
                        }
                    }
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (Map<String, Object> event : precomputed) {
                        chain = chain.thenCompose(ignored -> processSilhouetteEvent(accountId, event).thenApply(done -> null));
                    }
                    if (!unresolved.isEmpty()) {
                        chain = chain.thenCompose(ignored -> processMergedSilhouetteEvents(accountId, unresolved).thenApply(done -> null));
                    }
                    if (events.size() >= SILHOUETTE_PENDING_BATCH_LIMIT) {
                        chain = chain.thenCompose(ignored -> drainSilhouetteUpdates(accountId));
                    }
                    return chain;
                });
    }

    private CompletableFuture<Boolean> processSilhouetteEvent(long accountId, Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        String eventId = mapString(event, "eventId");
        if (eventId == null) {
            return CompletableFuture.completedFuture(false);
        }
        long attempts = mapLong(event, "attemptCount", 0L);
        if (attempts >= SILHOUETTE_MAX_EVENT_ATTEMPTS) {
            return ackSilhouetteEvent(accountId, eventId).thenApply(ignored -> false);
        }

        CompletableFuture<Map<String, Object>> snapshotFuture = readSilhouetteSnapshot(accountId);
        CompletableFuture<List<SignalRecord>> signalsFuture = readCurrentSignalRecords(accountId);
        CompletableFuture<Boolean> work = CompletableFuture.allOf(snapshotFuture, signalsFuture)
                .thenCompose(ignored -> {
                    Map<String, Object> snapshot = snapshotFuture.join();
                    List<SignalRecord> signalRecords = signalsFuture.join();
                    SilhouetteState base = SilhouetteState.fromMap(snapshot, accountId);
                    SilhouettePatch patch = precomputedSilhouettePatch(event);
                    if (patch == null || patch.isEmpty()) {
                        String signalSummary = buildSignalSummary(signalRecords);
                        Map<String, Object> augmented = augmentEventWithSignalSummary(event, signalSummary);
                        patch = SilhouetteEditor.buildPatch(openAI, base, augmented);
                    }
                    String source = mapString(event, "source");
                    String sourceId = mapString(event, "sourceId");
                    String promptId = mapString(event, "promptId");
                    String answer = mapString(event, "answer");
                    SilhouetteState merged = SilhouetteModeMerger.apply(
                            base,
                            patch,
                            silhouetteSourceWeight(source),
                            source,
                            sourceId,
                            promptId,
                            eventId,
                            answer,
                            System.currentTimeMillis());
                    Map<String, Object> payload = sanitizeSilhouetteMap(merged.toMap(), accountId);
                    return silhouetteDepot.appendAsync(payload)
                            .thenCompose(i -> ackSilhouetteEvent(accountId, eventId))
                            .thenApply(i -> true);
                });

        return work.handle((ok, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(Boolean.TRUE.equals(ok));
            }
            long nextAttempt = attempts + 1L;
            LOG.warn("Failed processing silhouette event {} for account {} (attempt {})",
                    eventId, accountId, nextAttempt, ex);
            if (nextAttempt >= SILHOUETTE_MAX_EVENT_ATTEMPTS) {
                return ackSilhouetteEvent(accountId, eventId).thenApply(ignored -> false);
            }
            return requeueSilhouetteEvent(event, nextAttempt).thenApply(ignored -> false);
        }).thenCompose(future -> future);
    }

    private CompletableFuture<Boolean> processMergedSilhouetteEvents(long accountId, List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        ArrayList<Map<String, Object>> eligible = new ArrayList<>();
        ArrayList<String> ackImmediately = new ArrayList<>();
        for (Map<String, Object> event : events) {
            if (event == null || event.isEmpty()) {
                continue;
            }
            String eventId = mapString(event, "eventId");
            if (eventId == null) {
                continue;
            }
            long attempts = mapLong(event, "attemptCount", 0L);
            if (attempts >= SILHOUETTE_MAX_EVENT_ATTEMPTS) {
                ackImmediately.add(eventId);
                continue;
            }
            eligible.add(event);
        }
        CompletableFuture<Void> immediateAckFuture = ackSilhouetteEvents(accountId, ackImmediately);
        if (eligible.isEmpty()) {
            return immediateAckFuture.thenApply(ignored -> false);
        }
        CompletableFuture<Map<String, Object>> mergedSnapshotFuture = readSilhouetteSnapshot(accountId);
        CompletableFuture<List<SignalRecord>> mergedSignalsFuture = readCurrentSignalRecords(accountId);
        return immediateAckFuture.thenCompose(ignored ->
                CompletableFuture.allOf(mergedSnapshotFuture, mergedSignalsFuture).thenCompose(allOf -> {
            Map<String, Object> snapshot = mergedSnapshotFuture.join();
            List<SignalRecord> signalRecords = mergedSignalsFuture.join();
            SilhouetteState base = SilhouetteState.fromMap(snapshot, accountId);
            Map<String, Object> mergedEvent = mergeSilhouetteEvents(accountId, eligible);
            String signalSummary = buildSignalSummary(signalRecords);
            Map<String, Object> augmentedMerged = augmentEventWithSignalSummary(mergedEvent, signalSummary);
            SilhouettePatch patch = SilhouetteEditor.buildPatch(openAI, base, augmentedMerged);
            if (patch == null || patch.isEmpty()) {
                return ackSilhouetteEvents(accountId, eventIds(eligible)).thenApply(done -> false);
            }
            String source = mapString(mergedEvent, "source");
            String sourceId = mapString(mergedEvent, "sourceId");
            String promptId = mapString(mergedEvent, "promptId");
            String answer = mapString(mergedEvent, "answer");
            String eventId = mapString(mergedEvent, "eventId");
            SilhouetteState merged = SilhouetteModeMerger.apply(
                    base,
                    patch,
                    silhouetteSourceWeight(source),
                    source,
                    sourceId,
                    promptId,
                    eventId,
                    answer,
                    System.currentTimeMillis());
            Map<String, Object> payload = sanitizeSilhouetteMap(merged.toMap(), accountId);
            return silhouetteDepot.appendAsync(payload)
                    .thenCompose(v -> ackSilhouetteEvents(accountId, eventIds(eligible)))
                    .thenApply(v -> true);
        }).handle((ok, ex) -> {
            if (ex == null) {
                return CompletableFuture.completedFuture(Boolean.TRUE.equals(ok));
            }
            ArrayList<CompletableFuture<Void>> recoveries = new ArrayList<>();
            for (Map<String, Object> event : eligible) {
                long nextAttempt = mapLong(event, "attemptCount", 0L) + 1L;
                if (nextAttempt >= SILHOUETTE_MAX_EVENT_ATTEMPTS) {
                    String eventId = mapString(event, "eventId");
                    if (eventId != null) {
                        recoveries.add(ackSilhouetteEvent(accountId, eventId));
                    }
                } else {
                    recoveries.add(requeueSilhouetteEvent(event, nextAttempt));
                }
            }
            if (recoveries.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.allOf(recoveries.toArray(new CompletableFuture[0])).thenApply(v -> false);
        }).thenCompose(f -> f));
    }

    private CompletableFuture<Void> requeueSilhouetteEvent(Map<String, Object> original, long nextAttempt) {
        if (original == null || original.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        HashMap<String, Object> updated = new HashMap<>(original);
        updated.put("attemptCount", nextAttempt);
        updated.put("lastTriedAt", System.currentTimeMillis());
        return silhouetteUpdateEventDepot.appendAsync(updated).thenApply(ignored -> null);
    }

    private CompletableFuture<Void> ackSilhouetteEvent(long accountId, String eventId) {
        if (accountId < 0L || eventId == null || eventId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        HashMap<String, Object> ack = new HashMap<>();
        ack.put("accountId", accountId);
        ack.put("eventId", eventId);
        ack.put("ackedAt", System.currentTimeMillis());
        return silhouetteUpdateAckDepot.appendAsync(ack).thenApply(ignored -> null);
    }

    private static Map<String, Object> buildSilhouetteEvent(long accountId,
            String source,
            String sourceId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            String contextMaybe,
            SilhouettePatch precomputedPatch,
            String semanticDelta) {
        HashMap<String, Object> out = new HashMap<>();
        out.put("eventId", "ev_" + UUID.randomUUID().toString().replace("-", ""));
        out.put("accountId", accountId);
        out.put("source", clampShort(source, 64));
        out.put("sourceId", clampShort(sourceId, 96));
        out.put("promptId", clampShort(promptId, 96));
        out.put("question", clampShort(question, 220));
        out.put("answer", clampShort(answer, 360));
        out.put("conversation", String.join(" | ", clampConversationLines(conversationLines, 12, 140)));
        String compactContext = compactSilhouetteEventContext(promptId, question, answer, contextMaybe, semanticDelta);
        out.put("context", clampShort(compactContext, 220));
        String delta = compactSilhouetteDelta(promptId, question, answer, clampConversationLines(conversationLines, 8, 180));
        if (delta != null && !delta.isBlank()) {
            out.put("delta", clampShort(delta, 220));
        }
        if (precomputedPatch != null && !precomputedPatch.isEmpty()) {
            out.put("precomputedPatch", precomputedPatch.toMap());
        }
        out.put("createdAt", System.currentTimeMillis());
        out.put("attemptCount", 0L);
        return out;
    }

    private static String compactSilhouetteEventContext(
            String promptId,
            String question,
            String answer,
            String contextMaybe,
            String semanticDelta) {
        String context = clampContext(contextMaybe);
        String delta = clampContext(semanticDelta);
        if (delta == null || delta.isBlank()) {
            delta = compactSilhouetteDelta(promptId, question, answer, List.of());
        }
        if (context == null || context.isBlank()) {
            return delta;
        }
        if (delta == null || delta.isBlank()) {
            return context;
        }
        return clampContext(context + " | " + delta);
    }

    private static boolean hasPrecomputedSilhouettePatch(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return false;
        }
        Object raw = event.get("precomputedPatch");
        if (!(raw instanceof Map<?, ?> map)) {
            return false;
        }
        SilhouettePatch parsed = SilhouettePatch.fromMap(castStringObjectMap(map));
        return parsed != null && !parsed.isEmpty();
    }

    private static SilhouettePatch precomputedSilhouettePatch(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return SilhouettePatch.empty();
        }
        Object raw = event.get("precomputedPatch");
        if (!(raw instanceof Map<?, ?> map)) {
            return SilhouettePatch.empty();
        }
        return SilhouettePatch.fromMap(castStringObjectMap(map));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjectMap(Map<?, ?> raw) {
        HashMap<String, Object> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey().toString(), deepMutableCopy(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepMutableCopy(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return castStringObjectMap(map);
        }
        if (raw instanceof List<?> list) {
            ArrayList<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(deepMutableCopy(item));
            }
            return out;
        }
        return raw;
    }

    private CompletableFuture<Void> ackSilhouetteEvents(long accountId, Collection<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String eventId : eventIds) {
            if (eventId == null || eventId.isBlank()) {
                continue;
            }
            chain = chain.thenCompose(ignored -> ackSilhouetteEvent(accountId, eventId));
        }
        return chain;
    }

    private static List<String> eventIds(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            String id = mapString(event, "eventId");
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    private static Map<String, Object> mergeSilhouetteEvents(long accountId, List<Map<String, Object>> events) {
        HashMap<String, Object> merged = new HashMap<>();
        merged.put("eventId", "batch_" + UUID.randomUUID().toString().replace("-", ""));
        merged.put("accountId", accountId);
        if (events == null || events.isEmpty()) {
            return merged;
        }
        Map<String, Object> latest = events.get(events.size() - 1);
        String source = mapString(latest, "source");
        String sourceId = mapString(latest, "sourceId");
        String promptId = mapString(latest, "promptId");
        String question = mapString(latest, "question");
        String answer = mapString(latest, "answer");
        ArrayList<String> snippets = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String delta = mapString(event, "delta");
            String context = mapString(event, "context");
            String eventAnswer = mapString(event, "answer");
            if (delta != null && !delta.isBlank()) {
                snippets.add(delta);
            } else if (context != null && !context.isBlank()) {
                snippets.add(context);
            } else if (eventAnswer != null && !eventAnswer.isBlank()) {
                snippets.add(eventAnswer);
            }
            if (snippets.size() >= 8) {
                break;
            }
        }
        merged.put("source", source == null ? "private_prompt_batch" : source);
        merged.put("sourceId", sourceId);
        merged.put("promptId", promptId);
        merged.put("question", question);
        merged.put("answer", answer);
        merged.put("conversation", "");
        merged.put("context", clampContext(String.join(" | ", snippets)));
        merged.put("createdAt", System.currentTimeMillis());
        merged.put("attemptCount", 0L);
        return merged;
    }

    private static boolean shouldQueueSilhouetteEvent(String source, String promptId, String answer) {
        String normalizedSource = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        String normalizedPromptId = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        int answerLen = answer == null ? 0 : answer.trim().length();
        if (answerLen < SILHOUETTE_MIN_ANSWER_CHARS) {
            return false;
        }
        if (normalizedSource.contains("public_prompt_reaction")) {
            return SILHOUETTE_PUBLIC_REACTION_ENABLED && answerLen >= SILHOUETTE_PUBLIC_MIN_ANSWER_CHARS;
        }
        if (normalizedSource.contains("public_prompt")) {
            return answerLen >= SILHOUETTE_PUBLIC_MIN_ANSWER_CHARS;
        }
        if ("private.popular.dislike".equals(normalizedPromptId) && answerLen < SILHOUETTE_PUBLIC_MIN_ANSWER_CHARS) {
            return false;
        }
        return true;
    }

    private static Map<String, Object> sanitizeSilhouetteMap(Map<String, Object> map, long accountId) {
        SilhouetteState normalized = SilhouetteState.fromMap(map, accountId);
        return normalized.toMap();
    }

    private static Map<String, Object> defaultSilhouetteMap(long accountId) {
        return SilhouetteState.empty(accountId).toMap();
    }

    private static String mapString(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        Object raw = map.get(key);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long mapLong(Map<String, Object> map, String key, long fallback) {
        if (map == null || map.isEmpty() || key == null || key.isBlank()) {
            return fallback;
        }
        Object raw = map.get(key);
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String clampShort(String raw, int limit) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit).trim();
    }

    private static String buildSignalSummary(List<SignalRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        ArrayList<SignalRecord> sorted = new ArrayList<>(records);
        sorted.sort((a, b) -> {
            double aV = a.isSetValence() ? Math.abs(a.getValence()) : 0.0;
            double bV = b.isSetValence() ? Math.abs(b.getValence()) : 0.0;
            int aC = a.isSetCount() ? Math.max(1, a.getCount()) : 1;
            int bC = b.isSetCount() ? Math.max(1, b.getCount()) : 1;
            return Double.compare(bV * Math.log1p(bC), aV * Math.log1p(aC));
        });
        ArrayList<String> selfPos = new ArrayList<>();
        ArrayList<String> selfNeg = new ArrayList<>();
        ArrayList<String> seekingPos = new ArrayList<>();
        ArrayList<String> seekingNeg = new ArrayList<>();
        for (SignalRecord r : sorted) {
            if (!r.isSetToken() || !r.isSetValence()) {
                continue;
            }
            double v = r.getValence();
            if (Math.abs(v) < 0.06) {
                continue;
            }
            String token = r.getToken();
            if (token == null || token.isBlank()) {
                continue;
            }
            SignalIntent intent = r.isSetIntent() ? r.getIntent() : SignalIntent.SELF;
            boolean isSelf = intent == SignalIntent.SELF || intent == SignalIntent.BOTH;
            boolean isSeeking = intent == SignalIntent.SEEKING || intent == SignalIntent.BOTH;
            if (v > 0) {
                if (isSelf && selfPos.size() < 10) selfPos.add(token);
                if (isSeeking && seekingPos.size() < 8) seekingPos.add(token);
            } else {
                if (isSelf && selfNeg.size() < 8) selfNeg.add(token);
                if (isSeeking && seekingNeg.size() < 8) seekingNeg.add(token);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!selfPos.isEmpty()) sb.append("self+: ").append(String.join(", ", selfPos)).append('\n');
        if (!selfNeg.isEmpty()) sb.append("self-: ").append(String.join(", ", selfNeg)).append('\n');
        if (!seekingPos.isEmpty()) sb.append("seeking+: ").append(String.join(", ", seekingPos)).append('\n');
        if (!seekingNeg.isEmpty()) sb.append("seeking-: ").append(String.join(", ", seekingNeg)).append('\n');
        return sb.toString().trim();
    }

    private static Map<String, Object> augmentEventWithSignalSummary(Map<String, Object> event, String signalSummary) {
        if (signalSummary == null || signalSummary.isBlank()) {
            return event;
        }
        HashMap<String, Object> augmented = new HashMap<>(event);
        augmented.put("signalSummary", signalSummary);
        return augmented;
    }

    private static double silhouetteSourceWeight(String source) {
        if (source == null || source.isBlank()) {
            return 0.30;
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("behavior") || normalized.contains("facecard")) {
            return 1.10;
        }
        if (normalized.contains("private_prompt")) {
            return 1.0;
        }
        if (normalized.contains("matchmaking_followup")) {
            return 0.85;
        }
        if (normalized.contains("public_prompt_reaction")) {
            return 0.45;
        }
        if (normalized.contains("public_prompt")) {
            return 0.45;
        }
        return 0.30;
    }

    public CompletableFuture<GetSignalConceptRegistry> getSignalConceptRegistry() {
        int promoted = SignalConceptRegistry.autoPromoteReadyCandidatesIfDue();
        if (promoted > 0) {
            LOG.info("Auto-promoted {} signal concept candidates", promoted);
        }
        return CompletableFuture.completedFuture(GetSignalConceptRegistry.fromEntries(
                SignalConceptRegistry.version(),
                SignalConceptRegistry.conceptsSnapshot()));
    }

    public CompletableFuture<GetSignalConceptCandidates> getSignalConceptCandidates(int limit) {
        int promoted = SignalConceptRegistry.autoPromoteReadyCandidatesIfDue();
        if (promoted > 0) {
            LOG.info("Auto-promoted {} signal concept candidates", promoted);
        }
        int bounded = Math.max(1, Math.min(500, limit));
        return CompletableFuture.completedFuture(GetSignalConceptCandidates.fromEntries(
                SignalConceptRegistry.version(),
                SignalConceptRegistry.candidateSnapshot(bounded)));
    }

    public CompletableFuture<GetSignalConceptCandidates> getBlockedSignalConceptCandidates(int limit) {
        int bounded = Math.max(1, Math.min(500, limit));
        return CompletableFuture.completedFuture(GetSignalConceptCandidates.fromBlockedEntries(
                SignalConceptRegistry.version(),
                SignalConceptRegistry.blockedSnapshot(bounded)));
    }

    private static final class ConceptMigrationSummary {
        int migratedAccounts = 0;
        final LinkedHashSet<Long> accountsWithStoredRawAlias = new LinkedHashSet<>();
    }

    private static final class ConceptMigrationOutcome {
        final boolean migrated;
        final boolean hadStoredRawAlias;

        ConceptMigrationOutcome(boolean migrated, boolean hadStoredRawAlias) {
            this.migrated = migrated;
            this.hadStoredRawAlias = hadStoredRawAlias;
        }
    }

    public static final class SignalConceptPromotionResult {
        public final boolean changed;
        public final String rawToken;
        public final String canonicalToken;
        public final int migratedStoredAccounts;
        public final int replayedObservedAccounts;
        public final int replayedContextualOwners;
        public final List<Long> observedAccountIds;
        public final List<String> parentConcepts;

        SignalConceptPromotionResult(boolean changed, String rawToken, String canonicalToken,
                int migratedStoredAccounts, int replayedObservedAccounts, int replayedContextualOwners,
                Collection<Long> observedAccountIds) {
            this(changed, rawToken, canonicalToken, migratedStoredAccounts, replayedObservedAccounts,
                    replayedContextualOwners, observedAccountIds, List.of());
        }

        SignalConceptPromotionResult(boolean changed, String rawToken, String canonicalToken,
                int migratedStoredAccounts, int replayedObservedAccounts, int replayedContextualOwners,
                Collection<Long> observedAccountIds, Collection<String> parentConcepts) {
            this.changed = changed;
            this.rawToken = rawToken;
            this.canonicalToken = canonicalToken;
            this.migratedStoredAccounts = migratedStoredAccounts;
            this.replayedObservedAccounts = replayedObservedAccounts;
            this.replayedContextualOwners = replayedContextualOwners;
            ArrayList<Long> ids = new ArrayList<>();
            if (observedAccountIds != null) {
                for (Long id : observedAccountIds) {
                    if (id != null && id.longValue() >= 0L) {
                        ids.add(id);
                    }
                }
            }
            ids.sort(Long::compareTo);
            this.observedAccountIds = Collections.unmodifiableList(ids);
            LinkedHashSet<String> parents = new LinkedHashSet<>();
            if (parentConcepts != null) {
                for (String parent : parentConcepts) {
                    String normalized = SignalNormalizer.normalizeOne(parent);
                    if (normalized != null && !normalized.isBlank()) {
                        parents.add(normalized);
                    }
                }
            }
            this.parentConcepts = Collections.unmodifiableList(new ArrayList<>(parents));
        }
    }

    public enum SignalConceptCandidateAction {
        CREATE,
        MAP,
        REJECT,
        BLOCK,
        UNBLOCK;

        static SignalConceptCandidateAction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return SignalConceptCandidateAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private static final class DisambiguationCandidateStats {
        final String key;
        final String term;
        final String question;
        final String promptId;
        String source;
        String sourceId;
        String context;
        int seenCount;
        long firstSeen;
        long lastSeen;

        DisambiguationCandidateStats(String key, String term, String question, String promptId) {
            this.key = key;
            this.term = term;
            this.question = question;
            this.promptId = promptId;
        }

        synchronized void record(String source, String sourceId, String context) {
            long now = System.currentTimeMillis();
            if (seenCount <= 0) {
                firstSeen = now;
            }
            seenCount += 1;
            lastSeen = now;
            if (source != null && !source.isBlank()) {
                this.source = source.trim();
            }
            if (sourceId != null && !sourceId.isBlank()) {
                this.sourceId = sourceId.trim();
            }
            if (context != null && !context.isBlank()) {
                String compact = context.trim();
                if (compact.length() > 220) {
                    compact = compact.substring(0, 220);
                }
                this.context = compact;
            }
        }

        GetSignalDisambiguationCandidates.Candidate toCandidate() {
            return new GetSignalDisambiguationCandidates.Candidate(
                    key,
                    term,
                    question,
                    promptId,
                    source,
                    sourceId,
                    context,
                    seenCount,
                    firstSeen,
                    lastSeen);
        }
    }

    public CompletableFuture<Boolean> promoteSignalConcept(String rawToken, String canonicalToken) {
        return promoteSignalConceptWithDebug(rawToken, canonicalToken).thenApply(result -> result.changed);
    }

    public CompletableFuture<SignalConceptPromotionResult> createSignalConceptWithDebug(String rawToken) {
        return createSignalConceptWithDebug(rawToken, null);
    }

    public CompletableFuture<SignalConceptPromotionResult> createSignalConceptWithDebug(String rawToken, String category) {
        return createSignalConceptWithDebug(rawToken, category, List.of());
    }

    public CompletableFuture<SignalConceptPromotionResult> createSignalConceptWithDebug(String rawToken, String category,
            Collection<String> parentConcepts) {
        String normalizedRaw = SignalNormalizer.normalizeOne(rawToken);
        if (normalizedRaw == null || normalizedRaw.isBlank()) {
            return CompletableFuture.completedFuture(
                    new SignalConceptPromotionResult(false, normalizedRaw, normalizedRaw, 0, 0, 0, List.of()));
        }
        return promoteSignalConceptWithDebug(normalizedRaw, normalizedRaw, category, parentConcepts);
    }

    public CompletableFuture<Boolean> createSignalConcept(String rawToken) {
        return createSignalConceptWithDebug(rawToken).thenApply(result -> result.changed);
    }

    public CompletableFuture<SignalConceptPromotionResult> mapSignalConceptToExistingCanonicalWithDebug(
            String rawToken,
            String canonicalToken) {
        return mapSignalConceptToExistingCanonicalWithDebug(rawToken, canonicalToken, null);
    }

    public CompletableFuture<SignalConceptPromotionResult> mapSignalConceptToExistingCanonicalWithDebug(
            String rawToken,
            String canonicalToken,
            String category) {
        return mapSignalConceptToExistingCanonicalWithDebug(rawToken, canonicalToken, category, List.of());
    }

    public CompletableFuture<SignalConceptPromotionResult> mapSignalConceptToExistingCanonicalWithDebug(
            String rawToken,
            String canonicalToken,
            String category,
            Collection<String> parentConcepts) {
        String normalizedCanonical = SignalNormalizer.normalizeOne(canonicalToken);
        if (normalizedCanonical == null || normalizedCanonical.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("canonicalToken required."));
        }
        if (!SignalConceptRegistry.isCanonicalConcept(normalizedCanonical)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("canonicalToken must map to an existing canonical concept."));
        }
        return promoteSignalConceptWithDebug(rawToken, normalizedCanonical, category, parentConcepts);
    }

    public CompletableFuture<Boolean> mapSignalConceptToExistingCanonical(String rawToken, String canonicalToken) {
        return mapSignalConceptToExistingCanonicalWithDebug(rawToken, canonicalToken)
                .thenApply(result -> result.changed);
    }

    public CompletableFuture<Map<String, Object>> actOnSignalConceptCandidate(
            String rawToken,
            String canonicalToken,
            SignalConceptCandidateAction action) {
        return actOnSignalConceptCandidate(rawToken, canonicalToken, null, action);
    }

    public CompletableFuture<Map<String, Object>> actOnSignalConceptCandidate(
            String rawToken,
            String canonicalToken,
            String category,
            SignalConceptCandidateAction action) {
        return actOnSignalConceptCandidate(rawToken, canonicalToken, category, List.of(), action);
    }

    public CompletableFuture<Map<String, Object>> actOnSignalConceptCandidate(
            String rawToken,
            String canonicalToken,
            String category,
            Collection<String> parentConcepts,
            SignalConceptCandidateAction action) {
        if (action == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("action required."));
        }
        if (action == SignalConceptCandidateAction.REJECT
                || action == SignalConceptCandidateAction.BLOCK
                || action == SignalConceptCandidateAction.UNBLOCK) {
            CompletableFuture<Boolean> changeFuture = action == SignalConceptCandidateAction.REJECT
                    ? rejectSignalConceptCandidate(rawToken)
                    : action == SignalConceptCandidateAction.BLOCK
                            ? blockSignalConceptCandidate(rawToken)
                            : unblockSignalConceptCandidate(rawToken);
            return changeFuture.thenApply(changed -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("action", action.name().toLowerCase(Locale.ROOT));
                out.put("changed", changed);
                out.put("rawToken", SignalNormalizer.normalizeOne(rawToken));
                out.put("canonicalToken", null);
                out.put("category", null);
                out.put("migratedStoredAccounts", 0);
                out.put("replayedObservedAccounts", 0);
                out.put("replayedContextualOwners", 0);
                out.put("observedAccountIds", List.of());
                out.put("parentConcepts", List.of());
                return out;
            });
        }
        CompletableFuture<SignalConceptPromotionResult> changeFuture = action == SignalConceptCandidateAction.CREATE
                ? createSignalConceptWithDebug(rawToken, category, parentConcepts)
                : mapSignalConceptToExistingCanonicalWithDebug(rawToken, canonicalToken, category, parentConcepts);
        return changeFuture.thenApply(result -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("action", action.name().toLowerCase(Locale.ROOT));
            out.put("changed", result == null ? Boolean.FALSE : result.changed);
            out.put("rawToken", result == null ? null : result.rawToken);
            out.put("canonicalToken", result == null ? null : result.canonicalToken);
            out.put("category", result == null || result.canonicalToken == null
                    ? null
                    : SignalConceptRegistry.categoryForConcept(result.canonicalToken));
            out.put("migratedStoredAccounts", result == null ? 0 : result.migratedStoredAccounts);
            out.put("replayedObservedAccounts", result == null ? 0 : result.replayedObservedAccounts);
            out.put("replayedContextualOwners", result == null ? 0 : result.replayedContextualOwners);
            out.put("observedAccountIds", result == null ? List.of() : result.observedAccountIds);
            out.put("parentConcepts", result == null ? List.of() : result.parentConcepts);
            return out;
        });
    }

    public CompletableFuture<SignalConceptPromotionResult> promoteSignalConceptWithDebug(String rawToken,
            String canonicalToken) {
        return promoteSignalConceptWithDebug(rawToken, canonicalToken, null);
    }

    public CompletableFuture<SignalConceptPromotionResult> promoteSignalConceptWithDebug(String rawToken,
            String canonicalToken,
            String category) {
        return promoteSignalConceptWithDebug(rawToken, canonicalToken, category, List.of());
    }

    public CompletableFuture<SignalConceptPromotionResult> promoteSignalConceptWithDebug(String rawToken,
            String canonicalToken,
            String category,
            Collection<String> parentConcepts) {
        final String normalizedRaw = SignalNormalizer.normalizeOne(rawToken);
        final String normalizedCanonical = SignalNormalizer.normalizeOne(canonicalToken);
        if (normalizedRaw == null || normalizedRaw.isBlank()
                || normalizedCanonical == null || normalizedCanonical.isBlank()) {
            return CompletableFuture.completedFuture(
                    new SignalConceptPromotionResult(false, normalizedRaw, normalizedCanonical, 0, 0, 0, List.of()));
        }

        List<SignalConceptRegistry.CandidateAccountIntentObservation> observations = SignalConceptRegistry
                .candidateAccountIntentObservations(normalizedRaw);
        List<String> candidateContexts = SignalConceptRegistry.candidateExampleContexts(normalizedRaw);
        List<String> normalizedParentConcepts = promotionParentConcepts(parentConcepts, category);
        LinkedHashSet<Long> observedAccountIds = new LinkedHashSet<>();
        if (observations != null) {
            for (SignalConceptRegistry.CandidateAccountIntentObservation observation : observations) {
                if (observation != null && observation.accountId >= 0L) {
                    observedAccountIds.add(observation.accountId);
                }
            }
        }
        boolean changed = SignalConceptRegistry.promoteAlias(normalizedRaw, normalizedCanonical, category,
                normalizedParentConcepts);
        if (!changed) {
            return CompletableFuture.completedFuture(new SignalConceptPromotionResult(
                    false,
                    normalizedRaw,
                    normalizedCanonical,
                    0,
                    0,
                    0,
                    observedAccountIds,
                    normalizedParentConcepts));
        }
        return migratePromotedConceptAcrossAccounts(normalizedRaw, normalizedCanonical)
                .thenCompose(summary -> applyPromotedConceptToObservedRequesters(
                        normalizedRaw,
                        normalizedCanonical,
                        observations,
                        summary.accountsWithStoredRawAlias)
                        .thenCompose(appliedObserved -> backfillPromotedConceptFromPublicPromptContexts(
                                normalizedRaw,
                                normalizedCanonical,
                                candidateContexts,
                                summary.accountsWithStoredRawAlias,
                                observedAccountIds).thenApply(appliedOwners -> {
                            LOG.info(
                                    "Promoted signal concept alias {} -> {} (migrated {} stored accounts, backfilled {} requester observations, {} contextual owners)",
                                    normalizedRaw,
                                    normalizedCanonical,
                                    summary.migratedAccounts,
                                    appliedObserved,
                                    appliedOwners);
                            return new SignalConceptPromotionResult(
                                    true,
                                    normalizedRaw,
                                    normalizedCanonical,
                                    summary.migratedAccounts,
                                    appliedObserved,
                                    appliedOwners,
                                    observedAccountIds,
                                    normalizedParentConcepts);
                        })));
    }

    private static List<String> promotionParentConcepts(Collection<String> parentConcepts, String categoryOrParentHint) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (parentConcepts != null) {
            for (String parent : parentConcepts) {
                String normalized = SignalNormalizer.normalizeOne(parent);
                if (normalized != null && !normalized.isBlank() && SignalConceptRegistry.isCanonicalConcept(normalized)) {
                    out.add(normalized);
                }
            }
        }
        String categoryHint = SignalTaxonomy.normalizeCategory(categoryOrParentHint);
        if (categoryHint == null) {
            String possibleParent = SignalNormalizer.normalizeOne(categoryOrParentHint);
            if (possibleParent != null
                    && !possibleParent.isBlank()
                    && SignalConceptRegistry.isCanonicalConcept(possibleParent)) {
                out.add(possibleParent);
            }
        }
        return new ArrayList<>(out);
    }

    public CompletableFuture<Boolean> rejectSignalConceptCandidate(String rawToken) {
        boolean changed = SignalConceptRegistry.rejectCandidate(rawToken);
        return CompletableFuture.completedFuture(changed);
    }

    public CompletableFuture<Boolean> blockSignalConceptCandidate(String rawToken) {
        boolean changed = SignalConceptRegistry.blockCandidate(rawToken);
        return CompletableFuture.completedFuture(changed);
    }

    public CompletableFuture<Boolean> unblockSignalConceptCandidate(String rawToken) {
        boolean changed = SignalConceptRegistry.unblockCandidate(rawToken);
        return CompletableFuture.completedFuture(changed);
    }

    public CompletableFuture<GetSignalDisambiguationCandidates> getSignalDisambiguationCandidates(long accountId,
            int limit) {
        int bounded = Math.max(1, Math.min(500, limit <= 0 ? 100 : limit));
        ConcurrentHashMap<String, DisambiguationCandidateStats> byKey = signalDisambiguationByAccount.get(accountId);
        if (byKey == null || byKey.isEmpty()) {
            return CompletableFuture.completedFuture(new GetSignalDisambiguationCandidates(List.of()));
        }
        ArrayList<GetSignalDisambiguationCandidates.Candidate> out = new ArrayList<>();
        for (DisambiguationCandidateStats stats : byKey.values()) {
            if (stats == null || stats.seenCount <= 0 || stats.key == null || stats.key.isBlank()) {
                continue;
            }
            out.add(stats.toCandidate());
        }
        out.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
        if (out.size() > bounded) {
            out = new ArrayList<>(out.subList(0, bounded));
        }
        return CompletableFuture.completedFuture(new GetSignalDisambiguationCandidates(out));
    }

    private CompletableFuture<Integer> applyPromotedConceptToObservedRequesters(String normalizedRaw,
            String normalizedCanonical, List<SignalConceptRegistry.CandidateAccountIntentObservation> observations,
            Set<Long> skipAccountsWithStoredRawAlias) {
        if (normalizedCanonical == null || normalizedCanonical.isBlank()
                || observations == null || observations.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        Set<Long> skip = skipAccountsWithStoredRawAlias == null ? Set.of() : skipAccountsWithStoredRawAlias;
        CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
        for (SignalConceptRegistry.CandidateAccountIntentObservation observation : observations) {
            if (observation == null || observation.accountId < 0L || observation.seenCount <= 0) {
                continue;
            }
            if (skip.contains(observation.accountId)) {
                continue;
            }
            double averageValence = clampSigned(observation.averageValence);
            if (!Double.isFinite(averageValence) || Math.abs(averageValence) <= 1.0e-9) {
                continue;
            }
            SignalIntent intent = observation.intent == null ? SignalIntent.SELF : observation.intent;
            ExtractedSignal template = ExtractedSignal.from(normalizedCanonical, intent, averageValence);
            if (template == null) {
                continue;
            }
            ArrayList<ExtractedSignal> replaySignals = new ArrayList<>(observation.seenCount);
            for (int i = 0; i < observation.seenCount; i++) {
                replaySignals.add(template);
            }
            if (replaySignals.isEmpty()) {
                continue;
            }
            String sourceId = "promoted:" + normalizedRaw;
            String context = "promoted_alias=" + normalizedRaw + " | seen=" + observation.seenCount;
            chain = chain.thenCompose(total -> persistSignals(
                    observation.accountId,
                    replaySignals,
                    "signal_concept_promotion",
                    sourceId,
                    context)
                    .thenApply(ok -> ok ? total + 1 : total)
                    .exceptionally(ex -> {
                        LOG.warn("Failed concept backfill replay for account {} ({} -> {})",
                                observation.accountId, normalizedRaw, normalizedCanonical, ex);
                        return total;
                    }));
        }
        return chain;
    }

    private static Map<Long, String> ownerToAnswerIdFromContexts(List<String> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<Long, String> out = new LinkedHashMap<>();
        for (String context : contexts) {
            if (context == null || context.isBlank()) {
                continue;
            }
            String[] parts = context.split("\\|");
            String ownerId = null;
            String answerId = null;
            for (String part : parts) {
                if (part == null) {
                    continue;
                }
                String trimmed = part.trim();
                if (trimmed.startsWith("answer_owner_id=")) {
                    ownerId = trimmed.substring("answer_owner_id=".length()).trim();
                } else if (trimmed.startsWith("answer_id=")) {
                    answerId = trimmed.substring("answer_id=".length()).trim();
                }
            }
            if (ownerId != null && !ownerId.isBlank() && answerId != null && !answerId.isBlank()) {
                try {
                    long ownerIdLong = Long.parseLong(ownerId);
                    out.putIfAbsent(ownerIdLong, answerId);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static Set<String> contextValues(List<String> contexts, String key) {
        if (contexts == null || contexts.isEmpty() || key == null || key.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String prefix = key + "=";
        for (String context : contexts) {
            if (context == null || context.isBlank()) {
                continue;
            }
            String[] parts = context.split("\\|");
            for (String part : parts) {
                if (part == null) {
                    continue;
                }
                String trimmed = part.trim();
                if (!trimmed.startsWith(prefix)) {
                    continue;
                }
                String value = trimmed.substring(prefix.length()).trim();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static Set<Long> contextLongValues(List<String> contexts, String key) {
        Set<String> values = contextValues(contexts, key);
        if (values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = asTrimmedString(value);
            if (trimmed == null) {
                continue;
            }
            try {
                long parsed = Long.parseLong(trimmed);
                if (parsed >= 0L) {
                    out.add(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static Long parsePublicSourceOwnerId(String sourceId) {
        String trimmed = asTrimmedString(sourceId);
        if (trimmed == null || !trimmed.startsWith("public#")) {
            return null;
        }
        String suffix = trimmed.substring("public#".length()).trim();
        if (suffix.isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(suffix);
            return parsed >= 0L ? Long.valueOf(parsed) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean answerLikelyContainsPromotedToken(PublicPromptAnswer answer, String normalizedRaw,
            String normalizedCanonical) {
        if (answer == null) {
            return false;
        }
        List<String> tokens = SignalNormalizer.normalizeTokens(answer.getSignalTokens());
        if (tokens.contains(normalizedRaw) || tokens.contains(normalizedCanonical)) {
            return true;
        }
        String body = answer.isSetBody() ? answer.getBody() : null;
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        String rawPhrase = normalizedRaw == null ? "" : normalizedRaw.replace('_', ' ');
        String canonicalPhrase = normalizedCanonical == null ? "" : normalizedCanonical.replace('_', ' ');
        return (!rawPhrase.isBlank() && (lower.contains(rawPhrase) || lower.contains(normalizedRaw)))
                || (!canonicalPhrase.isBlank() && (lower.contains(canonicalPhrase) || lower.contains(normalizedCanonical)));
    }

    private CompletableFuture<Integer> backfillPromotedConceptFromPublicPromptContexts(String normalizedRaw,
            String normalizedCanonical, List<String> candidateContexts, Set<Long> skipAccountsWithStoredRawAlias,
            Set<Long> skipObservedAccounts) {
        if (normalizedRaw == null || normalizedRaw.isBlank()
                || normalizedCanonical == null || normalizedCanonical.isBlank()) {
            return CompletableFuture.completedFuture(0);
        }
        LinkedHashSet<Long> skip = new LinkedHashSet<>();
        if (skipAccountsWithStoredRawAlias != null) {
            skip.addAll(skipAccountsWithStoredRawAlias);
        }
        if (skipObservedAccounts != null) {
            skip.addAll(skipObservedAccounts);
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>(contextValues(candidateContexts, "source_id"));
        LinkedHashSet<String> answerIds = new LinkedHashSet<>(contextValues(candidateContexts, "answer_id"));
        answerIds.addAll(sourceIds);
        LinkedHashSet<String> promptIds = new LinkedHashSet<>(contextValues(candidateContexts, "prompt_id"));
        LinkedHashSet<Long> sourceOwnerIds = new LinkedHashSet<>();
        LinkedHashSet<Long> contextOwnerIds = new LinkedHashSet<>(contextLongValues(candidateContexts, "answer_owner_id"));
        contextOwnerIds.addAll(contextLongValues(candidateContexts, "source_owner_id"));
        Map<Long, String> ownerToAnswerId = ownerToAnswerIdFromContexts(candidateContexts);
        for (String sourceId : sourceIds) {
            Long ownerId = parsePublicSourceOwnerId(sourceId);
            if (ownerId != null) {
                sourceOwnerIds.add(ownerId);
            }
        }
        if (answerIds.isEmpty() && promptIds.isEmpty() && sourceOwnerIds.isEmpty() && contextOwnerIds.isEmpty()) {
            List<PromptDefinition> publicPrompts = PromptLibrary.publicBank();
            if (publicPrompts != null) {
                for (PromptDefinition def : publicPrompts) {
                    if (def == null) {
                        continue;
                    }
                    String promptId = asTrimmedString(def.getPromptId());
                    if (promptId != null) {
                        promptIds.add(promptId);
                    }
                }
            }
        }
        if (answerIds.isEmpty() && promptIds.isEmpty() && sourceOwnerIds.isEmpty() && contextOwnerIds.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<LinkedHashSet<String>> answerIdsFuture = CompletableFuture.completedFuture(answerIds);
        for (String promptId : promptIds) {
            if (promptId == null || promptId.isBlank()) {
                continue;
            }
            answerIdsFuture = answerIdsFuture.thenCompose(ids -> getPublicPromptAnswerIdsByPromptId
                    .invokeAsync(promptId)
                    .handle((found, ex) -> {
                        if (ex != null) {
                            LOG.warn("Failed fetching public answers for prompt {} during concept promotion {} -> {}",
                                    promptId, normalizedRaw, normalizedCanonical, ex);
                            return ids;
                        }
                        if (found != null) {
                            for (String foundId : found) {
                                String normalizedId = asTrimmedString(foundId);
                                if (normalizedId != null) {
                                    ids.add(normalizedId);
                                }
                            }
                        }
                        return ids;
                    }));
        }

        return answerIdsFuture.thenCompose(ids -> {
            CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
            LinkedHashSet<String> replayKeys = new LinkedHashSet<>();
            if (ids != null && !ids.isEmpty()) {
                for (String answerId : ids) {
                    String normalizedAnswerId = asTrimmedString(answerId);
                    if (normalizedAnswerId == null) {
                        continue;
                    }
                    chain = chain.thenCompose(total -> getPublicPromptAnswerById.invokeAsync(normalizedAnswerId)
                            .thenCompose(answer -> {
                                if (answer == null || !answer.isSetAccountId()) {
                                    return CompletableFuture.completedFuture(total);
                                }
                                long ownerId = answer.getAccountId();
                                if (ownerId < 0L || skip.contains(ownerId)) {
                                    return CompletableFuture.completedFuture(total);
                                }
                                if (!answerLikelyContainsPromotedToken(answer, normalizedRaw, normalizedCanonical)) {
                                    return CompletableFuture.completedFuture(total);
                                }
                                String replayKey = ownerId + "|" + normalizedAnswerId;
                                if (!replayKeys.add(replayKey)) {
                                    return CompletableFuture.completedFuture(total);
                                }
                                ExtractedSignal signal = ExtractedSignal.from(
                                        normalizedCanonical,
                                        SignalIntent.SELF,
                                        PUBLIC_PROMPT_OWNER_CANDIDATE_FALLBACK_VALENCE);
                                if (signal == null) {
                                    return CompletableFuture.completedFuture(total);
                                }
                                String sourceId = "promoted:" + normalizedRaw + ":answer:" + normalizedAnswerId;
                                String hierarchySourceId = "promoted_parent:owner:" + ownerId + ":answer:" + normalizedAnswerId;
                                String context = "promoted_alias=" + normalizedRaw + " | answer_id=" + normalizedAnswerId;
                                return persistSignals(ownerId,
                                        List.of(signal),
                                        "signal_concept_promotion_owner_backfill",
                                        sourceId,
                                        context,
                                        hierarchySourceId).thenApply(ok -> ok ? total + 1 : total);
                            }).exceptionally(ex -> {
                                LOG.warn("Failed contextual owner backfill for promoted concept {} -> {} answer {}",
                                        normalizedRaw, normalizedCanonical, normalizedAnswerId, ex);
                                return total;
                            }));
                }
            }
            LinkedHashSet<Long> directOwnerIds = new LinkedHashSet<>();
            directOwnerIds.addAll(sourceOwnerIds);
            directOwnerIds.addAll(contextOwnerIds);
            if (!directOwnerIds.isEmpty()) {
                for (Long ownerId : directOwnerIds) {
                    if (ownerId == null || ownerId.longValue() < 0L || skip.contains(ownerId)) {
                        continue;
                    }
                    chain = chain.thenCompose(total -> {
                        ExtractedSignal signal = ExtractedSignal.from(
                                normalizedCanonical,
                                SignalIntent.SELF,
                                PUBLIC_PROMPT_OWNER_CANDIDATE_FALLBACK_VALENCE);
                        if (signal == null) {
                            return CompletableFuture.completedFuture(total);
                        }
                        String sourceId = "promoted:" + normalizedRaw + ":source-owner:" + ownerId.longValue();
                        String knownAnswerId = ownerToAnswerId.get(ownerId);
                        String hierarchySourceId = knownAnswerId != null
                                ? "promoted_parent:owner:" + ownerId.longValue() + ":answer:" + knownAnswerId
                                : "promoted_parent:owner:" + ownerId.longValue();
                        String context = "promoted_alias=" + normalizedRaw + " | source_owner_id=" + ownerId.longValue();
                        return persistSignals(
                                ownerId.longValue(),
                                List.of(signal),
                                "signal_concept_promotion_owner_backfill",
                                sourceId,
                                context,
                                hierarchySourceId)
                                .handle((ok, ex) -> {
                                    if (ex != null) {
                                        LOG.warn("Failed source-id owner backfill for promoted concept {} -> {} owner {}",
                                                normalizedRaw, normalizedCanonical, ownerId, ex);
                                        return total;
                                    }
                                    return (ok != null && ok.booleanValue()) ? total + 1 : total;
                                });
                    });
                }
            }
            return chain;
        });
    }

    private CompletableFuture<ConceptMigrationSummary> migratePromotedConceptAcrossAccounts(String rawToken,
            String canonicalToken) {
        final String normalizedRaw = SignalNormalizer.normalizeOne(rawToken);
        final String normalizedCanonical = SignalNormalizer.normalizeOne(canonicalToken);
        if (normalizedRaw == null || normalizedRaw.isBlank()
                || normalizedCanonical == null || normalizedCanonical.isBlank()) {
            return CompletableFuture.completedFuture(new ConceptMigrationSummary());
        }
        return getSignalAccountIds.invokeAsync()
                .thenCompose(rawIds -> {
                    LinkedHashSet<Long> accountIds = new LinkedHashSet<>();
                    if (rawIds != null) {
                        for (Long rawId : rawIds) {
                            if (rawId != null && rawId >= 0L) {
                                accountIds.add(rawId);
                            }
                        }
                    }
                    if (accountIds.isEmpty()) {
                        return CompletableFuture.completedFuture(new ConceptMigrationSummary());
                    }
                    CompletableFuture<ConceptMigrationSummary> chain = CompletableFuture
                            .completedFuture(new ConceptMigrationSummary());
                    for (Long accountId : accountIds) {
                        chain = chain.thenCompose(summary -> migratePromotedConceptForAccount(
                                accountId.longValue(),
                                normalizedRaw).thenApply(outcome -> {
                                    if (outcome != null) {
                                        if (outcome.hadStoredRawAlias) {
                                            summary.accountsWithStoredRawAlias.add(accountId);
                                        }
                                        if (outcome.migrated) {
                                            summary.migratedAccounts += 1;
                                        }
                                    }
                                    return summary;
                                }));
                    }
                    return chain;
                })
                .exceptionally(ex -> {
                    LOG.warn("Failed concept migration for promoted alias {} -> {}", normalizedRaw, normalizedCanonical, ex);
                    return new ConceptMigrationSummary();
                });
    }

    private CompletableFuture<ConceptMigrationOutcome> migratePromotedConceptForAccount(long accountId,
            String normalizedRaw) {
        return getSignalsFromAccountId.invokeAsync(accountId, accountId).thenCompose(snapshot -> {
            if (snapshot == null || snapshot.getRecords() == null || snapshot.getRecords().isEmpty()) {
                return CompletableFuture.completedFuture(new ConceptMigrationOutcome(false, false));
            }
            if (!signalsContainRawAlias(snapshot, normalizedRaw)) {
                return CompletableFuture.completedFuture(new ConceptMigrationOutcome(false, false));
            }
            Signals canonicalized = canonicalizeSignalSnapshot(snapshot);
            canonicalized.setAccountId(accountId);
            return signalsDepot.appendAsync(canonicalized)
                    .thenApply(res -> new ConceptMigrationOutcome(true, true));
        }).exceptionally(ex -> {
            LOG.warn("Failed concept migration for account {} (raw alias={})", accountId, normalizedRaw,
                    ex);
            return new ConceptMigrationOutcome(false, false);
        });
    }

    private static boolean signalsContainRawAlias(Signals signals, String normalizedRaw) {
        if (normalizedRaw == null || normalizedRaw.isBlank()
                || signals == null || signals.getRecords() == null || signals.getRecords().isEmpty()) {
            return false;
        }
        for (SignalRecord record : signals.getRecords()) {
            if (record == null) {
                continue;
            }
            String token = SignalNormalizer.normalizeOne(record.getToken());
            String rawToken = SignalNormalizer.normalizeOne(record.isSetRawToken() ? record.getRawToken() : null);
            String canonicalToken = SignalNormalizer
                    .normalizeOne(record.isSetCanonicalToken() ? record.getCanonicalToken() : null);
            if (Objects.equals(token, normalizedRaw)
                    || Objects.equals(rawToken, normalizedRaw)
                    || Objects.equals(canonicalToken, normalizedRaw)) {
                return true;
            }
        }
        return false;
    }

    private CompletableFuture<Signals> maybeBootstrapSeedSignals(long requesterId, long accountId, Signals snapshot) {
        Signals canonical = canonicalizeSignalSnapshot(snapshot);
        if (requesterId != accountId || hasSignalRecords(canonical)) {
            return CompletableFuture.completedFuture(canonical);
        }

        CompletableFuture<Signals> inflight = seedSignalBootstrapByAccount.get(accountId);
        if (inflight != null) {
            return inflight.thenApply(result -> result == null ? canonical : result);
        }

        CompletableFuture<Signals> created = bootstrapSignalsFromSeededPublicAnswers(accountId, canonical)
                .exceptionally(ex -> {
                    LOG.warn("Seed signal bootstrap failed for account {}", accountId, ex);
                    return canonical;
                });
        CompletableFuture<Signals> raced = seedSignalBootstrapByAccount.putIfAbsent(accountId, created);
        if (raced != null) {
            return raced.thenApply(result -> result == null ? canonical : result);
        }
        created.whenComplete((result, ex) -> seedSignalBootstrapByAccount.remove(accountId, created));
        return created;
    }

    private CompletableFuture<Signals> bootstrapSignalsFromSeededPublicAnswers(long accountId, Signals fallback) {
        return backfillPublicPromptSignalsForAccount(accountId, false).thenCompose(softPassChanged -> readSignalsSnapshot(
                accountId, accountId).thenCompose(afterSoftPass -> {
                    Signals softSnapshot = canonicalizeSignalSnapshot(afterSoftPass);
                    if (hasSignalRecords(softSnapshot)) {
                        return CompletableFuture.completedFuture(softSnapshot);
                    }
                    return backfillPublicPromptSignalsForAccount(accountId, true).thenCompose(hardPassChanged -> {
                        if (!softPassChanged && !hardPassChanged) {
                            return CompletableFuture.completedFuture(fallback);
                        }
                        return getSignalsFromAccountId.invokeAsync(accountId, accountId)
                                .thenApply(CalypsoApiManager::canonicalizeSignalSnapshot)
                                .thenApply(refreshed -> hasSignalRecords(refreshed) ? refreshed : fallback);
                    });
                }));
    }

    private CompletableFuture<Boolean> backfillPublicPromptSignalsForAccount(long accountId, boolean forceExtraction) {
        return getMyPublicPromptAnswers.invokeAsync(accountId, accountId).thenCompose(answers -> {
            if (answers == null || answers.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(false);
            for (PublicPromptAnswer answer : answers) {
                chain = chain.thenCompose(changed -> backfillSignalsForPublicAnswer(answer, forceExtraction)
                        .thenApply(backfilled -> changed || backfilled));
            }
            return chain;
        });
    }

    private CompletableFuture<Boolean> backfillSignalsForPublicAnswer(PublicPromptAnswer answer, boolean forceExtraction) {
        if (answer == null) {
            return CompletableFuture.completedFuture(false);
        }
        String answerId = asTrimmedString(answer.getAnswerId());
        String promptId = asTrimmedString(answer.getPromptId());
        String promptText = PromptLibrary.publicTextById(promptId);
        String body = clampPromptText(answer.getBody(), 800);
        long ownerId = answer.isSetAccountId() ? answer.getAccountId() : 0L;
        if (answerId == null || promptId == null || promptText == null || body == null
                || !answer.isSetAccountId() || ownerId < 0L) {
            return CompletableFuture.completedFuture(false);
        }

        if (!forceExtraction) {
            if (hasAnswerSignalTokens(answer)) {
                return CompletableFuture.completedFuture(false);
            }
            return ensurePublicPromptAnswerSignalTokens(answer)
                    .thenApply(updated -> updated != null && hasAnswerSignalTokens(updated))
                    .exceptionally(ex -> {
                        LOG.warn("Soft backfill failed for public answer {}", answerId, ex);
                        return false;
                    });
        }

        return extractAndAppendSignalsFromPrompt(
                ownerId,
                promptId,
                promptText,
                body,
                "public_prompt",
                answerId).exceptionally(ex -> {
                    LOG.warn("Forced backfill extraction failed for public answer {}", answerId, ex);
                    return List.of();
                }).thenCompose(tokens -> {
                    List<String> normalized = SignalNormalizer.normalizeTokens(tokens);
                    if (normalized.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    if (hasAnswerSignalTokens(answer)) {
                        return CompletableFuture.completedFuture(true);
                    }
                    PublicPromptAnswer updated = new PublicPromptAnswer(answer);
                    updated.setSignalTokens(normalized);
                    updated.setUpdatedAt(System.currentTimeMillis());
                    return publicPromptAnswerDepot.appendAsync(updated)
                            .handle((ignored, ex) -> {
                                if (ex != null) {
                                    LOG.warn("Forced backfill persisted owner signals but failed answer token update {}",
                                            answerId, ex);
                                }
                                return true;
                            });
                });
    }

    private static boolean hasSignalRecords(Signals signals) {
        return signals != null
                && signals.isSetRecords()
                && signals.getRecords() != null
                && !signals.getRecords().isEmpty();
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

    private static String encodeMatchmakingFollowupPromptId(long viewerId, String missingToken, double missingValence,
            double pairScore, double uncertainty) {
        String token = SignalNormalizer.normalizeOne(missingToken);
        if (token == null) {
            token = "unknown";
        }
        double clampedValence = Math.max(-1.0, Math.min(1.0, missingValence));
        return MATCHMAKING_FOLLOWUP_PROMPT_PREFIX
                + "viewer=" + viewerId
                + "&token=" + urlEncode(token)
                + "&val=" + String.format(Locale.ROOT, "%.3f", clampedValence)
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

    private static Double parseFollowupValence(String rawValence, String token) {
        if (rawValence != null && !rawValence.isBlank()) {
            double parsed = parseDouble(rawValence, 1.0);
            return Math.max(-1.0, Math.min(1.0, parsed));
        }
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized != null && normalized.startsWith("anti_")) {
            return -1.0;
        }
        return 1.0;
    }

    private static String humanizeSignalToken(String token) {
        String normalized = SignalNormalizer.normalizeOne(token);
        if (normalized == null) {
            return "that";
        }
        String phrase = normalized.replace('_', ' ').trim();
        if (phrase.isBlank()) {
            return "that";
        }
        return phrase;
    }

   private static String buildMatchmakingFollowupQuestion(String token, Double missingValenceMaybe) {
    String normalized = SignalNormalizer.normalizeOne(token);
    if (normalized == null) {
        return "Quick matchmaking check: say a little more about what matters here.";
    }

    double missingValence = missingValenceMaybe == null ? 1.0 : missingValenceMaybe.doubleValue();
    if (missingValence < 0.0 && normalized.startsWith("anti_") && normalized.length() > "anti_".length()) {
        normalized = normalized.substring("anti_".length());
    }

    String phrase = humanizeSignalToken(normalized);

    if (missingValence < 0.0) {
        return "Quick check: how do you feel about " + phrase + "?";
    }

    return "Quick check: how do you feel about " + phrase + "?";
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

                    CompletableFuture<PrivatePromptProcessingResult> processingFuture = PRIVATE_UNIFIED_UNDERSTANDING_ENABLED
                            ? extractAndAppendFromUnifiedPrivateUnderstanding(
                                    accountId,
                                    current.getPromptId(),
                                    prompt.getText(),
                                    normalizedBody,
                                    normalizedConversation,
                                    "private_prompt",
                                    current.getInstanceId()).exceptionally(ex -> {
                                        LOG.warn("Unified private understanding failed for {}", current.getInstanceId(), ex);
                                        return PrivatePromptProcessingResult.empty(false, compactSilhouetteDelta(
                                                current.getPromptId(),
                                                prompt.getText(),
                                                normalizedBody,
                                                normalizedConversation));
                                    }).thenCompose(result -> {
                                        if (result != null && result.parsed) {
                                            return CompletableFuture.completedFuture(result);
                                        }
                                        return extractAndAppendSignalsFromPrompt(
                                                accountId,
                                                current.getPromptId(),
                                                prompt.getText(),
                                                normalizedBody,
                                                normalizedConversation,
                                                "private_prompt",
                                                current.getInstanceId()).thenApply(tokens -> new PrivatePromptProcessingResult(
                                                        tokens,
                                                        SilhouettePatch.empty(),
                                                        false,
                                                        compactSilhouetteDelta(
                                                                current.getPromptId(),
                                                                prompt.getText(),
                                                                normalizedBody,
                                                                normalizedConversation)));
                                    })
                            : extractAndAppendSignalsFromPrompt(
                                    accountId,
                                    current.getPromptId(),
                                    prompt.getText(),
                                    normalizedBody,
                                    normalizedConversation,
                                    "private_prompt",
                                    current.getInstanceId()).thenApply(tokens -> new PrivatePromptProcessingResult(
                                            tokens,
                                            SilhouettePatch.empty(),
                                            false,
                                            compactSilhouetteDelta(
                                                    current.getPromptId(),
                                                    prompt.getText(),
                                                    normalizedBody,
                                                    normalizedConversation)))
                                    .exceptionally(ex -> {
                                        LOG.warn("Signal extraction failed for private prompt answer {}", current.getInstanceId(),
                                                ex);
                                        return PrivatePromptProcessingResult.empty(false, compactSilhouetteDelta(
                                                current.getPromptId(),
                                                prompt.getText(),
                                                normalizedBody,
                                                normalizedConversation));
                                    });

                    return processingFuture.thenCompose(processing -> {
                        if (processing != null && processing.signalTokens != null && !processing.signalTokens.isEmpty()) {
                            answer.setSignalTokens(processing.signalTokens);
                        }
                        PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                        updated.setStatus(PrivatePromptStatus.ANSWERED);
                        updated.setCompletedAt(now);
                        if (updated.isSetSnoozeUntil()) {
                            updated.unsetSnoozeUntil();
                        }
                        CompletableFuture<ActivePrivatePrompt> persisted = privatePromptAnswerDepot.appendAsync(answer)
                                .thenCompose(v -> privatePromptAssignmentDepot.appendAsync(updated))
                                .thenCompose(v -> hydrateActivePrivatePrompt(updated));
                        return persisted.whenComplete((active, ex) -> {
                            if (ex == null) {
                                String context = normalizedConversation.isEmpty() ? normalizedBody
                                        : String.join(" | ", normalizedConversation);
                                if (processing != null && processing.parsed) {
                                    if (processing.patch != null && !processing.patch.isEmpty()) {
                                        queueSilhouetteUpdateAsync(
                                                accountId,
                                                "private_prompt",
                                                current.getInstanceId(),
                                                current.getPromptId(),
                                                prompt.getText(),
                                                normalizedBody,
                                                normalizedConversation,
                                                context,
                                                processing.patch,
                                                processing.delta);
                                    }
                                } else {
                                    queueSilhouetteUpdateAsync(
                                            accountId,
                                            "private_prompt",
                                            current.getInstanceId(),
                                            current.getPromptId(),
                                            prompt.getText(),
                                            normalizedBody,
                                            normalizedConversation,
                                            context);
                                }
                            }
                        });
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
        Double missingValence = parseFollowupValence(fields.get("val"), fields.get("token"));
        String questionText = buildMatchmakingFollowupQuestion(fields.get("token"), missingValence);
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
                    double missingValence = parseDouble(asTrimmedString(picked.get("missingValence")), 1.0);
                    double pairScore = parseDouble(asTrimmedString(picked.get("pairScore")), 0.0);
                    double uncertainty = parseDouble(asTrimmedString(picked.get("uncertainty")), 1.0);
                    String encodedPromptId = encodeMatchmakingFollowupPromptId(
                            viewerId,
                            missingToken,
                            missingValence,
                            pairScore,
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
                    Double missingValence = parseFollowupValence(fields.get("val"), fields.get("token"));
                    String baseQuestion = buildMatchmakingFollowupQuestion(fields.get("token"), missingValence);
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
                    Double missingValence = parseFollowupValence(fields.get("val"), fields.get("token"));
                    String question = buildMatchmakingFollowupQuestion(fields.get("token"), missingValence);

                    PrivatePromptAnswer answer = new PrivatePromptAnswer();
                    answer.setInstanceId(current.getInstanceId());
                    answer.setAccountId(accountId);
                    answer.setPromptId(MATCHMAKING_FOLLOWUP_PROMPT_ID);
                    answer.setBody(normalizedBody);
                    answer.setAnsweredAt(now);

                    CompletableFuture<PrivatePromptProcessingResult> processingFuture = PRIVATE_UNIFIED_UNDERSTANDING_ENABLED
                            ? extractAndAppendFromUnifiedPrivateUnderstanding(
                                    accountId,
                                    MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                    question,
                                    normalizedBody,
                                    normalizedConversation,
                                    "matchmaking_followup",
                                    current.getInstanceId()).exceptionally(ex -> {
                                        LOG.warn("Unified understanding failed for matchmaking followup {}", current.getInstanceId(),
                                                ex);
                                        return PrivatePromptProcessingResult.empty(false, compactSilhouetteDelta(
                                                MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                question,
                                                normalizedBody,
                                                normalizedConversation));
                                    }).thenCompose(result -> {
                                        if (result != null && result.parsed) {
                                            return CompletableFuture.completedFuture(result);
                                        }
                                        return extractAndAppendSignalsFromPrompt(
                                                accountId,
                                                MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                question,
                                                normalizedBody,
                                                normalizedConversation,
                                                "matchmaking_followup",
                                                current.getInstanceId()).thenApply(tokens -> new PrivatePromptProcessingResult(
                                                        tokens,
                                                        SilhouettePatch.empty(),
                                                        false,
                                                        compactSilhouetteDelta(
                                                                MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                                question,
                                                                normalizedBody,
                                                                normalizedConversation)));
                                    })
                            : extractAndAppendSignalsFromPrompt(
                                    accountId,
                                    MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                    question,
                                    normalizedBody,
                                    normalizedConversation,
                                    "matchmaking_followup",
                                    current.getInstanceId()).thenApply(tokens -> new PrivatePromptProcessingResult(
                                            tokens,
                                            SilhouettePatch.empty(),
                                            false,
                                            compactSilhouetteDelta(
                                                    MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                    question,
                                                    normalizedBody,
                                                    normalizedConversation)))
                                    .exceptionally(ex -> {
                                        LOG.warn("Signal extraction failed for matchmaking followup {}", current.getInstanceId(),
                                                ex);
                                        return PrivatePromptProcessingResult.empty(false, compactSilhouetteDelta(
                                                MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                question,
                                                normalizedBody,
                                                normalizedConversation));
                                    });

                    return processingFuture.thenCompose(processing -> {
                        if (processing != null && processing.signalTokens != null && !processing.signalTokens.isEmpty()) {
                            answer.setSignalTokens(processing.signalTokens);
                        }
                        PrivatePromptAssignment updated = new PrivatePromptAssignment(current);
                        updated.setStatus(PrivatePromptStatus.ANSWERED);
                        updated.setCompletedAt(now);
                        if (updated.isSetSnoozeUntil()) {
                            updated.unsetSnoozeUntil();
                        }
                        CompletableFuture<ActivePrivatePrompt> persisted = matchmakingFollowupAnswerDepot.appendAsync(answer)
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
                        return persisted.whenComplete((active, ex) -> {
                            if (ex == null) {
                                String context = normalizedConversation.isEmpty() ? normalizedBody
                                        : String.join(" | ", normalizedConversation);
                                if (processing != null && processing.parsed) {
                                    if (processing.patch != null && !processing.patch.isEmpty()) {
                                        queueSilhouetteUpdateAsync(
                                                accountId,
                                                "matchmaking_followup",
                                                current.getInstanceId(),
                                                MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                                question,
                                                normalizedBody,
                                                normalizedConversation,
                                                context,
                                                processing.patch,
                                                processing.delta);
                                    }
                                } else {
                                    queueSilhouetteUpdateAsync(
                                            accountId,
                                            "matchmaking_followup",
                                            current.getInstanceId(),
                                            MATCHMAKING_FOLLOWUP_PROMPT_ID,
                                            question,
                                            normalizedBody,
                                            normalizedConversation,
                                            context);
                                }
                            }
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
        return getSignalsFromAccountId.invokeAsync(accountId, accountId)
                .thenApply(CalypsoApiManager::canonicalizeSignalSnapshot)
                .thenApply(s -> {
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
            String normalizedToken = SignalNormalizer.normalizeOne(r.getToken());
            if (normalizedToken == null || normalizedToken.isBlank()) {
                continue;
            }
            String normalizedRaw = SignalNormalizer.normalizeOne(r.isSetRawToken() ? r.getRawToken() : normalizedToken);
            String preferredCanonical = SignalNormalizer
                    .normalizeOne(r.isSetCanonicalToken() ? r.getCanonicalToken() : normalizedToken);
            SignalConceptRegistry.Resolution resolved = SignalConceptRegistry.resolve(preferredCanonical);
            if (resolved == null || resolved.canonicalToken() == null || resolved.canonicalToken().isBlank()) {
                resolved = SignalConceptRegistry.resolve(normalizedToken);
            }
            String normalizedCanonical = resolved == null ? preferredCanonical : resolved.canonicalToken();
            if (normalizedCanonical == null || normalizedCanonical.isBlank()) {
                normalizedCanonical = normalizedToken;
            }
            r.setToken(normalizedCanonical);
            r.setCanonicalToken(normalizedCanonical);
            if (normalizedRaw != null && !normalizedRaw.isBlank()) {
                r.setRawToken(normalizedRaw);
            } else if (!r.isSetRawToken()) {
                r.setRawToken(normalizedCanonical);
            }
            SignalIntent intent = r.isSetIntent() ? r.getIntent() : null;
            if (intent == null) {
                intent = SignalIntent.SELF;
                r.setIntent(intent);
            }
            String key = recordKey(normalizedCanonical, intent);
            SignalRecord existing = map.get(key);
            if (existing != null) {
                mergeSignalRecords(existing, r);
                continue;
            }
            map.put(key, r);
        }
        return map;
    }

    private static Signals canonicalizeSignalSnapshot(Signals raw) {
        if (raw == null || raw.getRecords() == null || raw.getRecords().isEmpty()) {
            return raw;
        }
        ArrayList<SignalRecord> copied = new ArrayList<>(raw.getRecords().size());
        for (SignalRecord record : raw.getRecords()) {
            if (record != null) {
                copied.add(new SignalRecord(record));
            }
        }
        LinkedHashMap<String, SignalRecord> merged = toRecordMap(copied);
        Signals out = new Signals(raw);
        out.setRecords(new ArrayList<>(merged.values()));
        return out;
    }

    private static void mergeSignalRecords(SignalRecord target, SignalRecord incoming) {
        if (target == null || incoming == null) {
            return;
        }
        int targetCount = target.isSetCount() ? Math.max(1, target.getCount()) : 1;
        int incomingCount = incoming.isSetCount() ? Math.max(1, incoming.getCount()) : 1;
        target.setCount(targetCount + incomingCount);

        long targetFirst = target.isSetFirstSeen() ? target.getFirstSeen() : Long.MAX_VALUE;
        long incomingFirst = incoming.isSetFirstSeen() ? incoming.getFirstSeen() : Long.MAX_VALUE;
        long firstSeen = Math.min(targetFirst, incomingFirst);
        if (firstSeen != Long.MAX_VALUE) {
            target.setFirstSeen(firstSeen);
        }

        long targetTs = recordTimestamp(target);
        long incomingTs = recordTimestamp(incoming);
        long maxTs = Math.max(targetTs, incomingTs);
        if (maxTs != Long.MIN_VALUE) {
            target.setLastSeen(maxTs);
        }

        if (!target.isSetIntent() && incoming.isSetIntent()) {
            target.setIntent(incoming.getIntent());
        }

        if (incomingTs >= targetTs) {
            if (incoming.isSetSource()) {
                target.setSource(incoming.getSource());
            }
            if (incoming.isSetSourceId()) {
                target.setSourceId(incoming.getSourceId());
            }
            if (incoming.isSetLastContext()) {
                target.setLastContext(incoming.getLastContext());
            }
            if (incoming.isSetIntent()) {
                target.setIntent(incoming.getIntent());
            }
            if (incoming.isSetRawToken()) {
                target.setRawToken(incoming.getRawToken());
            }
            if (incoming.isSetCanonicalToken()) {
                target.setCanonicalToken(incoming.getCanonicalToken());
                target.setToken(incoming.getCanonicalToken());
            }
        }

        if (incoming.isSetValence()) {
            if (!target.isSetValence()
                    || incomingTs > targetTs
                    || (incomingTs == targetTs
                            && Math.abs(incoming.getValence()) > Math.abs(target.getValence()))) {
                target.setValence(incoming.getValence());
            }
        }
    }

    private static long recordTimestamp(SignalRecord record) {
        if (record == null) {
            return Long.MIN_VALUE;
        }
        if (record.isSetLastSeen()) {
            return record.getLastSeen();
        }
        if (record.isSetFirstSeen()) {
            return record.getFirstSeen();
        }
        return Long.MIN_VALUE;
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

    private static boolean requiresCanonicalMappingBeforePersist(String normalizedSource, String normalizedSourceId) {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            return false;
        }
        if (normalizedSource.startsWith("public_prompt")) {
            return true;
        }
        if (normalizedSource.startsWith("matchmaking_followup")) {
            return true;
        }
        if (normalizedSource.startsWith("private_prompt")) {
            return isUuidLike(normalizedSourceId);
        }
        return false;
    }

    private static boolean isUuidLike(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(raw.trim());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean shouldObserveUnresolvedCandidate(String normalizedSource, String context) {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            return true;
        }
        if (!normalizedSource.startsWith("private_prompt")) {
            return true;
        }
        String promptId = contextFieldValue(context, "prompt_id");
        if (promptId == null || promptId.isBlank()) {
            return true;
        }
        String normalizedPromptId = promptId.trim().toLowerCase(Locale.ROOT);
        if ("private.visual.aesthetic".equals(normalizedPromptId)) {
            return false;
        }
        if ("private.color.presence".equals(normalizedPromptId)) {
            return false;
        }
        return true;
    }

    private static String contextFieldValue(String context, String key) {
        if (context == null || context.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String marker = key.trim() + "=";
        for (String segment : context.split("\\|")) {
            if (segment == null) {
                continue;
            }
            String trimmed = segment.trim();
            if (!trimmed.startsWith(marker)) {
                continue;
            }
            String value = trimmed.substring(marker.length()).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value))
            return 0.0;
        if (value < 0.0)
            return 0.0;
        if (value > 1.0)
            return 1.0;
        return value;
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

    private static String sanitizeContextValue(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim().replace('|', ' ');
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String appendContextField(String context, String key, String value) {
        if (key == null || key.isBlank()) {
            return clampContext(context);
        }
        String sanitized = sanitizeContextValue(value);
        if (sanitized == null) {
            return clampContext(context);
        }
        String marker = key.trim() + "=";
        String base = clampContext(context);
        if (base != null && base.contains(marker)) {
            return base;
        }
        if (base == null) {
            return clampContext(marker + sanitized);
        }
        return clampContext(base + " | " + marker + sanitized);
    }

    private static String augmentObservationContext(String context, String normalizedSource, String normalizedSourceId) {
        String out = clampContext(context);
        if (normalizedSourceId == null || normalizedSourceId.isBlank()) {
            return out;
        }
        if (normalizedSource == null || !normalizedSource.startsWith("public_prompt")) {
            return out;
        }
        out = appendContextField(out, "source_id", normalizedSourceId);
        out = appendContextField(out, "answer_id", normalizedSourceId);
        return out;
    }

    private static String clampPromptText(String text, int limit) {
        if (text == null)
            return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty())
            return null;
        return trimmed.length() > limit ? trimmed.substring(0, limit) : trimmed;
    }

    private static String publicPromptReactionQuestion(String promptText, PromptReaction reaction) {
        String prompt = clampPromptText(promptText, 220);
        if (reaction == PromptReaction.DISLIKE) {
            return "Viewer DISLIKED someone else's answer to a public prompt. Infer partner preferences they want to avoid. Public prompt: "
                    + (prompt == null ? "unknown" : prompt);
        }
        return "Viewer LIKED someone else's answer to a public prompt. Infer partner preferences they are attracted to. Public prompt: "
                + (prompt == null ? "unknown" : prompt);
    }

    private static List<String> publicPromptReactionConversation(String promptId, String promptText,
            PromptReaction reaction) {
        String reactionLabel = reaction == null ? "unknown" : reaction.name().toLowerCase(Locale.ROOT);
        String prompt = clampPromptText(promptText, 220);
        ArrayList<String> out = new ArrayList<>();
        out.add("user: this is a reaction to another person's public prompt answer");
        out.add("user: reaction=" + reactionLabel);
        if (promptId != null && !promptId.isBlank()) {
            out.add("user: prompt_id=" + promptId.trim());
        }
        if (prompt != null) {
            out.add("user: original public prompt was: " + prompt);
        }
        out.add("user: infer viewer's SEEKING preferences, and use atomic canonical concept tags");
        return out;
    }

    private static String canonicalizePublicReactionToken(String rawToken) {
        String token = SignalNormalizer.normalizeOne(rawToken);
        if (token == null || token.isBlank()) {
            return null;
        }
        return token;
    }

    private static Double reactionValence(Double valenceMaybe, PromptReaction reaction) {
        boolean dislike = reaction == PromptReaction.DISLIKE;
        double floor = dislike ? PUBLIC_REACTION_DISLIKE_VALENCE_FLOOR : PUBLIC_REACTION_LIKE_VALENCE_FLOOR;
        double magnitude = floor;
        if (valenceMaybe != null && Double.isFinite(valenceMaybe.doubleValue())) {
            magnitude = Math.max(floor, Math.abs(clampSigned(valenceMaybe.doubleValue())));
        }
        return dislike ? -magnitude : magnitude;
    }

    private static double publicReactionSignalPriority(ExtractedSignal sig) {
        if (sig == null) {
            return Double.NEGATIVE_INFINITY;
        }
        double magnitude = Math.abs(clampSigned(sig.valence() == null ? 0.0 : sig.valence().doubleValue()));
        double specificity = Math.max(0.0, tokenSegments(sig.token()) - 1) * 0.05;
        return magnitude + specificity;
    }

    private static int tokenSegments(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        int segments = 1;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) == '_') {
                segments++;
            }
        }
        return segments;
    }

    private static List<ExtractedSignal> suppressPublicReactionUmbrellas(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, ExtractedSignal> byKey = new LinkedHashMap<>();
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null || sig.intent() == null) {
                continue;
            }
            String key = sig.intent().name() + "|" + sig.token();
            byKey.put(key, sig);
            tokens.add(sig.token());
        }
        if (byKey.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> dropTokens = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (tokenSegments(token) != 1) {
                continue;
            }
            for (String other : tokens) {
                if (other == null || other.equals(token)) {
                    continue;
                }
                if (other.startsWith(token + "_")) {
                    dropTokens.add(token);
                    break;
                }
            }
        }
        boolean hasSpecificReadingObject = tokens.stream().anyMatch(token -> token != null
                && (token.endsWith("_novels")
                        || token.endsWith("_books")
                        || token.endsWith("_manga")
                        || token.endsWith("_comics")));
        if (hasSpecificReadingObject) {
            dropTokens.add("reading");
            dropTokens.add("books");
        }

        ArrayList<ExtractedSignal> out = new ArrayList<>();
        for (ExtractedSignal sig : byKey.values()) {
            if (sig == null || sig.token() == null) {
                continue;
            }
            if (!dropTokens.contains(sig.token())) {
                out.add(sig);
            }
        }
        return out;
    }

    private static List<ExtractedSignal> normalizePublicPromptReactionSignals(List<ExtractedSignal> signals,
            PromptReaction reaction) {
        List<ExtractedSignal> sanitized = sanitizeSignals(signals);
        if (sanitized.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, ExtractedSignal> deduped = new LinkedHashMap<>();
        for (ExtractedSignal sig : sanitized) {
            if (sig == null) {
                continue;
            }
            String token = canonicalizePublicReactionToken(sig.token());
            if (token == null || token.isBlank()) {
                continue;
            }
            SignalIntent intent = sig.intent() == SignalIntent.META ? SignalIntent.META : SignalIntent.SEEKING;
            ExtractedSignal normalized = ExtractedSignal.from(
                    token,
                    intent,
                    reactionValence(sig.valence(), reaction));
            if (normalized == null) {
                continue;
            }
            String key = intent.name() + "|" + token;
            ExtractedSignal existing = deduped.get(key);
            if (existing == null || publicReactionSignalPriority(normalized) > publicReactionSignalPriority(existing)) {
                deduped.put(key, normalized);
            }
        }
        if (deduped.isEmpty()) {
            return List.of();
        }
        return suppressPublicReactionUmbrellas(new ArrayList<>(deduped.values()));
    }

    private CompletableFuture<List<String>> extractAndAppendSignalsFromPublicPromptReaction(long viewerId,
            String promptId, String promptText, String answerBody, PromptReaction reaction, String sourceId) {
        if (reaction != PromptReaction.LIKE && reaction != PromptReaction.DISLIKE) {
            return CompletableFuture.completedFuture(List.of());
        }
        String prompt = clampPromptText(promptText, 220);
        String answer = clampPromptText(answerBody, 800);
        if (prompt == null || answer == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        String question = publicPromptReactionQuestion(prompt, reaction);
        List<String> conversation = publicPromptReactionConversation(promptId, prompt, reaction);
        String context = "reaction=" + reaction.name().toLowerCase(Locale.ROOT) + " | prompt=" + prompt;
        return extractSignalsFromPrompt(promptId, question, answer, conversation).thenCompose(rawSignals -> {
            List<ExtractedSignal> normalized = normalizePublicPromptReactionSignals(rawSignals, reaction);
            if (normalized.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            List<String> tokens = tokens(normalized);
            CompletableFuture<List<String>> persisted = persistSignals(viewerId, normalized, "public_prompt_reaction", sourceId, context)
                    .thenApply(ok -> tokens);
            return persisted.whenComplete((result, ex) -> {
                if (ex == null) {
                    queueSilhouetteUpdateAsync(
                            viewerId,
                            "public_prompt_reaction",
                            sourceId,
                            promptId,
                            question,
                            answer,
                            conversation,
                            context);
                }
            });
        });
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

        CompletableFuture<List<String>> signals = extractAndAppendSignalsFromPrompt(accountId, promptId, promptText,
                normalized, "public_prompt", base.getAnswerId())
                .exceptionally(ex -> {
                    LOG.warn("Signal extraction failed for public prompt answer {}", base.getAnswerId(), ex);
                    return List.of();
                });

        return signals.thenCompose(tokens -> {
            PublicPromptAnswer stored = new PublicPromptAnswer(base);
            if (tokens != null && !tokens.isEmpty())
                stored.setSignalTokens(tokens);
            CompletableFuture<PublicPromptAnswer> persisted = publicPromptAnswerDepot.appendAsync(stored)
                    .thenApply(res -> new PublicPromptAnswer(stored));
            return persisted.whenComplete((result, ex) -> {
                if (ex == null) {
                    queueSilhouetteUpdateAsync(
                            accountId,
                            "public_prompt_answer",
                            stored.getAnswerId(),
                            promptId,
                            promptText,
                            normalized,
                            List.of(),
                            "prompt_id=" + promptId);
                }
            });
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

    private static Integer normalizePublicReactionStrength(Integer rawStrength) {
        if (rawStrength == null) {
            return null;
        }
        int value = rawStrength.intValue();
        if (value < PUBLIC_REACTION_STRENGTH_MIN || value > PUBLIC_REACTION_STRENGTH_MAX) {
            return null;
        }
        return value;
    }

    private static int strengthFromLegacyReaction(PromptReaction reaction) {
        if (reaction == null) {
            return 0;
        }
        switch (reaction) {
            case LIKE:
                return 1;
            case DISLIKE:
                return -1;
            case SKIP:
                return 0;
            default:
                return 0;
        }
    }

    private static PromptReaction coarseReactionFromStrength(int strength) {
        if (strength > 0) {
            return PromptReaction.LIKE;
        }
        if (strength < 0) {
            return PromptReaction.DISLIKE;
        }
        return PromptReaction.SKIP;
    }

    private static String encodePublicPromptIdWithStrength(String promptId, int strength) {
        String base = promptId == null ? "" : promptId.trim();
        if (base.isEmpty()) {
            base = "unknown_public_prompt";
        }
        int clamped = Math.max(PUBLIC_REACTION_STRENGTH_MIN, Math.min(PUBLIC_REACTION_STRENGTH_MAX, strength));
        return base + PUBLIC_REACTION_STRENGTH_PROMPT_SUFFIX + clamped;
    }

    private static boolean hasAnswerSignalTokens(PublicPromptAnswer answer) {
        return answer != null
                && answer.isSetSignalTokens()
                && answer.getSignalTokens() != null
                && !answer.getSignalTokens().isEmpty();
    }

    private CompletableFuture<PublicPromptAnswer> ensurePublicPromptAnswerSignalTokens(PublicPromptAnswer answer) {
        if (answer == null || hasAnswerSignalTokens(answer)) {
            return CompletableFuture.completedFuture(answer);
        }
        String answerId = answer.getAnswerId();
        String promptId = answer.getPromptId();
        String promptText = PromptLibrary.publicTextById(promptId);
        String body = clampPromptText(answer.getBody(), 800);
        if (answerId == null || answerId.isBlank() || promptText == null || body == null) {
            return CompletableFuture.completedFuture(answer);
        }

        long ownerId = answer.isSetAccountId() ? answer.getAccountId() : 0L;
        CompletableFuture<List<String>> extractedTokensFuture;
        if (answer.isSetAccountId() && ownerId >= 0L) {
            extractedTokensFuture = extractAndAppendSignalsFromPrompt(
                    ownerId,
                    promptId,
                    promptText,
                    body,
                    "public_prompt",
                    answerId).exceptionally(ex -> {
                        LOG.warn("Failed to backfill owner signals for answer {}", answerId, ex);
                        return List.of();
                    });
        } else {
            extractedTokensFuture = extractSignalsFromPrompt(promptId, promptText, body)
                    .thenApply(CalypsoApiManager::tokens)
                    .exceptionally(ex -> {
                        LOG.warn("Failed to backfill prompt tokens for answer {}", answerId, ex);
                        return List.of();
                    });
        }

        return extractedTokensFuture.thenCompose(extracted -> {
            List<String> normalizedTokens = SignalNormalizer.normalizeTokens(extracted);
            if (normalizedTokens.isEmpty()) {
                return CompletableFuture.completedFuture(answer);
            }
            PublicPromptAnswer updated = new PublicPromptAnswer(answer);
            updated.setSignalTokens(normalizedTokens);
            updated.setUpdatedAt(System.currentTimeMillis());
            return publicPromptAnswerDepot.appendAsync(updated)
                    .handle((ignored, ex) -> {
                        if (ex != null) {
                            LOG.warn("Failed to persist backfilled prompt tokens for answer {}", answerId, ex);
                            return answer;
                        }
                        return updated;
                    });
        });
    }

    private CompletableFuture<PublicPromptAnswer> schedulePublicPromptAnswerSignalBackfill(PublicPromptAnswer answer) {
        if (answer == null || hasAnswerSignalTokens(answer)) {
            return CompletableFuture.completedFuture(answer);
        }
        String answerId = asTrimmedString(answer.getAnswerId());
        if (answerId == null) {
            return CompletableFuture.completedFuture(answer);
        }
        CompletableFuture<PublicPromptAnswer> inflight = publicPromptSignalBackfillByAnswerId.get(answerId);
        if (inflight != null) {
            return inflight;
        }
        CompletableFuture<PublicPromptAnswer> created = ensurePublicPromptAnswerSignalTokens(answer)
                .exceptionally(ex -> {
                    LOG.warn("Async prompt-token backfill failed for answer {}", answerId, ex);
                    return answer;
                });
        CompletableFuture<PublicPromptAnswer> raced = publicPromptSignalBackfillByAnswerId.putIfAbsent(answerId,
                created);
        if (raced != null) {
            return raced;
        }
        created.whenComplete((result, ex) -> publicPromptSignalBackfillByAnswerId.remove(answerId, created));
        return created;
    }

    private void observeOwnerCandidatesFromAnswerTokensOnce(PublicPromptAnswer answer) {
        if (answer == null || !hasAnswerSignalTokens(answer)) {
            return;
        }
        if (!answer.isSetAccountId()) {
            return;
        }
        long ownerId = answer.getAccountId();
        if (ownerId < 0L) {
            return;
        }
        String answerId = asTrimmedString(answer.getAnswerId());
        if (answerId == null || !publicPromptOwnerCandidateObservedAnswerIds.add(answerId)) {
            return;
        }
        List<String> normalizedTokens = SignalNormalizer.normalizeTokens(answer.getSignalTokens());
        if (normalizedTokens.isEmpty()) {
            return;
        }
        String source = "public_prompt_owner_backfill";
        String context = "owner_candidate_observation";
        context = appendContextField(context, "answer_owner_id", Long.toString(ownerId));
        context = appendContextField(context, "answer_id", answerId);
        context = appendContextField(context, "prompt_id", answer.getPromptId());
        for (String token : normalizedTokens) {
            SignalConceptRegistry.Resolution resolution = SignalConceptRegistry.resolveForWrite(token);
            if (resolution == null || resolution.kind() != SignalConceptRegistry.ResolutionKind.UNKNOWN) {
                continue;
            }
            SignalConceptRegistry.observeUnresolved(
                    resolution.rawToken(),
                    source,
                    context,
                    ownerId,
                    SignalIntent.SELF,
                    PUBLIC_PROMPT_OWNER_CANDIDATE_FALLBACK_VALENCE);
        }
    }

    private CompletableFuture<List<ExtractedSignal>> publicReactionSignalsFromAnswer(PublicPromptAnswer answer,
            int strength) {
        if (!hasAnswerSignalTokens(answer)) {
            return CompletableFuture.completedFuture(List.of());
        }
        int bounded = Math.max(PUBLIC_REACTION_STRENGTH_MIN, Math.min(PUBLIC_REACTION_STRENGTH_MAX, strength));
        double reactionScale = (bounded / (double) PUBLIC_REACTION_STRENGTH_MAX) * PUBLIC_REACTION_VALENCE_SCALE;
        if (Math.abs(reactionScale) <= 1e-6) {
            return CompletableFuture.completedFuture(List.of());
        }
        double fallbackValence = reactionScale;
        List<String> normalizedTokens = SignalNormalizer.normalizeTokens(answer.getSignalTokens());
        if (normalizedTokens.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return resolvePublicAnswerValenceByToken(answer, normalizedTokens).thenApply(baseValenceByToken -> {
            LinkedHashMap<String, ExtractedSignal> deduped = new LinkedHashMap<>();
            for (String token : normalizedTokens) {
                if (token == null || token.isBlank()) {
                    continue;
                }
                Double baseValenceMaybe = baseValenceByToken.get(token);
                double signalValence;
                if (baseValenceMaybe == null || !Double.isFinite(baseValenceMaybe.doubleValue())) {
                    signalValence = fallbackValence;
                } else {
                    double scaledFromOwner = clampSigned(baseValenceMaybe.doubleValue() * reactionScale);
                    double magnitude = Math.max(Math.abs(fallbackValence), Math.abs(scaledFromOwner));
                    signalValence = Math.signum(reactionScale) * magnitude;
                }
                ExtractedSignal signal = ExtractedSignal.from(token, SignalIntent.SEEKING, signalValence);
                if (signal == null || signal.token() == null || signal.token().isBlank()) {
                    continue;
                }
                String key = signal.intent().name() + "|" + signal.token();
                deduped.putIfAbsent(key, signal);
            }
            if (deduped.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(deduped.values());
        });
    }

    private CompletableFuture<Map<String, Double>> resolvePublicAnswerValenceByToken(PublicPromptAnswer answer,
            List<String> normalizedTokens) {
        if (answer == null || normalizedTokens == null || normalizedTokens.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (!answer.isSetAccountId()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long ownerId = answer.getAccountId();
        if (ownerId < 0L) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String answerId = asTrimmedString(answer.getAnswerId());
        HashSet<String> wanted = new HashSet<>(normalizedTokens);
        if (wanted.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return readSignalsSnapshot(ownerId, ownerId).thenApply(snapshot -> {
            if (!hasSignalRecords(snapshot)) {
                return Map.of();
            }

            HashMap<String, Double> valenceByToken = new HashMap<>();
            HashMap<String, Integer> priorityByToken = new HashMap<>();
            HashMap<String, Long> timestampByToken = new HashMap<>();

            for (SignalRecord record : snapshot.getRecords()) {
                if (record == null) {
                    continue;
                }
                ParsedSignalToken parsed = parseSignalTokenAndValence(record);
                if (parsed == null || parsed.token == null) {
                    continue;
                }
                String normalizedToken = SignalNormalizer.normalizeOne(parsed.token);
                if (normalizedToken == null || normalizedToken.isBlank() || !wanted.contains(normalizedToken)) {
                    continue;
                }
                double valence = clampSigned(parsed.valence);
                if (Math.abs(valence) <= 1e-6) {
                    continue;
                }

                int priority = 0;
                if (record.isSetSource() && "public_prompt".equals(record.getSource())) {
                    priority += 1;
                }
                if (answerId != null && record.isSetSourceId() && answerId.equals(record.getSourceId())) {
                    priority += 2;
                }
                SignalIntent intent = record.isSetIntent() ? record.getIntent() : null;
                if (intent == null || intent == SignalIntent.SELF || intent == SignalIntent.BOTH) {
                    priority += 1;
                }
                long timestamp = recordTimestamp(record);

                Integer existingPriority = priorityByToken.get(normalizedToken);
                Double existingValence = valenceByToken.get(normalizedToken);
                Long existingTimestamp = timestampByToken.get(normalizedToken);
                boolean shouldReplace = existingPriority == null
                        || priority > existingPriority.intValue()
                        || (priority == existingPriority.intValue()
                                && (existingValence == null
                                        || Math.abs(valence) > Math.abs(existingValence.doubleValue()) + 1e-9))
                        || (priority == existingPriority.intValue()
                                && existingValence != null
                                && Math.abs(Math.abs(valence) - Math.abs(existingValence.doubleValue())) <= 1e-9
                                && timestamp > (existingTimestamp == null ? Long.MIN_VALUE : existingTimestamp));
                if (!shouldReplace) {
                    continue;
                }
                priorityByToken.put(normalizedToken, Integer.valueOf(priority));
                valenceByToken.put(normalizedToken, Double.valueOf(valence));
                timestampByToken.put(normalizedToken, Long.valueOf(timestamp));
            }
            return valenceByToken;
        });
    }

    private CompletableFuture<List<String>> appendSignalsFromPublicPromptReactionTokens(long viewerId,
            PublicPromptAnswer answer, int strength, String sourceId) {
        return publicReactionSignalsFromAnswer(answer, strength).thenCompose(signals -> {
            if (signals.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            String promptId = answer == null ? null : answer.getPromptId();
            String context = "reaction_strength=" + strength;
            if (answer != null && answer.isSetAccountId() && answer.getAccountId() >= 0L) {
                context = appendContextField(context, "answer_owner_id", Long.toString(answer.getAccountId()));
            }
            context = appendContextField(context, "prompt_id", promptId == null ? "unknown" : promptId);
            return persistSignals(viewerId, signals, "public_prompt_reaction", sourceId, context)
                    .thenApply(ok -> tokens(signals));
        });
    }

    public CompletableFuture<Boolean> postPublicPromptReaction(long viewerId, String answerId,
            PromptReaction reaction) {
        if (reaction == null) {
            throw new IllegalArgumentException("Reaction required.");
        }
        return postPublicPromptReaction(viewerId, answerId, strengthFromLegacyReaction(reaction));
    }

    public CompletableFuture<Boolean> postPublicPromptReaction(long viewerId, String answerId,
            Integer reactionStrength) {
        Integer normalizedStrength = normalizePublicReactionStrength(reactionStrength);
        if (normalizedStrength == null) {
            throw new IllegalArgumentException("Reaction strength must be between -3 and 3.");
        }
        int strength = normalizedStrength.intValue();
        return getPublicPromptAnswerById.invokeAsync(answerId).thenCompose(answer -> {
            if (answer == null) {
                throw new IllegalArgumentException("Unknown answer: " + answerId);
            }
            PublicPromptAnswer normalizedAnswer = answer;
            observeOwnerCandidatesFromAnswerTokensOnce(normalizedAnswer);
            PublicPromptReactionEvent event = new PublicPromptReactionEvent();
            event.setViewerAccountId(viewerId);
            event.setAnswerId(answerId);
            event.setPromptId(encodePublicPromptIdWithStrength(normalizedAnswer.getPromptId(), strength));
            event.setReaction(coarseReactionFromStrength(strength));
            event.setReactedAt(System.currentTimeMillis());
            CompletableFuture<Void> persist = publicPromptReactionDepot.appendAsync(event).thenApply(res -> null);
            if (strength == 0) {
                return persist.thenApply(v -> true);
            }
            if (hasAnswerSignalTokens(normalizedAnswer)) {
                return persist.thenCompose(v -> appendSignalsFromPublicPromptReactionTokens(
                        viewerId,
                        normalizedAnswer,
                        strength,
                        answerId).exceptionally(ex -> {
                            LOG.warn("Signal append failed for public prompt reaction viewer={} answer={}",
                                    viewerId, answerId, ex);
                            return List.of();
                        })).thenApply(v -> true);
            }

            // Non-blocking path for legacy/tokenless answers: persist reaction immediately and
            // append derived seeking signals once answer tokens are backfilled.
            persist.thenCompose(v -> schedulePublicPromptAnswerSignalBackfill(normalizedAnswer)
                    .thenCompose(backfilled -> {
                        PublicPromptAnswer effective = backfilled == null ? normalizedAnswer : backfilled;
                        observeOwnerCandidatesFromAnswerTokensOnce(effective);
                        if (!hasAnswerSignalTokens(effective)) {
                            return CompletableFuture.completedFuture(List.of());
                        }
                        return appendSignalsFromPublicPromptReactionTokens(
                                viewerId,
                                effective,
                                strength,
                                answerId);
                    }).exceptionally(ex -> {
                        LOG.warn("Async signal append failed for public prompt reaction viewer={} answer={}",
                                viewerId, answerId, ex);
                        return List.of();
                    }));
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

            SilhouettePatch behaviorPatch = facecardBehaviorSilhouettePatch(targetAccountId, reaction);
            String behaviorAnswer = facecardBehaviorSummary(targetAccountId, reaction);
            String behaviorSourceId = encodeFacecardReactionAnswerId(targetAccountId);
            return publicPromptReactionDepot.appendAsync(event)
                    .whenComplete((res, ex) -> {
                        if (ex != null) return;
                        if (behaviorPatch != null && !behaviorPatch.isEmpty()) {
                            queueSilhouetteUpdateAsync(
                                    viewerId,
                                    "facecard_behavior",
                                    behaviorSourceId,
                                    "facecard",
                                    "Facecard reaction",
                                    behaviorAnswer,
                                    List.of(),
                                    "reaction=" + reaction.name().toLowerCase(Locale.ROOT) + " | target=" + targetAccountId,
                                    behaviorPatch,
                                    behaviorAnswer);
                        }
                        // Fire-and-forget: record exposure without blocking the reaction response.
                        ServedPairs sp = new ServedPairs();
                        sp.setAccountId(viewerId);
                        sp.setTargetIds(List.of(targetAccountId));
                        sp.setServedAt(System.currentTimeMillis());
                        matchesServeDepot.appendAsync(sp).exceptionally(e2 -> {
                            LOG.warn("Facecard reaction recorded but exposure write failed for viewer {}", viewerId, e2);
                            return null;
                        });
                    })
                    .thenApply(ignored -> true);
        });
    }

    private static SilhouettePatch facecardBehaviorSilhouettePatch(long targetAccountId, PromptReaction reaction) {
        // TODO(physical-type): accumulate liked-candidate signals across reactions and
        // derive a "physical type" profile by finding token overlap in their SELF signals.
        // Single-reaction patches carry no interpretive weight and are suppressed until
        // that batch analysis is implemented.
        return new SilhouettePatch();
    }

    private static String facecardBehaviorSummary(long targetAccountId, PromptReaction reaction) {
        String label = reaction == null ? "reacted to" : reaction.name().toLowerCase(Locale.ROOT);
        return label + " facecard candidate " + targetAccountId;
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
    public CompletableFuture<DirectMessage> postDirectMessage(long requesterId, long viewerId, long targetAccountId,
            String text) {
        if (viewerId != requesterId) {
            throw new IllegalArgumentException("Requester must match sender.");
        }
        if (targetAccountId < 0L) {
            throw new IllegalArgumentException("Target account required.");
        }
        if (viewerId == targetAccountId) {
            throw new IllegalArgumentException("Cannot message yourself.");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Message text required.");
        }
        String trimmed = text.trim();
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException("Message too long (max 2000 chars).");
        }
        if (directMessageDepot == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Direct messaging not available yet — restart the server."));
        }
        DirectMessage msg = new DirectMessage();
        msg.setMessageId(java.util.UUID.randomUUID().toString());
        msg.setSenderId(viewerId);
        msg.setReceiverId(targetAccountId);
        msg.setText(trimmed);
        msg.setSentAt(System.currentTimeMillis());
        return directMessageDepot.appendAsync(msg)
                .thenApply(ignored -> msg)
                .exceptionally(ex -> {
                    LOG.warn("Failed to send direct message from {} to {}", viewerId, targetAccountId, ex);
                    throw new RuntimeException("Failed to send message.", ex);
                });
    }

    public CompletableFuture<List<DirectMessage>> fetchDirectMessages(long requesterId, long viewerId,
            long targetAccountId, int limit) {
        if (viewerId != requesterId) {
            throw new IllegalArgumentException("Requester must match viewer.");
        }
        if (getDirectMessages == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        int clamped = Math.min(Math.max(limit, 1), 100);
        return getDirectMessages.invokeAsync(requesterId, viewerId, targetAccountId)
                .completeOnTimeout(List.of(), 5, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to fetch direct messages for {} with {}", viewerId, targetAccountId, ex);
                    return List.of();
                })
                .thenApply(raw -> {
                    if (raw == null || raw.isEmpty()) return List.of();
                    List<DirectMessage> all = new ArrayList<>();
                    for (Object o : raw) {
                        if (o instanceof DirectMessage) all.add((DirectMessage) o);
                    }
                    return all.size() <= clamped ? all : all.subList(0, clamped);
                });
    }

    public CompletableFuture<Boolean> postSignals(long accountId, List<String> rawTokens, String source,
            String sourceId, String contextMaybe) {
        List<String> tokens = SignalConceptRegistry.normalizeAndCanonicalizeTokens(rawTokens);
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
        return persistSignals(accountId, signals, source, sourceId, contextMaybe, null);
    }

    private CompletableFuture<Boolean> persistSignals(long accountId, List<ExtractedSignal> signals, String source,
            String sourceId, String contextMaybe, String hierarchySourceId) {
        List<ExtractedSignal> sanitized = sanitizeSignals(signals);
        if (sanitized.isEmpty())
            return CompletableFuture.completedFuture(false);

        final long now = System.currentTimeMillis();
        final String normalizedSource = normalizeSource(source);
        final String normalizedSourceId = normalizeSourceId(sourceId);
        final String context = augmentObservationContext(
                clampContext(contextMaybe),
                normalizedSource,
                normalizedSourceId);
        final boolean strictCanonicalSource = requiresCanonicalMappingBeforePersist(normalizedSource, normalizedSourceId);
        int promoted = SignalConceptRegistry.autoPromoteReadyCandidatesIfDue();
        if (promoted > 0) {
            LOG.info("Auto-promoted {} signal concept candidates", promoted);
        }

        CompletableFuture<Void> chained = serialByAccount.compute(accountId, (k, prev) -> {
            CompletableFuture<Void> start = (prev == null) ? CompletableFuture.completedFuture(null) : prev;
            CompletableFuture<Void> next = start.thenCompose(
                    v -> readCurrentSignalRecords(accountId).thenCompose(current -> {
                        LinkedHashMap<String, SignalRecord> map = toRecordMap(current);
                        HashSet<String> seenInCurrentWrite = new HashSet<>();
                        for (ExtractedSignal sig : sanitized) {
                            SignalIntent intent = sig.intent();
                            double baseValence = sig.valence() == null ? 1.0 : clampSigned(sig.valence());
                            String rawToken = SignalNormalizer.normalizeOne(sig.token());
                            if (rawToken == null || rawToken.isBlank()) {
                                continue;
                            }
                            SignalConceptRegistry.Resolution resolution = SignalConceptRegistry.resolve(rawToken);
                            String canonicalToken = resolution == null ? rawToken : resolution.canonicalToken();
                            if (canonicalToken == null || canonicalToken.isBlank()) {
                                continue;
                            }
                            if (resolution != null && resolution.kind() == SignalConceptRegistry.ResolutionKind.UNKNOWN) {
                                if (shouldObserveUnresolvedCandidate(normalizedSource, context)) {
                                    SignalConceptRegistry.observeUnresolved(
                                            rawToken,
                                            normalizedSource,
                                            context,
                                            accountId,
                                            intent,
                                            baseValence);
                                }
                                if (strictCanonicalSource) {
                                    continue;
                                }
                            }
                            Map<String, Double> expanded = SignalConceptRegistry.expandedConceptWeights(
                                    canonicalToken,
                                    SIGNAL_HIERARCHY_MAX_DEPTH);
                            if (expanded == null || expanded.isEmpty()) {
                                expanded = Map.of(canonicalToken, 1.0);
                            }
                            LinkedHashMap<String, Double> expandedWithLexical = new LinkedHashMap<>();
                            for (Map.Entry<String, Double> entry : expanded.entrySet()) {
                                if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                                    continue;
                                }
                                double w = entry.getValue() == null ? 0.0 : entry.getValue().doubleValue();
                                if (Double.isNaN(w) || w <= 0.0) {
                                    continue;
                                }
                                String normalizedKey = SignalNormalizer.normalizeOne(entry.getKey());
                                if (normalizedKey == null || normalizedKey.isBlank()) {
                                    continue;
                                }
                                double existing = expandedWithLexical.getOrDefault(normalizedKey, 0.0);
                                if (w > existing) {
                                    expandedWithLexical.put(normalizedKey, w);
                                }
                            }
                            for (Map.Entry<String, Double> entry : expandedWithLexical.entrySet()) {
                                if (entry == null)
                                    continue;
                                String expandedToken = SignalNormalizer.normalizeOne(entry.getKey());
                                if (expandedToken == null || expandedToken.isBlank()) {
                                    continue;
                                }
                                double propagationWeight = entry.getValue() == null ? 0.0 : entry.getValue();
                                if (Double.isNaN(propagationWeight) || propagationWeight <= 0.0) {
                                    continue;
                                }
                                if (propagationWeight > 1.0) {
                                    propagationWeight = 1.0;
                                }
                                boolean derivedExpansion = !expandedToken.equals(canonicalToken);
                                double effectiveWeight = propagationWeight;
                                if (derivedExpansion) {
                                    effectiveWeight *= SIGNAL_HIERARCHY_DERIVED_VALENCE_SCALE;
                                }
                                String key = recordKey(expandedToken, intent);
                                boolean seenInCurrentWriteForKey = !seenInCurrentWrite.add(key);
                                SignalRecord record = map.get(key);
                                int priorCount = record == null
                                        ? 0
                                        : (record.isSetCount() ? Math.max(1, record.getCount()) : 1);
                                double scaledIncoming = clampSigned(baseValence * effectiveWeight);
                                scaledIncoming = clampSigned(
                                        scaledIncoming
                                                * sourceObservationValenceScale(
                                                        normalizedSource,
                                                        priorCount,
                                                        derivedExpansion,
                                                        record,
                                                        seenInCurrentWriteForKey));
                                if (Math.abs(scaledIncoming) < SIGNAL_HIERARCHY_MIN_VALENCE_ABS) {
                                    continue;
                                }
                                int nextCount;
                                if (record == null) {
                                    record = new SignalRecord();
                                    record.setToken(expandedToken);
                                    record.setFirstSeen(now);
                                    nextCount = 1;
                                } else {
                                    nextCount = record.isSetCount() ? record.getCount() + 1 : 1;
                                    if (!record.isSetFirstSeen())
                                        record.setFirstSeen(now);
                                    if (record.getToken() == null || record.getToken().isBlank()) {
                                        record.setToken(expandedToken);
                                    }
                                }
                                record.setCount(nextCount);
                                record.setToken(expandedToken);
                                record.setCanonicalToken(expandedToken);
                                String storedRawToken;
                                if (strictCanonicalSource) {
                                    storedRawToken = expandedToken;
                                } else if (expandedToken.equals(canonicalToken) || expandedToken.equals(rawToken)) {
                                    storedRawToken = rawToken;
                                } else {
                                    storedRawToken = expandedToken;
                                }
                                String effectiveSource = derivedExpansion ? SIGNAL_HIERARCHY_DERIVED_SOURCE
                                        : normalizedSource;
                                String effectiveSourceId = derivedExpansion
                                        ? (hierarchySourceId != null ? hierarchySourceId : null)
                                        : normalizedSourceId;
                                // Idempotency: if this derived/parent signal was already written
                                // from the same hierarchy source (e.g. two child promotions from
                                // the same answer), skip to avoid double-counting the parent.
                                if (derivedExpansion
                                        && effectiveSourceId != null
                                        && record != null
                                        && record.isSetSourceId()
                                        && effectiveSourceId.equals(record.getSourceId())) {
                                    continue;
                                }
                                boolean preserveExistingCoreAttribution = derivedExpansion
                                        && priorCount > 0
                                        && record.isSetSource()
                                        && !SIGNAL_HIERARCHY_DERIVED_SOURCE.equals(record.getSource());
                                if (!preserveExistingCoreAttribution || !record.isSetRawToken()) {
                                    record.setRawToken(storedRawToken);
                                }
                                if (!preserveExistingCoreAttribution || !record.isSetSource()) {
                                    record.setSource(effectiveSource);
                                }
                                if (effectiveSourceId != null
                                        && (!preserveExistingCoreAttribution || !record.isSetSourceId())) {
                                    record.setSourceId(effectiveSourceId);
                                }
                                record.setLastSeen(now);
                                if (context != null
                                        && (!preserveExistingCoreAttribution || !record.isSetLastContext())) {
                                    if (expandedToken.equals(rawToken) && !expandedToken.equals(canonicalToken)) {
                                        record.setLastContext("canonical=" + canonicalToken + " | " + context);
                                    } else if (expandedToken.equals(canonicalToken)) {
                                        record.setLastContext(context);
                                    } else {
                                        record.setLastContext("derived_from=" + canonicalToken + " | " + context);
                                    }
                                }
                                if (intent != null)
                                    record.setIntent(intent);
                                if (priorCount > 0 && record.isSetValence()) {
                                    double previousValence = clampSigned(record.getValence());
                                    double blended = blendStoredValence(previousValence, scaledIncoming, priorCount);
                                    record.setValence(applyValenceCountCeiling(blended, nextCount));
                                } else {
                                    // First observation: store the source-dampened value directly.
                                    // The ceiling applies only on subsequent blends to govern growth rate.
                                    record.setValence(scaledIncoming);
                                }
                                String finalKey = recordKey(record);
                                if (finalKey == null) {
                                    continue;
                                }
                                if (!finalKey.equals(key)) {
                                    map.remove(key);
                                }
                                map.put(finalKey, record);
                            }
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
        return extractSignalsFromPrompt(null, question, answer, List.of());
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromPrompt(String question, String answer,
            List<String> conversationLines) {
        return extractSignalsFromPrompt(null, question, answer, conversationLines);
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromPrompt(String promptId, String question,
            String answer) {
        return extractSignalsFromPrompt(promptId, question, answer, List.of());
    }

    public CompletableFuture<List<ExtractedSignal>> extractSignalsFromPrompt(String promptId, String question,
            String answer, List<String> conversationLines) {
        return CompletableFuture.supplyAsync(
                () -> SignalExtractor.extractFromPromptAnswer(openAI, promptId, question, answer, conversationLines,
                        Set.of()));
    }

    private void observePromptDisambiguationCandidates(
            long accountId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            List<ExtractedSignal> extractedSignals,
            String source,
            String sourceId,
            String contextMaybe) {
        if (accountId < 0L) {
            return;
        }
        List<SignalDisambiguationPlanner.FollowupCandidate> candidates = SignalDisambiguationPlanner.detectPromptAmbiguities(
                promptId,
                question,
                answer,
                conversationLines,
                extractedSignals);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        String normalizedSource = normalizeSource(source);
        String normalizedSourceId = normalizeSourceId(sourceId);
        String context = clampContext(contextMaybe);
        ConcurrentHashMap<String, DisambiguationCandidateStats> byKey = signalDisambiguationByAccount.computeIfAbsent(
                accountId,
                k -> new ConcurrentHashMap<>());
        for (SignalDisambiguationPlanner.FollowupCandidate candidate : candidates) {
            if (candidate == null || candidate.key == null || candidate.key.isBlank()
                    || candidate.term == null || candidate.term.isBlank()
                    || candidate.question == null || candidate.question.isBlank()) {
                continue;
            }
            String normalizedKey = SignalNormalizer.normalizeOne(candidate.key);
            if (normalizedKey == null || normalizedKey.isBlank()) {
                continue;
            }
            String normalizedTerm = SignalNormalizer.normalizeOne(candidate.term);
            if (normalizedTerm == null || normalizedTerm.isBlank()) {
                continue;
            }
            DisambiguationCandidateStats stats = byKey.computeIfAbsent(
                    normalizedKey,
                    k -> new DisambiguationCandidateStats(
                            normalizedKey,
                            normalizedTerm,
                            candidate.question.trim(),
                            asTrimmedString(candidate.promptId)));
            stats.record(normalizedSource, normalizedSourceId, context);
        }
        if (byKey.size() > DISAMBIGUATION_MAX_PER_ACCOUNT) {
            ArrayList<DisambiguationCandidateStats> all = new ArrayList<>(byKey.values());
            all.sort(Comparator.comparingLong(stats -> stats == null ? Long.MAX_VALUE : stats.firstSeen));
            int toRemove = byKey.size() - DISAMBIGUATION_MAX_PER_ACCOUNT;
            for (int i = 0; i < toRemove && i < all.size(); i++) {
                DisambiguationCandidateStats oldest = all.get(i);
                if (oldest == null || oldest.key == null || oldest.key.isBlank()) {
                    continue;
                }
                byKey.remove(oldest.key, oldest);
            }
        }
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
        return extractAndAppendSignalsFromPrompt(accountId, null, question, answer, List.of(), source, sourceId);
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String question,
            String answer, List<String> conversationLines, String source, String sourceId) {
        return extractAndAppendSignalsFromPrompt(accountId, null, question, answer, conversationLines, source, sourceId);
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String promptId,
            String question, String answer, String source, String sourceId) {
        return extractAndAppendSignalsFromPrompt(accountId, promptId, question, answer, List.of(), source, sourceId);
    }

    public CompletableFuture<List<String>> extractAndAppendSignalsFromPrompt(long accountId, String promptId,
            String question, String answer, List<String> conversationLines, String source, String sourceId) {
        List<String> normalizedConversation = clampConversationLines(conversationLines, 40, 320);
        String context = normalizedConversation.isEmpty() ? answer : String.join(" | ", normalizedConversation);
        if (promptId != null && !promptId.isBlank()) {
            context = appendContextField(context, "prompt_id", promptId.trim());
        }
        final String finalContext = context;
        return extractSignalsFromPrompt(promptId, question, answer, normalizedConversation).thenCompose(signals -> {
            observePromptDisambiguationCandidates(
                    accountId,
                    promptId,
                    question,
                    answer,
                    normalizedConversation,
                    signals,
                    source,
                    sourceId,
                    finalContext);
            if (signals.isEmpty())
                return CompletableFuture.completedFuture(List.of());
            List<String> tokens = tokens(signals);
            return persistSignals(accountId, signals, source, sourceId, finalContext).thenApply(ok -> tokens);
        });
    }

    private CompletableFuture<PrivatePromptProcessingResult> extractAndAppendFromUnifiedPrivateUnderstanding(
            long accountId,
            String promptId,
            String question,
            String answer,
            List<String> conversationLines,
            String source,
            String sourceId) {
        List<String> normalizedConversation = clampConversationLines(conversationLines, 40, 320);
        String context = normalizedConversation.isEmpty() ? answer : String.join(" | ", normalizedConversation);
        if (promptId != null && !promptId.isBlank()) {
            context = appendContextField(context, "prompt_id", promptId.trim());
        }
        final String finalContext = context;
        return CompletableFuture.supplyAsync(() -> PrivatePromptUnderstanding.generate(
                openAI,
                promptId,
                question,
                answer,
                normalizedConversation,
                Set.of())).thenCompose(understanding -> {
                    if (understanding == null || !understanding.parsed) {
                        return CompletableFuture.completedFuture(
                                PrivatePromptProcessingResult.empty(false, compactSilhouetteDelta(promptId, question, answer,
                                        normalizedConversation)));
                    }
                    List<ExtractedSignal> rawSignals = understanding.signals == null ? List.of() : understanding.signals;
                    List<ExtractedSignal> signals = SignalExtractor.augmentWithExplicitTitleMentions(
                            promptId,
                            question,
                            answer,
                            Set.of(),
                            rawSignals);
                    CompletableFuture<List<ExtractedSignal>> finalSignalsFuture = shouldBackstopUnifiedSignals(promptId, signals)
                            ? extractSignalsFromPrompt(promptId, question, answer, normalizedConversation)
                                    .thenApply(backstop -> mergeExtractedSignals(signals, backstop))
                            : CompletableFuture.completedFuture(signals);
                    return finalSignalsFuture.thenCompose(finalSignals -> {
                        observePromptDisambiguationCandidates(
                                accountId,
                                promptId,
                                question,
                                answer,
                                normalizedConversation,
                                finalSignals,
                                source,
                                sourceId,
                                finalContext);
                        SilhouettePatch residualPatch = sanitizeSilhouettePatchForResidualSemantics(
                                understanding.patch,
                                finalSignals,
                                promptId);
                        if (finalSignals.isEmpty()) {
                            return CompletableFuture.completedFuture(
                                    new PrivatePromptProcessingResult(
                                            List.of(),
                                            residualPatch,
                                            true,
                                            compactSilhouetteDelta(promptId, question, answer, normalizedConversation)));
                        }
                        List<String> extractedTokens = tokens(finalSignals);
                        return persistSignals(accountId, finalSignals, source, sourceId, finalContext)
                                .thenApply(ok -> new PrivatePromptProcessingResult(
                                        extractedTokens,
                                        residualPatch,
                                        true,
                                        compactSilhouetteDelta(promptId, question, answer, normalizedConversation)));
                    });
                });
    }

    private static boolean shouldBackstopUnifiedSignals(String promptId, List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return true;
        }
        String normalizedPrompt = promptId == null ? "" : promptId.trim().toLowerCase(Locale.ROOT);
        if (!SILHOUETTE_SIGNAL_FIRST_PROMPT_IDS.contains(normalizedPrompt)) {
            return false;
        }
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String category = SignalConceptRegistry.categoryForConcept(signal.token());
            if (SignalTaxonomy.isConcreteCategory(category)) {
                return false;
            }
        }
        return true;
    }

    private static List<ExtractedSignal> mergeExtractedSignals(
            List<ExtractedSignal> primary,
            List<ExtractedSignal> backstop) {
        LinkedHashMap<String, ExtractedSignal> out = new LinkedHashMap<>();
        mergeExtractedSignalList(out, primary);
        mergeExtractedSignalList(out, backstop);
        return new ArrayList<>(out.values());
    }

    private static void mergeExtractedSignalList(
            LinkedHashMap<String, ExtractedSignal> out,
            List<ExtractedSignal> signals) {
        if (out == null || signals == null || signals.isEmpty()) {
            return;
        }
        for (ExtractedSignal signal : signals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String token = SignalNormalizer.normalizeOne(signal.token());
            if (token == null || token.isBlank()) {
                continue;
            }
            SignalIntent intent = signal.intent() == null ? SignalIntent.SELF : signal.intent();
            String key = intent.name() + "|" + token;
            ExtractedSignal normalized = ExtractedSignal.from(token, intent, signal.valence());
            if (normalized == null) {
                continue;
            }
            ExtractedSignal existing = out.get(key);
            if (existing == null || signalMagnitude(normalized) > signalMagnitude(existing)) {
                out.put(key, normalized);
            }
        }
    }

    private static double signalMagnitude(ExtractedSignal signal) {
        if (signal == null || signal.valence() == null) {
            return 0.0;
        }
        return Math.abs(signal.valence().doubleValue());
    }

    private static SilhouettePatch sanitizeSilhouettePatchForResidualSemantics(
            SilhouettePatch patch,
            List<ExtractedSignal> extractedSignals,
            String promptId) {
        if (patch == null || patch.isEmpty() || patch.ops == null || patch.ops.isEmpty()) {
            return SilhouettePatch.empty();
        }
        Set<String> concreteTokens = concreteSignalTokens(extractedSignals);
        boolean signalFirstPrompt = isSilhouetteSignalFirstPrompt(promptId);
        SilhouettePatch out = new SilhouettePatch();
        for (SilhouettePatch.Op op : patch.ops) {
            if (op == null || op.op == null || op.op.isBlank()) {
                continue;
            }
            if (isLowValueMetaObservationOp(op)) {
                continue;
            }
            if (isSilhouetteDislikePrompt(promptId) && isNonBoundaryConceptOp(op)) {
                SilhouettePatch.Op anti = convertDislikeConceptToAntiPattern(op);
                if (anti != null) {
                    out.ops.add(anti);
                }
                continue;
            }
            if (shouldSuppressSilhouetteOpAsConcreteEcho(op, concreteTokens)) {
                continue;
            }
            if (signalFirstPrompt && shouldSuppressSignalFirstSilhouetteOp(op, concreteTokens)) {
                continue;
            }
            out.ops.add(op);
        }
        return out;
    }

    private static boolean isSilhouetteDislikePrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return false;
        }
        String normalized = promptId.trim().toLowerCase(Locale.ROOT);
        return "private.popular.dislike".equals(normalized)
                || "private.not.my.person".equals(normalized);
    }

    private static boolean isNonBoundaryConceptOp(SilhouettePatch.Op op) {
        if (op == null || op.op == null) {
            return false;
        }
        if (!"upsert_concept".equals(op.op) && !"reinforce_concept".equals(op.op)) {
            return false;
        }
        String target = SilhouetteEvidence.normalizeTarget(op.target);
        return !"anti_patterns".equals(target) && !"tensions".equals(target);
    }

    private static SilhouettePatch.Op convertDislikeConceptToAntiPattern(SilhouettePatch.Op op) {
        if (op == null) {
            return null;
        }
        String evidenceValue = op.evidence == null ? null : asTrimmedString(op.evidence.value);
        String conceptLabel = op.concept == null ? null : asTrimmedString(op.concept.label);
        String label = evidenceValue != null ? evidenceValue : conceptLabel;
        if (label == null || label.isBlank()) {
            return null;
        }
        SilhouetteAntiPattern anti = new SilhouetteAntiPattern();
        anti.label = label.length() > 120 ? label.substring(0, 120).trim() : label;
        String normalizedLabel = SignalNormalizer.normalizeOne(anti.label);
        anti.id = "anti_" + (normalizedLabel == null || normalizedLabel.isBlank() ? "boundary" : normalizedLabel);
        anti.scope = "relational";
        anti.severity = "low";
        double confidence = 0.50;
        if (op.concept != null) {
            confidence = Math.max(confidence, Math.min(0.62, op.concept.confidence));
        }
        if (op.evidence != null) {
            confidence = Math.max(confidence, Math.min(0.62, op.evidence.confidence));
        }
        anti.confidence = confidence;

        SilhouetteEvidence evidence = op.evidence == null ? new SilhouetteEvidence() : new SilhouetteEvidence(op.evidence);
        evidence.target = "anti_patterns";
        if (evidence.value == null || evidence.value.isBlank()) {
            evidence.value = anti.label;
        }
        if (evidence.confidence <= 0.0) {
            evidence.confidence = confidence;
        }
        if (evidence.strength <= 0.0) {
            evidence.strength = 0.55;
        }

        SilhouettePatch.Op out = SilhouettePatch.Op.upsertAntiPattern(
                "mode_boundary_clarity",
                "boundary clarity",
                anti,
                evidence);
        return out;
    }

    private static boolean isSilhouetteSignalFirstPrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) {
            return false;
        }
        String normalized = promptId.trim().toLowerCase(Locale.ROOT);
        return SILHOUETTE_SIGNAL_FIRST_PROMPT_IDS.contains(normalized);
    }

    private static boolean shouldSuppressSignalFirstSilhouetteOp(
            SilhouettePatch.Op op,
            Set<String> concreteTokens) {
        if (op == null) {
            return false;
        }
        if ("add_evidence".equals(op.op)) {
            return false;
        }
        String text = silhouetteOpSurfaceText(op);
        if (text == null || text.isBlank()) {
            return true;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String cue : SILHOUETTE_ABSTRACT_CUE_TERMS) {
            if (cue != null && !cue.isBlank() && lowered.contains(cue)) {
                return false;
            }
        }
        if (concreteTokens == null || concreteTokens.isEmpty()) {
            return true;
        }
        String normalizedText = normalizePhraseText(text);
        for (String token : concreteTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (containsPhrase(normalizedText, token.replace('_', ' '))) {
                return true;
            }
        }
        return true;
    }

    private static Set<String> concreteSignalTokens(List<ExtractedSignal> extractedSignals) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (extractedSignals == null || extractedSignals.isEmpty()) {
            return out;
        }
        for (ExtractedSignal signal : extractedSignals) {
            if (signal == null || signal.token() == null || signal.token().isBlank()) {
                continue;
            }
            String token = SignalNormalizer.normalizeOne(signal.token());
            if (token == null || token.isBlank()) {
                continue;
            }
            String category = SignalConceptRegistry.categoryForConcept(token);
            if (SignalTaxonomy.isConcreteCategory(category)) {
                out.add(token);
            }
        }
        return out;
    }

    private static boolean shouldSuppressSilhouetteOpAsConcreteEcho(
            SilhouettePatch.Op op,
            Set<String> concreteTokens) {
        if (op == null) {
            return false;
        }
        if ("add_evidence".equals(op.op)) {
            return false;
        }
        if (op.evidence != null) {
            return false;
        }
        if (concreteTokens == null || concreteTokens.isEmpty()) {
            return false;
        }
        String text = silhouetteOpSurfaceText(op);
        if (text == null || text.isBlank()) {
            return false;
        }

        LinkedHashSet<String> matched = new LinkedHashSet<>();
        String normalizedText = normalizePhraseText(text);
        for (String token : concreteTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String phrase = token.replace('_', ' ');
            if (containsPhrase(normalizedText, phrase)) {
                matched.add(token);
            }
        }
        if (matched.isEmpty()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String cue : SILHOUETTE_ABSTRACT_CUE_TERMS) {
            if (cue != null && !cue.isBlank() && lowered.contains(cue)) {
                return false;
            }
        }
        String residual = normalizedText;
        for (String token : matched) {
            residual = residual.replace(token.replace('_', ' '), " ");
        }
        residual = residual
                .replaceAll(
                        "\\b(prefers?|prefer|wants?|wanted|share|shares|shared|interests?|hobb(?:y|ies)|exclude|excluding|dislike|dislikes|disliked|hate|hates|avoid|avoids|turn\\s+off|people|person|partners?|who|engage|with|into|culture|scene|vibes?|social|community|belonging|home|primary|identif(?:y|ies)|strongly|around|for|to|and|or|the|a|an|of|on|in|is|are|be|not|no|get|dont|don't|just)\\b",
                        " ")
                .replaceAll("\\s+", " ")
                .trim();

        String target = SilhouetteEvidence.normalizeTarget(op.target);
        if ("anti_patterns".equals(target)) {
            return residual.length() <= 40;
        }
        return residual.length() <= 30;
    }

    private static boolean isLowValueMetaObservationOp(SilhouettePatch.Op op) {
        if (op == null) {
            return false;
        }
        if (!"add_open_question".equals(op.op)) {
            return false;
        }
        String text = asTrimmedString(op.openQuestion);
        if (text == null || text.isBlank()) {
            return true;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        for (String generic : SILHOUETTE_GENERIC_META_SUBSTRINGS) {
            if (generic != null && !generic.isBlank() && lowered.contains(generic)) {
                return true;
            }
        }
        return lowered.contains("focuses on")
                && (lowered.contains("lifestyle")
                        || lowered.contains("filters")
                        || lowered.contains("compatibility"));
    }

    private static String silhouetteOpSurfaceText(SilhouettePatch.Op op) {
        if (op == null) {
            return null;
        }
        if (op.concept != null && op.concept.label != null && !op.concept.label.isBlank()) {
            return op.concept.label;
        }
        if (op.antiPattern != null && op.antiPattern.label != null && !op.antiPattern.label.isBlank()) {
            return op.antiPattern.label;
        }
        if (op.tension != null && op.tension.a != null && op.tension.b != null) {
            return op.tension.a + " " + op.tension.b;
        }
        if (op.label != null && !op.label.isBlank()) {
            return op.label;
        }
        if (op.openQuestion != null && !op.openQuestion.isBlank()) {
            return op.openQuestion;
        }
        return null;
    }

    private static String normalizePhraseText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsPhrase(String normalizedText, String phrase) {
        if (normalizedText == null || normalizedText.isBlank() || phrase == null || phrase.isBlank()) {
            return false;
        }
        String normalizedPhrase = phrase.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalizedPhrase.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static String compactSilhouetteDelta(
            String promptId,
            String question,
            String answer,
            List<String> conversationLines) {
        String prompt = clampPromptText(promptId, 96);
        String q = clampPromptText(question, 120);
        String a = clampPromptText(answer, 180);
        String convo = "";
        if (conversationLines != null && !conversationLines.isEmpty()) {
            int start = Math.max(0, conversationLines.size() - 4);
            List<String> tail = conversationLines.subList(start, conversationLines.size());
            convo = clampPromptText(String.join(" | ", tail), 180);
        }
        String delta = null;
        delta = appendContextField(delta, "prompt_id", prompt == null ? "" : prompt);
        delta = appendContextField(delta, "q", q == null ? "" : q);
        delta = appendContextField(delta, "a", a == null ? "" : a);
        delta = appendContextField(delta, "tail", convo == null ? "" : convo);
        return clampContext(delta);
    }

    private static List<String> tokens(List<ExtractedSignal> signals) {
        if (signals == null || signals.isEmpty())
            return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (ExtractedSignal sig : signals) {
            if (sig == null || sig.token() == null) {
                continue;
            }
            String normalized = SignalNormalizer.normalizeOne(sig.token());
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            unique.add(normalized);
        }
        return new ArrayList<>(unique);
    }

    private static final class PrivatePromptProcessingResult {
        final List<String> signalTokens;
        final SilhouettePatch patch;
        final boolean parsed;
        final String delta;

        PrivatePromptProcessingResult(List<String> signalTokens, SilhouettePatch patch, boolean parsed, String delta) {
            this.signalTokens = signalTokens == null ? List.of() : List.copyOf(signalTokens);
            this.patch = patch == null ? SilhouettePatch.empty() : patch;
            this.parsed = parsed;
            this.delta = delta;
        }

        static PrivatePromptProcessingResult empty(boolean parsed, String delta) {
            return new PrivatePromptProcessingResult(List.of(), SilhouettePatch.empty(), parsed, delta);
        }
    }

    private CompletableFuture<Void> requestRefill(long viewerId, int targetSize) {
        if (viewerId < 0L) {
            return CompletableFuture.completedFuture(null);
        }
        long requestAt = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> selected = new java.util.concurrent.atomic.AtomicReference<>();
        matchRefillRequestByAccount.compute(viewerId, (accountId, current) -> {
            if (current != null && !current.isDone()) {
                selected.set(current);
                return current;
            }
            Long lastRequestAt = lastMatchRefillRequestAtByAccount.get(accountId);
            if (lastRequestAt != null && requestAt - lastRequestAt.longValue() < MATCH_REFILL_REQUEST_COOLDOWN_MS) {
                selected.set(CompletableFuture.completedFuture(null));
                return null;
            }
            MatchRefillRequest req = new MatchRefillRequest();
            req.setAccountId(accountId);
            req.setTargetSize(targetSize);
            CompletableFuture<Void> refill = matchRefillDepot.appendAsync(req).thenApply(x -> null);
            lastMatchRefillRequestAtByAccount.put(accountId, requestAt);
            refill.whenComplete((ignored, ex) -> matchRefillRequestByAccount.remove(accountId, refill));
            selected.set(refill);
            return refill;
        });
        CompletableFuture<Void> refill = selected.get();
        return refill == null ? CompletableFuture.completedFuture(null) : refill;
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

    private static double clampSigned(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value < -1.0) {
            return -1.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static double sourceObservationValenceScale(
            String source,
            int priorCount,
            boolean derivedExpansion,
            SignalRecord existingRecord,
            boolean seenInCurrentWriteForKey) {
        String normalized = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        SourceValenceScaleProfile profile = sourceValenceScaleProfile(normalized);
        if (profile == null) {
            return 1.0;
        }
        if (seenInCurrentWriteForKey) {
            return profile.firstHitScale;
        }
        if (priorCount <= 0) {
            return profile.firstHitScale;
        }
        boolean existingDerivedOnly = existingRecord != null
                && existingRecord.isSetSource()
                && SIGNAL_HIERARCHY_DERIVED_SOURCE.equals(existingRecord.getSource());
        if (!derivedExpansion && existingDerivedOnly) {
            return profile.firstHitScale;
        }
        return profile.repeatScale;
    }

    private static SourceValenceScaleProfile sourceValenceScaleProfile(String normalizedSource) {
        if (normalizedSource == null || normalizedSource.isBlank()) {
            return null;
        }
        // These sources carry pre-calibrated or already-observed valences; do not dampen.
        if (normalizedSource.contains("public_prompt_reaction")) {
            return null;
        }
        // signal_concept_promotion replays observed drift-queue valences verbatim;
        // the count-based ceiling (applyValenceCountCeiling) already constrains any
        // first-hit replay to ≤ 0.20, so no additional source-level dampening is needed.
        // The _owner_backfill variant also uses a pre-calibrated low fallback (0.30).
        if (normalizedSource.contains("signal_concept_promotion")) {
            return null;
        }
        if (normalizedSource.contains("private_prompt")) {
            return new SourceValenceScaleProfile(
                    PRIVATE_PROMPT_FIRST_HIT_VALENCE_SCALE,
                    PRIVATE_PROMPT_REPEAT_VALENCE_SCALE);
        }
        if (normalizedSource.contains("matchmaking_followup")) {
            return new SourceValenceScaleProfile(
                    MATCHMAKING_FOLLOWUP_FIRST_HIT_VALENCE_SCALE,
                    MATCHMAKING_FOLLOWUP_REPEAT_VALENCE_SCALE);
        }
        if (normalizedSource.contains("public_prompt")) {
            return new SourceValenceScaleProfile(
                    PUBLIC_PROMPT_FIRST_HIT_VALENCE_SCALE,
                    PUBLIC_PROMPT_REPEAT_VALENCE_SCALE);
        }
        // All unrecognized sources (agent conversation, backfill, etc.) get conservative
        // dampening so raw LLM valences don't land at full strength on first observation.
        return new SourceValenceScaleProfile(
                DEFAULT_SOURCE_FIRST_HIT_VALENCE_SCALE,
                DEFAULT_SOURCE_REPEAT_VALENCE_SCALE);
    }

    /**
     * Caps the magnitude of a stored valence based on how many observations have
     * accumulated. ceiling(n) = n / (n + softness), so reaching 1.0 requires many
     * strong observations rather than a single high-valence extraction.
     */
    private static double applyValenceCountCeiling(double valence, int count) {
        if (count <= 0) {
            return valence;
        }
        double ceiling = count / (count + VALENCE_COUNT_CEILING_SOFTNESS);
        double sign = Math.signum(valence);
        double magnitude = Math.abs(valence);
        return sign * Math.min(magnitude, ceiling);
    }

    private static double blendStoredValence(double previousValence, double incomingValence, int priorCount) {
        double prev = clampSigned(previousValence);
        double inc = clampSigned(incomingValence);
        if (Math.abs(prev) <= 1e-6) {
            return inc;
        }
        double prevSign = Math.signum(prev);
        double incSign = Math.signum(inc);
        if (Math.abs(inc) <= 1e-6) {
            return prev;
        }
        if (prevSign == incSign) {
            double prevMagnitude = Math.abs(prev);
            double incomingMagnitude = Math.abs(inc);
            double countBoost = Math.min(1.0, Math.log1p(Math.max(1, priorCount)) / 2.2);
            if (incomingMagnitude + 1.0e-6 < prevMagnitude) {
                double soften = (prevMagnitude - incomingMagnitude) * (0.10 + (0.10 * countBoost));
                double softenedMagnitude = Math.max(incomingMagnitude, prevMagnitude - soften);
                return clampSigned(prevSign * softenedMagnitude);
            }
            double lift = (1.0 - prevMagnitude) * incomingMagnitude * (0.22 + (0.18 * countBoost));
            double reinforcedMagnitude = Math.min(1.0, prevMagnitude + lift);
            return clampSigned(prevSign * reinforcedMagnitude);
        }

        // Opposing evidence should soften confidently but not erase immediately.
        double previousWeight = Math.max(1.0, Math.log1p(Math.max(1, priorCount)));
        double incomingWeight = 1.0 + (Math.abs(inc) * 1.5);
        double blended = ((prev * previousWeight) + (inc * incomingWeight)) / (previousWeight + incomingWeight);
        return clampSigned(blended);
    }

    private static int rerankPoolLimit(int requestedLimit) {
        int clamped = clampMatchLimit(requestedLimit);
        int pool = Math.max(clamped, clamped * MATCH_RERANK_POOL_MULTIPLIER);
        pool = Math.max(pool, MATCH_RERANK_POOL_MIN);
        pool = Math.min(pool, MATCH_RERANK_POOL_MAX);
        return Math.max(clamped, pool);
    }

    private static List<GetMatch> limitMatches(List<GetMatch> matches, int limit) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        int clamped = clampMatchLimit(limit);
        if (matches.size() <= clamped) {
            return matches;
        }
        return new ArrayList<>(matches.subList(0, clamped));
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

    private static MatchCandidate candidateFromHeap(Object rawHeap, long targetAccountId) {
        if (!(rawHeap instanceof List<?> heap) || targetAccountId < 0L) {
            return null;
        }
        for (Object entry : heap) {
            if (!(entry instanceof MatchCandidate candidate)) {
                continue;
            }
            if (candidate.getTargetAccountId() == targetAccountId) {
                return candidate;
            }
        }
        return null;
    }

    private static void putFiniteMetric(Map<String, Object> out, String key, Double value) {
        if (out == null || key == null || key.isBlank() || value == null || !Double.isFinite(value.doubleValue())) {
            return;
        }
        out.put(key, value.doubleValue());
    }

    private static Map<String, Object> scorerDebugFromCandidate(MatchCandidate candidate) {
        if (candidate == null || !candidate.isSetReasons() || candidate.getReasons() == null
                || candidate.getReasons().isEmpty()) {
            return null;
        }
        HashMap<String, Double> metrics = new HashMap<>();
        for (String reason : candidate.getReasons()) {
            if (reason == null || reason.isBlank()) {
                continue;
            }
            int sep = reason.indexOf('=');
            if (sep <= 0 || sep >= reason.length() - 1) {
                continue;
            }
            String key = reason.substring(0, sep).trim();
            String rawValue = reason.substring(sep + 1).trim();
            if (key.isBlank() || rawValue.isBlank()) {
                continue;
            }
            try {
                double parsed = Double.parseDouble(rawValue);
                if (Double.isFinite(parsed)) {
                    metrics.put(key, parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (metrics.isEmpty()) {
            return null;
        }

        HashMap<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            if (key == null || key.isBlank()) {
                continue;
            }
            putFiniteMetric(out, key, value);
        }
        return out.isEmpty() ? null : out;
    }

    private static String normalizeModeForDebug(String mode) {
        if ("focused".equalsIgnoreCase(mode)) {
            return "focused";
        }
        if ("exploratory".equalsIgnoreCase(mode)) {
            return "exploratory";
        }
        return "balanced";
    }

    private static Map<String, Object> thresholdsMap(double matchThreshold, double autoPassThreshold) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("match", matchThreshold);
        out.put("autoPass", autoPassThreshold);
        return out;
    }

    private static Map<String, Object> directionalPairScoreRow(
            MatchCandidate candidate,
            GetAccount account,
            double matchThreshold,
            double autoPassThreshold) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("present", candidate != null);
        if (candidate == null) {
            return out;
        }
        double score = candidate.getStage0Score();
        out.put("account", account);
        out.put("score", score);
        out.put("computedAt", candidate.getComputedAt());
        out.put("deltaToMatchThreshold", score - matchThreshold);
        out.put("deltaToAutoPassThreshold", score - autoPassThreshold);
        Map<String, Object> debug = scorerDebugFromCandidate(candidate);
        if (debug != null && !debug.isEmpty()) {
            out.put("scorerDebug", debug);
        }
        return out;
    }

    private static Map<String, Object> topCandidateRow(
            GetMatch match,
            double matchThreshold,
            double autoPassThreshold) {
        if (match == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("account", match.account);
        row.put("score", match.score);
        row.put("computedAt", match.computedAt);
        row.put("deltaToMatchThreshold", match.score - matchThreshold);
        row.put("deltaToAutoPassThreshold", match.score - autoPassThreshold);
        if (match.scorerDebug != null && !match.scorerDebug.isEmpty()) {
            row.put("scorerDebug", match.scorerDebug);
        }
        return row;
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
        return ((Number) raw).intValue() > 0;
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
            if (reactionValue.intValue() > 0) {
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

    private static Map<String, Object> copyScorerDebug(GetMatch match) {
        HashMap<String, Object> out = new HashMap<>();
        if (match != null && match.scorerDebug != null) {
            out.putAll(match.scorerDebug);
        }
        return out;
    }

    private static String intentLabel(SignalIntent intent) {
        if (intent == null) {
            return "self";
        }
        switch (intent) {
            case SELF:
                return "self";
            case SEEKING:
                return "seeking";
            case BOTH:
                return "both";
            case META:
                return "meta";
            default:
                return "self";
        }
    }

    private static ParsedSignalToken parseSignalTokenAndValence(SignalRecord record) {
        if (record == null || !record.isSetToken() || record.getToken() == null) {
            return null;
        }
        String token = record.getToken().trim().toLowerCase(Locale.ROOT);
        if (token.isBlank()) {
            return null;
        }
        boolean explicitValence = record.isSetValence();
        double valence = explicitValence ? clampSigned(record.getValence()) : 1.0;

        boolean stripped;
        do {
            stripped = false;
            if (token.startsWith("anti_") && token.length() > "anti_".length()) {
                if (!explicitValence) {
                    valence = -1.0;
                }
                token = token.substring("anti_".length());
                stripped = true;
            } else if (token.startsWith("not_") && token.length() > "not_".length()) {
                if (!explicitValence) {
                    valence = -1.0;
                }
                token = token.substring("not_".length());
                stripped = true;
            } else if (token.startsWith("no_") && token.length() > "no_".length()) {
                if (!explicitValence) {
                    valence = -1.0;
                }
                token = token.substring("no_".length());
                stripped = true;
            } else if (token.startsWith("avoid_") && token.length() > "avoid_".length()) {
                if (!explicitValence) {
                    valence = -1.0;
                }
                token = token.substring("avoid_".length());
                stripped = true;
            } else if (token.startsWith("exclude_") && token.length() > "exclude_".length()) {
                if (!explicitValence) {
                    valence = -1.0;
                }
                token = token.substring("exclude_".length());
                stripped = true;
            }
        } while (stripped);

        token = token.trim();
        if (token.isBlank()) {
            return null;
        }
        return new ParsedSignalToken(token, clampSigned(valence));
    }

    private static double signalStrength(SignalRecord record, double valenceMagnitude) {
        if (record == null) {
            return 0.0;
        }
        double count = record.isSetCount() ? Math.max(1.0, record.getCount()) : 1.0;
        return Math.log1p(count)
                * Math.max(0.0, valenceMagnitude);
    }

    private static List<MatchReranker.Signal> toRerankSignals(Signals signals, int limit) {
        if (signals == null || !signals.isSetRecords() || signals.getRecords() == null || signals.getRecords().isEmpty()) {
            return List.of();
        }
        HashMap<String, SignalFeature> merged = new HashMap<>();
        for (SignalRecord record : signals.getRecords()) {
            if (record == null) {
                continue;
            }
            ParsedSignalToken parsed = parseSignalTokenAndValence(record);
            if (parsed == null) {
                continue;
            }
            double valenceMagnitude = Math.abs(parsed.valence);
            if (valenceMagnitude <= 1e-6) {
                continue;
            }
            SignalIntent intent = record.isSetIntent() ? record.getIntent() : SignalIntent.SELF;
            String intentKey = intentLabel(intent);
            double strength = signalStrength(record, valenceMagnitude);
            if (strength <= 1e-6) {
                continue;
            }
            String key = intentKey + "|" + parsed.token;
            SignalFeature prev = merged.get(key);
            if (prev == null) {
                merged.put(key, new SignalFeature(parsed.token, intentKey, parsed.valence, strength));
                continue;
            }
            double combinedWeight = prev.weight + strength;
            double combinedValence = 0.0;
            if (combinedWeight > 1e-6) {
                combinedValence = ((prev.valence * prev.weight) + (parsed.valence * strength)) / combinedWeight;
            }
            prev.weight = combinedWeight;
            prev.valence = clampSigned(combinedValence);
        }

        if (merged.isEmpty()) {
            return List.of();
        }
        List<SignalFeature> ordered = new ArrayList<>(merged.values());
        ordered.sort((a, b) -> {
            int byWeight = Double.compare(b.weight, a.weight);
            if (byWeight != 0) {
                return byWeight;
            }
            return a.token.compareTo(b.token);
        });
        int top = Math.min(Math.max(1, limit), ordered.size());
        double maxWeight = Math.max(ordered.get(0).weight, 1e-6);
        ArrayList<MatchReranker.Signal> out = new ArrayList<>(top);
        for (int i = 0; i < top; i++) {
            SignalFeature feature = ordered.get(i);
            MatchReranker.Signal signal = new MatchReranker.Signal();
            signal.token = feature.token;
            signal.intent = feature.intent;
            signal.weight = clamp01(feature.weight / maxWeight);
            signal.valence = clampSigned(feature.valence);
            out.add(signal);
        }
        return out;
    }

    private static List<String> sharedSignalTokens(Signals a, Signals b, int limit) {
        if (a == null || b == null || !a.isSetRecords() || !b.isSetRecords()
                || a.getRecords() == null || b.getRecords() == null) {
            return List.of();
        }
        LinkedHashSet<String> left = new LinkedHashSet<>();
        for (SignalRecord record : a.getRecords()) {
            SignalIntent intent = record.isSetIntent() ? record.getIntent() : SignalIntent.SELF;
            if (intent != SignalIntent.SELF && intent != SignalIntent.BOTH) continue;
            ParsedSignalToken parsed = parseSignalTokenAndValence(record);
            if (parsed != null && parsed.token != null && !parsed.token.isBlank()) {
                left.add(parsed.token);
            }
        }
        if (left.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> shared = new LinkedHashSet<>();
        for (SignalRecord record : b.getRecords()) {
            SignalIntent intent = record.isSetIntent() ? record.getIntent() : SignalIntent.SELF;
            if (intent != SignalIntent.SELF && intent != SignalIntent.BOTH) continue;
            ParsedSignalToken parsed = parseSignalTokenAndValence(record);
            if (parsed == null || parsed.token == null || parsed.token.isBlank()) {
                continue;
            }
            if (left.contains(parsed.token)) {
                shared.add(parsed.token);
            }
            if (shared.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(shared);
    }

    private static String rankingGoalForMode(String mode) {
        if ("exploratory".equalsIgnoreCase(mode)) {
            return "discover";
        }
        if ("focused".equalsIgnoreCase(mode)) {
            return "precision";
        }
        return "balance";
    }

    private static boolean shouldApplyTier3Rerank(String surface) {
        if ("facecards".equalsIgnoreCase(surface)) {
            return FACECARD_RERANK_ENABLED || MatchReranker.hasTestOverride();
        }
        return false;
    }

    private static SilhouetteDigest silhouetteDigest(Map<String, Object> silhouetteMap) {
        SilhouetteState state = SilhouetteState.fromMap(silhouetteMap, mapLong(silhouetteMap, "accountId", 0L));
        return SilhouetteDigest.fromState(state);
    }

    private static String silhouetteMaturity(Map<String, Object> silhouetteMap) {
        String maturity = mapString(silhouetteMap, "maturity");
        return SilhouetteState.normalizeMaturity(maturity);
    }

    private CompletableFuture<Signals> readSignalsSnapshot(long requesterId, long accountId) {
        return getSignalsFromAccountId.invokeAsync(requesterId, accountId)
                .thenApply(CalypsoApiManager::canonicalizeSignalSnapshot)
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null);
    }

    private CompletableFuture<List<GetMatch>> applyTier3Rerank(long viewerId,
            List<GetMatch> stage2,
            int limit,
            String surface) {
        List<GetMatch> limitedStage2 = limitMatches(stage2, limit);
        if (!shouldApplyTier3Rerank(surface) || stage2 == null || stage2.isEmpty()) {
            return CompletableFuture.completedFuture(limitedStage2);
        }

        CompletableFuture<Filters> viewerFiltersFuture = getFiltersFromAccountId
                .invokeAsync(viewerId, viewerId)
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null);
        CompletableFuture<Signals> viewerSignalsFuture = readSignalsSnapshot(viewerId, viewerId);
        CompletableFuture<Map<String, Object>> viewerSilhouetteFuture = readSilhouetteSnapshot(viewerId);
        HashMap<Long, CompletableFuture<Signals>> targetSignalFutures = new HashMap<>();
        HashMap<Long, CompletableFuture<Map<String, Object>>> targetSilhouetteFutures = new HashMap<>();
        for (GetMatch candidate : stage2) {
            long targetId = parseTargetAccountId(candidate);
            if (targetId < 0L || targetId == viewerId || targetSignalFutures.containsKey(targetId)) {
                continue;
            }
            targetSignalFutures.put(targetId, readSignalsSnapshot(viewerId, targetId));
            targetSilhouetteFutures.put(targetId, readSilhouetteSnapshot(targetId));
        }

        ArrayList<CompletableFuture<?>> waits = new ArrayList<>();
        waits.add(viewerFiltersFuture);
        waits.add(viewerSignalsFuture);
        waits.add(viewerSilhouetteFuture);
        waits.addAll(targetSignalFutures.values());
        waits.addAll(targetSilhouetteFutures.values());
        CompletableFuture<Void> all = CompletableFuture.allOf(waits.toArray(new CompletableFuture[0]));
        return all.thenApply(ignored -> {
            Filters viewerFilters = viewerFiltersFuture.join();
            Signals viewerSignals = viewerSignalsFuture.join();
            Map<String, Object> viewerSilhouette = viewerSilhouetteFuture.join();
            HashMap<Long, Signals> targetSignalsById = new HashMap<>();
            HashMap<Long, Map<String, Object>> targetSilhouettesById = new HashMap<>();
            for (Map.Entry<Long, CompletableFuture<Signals>> entry : targetSignalFutures.entrySet()) {
                Signals snapshot = entry.getValue().join();
                if (snapshot != null) {
                    targetSignalsById.put(entry.getKey(), snapshot);
                }
            }
            for (Map.Entry<Long, CompletableFuture<Map<String, Object>>> entry : targetSilhouetteFutures.entrySet()) {
                Map<String, Object> snapshot = entry.getValue().join();
                if (snapshot != null) {
                    targetSilhouettesById.put(entry.getKey(), snapshot);
                }
            }

            MatchReranker.RerankRequest request = new MatchReranker.RerankRequest();
            request.surface = surface;
            request.rankingGoal = rankingGoalForMode(CalypsoHelpers.getModeSelfOrNull(viewerFilters));
            request.viewerSignals = toRerankSignals(viewerSignals, MATCH_RERANK_SIGNAL_LIMIT_VIEWER);
            if (SILHOUETTE_RERANK_ENABLED) {
                request.viewer = silhouetteDigest(viewerSilhouette);
            } else {
                request.viewer = new SilhouetteDigest();
            }
            for (GetMatch candidate : stage2) {
                if (candidate == null || candidate.account == null || candidate.account.id == null
                        || candidate.account.id.isBlank()) {
                    continue;
                }
                long targetId = parseTargetAccountId(candidate);
                if (targetId < 0L || targetId == viewerId) {
                    continue;
                }
                MatchReranker.Candidate entry = new MatchReranker.Candidate();
                entry.candidateId = candidate.account.id;
                entry.stage2Normalized = clamp01(candidate.score / 100.0);
                Signals targetSignals = targetSignalsById.get(targetId);
                entry.signals = toRerankSignals(targetSignals, MATCH_RERANK_SIGNAL_LIMIT_CANDIDATE);
                List<String> shared = sharedSignalTokens(viewerSignals, targetSignals, 10);
                entry.sharedSignals = shared;
                candidate.sharedSignals = shared;
                if (SILHOUETTE_RERANK_ENABLED) {
                    Map<String, Object> candidateSilhouette = targetSilhouettesById.get(targetId);
                    entry.digest = silhouetteDigest(candidateSilhouette);
                } else {
                    entry.digest = new SilhouetteDigest();
                }
                request.candidates.add(entry);
            }
            if (request.candidates.isEmpty()) {
                return limitedStage2;
            }

            MatchReranker.RerankResult result = MatchReranker.rerank(openAI, request);
            if (result == null || result.rankedCandidates == null || result.rankedCandidates.isEmpty()) {
                return limitedStage2;
            }
            HashMap<String, MatchReranker.Decision> decisionById = new HashMap<>();
            for (MatchReranker.Decision decision : result.rankedCandidates) {
                if (decision == null || decision.candidateId == null || decision.candidateId.isBlank()) {
                    continue;
                }
                decisionById.put(decision.candidateId.trim(), decision);
            }
            if (decisionById.isEmpty()) {
                return limitedStage2;
            }

            ArrayList<GetMatch> reranked = new ArrayList<>(stage2.size());
            for (GetMatch candidate : stage2) {
                if (candidate == null || candidate.account == null || candidate.account.id == null
                        || candidate.account.id.isBlank()) {
                    continue;
                }
                double stage2Norm = clamp01(candidate.score / 100.0);
                MatchReranker.Decision decision = decisionById.get(candidate.account.id.trim());
                if (decision == null) {
                    reranked.add(candidate);
                    continue;
                }

                double compatibility = clamp01(
                        decision.finalScore == null ? 0.5 : decision.finalScore.doubleValue());
                double confidence = clamp01(
                        decision.confidence == null ? 0.5 : decision.confidence.doubleValue());
                double appliedWeight = MATCH_RERANK_MAX_WEIGHT * Math.max(MATCH_RERANK_CONFIDENCE_MIN, confidence);
                double blendedNorm = clamp01((stage2Norm * (1.0 - appliedWeight)) + (compatibility * appliedWeight));
                if ("deprioritize".equals(decision.recommendedUse)) {
                    blendedNorm = Math.min(blendedNorm, stage2Norm * MATCH_RERANK_BLOCKER_CAP);
                }
                blendedNorm = clamp01(blendedNorm);
                double finalScore = blendedNorm * 100.0;

                Map<String, Object> debug = copyScorerDebug(candidate);
                debug.put("tier2Score", candidate.score);
                debug.put("tier2Normalized", stage2Norm);
                debug.put("tier3Compatibility", compatibility);
                debug.put("tier3Spark", decision.sparkScore == null ? 0.5 : clamp01(decision.sparkScore.doubleValue()));
                debug.put("tier3Sustainability",
                        decision.sustainabilityScore == null ? 0.5 : clamp01(decision.sustainabilityScore.doubleValue()));
                debug.put("tier3LearningValue",
                        decision.learningValueScore == null ? 0.5 : clamp01(decision.learningValueScore.doubleValue()));
                debug.put("tier3Confidence", confidence);
                debug.put("tier3AppliedWeight", appliedWeight);
                debug.put("tier3RecommendedUse", decision.recommendedUse);
                debug.put("tier3Applied", true);
                if (decision.fitSummaryInternal != null && !decision.fitSummaryInternal.isBlank()) {
                    debug.put("tier3Reason", decision.fitSummaryInternal.trim());
                }
                if (decision.bestModePair != null) {
                    HashMap<String, Object> bestModePair = new HashMap<>();
                    bestModePair.put("viewerModeId", decision.bestModePair.viewerModeId);
                    bestModePair.put("candidateModeId", decision.bestModePair.candidateModeId);
                    debug.put("tier3BestModePair", bestModePair);
                }
                debug.put("scoreBeforeTier3", candidate.score);
                debug.put("scoreAfterTier3", finalScore);
                GetMatch rerankedMatch = new GetMatch(candidate.account, finalScore, candidate.computedAt, debug);
                rerankedMatch.sharedSignals = candidate.sharedSignals;
                reranked.add(rerankedMatch);
            }

            reranked.sort((a, b) -> {
                int byScore = Double.compare(b.score, a.score);
                if (byScore != 0) {
                    return byScore;
                }
                return Long.compare(b.computedAt, a.computedAt);
            });
            return limitMatches(reranked, limit);
        }).completeOnTimeout(limitedStage2, MATCH_RERANK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to apply tier3 rerank for account {}", viewerId, ex);
                    return limitedStage2;
                });
    }

    private CompletableFuture<List<GetMatch>> recordServedExposure(long viewerId, List<GetMatch> served) {
        if (served == null || served.isEmpty()) {
            return CompletableFuture.completedFuture(served == null ? List.of() : served);
        }
        ArrayList<Long> targetIds = new ArrayList<>(served.size());
        for (GetMatch match : served) {
            long targetId = parseTargetAccountId(match);
            if (targetId >= 0L) {
                targetIds.add(targetId);
            }
        }
        if (targetIds.isEmpty()) {
            return CompletableFuture.completedFuture(served);
        }
        ServedPairs sp = new ServedPairs();
        sp.setAccountId(viewerId);
        sp.setTargetIds(targetIds);
        sp.setServedAt(System.currentTimeMillis());
        return matchesServeDepot.appendAsync(sp)
                .thenApply(ignored -> served)
                .exceptionally(ex -> {
                    LOG.warn("Failed to record served exposure for account {}", viewerId, ex);
                    return served;
                });
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
                    out.add(new GetMatch(new GetAccount(aw), c.getStage0Score(), c.getComputedAt(),
                            scorerDebugFromCandidate(c)));
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
                                    candidate.getComputedAt(), scorerDebugFromCandidate(candidate)));
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
            Map<String, Object> debug = copyScorerDebug(ranked);
            debug.put("matchScoreSource", "deterministicMutualMin");
            debug.put("viewerMode", normalizeModeForDebug(viewerMode));
            debug.put("targetMode", normalizeModeForDebug(targetMode));
            putFiniteMetric(debug, "viewerToTargetScore", viewerToTargetScore);
            putFiniteMetric(debug, "targetToViewerScore", targetToViewerScore);
            putFiniteMetric(debug, "mutualScore", mutualScore);
            putFiniteMetric(debug, "viewerMatchThreshold", modeAwareMatchThreshold(viewerMode));
            putFiniteMetric(debug, "targetMatchThreshold", modeAwareMatchThreshold(targetMode));
            putFiniteMetric(debug, "viewerAutoPassThreshold", modeAwareAutoPassThreshold(viewerMode));
            putFiniteMetric(debug, "targetAutoPassThreshold", modeAwareAutoPassThreshold(targetMode));
            debug.put("viewerLikedTargetFacecard", viewerLikedTargetFacecard);
            debug.put("targetLikedViewerFacecard", targetLikedViewerFacecard);
            debug.put("viewerPromptLikeSeen", viewerPromptLikeSeen);
            debug.put("targetPromptLikeSeen", targetPromptLikeSeen);
            debug.put("followupPending", followupPending);
            return new GetMatch(ranked.account, mutualScore, ranked.computedAt, debug);
        }).exceptionally(ex -> {
            LOG.warn("Failed to evaluate mutual match {} -> {}", viewerId, targetId, ex);
            return null;
        });
    }

    public CompletableFuture<List<GetMatch>> getMatches(long requesterId, long viewerId, int limit) {
        int clamped = clampMatchLimit(limit);
        int fetchLimit = rerankPoolLimit(clamped);
        int refillTarget = Math.max(80, clamped * 3);
        requestRefill(viewerId, refillTarget).exceptionally(ex -> {
            LOG.warn("Failed to enqueue match refill for account {} (target size {})", viewerId, refillTarget, ex);
            return null;
        });

        CompletableFuture<Filters> viewerFiltersFuture = getFiltersFromAccountId
                .invokeAsync(requesterId, viewerId)
                .completeOnTimeout(null, 3, TimeUnit.SECONDS)
                .exceptionally(ex -> null);

        return loadRawRankedCandidates(viewerId, fetchLimit)
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
                        out.sort((a, b) -> {
                            int byScore = Double.compare(b.score, a.score);
                            if (byScore != 0) {
                                return byScore;
                            }
                            return Long.compare(b.computedAt, a.computedAt);
                        });
                        return limitMatches(out, clamped);
                    });
                }))
                .completeOnTimeout(List.<GetMatch>of(), 10, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load mutual matches for account {}", viewerId, ex);
                    return List.<GetMatch>of();
                });
    }

    private static String currentFacecardDayKey() {
        return Instant.now().atZone(FACECARD_DAY_ZONE).toLocalDate().toString();
    }

    private static int clampFacecardLimit(int limit) {
        return Math.max(1, Math.min(FACECARD_DAILY_LIMIT, limit));
    }

    private static String facecardDeckKey(long viewerId, String dayKey, String fingerprint) {
        return viewerId + "|" + dayKey + "|" + (fingerprint == null ? "" : fingerprint);
    }

    private static String facecardDeckFingerprint(List<GetMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return "empty";
        }
        StringBuilder sb = new StringBuilder();
        for (GetMatch match : matches) {
            long targetId = parseTargetAccountId(match);
            if (targetId < 0L) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(targetId);
        }
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    private static double mapDouble(Map<String, Object> map, String key, double fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object raw = map.get(key);
        if (raw instanceof Number) {
            double value = ((Number) raw).doubleValue();
            return Double.isFinite(value) ? value : fallback;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapObject(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object raw = map.get(key);
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return null;
        }
        HashMap<String, Object> out = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry != null && entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> facecardDeckCards(Map<String, Object> deck) {
        if (deck == null) {
            return List.of();
        }
        Object raw = deck.get("cards");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return List.of();
        }
        ArrayList<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : rawList) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                continue;
            }
            HashMap<String, Object> card = new HashMap<>();
            for (Map.Entry<?, ?> mapEntry : rawMap.entrySet()) {
                if (mapEntry != null && mapEntry.getKey() != null) {
                    card.put(mapEntry.getKey().toString(), mapEntry.getValue());
                }
            }
            out.add(card);
        }
        return out;
    }

    private static Map<String, Object> buildFacecardDeckSnapshot(long viewerId, String dayKey,
            String status, List<GetMatch> matches, String fingerprint) {
        HashMap<String, Object> deck = new HashMap<>();
        deck.put("accountId", viewerId);
        deck.put("dayKey", dayKey);
        deck.put("status", status);
        deck.put("fingerprint", fingerprint == null ? facecardDeckFingerprint(matches) : fingerprint);
        deck.put("generatedAt", System.currentTimeMillis());
        ArrayList<Map<String, Object>> cards = new ArrayList<>();
        List<GetMatch> limited = limitMatches(matches, FACECARD_DAILY_LIMIT);
        for (GetMatch match : limited) {
            long targetId = parseTargetAccountId(match);
            if (targetId < 0L) {
                continue;
            }
            HashMap<String, Object> card = new HashMap<>();
            card.put("targetId", targetId);
            card.put("score", match.score);
            card.put("computedAt", match.computedAt);
            if (match.scorerDebug != null && !match.scorerDebug.isEmpty()) {
                card.put("scorerDebug", deepMutableCopy(match.scorerDebug));
            }
            if (match.sharedSignals != null && !match.sharedSignals.isEmpty()) {
                card.put("sharedSignals", new ArrayList<>(match.sharedSignals));
            }
            cards.add(card);
        }
        deck.put("cards", cards);
        deck.put("size", cards.size());
        if (FACECARD_DECK_STATUS_RERANKED.equals(status)) {
            deck.put("rerankedAt", deck.get("generatedAt"));
        }
        return deck;
    }

    private CompletableFuture<List<GetMatch>> hydrateFacecardDeck(long requesterId, long viewerId,
            Map<String, Object> deck, int limit) {
        List<Map<String, Object>> cards = facecardDeckCards(deck);
        if (cards.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        int clamped = clampFacecardLimit(limit);
        ArrayList<Long> ids = new ArrayList<>();
        for (Map<String, Object> card : cards) {
            Long targetId = asLong(card.get("targetId"));
            if (targetId == null || targetId.longValue() < 0L) {
                continue;
            }
            ids.add(targetId);
            if (ids.size() >= clamped) {
                break;
            }
        }
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return getAccountsFromAccountIds.invokeAsync(requesterId, ids)
                .completeOnTimeout(List.of(), 5, TimeUnit.SECONDS)
                .thenApply(accounts -> {
                    Map<Long, AccountWithId> accountsById = new HashMap<>();
                    if (accounts != null) {
                        for (AccountWithId account : accounts) {
                            if (account != null && account.account != null) {
                                accountsById.put(account.accountId, account);
                            }
                        }
                    }
                    List<GetMatch> out = new ArrayList<>();
                    for (Map<String, Object> card : cards) {
                        Long targetId = asLong(card.get("targetId"));
                        if (targetId == null || targetId.longValue() < 0L) {
                            continue;
                        }
                        AccountWithId account = accountsById.get(targetId);
                        if (account == null || account.account == null) {
                            continue;
                        }
                        Map<String, Object> debug = mapObject(card, "scorerDebug");
                        GetMatch match = new GetMatch(new GetAccount(account),
                                mapDouble(card, "score", 0.0),
                                mapLong(card, "computedAt", 0L),
                                debug);
                        List<String> shared = asStringList(card.get("sharedSignals"));
                        if (!shared.isEmpty()) {
                            match.sharedSignals = shared;
                        }
                        out.add(match);
                        if (out.size() >= clamped) {
                            break;
                        }
                    }
                    return out;
                })
                .exceptionally(ex -> {
                    LOG.warn("Failed to hydrate facecard deck for account {}", viewerId, ex);
                    return List.of();
                });
    }

    private CompletableFuture<List<GetMatch>> filterAlreadyReactedFacecards(long viewerId, List<GetMatch> matches,
            int limit) {
        List<GetMatch> safeMatches = matches == null ? List.of() : matches;
        if (safeMatches.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        int clamped = clampFacecardLimit(limit);
        return viewerIdToTargetIdToFacecardReaction.selectOneAsync(Path.key(viewerId))
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null)
                .thenApply(raw -> {
                    Set<Long> reacted = new HashSet<>();
                    if (raw instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            Long targetId = asLong(entry.getKey());
                            if (targetId != null && targetId.longValue() >= 0L) {
                                reacted.add(targetId);
                            }
                        }
                    }
                    ArrayList<GetMatch> out = new ArrayList<>();
                    for (GetMatch match : safeMatches) {
                        long targetId = parseTargetAccountId(match);
                        if (targetId < 0L || reacted.contains(targetId)) {
                            continue;
                        }
                        out.add(match);
                        if (out.size() >= clamped) {
                            break;
                        }
                    }
                    return out;
                });
    }

    private CompletableFuture<Void> persistFacecardDeck(Map<String, Object> deck) {
        if (facecardDeckDepot == null || deck == null || deck.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return facecardDeckDepot.appendAsync(deck)
                .thenApply(ignored -> (Void) null)
                .exceptionally(ex -> {
                    LOG.warn("Failed to persist facecard deck for account {}", deck.get("accountId"), ex);
                    return null;
                });
    }

    private boolean facecardRerankEligible(Map<String, Object> existingDeck, String fingerprint) {
        if (!shouldApplyTier3Rerank("facecards")) {
            return false;
        }
        if (existingDeck == null || existingDeck.isEmpty()) {
            return true;
        }
        String status = mapString(existingDeck, "status");
        String existingFingerprint = mapString(existingDeck, "fingerprint");
        return !FACECARD_DECK_STATUS_RERANKED.equals(status)
                || !Objects.equals(existingFingerprint, fingerprint);
    }

    private void queueAsyncFacecardRerank(long viewerId, String dayKey, String fingerprint,
            List<GetMatch> deterministicDeck, Map<String, Object> existingDeck) {
        if (deterministicDeck == null || deterministicDeck.isEmpty()
                || !facecardRerankEligible(existingDeck, fingerprint)
                || facecardDeckDepot == null) {
            return;
        }
        String deckKey = facecardDeckKey(viewerId, dayKey, fingerprint);
        facecardRerankByDeckKey.computeIfAbsent(deckKey, key -> {
            List<GetMatch> rerankInput = List.copyOf(limitMatches(deterministicDeck, FACECARD_DAILY_LIMIT));
            CompletableFuture<Void> future = applyTier3Rerank(viewerId, rerankInput, FACECARD_DAILY_LIMIT, "facecards")
                    .thenCompose(reranked -> {
                        List<GetMatch> finalDeck = reranked == null || reranked.isEmpty() ? rerankInput : reranked;
                        Map<String, Object> payload = buildFacecardDeckSnapshot(
                                viewerId,
                                dayKey,
                                FACECARD_DECK_STATUS_RERANKED,
                                finalDeck,
                                fingerprint);
                        return persistFacecardDeck(payload);
                    })
                    .completeOnTimeout(null, MATCH_RERANK_TIMEOUT_MS + 1500L, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        LOG.warn("Async facecard rerank failed for account {}", viewerId, ex);
                        return null;
                    });
            future.whenComplete((ignored, ex) -> facecardRerankByDeckKey.remove(key, future));
            return future;
        });
    }

    private CompletableFuture<List<GetMatch>> legacyFacecards(long requesterId, long viewerId, int limit) {
        int clamped = clampFacecardLimit(limit);
        int refillTarget = Math.max(120, clamped * 6);
        requestRefill(viewerId, refillTarget).exceptionally(ex -> {
            LOG.warn("Failed to enqueue facecard refill for account {} (target size {})", viewerId, refillTarget, ex);
            return null;
        });
        return loadRankedCandidates(requesterId, viewerId, clamped, false)
                .thenCompose(stage2 -> applyTier3Rerank(viewerId, stage2, clamped, "facecards"))
                .thenCompose(facecards -> filterAlreadyReactedFacecards(viewerId, facecards, clamped))
                .completeOnTimeout(List.<GetMatch>of(), 4, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load legacy facecards for account {}", viewerId, ex);
                    return List.<GetMatch>of();
                });
    }

    public CompletableFuture<List<GetMatch>> getFacecards(long requesterId, long viewerId, int limit) {
        int clamped = clampFacecardLimit(limit);
        if (facecardDeckDepot == null || getFacecardDeck == null) {
            return legacyFacecards(requesterId, viewerId, clamped);
        }
        String dayKey = currentFacecardDayKey();
        CompletableFuture<Map<String, Object>> existingDeckFuture = getFacecardDeck
                .invokeAsync(requesterId, viewerId, dayKey)
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to read facecard daily deck for account {}", viewerId, ex);
                    return null;
                });
        return existingDeckFuture.thenCompose(existingDeck -> {
            List<Map<String, Object>> existingCards = facecardDeckCards(existingDeck);
            if (!existingCards.isEmpty()) {
                String fingerprint = mapString(existingDeck, "fingerprint");
                return hydrateFacecardDeck(requesterId, viewerId, existingDeck, clamped)
                        .thenCompose(deck -> filterAlreadyReactedFacecards(viewerId, deck, clamped))
                        .thenApply(deck -> {
                            if (!FACECARD_DECK_STATUS_RERANKED.equals(mapString(existingDeck, "status"))) {
                                queueAsyncFacecardRerank(viewerId, dayKey, fingerprint, deck, existingDeck);
                            }
                            return deck;
                        });
            }

            int refillTarget = Math.max(120, clamped * 6);
            requestRefill(viewerId, refillTarget).exceptionally(ex -> {
                LOG.warn("Failed to enqueue facecard refill for account {} (target size {})", viewerId, refillTarget, ex);
                return null;
            });
            return loadRankedCandidates(requesterId, viewerId, FACECARD_DAILY_LIMIT, false)
                    .thenCompose(stage2 -> {
                        List<GetMatch> deterministicDeck = limitMatches(stage2, FACECARD_DAILY_LIMIT);
                        if (deterministicDeck.isEmpty()) {
                            return CompletableFuture.completedFuture(List.<GetMatch>of());
                        }
                        String fingerprint = facecardDeckFingerprint(deterministicDeck);
                        Map<String, Object> stage2Snapshot = buildFacecardDeckSnapshot(
                                viewerId,
                                dayKey,
                                FACECARD_DECK_STATUS_STAGE2,
                                deterministicDeck,
                                fingerprint);
                        return persistFacecardDeck(stage2Snapshot)
                                .thenCompose(ignored -> {
                                    queueAsyncFacecardRerank(viewerId, dayKey, fingerprint, deterministicDeck, null);
                                    return filterAlreadyReactedFacecards(viewerId, deterministicDeck, clamped);
                                });
                    });
        })
                .completeOnTimeout(List.<GetMatch>of(), 4, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load facecards for account {}", viewerId, ex);
                    return List.<GetMatch>of();
                });
    }

    public CompletableFuture<Map<String, Object>> getAdminPairScoreDebug(
            long requesterId,
            long viewerId,
            Long targetIdMaybe,
            int limit) {
        int clamped = clampMatchLimit(limit);
        long generatedAt = System.currentTimeMillis();

        CompletableFuture<Filters> viewerFiltersFuture = getFiltersFromAccountId
                .invokeAsync(requesterId, viewerId)
                .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                .exceptionally(ex -> null);
        CompletableFuture<List<GetMatch>> topCandidatesFuture = loadRawRankedCandidates(viewerId, clamped)
                .completeOnTimeout(List.of(), 4, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    LOG.warn("Failed to load top candidate rows for admin pair-score {}", viewerId, ex);
                    return List.of();
                });

        CompletableFuture<Map<String, Object>> pairFuture;
        if (targetIdMaybe == null || targetIdMaybe.longValue() < 0L) {
            pairFuture = CompletableFuture.completedFuture(null);
        } else {
            long targetId = targetIdMaybe.longValue();
            CompletableFuture<Object> viewerHeapFuture = accountIdToCandidateHeap
                    .selectOneAsync(Path.key(viewerId))
                    .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);
            CompletableFuture<Object> targetHeapFuture = accountIdToCandidateHeap
                    .selectOneAsync(Path.key(targetId))
                    .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);
            CompletableFuture<Filters> targetFiltersFuture = getFiltersFromAccountId
                    .invokeAsync(requesterId, targetId)
                    .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);
            CompletableFuture<AccountWithId> viewerAccountFuture = getAccountWithId(requesterId, viewerId)
                    .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);
            CompletableFuture<AccountWithId> targetAccountFuture = getAccountWithId(requesterId, targetId)
                    .completeOnTimeout(null, 2, TimeUnit.SECONDS)
                    .exceptionally(ex -> null);

            CompletableFuture<Void> pairAll = CompletableFuture.allOf(
                    viewerHeapFuture,
                    targetHeapFuture,
                    viewerFiltersFuture,
                    targetFiltersFuture,
                    viewerAccountFuture,
                    targetAccountFuture);
            pairFuture = pairAll.thenApply(ignored -> {
                String viewerMode = normalizeModeForDebug(CalypsoHelpers.getModeSelfOrNull(viewerFiltersFuture.join()));
                String targetMode = normalizeModeForDebug(CalypsoHelpers.getModeSelfOrNull(targetFiltersFuture.join()));
                double viewerMatchThreshold = modeAwareMatchThreshold(viewerMode);
                double viewerAutoPassThreshold = modeAwareAutoPassThreshold(viewerMode);
                double targetMatchThreshold = modeAwareMatchThreshold(targetMode);
                double targetAutoPassThreshold = modeAwareAutoPassThreshold(targetMode);

                MatchCandidate viewerToTarget = candidateFromHeap(viewerHeapFuture.join(), targetId);
                MatchCandidate targetToViewer = candidateFromHeap(targetHeapFuture.join(), viewerId);
                AccountWithId viewerRaw = viewerAccountFuture.join();
                AccountWithId targetRaw = targetAccountFuture.join();
                GetAccount viewerAccount = viewerRaw == null ? null : new GetAccount(viewerRaw);
                GetAccount targetAccount = targetRaw == null ? null : new GetAccount(targetRaw);

                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("targetAccountId", CalypsoHelpers.serializeAccountId(targetId));
                pair.put("viewerMode", viewerMode);
                pair.put("targetMode", targetMode);
                pair.put("viewerThresholds", thresholdsMap(viewerMatchThreshold, viewerAutoPassThreshold));
                pair.put("targetThresholds", thresholdsMap(targetMatchThreshold, targetAutoPassThreshold));
                pair.put("viewerToTarget", directionalPairScoreRow(
                        viewerToTarget, targetAccount, viewerMatchThreshold, viewerAutoPassThreshold));
                pair.put("targetToViewer", directionalPairScoreRow(
                        targetToViewer, viewerAccount, targetMatchThreshold, targetAutoPassThreshold));

                if (viewerToTarget != null && targetToViewer != null) {
                    double viewerToTargetScore = viewerToTarget.getStage0Score();
                    double targetToViewerScore = targetToViewer.getStage0Score();
                    double mutualMin = Math.min(viewerToTargetScore, targetToViewerScore);
                    pair.put("mutualMinScore", mutualMin);
                    pair.put("mutualDeltaToThreshold",
                            Math.min(viewerToTargetScore - viewerMatchThreshold, targetToViewerScore - targetMatchThreshold));
                    pair.put("bothMeetMatchThreshold",
                            viewerToTargetScore >= viewerMatchThreshold && targetToViewerScore >= targetMatchThreshold);
                    pair.put("bothMeetAutoPassThreshold",
                            viewerToTargetScore >= viewerAutoPassThreshold && targetToViewerScore >= targetAutoPassThreshold);
                } else {
                    pair.put("bothMeetMatchThreshold", false);
                    pair.put("bothMeetAutoPassThreshold", false);
                }
                return pair;
            }).exceptionally(ex -> {
                LOG.warn("Failed to resolve admin pair debug snapshot {} -> {}", viewerId, targetId, ex);
                return null;
            });
        }

        CompletableFuture<Void> all = CompletableFuture.allOf(viewerFiltersFuture, topCandidatesFuture, pairFuture);
        return all.thenApply(ignored -> {
            String viewerMode = normalizeModeForDebug(CalypsoHelpers.getModeSelfOrNull(viewerFiltersFuture.join()));
            double viewerMatchThreshold = modeAwareMatchThreshold(viewerMode);
            double viewerAutoPassThreshold = modeAwareAutoPassThreshold(viewerMode);

            List<GetMatch> topCandidates = topCandidatesFuture.join();
            ArrayList<Map<String, Object>> topRows = new ArrayList<>();
            if (topCandidates != null) {
                for (GetMatch match : topCandidates) {
                    Map<String, Object> row = topCandidateRow(match, viewerMatchThreshold, viewerAutoPassThreshold);
                    if (!row.isEmpty()) {
                        topRows.add(row);
                    }
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generatedAt", generatedAt);
            out.put("viewerId", CalypsoHelpers.serializeAccountId(viewerId));
            out.put("viewerMode", viewerMode);
            out.put("viewerThresholds", thresholdsMap(viewerMatchThreshold, viewerAutoPassThreshold));
            out.put("topCandidates", topRows);
            out.put("pair", pairFuture.join());
            return out;
        }).exceptionally(ex -> {
            LOG.warn("Failed to build admin pair-score payload for account {}", viewerId, ex);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("generatedAt", generatedAt);
            fallback.put("viewerId", CalypsoHelpers.serializeAccountId(viewerId));
            fallback.put("viewerMode", "balanced");
            fallback.put("viewerThresholds", thresholdsMap(MATCH_MIN_BALANCED, MATCH_AUTOPASS_BALANCED));
            fallback.put("topCandidates", List.of());
            fallback.put("pair", null);
            return fallback;
        });
    }

    private static final class ParsedSignalToken {
        final String token;
        final double valence;

        private ParsedSignalToken(String token, double valence) {
            this.token = token;
            this.valence = valence;
        }
    }

    private static final class SignalFeature {
        final String token;
        final String intent;
        double valence;
        double weight;

        private SignalFeature(String token, String intent, double valence, double weight) {
            this.token = token;
            this.intent = intent;
            this.valence = valence;
            this.weight = weight;
        }
    }

    private static final class SourceValenceScaleProfile {
        final double firstHitScale;
        final double repeatScale;

        private SourceValenceScaleProfile(double firstHitScale, double repeatScale) {
            this.firstHitScale = clamp01(firstHitScale);
            this.repeatScale = clamp01(repeatScale);
        }
    }

}
