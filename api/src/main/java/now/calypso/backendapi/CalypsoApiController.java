package now.calypso.backendapi;

import java.util.HashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebSession;

import now.calypso.backend.data.AccountWithId;
import now.calypso.backendapi.pojos.*;
import reactor.core.publisher.Mono;

@RestController
public class CalypsoApiController {

    public static CalypsoApiManager manager;

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
        else if (!params.agreement) {
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

        // 5) Create the account
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

}
