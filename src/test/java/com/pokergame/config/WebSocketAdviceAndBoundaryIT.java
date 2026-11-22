package com.pokergame.config;

import static com.pokergame.testutil.TestRoomLocks.withRoomLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerFeedbackResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.persistence.RoomAggregates;
import com.pokergame.security.JwtService;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.security.RateLimitService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import com.pokergame.testutil.TestRoomFixtures;
import com.pokergame.testutil.TestStompClientSupport;
import com.pokergame.util.RoomCoordinator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests verifying WebSocket STOMP decode-boundary exceptions, advice error mapping,
 * readiness rejection feedback, and invalid-action protection across {@code /user/queue/private}.
 */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poker.rate-limiting.enabled=true")
@ActiveProfiles("test")
@DisplayName("WebSocket advice and request boundary integration")
class WebSocketAdviceAndBoundaryIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String PRIVATE_QUEUE = "/user/queue/private";
    private static final String HOST_NAME = "BoundaryHost";
    private static final String OTHER_NAME = "BoundaryOther";

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAggregates roomAggregates;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private RoomCoordinator roomCoordinator;

    @Autowired
    private WebSocketEventListener webSocketEventListener;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private UserDestinationResolver userDestinationResolver;

    @Autowired
    private SimpleBrokerMessageHandler broker;

    @Autowired
    private ObjectMapper objectMapper;

    private final Set<String> trackedRoomIds = ConcurrentHashMap.newKeySet();
    private TestRoomFixtures roomFixtures;
    private TestStompClientSupport stomp;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        rateLimitService.reset();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
        roomFixtures = new TestRoomFixtures(roomService, roomCoordinator, webSocketEventListener, trackedRoomIds);
        stomp = new TestStompClientSupport(port, userRegistry, userDestinationResolver, broker);
    }

    @AfterEach
    void tearDown() {
        stomp.closeAllAndDestroyRooms(roomCoordinator, roomService, roomAggregates, trackedRoomIds);
        rateLimitService.reset();
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
        @DisplayName("invalid action JSON frames yield structured private errors and leave the table unchanged")
        void invalidActionFramePublishesClientErrorAndPreservesGame(
                String label, String rawJson, String expectedCode, String expectedMessage) throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectRawBytesSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            sendRawAction(session, game.roomId(), rawJson);

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).as(label).isEqualTo(expectedCode);
            assertThat(error.message()).as(label).isEqualTo(expectedMessage);
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }

        @Test
        @DisplayName("wrong-typed action amount yields INVALID_ACTION_FORMAT on the private queue")
        void wrongTypedAmountYieldsInvalidActionFormatOnPrivateQueue() throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectRawBytesSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            sendRawAction(session, game.roomId(), "{\"action\":\"RAISE\",\"amount\":\"not-a-number\"}");

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INVALID_ACTION_FORMAT");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
            assertThat(error.message()).isEqualTo("Enter a valid action amount");
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }

        @Test
        @DisplayName("completely malformed action JSON yields a player notification, not an action error")
        void malformedActionJsonYieldsPlayerNotification() throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectRawBytesSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            sendRawAction(session, game.roomId(), "{bad");

            PlayerFeedbackResponse notice = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(notice.type()).isEqualTo(PlayerFeedbackResponse.Type.PLAYER_NOTIFICATION);
            assertThat(notice.message()).isEqualTo("Invalid action request format. Please try again with a valid amount.");
            assertThat(notice.code()).isNull();
            assertThat(notice.action()).isNull();
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }

        @Test
        @DisplayName("readiness rejection yields ACTION_REJECTED on the private queue")
        void readinessRejectionYieldsActionRejectedOnPrivateQueue() throws Exception {
            StartedGame game = createStartedGame();
            StompSession session = stomp.connectJsonSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            session.send("/app/" + game.roomId() + "/ready", "");

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("ACTION_REJECTED");
            assertThat(error.action()).isEqualTo(RejectedAction.READY);
            assertThat(error.message()).isEqualTo("Ready countdown is not active.");
        }
    }

    @Nested
    @DisplayName("Service-rejected STOMP actions leave the table unchanged")
    class ServiceRejectionWireErrors {

        @ParameterizedTest
        @EnumSource(PlayerAction.class)
        @DisplayName("every poker action sent out of turn publishes NOT_YOUR_TURN with its rejected action identity")
        void outOfTurnActionPublishesStructuredFeedback(PlayerAction action) throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectJsonSession(game.waitingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.waitingToken());

            int amount = action == PlayerAction.BET || action == PlayerAction.RAISE ? 100 : 0;
            session.send("/app/" + game.roomId() + "/action", new PlayerActionRequest(action, amount));

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("NOT_YOUR_TURN");
            assertThat(error.action()).isEqualTo(RejectedAction.from(action));
            assertThat(error.message()).isEqualTo("Wait for your turn");
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }

        @Test
        @DisplayName("insufficient chips publishes INSUFFICIENT_CHIPS error to private queue")
        void insufficientChipsPublishesPrivateError() throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectJsonSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            session.send("/app/" + game.roomId() + "/action", new PlayerActionRequest(PlayerAction.RAISE, 2000));

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INSUFFICIENT_CHIPS");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
            int availableChips = tableBefore.chips().get(game.actingPlayer());
            assertThat(error.message()).isEqualTo("Available: " + availableChips + "; yours: 2000");
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }

        @Test
        @DisplayName("action amount above 10,000,000 yields INVALID_ACTION_FORMAT before mutating state")
        void oversizedActionAmountPublishesInvalidFormatWithoutMutatingGame() throws Exception {
            StartedGame game = createStartedGame();
            TableState tableBefore = readTable(game);
            StompSession session = stomp.connectJsonSession(game.actingToken());
            CompletableFuture<PlayerFeedbackResponse> feedback = subscribeToPrivateFeedback(session, game.actingToken());

            session.send("/app/" + game.roomId() + "/action", new PlayerActionRequest(PlayerAction.RAISE, 10_000_001));

            PlayerFeedbackResponse error = feedback.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("INVALID_ACTION_FORMAT");
            assertThat(error.action()).isEqualTo(RejectedAction.RAISE);
            assertThat(error.message()).isEqualTo("Amount cannot exceed 10,000,000");
            assertThat(readTable(game)).isEqualTo(tableBefore);
        }
    }

    // Starts a heads-up game with both players connected and identifies who must act first.
    private StartedGame createStartedGame() {
        String roomId = roomFixtures.createAndConnectRoom("BoundaryRoom", HOST_NAME, OTHER_NAME);
        String hostToken = tokenFor(roomId, HOST_NAME);
        String otherToken = tokenFor(roomId, OTHER_NAME);
        gameLifecycleService.createGameFromRoom(roomId);

        String actingPlayer = withRoomLock(roomCoordinator, roomId,
                () -> gameLifecycleService.getGame(roomId).getCurrentPlayer().getName());
        boolean hostActs = HOST_NAME.equals(actingPlayer);
        return new StartedGame(roomId, actingPlayer, hostActs ? hostToken : otherToken, hostActs ? otherToken : hostToken);
    }

    private String tokenFor(String roomId, String playerName) {
        PlayerPrincipal principal = roomFixtures.principalFor(roomId, playerName);
        return jwtService.generateToken(playerName, roomId, principal.membershipId());
    }

    // Subscribes to the private queue and returns the first feedback payload, once the broker has the subscription.
    private CompletableFuture<PlayerFeedbackResponse> subscribeToPrivateFeedback(StompSession session, String token) {
        CompletableFuture<PlayerFeedbackResponse> feedback = new CompletableFuture<>();
        stomp.subscribe(session, PRIVATE_QUEUE, PlayerFeedbackResponse.class, reply -> {
            if (reply.type() != null) {
                feedback.complete(reply);
            }
        });
        stomp.awaitUserBrokerSubscription(jwtService.extractPrincipal(token).getName(), PRIVATE_QUEUE);
        return feedback;
    }

    private void sendRawAction(StompSession session, String roomId, String rawJson) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/" + roomId + "/action");
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        session.send(headers, rawJson.getBytes(StandardCharsets.UTF_8));
    }

    // Reads the public table projection as the acting player sees it.
    private TableState readTable(StartedGame game) {
        String body = restClient.get()
                .uri("/api/game/" + game.roomId() + "/state")
                .headers(headers -> headers.setBearerAuth(game.actingToken()))
                .retrieve()
                .body(String.class);
        JsonNode state = objectMapper.readTree(body);
        Map<String, Integer> chips = new LinkedHashMap<>();
        for (JsonNode player : state.path("players")) {
            chips.put(player.path("name").asString(), player.path("chips").asInt());
        }
        return new TableState(
                state.path("currentPlayerName").asString(),
                state.path("phase").asString(),
                state.path("pot").asInt(),
                state.path("stateRevision").asLong(),
                chips);
    }

    private record StartedGame(String roomId, String actingPlayer, String actingToken, String waitingToken) { }

    private record TableState(String currentPlayer, String phase, int pot, long stateRevision, Map<String, Integer> chips) { }
}
