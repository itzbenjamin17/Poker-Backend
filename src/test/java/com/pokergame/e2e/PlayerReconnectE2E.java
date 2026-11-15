package com.pokergame.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end test verifying real WebSocket disconnect detection and reconnect grace restoration.
 */
@Tag("e2e")
@DisplayName("Player reconnect E2E")
class PlayerReconnectE2E extends AbstractE2ESupport {

    @Test
    @DisplayName("should allow a player to reconnect and continue acting in a game")
    void givenActiveGame_whenPlayerDisconnectsAndReconnects_thenPlayerCanStillAct() throws Exception {
        String roomName = uniqueName("ReconnectRoom");
        JsonNode hostData = createRoom(roomName, "ReconnectHost", 2);
        String gameId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();
        String guestToken = joinRoom(roomName, "ReconnectGuest").path("token").asText();
        startGame(gameId, hostToken);

        JsonNode initialState = readGameState(gameId, hostToken);
        String currentPlayerName = initialState.path("currentPlayerName").asText();
        String currentToken = "ReconnectHost".equals(currentPlayerName) ? hostToken : guestToken;
        String otherToken = "ReconnectHost".equals(currentPlayerName) ? guestToken : hostToken;
        String otherPlayerName = "ReconnectHost".equals(currentPlayerName) ? "ReconnectGuest" : "ReconnectHost";

        WebSocketStompClient stompClient = createStompClient();
        StompSession currentSession = connectSession(stompClient, currentToken);
        StompSession otherSession = connectSession(stompClient, otherToken);

        currentSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));

        otherSession.disconnect();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode state = readGameState(gameId, currentToken);
            assertThat(findPlayer(state, otherPlayerName).path("status").asText()).isEqualTo("DISCONNECTED");
        });

        StompSession reconnectedOtherSession = connectSession(stompClient, otherToken);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode state = readGameState(gameId, hostToken);
            assertThat(findPlayer(state, otherPlayerName).path("status").asText()).isEqualTo("ACTIVE");
        });

        reconnectedOtherSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, null));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            JsonNode flopState = readGameState(gameId, hostToken);
            assertThat(flopState.path("phase").asText()).isEqualTo("FLOP");
        });
    }
}
