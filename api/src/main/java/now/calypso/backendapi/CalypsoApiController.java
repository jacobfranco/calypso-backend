package now.calypso.backendapi;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;

import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.*;
import now.calypso.backendapi.filters.*;
import now.calypso.backendapi.pojos.*;
import reactor.core.publisher.Mono;

@RestController
public class CalypsoApiController {

    private static final java.util.regex.Pattern EMAIL_PATTERN = java.util.regex.Pattern
            .compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

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
        // 5) Validate the email looks sane-ish
        else if (params.email == null || !EMAIL_PATTERN.matcher(params.email).matches()) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Email is invalid",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("email", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Email must be a valid address"));
                        }
                    }));
        }
        // 6) Password guardrail
        else if (params.password == null || params.password.length() < MIN_PASSWORD_LENGTH) {
            response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
            return Mono.just(new GetErrorDetails(
                    "Password too short",
                    new HashMap<String, GetErrorDetails.Error>() {
                        {
                            put("password", new GetErrorDetails.Error(
                                    "ERR_INVALID",
                                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
                        }
                    }));
        }

        // 7) Create the account
        return Mono.fromFuture(manager.postAccount(params))
                .flatMap(success -> {
                    if (success) {
                        // lookup by email instead of username
                        return Mono.fromFuture(manager.getAccountId(params.email))
                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                                .flatMap(accountId -> Mono.fromFuture(manager.getAccountWithId(accountId)))
                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                                .flatMap(accountWithId -> this.loginWithAccount(
                                        session,
                                        "read write follow push",
                                        accountWithId));
                    } else {
                        // on failure, treat it as email conflict (name no longer needs to be unique)
                        response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Mono.just(new GetErrorDetails(
                                "Validation failed",
                                new HashMap<String, GetErrorDetails.Error>() {
                                    {
                                        put("email", new GetErrorDetails.Error(
                                                "ERR_TAKEN",
                                                "Email already in use"));
                                    }
                                }));
                    }
                });
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
