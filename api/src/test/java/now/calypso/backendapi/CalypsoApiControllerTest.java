package now.calypso.backendapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

import now.calypso.backendapi.pojos.PostAccount;
import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.Account;
import now.calypso.backend.data.AccountWithId;

/**
 * Slice tests for CalypsoApiController, using the correct serialized ID format
 * and stubbing postAuthCode(...) so loginWithAccount(...) does not NPE.
 */
class CalypsoApiControllerTest {

    private WebTestClient client;
    private CalypsoApiManager mockManager;

    @BeforeEach
    void setUp() {
        // 1) Mock the manager
        mockManager = mock(CalypsoApiManager.class);
        CalypsoApiController.manager = mockManager;

        // 2) Bind only the controller (no Spring context)
        CalypsoApiController controller = new CalypsoApiController();
        client = WebTestClient.bindToController(controller).build();
    }

    //
    // POST /api/accounts → 422 on invalid name/agreement, 200 on valid.
    //

    @Test
    void postAccount_emptyName_returns422() {
        PostAccount badReq = new PostAccount(
            "",                         // name (blank)
            "foo@bar.com",             // email
            "dummyPass",               // password
            true,                      // agreement
            "en_US",                   // locale
            ""                         // reason
        );

        client.post()
              .uri("/api/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(badReq)
              .exchange()
              .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void postAccount_nameTooLong_returns422() {
        String longName = "X".repeat(CalypsoApiConfig.MAX_NAME_LENGTH + 1);

        PostAccount req = new PostAccount(
            longName,
            "foo@bar.com",
            "dummyPass",
            true,
            "en_US",
            ""
        );

        client.post()
              .uri("/api/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(req)
              .exchange()
              .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void postAccount_invalidCharactersInName_returns422() {
        PostAccount req = new PostAccount(
            "Bad🌟Name",
            "foo@bar.com",
            "dummyPass",
            true,
            "en_US",
            ""
        );

        client.post()
              .uri("/api/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(req)
              .exchange()
              .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void postAccount_agreementFalse_returns422() {
        PostAccount req = new PostAccount(
            "Alice",
            "alice@x.com",
            "dummyPass",
            false,
            "en_US",
            ""
        );

        client.post()
              .uri("/api/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(req)
              .exchange()
              .expectStatus().isEqualTo(UNPROCESSABLE_ENTITY);
    }

    @Test
    void postAccount_valid_createsToken200() {
        // 1) Stub manager.postAccount(...) → true
        when(mockManager.postAccount(any(PostAccount.class)))
            .thenReturn(CompletableFuture.completedFuture(true));

        // 2) Stub manager.getAccountId(...) → 42
        when(mockManager.getAccountId("user@example.com"))
            .thenReturn(CompletableFuture.completedFuture(42L));

        // 3) Build a Thrift Account with all required fields
        Account thriftAcct = new Account()
            .setName("Alice")
            .setEmail("user@example.com")
            .setPwdHash("dummyHash")
            .setLocale("en_US")
            .setUuid("uuid-1234")
            .setPublicKey("pubKey")
            .setTimestamp(0L)
            .setAdmin(false);

        // 4) Stub manager.getAccountWithId(42) → AccountWithId(42, thriftAcct)
        when(mockManager.getAccountWithId(42L))
            .thenReturn(CompletableFuture.completedFuture(new AccountWithId(42L, thriftAcct)));

        // 5) IMPORTANT: Stub manager.postAuthCode(42, anyString()) so loginWithAccount does not NPE
        when(mockManager.postAuthCode(eq(42L), anyString()))
            .thenReturn(CompletableFuture.completedFuture(true));

        PostAccount goodReq = new PostAccount(
            "Alice",
            "user@example.com",
            "dummyPass",
            true,
            "en_US",
            ""
        );

        client.post()
              .uri("/api/accounts")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(goodReq)
              .exchange()
              .expectStatus().isOk();
    }

    //
    // GET /api/accounts/{id}
    //   • If parseAccountId(...) blows up, it becomes a 500.
    //   • If parse succeeds but manager returns null, it becomes a 404.
    //   • If parse succeeds and manager returns an AccountWithId, we get 200.
    //

    @Test
    void getAccount_invalidIdFormat_returns500() {
        // “100” is not in the correct serialized format → parseAccountId throws → 500
        client.get()
              .uri("/api/accounts/100")
              .exchange()
              .expectStatus().isEqualTo(INTERNAL_SERVER_ERROR);
    }

    @Test
    void getAccount_validIdButNotFound_returns404() {
        // Use CalypsoHelpers.serializeAccountId so parseAccountId(...) succeeds.
        String serializedId = CalypsoHelpers.serializeAccountId(999L);

        // Stub manager.getAccountWithId(999) → null
        when(mockManager.getAccountWithId(999L))
            .thenReturn(CompletableFuture.completedFuture(null));

        client.get()
              .uri("/api/accounts/" + serializedId)
              .exchange()
              .expectStatus().isEqualTo(NOT_FOUND);
    }

    @Test
    void getAccount_found_returns200() {
        Account thriftAcct = new Account()
            .setName("Bob")
            .setEmail("bob@x.com")
            .setPwdHash("dummyHash")
            .setLocale("en_US")
            .setUuid("uuid-9999")
            .setPublicKey("pubKey")
            .setTimestamp(0L)
            .setAdmin(false);

        // Stub manager.getAccountWithId(100) → AccountWithId(100, thriftAcct)
        when(mockManager.getAccountWithId(100L))
            .thenReturn(CompletableFuture.completedFuture(new AccountWithId(100L, thriftAcct)));

        // Serialize 100L so parseAccountId(...) returns 100
        String serializedId = CalypsoHelpers.serializeAccountId(100L);

        client.get()
              .uri("/api/accounts/" + serializedId)
              .exchange()
              .expectStatus().isEqualTo(OK);
    }
}
