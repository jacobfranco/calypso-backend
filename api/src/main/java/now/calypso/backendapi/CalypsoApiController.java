package now.calypso.backendapi;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.*;
import now.calypso.backendapi.filters.*;
import now.calypso.backendapi.pojos.*;
import now.calypso.backendapi.prompts.PromptLibrary;
import reactor.core.publisher.Mono;

@RestController
public class CalypsoApiController {
    private static final Logger LOG = LoggerFactory.getLogger(CalypsoApiController.class);

    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern
            .compile("^\\+[1-9][0-9]{7,14}$");
    private static final int MIN_AGE_YEARS = 18;
    private static final Duration SIGNALS_READ_TIMEOUT = Duration.ofSeconds(4);

    public static CalypsoApiManager manager;

    @Autowired
    private FiltersValidator validator;

    @Autowired
    private TagDictionaryService tagService;

    private Mono<GetToken> loginWithAccount(WebSession session, String scope, AccountWithId accountWithId) {
        // Update Session
        session.getAttributes().put("accountId", accountWithId.accountId);
        session.getAttributes().put("accountName", accountWithId.account.name);
        // Store the session id in the backend and return token
        return Mono.fromFuture(manager.postAuthCode(accountWithId.accountId, session.getId()))
                .map(res -> new GetToken(session.getId(), scope));
    }

