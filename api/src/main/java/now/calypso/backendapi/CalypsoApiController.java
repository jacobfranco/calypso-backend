package now.calypso.backendapi;

import java.util.*;
import java.util.concurrent.CompletableFuture;
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
import reactor.core.publisher.Mono;

@RestController
public class CalypsoApiController {
    private static final Logger LOG = LoggerFactory.getLogger(CalypsoApiController.class);

    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern
            .compile("^\\+[1-9][0-9]{7,14}$");
    private static final int MIN_AGE_YEARS = 18;

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
        return Mono.fromFuture(manager.requestPhoneCode(phoneNumber))
                .map(code -> {
                    HashMap<String, Object> payload = new HashMap<>();
                    String fallbackValue = System.getenv("CALYPSO_SMS_FALLBACK");
                    if (fallbackValue != null && fallbackValue.trim().equalsIgnoreCase("true")) {
                        payload.put("code", code);
                        payload.put("fallback", true);
                    }
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
    public Mono<Object> verifyPhoneCode(ServerHttpResponse response, @RequestBody PostPhoneVerify params) {
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
                .map(result -> (Object) new GetPhoneVerification(result))
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

    @GetMapping("/api/meta/tags/interests")
    public Map<String, List<String>> interestTags() {
        return tagService.interests();
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

    @GetMapping("/api/accounts/{id}/signals")
    public Mono<GetSignals> getSignals(@PathVariable("id") String idStr,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        return Mono.fromFuture(manager.getSignals(accountId, accountId))
                .map(thrift -> thrift == null
                        ? new GetSignals(accountId, List.of())
                        : new GetSignals(thrift))
                .defaultIfEmpty(new GetSignals(accountId, List.of()));
    }

    @GetMapping("/api/accounts/{id}/prompts/next")
    public Mono<GetPromptSuggestion> nextPrompt(@PathVariable("id") String idStr, WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.nextPrompt(accountId))
                .map(suggestion -> new GetPromptSuggestion(suggestion.question(), suggestion.targetAccountId(),
                        suggestion.targetScore()))
                .onErrorMap(IllegalStateException.class,
                        ex -> new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex));
    }

    @PostMapping("/api/accounts/{id}/prompts/{promptId}/response")
    public Mono<GetPromptResponse> respondToPrompt(
            @PathVariable("id") String idStr,
            @PathVariable("promptId") String promptId,
            @RequestBody(required = false) PostPromptResponseRequest params,
            WebSession session) {
        long accountId = CalypsoHelpers.parseAccountId(idStr);
        Long me = session.getAttribute("accountId");
        if (me == null || !me.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return Mono.fromFuture(manager.postPromptResponse(accountId, promptId, params))
                .map(GetPromptResponse::new)
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

}
