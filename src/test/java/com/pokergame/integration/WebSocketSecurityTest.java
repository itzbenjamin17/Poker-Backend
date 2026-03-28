package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.security.JwtService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
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

/**
 * Integration tests for WebSocket authentication via JWT.
 * Tests STOMP connection with and without valid JWT tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSecurityTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    private WebSocketStompClient stompClient;
    private String wsUrl;
    private String roomId;
    private WebSocketHttpHeaders handshakeHeaders;

    @BeforeEach
    void setUp() {
        wsUrl = "ws://localhost:" + port + "/ws";

        // Define handshake headers with the allowed Origin to pass CORS check
        handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.add("Origin", "http://localhost:5173");

        // Create STOMP client with SockJS
        List<Transport> transports = new ArrayList<>();
        transports.add(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);

        stompClient = new WebSocketStompClient(sockJsClient);

        // Register converters for String and JSON (Jackson)
        List<MessageConverter> converters = new ArrayList<>();
        converters.add(new StringMessageConverter());
        // Use JacksonJsonMessageConverter as MappingJackson2MessageConverter is
        // deprecated
        converters.add(new JacksonJsonMessageConverter());
        stompClient.setMessageConverter(new CompositeMessageConverter(converters));

        // Create a test room with UUID to avoid name collisions
        CreateRoomRequest createRequest = new CreateRoomRequest(
                "WsTestRoom" + UUID.randomUUID().toString().substring(0, 8),
                "WsTestHost",
                6, 10, 20, 1000, null);
        roomId = roomService.createRoom(createRequest);
    }

    @Test
    @DisplayName("WebSocket should connect with valid JWT token")
    void websocket_shouldConnect_withValidToken() throws Exception {
        String token = jwtService.generateToken("WsTestHost");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }

                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command,
                                                @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }
                });

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(session);
        assertTrue(session.isConnected());

        session.disconnect();
    }

    @Test
    @DisplayName("WebSocket should connect without token (handshake is public)")
    void websocket_shouldConnect_withoutToken() throws Exception {
        // WebSocket handshake is public, but user won't be authenticated
        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }

                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command,
                                                @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }
                });

        // Connection should succeed (handshake is public), but principal will be null
        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(session);
        assertTrue(session.isConnected());

        session.disconnect();
    }

    @Test
    @DisplayName("WebSocket should handle invalid JWT token gracefully")
    void websocket_shouldHandleInvalidToken_gracefully() throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer invalid-token-here");

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }

                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command,
                                                @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }
                });

        // Connection should still succeed but user won't be authenticated
        // The interceptor logs a warning but doesn't reject the connection
        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(session);
        assertTrue(session.isConnected());

        session.disconnect();
    }

    @Test
    @DisplayName("WebSocket should allow subscription to game topics with valid token")
    void websocket_shouldAllowSubscription_withValidToken() throws Exception {
        String token = jwtService.generateToken("WsTestHost");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        CompletableFuture<Boolean> subscriptionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }

                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command,
                                                @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }
                });

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);

        // Subscribe to a game topic
        session.subscribe("/game/" + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                subscriptionFuture.complete(true);
            }
        });

        // Subscription should succeed (no exception thrown)
        assertTrue(session.isConnected());

        session.disconnect();
    }

    @Test
    @DisplayName("WebSocket should allow subscription to room topics")
    void websocket_shouldAllowSubscription_toRoomTopics() throws Exception {
        String token = jwtService.generateToken("WsTestHost");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }
                });

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);

        // Subscribe to room topic
        StompSession.Subscription subscription = session.subscribe("/room/" + roomId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                // Handle frame
            }
        });

        assertNotNull(subscription);
        assertTrue(session.isConnected());

        session.disconnect();
    }

    @Test
    @DisplayName("WebSocket client can send messages to application destinations")
    void websocket_shouldSendMessages_toAppDestinations() throws Exception {
        String token = jwtService.generateToken("WsTestHost");

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();

        stompClient.connectAsync(wsUrl, handshakeHeaders, connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        sessionFuture.complete(session);
                    }
                });

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);

        // Send a message to app destination (this tests the /app prefix routing)
        // Server may log an error since there's no active game, but message routing
        // works
        // Using a Map to send proper JSON object
        java.util.Map<String, Object> actionPayload = new java.util.HashMap<>();
        actionPayload.put("action", "FOLD");

        assertDoesNotThrow(() -> {
            session.send("/app/" + roomId + "/action", actionPayload);
        });

        session.disconnect();
    }

    @Test
    @DisplayName("Multiple clients can connect with different tokens")
    void websocket_multipleClients_canConnect() throws Exception {
        String token1 = jwtService.generateToken("Player1");
        String token2 = jwtService.generateToken("Player2");

        CompletableFuture<StompSession> session1Future = new CompletableFuture<>();
        CompletableFuture<StompSession> session2Future = new CompletableFuture<>();

        // Connect first client
        StompHeaders headers1 = new StompHeaders();
        headers1.add("Authorization", "Bearer " + token1);

        stompClient.connectAsync(wsUrl, handshakeHeaders, headers1,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        session1Future.complete(session);
                    }
                });

        // Connect second client
        StompHeaders headers2 = new StompHeaders();
        headers2.add("Authorization", "Bearer " + token2);

        stompClient.connectAsync(wsUrl, handshakeHeaders, headers2,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(@NonNull StompSession session, @NonNull StompHeaders connectedHeaders) {
                        session2Future.complete(session);
                    }
                });

        StompSession session1 = session1Future.get(5, TimeUnit.SECONDS);
        StompSession session2 = session2Future.get(5, TimeUnit.SECONDS);

        assertNotNull(session1);
        assertNotNull(session2);
        assertTrue(session1.isConnected());
        assertTrue(session2.isConnected());

        session1.disconnect();
        session2.disconnect();
    }

}
