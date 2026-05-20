package com.pokergame.integration;

import com.pokergame.dto.request.CreateRoomRequest;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.RoomService;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
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

    @Test
    @DisplayName("should close connection when exceeding message rate limit")
    void givenHighMessageRate_whenSendTooFast_thenConnectionClosed() throws Exception {
        // Explicitly enable rate limiting for this test
        ReflectionTestUtils.setField(rateLimitService, "enabled", true);
        rateLimitService.reset();

        String playerName = "ThrottledPlayer";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                "ThrottledRoom", playerName, 6, 10, 20, 1000, null));
        String token = jwtService.generateToken(playerName, roomId);

        WebSocketStompClient stompClient = createStompClient();
        
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                createHandshakeHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command, 
                                               @NonNull StompHeaders headers, byte @NonNull [] payload, 
                                               @NonNull Throwable exception) {
                    }
                }
        ).get(5, TimeUnit.SECONDS);
        
        // Limit is 5 per second. Send more than 5 quickly.
        for (int i = 0; i < 15; i++) {
            try {
                session.send("/app/" + roomId + "/ready", "");
            } catch (Exception e) {
                // Connection might already be closed
                break;
            }
        }

        // Wait a bit for the server to process and close
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session.isConnected()).isFalse());
    }
}
