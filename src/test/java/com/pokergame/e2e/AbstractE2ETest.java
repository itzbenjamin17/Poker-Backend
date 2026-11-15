package com.pokergame.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import com.pokergame.util.LockLease;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shared black-box REST and WebSocket STOMP fixture for end-to-end tests.
 * <p>
 * Subclasses share one Spring Boot application context configured with deterministic test timing
 * and clean per-test teardown of STOMP sessions, WebSocket clients, and tracked rooms under
 * {@link RoomCoordinator}.
 * </p>
 */
@Tag("e2e")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=false",
                "poker.lifecycle.round-end-display-delay-ms=0",
                "poker.lifecycle.ready-countdown-ms=3000",
                "poker.lifecycle.game-end-display-delay-ms=1000"
        })
@ActiveProfiles("test")
public abstract class AbstractE2ETest {

    protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    protected static final String FRONTEND_ORIGIN = "http://localhost:5173";
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    protected int port;

    @Autowired
    protected RoomCoordinator roomCoordinator;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected RestClient restClient;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private final List<StompSession> trackedSessions = new CopyOnWriteArrayList<>();
    private final List<WebSocketStompClient> trackedClients = new CopyOnWriteArrayList<>();

    /** Bound player credentials and optional live STOMP session for black-box game actions. */
    protected record PlayerActor(String name, String token, StompSession session) {
    }

    @BeforeEach
    void setUpBaseClient() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @AfterEach
    void tearDownResources() {
        for (StompSession session : trackedSessions) {
            if (session != null && session.isConnected()) {
                try {
                    session.disconnect();
                } catch (RuntimeException ignored) {
                    // Best-effort disconnect during teardown.
                }
            }
        }
        trackedSessions.clear();

        for (WebSocketStompClient client : trackedClients) {
            if (client != null) {
                try {
                    client.stop();
                } catch (RuntimeException ignored) {
                    // Best-effort client stop during teardown.
                }
            }
        }
        trackedClients.clear();

        for (String roomId : List.copyOf(trackedRoomIds)) {
            withRoomLock(roomId, () -> {
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
    }

    protected static String uniqueName(String prefix) {
        return prefix + "-" + UNIQUE_COUNTER.incrementAndGet();
    }

    protected <T> T withRoomLock(String roomId, Supplier<T> action) {
        try (LockLease _ = roomCoordinator.acquire(roomId)) {
            return action.get();
        }
    }

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
        String roomId = data.path("roomId").asText();
        assertThat(roomId).isNotBlank();
        assertThat(data.path("token").asText()).isNotBlank();
        trackedRoomIds.add(roomId);
        return data;
    }

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

    protected JsonNode startGame(String roomId, String hostToken) throws Exception {
        String body = restClient.post()
                .uri("/api/room/" + roomId + "/start-game")
                .header("Authorization", "Bearer " + hostToken)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected JsonNode getRoomData(String roomId, String token) throws Exception {
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri("/api/room/" + roomId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        String body = request.retrieve().body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected String leaveRoom(String roomId, String token) {
        return restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
    }

    protected JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected JsonNode readPrivateState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/private-state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected JsonNode claimWin(String gameId, String token) throws Exception {
        String body = restClient.post()
                .uri("/api/game/" + gameId + "/claim-win")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertThat(body).isNotBlank();
        return objectMapper.readTree(body);
    }

    protected void awaitRoomDestruction(String roomId, String token, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .pollInterval(Duration.ofMillis(150))
                .untilAsserted(() -> {
                    HttpClientErrorException exception = assertThrows(
                            HttpClientErrorException.class,
                            () -> restClient.get()
                                    .uri("/api/room/" + roomId)
                                    .header("Authorization", "Bearer " + token)
                                    .retrieve()
                                    .body(String.class));
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    protected WebSocketStompClient createStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        trackedClients.add(client);
        return client;
    }

    protected StompSession connectSession(WebSocketStompClient stompClient, String token) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", FRONTEND_ORIGIN);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {}).get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        trackedSessions.add(session);
        return session;
    }

    protected void awaitBrokerSubscription(String destination) {
        awaitBrokerSubscriptions(destination, 1);
    }

    protected void awaitBrokerSubscriptions(String destination, int minCount) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination(destination);
        var probe = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        Awaitility.await()
                .atMost(DEFAULT_TIMEOUT)
                .until(() -> broker.getSubscriptionRegistry().findSubscriptions(probe).values().stream()
                        .mapToInt(List::size).sum() >= minCount);
    }

    protected String performActionByCurrentPlayer(
            String gameId,
            PlayerActionRequest request,
            PlayerActor actorA,
            PlayerActor actorB,
            Predicate<JsonNode> expectedState) throws Exception {
        JsonNode state = readGameState(gameId, actorA.token());
        String currentPlayerName = state.path("currentPlayerName").asText();
        PlayerActor acting = currentPlayerName.equals(actorA.name()) ? actorA : actorB;
        assertThat(acting.name())
                .as("Expected current player %s to be one of %s or %s", currentPlayerName, actorA.name(), actorB.name())
                .isEqualTo(currentPlayerName);

        acting.session().send("/app/" + gameId + "/action", request);

        Awaitility.await().atMost(DEFAULT_TIMEOUT).untilAsserted(() -> {
            JsonNode nextState = readGameState(gameId, actorA.token());
            assertThat(expectedState.test(nextState))
                    .as("Expected game state after %s but saw phase=%s currentPlayer=%s communityCards=%s",
                            request.action(),
                            nextState.path("phase").asText(),
                            nextState.path("currentPlayerName").asText(),
                            nextState.path("communityCards").size())
                    .isTrue();
        });
        return acting.name();
    }

    protected static JsonNode findPlayer(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asText())) {
                return player;
            }
        }
        throw new AssertionError("Missing player in game state: " + playerName);
    }

    protected static boolean playerHasFolded(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asText())) {
                return player.path("hasFolded").asBoolean();
            }
        }
        return false;
    }
}
