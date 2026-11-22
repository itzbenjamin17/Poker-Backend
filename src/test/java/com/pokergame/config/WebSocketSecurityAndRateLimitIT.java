package com.pokergame.config;

import static com.pokergame.testutil.TestRoomLocks.runWithRoomLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerFeedbackResponse;
import com.pokergame.dto.response.TokenResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.security.JwtService;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestStompClientSupport;
import com.pokergame.util.RoomCoordinator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests covering WebSocket/STOMP origin checks, subscription and destination isolation,
 * membership boundary enforcement, and per-session rate throttling.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poker.rate-limiting.enabled=true")
@ActiveProfiles("test")
@DisplayName("WebSocket security, identity isolation, and rate-limit integration")
class WebSocketSecurityAndRateLimitIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "http://malicious-site.com";
    private static final String PRIVATE_QUEUE = "/user/queue/private";
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
    private SimpMessagingTemplate messagingTemplate;

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

    @Autowired
    private ObjectMapper objectMapper;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private TestStompClientSupport stomp;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
        stomp = new TestStompClientSupport(port, userRegistry, userDestinationResolver, broker);
    }

    @AfterEach
    void tearDown() {
        stomp.closeAllAndDestroyRooms(roomCoordinator, roomService, roomAggregates, trackedRoomIds);
        rateLimitService.reset();
    }

    @Nested
    @DisplayName("Subscription and origin security")
    class SubscriptionAndOriginSecurity {

        @Test
        @DisplayName("subscribing to another player's private topic closes the STOMP session")
        void forbiddenPrivateTopicSubscriptionClosesSession() throws Exception {
            String roomName = uniqueName("WSSecurityRoom");
            TokenResponse player1 = createRoom(roomName, "Player1", 6);
            joinRoom(roomName, "Player2");

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(
                    stomp.createJsonClient(), player1.token(), errorReceived);

            String forbiddenTopic = "/game/" + player1.roomId() + "/player-name/Player2/private";
            stomp.subscribe(session1, forbiddenTopic, String.class, frame -> { });

            awaitSessionClosedWithError(session1, errorReceived);
            assertThat(stomp.hasBrokerSubscription(forbiddenTopic)).isFalse();
        }

        @Test
        @DisplayName("each live private subscription receives only its own authenticated player projection")
        void livePrivateSubscriptionsReceiveOnlyAuthenticatedProjection() throws Exception {
            String roomName = uniqueName("WSPrivateIsolation");
            TokenResponse host = createRoom(roomName, "Player1", 2);
            TokenResponse guest = joinRoom(roomName, "Player2");
            String roomId = host.roomId();

            WebSocketStompClient stompClient = stomp.createJsonClient();
            StompSession hostSession = stomp.connectSession(stompClient, host.token());
            StompSession guestSession = stomp.connectSession(stompClient, guest.token());
            assertThat(startGame(roomId, host.token())).isEqualTo(204);

            PrivateProjection hostProjection = readPrivateProjection(roomId, host.token());
            PrivateProjection guestProjection = readPrivateProjection(roomId, guest.token());
            JsonNode table = readGameState(roomId, host.token());
            assertThat(hostProjection.playerId()).isEqualTo(playerState(table, "Player1").path("id").asString());
            assertThat(guestProjection.playerId()).isEqualTo(playerState(table, "Player2").path("id").asString());
            assertThat(hostProjection.holeCards()).hasSize(2).doesNotContainAnyElementsOf(guestProjection.holeCards());
            assertThat(guestProjection.holeCards()).hasSize(2);

            BlockingQueue<PrivateProjection> hostFrames = new LinkedBlockingQueue<>();
            BlockingQueue<PrivateProjection> guestFrames = new LinkedBlockingQueue<>();
            String hostBrokerSession = subscribePrivate(hostSession, host.token(), hostFrames);
            String guestBrokerSession = subscribePrivate(guestSession, guest.token(), guestFrames);
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
                runWithRoomLock(roomCoordinator, roomId,
                        () -> gameStateService.broadcastGameState(gameLifecycleService.getGame(roomId)));

                assertThat(hostFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(hostProjection);
                assertThat(guestFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(guestProjection);
                assertPrivateDispatches(dispatches, hostBrokerSession, hostProjection);
                assertPrivateDispatches(dispatches, guestBrokerSession, guestProjection);
            } finally {
                outboundChannel.removeInterceptor(capture);
            }
        }

        @Test
        @DisplayName("WebSocket upgrade from the allowed Origin is accepted")
        void allowedOriginHandshakeIsUpgraded() throws IOException {
            assertThat(handshakeStatus(ALLOWED_ORIGIN)).isEqualTo(101);
        }

        @Test
        @DisplayName("WebSocket upgrade from a disallowed Origin is refused with 403")
        void disallowedOriginHandshakeRejected() throws IOException {
            assertThat(handshakeStatus(DISALLOWED_ORIGIN)).isEqualTo(403);
        }

        /** Sends a raw WebSocket upgrade request with {@code origin} and returns the HTTP status of the reply. */
        private int handshakeStatus(String origin) throws IOException {
            try (Socket socket = new Socket("localhost", port)) {
                socket.setSoTimeout((int) TIMEOUT.toMillis());
                String request = "GET /ws HTTP/1.1\r\n"
                        + "Host: localhost:" + port + "\r\n"
                        + "Origin: " + origin + "\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String statusLine = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                return Integer.parseInt(statusLine.split(" ")[1]);
            }
        }

        private PrivateProjection readPrivateProjection(String roomId, String token) {
            String body = restClient.get()
                    .uri("/api/game/" + roomId + "/private-state")
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readValue(body, PrivateProjection.class);
        }

        private String subscribePrivate(StompSession session, String token, BlockingQueue<PrivateProjection> frames) {
            stomp.subscribe(session, PRIVATE_QUEUE, PrivateProjection.class, frames::add);
            return stomp.awaitUserBrokerSubscription(jwtService.extractPrincipal(token).getName(), PRIVATE_QUEUE);
        }

        private void assertPrivateDispatches(
                Collection<Message<?>> dispatches, String sessionId, PrivateProjection expected) {
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
            TokenResponse player1 = createRoom(roomName, "Player1", 6);
            String roomId = player1.roomId();
            TokenResponse player2 = joinRoom(roomName, "Player2");

            WebSocketStompClient stompClient = stomp.createJsonClient();
            StompSession session2 = stomp.connectSession(stompClient, player2.token());
            BlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
            stomp.subscribe(session2, "/game/" + roomId, String.class, receivedFrames::add);
            stomp.awaitBrokerSubscription("/game/" + roomId);

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(stompClient, player1.token(), errorReceived);
            session1.send("/game/" + roomId, "{\"spoofed\":\"event\"}");

            awaitSessionClosedWithError(session1, errorReceived);

            // A genuine server broadcast reaches the subscriber only after the rejected SEND was processed, so
            // seeing it first proves the spoofed frame was never fanned out.
            messagingTemplate.convertAndSend("/game/" + roomId, "sentinel");
            await().atMost(TIMEOUT).until(() -> receivedFrames.contains("sentinel"));
            assertThat(receivedFrames).containsExactly("sentinel");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/room/{roomId}",
                "/queue/private",
                "/app/"
        })
        @DisplayName("direct SEND to broker destinations or blank /app/ destination closes the session")
        void directBrokerAndBlankAppDestinationsCloseSession(String destinationTemplate) throws Exception {
            TokenResponse player1 = createRoom(uniqueName("WSDestBlock"), "Player1", 6);
            String destination = destinationTemplate.replace("{roomId}", player1.roomId());

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(
                    stomp.createJsonClient(), player1.token(), errorReceived);

            session1.send(destination, "{\"spoofed\":\"payload\"}");

            awaitSessionClosedWithError(session1, errorReceived);
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
            TokenResponse player1 = createRoom(uniqueName("WSCrossOne"), "Player1", 6);
            TokenResponse player2 = createRoom(uniqueName("WSCrossTwo"), "Player2", 6);
            String destination = destinationTemplate.replace("{otherRoomId}", player2.roomId());

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(
                    stomp.createJsonClient(), player1.token(), errorReceived);

            session1.send(destination, new PlayerActionRequest(PlayerAction.FOLD, 0));

            awaitSessionClosedWithError(session1, errorReceived);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/room/{otherRoomId}", "/game/{otherRoomId}"})
        @DisplayName("cross-room STOMP SUBSCRIBEs close the session and never register with the broker")
        void crossRoomSubscribesCloseSession(String destinationTemplate) throws Exception {
            TokenResponse player1 = createRoom(uniqueName("WSSubOne"), "Player1", 6);
            TokenResponse player2 = createRoom(uniqueName("WSSubTwo"), "Player2", 6);
            String destination = destinationTemplate.replace("{otherRoomId}", player2.roomId());

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(
                    stomp.createJsonClient(), player1.token(), errorReceived);

            stomp.subscribe(session1, destination, String.class, frame -> { });

            awaitSessionClosedWithError(session1, errorReceived);
            assertThat(stomp.hasBrokerSubscription(destination)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/room/{roomId}", "/game/{roomId}"})
        @DisplayName("subscribing to the player's own room and game topics is accepted and stays connected")
        void ownRoomSubscribesAreAccepted(String destinationTemplate) throws Exception {
            TokenResponse player1 = createRoom(uniqueName("WSSubOwn"), "Player1", 6);
            String destination = destinationTemplate.replace("{roomId}", player1.roomId());

            StompSession session1 = stomp.connectSession(stomp.createJsonClient(), player1.token());
            stomp.subscribe(session1, destination, String.class, frame -> { });

            stomp.awaitBrokerSubscription(destination);
            assertThat(session1.isConnected()).isTrue();
        }

        @Test
        @DisplayName("ready and unready STOMP commands update only the acting player's state")
        void readyAndUnreadyCommandsChangeOnlyActingPlayer() throws Exception {
            String roomName = uniqueName("WSAuthorizedSend");
            TokenResponse host = createRoom(roomName, "Player1", 2);
            TokenResponse guest = joinRoom(roomName, "Player2");
            String roomId = host.roomId();

            WebSocketStompClient stompClient = stomp.createJsonClient();
            StompSession hostSession = stomp.connectSession(stompClient, host.token());
            StompSession guestSession = stomp.connectSession(stompClient, guest.token());
            assertThat(startGame(roomId, host.token())).isEqualTo(204);

            String actor = readGameState(roomId, host.token()).path("currentPlayerName").asString();
            StompSession actingSession = "Player1".equals(actor) ? hostSession : guestSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            JsonNode opened = await().atMost(TIMEOUT).until(
                    () -> readGameState(roomId, host.token()),
                    state -> "COUNTING_DOWN".equals(state.path("readyGateState").asString()));
            long handNumber = opened.path("handNumber").asLong();
            assertThat(handNumber).isPositive();
            assertThat(readyFlag(opened, "Player1")).isFalse();
            assertThat(readyFlag(opened, "Player2")).isFalse();

            hostSession.send("/app/" + roomId + "/ready", "");
            long readyRevision = awaitReadiness(roomId, host.token(), handNumber, opened.path("stateRevision").asLong(), true);
            hostSession.send("/app/" + roomId + "/unready", "");
            awaitReadiness(roomId, host.token(), handNumber, readyRevision, false);
            assertThat(hostSession.isConnected()).isTrue();
            assertThat(guestSession.isConnected()).isTrue();
        }

        private long awaitReadiness(String roomId, String token, long handNumber, long previousRevision, boolean ready) {
            JsonNode updated = await().atMost(TIMEOUT).until(
                    () -> readGameState(roomId, token),
                    state -> state.path("stateRevision").asLong() > previousRevision
                            && state.path("handNumber").asLong() == handNumber
                            && "COUNTING_DOWN".equals(state.path("readyGateState").asString())
                            && ready == readyFlag(state, "Player1"));
            assertThat(readyFlag(updated, "Player2")).isFalse();
            return updated.path("stateRevision").asLong();
        }
    }

    @Nested
    @DisplayName("Membership boundaries")
    class MembershipBoundaries {

        @Test
        @DisplayName("stale-membership token is rejected on STOMP CONNECT while active token advances the game")
        void staleMembershipTokenRejectedOnConnectWhileActiveActs() throws Exception {
            String roomName = uniqueName("MembershipRoom");
            TokenResponse host = createRoom(roomName, "HostMember", 6);
            String gameId = host.roomId();
            String guestToken = joinRoom(roomName, "GuestMember").token();
            startGame(gameId, host.token());

            JsonNode beforeState = readGameState(gameId, host.token());
            String currentPlayer = beforeState.path("currentPlayerName").asString();
            String validActingToken = jwtService.extractPlayerName(host.token()).equals(currentPlayer)
                    ? host.token()
                    : guestToken;
            String staleToken = jwtService.generateToken(currentPlayer, gameId, "forged-stale-membership");

            WebSocketStompClient stompClient = stomp.createJsonClient();
            assertThatThrownBy(() -> stomp.connectSession(stompClient, staleToken))
                    .as("stale-membership CONNECT must be rejected by the membership boundary")
                    .isInstanceOf(ExecutionException.class);

            JsonNode afterStale = readGameState(gameId, host.token());
            assertThat(afterStale.path("phase").asString()).isEqualTo("PRE_FLOP");
            assertThat(afterStale.path("currentPlayerName").asString()).isEqualTo(currentPlayer);

            StompSession validSession = stomp.connectSession(stompClient, validActingToken);
            validSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, 0));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                JsonNode next = readGameState(gameId, host.token());
                boolean advanced = !currentPlayer.equals(next.path("currentPlayerName").asString())
                        || !"PRE_FLOP".equals(next.path("phase").asString());
                assertThat(advanced).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("WebSocket rate limiting and per-room bucket isolation")
    class WebSocketRateLimiting {

        @Test
        @DisplayName("exceeding STOMP message rate limit closes the offending connection")
        void highMessageRateClosesSession() throws Exception {
            TokenResponse host = createRoom(uniqueName("ThrottledRoom"), "ThrottledPlayer", 6);

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            StompSession session = stomp.connectSessionWithErrorHandler(
                    stomp.createJsonClient(), host.token(), errorReceived);

            for (int i = 0; i < 15; i++) {
                try {
                    session.send("/app/" + host.roomId() + "/ready", "");
                } catch (RuntimeException sessionAlreadyClosed) {
                    break;
                }
            }

            awaitSessionClosedWithError(session, errorReceived);
        }

        @Test
        @DisplayName("same player name in different rooms has isolated WebSocket rate-limit buckets")
        void samePlayerNameInDifferentRoomsIsolatedWsRateLimits() throws Exception {
            TokenResponse alice1 = createRoom(uniqueName("RateRoom1"), "Alice", 6);
            TokenResponse alice2 = createRoom(uniqueName("RateRoom2"), "Alice", 6);

            WebSocketStompClient stompClient = stomp.createJsonClient();
            AtomicBoolean session1Error = new AtomicBoolean(false);
            StompSession session1 = stomp.connectSessionWithErrorHandler(stompClient, alice1.token(), session1Error);
            StompSession session2 = stomp.connectSession(stompClient, alice2.token());

            CompletableFuture<PlayerFeedbackResponse> session2Reply = new CompletableFuture<>();
            stomp.subscribe(session2, PRIVATE_QUEUE, PlayerFeedbackResponse.class, session2Reply::complete);
            stomp.awaitUserBrokerSubscription(jwtService.extractPrincipal(alice2.token()).getName(), PRIVATE_QUEUE);

            for (int i = 0; i < 6; i++) {
                try {
                    session1.send("/app/" + alice1.roomId() + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));
                } catch (RuntimeException sessionAlreadyClosed) {
                    break;
                }
            }
            awaitSessionClosedWithError(session1, session1Error);

            session2.send("/app/" + alice2.roomId() + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));

            PlayerFeedbackResponse reply = session2Reply.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(reply.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(reply.code()).isEqualTo("GAME_NOT_FOUND");
            assertThat(session2.isConnected()).isTrue();
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    private static void awaitSessionClosedWithError(StompSession session, AtomicBoolean errorReceived) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(session.isConnected()).isFalse();
            assertThat(errorReceived).isTrue();
        });
    }

    private TokenResponse createRoom(String roomName, String hostName, int maxPlayers) {
        TokenResponse created = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(created).isNotNull();
        trackedRoomIds.add(created.roomId());
        return created;
    }

    private TokenResponse joinRoom(String roomName, String playerName) {
        TokenResponse joined = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JoinRoomRequest(roomName, playerName, null))
                .retrieve()
                .body(TokenResponse.class);
        assertThat(joined).isNotNull();
        return joined;
    }

    private int startGame(String roomId, String hostToken) {
        return restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    private JsonNode readGameState(String gameId, String token) {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private static JsonNode playerState(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asString())) {
                return player;
            }
        }
        throw new AssertionError("Missing player in public projection: " + playerName);
    }

    private static boolean readyFlag(JsonNode state, String playerName) {
        JsonNode flag = playerState(state, playerName).path("isReadyForNextHand");
        assertThat(flag.isBoolean()).as("isReadyForNextHand of %s", playerName).isTrue();
        return flag.booleanValue();
    }

    private record PrivateProjection(String playerId, long handNumber, List<String> holeCards) { }
}
