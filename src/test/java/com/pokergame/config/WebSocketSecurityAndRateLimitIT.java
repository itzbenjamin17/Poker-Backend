package com.pokergame.config;

import com.pokergame.persistence.RoomAggregates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.security.JwtService;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.util.RoomCoordinator;
import com.pokergame.service.RoomService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests covering WebSocket/STOMP origin checks, subscription and destination isolation,
 * membership boundary enforcement, identity isolation across rooms, and per-session rate throttling.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.rate-limiting.enabled=true",
                "poker.game.ready-countdown-ms=30000"
        })
@ActiveProfiles("test")
@DisplayName("WebSocket security, identity isolation, and rate-limit integration")
class WebSocketSecurityAndRateLimitIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "http://malicious-site.com";
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private UserDestinationResolver userDestinationResolver;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private AbstractMessageChannel outboundChannel;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private GameStateService gameStateService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private final List<StompSession> trackedSessions = new CopyOnWriteArrayList<>();
    private final List<WebSocketStompClient> trackedClients = new CopyOnWriteArrayList<>();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : trackedSessions) {
            if (session != null && session.isConnected()) {
                try {
                    session.disconnect();
                } catch (RuntimeException ignored) {
                    // Best-effort disconnect on teardown.
                }
            }
        }
        trackedSessions.clear();
        for (WebSocketStompClient client : trackedClients) {
            if (client != null) {
                try {
                    client.stop();
                } catch (RuntimeException ignored) {
                    // Best-effort stop on teardown.
                }
            }
        }
        trackedClients.clear();
        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomCoordinator, roomId, () -> {
                roomService.destroyRoom(roomId);
                roomAggregates.remove(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
        rateLimitService.reset();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    private JsonNode createRoom(String roomName, String hostName, int maxPlayers) throws Exception {
        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null))
                .retrieve()
                .body(String.class);
        JsonNode data = objectMapper.readTree(body).path("data");
        String roomId = data.path("roomId").asText();
        if (!roomId.isBlank()) {
            trackedRoomIds.add(roomId);
        }
        return data;
    }

    private JsonNode joinRoom(String roomName, String playerName) throws Exception {
        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinRoomRequest(roomName, playerName, null))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body).path("data");
    }

    private JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private WebSocketStompClient createStompClient() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        trackedClients.add(stompClient);
        return stompClient;
    }

    private StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        return connectSessionWithOrigin(stompClient, token, ALLOWED_ORIGIN).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }

    private CompletableFuture<StompSession> connectSessionWithOrigin(
            WebSocketStompClient stompClient, String token, String origin) {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", origin);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> future = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {});
        future.thenAccept(trackedSessions::add);
        return future;
    }

    private StompSession connectSessionWithErrorHandler(
            WebSocketStompClient stompClient, String token, AtomicBoolean errorReceived) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", ALLOWED_ORIGIN);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(
                            @NonNull StompSession session,
                            StompCommand command,
                            @NonNull StompHeaders headers,
                            byte @NonNull [] payload,
                            @NonNull Throwable exception) {
                        errorReceived.set(true);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        errorReceived.set(true);
                    }
                }).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        trackedSessions.add(session);
        return session;
    }

    private void awaitBrokerSubscription(String destination) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        Awaitility.await().atMost(TIMEOUT)
                .until(() -> !broker.getSubscriptionRegistry().findSubscriptions(probe).isEmpty());
    }

    private static JsonNode playerState(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asText())) {
                return player;
            }
        }
        throw new AssertionError("Missing player in public projection: " + playerName);
    }

    private record PrivateProjection(String playerId, long handNumber, List<String> holeCards) {}

    @Nested
    @DisplayName("Subscription and origin security")
    class SubscriptionAndOriginSecurity {

        @Test
        @DisplayName("subscribing to another player's private topic closes the STOMP session")
        void forbiddenPrivateTopicSubscriptionClosesSession() throws Exception {
            String roomName = uniqueName("WSSecurityRoom");
            JsonNode player1 = createRoom(roomName, "Player1", 6);
            joinRoom(roomName, "Player2");

            WebSocketStompClient stompClient = createStompClient();
            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

            String forbiddenTopic = "/game/" + player1.path("roomId").asText() + "/player-name/Player2/private";
            session1.subscribe(forbiddenTopic, new StompFrameHandler() {
                @Override
                public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                }
            });

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
        }

        @Test
        @DisplayName("each live private subscription receives only its own authenticated player projection")
        void livePrivateSubscriptionsReceiveOnlyAuthenticatedProjection() throws Exception {
            String roomName = uniqueName("WSPrivateIsolation");
            JsonNode host = createRoom(roomName, "Player1", 2);
            JsonNode guest = joinRoom(roomName, "Player2");
            String roomId = host.path("roomId").asText();
            String hostToken = host.path("token").asText();
            String guestToken = guest.path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);
            assertThat(startGame(roomId, hostToken).path("message").asText()).isEqualTo("Game started successfully");

            PrivateProjection hostProjection = readPrivateProjection(roomId, hostToken);
            PrivateProjection guestProjection = readPrivateProjection(roomId, guestToken);
            JsonNode table = readGameState(roomId, hostToken);
            assertThat(hostProjection.playerId()).isEqualTo(playerState(table, "Player1").path("id").asText());
            assertThat(guestProjection.playerId()).isEqualTo(playerState(table, "Player2").path("id").asText());
            assertThat(hostProjection.holeCards()).hasSize(2).doesNotContainAnyElementsOf(guestProjection.holeCards());
            assertThat(guestProjection.holeCards()).hasSize(2);

            BlockingQueue<PrivateProjection> hostFrames = new LinkedBlockingQueue<>();
            BlockingQueue<PrivateProjection> guestFrames = new LinkedBlockingQueue<>();
            String hostBrokerSession = subscribePrivate(hostSession, hostToken, hostFrames);
            String guestBrokerSession = subscribePrivate(guestSession, guestToken, guestFrames);
            Set<String> sessionIds = Set.of(hostBrokerSession, guestBrokerSession);

            var dispatches = new ConcurrentLinkedQueue<Message<?>>();
            ChannelInterceptor capture = new ChannelInterceptor() {
                @Override
                public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                    String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
                    if (sessionId != null && sessionIds.contains(sessionId)
                            && SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE) {
                        dispatches.add(message);
                    }
                    return message;
                }
            };
            outboundChannel.addInterceptor(capture);
            try {
                withRoomLock(roomCoordinator, roomId, () -> {
                    gameStateService.broadcastGameState(gameLifecycleService.getGame(roomId));
                    return null;
                });

                assertThat(hostFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(hostProjection);
                assertThat(guestFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(guestProjection);
                assertPrivateDispatches(dispatches, hostBrokerSession, hostProjection);
                assertPrivateDispatches(dispatches, guestBrokerSession, guestProjection);
            } finally {
                outboundChannel.removeInterceptor(capture);
            }
        }

        @Test
        @DisplayName("live handshake from disallowed Origin fails upgrade")
        void disallowedOriginHandshakeRejected() throws Exception {
            JsonNode player1 = createRoom(uniqueName("WSOriginReject"), "Player1", 6);
            WebSocketStompClient stompClient = createStompClient();
            CompletableFuture<StompSession> attempt =
                    connectSessionWithOrigin(stompClient, player1.path("token").asText(), DISALLOWED_ORIGIN);

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> attempt.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            assertThat(failure.getCause()).isNotNull();
        }

        private PrivateProjection readPrivateProjection(String roomId, String token) throws Exception {
            String body = restClient.get()
                    .uri("/api/game/" + roomId + "/private-state")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readValue(body, PrivateProjection.class);
        }

        private String subscribePrivate(StompSession session, String token, BlockingQueue<PrivateProjection> frames) {
            String identity = jwtService.extractPrincipal(token).getName();
            SimpUser user = Awaitility.await().atMost(TIMEOUT).until(
                    () -> userRegistry.getUser(identity), registered -> registered != null && registered.hasSessions());
            assertThat(user.getSessions()).hasSize(1);
            String sessionId = user.getSessions().iterator().next().getId();
            String destination = "/user/queue/private";

            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                    return PrivateProjection.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    frames.add((PrivateProjection) payload);
                }
            });

            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
            headers.setSessionId(sessionId);
            headers.setDestination(destination);
            var subscription = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
            var resolved = userDestinationResolver.resolveDestination(subscription);
            assertThat(resolved.getTargetDestinations()).hasSize(1);
            awaitBrokerSubscription(resolved.getTargetDestinations().iterator().next());
            return sessionId;
        }

        private void assertPrivateDispatches(
                Collection<Message<?>> dispatches, String sessionId, PrivateProjection expected) throws Exception {
            List<Message<?>> forSession = dispatches.stream()
                    .filter(frame -> sessionId.equals(SimpMessageHeaderAccessor.getSessionId(frame.getHeaders())))
                    .toList();
            assertThat(forSession).hasSize(1);
            byte[] payload = (byte[]) forSession.getFirst().getPayload();
            assertThat(objectMapper.readValue(payload, PrivateProjection.class)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Destination and cross-room STOMP guards")
    class DestinationAndCrossRoomIsolation {

        @Test
        @DisplayName("direct SEND to /game/{roomId} closes sender session and never delivers to subscribers")
        void directSendToGameBrokerRejectedAndIsolated() throws Exception {
            String roomName = uniqueName("WSSpoofIsolation");
            JsonNode player1 = createRoom(roomName, "Player1", 6);
            String roomId = player1.path("roomId").asText();
            JsonNode player2 = joinRoom(roomName, "Player2");

            WebSocketStompClient stompClient = createStompClient();
            StompSession session2 = connectSession(stompClient, player2.path("token").asText());
            BlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
            session2.subscribe("/game/" + roomId, new StompFrameHandler() {
                @Override
                public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    receivedFrames.add(String.valueOf(payload));
                }
            });
            awaitBrokerSubscription("/game/" + roomId);

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);
            session1.send("/game/" + roomId, "{\"spoofed\":\"event\"}");

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
            assertThat(receivedFrames.poll(500, TimeUnit.MILLISECONDS)).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/room/{roomId}",
                "/queue/private",
                "/app/"
        })
        @DisplayName("direct SEND to broker destinations or blank /app/ destination closes the session")
        void directBrokerAndBlankAppDestinationsCloseSession(String destinationTemplate) throws Exception {
            JsonNode player1 = createRoom(uniqueName("WSDestBlock"), "Player1", 6);
            String roomId = player1.path("roomId").asText();
            String destination = destinationTemplate.replace("{roomId}", roomId);

            WebSocketStompClient stompClient = createStompClient();
            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

            session1.send(destination, "{\"spoofed\":\"payload\"}");

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/app/{otherRoomId}/action",
                "/app/{otherRoomId}/ready",
                "/app/{otherRoomId}/unready",
                "/app/game/{otherRoomId}/ready",
                "/app/room/{otherRoomId}/action"
        })
        @DisplayName("cross-room STOMP SENDs close the sender session")
        void crossRoomAppSendsCloseSession(String destinationTemplate) throws Exception {
            JsonNode player1 = createRoom(uniqueName("WSCrossOne"), "Player1", 6);
            JsonNode player2 = createRoom(uniqueName("WSCrossTwo"), "Player2", 6);
            String otherRoomId = player2.path("roomId").asText();
            String destination = destinationTemplate.replace("{otherRoomId}", otherRoomId);

            WebSocketStompClient stompClient = createStompClient();
            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

            if (destination.endsWith("/action") && !destination.contains("/room/")) {
                session1.send(destination, new PlayerActionRequest(PlayerAction.FOLD, 0));
            } else {
                session1.send(destination, "{}");
            }

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
        }

        @Test
        @DisplayName("ready and unready STOMP commands update only the acting player's state")
        void readyAndUnreadyCommandsChangeOnlyActingPlayer() throws Exception {
            String roomName = uniqueName("WSAuthorizedSend");
            JsonNode host = createRoom(roomName, "Player1", 2);
            JsonNode guest = joinRoom(roomName, "Player2");
            String roomId = host.path("roomId").asText();
            String hostToken = host.path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guest.path("token").asText());
            startGame(roomId, hostToken);

            String actor = readGameState(roomId, hostToken).path("currentPlayerName").asText();
            StompSession actingSession = "Player1".equals(actor) ? hostSession : guestSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            JsonNode opened = Awaitility.await().atMost(TIMEOUT).until(
                    () -> readGameState(roomId, hostToken),
                    state -> "COUNTING_DOWN".equals(state.path("readyGateState").asText()));
            long handNumber = opened.path("handNumber").asLong();
            assertThat(handNumber).isPositive();
            assertThat(playerState(opened, "Player1").path("isReadyForNextHand").asText()).isEqualTo("false");
            assertThat(playerState(opened, "Player2").path("isReadyForNextHand").asText()).isEqualTo("false");

            hostSession.send("/app/" + roomId + "/ready", "");
            long readyRevision = awaitReadiness(roomId, hostToken, handNumber, opened.path("stateRevision").asLong(), true);
            hostSession.send("/app/" + roomId + "/unready", "");
            awaitReadiness(roomId, hostToken, handNumber, readyRevision, false);
            assertThat(hostSession.isConnected()).isTrue();
        }

        private long awaitReadiness(String roomId, String token, long handNumber, long previousRevision, boolean ready) {
            JsonNode updated = Awaitility.await().atMost(TIMEOUT).until(
                    () -> readGameState(roomId, token),
                    state -> state.path("stateRevision").asLong() > previousRevision
                            && state.path("handNumber").asLong() == handNumber
                            && "COUNTING_DOWN".equals(state.path("readyGateState").asText())
                            && Boolean.toString(ready).equals(playerState(state, "Player1").path("isReadyForNextHand").asText()));
            assertThat(playerState(updated, "Player2").path("isReadyForNextHand").asText()).isEqualTo("false");
            return updated.path("stateRevision").asLong();
        }
    }

    @Nested
    @DisplayName("Membership and identity isolation boundaries")
    class MembershipAndIdentityIsolation {

        @Test
        @DisplayName("stale-membership token is rejected on STOMP CONNECT while active token advances the game")
        void staleMembershipTokenRejectedOnConnectWhileActiveActs() throws Exception {
            String roomName = uniqueName("MembershipRoom");
            JsonNode hostData = createRoom(roomName, "HostMember", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "GuestMember").path("token").asText();
            startGame(gameId, hostToken);

            JsonNode beforeState = readGameState(gameId, hostToken);
            String currentPlayer = beforeState.path("currentPlayerName").asText();
            String validActingToken = jwtService.extractPlayerName(hostToken).equals(currentPlayer) ? hostToken : guestToken;
            String staleToken = jwtService.generateToken(currentPlayer, gameId, "forged-stale-membership");

            WebSocketStompClient stompClient = createStompClient();
            assertThatThrownBy(() -> connectSession(stompClient, staleToken))
                    .as("stale-membership CONNECT must be rejected by the membership boundary")
                    .isInstanceOf(Exception.class);

            JsonNode afterStale = readGameState(gameId, hostToken);
            assertThat(afterStale.path("phase").asText()).isEqualTo("PRE_FLOP");
            assertThat(afterStale.path("currentPlayerName").asText()).isEqualTo(currentPlayer);

            StompSession validSession = connectSession(stompClient, validActingToken);
            validSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, 0));

            Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                JsonNode next = readGameState(gameId, hostToken);
                boolean advanced = !currentPlayer.equals(next.path("currentPlayerName").asText())
                        || !"PRE_FLOP".equals(next.path("phase").asText());
                assertThat(advanced).isTrue();
            });
        }

        @Test
        @DisplayName("same player name in room A cannot access room B")
        void playerInRoomACannotAccessRoomB() throws Exception {
            JsonNode aliceAData = createRoom(uniqueName("RoomA"), "Alice", 6);
            JsonNode aliceBData = createRoom(uniqueName("RoomB"), "Alice", 6);
            String roomIdB = aliceBData.path("roomId").asText();
            String tokenA = aliceAData.path("token").asText();

            HttpClientErrorException forbidden = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomIdB + "/leave")
                    .header("Authorization", "Bearer " + tokenA)
                    .retrieve()
                    .toBodilessEntity());

            assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
            assertThat(forbidden.getResponseBodyAsString()).contains("Token is not valid for this room");
        }

        @Test
        @DisplayName("leaving and rejoining retires prior token on both room and game endpoints with 401")
        void leaveAndRejoinRetiresStaleTokenAcrossRoomAndGameEndpoints() throws Exception {
            String roomName = uniqueName("StaleSessionRoom");
            JsonNode hostData = createRoom(roomName, "HostPlayer", 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String staleToken = joinRoom(roomName, "Alice").path("token").asText();

            restClient.post()
                    .uri("/api/room/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + staleToken)
                    .retrieve()
                    .toBodilessEntity();

            String activeToken = joinRoom(roomName, "Alice").path("token").asText();
            assertThat(activeToken).isNotEqualTo(staleToken);

            HttpClientErrorException roomRejected = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + staleToken)
                    .retrieve()
                    .toBodilessEntity());
            assertThat(roomRejected.getStatusCode().value()).isEqualTo(401);
            assertThat(roomRejected.getResponseBodyAsString()).contains("Unauthorized");

            assertThat(restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + activeToken)
                    .retrieve()
                    .body(String.class)).contains(roomId);

            startGame(roomId, hostToken);

            HttpClientErrorException gameRejected = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/" + roomId + "/state")
                    .header("Authorization", "Bearer " + staleToken)
                    .retrieve()
                    .toBodilessEntity());
            assertThat(gameRejected.getStatusCode().value()).isEqualTo(401);
            assertThat(gameRejected.getResponseBodyAsString()).contains("Unauthorized");

            assertThat(restClient.get()
                    .uri("/api/game/" + roomId + "/state")
                    .header("Authorization", "Bearer " + activeToken)
                    .retrieve()
                    .body(String.class)).contains("Alice");
        }
    }

    @Nested
    @DisplayName("WebSocket rate limiting and per-room bucket isolation")
    class WebSocketRateLimiting {

        @Test
        @DisplayName("exceeding STOMP message rate limit closes the offending connection")
        void highMessageRateClosesSession() throws Exception {
            JsonNode host = createRoom(uniqueName("ThrottledRoom"), "ThrottledPlayer", 6);
            String roomId = host.path("roomId").asText();
            String token = host.path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session = connectSessionWithErrorHandler(stompClient, token, errorReceived);

            for (int i = 0; i < 15; i++) {
                try {
                    session.send("/app/" + roomId + "/ready", "");
                } catch (Exception e) {
                    break;
                }
            }

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session.isConnected()).isFalse());
        }

        @Test
        @DisplayName("same player name in different rooms has isolated WebSocket rate-limit buckets")
        void samePlayerNameInDifferentRoomsIsolatedWsRateLimits() throws Exception {
            JsonNode alice1Data = createRoom(uniqueName("RateRoom1"), "Alice", 6);
            JsonNode alice2Data = createRoom(uniqueName("RateRoom2"), "Alice", 6);
            String roomId1 = alice1Data.path("roomId").asText();
            String roomId2 = alice2Data.path("roomId").asText();
            String token1 = alice1Data.path("token").asText();
            String token2 = alice2Data.path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession session1 = connectSession(stompClient, token1);
            StompSession session2 = connectSession(stompClient, token2);

            CompletableFuture<ActionErrorResponse> session2Reply = new CompletableFuture<>();
            session2.subscribe("/user/queue/private", new StompFrameHandler() {
                @Override
                public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                    return ActionErrorResponse.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    if (payload instanceof ActionErrorResponse reply && !session2Reply.isDone()) {
                        session2Reply.complete(reply);
                    }
                }
            });

            for (int i = 0; i < 6; i++) {
                session1.send("/app/" + roomId1 + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));
            }

            Awaitility.await().atMost(TIMEOUT)
                    .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());

            session2.send("/app/" + roomId2 + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));
            ActionErrorResponse reply = session2Reply.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(reply).isNotNull();
            assertThat(session2.isConnected()).isTrue();
        }
    }
}
