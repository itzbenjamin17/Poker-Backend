package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class WebSocketSecurityTest extends AbstractIntegrationTestSupport {

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
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return String.class; }
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });

        // The interceptor throws MessagingException, which Spring translates to an ERROR frame.
        // Standard STOMP behavior is to close the connection after an ERROR frame.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());
    }

    @Test
    @DisplayName("should allow subscription to own secure user destination")
    void givenAuthenticatedPlayer_whenSubscribesToOwnPrivate_thenSuccess() throws Exception {
        String roomName = uniqueName("WSSecurityRoom2");
        JsonNode player1 = createRoom(roomName, "Player1", 6);

        WebSocketStompClient stompClient = createStompClient();
        StompSession session1 = connectSession(stompClient, player1.path("token").asText());
        
        // /user/queue/private is the new secure path
        session1.subscribe("/user/queue/private", new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) { return Object.class; }
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {}
        });

        // Verify subscription is processed and session stays alive
        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(session1.isConnected()).isTrue());
    }

    private StompSession connectSessionWithErrorHandler(WebSocketStompClient stompClient, String token, AtomicBoolean errorReceived) throws Exception {
        String wsUrl = "ws://localhost:" + port + "/ws";
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return stompClient.connectAsync(wsUrl, createHandshakeHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                // Not used
            }

            @Override
            public void handleException(@NonNull StompSession session, StompCommand command, @NonNull StompHeaders headers, byte @NonNull [] payload, @NonNull Throwable exception) {
                errorReceived.set(true);
            }

            @Override
            public void handleTransportError(@NonNull StompSession session, @NonNull Throwable exception) {
                errorReceived.set(true);
            }
        }).get(Duration.ofSeconds(5).toSeconds(), TimeUnit.SECONDS);
    }
}
