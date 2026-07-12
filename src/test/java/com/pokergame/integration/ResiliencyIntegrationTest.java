package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.service.GameLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;


import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Resiliency integration")
class ResiliencyIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private GameLifecycleService gameLifecycleService;

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

        var stompClient = createStompClient();
        StompSession currentSession = connectSession(stompClient, currentToken);
        
        // 1. Current player acts (Pre-flop: Call)
        currentSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));
        
        // 2. Simulate other player disconnecting
        gameLifecycleService.markPlayerDisconnected(gameId, otherPlayerName, System.currentTimeMillis() + 120_000);
        
        // 3. Verify state shows DISCONNECTED for other player
        JsonNode stateAfterDisconnect = readGameState(gameId, currentToken);
        assertThat(stateAfterDisconnect.path("players").elements())
                .toIterable()
                .anyMatch(p -> otherPlayerName.equals(p.path("name").asText()) && "DISCONNECTED".equals(p.path("status").asText()));

        // 4. Other player reconnects
        StompSession reconnectedOtherSession = connectSession(stompClient, otherToken);
        gameLifecycleService.markPlayerReconnected(gameId, otherPlayerName);

        // 5. Current player (now the other one) acts to finish Pre-flop
        reconnectedOtherSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, null));
        
        // 6. Wait for Flop
        await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() -> {
            JsonNode flopState = readGameState(gameId, hostToken);
            assertThat(flopState.path("phase").asText()).isEqualTo("FLOP");
        });
        
        // 7. Verify reconnected player is ACTIVE
        JsonNode stateAfterReconnect = readGameState(gameId, hostToken);
        assertThat(stateAfterReconnect.path("players").elements())
                .toIterable()
                .anyMatch(p -> otherPlayerName.equals(p.path("name").asText()) && "ACTIVE".equals(p.path("status").asText()));

        currentSession.disconnect();
        reconnectedOtherSession.disconnect();
        stompClient.stop();
    }
}
