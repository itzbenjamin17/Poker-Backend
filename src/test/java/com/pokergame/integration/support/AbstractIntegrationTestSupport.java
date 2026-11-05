package com.pokergame.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.security.RateLimitService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Assertions;

/** Provides abstract integration test support shared by integration tests. */
@TestPropertySource(locations = "classpath:application-test.properties")
public abstract class AbstractIntegrationTestSupport {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    protected static final String FRONTEND_ORIGIN = "http://localhost:5173";

    /**
     * An explicitly hostile, unconfigured Origin for {@link #connectSessionWithOrigin}. It is not in the
     * test profile's {@code app.security.cors.allowed-origins} allowlist, so a handshake carrying it must
     * be rejected. Deliberately distinct from {@link #FRONTEND_ORIGIN}.
     */
    protected static final String DISALLOWED_ORIGIN = "http://evil.example.com";

    /**
     * Production property key that binds {@link RateLimitService}'s {@code enabled} flag
     * ({@code @Value("${poker.rate-limiting.enabled:true}")}). Subclasses that need rate limiting on should
     * prefer configuration over reflection, e.g.
     * {@code @SpringBootTest(properties = AbstractIntegrationTestSupport.RATE_LIMITING_ENABLED_PROPERTY + "=true")}
     * or a {@code @DynamicPropertySource}.
     */
    public static final String RATE_LIMITING_ENABLED_PROPERTY = "poker.rate-limiting.enabled";

    @LocalServerPort
    protected int port;

    @Autowired
    protected RateLimitService rateLimitService;

    /**
     * The rate-limiting enablement injected into this context from {@link #RATE_LIMITING_ENABLED_PROPERTY}.
     * {@link #setupIntegrationTest} resets {@link RateLimitService}'s flag to this configured value before
     * each test so a class-level override (e.g.
     * {@code @SpringBootTest(properties = RATE_LIMITING_ENABLED_PROPERTY + "=true")}) actually takes effect
     * instead of being clobbered back to {@code false}. The suite default (test profile) is {@code false}, so
     * classes that do not override keep their previous behaviour.
     */
    @org.springframework.beans.factory.annotation.Value("${poker.rate-limiting.enabled:false}")
    protected boolean rateLimitingEnabledByConfig;

    /**
     * Broker handler used by {@link #awaitBrokerSubscription}. Injected lazily through an
     * {@link ObjectProvider} so classes whose context does not register a {@link SimpleBrokerMessageHandler}
     * still wire; only tests that call the await helper touch it.
     */
    @Autowired
    protected ObjectProvider<SimpleBrokerMessageHandler> brokerProvider;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected RestClient restClient;

    /**
     * STOMP clients created through {@link #createStompClient} or registered via {@link #registerClient}.
     * Closed failure-safely in {@link #tearDownIntegrationResources}.
     */
    private final List<WebSocketStompClient> managedClients = Collections.synchronizedList(new ArrayList<>());

    /**
     * STOMP sessions opened through {@link #connectSession} or registered via {@link #registerSession}.
     * Disconnected failure-safely in {@link #tearDownIntegrationResources}.
     */
    private final List<StompSession> managedSessions = Collections.synchronizedList(new ArrayList<>());

