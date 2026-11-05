package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

/** Tests WebSocket security behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class WebSocketSecurityTest extends AbstractIntegrationTestSupport {

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

    /**
     * Protects the contract that a player's subscription to its own secure user destination is actually
     * registered by the broker. This replaces an earlier oracle that only checked the socket stayed open:
     * here we prove the SUBSCRIBE reached the broker's registry, an observable effect of processing.
     */
    @Test
    @DisplayName("should register a player's subscription to its own secure user destination")
    void givenAuthenticatedPlayer_whenSubscribesToOwnPrivate_thenSubscriptionRegistered() throws Exception {
        String roomName = uniqueName("WSSecurityRoom2");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        StompSession session1 = connectSession(stompClient, player1.path("token").asText());

        // Subscribe to the public game topic alongside the private queue; because STOMP preserves per-session
        // frame order, observing the public subscription in the broker registry proves the private SUBSCRIBE
        // (sent first) was processed too. This is an observable effect rather than a mere open socket.
        String roomId = player1.path("roomId").asText();
        session1.subscribe("/user/queue/private", new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return Object.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });
        session1.subscribe("/game/" + roomId, new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return Object.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });

        awaitBrokerSubscription("/game/" + roomId);
        assertThat(session1.isConnected()).isTrue();
    }

    /**
     * Protects the contract that two players subscribed to their own {@code /user/queue/private} destinations
     * each receive ONLY their own membership's private projection (player identity and hole cards) and never
     * the other player's. Drives a real two-player game over live sockets.
     */
    @Test
    @DisplayName("should deliver each live player only its own private hole cards, never the other's")
    void givenTwoLivePlayers_whenPrivateStateBroadcast_thenEachReceivesOnlyItsOwnHoleCards() throws Exception {
        String roomName = uniqueName("WSPrivateIsolation");
        JsonNode player1 = createRoom(roomName, "Player1", 2);
        JsonNode player2 = joinRoom(roomName, "Player2");
        String roomId = player1.path("roomId").asText();
        String hostToken = player1.path("token").asText();
        String guestToken = player2.path("token").asText();

        WebSocketStompClient stompClient = createStompClient();
        StompSession session1 = connectSession(stompClient, hostToken);
        StompSession session2 = connectSession(stompClient, guestToken);

        CompletableFuture<PrivateProjection> private1 = new CompletableFuture<>();
        CompletableFuture<PrivateProjection> private2 = new CompletableFuture<>();
        subscribePrivateAndPublic(session1, roomId, private1);
        subscribePrivateAndPublic(session2, roomId, private2);

        // Wait until both sessions' subscriptions are registered before any broadcast fires, so no private
        // projection can be published into an unsubscribed session.
        awaitBrokerSubscriptions("/game/" + roomId, 2);

        // Start the game (deals hole cards), then drive a legal pre-flop action so the server emits a full
        // game-state broadcast, which delivers each player its own private projection on /user/queue/private.
        assertThat(startGame(roomId, hostToken).path("message").asText()).isEqualTo("Game started successfully");
        String currentPlayerName = readGameState(roomId, hostToken).path("currentPlayerName").asText();
        StompSession actingSession = "Player1".equals(currentPlayerName) ? session1 : session2;
        actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));

        PrivateProjection state1 = private1.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        PrivateProjection state2 = private2.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        // Each session receives its own two hole cards and a distinct player identity; the two hands never
        // overlap, proving neither session ever saw the other membership's private cards. Hole cards are the
        // compact wire codes (e.g. "AS") the server publishes.
        assertThat(state1.holeCards()).hasSize(2);
        assertThat(state2.holeCards()).hasSize(2);
        assertThat(state1.playerId()).isNotEqualTo(state2.playerId());
        assertThat(state1.holeCards()).doesNotContainAnyElementsOf(state2.holeCards());
        assertThat(state2.holeCards()).doesNotContainAnyElementsOf(state1.holeCards());
    }

    /**
     * Minimal view of a player's private projection as it travels on the wire: the server serialises each
     * hole {@code Card} to a compact string code (via {@code @JsonValue}), so the test reads them as strings
     * rather than reconstructing {@code Card} instances.
     *
     * @param playerId  the receiving player's game identity
     * @param handNumber the hand this projection belongs to
     * @param holeCards the player's hole cards as compact wire codes
     */
    private record PrivateProjection(String playerId, long handNumber, java.util.List<String> holeCards) {
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
     * Subscribes a session to its private queue (completing the given future with the first private
     * projection) and to the public game topic. Subscribing the private queue first means that observing the
     * public subscription in the broker registry also implies the private one is registered.
     *
     * @param session connected player session
     * @param roomId game identifier whose public topic to also subscribe
     * @param privateState future completed with the first private projection received
     */
    private void subscribePrivateAndPublic(
            StompSession session, String roomId, CompletableFuture<PrivateProjection> privateState) {
        session.subscribe("/user/queue/private", new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return PrivateProjection.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload instanceof PrivateProjection state && !privateState.isDone()) {
                    privateState.complete(state);
                }
            }
        });
        session.subscribe("/game/" + roomId, new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return Object.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });
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

    /**
     * Protects the contract that authorized SEND frames to own room destinations succeed without disconnecting.
     */
    @Test
    @DisplayName("should allow authorized SEND frames to own room /ready and /unready destinations")
    void givenAuthorizedPlayer_whenSendToOwnRoomReadyAndUnready_thenSessionRemainsConnected() throws Exception {
        String roomName = uniqueName("WSAuthorizedSend");
        JsonNode player1 = createRoom(roomName, "Player1", 6);
        String roomId = player1.path("roomId").asText();

        WebSocketStompClient stompClient = createStompClient();
        StompSession session1 = connectSession(stompClient, player1.path("token").asText());

        session1.send("/app/" + roomId + "/ready", "");
        session1.send("/app/" + roomId + "/unready", "");

        // Session must remain alive and connected
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(session1.isConnected()).isTrue());
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
