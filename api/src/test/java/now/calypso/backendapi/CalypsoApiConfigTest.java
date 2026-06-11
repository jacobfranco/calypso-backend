package now.calypso.backendapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.web.cors.CorsConfiguration;

class CalypsoApiConfigTest {
    @Test
    void cachedSessionLookupDoesNotBlockOnBackendAuthRead() {
        CalypsoApiManager previous = CalypsoApiController.manager;
        CalypsoApiManager manager = mock(CalypsoApiManager.class);
        String token = "cached-token";
        when(manager.getAccountIdFromAuthCode(token)).thenReturn(new CompletableFuture<>());
        CalypsoApiController.manager = manager;
        try {
            ReactiveSessionRepository<MapSession> repository = new CalypsoApiConfig().reactiveSessionRepository();
            MapSession session = new MapSession();
            session.setId(token);
            session.setAttribute("accountId", 123L);
            session.setAttribute("accountName", "Jacob");
            repository.save(session).block(Duration.ofSeconds(1));

            MapSession found = assertTimeout(
                    Duration.ofMillis(200),
                    () -> repository.findById(token).block(Duration.ofSeconds(1)));

            assertNotNull(found);
            assertEquals(123L, found.<Long>getAttribute("accountId"));
        } finally {
            CalypsoApiController.manager = previous;
        }
    }

    @Test
    void corsIsClosedByDefault() {
        CorsConfiguration config = CalypsoApiConfig.corsConfiguration(new MockEnvironment());

        assertEquals(Collections.emptyList(), config.getAllowedOrigins());
        assertEquals(Collections.emptyList(), config.getAllowedOriginPatterns());
        assertNull(config.checkOrigin("http://localhost:8081"));
        assertEquals(false, config.getAllowCredentials());
    }

    @Test
    void corsAllowsOnlyConfiguredExactOrigins() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(CalypsoApiConfig.CORS_ALLOWED_ORIGINS_PROPERTY,
                        "https://admin.example.com/, https://app.example.com");

        CorsConfiguration config = CalypsoApiConfig.corsConfiguration(environment);

        assertEquals(List.of("https://admin.example.com", "https://app.example.com"),
                config.getAllowedOrigins());
        assertEquals("https://admin.example.com", config.checkOrigin("https://admin.example.com"));
        assertEquals("https://app.example.com", config.checkOrigin("https://app.example.com"));
        assertNull(config.checkOrigin("https://evil.example.com"));
        assertTrue(config.getAllowedHeaders().contains(HttpHeaders.AUTHORIZATION));
        assertTrue(config.getAllowedHeaders().contains(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void corsOriginPatternsMustBeExplicitlyConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(CalypsoApiConfig.CORS_ALLOWED_ORIGIN_PATTERNS_PROPERTY,
                        "http://localhost:[*], http://192.168.*.*:[*]");

        CorsConfiguration config = CalypsoApiConfig.corsConfiguration(environment);

        assertEquals("http://localhost:8081", config.checkOrigin("http://localhost:8081"));
        assertEquals("http://192.168.68.56:8081", config.checkOrigin("http://192.168.68.56:8081"));
        assertNull(config.checkOrigin("https://admin.example.com"));
    }
}
