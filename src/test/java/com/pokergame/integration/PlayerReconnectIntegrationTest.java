package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;


import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Tests resiliency integration behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Resiliency integration")
class PlayerReconnectIntegrationTest extends AbstractIntegrationTestSupport {

    /**
     * Protects ADR-0009 last-session disconnect and reconnect-driven grace cancellation through the real
     * socket lifecycle: closing a player's only session must flip the projection to DISCONNECTED, and
     * reconnecting the same membership on a fresh live session must restore ACTIVE and let the player act. No
     * direct lifecycle calls are used; disconnect/reconnect are driven entirely by SessionDisconnectEvent and
     * the connect handshake.
     */
    @Test
    @DisplayName("should allow a player to reconnect and continue acting in a game")
    void givenActiveGame_whenPlayerDisconnectsAndReconnects_thenPlayerCanStillAct() throws Exception {
        String roomName = uniqueName("ReconnectRoom");
        JsonNode hostData = createRoom(roomName, "ReconnectHost", 2);
        String gameId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();
        String guestToken = joinRoom(roomName, "ReconnectGuest").path("token").asText();
        startGame(gameId, hostToken);

        // Determine current player
        JsonNode initialState = readGameState(gameId, hostToken);
        String currentPlayerName = initialState.path("currentPlayerName").asText();
        String currentToken = currentPlayerName.equals("ReconnectHost") ? hostToken : guestToken;
        String otherToken = currentPlayerName.equals("ReconnectHost") ? guestToken : hostToken;
        String otherPlayerName = currentPlayerName.equals("ReconnectHost") ? "ReconnectGuest" : "ReconnectHost";

        WebSocketStompClient stompClient = createStompClient();
        StompSession currentSession = connectSession(stompClient, currentToken);
        // The other player holds one live session; closing it below is therefore a genuine last-session
        // disconnect that ADR-0009 must surface as DISCONNECTED.
        StompSession otherSession = connectSession(stompClient, otherToken);

        // 1. Current player acts (Pre-flop: Call)
        currentSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));

        // 2. Other player disconnects by closing its only socket; the SessionDisconnectEvent drives the
        // transition — no direct markPlayerDisconnected call.
        otherSession.disconnect();

        // 3. The projection must show DISCONNECTED for the other player once the event is processed.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode state = readGameState(gameId, currentToken);
            assertThat(state.path("players").elements()).toIterable()
                    .anyMatch(p -> otherPlayerName.equals(p.path("name").asText())
                            && "DISCONNECTED".equals(p.path("status").asText()));
        });

        // 4. Other player reconnects on a fresh live session with the same active membership; the connect
        // handshake drives reconnection and grace cancellation — no direct markPlayerReconnected call.
        StompSession reconnectedOtherSession = connectSession(stompClient, otherToken);

        // 5. Await the projection returning to ACTIVE for the reconnected player.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode state = readGameState(gameId, hostToken);
            assertThat(state.path("players").elements()).toIterable()
                    .anyMatch(p -> otherPlayerName.equals(p.path("name").asText())
                            && "ACTIVE".equals(p.path("status").asText()));
        });

        // 6. The reconnected player completes a legal action through the socket to finish Pre-flop.
        reconnectedOtherSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, null));

        // 7. Wait for Flop, proving the reconnected player's action was processed.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode flopState = readGameState(gameId, hostToken);
            assertThat(flopState.path("phase").asText()).isEqualTo("FLOP");
        });
    }
}