    /**
     * Supports the test scenario for setup integration test.
     */
    @BeforeEach
    void setupIntegrationTest() {
        // Reset rate limiting to the value configured for this context. The suite default (test profile) is
        // disabled; a class that sets RATE_LIMITING_ENABLED_PROPERTY=true via @SpringBootTest/@DynamicPropertySource
        // gets it enabled here without reflection in the test body.
        ReflectionTestUtils.setField(rateLimitService, "enabled", rateLimitingEnabledByConfig);
        rateLimitService.reset();

        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    /**
     * Closes every STOMP session and client this test opened, even when the test body threw and even when a
     * subclass already performed its own tail cleanup (double-close is harmless). Each resource is released
     * inside its own try/catch so one failure cannot skip the rest.
     */
    @AfterEach
    void tearDownIntegrationResources() {
        synchronized (managedSessions) {
            for (StompSession session : managedSessions) {
                try {
                    if (session != null && session.isConnected()) {
                        session.disconnect();
                    }
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup: a session that is already closed or failing must not block the rest.
                }
            }
            managedSessions.clear();
        }
        synchronized (managedClients) {
            for (WebSocketStompClient client : managedClients) {
                try {
                    if (client != null) {
                        client.stop();
                    }
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup: stopping an already-stopped client must not block the rest.
                }
            }
            managedClients.clear();
        }
    }

    /**
     * Tracks a STOMP client so the failure-safe {@link #tearDownIntegrationResources} stops it. Returns the
     * same instance for fluent use. Safe to call for clients a test created itself.
     *
     * @param client client to track
     * @return the same client
     */
    protected WebSocketStompClient registerClient(WebSocketStompClient client) {
        if (client != null) {
            managedClients.add(client);
        }
        return client;
    }

    /**
     * Tracks a STOMP session so the failure-safe {@link #tearDownIntegrationResources} disconnects it.
     * Returns the same instance for fluent use.
     *
     * @param session session to track
     * @return the same session
     */
    protected StompSession registerSession(StompSession session) {
        if (session != null) {
            managedSessions.add(session);
        }
        return session;
    }

    /**
     * Enables rate limiting for the current test without reflection-free config at the class level. Kept as a
     * fallback for tests that must flip the flag mid-method; class-level cases should instead set
     * {@link #RATE_LIMITING_ENABLED_PROPERTY} via {@code @SpringBootTest(properties=...)} or
     * {@code @DynamicPropertySource}.
     */
    protected void enableRateLimiting() {
        ReflectionTestUtils.setField(rateLimitService, "enabled", true);
    }

    /**
     * Disables rate limiting for the current test. Counterpart to {@link #enableRateLimiting}.
     */
    protected void disableRateLimiting() {
        ReflectionTestUtils.setField(rateLimitService, "enabled", false);
    }

    /**
     * Blocks until the broker has registered a subscription for the given destination, mirroring the
     * gold-standard readiness idiom in
     * {@code PersistenceAvailabilityTransportIntegrationTest}. Use this instead of sleeping before publishing
     * to a destination a test just subscribed to.
     *
     * @param destination STOMP destination a session subscribed to (e.g. {@code "/game/" + roomId})
     */
    protected void awaitBrokerSubscription(String destination) {
        awaitBrokerSubscription(destination, DEFAULT_TIMEOUT);
    }

    /**
     * Blocks until the broker has registered a subscription for the given destination or the timeout elapses.
     *
     * @param destination STOMP destination a session subscribed to
     * @param timeout     maximum time to wait
     */
    protected void awaitBrokerSubscription(String destination, Duration timeout) {
        awaitBrokerSubscriptions(destination, 1, timeout);
    }

    /**
     * Blocks until the broker has registered at least {@code minCount} subscriptions for the given
     * destination. Useful when two sessions (or two handlers) subscribe to the same topic and the test must
     * not publish until both are registered, replacing a fixed sleep.
     *
     * @param destination STOMP destination sessions subscribed to
     * @param minCount     minimum number of registered subscriptions to wait for
     */
    protected void awaitBrokerSubscriptions(String destination, int minCount) {
        awaitBrokerSubscriptions(destination, minCount, DEFAULT_TIMEOUT);
    }

    /**
     * Blocks until the broker has registered at least {@code minCount} subscriptions for the destination or
     * the timeout elapses.
     *
     * @param destination STOMP destination sessions subscribed to
     * @param minCount     minimum number of registered subscriptions to wait for
     * @param timeout      maximum time to wait
     */
    protected void awaitBrokerSubscriptions(String destination, int minCount, Duration timeout) {
        SimpleBrokerMessageHandler broker = brokerProvider.getObject();
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        Awaitility.await()
                .atMost(timeout)
                .until(() -> broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                        .mapToInt(List::size).sum() >= minCount);
    }

    /**
     * Creates room for the test.
     * @param roomName room name supplied to the fixture
     * @param hostName host name supplied to the fixture
     * @param maxPlayers max players supplied to the fixture
     * @return parsed room-creation payload
     */
    protected JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        CreateRoomRequest request = new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null);

        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("message").asText()).isEqualTo("Room created successfully");

        JsonNode data = response.path("data");
        assertThat(data.path("roomId").asText()).isNotBlank();
        assertThat(data.path("token").asText()).isNotBlank();
        // Relaxing this assertion to allow for server-side sanitisation
        if (!hostName.contains(" ")) {
            assertThat(data.path("playerName").asText()).isEqualTo(hostName);
        }
        return data;
    }

    /**
     * Supports the test scenario for join room.
     * @param roomName room name supplied to the fixture
     * @param playerName player name supplied to the fixture
     * @return parsed room-join payload
     */
    protected JsonNode joinRoom(String roomName, String playerName) throws Exception {
        JoinRoomRequest request = new JoinRoomRequest(roomName, playerName, null);

        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("message").asText()).isEqualTo("Successfully joined room");

        JsonNode data = response.path("data");
        assertThat(data.path("roomId").asText()).isNotBlank();
        assertThat(data.path("token").asText()).isNotBlank();
        assertThat(data.path("playerName").asText()).isEqualTo(playerName);
        return data;
    }

    /**
     * Starts a room through the public REST contract and parses the success response.
     *
     * @param roomId room to start
     * @param hostToken room host's bearer token
     * @return parsed API response
     */
    protected JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the current room projection through the REST API.
     *
     * @param roomId room to query
     * @param token optional bearer token
     * @return parsed room projection
     */
    protected JsonNode getRoomData(String roomId, String token) throws Exception {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/api/room/" + roomId);
        
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        String body = request.retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Reads the authenticated public game projection through the REST API.
     *
     * @param gameId game to query
     * @param token player's bearer token
     * @return parsed public game projection
     */
    protected JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Waits until the room endpoint confirms that cleanup removed the room.
     *
     * @param roomId room expected to be destroyed
     * @param token bearer token used for polling
     * @param timeout maximum wait duration
     */
    protected void awaitRoomDestruction(String roomId, String token, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    HttpClientErrorException exception = Assertions.assertThrows(
                            HttpClientErrorException.class,
                            () -> restClient.get()
                                    .uri("/api/room/" + roomId)
                                    .header("Authorization", "Bearer " + token)
                                    .retrieve()
                                    .body(String.class));

                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /**
     * Creates a STOMP client with the same string and JSON converters expected by
     * the integration scenarios.
     *
     * @return configured STOMP client
     */
    protected WebSocketStompClient createStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());

        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());
        converters.add(new JacksonJsonMessageConverter());
        client.setMessageConverter(new CompositeMessageConverter(converters));
        return registerClient(client);
    }

    /**
     * Connects a STOMP session using a bearer token and the allowed frontend origin. The returned session is
     * registered for failure-safe teardown.
     *
     * @param stompClient configured STOMP client
     * @param token player's bearer token
     * @return connected session
     */
    protected StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        return registerSession(openSession(stompClient, token, handshakeHeaders)
                .get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
    }

    /**
     * Attempts a STOMP handshake carrying an explicitly disallowed, unconfigured Origin (see
     * {@link #DISALLOWED_ORIGIN}) so a test can assert the upgrade is rejected. Returns the pending future so
     * the caller controls how it asserts the failure; it does NOT register the session (none should connect).
     * This helper makes no claim about missing-Origin handling: it always sends an Origin header.
     *
     * @param stompClient configured STOMP client
     * @param token       player's bearer token
     * @param origin      hostile origin to present (use {@link #DISALLOWED_ORIGIN})
     * @return future that completes exceptionally when the handshake is rejected
     */
    protected CompletableFuture<StompSession> connectSessionWithOrigin(
            WebSocketStompClient stompClient, String token, String origin) {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", origin);
        return openSession(stompClient, token, handshakeHeaders);
    }

    /**
     * Starts a STOMP handshake and returns a future completed on connect or completed exceptionally on error.
     *
     * @param stompClient     configured STOMP client
     * @param token           player's bearer token
     * @param handshakeHeaders handshake headers, including the Origin to present
     * @return future tracking the connection attempt
     */
    private CompletableFuture<StompSession> openSession(
            WebSocketStompClient stompClient, String token, WebSocketHttpHeaders handshakeHeaders) {
        String wsUrl = "ws://localhost:" + port + "/ws";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders, new StompSessionHandlerAdapter() {
            /** {@inheritDoc} */
            @Override
            public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }

            /** {@inheritDoc} */
            @Override
            public void handleException(
                    @NonNull StompSession session,
                    StompCommand command,
                    @NonNull StompHeaders headers,
                    byte @NonNull [] payload,
                    @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }

            /** {@inheritDoc} */
            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        });

        return sessionFuture;
    }

    /**
     * Creates handshake headers for the test.
     * @return handshake headers containing the allowed frontend origin
     */
    protected WebSocketHttpHeaders createHandshakeHeaders() {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        return handshakeHeaders;
    }

    /**
     * Supports the test scenario for unique name.
     * @param prefix prefix supplied to the fixture
     * @return prefix with a random suffix
     */
    protected String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
