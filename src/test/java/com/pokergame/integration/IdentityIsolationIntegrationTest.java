package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.jspecify.annotations.NonNull;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Assertions;
import org.springframework.web.client.HttpClientErrorException;

/** Tests identity isolation integration behavior. */
@Tag("integration")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = AbstractIntegrationTestSupport.RATE_LIMITING_ENABLED_PROPERTY + "=true")
@ActiveProfiles("test")
// Enabling rate limiting via the property seam makes this context cacheable and shareable with other
// rate-limiting contexts (e.g. LifecycleRegistrationCleanupTest). Since this class opens live sockets whose
// teardown schedules grace-period pending-disconnects, dirty the context after the class so no leaked
// WebSocket registration state bleeds into a sibling test that asserts pristine global counts.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Identity isolation integration")
class IdentityIsolationIntegrationTest extends AbstractIntegrationTestSupport {

    private WebSocketStompClient stompClient;

    /**
     * Initializes the test fixtures before each scenario. Rate limiting is enabled for this class via the
     * RATE_LIMITING_ENABLED_PROPERTY config seam, so the base setup leaves it on and resets the buckets.
     */
    @BeforeEach
    void setUp() {
        stompClient = createStompClient();
    }

    /**
     * Protects the contract that the system should isolate rate limits for same player name in different rooms:
     * throttling one session is observable (the throttled session is closed) while the other's command is
     * actually processed (it receives a private reply), not merely that its socket remains open.
     */
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

        // session2 listens on its private queue so we can prove its command was actually processed by the
        // server (a reply arrives) rather than inferring success from an open socket.
        CompletableFuture<ActionErrorResponse> session2Reply = new CompletableFuture<>();
        session2.subscribe("/user/queue/private", new StompFrameHandler() {
            /** {@inheritDoc} */
            @Override public Type getPayloadType(@NonNull StompHeaders headers) {
                return ActionErrorResponse.class;
            }

            /** {@inheritDoc} */
            @Override public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (payload instanceof ActionErrorResponse reply && !session2Reply.isDone()) {
                    session2Reply.complete(reply);
                }
            }
        });

        // 3. Exhaust Alice1's bucket then push one more SEND past the limit (limit is 5 per second).
        for (int i = 0; i < 6; i++) {
            sendAction(session1, roomId1, token1);
        }

        // Observable throttling: exceeding the bucket makes the server close session1's connection.
        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(session1.isConnected()).isFalse());

        // 4. Alice2's bucket is separate, so her command is processed: the server replies on her private
        // queue (an ACTION_ERROR because no game is running) and her session stays open.
        sendAction(session2, roomId2, token2);

        ActionErrorResponse reply = session2Reply.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply).isNotNull();
        assertThat(session2.isConnected()).isTrue();
    }

    /**
     * Protects the contract that the system should prevent Alice from Room A accessing Room B.
     */
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
            
            Assertions.fail("Should have thrown 403/Forbidden");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(403);
            assertThat(e.getResponseBodyAsString()).contains("Token is not valid for this room");
        }
    }

    /**
     * Protects against B7-10: Stale session JWT token reuse after leaving and rejoining a room.
     * The old token must be rejected with HTTP 401 Unauthorized.
     */
    @Test
    @DisplayName("should reject stale session token with 401 when player leaves and rejoins")
    void givenPlayerLeftAndRejoined_whenAccessingWithStaleToken_thenUnauthorized401() throws Exception {
        JsonNode hostData = createRoom("StaleSessionRoom", "HostPlayer", 6);
        String roomId = hostData.path("roomId").asText();

        JsonNode aliceData1 = joinRoom("StaleSessionRoom", "Alice");
        String staleToken = aliceData1.path("token").asText();

        // Alice leaves the room
        restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + staleToken)
                .retrieve()
                .toBodilessEntity();

        // Alice rejoins the room and receives a new token with fresh membershipId
        JsonNode aliceData2 = joinRoom("StaleSessionRoom", "Alice");
        String activeToken = aliceData2.path("token").asText();
        assertThat(activeToken).isNotEqualTo(staleToken);

        // Accessing room info with stale token must be rejected with 401
        try {
            restClient.get()
                    .uri("/api/room/" + roomId)
                    .header("Authorization", "Bearer " + staleToken)
                    .retrieve()
                    .toBodilessEntity();
            Assertions.fail("Expected 401 Unauthorized for stale token");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            assertThat(e.getResponseBodyAsString()).contains("Unauthorized");
        }

        // Accessing room info with active token must succeed (200 OK)
        String activeResponse = restClient.get()
                .uri("/api/room/" + roomId)
                .header("Authorization", "Bearer " + activeToken)
                .retrieve()
                .body(String.class);
        assertThat(activeResponse).contains(roomId);
    }

    /**
     * Protects against B7-10: Stale session JWT token reuse on game endpoints.
     */
    @Test
    @DisplayName("should reject stale session token on game endpoints with 401")
    void givenGameStartedAndPlayerRejoined_whenAccessingGameWithStaleToken_thenUnauthorized401() throws Exception {
        JsonNode hostData = createRoom("StaleGameRoom", "HostPlayer2", 6);
        String roomId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        JsonNode aliceData1 = joinRoom("StaleGameRoom", "Alice2");
        String staleToken = aliceData1.path("token").asText();

        // Alice leaves the room
        restClient.post()
                .uri("/api/room/" + roomId + "/leave")
                .header("Authorization", "Bearer " + staleToken)
                .retrieve()
                .toBodilessEntity();

        // Alice rejoins
        JsonNode aliceData2 = joinRoom("StaleGameRoom", "Alice2");
        String activeToken = aliceData2.path("token").asText();

        // Host starts the game
        startGame(roomId, hostToken);

        // Accessing game state with stale token must be rejected with 401
        try {
            restClient.get()
                    .uri("/api/game/" + roomId + "/state")
                    .header("Authorization", "Bearer " + staleToken)
                    .retrieve()
                    .toBodilessEntity();
            Assertions.fail("Expected 401 Unauthorized for stale token on game endpoint");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
            assertThat(e.getResponseBodyAsString()).contains("Unauthorized");
        }

        // Accessing game state with active token must succeed
        String activeState = restClient.get()
                .uri("/api/game/" + roomId + "/state")
                .header("Authorization", "Bearer " + activeToken)
                .retrieve()
                .body(String.class);
        assertThat(activeState).contains("Alice2");
    }

    /**
     * Sends action for the test.
     * @param session session supplied to the fixture
     * @param roomId room ID supplied to the fixture
     * @param token token supplied to the fixture
     */
    private void sendAction(StompSession session, String roomId, String token) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/" + roomId + "/action");
        headers.add("Authorization", "Bearer " + token);
        session.send(headers, new PlayerActionRequest(PlayerAction.CHECK, 0));
    }
}
