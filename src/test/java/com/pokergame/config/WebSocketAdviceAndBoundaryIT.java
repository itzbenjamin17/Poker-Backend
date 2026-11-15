package com.pokergame.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomCoordinator;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.AbstractMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying WebSocket STOMP decode-boundary exceptions, advice error mapping,
 * readiness rejection feedback, and invalid-action protection across {@code /user/queue/private}.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.persistence.enabled=false",
                "poker.rate-limiting.enabled=false"
        })
@ActiveProfiles("test")
@DisplayName("WebSocket advice and request boundary integration")
class WebSocketAdviceAndBoundaryIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String HOST_NAME = "BoundaryHost";
    private static final String OTHER_NAME = "BoundaryOther";
    private static final AtomicLong UNIQUE_COUNTER = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private final List<StompSession> trackedSessions = new CopyOnWriteArrayList<>();
    private final List<WebSocketStompClient> trackedClients = new CopyOnWriteArrayList<>();
    private RestClient restClient;

    @BeforeEach
    void setUp() {
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
                gameLifecycleService.evictGame(roomId);
                roomService.destroyRoom(roomId);
                return null;
            });
        }
        trackedRoomIds.clear();
    }

    private StartedGameSession createStartedGame() {
        String roomName = "BoundaryRoom-" + UNIQUE_COUNTER.incrementAndGet();
        String roomId = UUID.randomUUID().toString();
        trackedRoomIds.add(roomId);
        roomService.createRoom(roomId, new CreateRoomRequest(roomName, HOST_NAME, 6, 10, 20, 1000, null));
        roomService.joinRoom(roomId, new JoinRoomRequest(roomName, OTHER_NAME, null));

        StartedGameSession sessionInfo = withRoomLock(roomCoordinator, roomId, () -> {
            Room room = roomService.getRoom(roomId);
            room.setPlayerConnected(HOST_NAME, true);
            room.setPlayerConnected(OTHER_NAME, true);
            return new StartedGameSession(
                    roomId,
                    jwtService.generateToken(HOST_NAME, roomId, room.getMembershipId(HOST_NAME)),
                    jwtService.generateToken(OTHER_NAME, roomId, room.getMembershipId(OTHER_NAME)));
        });

        gameLifecycleService.createGameFromRoom(roomId);
        return sessionInfo;
    }

    private String currentPlayerName(String roomId) {
        return withRoomLock(roomCoordinator, roomId, () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
    }

    private JsonNode readGameState(String gameId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + gameId + "/state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body);
    }

    private StompSession connectJsonSession(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new JacksonJsonMessageConverter())));
        trackedClients.add(client);
        return connectWithClient(client, token);
    }

    private StompSession connectRawBytesSession(String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new RawBytesMessageConverter());
        trackedClients.add(client);
        return connectWithClient(client, token);
    }

    private StompSession connectWithClient(WebSocketStompClient client, String token) throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", ALLOWED_ORIGIN);
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = client.connectAsync(
                "ws://localhost:" + port + "/ws",
                handshakeHeaders,
                connectHeaders,
                new StompSessionHandlerAdapter() {}).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        trackedSessions.add(session);
        return session;
    }

    private CompletableFuture<ActionErrorResponse> subscribePrivateErrors(StompSession session) {
        CompletableFuture<ActionErrorResponse> errorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/private", new StompFrameHandler() {
            @Override
            public @NonNull Type getPayloadType(@NonNull StompHeaders headers) {
                return ActionErrorResponse.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload instanceof ActionErrorResponse response
                        && response.type() == ResponseMessage.ACTION_ERROR
                        && !errorFuture.isDone()) {
                    errorFuture.complete(response);
                }
            }
        });
        return errorFuture;
    }

    @Nested
    @DisplayName("STOMP exception handler advice and raw JSON boundaries")
    class DecodeAndAdviceBoundaries {

        static Stream<Arguments> invalidActionFrames() {
            return Stream.of(
                    Arguments.of("CALL with negative amount",
                            "{\"action\":\"CALL\",\"amount\":-5}",
                            "UNEXPECTED_AMOUNT", "No amount needed; yours: -5"),
                    Arguments.of("BET of zero",
                            "{\"action\":\"BET\",\"amount\":0}",
                            "AMOUNT_REQUIRED", "Amount must be positive; yours: 0"),
                    Arguments.of("RAISE of zero",
                            "{\"action\":\"RAISE\",\"amount\":0}",
                            "AMOUNT_REQUIRED", "Amount must be positive; yours: 0"),
                    Arguments.of("BET with negative amount",
                            "{\"action\":\"BET\",\"amount\":-10}",
                            "NEGATIVE_AMOUNT", "Amount must be positive; yours: -10")
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidActionFrames")
        @DisplayName("invalid action JSON frames yield structured private errors and leave game unchanged")
        void invalidActionFramePublishesClientErrorAndPreservesGame(
                String label, String rawJson, String expectedCode, String expectedMessage) throws Exception {
            StartedGameSession gameSession = createStartedGame();
            String currentPlayer = currentPlayerName(gameSession.roomId());
            String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

            JsonNode stateBefore = readGameState(gameSession.roomId(), actingToken);
            String currentPlayerBefore = stateBefore.path("currentPlayerName").asText();
            String phaseBefore = stateBefore.path("phase").asText();
            int potBefore = stateBefore.path("pot").asInt();

            StompSession session = connectRawBytesSession(actingToken);
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/" + gameSession.roomId() + "/action");
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, rawJson.getBytes(StandardCharsets.UTF_8));

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).as(label).isEqualTo(expectedCode);
            assertThat(error.message()).as(label).isEqualTo(expectedMessage);

            JsonNode stateAfter = readGameState(gameSession.roomId(), actingToken);
            assertThat(stateAfter.path("currentPlayerName").asText()).isEqualTo(currentPlayerBefore);
            assertThat(stateAfter.path("phase").asText()).isEqualTo(phaseBefore);
            assertThat(stateAfter.path("pot").asInt()).isEqualTo(potBefore);
        }

        @Test
        @DisplayName("wrong-typed action amount yields INVALID_ACTION_FORMAT on the private queue")
        void wrongTypedAmountYieldsInvalidActionFormatOnPrivateQueue() throws Exception {
            StartedGameSession gameSession = createStartedGame();
            String currentPlayer = currentPlayerName(gameSession.roomId());
            String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

            StompSession session = connectRawBytesSession(actingToken);
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/" + gameSession.roomId() + "/action");
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, "{\"action\":\"RAISE\",\"amount\":\"not-a-number\"}".getBytes(StandardCharsets.UTF_8));

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INVALID_ACTION_FORMAT");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
        }

        @Test
        @DisplayName("readiness rejection yields ACTION_REJECTED on the private queue")
        void readinessRejectionYieldsActionRejectedOnPrivateQueue() throws Exception {
            StartedGameSession gameSession = createStartedGame();
            StompSession session = connectJsonSession(gameSession.hostToken());
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            session.send("/app/" + gameSession.roomId() + "/ready", "");

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("ACTION_REJECTED");
            assertThat(error.action()).isEqualTo(RejectedAction.READY);
            assertThat(error.message()).isEqualTo("Ready countdown is not active.");
        }
    }

    @Nested
    @DisplayName("Service-rejected STOMP actions preserve authoritative state")
    class ServiceRejectionWireErrors {

        @ParameterizedTest
        @EnumSource(PlayerAction.class)
        @DisplayName("every poker action sent out of turn publishes NOT_YOUR_TURN with its rejected action identity")
        void outOfTurnActionPublishesStructuredFeedback(PlayerAction action) throws Exception {
            StartedGameSession gameSession = createStartedGame();
            String currentPlayer = currentPlayerName(gameSession.roomId());
            String otherToken = HOST_NAME.equals(currentPlayer) ? gameSession.otherToken() : gameSession.hostToken();

            StompSession session = connectJsonSession(otherToken);
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            Integer amount = action == PlayerAction.BET || action == PlayerAction.RAISE ? 100 : null;
            session.send("/app/" + gameSession.roomId() + "/action", new PlayerActionRequest(action, amount));

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("NOT_YOUR_TURN");
            assertThat(error.action()).isEqualTo(RejectedAction.from(action));
            assertThat(error.message()).isEqualTo("Wait for your turn");
        }

        @Test
        @DisplayName("insufficient chips publishes INSUFFICIENT_CHIPS error to private queue")
        void insufficientChipsPublishesPrivateError() throws Exception {
            StartedGameSession gameSession = createStartedGame();
            String currentPlayer = currentPlayerName(gameSession.roomId());
            String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

            StompSession session = connectJsonSession(actingToken);
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            session.send("/app/" + gameSession.roomId() + "/action", new PlayerActionRequest(PlayerAction.RAISE, 2000));

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INSUFFICIENT_CHIPS");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
            assertThat(error.message()).contains("Available:").contains("yours: 2000");
        }

        @Test
        @DisplayName("action amount above 10,000,000 yields INVALID_ACTION_FORMAT before mutating state")
        void oversizedActionAmountPublishesInvalidFormatWithoutMutatingGame() throws Exception {
            StartedGameSession gameSession = createStartedGame();
            String currentPlayer = currentPlayerName(gameSession.roomId());
            String actingToken = HOST_NAME.equals(currentPlayer) ? gameSession.hostToken() : gameSession.otherToken();

            StompSession session = connectJsonSession(actingToken);
            CompletableFuture<ActionErrorResponse> errorFuture = subscribePrivateErrors(session);

            session.send("/app/" + gameSession.roomId() + "/action",
                    new PlayerActionRequest(PlayerAction.RAISE, 10_000_001));

            ActionErrorResponse error = errorFuture.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INVALID_ACTION_FORMAT");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
            assertThat(error.message()).isEqualTo("Amount cannot exceed 10,000,000");

            withRoomLock(roomCoordinator, gameSession.roomId(), () -> {
                Game game = gameLifecycleService.getGame(gameSession.roomId());
                assertThat(game.getCurrentPlayer().getName()).isEqualTo(currentPlayer);
                return null;
            });
        }
    }

    private record StartedGameSession(String roomId, String hostToken, String otherToken) {}

    private static final class RawBytesMessageConverter extends AbstractMessageConverter {

        private final JacksonJsonMessageConverter json = new JacksonJsonMessageConverter();

        private RawBytesMessageConverter() {
            super(MimeTypeUtils.APPLICATION_JSON);
        }

        @Override
        protected boolean supports(@NonNull Class<?> clazz) {
            return true;
        }

        @Override
        protected Object convertFromInternal(
                @NonNull Message<?> message, @NonNull Class<?> targetClass, Object conversionHint) {
            return json.fromMessage(message, targetClass);
        }

        @Override
        protected Object convertToInternal(
                @NonNull Object payload, MessageHeaders headers, Object conversionHint) {
            return payload instanceof byte[] raw ? raw : String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }
}
