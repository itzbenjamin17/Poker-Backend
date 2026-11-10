package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.GameStateService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests WebSocket security behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "poker.ready-countdown-ms=30000")
public class WebSocketSecurityTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SimpUserRegistry userRegistry;

    @Autowired
    private UserDestinationResolver userDestinationResolver;

    @Autowired
    @Qualifier("clientOutboundChannel")
    private AbstractMessageChannel outboundChannel;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private GameStateService gameStateService;

    /**
     * Protects the contract that the system should prevent player from subscribing to another player's private topic.
     */
    @Test
    @DisplayName("should prevent player from subscribing to another player's private topic")
    void givenTwoPlayers_whenOneSubscribesToOthersPrivate_thenBlockSubscription() throws Exception {
        String roomName = uniqueName("WSSecurityRoom");
        JsonNode player1 = createRoom(roomName, "Player1", 6);
        joinRoom(roomName, "Player2");

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);
        
        // Attempt to subscribe to player2's private topic (legacy predictable path)
        String forbiddenTopic = "/game/" + player1.path("roomId").asText() + "/player-name/Player2/private";
        
        session1.subscribe(forbiddenTopic, new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return String.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });

        // The interceptor throws MessagingException, which Spring translates to an ERROR frame.
        // Standard STOMP behavior is to close the connection after an ERROR frame.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /** Checks both live delivery and the full set of broker dispatches for a single broadcast. */
    @Test
    void eachLivePrivateSubscriptionReceivesOnlyItsAuthenticatedProjection() throws Exception {
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

        // Broker dispatch on the publishing thread is complete when broadcastGameState returns.
        // Capture there so the assertion covers all dispatches, including frames still queued for socket I/O.
        var dispatches = new ConcurrentLinkedQueue<Message<?>>();
        ChannelInterceptor capture = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
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
            gameStateService.broadcastGameState(roomId, gameLifecycleService.getGame(roomId));

            assertThat(hostFrames.poll(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(hostProjection);
            assertThat(guestFrames.poll(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(guestProjection);
            assertPrivateDispatches(dispatches, hostBrokerSession, hostProjection);
            assertPrivateDispatches(dispatches, guestBrokerSession, guestProjection);
        } finally {
            outboundChannel.removeInterceptor(capture);
        }
    }

    /** Mirrors the private state DTO's compact card codes on both REST and STOMP. */
    private record PrivateProjection(String playerId, long handNumber, List<String> holeCards) {}

    /** Reads the server's authoritative projection for the session's authenticated membership. */
    private PrivateProjection readPrivateProjection(String roomId, String token) throws Exception {
        String body = restClient.get()
                .uri("/api/game/" + roomId + "/private-state")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        return objectMapper.readValue(body, PrivateProjection.class);
    }

    /** Awaits the actual resolved private subscription before any game projection is published. */
    private String subscribePrivate(StompSession session, String token, BlockingQueue<PrivateProjection> frames) {
        String identity = jwtService.extractPrincipal(token).getName();
        SimpUser user = Awaitility.await().atMost(DEFAULT_TIMEOUT).until(
                () -> userRegistry.getUser(identity), registered -> registered != null && registered.hasSessions());
        assertThat(user.getSessions()).hasSize(1);
        String sessionId = user.getSessions().iterator().next().getId();
        String destination = "/user/queue/private";

        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
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

    /** Verifies the complete broker dispatch set for one socket, including delayed outbound frames. */
    private void assertPrivateDispatches(
            Collection<Message<?>> dispatches, String sessionId, PrivateProjection expected) throws Exception {
        List<Message<?>> forSession = dispatches.stream()
                .filter(frame -> sessionId.equals(SimpMessageHeaderAccessor.getSessionId(frame.getHeaders())))
                .toList();
        assertThat(forSession).hasSize(1);
        byte[] payload = (byte[]) forSession.getFirst().getPayload();
        assertThat(objectMapper.readValue(payload, PrivateProjection.class)).isEqualTo(expected);
    }

    /**
     * Protects the contract that a live STOMP handshake carrying an explicitly disallowed, unconfigured Origin
     * is rejected (the WebSocket upgrade fails). Makes no claim about a missing-Origin policy.
     */
    @Test
    @DisplayName("should reject a live handshake from a disallowed Origin")
    void givenDisallowedOrigin_whenHandshake_thenUpgradeFails() throws Exception {
        String roomName = uniqueName("WSOriginReject");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        CompletableFuture<StompSession> attempt =
                connectSessionWithOrigin(stompClient, player1.path("token").asText(), DISALLOWED_ORIGIN);

        ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class,
                () -> attempt.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        assertThat(failure.getCause()).isNotNull();
    }

    /**
     * Protects the contract that direct SEND frames to /game/{roomId} broker topic are rejected
     * and terminate the connection.
     */
    @Test
    @DisplayName("should reject direct SEND to /game/{roomId} broker topic and close session")
    void givenConnectedSession_whenSendDirectlyToGameBroker_thenRejectAndDisconnect() throws Exception {
        String roomName = uniqueName("WSBrokerBypass");
        JsonNode player1 = createRoom(roomName, "Player1", 6);
        String roomId = player1.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/game/" + roomId, "{\"spoofed\":\"state\"}");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that when a client attempts direct publish to /game/{roomId},
     * other subscribers on that topic do not receive the spoofed payload.
     */
    @Test
    @DisplayName("should isolate subscribers when a client attempts direct broker spoofing")
    void givenSubscribedPlayer_whenAnotherPlayerSpoofsGameBroker_thenSubscriberReceivesNothing() throws Exception {
        String roomName = uniqueName("WSSpoofIsolation");
        JsonNode player1 = createRoom(roomName, "Player1", 6);
        String roomId = player1.path("roomId").asText();
        JsonNode player2 = joinRoom(roomName, "Player2");

        WebSocketStompClient stompClient = createStompClient();

        // Player 2 subscribes to /game/{roomId}
        StompSession session2 = connectSession(stompClient, player2.path("token").asText());
        BlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
        session2.subscribe("/game/" + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                receivedFrames.add(String.valueOf(payload));
            }
        });

        awaitBrokerSubscription("/game/" + roomId);

        // Player 1 attempts to publish directly to the game broker topic
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);
        session1.send("/game/" + roomId, "{\"spoofed\":\"event\"}");

        // Player 1 session should be disconnected
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());

        // Player 2 should never have received the spoofed payload
        assertThat(receivedFrames.poll(1, TimeUnit.SECONDS)).isNull();
    }

    /**
     * Protects the contract that direct SEND frames to /room/{roomId} broker topic are rejected
     * and terminate the connection.
     */
    @Test
    @DisplayName("should reject direct SEND to /room/{roomId} broker topic and close session")
    void givenConnectedSession_whenSendDirectlyToRoomBroker_thenRejectAndDisconnect() throws Exception {
        String roomName = uniqueName("WSRoomBypass");
        JsonNode player1 = createRoom(roomName, "Player1", 6);
        String roomId = player1.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/room/" + roomId, "{\"spoofed\":\"room\"}");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that direct SEND frames to /queue/** destinations are rejected.
     */
    @Test
    @DisplayName("should reject direct SEND to /queue/private destination and close session")
    void givenConnectedSession_whenSendDirectlyToQueueBroker_thenRejectAndDisconnect() throws Exception {
        String roomName = uniqueName("WSQueueBypass");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/queue/private", "spoofed-private-event");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that a player cannot send actions to another room's action destination.
     */
    @Test
    @DisplayName("should reject cross-room SEND to /app/{otherRoomId}/action and close session")
    void givenTwoRooms_whenPlayerSendsToOtherRoomAction_thenRejectAndDisconnect() throws Exception {
        String roomName1 = uniqueName("WSRoomOne");
        JsonNode player1 = createRoom(roomName1, "Player1", 6);

        String roomName2 = uniqueName("WSRoomTwo");
        JsonNode player2 = createRoom(roomName2, "Player2", 6);
        String room2Id = player2.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/" + room2Id + "/action", new PlayerActionRequest(PlayerAction.FOLD, null));

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that a player cannot send ready confirmations to another room.
     */
    @Test
    @DisplayName("should reject cross-room SEND to /app/{otherRoomId}/ready and close session")
    void givenTwoRooms_whenPlayerSendsToOtherRoomReady_thenRejectAndDisconnect() throws Exception {
        String roomName1 = uniqueName("WSReadyOne");
        JsonNode player1 = createRoom(roomName1, "Player1", 6);

        String roomName2 = uniqueName("WSReadyTwo");
        JsonNode player2 = createRoom(roomName2, "Player2", 6);
        String room2Id = player2.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/" + room2Id + "/ready", "");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that a player cannot send unready confirmations to another room.
     */
    @Test
    @DisplayName("should reject cross-room SEND to /app/{otherRoomId}/unready and close session")
    void givenTwoRooms_whenPlayerSendsToOtherRoomUnready_thenRejectAndDisconnect() throws Exception {
        String roomName1 = uniqueName("WSUnreadyOne");
        JsonNode player1 = createRoom(roomName1, "Player1", 6);

        String roomName2 = uniqueName("WSUnreadyTwo");
        JsonNode player2 = createRoom(roomName2, "Player2", 6);
        String room2Id = player2.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/" + room2Id + "/unready", "");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that multi-segment prefixed cross-room SENDs are rejected.
     */
    @Test
    @DisplayName("should reject cross-room SEND to /app/game/{otherRoomId}/ready and close session")
    void givenTwoRooms_whenPlayerSendsToAppGameOtherRoomReady_thenRejectAndDisconnect() throws Exception {
        String roomName1 = uniqueName("WSGamePrefixOne");
        JsonNode player1 = createRoom(roomName1, "Player1", 6);

        String roomName2 = uniqueName("WSGamePrefixTwo");
        JsonNode player2 = createRoom(roomName2, "Player2", 6);
        String room2Id = player2.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/game/" + room2Id + "/ready", "");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that multi-segment prefixed cross-room room SENDs are rejected.
     */
    @Test
    @DisplayName("should reject cross-room SEND to /app/room/{otherRoomId}/action and close session")
    void givenTwoRooms_whenPlayerSendsToAppRoomOtherRoomAction_thenRejectAndDisconnect() throws Exception {
        String roomName1 = uniqueName("WSRoomPrefixOne");
        JsonNode player1 = createRoom(roomName1, "Player1", 6);

        String roomName2 = uniqueName("WSRoomPrefixTwo");
        JsonNode player2 = createRoom(roomName2, "Player2", 6);
        String room2Id = player2.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/room/" + room2Id + "/action", "{}");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /**
     * Protects the contract that SEND frames to blank /app/ destination are rejected.
     */
    @Test
    @DisplayName("should reject SEND to blank /app/ destination and close session")
    void givenConnectedSession_whenSendToBlankAppDestination_thenRejectAndDisconnect() throws Exception {
        String roomName = uniqueName("WSBlankDest");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        AtomicBoolean errorReceived = new AtomicBoolean(false);
        StompSession session1 = connectSessionWithErrorHandler(stompClient, player1.path("token").asText(), errorReceived);

        session1.send("/app/", "");

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    /** Drives both commands through a real gate and checks their authenticated player's public state. */
    @Test
    void readyAndUnreadyCommandsChangeOnlyTheActingPlayersReadiness() throws Exception {
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
        assertThat(actor).isIn("Player1", "Player2");
        StompSession actingSession = "Player1".equals(actor) ? hostSession : guestSession;
        actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, null));

        JsonNode opened = Awaitility.await().atMost(DEFAULT_TIMEOUT).until(
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

    /** Correlates each transition with a newer projection of the same open gate and hand. */
    private long awaitReadiness(String roomId, String token, long handNumber, long previousRevision, boolean ready) {
        JsonNode updated = Awaitility.await().atMost(DEFAULT_TIMEOUT).until(
                () -> readGameState(roomId, token),
                state -> state.path("stateRevision").asLong() > previousRevision
                        && state.path("handNumber").asLong() == handNumber
                        && "COUNTING_DOWN".equals(state.path("readyGateState").asText())
                        && Boolean.toString(ready).equals(playerState(state, "Player1").path("isReadyForNextHand").asText()));
        assertThat(playerState(updated, "Player2").path("isReadyForNextHand").asText()).isEqualTo("false");
        return updated.path("stateRevision").asLong();
    }

    /** Finds a required player in the public projection. */
    private JsonNode playerState(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asText())) {
                return player;
            }
        }
        throw new AssertionError("Missing player in public projection: " + playerName);
    }

    /**
     * Connects session with error handler for the test.
     * @param stompClient STOMP client supplied to the fixture
     * @param token token supplied to the fixture
     * @param errorReceived error received supplied to the fixture
     * @return connected session that records transport errors
     */
    private StompSession connectSessionWithErrorHandler(WebSocketStompClient stompClient, String token, AtomicBoolean errorReceived) throws Exception {
        String wsUrl = "ws://localhost:" + port + "/ws";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return registerSession(stompClient.connectAsync(wsUrl, createHandshakeHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                // Not used
            }

            /** {@inheritDoc} */
            @Override
            public void handleException(@NonNull StompSession session, StompCommand command, @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                errorReceived.set(true);
            }

            /** {@inheritDoc} */
            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                errorReceived.set(true);
            }
        }).get(Duration.ofSeconds(5).toSeconds(), TimeUnit.SECONDS));
    }
}
