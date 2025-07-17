package now.calypso.backendapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import now.calypso.backendapi.pojos.*;
import now.calypso.backend.CalypsoHelpers;
import now.calypso.backend.data.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class CalypsoApiControllerTest {

        @LocalServerPort
        int port;

        @Autowired
        WebTestClient client;

        @MockBean
        CalypsoApiManager mockManager;

        private String sessionToken;
        private String serializedId;

        @BeforeEach
        void loginAndCaptureSession() {
                // ensure controller static manager is set
                CalypsoApiController.manager = mockManager;

                // stub session lookup: map session token back to account ID
                when(mockManager.getAccountIdFromAuthCode(anyString()))
                                .thenReturn(CompletableFuture.completedFuture(7L));

                // Stub account creation flow
                when(mockManager.postAccount(any(PostAccount.class)))
                                .thenReturn(CompletableFuture.completedFuture(true));
                when(mockManager.getAccountId("foo@bar.com"))
                                .thenReturn(CompletableFuture.completedFuture(7L));
                Account acct = new Account()
                                .setName("Foo").setEmail("foo@bar.com")
                                .setPwdHash("x").setLocale("en_US")
                                .setUuid("u").setPublicKey("p")
                                .setTimestamp(0L).setAdmin(false);
                when(mockManager.getAccountWithId(7L))
                                .thenReturn(CompletableFuture.completedFuture(new AccountWithId(7L, acct)));
                // stub postAuthCode for login and account endpoints
                when(mockManager.postAuthCode(anyLong(), anyString()))
                                .thenReturn(CompletableFuture.completedFuture(true));

                // Perform real login and capture session token
                PostAccount login = new PostAccount("Foo", "foo@bar.com", "pw", true, "en_US", "");
                GetToken tokenBody = client.post()
                                .uri("/api/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(login)
                                .exchange()
                                .expectStatus().isOk()
                                .returnResult(GetToken.class)
                                .getResponseBody()
                                .blockFirst();

                sessionToken = tokenBody.access_token;
                serializedId = CalypsoHelpers.serializeAccountId(7L);
        }

        // POST /api/accounts → validation
        @Test
        void postAccount_emptyName_returns422() {
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("", "x@x.com", "pw", true, "en_US", ""))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_nameTooLong_returns422() {
                String longName = "X".repeat(CalypsoApiConfig.MAX_NAME_LENGTH + 1);
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount(longName, "x@x.com", "pw", true, "en_US", ""))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_invalidCharactersInName_returns422() {
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Bad🌟Name", "x@x.com", "pw", true, "en_US", ""))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_agreementFalse_returns422() {
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Alice", "a@x.com", "pw", false, "en_US", ""))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_valid_createsToken200() {
                when(mockManager.getAccountId("user@example.com"))
                                .thenReturn(CompletableFuture.completedFuture(42L));
                Account thriftAcct = new Account().setName("Alice").setEmail("user@example.com")
                                .setPwdHash("h").setLocale("en_US").setUuid("u").setPublicKey("p")
                                .setTimestamp(0L).setAdmin(false);
                when(mockManager.getAccountWithId(42L))
                                .thenReturn(CompletableFuture.completedFuture(new AccountWithId(42L, thriftAcct)));
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Alice", "user@example.com", "pw", true, "en_US", ""))
                                .exchange().expectStatus().isOk();
        }

        // GET /api/accounts/{id}
        @Test
        void getAccount_invalidIdFormat_returns400() {
                client.get().uri("/api/accounts/100")
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void getAccount_validIdButNotFound_returns404() {
                String id = CalypsoHelpers.serializeAccountId(999L);
                when(mockManager.getAccountWithId(999L))
                                .thenReturn(CompletableFuture.completedFuture(null));
                client.get().uri("/api/accounts/" + id)
                                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void getAccount_found_returns200() {
                String id = CalypsoHelpers.serializeAccountId(100L);
                Account a = new Account().setName("Bob").setEmail("b@x.com").setPwdHash("h")
                                .setLocale("en_US").setUuid("u").setPublicKey("p").setTimestamp(0L).setAdmin(false);
                when(mockManager.getAccountWithId(100L))
                                .thenReturn(CompletableFuture.completedFuture(new AccountWithId(100L, a)));
                client.get().uri("/api/accounts/" + id)
                                .exchange().expectStatus().isOk();
        }

        // POST /api/accounts/{id}/filters
        @Test
        void postFilters_unauthenticated_returns403() {
                client.post().uri("/api/accounts/" + serializedId + "/filters")
                                .bodyValue(new PostFilters())
                                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void postFilters_valid_returns200AndBody() {
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .bodyValue(new PostFilters())
                                .exchange().expectStatus().isOk()
                                .expectBody(GetFilters.class)
                                .value(gf -> assertEquals(7, gf.filters.getAccountId()));
        }

        @Test
        void postFilters_managerFails_returns500() {
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(false));
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .bodyValue(new PostFilters())
                                .exchange().expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // GET /api/accounts/{id}/filters
        @Test
        void getFilters_unauthenticated_returns403() {
                client.get().uri("/api/accounts/" + serializedId + "/filters")
                                .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void getFilters_notFound_returns404() {
                when(mockManager.getFilters(eq(7L), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(null));
                client.get()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void getFilters_found_returns200AndBody() {
                Filters f = new Filters().setAccountId(7);
                when(mockManager.getFilters(eq(7L), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(f));
                client.get()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange().expectStatus().isOk()
                                .expectBody(GetFilters.class)
                                .value(gf -> assertEquals(7, gf.filters.getAccountId()));
        }

        // ---------------------------------------------------------------------------
        // builds a valid PostFilters object we can tweak per test
        // ---------------------------------------------------------------------------
        private PostFilters baseFilters() {
                PostFilters pf = new PostFilters();

                RangeFilter age = new RangeFilter()
                                .setSelf(25)
                                .setMin(22)
                                .setMax(30);
                pf.age = age;

                TagPreference wlPref = new TagPreference()
                                .setTag("weightlifting")
                                .setImportance(Importance.PREFERENCE);

                ManyToManyFilter life = new ManyToManyFilter()
                                .setSelf(new ArrayList<>(List.of("weightlifting"))) // ← modifiable
                                .setPreferences(List.of(wlPref));
                pf.lifestyle = life;

                return pf;
        }

        // ---------------------------------------------------------------------------
        // VALID payload → 200
        // ---------------------------------------------------------------------------
        @Test
        void postFilters_validComplex_returns200() {
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post().uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(baseFilters())
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody(GetFilters.class)
                                .value(gf -> assertEquals(7, gf.filters.getAccountId()));
        }

        // ---------------------------------------------------------------------------
        // unknown tag → 400 BAD_REQUEST (validator fires before manager call)
        // ---------------------------------------------------------------------------
        @Test
        void postFilters_unknownTag_returns400() {
                PostFilters bad = baseFilters();
                bad.lifestyle.getSelf().add("made_up_tag");

                client.post().uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();

                // manager should NOT have been invoked
                verify(mockManager, times(0)).postFilters(any(), anyLong());
        }

        // ---------------------------------------------------------------------------
        // min > max age → 400 BAD_REQUEST
        // ---------------------------------------------------------------------------
        @Test
        void postFilters_invalidAgeRange_returns400() {
                PostFilters bad = baseFilters();
                bad.age.setMin(35).setMax(30); // inverted

                client.post().uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();

                verify(mockManager, times(0)).postFilters(any(), anyLong());
        }

}
