package now.calypso.backendapi;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.session.*;
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.config.*;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.server.*;
import org.springframework.web.server.session.*;

import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableWebFlux
@EnableSpringWebSession
@EnableWebFluxSecurity
public class CalypsoApiConfig implements WebFluxConfigurer {
    public static final String STATIC_FILE_DIR = "./uploads";
    public static final String STATIC_FILE_URL_PATH_NAME = "uploads";
    public static final HashSet<String> IMAGE_EXTS = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "webp"));
    public static final HashSet<String> VIDEO_EXTS = new HashSet<>(Arrays.asList("webm", "mp4", "m4v", "mov"));
    public static final String OAUTH_CLIENT_ID = "cef6f1929499f942a173abd002a69a3a";
    static final String CORS_ALLOWED_ORIGINS_PROPERTY = "calypso.cors.allowed-origins";
    static final String CORS_ALLOWED_ORIGIN_PATTERNS_PROPERTY = "calypso.cors.allowed-origin-patterns";
    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE",
            "OPTIONS");
    private static final List<String> CORS_ALLOWED_HEADERS = List.of(HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT);

    public static class S3Options {
        public String bucketName;
        public String url;
    }

    public static S3Options S3_OPTIONS = null;
    static {
        S3_OPTIONS = new S3Options();
        S3_OPTIONS.bucketName = "yourbucket";
        S3_OPTIONS.url = "https://yourbucket.s3.amazonaws.com";
    }
    public static final int MAX_NAME_LENGTH = 50;

    @Bean
    public ReactiveSessionRepository reactiveSessionRepository() {
        return new ReactiveSessionRepository<MapSession>() {
            private final Map<String, Session> sessions = CalypsoApiConcurrentFixedMap.init(10000);
            private final Set<String> authRefreshesInFlight = ConcurrentHashMap.newKeySet();
            private static final String AUTH_VALIDATED_AT_ATTR = "_calypsoAuthValidatedAt";
            private static final long AUTH_LOOKUP_TIMEOUT_MS = 750L;
            private static final long AUTH_REVALIDATE_INTERVAL_MS = 30_000L;

            @Override
            public Mono<Void> save(MapSession session) {
                return Mono.fromRunnable(() -> {
                    if (!session.getId().equals(session.getOriginalId()))
                        this.sessions.remove(session.getOriginalId());
                    MapSession stored = new MapSession(session);
                    if (stored.getAttribute("accountId") != null
                            && stored.getAttribute(AUTH_VALIDATED_AT_ATTR) == null) {
                        stored.setAttribute(AUTH_VALIDATED_AT_ATTR, System.currentTimeMillis());
                    }
                    this.sessions.put(session.getId(), stored);
                });
            }

            @Override
            public Mono<MapSession> findById(String id) {
                return Mono.defer(() -> {
                    Session cached = this.sessions.get(id);
                    if (cached != null) {
                        this.refreshCachedAuthAsync(id, cached);
                        return Mono.just(new MapSession(cached));
                    }
                    return Mono.fromFuture(
                            CalypsoApiController.manager.getAccountIdFromAuthCode(id)
                                    .orTimeout(AUTH_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                            // if we can't find it, make sure it's removed from memory
                            .switchIfEmpty(Mono.defer(() -> this.deleteById(id).then(Mono.empty())))
                            // if we can't find the session locally, query the backend for the account info
                            // and create the session. this could happen if the user logged in via
                            // a different API server.
                            .flatMap(accountId -> Mono.fromFuture(
                                    CalypsoApiController.manager.getAccountWithId(accountId)
                                            .orTimeout(AUTH_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                                    .flatMap(accountWithId -> this.createSession()
                                            .flatMap(session -> {
                                                session.setId(id);
                                                session.setAttribute("accountId", accountWithId.accountId);
                                                session.setAttribute("accountName",
                                                        accountWithId.account.name);
                                                session.setAttribute(AUTH_VALIDATED_AT_ATTR,
                                                        System.currentTimeMillis());
                                                return this.save(session).then(Mono.just(session));
                                            })))
                            .onErrorResume(ex -> Mono.empty());
                });
            }

            private void refreshCachedAuthAsync(String id, Session cached) {
                Object lastRaw = cached.getAttribute(AUTH_VALIDATED_AT_ATTR);
                long now = System.currentTimeMillis();
                if (lastRaw instanceof Number last
                        && now - last.longValue() < AUTH_REVALIDATE_INTERVAL_MS) {
                    return;
                }
                if (!this.authRefreshesInFlight.add(id)) {
                    return;
                }
                CalypsoApiController.manager.getAccountIdFromAuthCode(id)
                        .orTimeout(AUTH_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .whenComplete((accountId, ex) -> {
                            this.authRefreshesInFlight.remove(id);
                            if (ex != null) {
                                return;
                            }
                            if (accountId == null) {
                                this.sessions.remove(id);
                                return;
                            }
                            Session current = this.sessions.get(id);
                            if (current == null) {
                                return;
                            }
                            Long currentAccountId = current.getAttribute("accountId");
                            if (currentAccountId == null || !currentAccountId.equals(accountId)) {
                                this.sessions.remove(id);
                                return;
                            }
                            MapSession refreshed = new MapSession(current);
                            refreshed.setAttribute(AUTH_VALIDATED_AT_ATTR, System.currentTimeMillis());
                            this.sessions.put(id, refreshed);
                        });
            }

            @Override
            public Mono<Void> deleteById(String id) {
                return Mono.fromRunnable(() -> this.sessions.remove(id));
            }

            @Override
            public Mono<MapSession> createSession() {
                return Mono.defer(() -> {
                    MapSession result = new MapSession();
                    return Mono.just(result);
                });
            }
        };
    }

    @Bean
    public CorsWebFilter corsWebFilter(Environment environment) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration(environment));
        return new CorsWebFilter(source);
    }

    static CorsConfiguration corsConfiguration(Environment environment) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(csvProperty(environment, CORS_ALLOWED_ORIGINS_PROPERTY));
        config.setAllowedOriginPatterns(csvProperty(environment, CORS_ALLOWED_ORIGIN_PATTERNS_PROPERTY));
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        config.setExposedHeaders(List.of(HttpHeaders.AUTHORIZATION));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        return config;
    }

    private static List<String> csvProperty(Environment environment, String propertyName) {
        String configured = environment.getProperty(propertyName);
        if (configured == null || configured.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(CalypsoApiConfig::trimTrailingSlashes)
                .distinct()
                .toList();
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Bean
    public WebSessionIdResolver headerWebSessionIdResolver() {
        final String AUTH_HEADER = "Authorization";
        final String AUTH_HEADER_PREFIX = "Bearer";
        final String SEC_WEBSOCKET_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";

        return new WebSessionIdResolver() {
            @Override
            public List<String> resolveSessionIds(ServerWebExchange exchange) {
                HttpHeaders headers = exchange.getRequest().getHeaders();
                // authentication header
                for (String header : headers.getOrDefault(AUTH_HEADER, Collections.emptyList())) {
                    String[] parts = header.split("\\s+");
                    if (parts.length == 2 && AUTH_HEADER_PREFIX.equals(parts[0]))
                        return Arrays.asList(parts[1]);
                    else
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Authorization header");
                }
                // web socket connections send the session id via a different header
                for (String header : headers.getOrDefault(SEC_WEBSOCKET_PROTOCOL_HEADER, Collections.emptyList())) {
                    return Arrays.asList(header);
                }
                return new ArrayList<>();
            }

            @Override
            public void setSessionId(ServerWebExchange exchange, String sessionId) {
                exchange.getResponse().getHeaders().set(AUTH_HEADER, AUTH_HEADER_PREFIX + " " + sessionId);
            }

            @Override
            public void expireSession(ServerWebExchange exchange) {
                this.setSessionId(exchange, "");
            }
        };
    }

    @Bean
    public SecurityWebFilterChain springWebFilterChain(ServerHttpSecurity http) {
        return http.httpBasic().disable()
                .formLogin().disable()
                .csrf().disable()
                .authorizeExchange()
                .pathMatchers("/**")
                .permitAll()
                .and()
                .build();
    }

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        DefaultPartHttpMessageReader partReader = new DefaultPartHttpMessageReader();
        partReader.setMaxParts(40); // the update_credentials endpoint sends all its params as parts
        partReader.setMaxDiskUsagePerPart(4 * 1024 * 1024);
        partReader.setEnableLoggingRequestDetails(true);
        MultipartHttpMessageReader multipartReader = new MultipartHttpMessageReader(partReader);
        multipartReader.setEnableLoggingRequestDetails(true);
        configurer.defaultCodecs().multipartReader(multipartReader);
        configurer.defaultCodecs().maxInMemorySize(512 * 1024);
    }

    @Bean
    public RouterFunction staticResourceLocator() {
        return RouterFunctions.resources(String.format("/%s/**", STATIC_FILE_URL_PATH_NAME),
                new FileSystemResource(STATIC_FILE_DIR + "/"));
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/public/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS));
    }

}
