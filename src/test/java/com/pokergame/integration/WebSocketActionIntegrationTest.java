package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerNotificationResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.ResponseMessage;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketActionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private GameLifecycleService gameLifecycleService;

    private WebSocketStompClient stompClient;
    private String wsUrl;
    private String roomId;
    private String hostToken;
    private String otherToken;
    private final String hostName = "ActionHost";
    private final String otherName = "OtherPlayer";

    @BeforeEach
    void setUp() {
        wsUrl = "ws://localhost:" + port + "/ws";

        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);
        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());
        converters.add(new JacksonJsonMessageConverter());
        stompClient.setMessageConverter(new CompositeMessageConverter(converters));

        // Create a room
        CreateRoomRequest createRequest = new CreateRoomRequest(
                "ActionTestRoom" + UUID.randomUUID().toString().substring(0, 8),
                hostName,
                6, 10, 20, 1000, null);
        roomId = roomService.createRoom(createRequest);
        hostToken = jwtService.generateToken(hostName);
        otherToken = jwtService.generateToken(otherName);
    }

    private StompSession connectSession(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", "http://localhost:5173");

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }
                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command, @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }
                });
        return sessionFuture.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Should receive ACTION_ERROR on private topic for invalid bets")
    void shouldReceiveError_whenInvalidActionSent() throws Exception {
        roomService.joinRoom(new com.pokergame.dto.request.JoinRoomRequest(roomService.getRoom(roomId).getRoomName(), otherName, null));
        
        StompSession session = connectSession(hostToken);
        CompletableFuture<PlayerNotificationResponse> errorFuture = new CompletableFuture<>();

        String privateTopic = "/game/" + roomId + "/player-name/" + hostName + "/private";
        session.subscribe(privateTopic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return PlayerNotificationResponse.class;
            }
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload instanceof PlayerNotificationResponse notification && ResponseMessage.ACTION_ERROR.equals(notification.type())) {
                    errorFuture.complete(notification);
                }
            }
        });

        gameLifecycleService.createGameFromRoom(roomId);
        Thread.sleep(500);

        PlayerActionRequest invalidAction = new PlayerActionRequest(PlayerAction.BET, 2000);
        session.send("/app/" + roomId + "/action", invalidAction);

        PlayerNotificationResponse error = errorFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(error);
        assertEquals(ResponseMessage.ACTION_ERROR, error.type());
        session.disconnect();
    }

    @Test
    @DisplayName("Should update game state when valid action is sent via WebSocket")
    void shouldSucceed_whenValidActionSent() throws Exception {
        roomService.joinRoom(new JoinRoomRequest(
                roomService.getRoom(roomId).getRoomName(), otherName, null));

        StompSession hostSession  = connectSession(hostToken);
        StompSession otherSession = connectSession(otherToken);

        CompletableFuture<PublicGameStateResponse> initialFuture = new CompletableFuture<>();
        CompletableFuture<PublicGameStateResponse> nextFuture    = new CompletableFuture<>();

        StompFrameHandler handler = new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) {
                return PublicGameStateResponse.class;
            }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                if (!(payload instanceof PublicGameStateResponse state)) return;
                if (!initialFuture.isDone()) initialFuture.complete(state);
                else nextFuture.complete(state);
            }
        };

        hostSession.subscribe("/game/" + roomId, handler);
        otherSession.subscribe("/game/" + roomId, handler);

        gameLifecycleService.createGameFromRoom(roomId);
        PublicGameStateResponse initial = initialFuture.get(10, TimeUnit.SECONDS);

        StompSession activeSession = hostName.equals(initial.currentPlayerName())
                ? hostSession : otherSession;

        activeSession.send("/app/" + roomId + "/action",
                new PlayerActionRequest(PlayerAction.FOLD, null));

        assertNotNull(nextFuture.get(10, TimeUnit.SECONDS));
        hostSession.disconnect();
        otherSession.disconnect();
    }

    @Test
    @DisplayName("Should handle actions correctly after client disconnects and reconnects")
    void shouldHandleActions_afterReconnect() throws Exception {
        roomService.joinRoom(new JoinRoomRequest(
                roomService.getRoom(roomId).getRoomName(), otherName, null));

        // Get initial state to know who acts first
        StompSession tempSession = connectSession(hostToken);
        CompletableFuture<PublicGameStateResponse> initialFuture = new CompletableFuture<>();
        tempSession.subscribe("/game/" + roomId, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return PublicGameStateResponse.class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                if (payload instanceof PublicGameStateResponse s) initialFuture.complete(s);
            }
        });

        gameLifecycleService.createGameFromRoom(roomId);
        String current = initialFuture.get(10, TimeUnit.SECONDS).currentPlayerName();
        tempSession.disconnect();

        String token = hostName.equals(current) ? hostToken : otherToken;

        // Reconnect and subscribe BEFORE sending — captures reconnect broadcast and FOLD response
        StompSession session2 = connectSession(token);
        CompletableFuture<Object> finalFuture = new CompletableFuture<>();

        session2.subscribe("/game/" + roomId, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return PublicGameStateResponse.class; }
            @Override public void handleFrame(StompHeaders h, Object payload) {
                finalFuture.complete(payload); // accepts any payload — state update or game end
            }
        });

        session2.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, null));

        assertNotNull(finalFuture.get(10, TimeUnit.SECONDS));
        session2.disconnect();
    }
}
