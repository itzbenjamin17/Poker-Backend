package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("WebSocket action integration")
class WebSocketActionIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    private static final String HOST_NAME = "ActionHost";
    private static final String OTHER_NAME = "OtherPlayer";

    @Nested
    @DisplayName("player actions")
    class PlayerActions {

        @Test
        @DisplayName("should publish ACTION_ERROR to the player's private topic for invalid bets")
        void givenInvalidAction_whenSendOverWebSocket_thenReceivePrivateErrorNotification() throws Exception {
            TestGameSession gameSession = createStartedGame();
            WebSocketStompClient stompClient = createStompClient();
            StompSession session = connectSession(stompClient, gameSession.hostToken());
            CompletableFuture<PlayerNotificationResponse> errorFuture = new CompletableFuture<>();

            session.subscribe(
                    "/game/" + gameSession.roomId() + "/player-name/" + HOST_NAME + "/private",
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(@NonNull StompHeaders headers) {
                            return PlayerNotificationResponse.class;
                        }

                        @Override
                        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                            if (payload instanceof PlayerNotificationResponse notification
                                    && notification.type() == ResponseMessage.ACTION_ERROR
                                    && !errorFuture.isDone()) {
                                errorFuture.complete(notification);
                            }
                        }
                    });

            session.send("/app/" + gameSession.roomId() + "/action", new PlayerActionRequest(PlayerAction.BET, 2000));

            PlayerNotificationResponse error = errorFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            session.disconnect();
            stompClient.stop();
        }

        @Test
        @DisplayName("should broadcast a public game update when a valid action is sent")
        void givenValidAction_whenSendOverWebSocket_thenBroadcastUpdatedGameState() throws Exception {
            TestGameSession gameSession = createStartedGame();
            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, gameSession.hostToken());
            StompSession otherSession = connectSession(stompClient, gameSession.otherToken());
            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> nextStateFuture = new CompletableFuture<>();

            StompFrameHandler handler = new StompFrameHandler() {
                @Override
                public Type getPayloadType(@NonNull StompHeaders headers) {
                    return PublicGameStateResponse.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    if (!(payload instanceof PublicGameStateResponse response)) {
                        return;
                    }

                    if (!initialStateFuture.isDone()) {
                        initialStateFuture.complete(response);
                    } else if (!nextStateFuture.isDone()) {
                        nextStateFuture.complete(response);
                    }
                }
            };

            hostSession.subscribe("/game/" + gameSession.roomId(), handler);
            otherSession.subscribe("/game/" + gameSession.roomId(), handler);

            gameLifecycleService.createGameFromRoom(gameSession.roomId());

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            StompSession actingSession = HOST_NAME.equals(initialState.currentPlayerName()) ? hostSession : otherSession;
            actingSession.send("/app/" + gameSession.roomId() + "/action", new PlayerActionRequest(PlayerAction.FOLD, null));

            PublicGameStateResponse nextState = nextStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(nextState.currentPlayerName()).isNotBlank();
            hostSession.disconnect();
            otherSession.disconnect();
            stompClient.stop();
        }

        @Test
        @DisplayName("should continue processing actions after the active client reconnects")
        void givenReconnectDuringGame_whenActionIsSent_thenGameStillBroadcastsState() throws Exception {
            TestGameSession gameSession = createStartedGame();
            WebSocketStompClient stompClient = createStompClient();
            StompSession observerSession = connectSession(stompClient, gameSession.hostToken());
            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();

            observerSession.subscribe("/game/" + gameSession.roomId(), new PublicStateFrameHandler(initialStateFuture));
            gameLifecycleService.createGameFromRoom(gameSession.roomId());

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            observerSession.disconnect();

            String actingToken = HOST_NAME.equals(initialState.currentPlayerName())
                    ? gameSession.hostToken()
                    : gameSession.otherToken();

            StompSession reconnectedSession = connectSession(stompClient, actingToken);
            CompletableFuture<PublicGameStateResponse> nextStateFuture = new CompletableFuture<>();
            reconnectedSession.subscribe("/game/" + gameSession.roomId(), new PublicStateFrameHandler(nextStateFuture));

            reconnectedSession.send("/app/" + gameSession.roomId() + "/action", new PlayerActionRequest(PlayerAction.FOLD, null));

            PublicGameStateResponse nextState = nextStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(nextState).isNotNull();
            reconnectedSession.disconnect();
            stompClient.stop();
        }
    }

    private TestGameSession createStartedGame() {
        String roomId = roomService.createRoom(new CreateRoomRequest(
                uniqueName("ActionTestRoom"),
                HOST_NAME,
                6,
                10,
                20,
                1000,
                null));
        roomService.joinRoom(new JoinRoomRequest(roomService.getRoom(roomId).getRoomName(), OTHER_NAME, null));

        return new TestGameSession(
                roomId,
                jwtService.generateToken(HOST_NAME),
                jwtService.generateToken(OTHER_NAME));
    }

    private record TestGameSession(String roomId, String hostToken, String otherToken) {
    }

    private static final class PublicStateFrameHandler implements StompFrameHandler {

        private final CompletableFuture<PublicGameStateResponse> future;

        private PublicStateFrameHandler(CompletableFuture<PublicGameStateResponse> future) {
            this.future = future;
        }

        @Override
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return PublicGameStateResponse.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            if (payload instanceof PublicGameStateResponse response && !future.isDone()) {
                future.complete(response);
            }
        }
    }
}
