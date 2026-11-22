package com.pokergame.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerFeedbackResponse;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.security.JwtService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestStompClientSupport;
import com.pokergame.util.RoomCoordinator;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared black-box REST and WebSocket STOMP fixture for end-to-end tests.
 * <p>
 * Subclasses share one Spring Boot application context with a 3-second ready countdown and a
 * 1-second game-end display delay: long enough that a journey can finish inside one countdown,
 * short enough that timeout and cleanup journeys stay fast. Every test gets clean teardown of
 * STOMP sessions, WebSocket clients, and tracked rooms under {@link RoomCoordinator}, and fails if
 * any STOMP client reported an unexpected error.
 * </p>
 */
@Tag("e2e")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.game.ready-countdown-ms=3000",
                "poker.game.game-end-display-delay-ms=1000"
        })
@ActiveProfiles("test")
public abstract class AbstractE2ESupport {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    protected static final String PRIVATE_QUEUE = "/user/queue/private";
    private static final String FRONTEND_ORIGIN = "http://localhost:5173";
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    protected int port;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private UserDestinationResolver userDestinationResolver;

    @Autowired
    private ObjectMapper objectMapper;

    /** Creates STOMP clients, subscribes with typed payloads, and waits for broker subscriptions. */
    protected TestStompClientSupport stomp;
    protected RestClient restClient;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private final Map<StompSession, RecordingSessionHandler> trackedSessions = new ConcurrentHashMap<>();
    private final List<String> unexpectedStompErrors = new CopyOnWriteArrayList<>();
    private volatile boolean tearingDown;

    /** Bound player credentials and live STOMP session for black-box game actions. */
    protected record PlayerActor(String name, String token, StompSession session) {
    }

