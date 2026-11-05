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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests WebSocket throttling integration behavior. */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = AbstractIntegrationTestSupport.RATE_LIMITING_ENABLED_PROPERTY + "=true")
@ActiveProfiles("test")
// Enabling rate limiting via the property seam makes this context cacheable and shareable with other
// rate-limiting contexts (e.g. LifecycleRegistrationCleanupTest). Since this class opens a live socket whose
// teardown schedules a grace-period pending-disconnect, dirty the context after the class so no leaked
// WebSocket registration state bleeds into a sibling test that asserts pristine global counts.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("WebSocket Throttling Integration")
class WebSocketThrottlingIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RoomService roomService;

    /**
     * Protects the contract that the system should close connection when exceeding message rate limit.
     */
    @Test
    @DisplayName("should close connection when exceeding message rate limit")
    void givenHighMessageRate_whenSendTooFast_thenConnectionClosed() throws Exception {
        // Rate limiting is enabled for this class via the RATE_LIMITING_ENABLED_PROPERTY config seam; the base
        // setup resets the buckets before each test, so no reflection is needed here.
        String playerName = "ThrottledPlayer";
        String roomId = roomService.createRoom(new CreateRoomRequest(
                "ThrottledRoom", playerName, 6, 10, 20, 1000, null));
        String token = jwtService.generateToken(playerName, roomId, roomService.getRoom(roomId).getMembershipId(playerName));

        WebSocketStompClient stompClient = createStompClient();
        
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = registerSession(stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                createHandshakeHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    /** {@inheritDoc} */
                    @Override
                    public void handleException(@NonNull StompSession session, StompCommand command, 
                                               @NonNull StompHeaders headers, byte @NonNull [] payload,
                                               @NonNull Throwable exception) {
                    }
                }
        ).get(5, TimeUnit.SECONDS));
        
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
