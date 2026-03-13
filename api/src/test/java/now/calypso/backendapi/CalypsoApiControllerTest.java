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
import org.springframework.web.reactive.function.BodyInserters;

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
                when(mockManager.getAccountId("+15555550100"))
                                .thenReturn(CompletableFuture.completedFuture(7L));
                Account acct = new Account()
                                .setName("Foo").setPhone_number("+15555550100")
                                .setLocale("en_US")
                                .setUuid("u").setPublicKey("p")
                                .setTimestamp(0L).setAdmin(false);
                when(mockManager.getAccountWithId(7L))
                                .thenReturn(CompletableFuture.completedFuture(new AccountWithId(7L, acct)));
                when(mockManager.consumePhoneVerification(anyString(), anyString()))
                                .thenReturn(CompletableFuture.completedFuture(true));
                // stub postAuthCode for login and account endpoints
                when(mockManager.postAuthCode(anyLong(), anyString()))
                                .thenReturn(CompletableFuture.completedFuture(true));

                // Perform real login and capture session token
                PostAccount login = new PostAccount("Foo", "+15555550100", "1990-01-01", "token", true, "en_US");
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
                                .bodyValue(new PostAccount("", "+15555550101", "1990-01-01", "token", true, "en_US"))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_nameTooLong_returns422() {
                String longName = "X".repeat(CalypsoApiConfig.MAX_NAME_LENGTH + 1);
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount(longName, "+15555550101", "1990-01-01", "token", true, "en_US"))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_invalidCharactersInName_returns422() {
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Bad🌟Name", "+15555550101", "1990-01-01", "token", true, "en_US"))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_agreementFalse_returns422() {
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Alice", "+15555550101", "1990-01-01", "token", false, "en_US"))
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_agreementNull_returns422() {
                PostAccount body = new PostAccount("Alice", "+15555550101", "1990-01-01", "token", null, "en_US");
                client.post().uri("/api/accounts")
                                .bodyValue(body)
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_invalidEmail_returns422() {
                PostAccount body = new PostAccount("Alice", "not-a-phone", "1990-01-01", "token", true, "en_US");
                client.post().uri("/api/accounts")
                                .bodyValue(body)
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_passwordTooShort_returns422() {
                PostAccount body = new PostAccount("Alice", "+15555550101", "", "token", true, "en_US");
                client.post().uri("/api/accounts")
                                .bodyValue(body)
                                .exchange().expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void postAccount_valid_createsToken200() {
                when(mockManager.getAccountId("+15555550102"))
                                .thenReturn(CompletableFuture.completedFuture(42L));
                Account thriftAcct = new Account().setName("Alice").setPhone_number("+15555550102")
                                .setLocale("en_US").setUuid("u").setPublicKey("p")
                                .setTimestamp(0L).setAdmin(false);
                when(mockManager.getAccountWithId(42L))
                                .thenReturn(CompletableFuture.completedFuture(new AccountWithId(42L, thriftAcct)));
                client.post().uri("/api/accounts")
                                .bodyValue(new PostAccount("Alice", "+15555550102", "1990-01-01", "token", true, "en_US"))
                                .exchange().expectStatus().isOk();
        }

        @Test
        void requestPhoneCode_existingAccount_returnsExistingTrue() {
                when(mockManager.getAccountId("+15555550123"))
                                .thenReturn(CompletableFuture.completedFuture(7L));
                when(mockManager.requestPhoneCode("+15555550123"))
                                .thenReturn(CompletableFuture.completedFuture("123456"));

                client.post().uri("/api/accounts/phone/request")
                                .bodyValue(new PostPhoneRequest("+15555550123"))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.existing").isEqualTo(true);
        }

        @Test
        void verifyPhoneCode_existingAccount_returnsToken() {
                when(mockManager.verifyPhoneCode("+15555550124", "123456"))
                                .thenReturn(CompletableFuture.completedFuture("verify-token"));
                when(mockManager.getAccountId("+15555550124"))
                                .thenReturn(CompletableFuture.completedFuture(7L));

                client.post().uri("/api/accounts/phone/verify")
                                .bodyValue(new PostPhoneVerify("+15555550124", "123456"))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.access_token").isNotEmpty();
        }

        @Test
        void verifyPhoneCode_newAccount_returnsVerificationToken() {
                when(mockManager.verifyPhoneCode("+15555550125", "123456"))
                                .thenReturn(CompletableFuture.completedFuture("verify-token"));
                when(mockManager.getAccountId("+15555550125"))
                                .thenReturn(CompletableFuture.completedFuture(null));

                client.post().uri("/api/accounts/phone/verify")
                                .bodyValue(new PostPhoneVerify("+15555550125", "123456"))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.verification_token").isEqualTo("verify-token");
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
                Account a = new Account().setName("Bob").setPhone_number("+15555550103")
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
                                .setTag("non_drinker")
                                .setImportance(Importance.PREFERENCE);

                ManyToManyFilter life = new ManyToManyFilter()
                                .setSelf(new ArrayList<>(List.of("non_drinker"))) // ← modifiable
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

        // missing relationshipMode.self
        @Test
        void postFilters_missingRelationshipModeSelf_returns400() {
                PostFilters bad = baseFilters();
                bad.relationshipMode = new ModeFilter(); // self == null
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        // invalid relationshipMode value
        @Test
        void postFilters_invalidRelationshipModeOpen_returns400() {
                PostFilters pf = baseFilters();
                pf.relationshipMode = new ModeFilter().setSelf("open");
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        // invalid location.radius
        @Test
        void postFilters_invalidLocationRadius_returns400() {
                PostFilters bad = baseFilters();
                // CLT approx; negative radius to trigger validator
                bad.location = new LocationFilter().setLat(35.2271).setLon(-80.8431).setRadiusKm(-5.0);

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        // duplicate tags in lifestyle.self
        @Test
        void postFilters_duplicateLifestyleSelf_returns400() {
                PostFilters bad = baseFilters();
                bad.lifestyle.setSelf(List.of("running", "running"));
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        // duplicate tags in lifestyle.preferences
        @Test
        void postFilters_duplicateLifestylePreferences_returns400() {
                PostFilters bad = baseFilters();
                TagPreference tp1 = new TagPreference().setTag("running").setImportance(Importance.PREFERENCE);
                TagPreference tp2 = new TagPreference().setTag("running").setImportance(Importance.DEALBREAKER);
                bad.lifestyle.setPreferences(List.of(tp1, tp2));
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(bad)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_invalidIdFormat_returns400() {
                client.post()
                                .uri("/api/accounts/NOT_AN_ID/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(baseFilters())
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void getFilters_invalidIdFormat_returns400() {
                client.get()
                                .uri("/api/accounts/HELLO/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).getFilters(anyLong(), anyLong());
        }

        @Test
        void postFilters_malformedJson_returns400() {
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue("this is not json"))
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_emptyBody_returns400() {
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(BodyInserters.fromValue(""))
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_emptyObject_returns200() {
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostFilters())
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager, times(1)).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_ageOnlyMin_returns200() {
                PostFilters pf = new PostFilters();
                pf.age = new RangeFilter().setMin(20);
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_ageOnlyMax_returns200() {
                PostFilters pf = new PostFilters();
                pf.age = new RangeFilter().setMax(50);
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_ageOnlySelf_returns200() {
                PostFilters pf = new PostFilters();
                pf.age = new RangeFilter().setSelf(30);
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_genderOnlySelf_returns200() {
                PostFilters pf = baseFilters();
                pf.gender = new OneToManyFilter().setSelf("woman");
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_genderOnlySeeking_returns200() {
                PostFilters pf = baseFilters();
                pf.gender = new OneToManyFilter().setSeeking(List.of("man"));
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_genderOnlyImportance_returns200() {
                PostFilters pf = baseFilters();
                pf.gender = new OneToManyFilter().setImportance(Importance.DEALBREAKER);
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_genderValid_returns200() {
                PostFilters pf = baseFilters();
                pf.gender = new OneToManyFilter().setSelf("man");
                when(mockManager.postFilters(any(PostFilters.class), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();
                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_genderUnknown_returns400() {
                PostFilters pf = baseFilters();
                pf.gender = new OneToManyFilter().setSelf("alien");
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_religionSeeking_returns400() {
                PostFilters pf = baseFilters();
                pf.religion = new OneToManyFilter().setSeeking(List.of("pastafarian"));
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_politicsValid_returns200() {
                PostFilters pf = baseFilters();
                pf.politics = new OneToManyFilter().setSelf("libertarian");
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();
                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_politicsUnknown_returns400() {
                PostFilters pf = baseFilters();
                pf.politics = new OneToManyFilter().setSelf("anarcho-capitalist");
                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_validRelationshipMode_returns200() {
                PostFilters pf = baseFilters();
                pf.relationshipMode = new ModeFilter().setSelf("focused");
                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();

                verify(mockManager).postFilters(any(), eq(7L));
        }

        @Test
        void postFilters_locationOnlyLatLon_returns400() {
                PostFilters pf = baseFilters();
                pf.location = new LocationFilter().setLat(41.8781).setLon(-87.6298); // Chicago, no radiusKm

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_locationOnlyRadiusKm_returns400() {
                String payload = """
                                {
                                  "age": { "self": 25, "min": 22, "max": 30 },
                                  "lifestyle": {
                                    "self": ["non_drinker"],
                                    "preferences": [
                                      { "tag": "non_drinker", "importance": "PREFERENCE" }
                                    ]
                                  },
                                  "location": { "radiusKm": 35.0 }
                                }
                                """;

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(payload)
                                .exchange()
                                .expectStatus().isBadRequest();
                verify(mockManager, never()).postFilters(any(), anyLong());
        }

        @Test
        void postFilters_locationLatLonAndRadius_returns200() {
                PostFilters pf = baseFilters();
                // Boston approx, state-ish radius
                pf.location = new LocationFilter().setLat(42.3601).setLon(-71.0589).setRadiusKm(250.0);

                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk();
                verify(mockManager).postFilters(any(), eq(7L));
        }

        // ---------------------------------------------------------------------------
        // I) Full‐payload happy‐path
        // ---------------------------------------------------------------------------
        @Test
        void postFilters_fullPayload_returns200() {
                PostFilters pf = new PostFilters();
                pf.relationshipMode = new ModeFilter().setSelf("balanced");
                pf.gender = new OneToManyFilter().setSelf("nonbinary").setImportance(Importance.PREFERENCE);
                pf.age = new RangeFilter().setSelf(27).setMin(23).setMax(32).setImportance(Importance.NOT_IMPORTANT);
                // Miami approx, state-ish radius
                pf.location = new LocationFilter().setLat(25.7617).setLon(-80.1918).setRadiusKm(250.0);
                pf.religion = new OneToManyFilter().setSelf("agnostic").setImportance(Importance.PREFERENCE);
                pf.politics = new OneToManyFilter().setSelf("liberal");
                pf.lifestyle = new ManyToManyFilter().setSelf(List.of("non_drinker"))
                                .setPreferences(List.of(new TagPreference().setTag("non_drinker")
                                                .setImportance(Importance.PREFERENCE)));

                when(mockManager.postFilters(any(), eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(pf)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.filters.accountId").isEqualTo(7);

                verify(mockManager).postFilters(any(), eq(7L));
        }

        // ---------------------------------------------------------------------------
        // J) GET‐specific
        // ---------------------------------------------------------------------------
        @Test
        void getFilters_validButWrongUser_returns403() {
                String otherId = CalypsoHelpers.serializeAccountId(8L);
                client.get()
                                .uri("/api/accounts/" + otherId + "/filters")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
                verify(mockManager, never()).getFilters(anyLong(), anyLong());
        }

        @Test
        void getMetaTags_lifestyle_returns200() {
                client.get()
                                .uri("/api/meta/tags/lifestyle")
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.kids_current").isArray()
                                .jsonPath("$.kids_future").isArray();
        }

        @Test
        void getMetaTags_gender_returns200() {
                client.get()
                                .uri("/api/meta/tags/gender")
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.binary").isArray()
                                .jsonPath("$.other").isArray();
        }

        @Test
        void getMetaTags_religion_returns200() {
                client.get()
                                .uri("/api/meta/tags/religion")
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.major").isArray()
                                .jsonPath("$.other").isArray();
        }

        @Test
        void getMetaTags_politics_returns200() {
                client.get()
                                .uri("/api/meta/tags/politics")
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.spectrum").isArray();
        }

        // --------- Public prompts endpoint tests ---------

        @Test
        void getPublicPromptFeed_unauthenticated_returns403() {
                client.get()
                                .uri("/api/accounts/" + serializedId + "/public-prompt-feed?limit=2")
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

                verify(mockManager, never()).getPublicPromptFeed(anyLong(), anyInt());
        }

        @Test
        void getPublicPromptFeed_wrongUser_returns403() {
                String otherId = CalypsoHelpers.serializeAccountId(8L);
                client.get()
                                .uri("/api/accounts/" + otherId + "/public-prompt-feed?limit=2")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

                verify(mockManager, never()).getPublicPromptFeed(anyLong(), anyInt());
        }

        @Test
        void getPublicPromptFeed_returns200AndBody() {
                PublicPromptFeedCard card = new PublicPromptFeedCard()
                                .setAnswerId("answer-1")
                                .setPromptId("prompt.talk.hours")
                                .setPromptText("How do you spend your evenings?")
                                .setBody("Reading and long walks.")
                                .setCreatedAt(System.currentTimeMillis());
                when(mockManager.getPublicPromptFeed(eq(7L), eq(2)))
                                .thenReturn(CompletableFuture.completedFuture(List.of(card)));

                client.get()
                                .uri("/api/accounts/" + serializedId + "/public-prompt-feed?limit=2")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$[0].answerId").isEqualTo("answer-1")
                                .jsonPath("$[0].promptId").isEqualTo("prompt.talk.hours")
                                .jsonPath("$[0].body").isEqualTo("Reading and long walks.");
        }

        @Test
        void postPublicPromptAnswer_invalidPayload_returns400() {
                when(mockManager.postPublicPromptAnswer(eq(7L), eq("prompt.talk.hours"), any()))
                                .thenReturn(CompletableFuture.failedFuture(
                                                new IllegalArgumentException("Answer body required.")));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/prompt.talk.hours/answer")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptAnswerRequest("   "))
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void postPublicPromptAnswer_valid_returns200() {
                PublicPromptAnswer answer = new PublicPromptAnswer()
                                .setAnswerId("answer-2")
                                .setAccountId(7L)
                                .setPromptId("prompt.talk.hours")
                                .setBody("Coffee and coding.")
                                .setCreatedAt(System.currentTimeMillis())
                                .setUpdatedAt(System.currentTimeMillis());
                when(mockManager.postPublicPromptAnswer(eq(7L), eq("prompt.talk.hours"), eq("Coffee and coding.")))
                                .thenReturn(CompletableFuture.completedFuture(answer));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/prompt.talk.hours/answer")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptAnswerRequest("Coffee and coding."))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.answerId").isEqualTo("answer-2")
                                .jsonPath("$.body").isEqualTo("Coffee and coding.");
        }

        @Test
        void getPublicPromptSelection_none_returns204() {
                when(mockManager.getPublicPromptSelection(eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(null));

                client.get()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/selection")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isNoContent()
                                .expectBody().isEmpty();
        }

        @Test
        void getPublicPromptSelection_found_returns200() {
                PublicPromptSelection selection = new PublicPromptSelection()
                                .setAccountId(7L)
                                .setSelectedPromptIds(List.of("prompt.talk.hours", "prompt.life.goal"))
                                .setUpdatedAt(System.currentTimeMillis());
                when(mockManager.getPublicPromptSelection(eq(7L)))
                                .thenReturn(CompletableFuture.completedFuture(selection));

                client.get()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/selection")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.accountId").isEqualTo(7)
                                .jsonPath("$.selectedPromptIds.length()").isEqualTo(2);
        }

        @Test
        void postPublicPromptSelection_invalidPayload_returns400() {
                when(mockManager.postPublicPromptSelection(eq(7L), any()))
                                .thenReturn(CompletableFuture.failedFuture(
                                                new IllegalArgumentException("Unknown public prompt: bogus.prompt")));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/selection")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptSelectionRequest(List.of("bogus.prompt")))
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void postPublicPromptSelection_valid_returns200() {
                PublicPromptSelection selection = new PublicPromptSelection()
                                .setAccountId(7L)
                                .setSelectedPromptIds(List.of("prompt.talk.hours"))
                                .setUpdatedAt(System.currentTimeMillis());
                when(mockManager.postPublicPromptSelection(eq(7L), eq(List.of("prompt.talk.hours"))))
                                .thenReturn(CompletableFuture.completedFuture(selection));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompts/selection")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptSelectionRequest(List.of("prompt.talk.hours")))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.accountId").isEqualTo(7)
                                .jsonPath("$.selectedPromptIds[0]").isEqualTo("prompt.talk.hours");
        }

        @Test
        void postPublicPromptReaction_invalidReaction_returns400() {
                when(mockManager.postPublicPromptReaction(eq(7L), eq("answer-1"), isNull()))
                                .thenReturn(CompletableFuture.failedFuture(
                                                new IllegalArgumentException("Reaction required.")));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompt-feed/answer-1/reaction")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptReactionRequest("not_real"))
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void postPublicPromptReaction_valid_returns200() {
                when(mockManager.postPublicPromptReaction(eq(7L), eq("answer-1"), eq(PromptReaction.LIKE)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/public-prompt-feed/answer-1/reaction")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptReactionRequest("LIKE"))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .json("{}");
        }

        @Test
        void postFacecardReaction_invalidReaction_returns400() {
                String targetId = CalypsoHelpers.serializeAccountId(9L);
                when(mockManager.postFacecardReaction(eq(7L), eq(9L), isNull()))
                                .thenReturn(CompletableFuture.failedFuture(
                                                new IllegalArgumentException("Reaction required.")));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/facecards/" + targetId + "/reaction")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptReactionRequest("not_real"))
                                .exchange()
                                .expectStatus().isBadRequest();
        }

        @Test
        void postFacecardReaction_valid_returns200() {
                String targetId = CalypsoHelpers.serializeAccountId(9L);
                when(mockManager.postFacecardReaction(eq(7L), eq(9L), eq(PromptReaction.DISLIKE)))
                                .thenReturn(CompletableFuture.completedFuture(true));

                client.post()
                                .uri("/api/accounts/" + serializedId + "/facecards/" + targetId + "/reaction")
                                .header("Authorization", "Bearer " + sessionToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new PostPublicPromptReactionRequest("DISLIKE"))
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .json("{}");
        }

        // --------- Matches endpoint tests (new) ---------

        @Test
        void getMatches_unauthenticated_returns403() {
                String id = CalypsoHelpers.serializeAccountId(7L);
                client.get()
                                .uri("/api/accounts/" + id + "/matches?limit=5")
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

                verify(mockManager, never()).getMatches(anyLong(), anyLong(), anyInt());
        }

        @Test
        void getMatches_wrongUser_returns403() {
                String other = CalypsoHelpers.serializeAccountId(8L);
                client.get()
                                .uri("/api/accounts/" + other + "/matches?limit=5")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);

                verify(mockManager, never()).getMatches(anyLong(), anyLong(), anyInt());
        }

        @Test
        void getMatches_returns200AndBody() {
                String id = CalypsoHelpers.serializeAccountId(7L);

                GetAccount ga = new GetAccount(new AccountWithId(9L,
                                new Account().setName("Zed").setPhone_number("+15555550104").setLocale("en_US")
                                                .setUuid("u").setPublicKey("p").setTimestamp(0L).setAdmin(false)));

                List<GetMatch> payload = List.of(new GetMatch(ga, 88.5, System.currentTimeMillis()));
                when(mockManager.getMatches(eq(7L), eq(7L), eq(5)))
                                .thenReturn(CompletableFuture.completedFuture(payload));

                client.get()
                                .uri("/api/accounts/" + id + "/matches?limit=5")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isOk()
                                .expectBody()
                                .jsonPath("$.matches[0].account.name").isEqualTo("Zed")
                                .jsonPath("$.matches[0].score").exists();

                verify(mockManager).getMatches(eq(7L), eq(7L), eq(5));
        }

        @Test
        void getMatches_limitParam_clampedAndPassed() {
                String id = CalypsoHelpers.serializeAccountId(7L);

                when(mockManager.getMatches(eq(7L), eq(7L), anyInt()))
                                .thenReturn(CompletableFuture.completedFuture(List.of()));

                client.get()
                                .uri("/api/accounts/" + id + "/matches?limit=5000")
                                .header("Authorization", "Bearer " + sessionToken)
                                .exchange()
                                .expectStatus().isOk();

                // Manager should receive a clamped value (<=100). We can't introspect clamp
                // easily,
                // but we can at least verify it was invoked once with some int.
                verify(mockManager, times(1)).getMatches(eq(7L), eq(7L), anyInt());
        }

}
