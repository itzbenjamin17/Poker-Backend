package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WebSocket security integration")
class WebSocketSecurityTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Nested
    @DisplayName("connection handling")
    class ConnectionHandling {

        @Test
        @DisplayName("should connect with a valid JWT")
        void givenValidToken_whenConnect_thenSessionIsConnected() throws Exception {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("WsConnectRoom"),
                    "WsTestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));
            WebSocketStompClient stompClient = createStompClient();

            StompSession session = connectSession(stompClient, jwtService.generateToken("WsTestHost"));

            assertThat(roomId).isNotBlank();
            assertThat(session.isConnected()).isTrue();
            session.disconnect();
            stompClient.stop();
        }

        @Test
        @DisplayName("should reject connections without a token")
        void givenMissingToken_whenConnect_thenFail() {
            WebSocketStompClient stompClient = createStompClient();
            CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

            try {
                stompClient.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        createHandshakeHeaders(),
                        new StompHeaders(),
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void afterConnected(
                                    @NonNull StompSession session,
                                    @NonNull StompHeaders connectedHeaders) {
                                sessionFuture.complete(session);
                            }

                            @Override
                            public void handleTransportError(
                                    @NonNull StompSession session,
                                    @NonNull Throwable exception) {
                                sessionFuture.completeExceptionally(exception);
                            }
                        });

                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> sessionFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertThat(exception.getCause()).isNotNull();
            } finally {
                stompClient.stop();
            }
        }

        @Test
        @DisplayName("should reject connections with an invalid token")
        void givenInvalidToken_whenConnect_thenFail() {
            WebSocketStompClient stompClient = createStompClient();
            CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer invalid-token-here");

            try {
                stompClient.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        createHandshakeHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void afterConnected(
                                    @NonNull StompSession session,
                                    @NonNull StompHeaders connectedHeaders) {
                                sessionFuture.complete(session);
                            }

                            @Override
                            public void handleTransportError(
                                    @NonNull StompSession session,
                                    @NonNull Throwable exception) {
                                sessionFuture.completeExceptionally(exception);
                            }
                        });

                ExecutionException exception = assertThrows(
                        ExecutionException.class,
                        () -> sessionFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
                assertThat(exception.getCause()).isNotNull();
            } finally {
                stompClient.stop();
            }
        }
    }

    @Nested
    @DisplayName("connected clients")
    class ConnectedClients {

        @Test
        @DisplayName("should allow subscriptions to room and game destinations after authentication")
        void givenConnectedClient_whenSubscribe_thenSessionRemainsConnected() throws Exception {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("WsSubscribeRoom"),
                    "WsTestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));
            WebSocketStompClient stompClient = createStompClient();
            StompSession session = connectSession(stompClient, jwtService.generateToken("WsTestHost"));

            StompSession.Subscription roomSubscription = session.subscribe("/room/" + roomId, new NoOpFrameHandler());
            StompSession.Subscription gameSubscription = session.subscribe("/game/" + roomId, new NoOpFrameHandler());

            assertThat(roomSubscription).isNotNull();
            assertThat(gameSubscription).isNotNull();
            assertThat(session.isConnected()).isTrue();

            session.disconnect();
            stompClient.stop();
        }

        @Test
        @DisplayName("should allow sending messages to application destinations")
        void givenConnectedClient_whenSendActionMessage_thenNoRoutingExceptionOccurs() throws Exception {
            String roomId = roomService.createRoom(new CreateRoomRequest(
                    uniqueName("WsSendRoom"),
                    "WsTestHost",
                    6,
                    10,
                    20,
                    1000,
                    null));
            roomService.joinRoom(new JoinRoomRequest(roomService.getRoom(roomId).getRoomName(), "WsOtherPlayer", null));
            gameLifecycleService.createGameFromRoom(roomId);
            String currentPlayerName = gameLifecycleService.getGame(roomId).getCurrentPlayer().getName();

            WebSocketStompClient stompClient = createStompClient();
            StompSession session = connectSession(stompClient, jwtService.generateToken(currentPlayerName));
            CompletableFuture<PublicGameStateResponse> updateFuture = new CompletableFuture<>();

            session.subscribe("/game/" + roomId, new FoldedPlayerFrameHandler(updateFuture, currentPlayerName));

            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> session.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, null)));
            assertThat(updateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isNotNull();

            session.disconnect();
            stompClient.stop();
        }

        @Test
        @DisplayName("should allow multiple authenticated clients to connect at the same time")
        void givenMultipleValidTokens_whenConnect_thenEachClientGetsItsOwnSession() throws Exception {
            WebSocketStompClient stompClient = createStompClient();

            StompSession sessionOne = connectSession(stompClient, jwtService.generateToken("Player1"));
            StompSession sessionTwo = connectSession(stompClient, jwtService.generateToken("Player2"));

            assertThat(sessionOne.isConnected()).isTrue();
            assertThat(sessionTwo.isConnected()).isTrue();

            sessionOne.disconnect();
            sessionTwo.disconnect();
            stompClient.stop();
        }
    }

    private static class NoOpFrameHandler implements org.springframework.messaging.simp.stomp.StompFrameHandler {
        @Override
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return Object.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            // No-op: these tests only care that subscription succeeds.
        }
    }

    private static class FoldedPlayerFrameHandler implements org.springframework.messaging.simp.stomp.StompFrameHandler {

        private final CompletableFuture<PublicGameStateResponse> future;
        private final String playerName;

        private FoldedPlayerFrameHandler(CompletableFuture<PublicGameStateResponse> future, String playerName) {
            this.future = future;
            this.playerName = playerName;
        }

        @Override
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return PublicGameStateResponse.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            if (payload instanceof PublicGameStateResponse response
                    && !future.isDone()
                    && response.players().stream().anyMatch(player ->
                            player.name().equals(playerName) && player.hasFolded())) {
                future.complete(response);
            }
        }
    }
}
