package now.calypso.backendapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.ReactiveSessionRepository;

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
}