    // Define a controller method to handle POST requests for application
    // registration with JSON payload
    @PostMapping(value = "/api/apps", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GetApplication> postApplication(@RequestBody(required = true) PostApplication params) {
        return Mono.fromFuture(manager.postApplication(params))
                .map(GetApplication::new);
    }

    @GetMapping("/api/apps/{clientId}")
    public Mono<GetApplication> getApplication(@PathVariable String clientId) {
        return Mono.fromFuture(manager.getApplication(clientId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Application not found")))
                .map(GetApplication::new);
    }

    // Define a controller method to handle POST requests for application
    // registration with form URL encoded payload
    @PostMapping(value = "/api/apps", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<GetApplication> postApplication(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(formParams -> {
                    PostApplication params = CalypsoApiFormParser.parseParams(formParams, new PostApplication());
                    return this.postApplication(params);
                });
    }

    // Define a controller method to handle POST requests for OAuth token generation
    // with JSON payload
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<GetToken> postOauthToken(WebSession session, @RequestBody(required = true) PostToken params) {
        // Handle the "password" grant type
        if ("password".equals(params.grant_type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password grant not supported");
        } else if ("client_credentials".equals(params.grant_type)) {
            // Handle the "client_credentials" grant type
            return Mono.just(new GetToken(session.getId(), params.scope));
        } else {
            // Handle unsupported grant types
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    // Define a controller method to handle POST requests for OAuth token generation
    // with form URL encoded payload
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<GetToken> postOauthToken(WebSession session, ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(formParams -> {
                    PostToken params = CalypsoApiFormParser.parseParams(formParams, new PostToken());
                    return this.postOauthToken(session, params);
                });
    }

    // Define a POST endpoint for revoking OAuth tokens with JSON payload
    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Object> postRevokeOauthToken(@RequestBody(required = true) PostRevokeToken params) {
        // Invoke the manager to remove the authorization code using the token provided
        // and return an empty map as the response
        return Mono.fromFuture(manager.postRemoveAuthCode(params.token)).map(res -> new HashMap<String, Object>());
    }

    // Overloaded POST endpoint for revoking OAuth tokens using form-urlencoded
    // payload
    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<Object> postRevokeOauthToken(ServerWebExchange exchange) {
        // Extract form data from the request
        return exchange.getFormData()
                .flatMap(formParams -> {
                    // Parse form parameters into a PostRevokeToken object
                    PostRevokeToken params = CalypsoApiFormParser.parseParams(formParams, new PostRevokeToken());
                    // Delegate to the other postRevokeOauthToken method for processing
                    return this.postRevokeOauthToken(params);
                });
    }

    @PostMapping("/api/accounts")
    public Mono<Object> postAccount(WebSession session, ServerHttpResponse response,
            @RequestBody PostAccount params) {

        // 1) Validate the 'name' isn't blank
        if (params.name == null || params.name.trim().isEmpty()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Name must not be empty",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("name", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Name must not be empty"));
                        }
                    }));
        }
        // 2) Enforce max length
        else if (params.name.length() > CalypsoApiConfig.MAX_NAME_LENGTH) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Name too long",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("name", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Name cannot be greater than " + CalypsoApiConfig.MAX_NAME_LENGTH + " characters"));
                        }
                    }));
        }
        // 3) Validate that the name only contains letters (any language) plus a few
        // name-friendly symbols (spaces, hyphens, apostrophes, dots).
        else if (!params.name.matches("^[\\p{L} .'-]+$")) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Name contains invalid characters",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("name", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Name may only include letters, spaces, hyphens, apostrophes, or dots"));
                        }
                    }));
        }

        // 4) Validate agreement
        else if (!Boolean.TRUE.equals(params.agreement)) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "The agreement has not been accepted",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("agreement", new GetErrorDetails.Error(
                                    "ERR_ACCEPTED",
                                    "The agreement has not been accepted"));
                        }
                    }));
        }
        // 5) Phone + birthday validation
        else {
            if (params.phone_number == null || params.phone_number.trim().isEmpty()) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Phone number is required",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("phone_number", new GetErrorDetails.Error(
                                        "ERR_REQUIRED",
                                        "Phone number is required"));
                            }
                        }));
            }
            String phoneNumber = normalizePhoneNumber(params.phone_number);
            if (phoneNumber == null || !PHONE_PATTERN.matcher(phoneNumber).matches()) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Phone number is invalid",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("phone_number", new GetErrorDetails.Error(
                                        "ERR_INVALID",
                                        "Phone number must be valid"));
                            }
                        }));
            }
            if (params.birthday == null || params.birthday.trim().isEmpty()) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Birthday is required",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("birthday", new GetErrorDetails.Error(
                                        "ERR_REQUIRED",
                                        "Birthday is required"));
                            }
                        }));
            }
            LocalDate birthDate;
            try {
                birthDate = LocalDate.parse(params.birthday.trim());
            } catch (Exception ex) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Birthday is invalid",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("birthday", new GetErrorDetails.Error(
                                        "ERR_INVALID",
                                        "Birthday must be YYYY-MM-DD"));
                            }
                        }));
            }
            int age = Period.between(birthDate, LocalDate.now()).getYears();
            if (age < MIN_AGE_YEARS) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Must be 18 or older",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("birthday", new GetErrorDetails.Error(
                                        "ERR_INVALID",
                                        "You need to be at least 18 to sign up"));
                            }
                        }));
            }
            if (params.verification_token == null || params.verification_token.trim().isEmpty()) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Verification required",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("verification_token", new GetErrorDetails.Error(
                                        "ERR_REQUIRED",
                                        "Phone verification required"));
                            }
                        }));
            }
        }

        String normalizedPhone = normalizePhoneNumber(params.phone_number);
        Mono<Boolean> verification = Mono.fromFuture(manager.consumePhoneVerification(normalizedPhone,
                params.verification_token.trim()));

        return verification.flatMap(valid -> {
            if (!valid) {
                response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                return Mono.just(new GetErrorDetails(
                        "Verification required",
                        new HashMap<String, GetErrorDetails.Error>() {
                            {
                                put("verification_token", new GetErrorDetails.Error(
                                        "ERR_INVALID",
                                        "Phone verification required"));
                            }
                        }));
            }

            PostAccount normalized = new PostAccount(
                    params.name,
                    normalizedPhone,
                    params.birthday,
                    params.verification_token,
                    params.agreement,
                    params.locale);
            return Mono.fromFuture(manager.postAccount(normalized))
                    .flatMap(success -> {
                        if (success) {
                            return Mono.fromFuture(manager.getAccountId(normalizedPhone))
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                                    .flatMap(accountId -> Mono.fromFuture(manager.getAccountWithId(accountId)))
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                                    .flatMap(accountWithId -> this.loginWithAccount(
                                            session,
                                            "read write follow push",
                                            accountWithId));
                        } else {
                            // on failure, treat it as phone conflict (name no longer needs to be unique)
                            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                            return Mono.just(new GetErrorDetails(
                                    "Validation failed",
                                    new HashMap<String, GetErrorDetails.Error>() {
                                        {
                                            put("phone_number", new GetErrorDetails.Error(
                                                    "ERR_TAKEN",
                                                    "Phone already in use"));
                                        }
                                    }));
                        }
                    });
        });
    }

    @PostMapping("/api/accounts/phone/request")
    public Mono<Object> requestPhoneCode(ServerHttpResponse response, @RequestBody PostPhoneRequest params) {
        if (params.phone_number == null || params.phone_number.trim().isEmpty()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Phone number is required",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("phone_number", new GetErrorDetails.Error(
                                    "ERR_REQUIRED",
                                    "Phone number is required"));
                        }
                    }));
        }
        String phoneNumber = normalizePhoneNumber(params.phone_number);
        LOG.warn("Phone code request received for {}", phoneNumber);
        if (phoneNumber == null || !PHONE_PATTERN.matcher(phoneNumber).matches()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Phone number is invalid",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("phone_number", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Phone number must be valid"));
                        }
                    }));
        }
        Mono<Boolean> existingAccount = resolveAccountIdByPhoneVariants(phoneNumber)
                .map(id -> true)
                .defaultIfEmpty(false)
                .timeout(Duration.ofSeconds(2))
                .onErrorReturn(false);
        return Mono.fromFuture(manager.requestPhoneCode(phoneNumber))
                .zipWith(existingAccount)
                .map(result -> {
                    HashMap<String, Object> payload = new HashMap<>();
                    String fallbackValue = System.getenv("CALYPSO_SMS_FALLBACK");
                    if (fallbackValue != null && fallbackValue.trim().equalsIgnoreCase("true")) {
                        payload.put("code", result.getT1());
                        payload.put("fallback", true);
                    }
                    payload.put("existing", result.getT2());
                    return (Object) payload;
                })
                .onErrorResume(IllegalStateException.class, err -> {
                    response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return Mono.just(new GetErrorDetails(
                            "SMS provider unavailable",
                            new HashMap<String, GetErrorDetails.Error>() {
                                {
                                    put("sms", new GetErrorDetails.Error(
                                            "ERR_UNAVAILABLE",
                                            err.getMessage()));
                                }
                            }));
                });
    }

    @PostMapping("/api/accounts/phone/verify")
    public Mono<Object> verifyPhoneCode(WebSession session, ServerHttpResponse response,
            @RequestBody PostPhoneVerify params) {
        if (params.phone_number == null || params.phone_number.trim().isEmpty()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Phone number is required",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("phone_number", new GetErrorDetails.Error(
                                    "ERR_REQUIRED",
                                    "Phone number is required"));
                        }
                    }));
        }
        if (params.code == null || params.code.trim().isEmpty()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Verification code is required",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("code", new GetErrorDetails.Error(
                                    "ERR_REQUIRED",
                                    "Verification code is required"));
                        }
                    }));
        }
        String phoneNumber = normalizePhoneNumber(params.phone_number);
        if (phoneNumber == null || !PHONE_PATTERN.matcher(phoneNumber).matches()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Phone number is invalid",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("phone_number", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Phone number must be valid"));
                        }
                    }));
        }
        String code = params.code.trim();
        return Mono.fromFuture(manager.verifyPhoneCode(phoneNumber, code))
                .flatMap(result -> resolveAccountIdByPhoneVariants(phoneNumber)
                        .flatMap(accountId -> Mono.fromFuture(manager.getAccountWithId(accountId)))
                        .flatMap(accountWithId -> Mono.fromFuture(manager.consumePhoneVerification(phoneNumber, result))
                                .onErrorReturn(false)
                                .then(this.loginWithAccount(session, "read write follow push", accountWithId)))
                        .cast(Object.class)
                        .switchIfEmpty(Mono.just((Object) new GetPhoneVerification(result))))
                .onErrorResume(IllegalArgumentException.class, err -> {
                    response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                    return Mono.just(new GetErrorDetails(
                            err.getMessage(),
                            new HashMap<String, GetErrorDetails.Error>() {
                                {
                                    put("code", new GetErrorDetails.Error(
                                            "ERR_INVALID",
                                            err.getMessage()));
                                }
                            }));
                });
    }

    private Mono<Long> resolveAccountIdByPhoneVariants(String normalizedPhone) {
        List<String> candidates = phoneLookupVariants(normalizedPhone);
        if (candidates.isEmpty()) {
            return Mono.empty();
        }
        Mono<Long> lookup = Mono.empty();
        for (String candidate : candidates) {
            lookup = lookup.switchIfEmpty(
                    Mono.defer(() -> {
                        CompletableFuture<Long> future = manager.getAccountId(candidate);
                        if (future == null) {
                            return Mono.empty();
                        }
                        return Mono.fromFuture(future).filter(Objects::nonNull);
                    }));
        }
        return lookup;
    }

    private static List<String> phoneLookupVariants(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(normalizedPhone);
        String digits = normalizedPhone.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) {
            variants.add(digits.substring(1));
        } else if (digits.length() == 10) {
            variants.add(digits);
        }
        return new ArrayList<>(variants);
    }

    private static String normalizePhoneNumber(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("+")) {
            return trimmed.replaceAll("[^+0-9]", "");
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "+1" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("1")) {
            return "+" + digits;
        }
        return "+" + digits;
    }

    @GetMapping("/api/accounts/{id}")
    public Mono<GetAccount> getAccount(@PathVariable("id") String accountId) {
        return Mono.fromFuture(manager.getAccountWithId(CalypsoHelpers.parseAccountId(accountId)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(GetAccount::new);
    }

    @PostMapping("/api/accounts/{id}/filters")
    public Mono<GetFilters> postFilters(@PathVariable("id") String idStr,
            @RequestBody PostFilters params,
            WebSession session) {

        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = (Long) session.getAttribute("accountId");
        if (me == null || !me.equals(accountId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);

        validator.validate(params);

        return Mono.fromFuture(manager.postFilters(params, accountId))
                .flatMap(ok -> ok
                        ? Mono.just(new GetFilters(params.toThrift(accountId)))
                        : Mono.error(
                                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to persist filters")));
    }

    @GetMapping("/api/accounts/{id}/filters")
    public Mono<GetFilters> getFilters(@PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        return Mono.fromFuture(manager.getFilters(me, accountId))
                // if the future completes with a Filters → wrap it
                .map(filters -> new GetFilters(filters))
                // but if it completed to null, Mono is empty → turn into 404
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/api/meta/tags/lifestyle")
    public Map<String, List<String>> lifestyleTags() {
        return tagService.lifestyle();
    }

    @GetMapping("/api/meta/tags/gender")
    public Map<String, List<String>> genderTags() {
        return tagService.genders();
    }

    @GetMapping("/api/meta/tags/religion")
    public Map<String, List<String>> religionTags() {
        return tagService.religions();
    }

    @GetMapping("/api/meta/tags/politics")
    public Map<String, List<String>> politicalTags() {
        return tagService.politics();
    }

    @GetMapping("/api/meta/prompts/public")
    public List<PromptDefinition> publicPromptLibrary() {
        return PromptLibrary.publicBank();
    }

    @GetMapping("/api/meta/prompts/private")
    public List<PromptDefinition> privatePromptLibrary() {
        return PromptLibrary.privateBank();
    }

    @GetMapping("/api/accounts/{id}/signals")
    public Mono<GetSignals> getSignals(@PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        return Mono.fromFuture(manager.getSignals(accountId, accountId))
                .timeout(SIGNALS_READ_TIMEOUT)
                .map(thrift -> thrift == null
                        ? new GetSignals(accountId, List.of())
                        : new GetSignals(thrift))
                .defaultIfEmpty(new GetSignals(accountId, List.of()))
                .onErrorResume(ex -> {
                    LOG.warn("Failed to load signals for account {}", accountId, ex);
                    return Mono.just(new GetSignals(accountId, List.of()));
                });
    }

    @GetMapping("/api/accounts/{id}/admin/silhouette")
    public Mono<Map<String, Object>> getAdminSilhouette(@PathVariable("id") String idStr, WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getSilhouette(accountId, accountId));
    }

    @GetMapping("/api/accounts/{id}/admin/llm-telemetry")
    public Mono<Map<String, Object>> getAdminLlmTelemetry(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "120") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getLlmTelemetry(accountId, limit));
    }

    @GetMapping("/api/accounts/{id}/admin/pair-score")
    public Mono<Map<String, Object>> getAdminPairScore(
            @PathVariable("id") String idStr,
            @RequestParam(value = "targetId", required = false) String targetIdStr,
            @RequestParam(value = "limit", required = false, defaultValue = "12") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Long targetId = null;
        if (targetIdStr != null && !targetIdStr.isBlank()) {
            try {
                targetId = CalypsoHelpers.parseAccountId(targetIdStr.trim());
            } catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid targetId", ex);
            }
        }
        return Mono.fromFuture(manager.getAdminPairScoreDebug(accountId, accountId, targetId, limit));
    }

    @GetMapping("/api/accounts/{id}/admin/rerank-events")
    public Mono<Map<String, Object>> getAdminRerankEvents(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getAdminRerankEvents(accountId, accountId, limit));
    }

    @GetMapping("/api/accounts/{id}/admin/signal-concepts")
    public Mono<GetSignalConceptRegistry> getSignalConceptRegistry(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getSignalConceptRegistry());
    }

    @GetMapping("/api/accounts/{id}/admin/signal-concepts/candidates")
    public Mono<GetSignalConceptCandidates> getSignalConceptCandidates(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getSignalConceptCandidates(limit));
    }

    @GetMapping("/api/accounts/{id}/admin/signal-concepts/blocked")
    public Mono<GetSignalConceptCandidates> getBlockedSignalConceptCandidates(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getBlockedSignalConceptCandidates(limit));
    }

    @GetMapping("/api/accounts/{id}/admin/signal-disambiguation/candidates")
    public Mono<GetSignalDisambiguationCandidates> getSignalDisambiguationCandidates(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getSignalDisambiguationCandidates(accountId, limit));
    }

    @PostMapping("/api/accounts/{id}/admin/signal-concepts/promote")
    public Mono<Map<String, Object>> postPromoteSignalConceptCandidate(
            @PathVariable("id") String idStr,
            @RequestBody(required = false) PostSignalConceptPromoteRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String rawToken = payload == null ? null : payload.rawToken;
        String canonicalToken = payload == null ? null : payload.canonicalToken;
        String category = payload == null ? null : payload.category;
        List<String> parentConcepts = payload == null ? null : payload.parentConcepts;
        return Mono.fromFuture(manager.promoteSignalConceptWithDebug(rawToken, canonicalToken, category, parentConcepts))
                .map(result -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("changed", result == null ? Boolean.FALSE : result.changed);
                    out.put("rawToken", result == null ? null : result.rawToken);
                    out.put("canonicalToken", result == null ? null : result.canonicalToken);
                    out.put("category", result == null || result.canonicalToken == null
                            ? null
                            : now.calypso.backendapi.signals.SignalConceptRegistry.categoryForConcept(result.canonicalToken));
                    out.put("migratedStoredAccounts", result == null ? 0 : result.migratedStoredAccounts);
                    out.put("replayedObservedAccounts", result == null ? 0 : result.replayedObservedAccounts);
                    out.put("replayedContextualOwners", result == null ? 0 : result.replayedContextualOwners);
                    out.put("observedAccountIds", result == null ? List.of() : result.observedAccountIds);
                    out.put("parentConcepts", result == null ? List.of() : result.parentConcepts);
                    return out;
                })
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/admin/signal-concepts/reject")
    public Mono<Map<String, Object>> postRejectSignalConceptCandidate(
            @PathVariable("id") String idStr,
            @RequestBody(required = false) PostSignalConceptRejectRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String rawToken = payload == null ? null : payload.rawToken;
        return Mono.fromFuture(manager.rejectSignalConceptCandidate(rawToken))
                .map(changed -> Collections.<String, Object>singletonMap("changed", changed))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/admin/signal-concepts/action")
    public Mono<Map<String, Object>> postSignalConceptCandidateAction(
            @PathVariable("id") String idStr,
            @RequestBody(required = false) PostSignalConceptActionRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String rawToken = payload == null ? null : payload.rawToken;
        String canonicalToken = payload == null ? null : payload.canonicalToken;
        String category = payload == null ? null : payload.category;
        List<String> parentConcepts = payload == null ? null : payload.parentConcepts;
        CalypsoApiManager.SignalConceptCandidateAction action = CalypsoApiManager.SignalConceptCandidateAction
                .parse(payload == null ? null : payload.action);
        if (action == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action required (create|map|reject|block|unblock)");
        }
        return Mono.fromFuture(manager.actOnSignalConceptCandidate(rawToken, canonicalToken, category, parentConcepts, action))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @GetMapping("/api/accounts/{id}/public-prompt-feed")
    public Mono<List<PublicPromptFeedCard>> getPublicPromptFeed(
            @PathVariable("id") String idStr,
            @RequestParam(value = "limit", required = false, defaultValue = "1") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getPublicPromptFeed(accountId, limit));
    }

    @GetMapping("/api/accounts/{id}/public-prompts/answers")
    public Mono<List<PublicPromptAnswer>> getMyPublicPromptAnswers(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getMyPublicPromptAnswers(accountId));
    }

    @PostMapping("/api/accounts/{id}/public-prompts/{promptId}/answer")
    public Mono<PublicPromptAnswer> postPublicPromptAnswer(
            @PathVariable("id") String idStr,
            @PathVariable("promptId") String promptId,
            @RequestBody(required = false) PostPublicPromptAnswerRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String body = payload == null ? null : payload.body;
        return Mono.fromFuture(manager.postPublicPromptAnswer(accountId, promptId, body))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @GetMapping("/api/accounts/{id}/public-prompts/selection")
    public Mono<ResponseEntity<PublicPromptSelection>> getPublicPromptSelection(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getPublicPromptSelection(accountId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/accounts/{id}/public-prompts/selection")
    public Mono<PublicPromptSelection> postPublicPromptSelection(
            @PathVariable("id") String idStr,
            @RequestBody(required = false) PostPublicPromptSelectionRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<String> selected = payload == null ? null : payload.selectedPromptIds;
        return Mono.fromFuture(manager.postPublicPromptSelection(accountId, selected))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/public-prompt-feed/{answerId}/reaction")
    public Mono<Map<String, Object>> postPublicPromptReaction(
            @PathVariable("id") String idStr,
            @PathVariable("answerId") String answerId,
            @RequestBody(required = false) PostPublicPromptReactionRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Integer strength = payload == null ? null : payload.parsedPublicPromptStrength();
        return Mono.fromFuture(manager.postPublicPromptReaction(accountId, answerId, strength))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/facecards/{targetId}/reaction")
    public Mono<Map<String, Object>> postFacecardReaction(
            @PathVariable("id") String idStr,
            @PathVariable("targetId") String targetIdStr,
            @RequestBody(required = false) PostPublicPromptReactionRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        long targetId = CalypsoHelpers.parseAccountId(targetIdStr);
        PromptReaction reaction = payload == null ? null : payload.parsedReaction();
        return Mono.fromFuture(manager.postFacecardReaction(accountId, targetId, reaction))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @GetMapping("/api/accounts/{id}/agent/session")
    public Mono<GetAgentSession> getAgentSession(@PathVariable("id") String idStr, WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getAgentSessionSnapshot(accountId))
                .map(GetAgentSession::new);
    }

    @PostMapping("/api/accounts/{id}/agent/messages")
    public Mono<GetAgentSession> postAgentMessage(
            @PathVariable("id") String idStr,
            @RequestBody PostAgentMessageRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String text = payload == null ? null : payload.safeText();
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message text is required.");
        }
        return Mono.fromFuture(manager.postAgentMessage(accountId, text))
                .map(GetAgentSession::new)
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @GetMapping("/api/accounts/{id}/agent/private-prompt")
    public Mono<ResponseEntity<ActivePrivatePrompt>> getActivePrivatePrompt(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getActivePrivatePrompt(accountId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/accounts/{id}/agent/private-prompt/{instanceId}/answer")
    public Mono<ActivePrivatePrompt> postPrivatePromptAnswer(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptAnswerRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String body = payload == null ? null : payload.safeBody();
        List<String> conversation = payload == null ? List.of() : payload.safeConversation();
        return Mono.fromFuture(manager.postPrivatePromptAnswer(accountId, instanceId, body, conversation))
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/private-prompt/{instanceId}/chat-turn")
    public Mono<GetPrivatePromptChatTurn> postPrivatePromptChatTurn(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptChatTurnRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String questionPart = payload == null ? null : payload.safeQuestionPart();
        String userMessage = payload == null ? null : payload.safeUserMessage();
        List<String> conversation = payload == null ? List.of() : payload.safeConversation();
        return Mono.fromFuture(
                manager.postPrivatePromptChatTurn(accountId, instanceId, questionPart, userMessage, conversation))
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/private-prompt/{instanceId}/skip")
    public Mono<Map<String, Object>> postPrivatePromptSkip(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.postPrivatePromptSkip(accountId, instanceId))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/private-prompt/{instanceId}/snooze")
    public Mono<Map<String, Object>> postPrivatePromptSnooze(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptSnoozeRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Long snoozeUntil = payload == null ? null : payload.snoozeUntil;
        return Mono.fromFuture(manager.postPrivatePromptSnooze(accountId, instanceId, snoozeUntil))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/private-prompt/debug/next")
    public Mono<ResponseEntity<ActivePrivatePrompt>> debugSummonNextPrivatePrompt(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.debugSummonNextPrivatePrompt(accountId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build())
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @GetMapping("/api/accounts/{id}/agent/matchmaking-followup")
    public Mono<ResponseEntity<ActivePrivatePrompt>> getActiveMatchmakingFollowup(
            @PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getActiveMatchmakingFollowup(accountId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/accounts/{id}/agent/matchmaking-followup/{instanceId}/answer")
    public Mono<ActivePrivatePrompt> postMatchmakingFollowupAnswer(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptAnswerRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String body = payload == null ? null : payload.safeBody();
        List<String> conversation = payload == null ? List.of() : payload.safeConversation();
        return Mono.fromFuture(manager.postMatchmakingFollowupAnswer(accountId, instanceId, body, conversation))
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/matchmaking-followup/{instanceId}/chat-turn")
    public Mono<GetPrivatePromptChatTurn> postMatchmakingFollowupChatTurn(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptChatTurnRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String questionPart = payload == null ? null : payload.safeQuestionPart();
        String userMessage = payload == null ? null : payload.safeUserMessage();
        List<String> conversation = payload == null ? List.of() : payload.safeConversation();
        return Mono.fromFuture(
                manager.postMatchmakingFollowupChatTurn(accountId, instanceId, questionPart, userMessage, conversation))
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/matchmaking-followup/{instanceId}/skip")
    public Mono<Map<String, Object>> postMatchmakingFollowupSkip(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.postMatchmakingFollowupSkip(accountId, instanceId))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/agent/matchmaking-followup/{instanceId}/snooze")
    public Mono<Map<String, Object>> postMatchmakingFollowupSnooze(
            @PathVariable("id") String idStr,
            @PathVariable("instanceId") String instanceId,
            @RequestBody(required = false) PostPrivatePromptSnoozeRequest payload,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Long snoozeUntil = payload == null ? null : payload.snoozeUntil;
        return Mono.fromFuture(manager.postMatchmakingFollowupSnooze(accountId, instanceId, snoozeUntil))
                .map(ok -> Collections.<String, Object>emptyMap())
                .onErrorMap(SecurityException.class,
                        ex -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Forbidden", ex))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex))
                .onErrorMap(IllegalArgumentException.class,
                        ex -> new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/signals")
    public Mono<GetSignals> postSignals(@PathVariable("id") String idStr,
            @RequestBody(required = false) PostSignalsRequest params,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (params == null || (!params.hasTokens() && !params.hasText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payload must include tokens or text.");
        }
        CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);
        String source = params.sourceOrDefault();
        String context = params.contextOrNull();
        String sourceId = params.sourceIdOrNull();
        if (params.hasTokens()) {
            List<String> tokens = params.safeTokens();
            writes = writes.thenCompose(v -> manager.postSignals(accountId, tokens, source, sourceId, context)
                    .thenApply(ok -> null));
        }
        if (params.hasText()) {
            String text = params.text;
            writes = writes.thenCompose(
                    v -> manager.extractAndAppendSignals(accountId, text, source, sourceId,
                            context != null ? context : text)
                            .thenApply(ignored -> null));
        }

        return Mono.fromFuture(writes.thenCompose(v -> manager.getSignals(accountId, accountId)))
                .map(thrift -> thrift == null
                        ? new GetSignals(accountId, List.of())
                        : new GetSignals(thrift))
                .defaultIfEmpty(new GetSignals(accountId, List.of()));
    }

    // For testing

    @GetMapping("/api/accounts/me")
    public Mono<GetAccount> whoami(WebSession session) {
        Long id = (Long) session.getAttribute("accountId");
        if (id == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        }
        return Mono.fromFuture(manager.getAccountWithId(id))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .map(GetAccount::new);
    }

    @GetMapping("/api/accounts/{id}/matches")
    public Mono<GetMatches> getMatches(
            @PathVariable("id") String idStr,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            WebSession session) {
        long accountId = now.calypso.backend.CalypsoHelpers.parseAccountId(idStr);
        Long me = (Long) session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getMatches(me, accountId, limit))
                .map(GetMatches::new);
    }

    @GetMapping("/api/accounts/{id}/facecards")
    public Mono<GetMatches> getFacecards(
            @PathVariable("id") String idStr,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            WebSession session) {
        long accountId = now.calypso.backend.CalypsoHelpers.parseAccountId(idStr);
        Long me = (Long) session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.getFacecards(me, accountId, limit))
                .map(GetMatches::new);
    }

    @PostMapping(value = "/api/accounts/{id}/direct-messages/{targetId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> postDirectMessage(
            @PathVariable("id") String idStr,
            @PathVariable("targetId") String targetIdStr,
            @RequestBody Map<String, Object> body,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = (Long) session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        long targetId = CalypsoHelpers.parseAccountId(targetIdStr);
        String text = body == null ? null : (body.get("text") instanceof String ? (String) body.get("text") : null);
        return Mono.fromFuture(manager.postDirectMessage(me, accountId, targetId, text))
                .map(msg -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("messageId", msg.getMessageId());
                    out.put("senderId", msg.getSenderId() + "-a");
                    out.put("receiverId", msg.getReceiverId() + "-a");
                    out.put("text", msg.getText());
                    out.put("sentAt", msg.getSentAt());
                    return out;
                });
    }

    @GetMapping("/api/accounts/{id}/direct-messages/{targetId}")
    public Mono<Map<String, Object>> getDirectMessages(
            @PathVariable("id") String idStr,
            @PathVariable("targetId") String targetIdStr,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = (Long) session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
        }
        long targetId = CalypsoHelpers.parseAccountId(targetIdStr);
        return Mono.fromFuture(manager.fetchDirectMessages(me, accountId, targetId, limit))
                .map(messages -> {
                    List<Map<String, Object>> serialized = new ArrayList<>();
                    for (DirectMessage msg : messages) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("messageId", msg.getMessageId());
                        m.put("senderId", msg.getSenderId() + "-a");
                        m.put("receiverId", msg.getReceiverId() + "-a");
                        m.put("text", msg.getText());
                        m.put("sentAt", msg.getSentAt());
                        serialized.add(m);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("messages", serialized);
                    return out;
                });
    }

}
