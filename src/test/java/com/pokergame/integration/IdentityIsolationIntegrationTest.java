package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Identity isolation integration")
class IdentityIsolationIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private RateLimitService rateLimitService;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rateLimitService, "enabled", true);
        rateLimitService.reset();
        stompClient = createStompClient();
    }

    @Test
    @DisplayName("should isolate rate limits for same player name in different rooms")
    void givenSamePlayerNameInDifferentRooms_whenThrottlingOne_thenOtherIsUnchanged() throws Exception {
        // 1. Create two rooms with the same player name "Alice"
        JsonNode alice1Data = createRoom("Room1", "Alice", 6);
        JsonNode alice2Data = createRoom("Room2", "Alice", 6);

        String roomId1 = alice1Data.path("roomId").asText();
        String roomId2 = alice2Data.path("roomId").asText();
        String token1 = alice1Data.path("token").asText();
        String token2 = alice2Data.path("token").asText();

        assertThat(roomId1).isNotEqualTo(roomId2);

        // 2. Connect both to WebSockets
        StompSession session1 = connectSession(stompClient, token1);
        StompSession session2 = connectSession(stompClient, token2);

        // 3. Exhaust Alice1's rate limit (Limit is 5 per second)
        for (int i = 0; i < 5; i++) {
            sendAction(session1, roomId1, token1);
        }

        // The 6th action for Alice1 should be throttled (we expect a disconnect or dropped message)
        sendAction(session1, roomId1, token1);
        
        // 4. Alice2 should still be able to send messages because her bucket is separate
        sendAction(session2, roomId2, token2);
        
        // If session2 is still connected, it means it wasn't affected by session1's throttling
        assertThat(session2.isConnected()).isTrue();
    }

    @Test
    @DisplayName("should prevent Alice from Room A accessing Room B")
    void givenAliceInRoomA_whenAccessingRoomB_thenForbidden() throws Exception {
        JsonNode aliceAData = createRoom("RoomA", "Alice", 6);
        JsonNode aliceBData = createRoom("RoomB", "Alice", 6);

        String roomIdB = aliceBData.path("roomId").asText();
        String tokenA = aliceAData.path("token").asText();

        // AliceA tries to leave RoomB
        try {
            restClient.post()
                    .uri("/api/room/" + roomIdB + "/leave")
                    .header("Authorization", "Bearer " + tokenA)
                    .retrieve()
                    .toBodilessEntity();
            
            org.junit.jupiter.api.Assertions.fail("Should have thrown 403/Forbidden");
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(403);
            assertThat(e.getResponseBodyAsString()).contains("Token is not valid for this room");
        }
    }

    private void sendAction(StompSession session, String roomId, String token) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/" + roomId + "/action");
        headers.add("Authorization", "Bearer " + token);
        session.send(headers, new PlayerActionRequest(PlayerAction.CHECK, 0));
    }
}
