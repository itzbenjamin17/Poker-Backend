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
     * Protects the contract that the system should allow subscription to own secure user destination.
     */
    @Test
    @DisplayName("should allow subscription to own secure user destination")
    void givenAuthenticatedPlayer_whenSubscribesToOwnPrivate_thenSuccess() throws Exception {
        String roomName = uniqueName("WSSecurityRoom2");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        StompSession session1 = connectSession(stompClient, player1.path("token").asText());
        
        // /user/queue/private is the new secure path
        session1.subscribe("/user/queue/private", new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return Object.class; }
            /** {@inheritDoc} */
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });

        // Verify subscription is processed and session stays alive
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(session1.isConnected()).isTrue());
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
        java.util.concurrent.BlockingQueue<String> receivedFrames = new java.util.concurrent.LinkedBlockingQueue<>();
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

        session1.send("/app/" + room2Id + "/action", new com.pokergame.dto.request.PlayerActionRequest(com.pokergame.enums.PlayerAction.FOLD, null));

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

        return stompClient.connectAsync(wsUrl, createHandshakeHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
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
        }).get(Duration.ofSeconds(5).toSeconds(), TimeUnit.SECONDS);
    }
}