    @BeforeEach
    void setUpBaseClients() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(DEFAULT_TIMEOUT);
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(requestFactory)
                .build();
        stomp = new TestStompClientSupport(port, userRegistry, userDestinationResolver, broker);
    }

    @AfterEach
    void tearDownResources() {
        tearingDown = true;
        for (StompSession session : trackedSessions.keySet()) {
            if (session.isConnected()) {
                try {
                    session.disconnect();
                } catch (RuntimeException ignored) {
                    // Best-effort disconnect during teardown.
                }
            }
        }
        trackedSessions.clear();

        // Stops the clients it created and destroys every tracked room, isolating failures per room.
        stomp.closeAllAndDestroyRooms(roomCoordinator, roomService, roomAggregates, trackedRoomIds);

        assertThat(unexpectedStompErrors)
                .as("STOMP clients must not report unexpected errors")
                .isEmpty();
    }

    /**
     * Returns a name that is unique across the whole E2E run.
     *
     * @param prefix readable prefix for the generated name
     * @return {@code prefix} followed by a unique counter value
     */
    protected static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    /**
     * Starts an Awaitility wait bounded by {@link #DEFAULT_TIMEOUT} that fails immediately when any
     * STOMP client has reported an unexpected error.
     *
     * @return configured condition factory
     */
    protected ConditionFactory await() {
        return Awaitility.await()
                .atMost(DEFAULT_TIMEOUT)
                .failFast("A STOMP client reported an unexpected error", () -> !unexpectedStompErrors.isEmpty());
    }

    /**
     * Creates a room through the public REST API and tracks it for teardown.
     *
     * @param roomName   unique room name
     * @param hostName   host player name
     * @param maxPlayers seat count
     * @return the {@code TokenResponse} JSON for the host
     */
    protected JsonNode createRoom(String roomName, String hostName, int maxPlayers) {
        CreateRoomRequest request = new CreateRoomRequest(roomName, hostName, maxPlayers, 10, 20, 1000, null);
        String body = restClient.post()
                .uri("/api/room/create")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);

        String roomId = response.path("roomId").asString();
        assertThat(roomId).isNotBlank();
        assertThat(response.path("token").asString()).isNotBlank();
        trackedRoomIds.add(roomId);
        return response;
    }

    /**
     * Joins an existing room through the public REST API.
     *
     * @param roomName   name of the room to join
     * @param playerName joining player's name
     * @return the {@code TokenResponse} JSON for the joined player
     */
    protected JsonNode joinRoom(String roomName, String playerName) {
        JoinRoomRequest request = new JoinRoomRequest(roomName, playerName, null);
        String body = restClient.post()
                .uri("/api/room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        assertThat(body).isNotBlank();
        JsonNode response = objectMapper.readTree(body);
        assertThat(response.path("roomId").asString()).isNotBlank();
        assertThat(response.path("token").asString()).isNotBlank();
        assertThat(response.path("playerName").asString()).isEqualTo(playerName);
        return response;
    }

    /**
     * Starts the game through {@code POST /api/room/{roomId}/start-game}.
     *
     * @param roomId    room identifier
     * @param hostToken host's bearer token
     * @return the HTTP status code
     */
    protected int startGame(String roomId, String hostToken) {
        return restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    /**
     * Reads room details through {@code GET /api/room/{roomId}}.
     *
     * @param roomId room identifier
     * @param token  member's bearer token
     * @return the {@code RoomDataResponse} JSON
     */
    protected JsonNode getRoomData(String roomId, String token) {
        String body = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Leaves a room through {@code POST /api/room/{roomId}/leave}.
     *
     * @param roomId room identifier
     * @param token  member's bearer token
     * @return the HTTP status code
     */
    protected int leaveRoom(String roomId, String token) {
        return restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }

    /**
     * Reads the public game state through {@code GET /api/game/{gameId}/state}.
     *
     * @param gameId game identifier
     * @param token  player's bearer token
     * @return the {@code PublicGameStateResponse} JSON
     */
    protected JsonNode readGameState(String gameId, String token) {
        return getJson("/api/game/" + gameId + "/state", token);
    }

    /**
     * Reads the caller's private state through {@code GET /api/game/{gameId}/private-state}.
     *
     * @param gameId game identifier
     * @param token  player's bearer token
     * @return the {@code PrivatePlayerState} JSON
     */
    protected JsonNode readPrivateState(String gameId, String token) {
        return getJson("/api/game/" + gameId + "/private-state", token);
    }

    /**
     * Reads the final game result through {@code GET /api/game/{gameId}/review}.
     *
     * @param gameId game identifier
     * @param token  player's bearer token
     * @return the {@code PublicGameStateResponse} JSON of the finished game
     */
    protected JsonNode readGameReview(String gameId, String token) {
        return getJson("/api/game/" + gameId + "/review", token);
    }

    /**
     * Claims a win through {@code POST /api/game/{gameId}/claim-win}.
     *
     * @param gameId game identifier
     * @param token  claiming player's bearer token
     * @return the {@code PublicGameStateResponse} JSON of the finished game
     */
    protected JsonNode claimWin(String gameId, String token) {
        String body = restClient.post()
                .uri("/api/game/" + gameId + "/claim-win")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    /**
     * Runs {@code call} and returns the 4xx rejection it must produce.
     *
     * @param call request expected to be rejected
     * @return the rejection raised by the REST client
     */
    protected static HttpClientErrorException expectRejection(ThrowingCallable call) {
        HttpClientErrorException rejection = catchThrowableOfType(HttpClientErrorException.class, call);
        assertThat(rejection).as("the request must be rejected with a 4xx status").isNotNull();
        return rejection;
    }

    /**
     * Asserts that {@code rejection} is a {@code ProblemDetail} response with the given status and detail.
     *
     * @param rejection      rejection returned by {@link #expectRejection(ThrowingCallable)}
     * @param expectedStatus expected HTTP status
     * @param expectedDetail expected {@code $.detail} text
     */
    protected void assertProblem(HttpClientErrorException rejection, HttpStatus expectedStatus, String expectedDetail) {
        assertThat(rejection.getStatusCode()).isEqualTo(expectedStatus);
        HttpHeaders headers = rejection.getResponseHeaders();
        assertThat(headers).isNotNull();
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = objectMapper.readTree(rejection.getResponseBodyAsString());
        assertThat(problem.path("status").asInt()).isEqualTo(expectedStatus.value());
        assertThat(problem.path("title").asString()).isEqualTo(expectedStatus.getReasonPhrase());
        assertThat(problem.path("detail").asString()).isEqualTo(expectedDetail);
    }

    /**
     * Waits until {@code GET /api/room/{roomId}} answers 404 for the given member token.
     *
     * @param roomId room identifier
     * @param token  bearer token of a former member
     */
    protected void awaitRoomDestruction(String roomId, String token) {
        await().untilAsserted(() -> assertThatExceptionOfType(HttpClientErrorException.class)
                .isThrownBy(() -> getRoomData(roomId, token))
                .satisfies(rejection -> assertThat(rejection.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
    }

    /**
     * Waits until {@code playerName} has the given public status in the game state seen by {@code viewerToken}.
     *
     * @param gameId      game identifier
     * @param viewerToken bearer token of a player in the game
     * @param playerName  player whose status is awaited
     * @param status      expected status, for example {@code ACTIVE} or {@code DISCONNECTED}
     */
    protected void awaitPlayerStatus(String gameId, String viewerToken, String playerName, String status) {
        await().untilAsserted(() -> assertThat(
                findPlayer(readGameState(gameId, viewerToken), playerName).path("status").asString())
                .isEqualTo(status));
    }

    /**
     * Creates a tracked STOMP client that sends and decodes JSON.
     *
     * @return tracked client, stopped during teardown
     */
    protected WebSocketStompClient createStompClient() {
        return stomp.createJsonClient();
    }

    /**
     * Connects a STOMP session whose protocol and transport errors fail the test.
     *
     * @param stompClient client to connect with
     * @param token       player's bearer token
     * @return connected session, disconnected during teardown
     * @throws Exception if the connection fails or exceeds {@link #DEFAULT_TIMEOUT}
     */
    protected StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        RecordingSessionHandler handler = new RecordingSessionHandler();
        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws", handshakeHeaders, connectHeaders, handler)
                .get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        trackedSessions.put(session, handler);
        return session;
    }

    /**
     * Disconnects a session on purpose so the resulting close is not reported as an unexpected error.
     *
     * @param session session opened by {@link #connectSession(WebSocketStompClient, String)}
     */
    protected void disconnectSession(StompSession session) {
        RecordingSessionHandler handler = trackedSessions.get(session);
        assertThat(handler).as("session must come from connectSession").isNotNull();
        handler.closing = true;
        session.disconnect();
    }

    /**
     * Waits until no client is subscribed to {@code destination} on the simple broker.
     *
     * @param destination broker destination, for example {@code /game/{roomId}}
     */
    protected void awaitNoBrokerSubscription(String destination) {
        await().until(() -> !stomp.hasBrokerSubscription(destination));
    }

    /**
     * Subscribes to {@code /game/{roomId}}, waits until the broker has the subscription, and returns
     * a live list of every public game state broadcast received afterwards. Call it only while no
     * other client is subscribed to the destination.
     * <p>
     * Broadcasts are kept as JSON because {@code Card} serializes to a compact string that the typed
     * response record cannot read back once community cards are dealt.
     * </p>
     *
     * @param session connected session
     * @param roomId  room and game identifier
     * @return live, thread-safe list of received {@code PublicGameStateResponse} JSON broadcasts
     */
    protected List<JsonNode> recordGameBroadcasts(StompSession session, String roomId) {
        List<JsonNode> broadcasts = new CopyOnWriteArrayList<>();
        String destination = "/game/" + roomId;
        stomp.subscribe(session, destination, JsonNode.class, broadcasts::add);
        stomp.awaitBrokerSubscription(destination);
        return broadcasts;
    }

    /**
     * Waits for the first recorded broadcast matching {@code expected}.
     *
     * @param broadcasts list returned by {@link #recordGameBroadcasts(StompSession, String)}
     * @param expected   condition the awaited broadcast must satisfy
     * @return the first matching broadcast
     */
    protected JsonNode awaitBroadcast(List<JsonNode> broadcasts, Predicate<JsonNode> expected) {
        return await()
                .until(() -> broadcasts.stream().filter(expected).findFirst(), Optional::isPresent)
                .orElseThrow();
    }

    /**
     * Subscribes to {@code /user/queue/private}, waits until the broker has the user subscription,
     * and returns a live list of every {@link PlayerFeedbackResponse} received afterwards.
     *
     * @param session connected session of the player
     * @param token   the same player's bearer token
     * @return live, thread-safe list of received feedback
     */
    protected List<PlayerFeedbackResponse> recordPrivateFeedback(StompSession session, String token) {
        List<PlayerFeedbackResponse> feedback = new CopyOnWriteArrayList<>();
        stomp.subscribe(session, PRIVATE_QUEUE, PlayerFeedbackResponse.class, reply -> {
            if (reply.type() != null) {
                feedback.add(reply);
            }
        });
        stomp.awaitUserBrokerSubscription(jwtService.extractPrincipal(token).getName(), PRIVATE_QUEUE);
        return feedback;
    }

    /**
     * Sends {@code request} as the player whose turn it is and waits until the public game state has
     * advanced to a new hand or revision and satisfies {@code expectedState}.
     *
     * @param gameId        game identifier
     * @param request       action to send over STOMP
     * @param actorA        first player
     * @param actorB        second player
     * @param expectedState condition the state after the action must satisfy
     * @return the name of the player who acted
     */
    protected String performActionByCurrentPlayer(
            String gameId,
            PlayerActionRequest request,
            PlayerActor actorA,
            PlayerActor actorB,
            Predicate<JsonNode> expectedState) {
        JsonNode before = readGameState(gameId, actorA.token());
        long beforeHand = before.path("handNumber").asLong();
        long beforeRevision = before.path("stateRevision").asLong();
        String currentPlayerName = before.path("currentPlayerName").asString();
        PlayerActor acting = currentPlayerName.equals(actorA.name()) ? actorA : actorB;
        assertThat(acting.name())
                .as("Expected current player %s to be one of %s or %s", currentPlayerName, actorA.name(), actorB.name())
                .isEqualTo(currentPlayerName);

        acting.session().send("/app/" + gameId + "/action", request);

        await().untilAsserted(() -> {
            JsonNode next = readGameState(gameId, actorA.token());
            boolean advanced = next.path("handNumber").asLong() > beforeHand
                    || next.path("stateRevision").asLong() > beforeRevision;
            assertThat(advanced && expectedState.test(next))
                    .as("Expected a new state after %s (hand %d, revision %d) but saw hand=%d revision=%d "
                                    + "phase=%s currentPlayer=%s communityCards=%d",
                            request.action(), beforeHand, beforeRevision,
                            next.path("handNumber").asLong(), next.path("stateRevision").asLong(),
                            next.path("phase").asString(), next.path("currentPlayerName").asString(),
                            next.path("communityCards").size())
                    .isTrue();
        });
        return acting.name();
    }

    /**
     * Finds one player's public state.
     *
     * @param state      {@code PublicGameStateResponse} JSON
     * @param playerName player to find
     * @return the player's JSON node
     */
    protected static JsonNode findPlayer(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asString())) {
                return player;
            }
        }
        throw new AssertionError("Missing player in game state: " + playerName);
    }

    /**
     * Reports whether a player has folded in the given public state.
     *
     * @param state      {@code PublicGameStateResponse} JSON
     * @param playerName player to check
     * @return {@code true} when the player is present and has folded
     */
    protected static boolean playerHasFolded(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asString())) {
                return player.path("hasFolded").asBoolean();
            }
        }
        return false;
    }

    /**
     * Reports whether a JSON field is absent or {@code null}.
     *
     * @param node field node returned by {@code path(...)}
     * @return {@code true} for a missing or JSON {@code null} value
     */
    protected static boolean isAbsent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private JsonNode getJson(String uri, String token) {
        String body = restClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    private final class RecordingSessionHandler extends StompSessionHandlerAdapter {
        private volatile boolean closing;

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            // The session handler only receives STOMP ERROR frames; subscriptions have their own handlers.
            // A session being closed on purpose can still get a "Session closed." ERROR for in-flight sends.
            if (!closing) {
                record("ERROR frame " + headers + " payload=" + payload);
            }
        }

        @Override
        public void handleException(
                @NonNull StompSession session,
                StompCommand command,
                @NonNull StompHeaders headers,
                byte @NonNull [] payload,
                @NonNull Throwable exception) {
            record("exception handling " + command + " " + headers + ": " + exception);
        }

        @Override
        public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
            if (!closing) {
                record("transport error: " + exception);
            }
        }

        private void record(String error) {
            if (!tearingDown) {
                unexpectedStompErrors.add(error);
            }
        }
    }
}
