package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.dto.request.JoinRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.RoomService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WebSocket Throttling Integration")
class WebSocketThrottlingIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private com.pokergame.security.RateLimitService rateLimitService;

    @Test
    @DisplayName("should close connection when exceeding message rate limit")
    void givenHighMessageRate_whenSendTooFast_thenConnectionClosed() throws Exception {
        // Explicitly enable rate limiting for this test
        org.springframework.test.util.ReflectionTestUtils.setField(rateLimitService, "enabled", true);
        rateLimitService.reset();

        String playerName = "ThrottledPlayer";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                "ThrottledRoom", playerName, 6, 10, 20, 1000, null));
        String token = jwtService.generateToken(playerName);

        WebSocketStompClient stompClient = createStompClient();
        
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new org.springframework.web.socket.WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command, 
                                               @NonNull StompHeaders headers, byte @NonNull [] payload, 
                                               @NonNull Throwable exception) {
                        // Exception handled here
                    }
                }
        ).get(5, TimeUnit.SECONDS);
        
        // Limit is 5 per second. Send more than 5 quickly.
        // We expect a ConnectionLostException or similar because Spring closes the channel.
        
        boolean connectionLost = false;
        try {
            for (int i = 0; i < 20; i++) {
                session.send("/app/" + roomId + "/ready", "");
                // Small sleep to ensure they are processed sequentially but fast
                Thread.sleep(10);
            }
        } catch (Exception e) {
            connectionLost = true;
        }

        // Wait a bit for the server to process and close
        Thread.sleep(200);
        
        if (!session.isConnected()) {
            connectionLost = true;
        }

        assertThat(connectionLost).as("Connection should be closed after rate limit exceeded").isTrue();
    }
}
