package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the active membership boundary (ADR-0004) over the real STOMP transport.
 * The boundary ({@code validatePlayerMembership} server-side, mirrored by
 * {@code WebSocketAuthInterceptor.validateMembership}) must fail closed: a token whose
 * membershipId no longer matches the room's active membership cannot publish a game
 * action, while a token carrying the active membershipId can and produces an observable
 * game-state change. The stale token is forged with the real {@link JwtService} so it is
 * correctly signed yet carries a membershipId the room never issued, matching the
 * token-minting pattern used by the security integration tests.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("WebSocket membership boundary integration")
class WebSocketMembershipIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("stale-membership token cannot act over STOMP while a valid token can")
    void givenStartedGame_whenActingWithStaleVsValidMembership_thenOnlyValidMutatesState() throws Exception {
        String roomName = uniqueName("MembershipRoom");
        JsonNode hostData = createRoom(roomName, "HostMember", 6);
        String gameId = hostData.path("roomId").asText();
        String hostToken = hostData.path("token").asText();

        JsonNode guestData = joinRoom(roomName, "GuestMember");
        String guestToken = guestData.path("token").asText();

        startGame(gameId, hostToken);

        JsonNode beforeState = readGameState(gameId, hostToken);
        assertThat(beforeState.path("phase").asText()).isEqualTo("PRE_FLOP");
        String currentPlayer = beforeState.path("currentPlayerName").asText();

        // The current player's active token is the one allowed to act this turn.
        String validActingToken = jwtService.extractPlayerName(hostToken).equals(currentPlayer) ? hostToken : guestToken;

        // Forge a correctly signed token for the acting player that carries a membershipId
        // the room never issued, so it is stale/mismatched against the active membership.
        String staleToken = jwtService.generateToken(currentPlayer, gameId, "forged-stale-membership");

        WebSocketStompClient stompClient = createStompClient();

        // A stale-membership token is refused at the transport boundary: the CONNECT frame
        // carrying it never establishes an authenticated session, so no action can be sent.
        assertThatThrownBy(() -> connectSession(stompClient, staleToken))
                .as("stale-membership CONNECT must be rejected by the membership boundary")
                .isInstanceOf(Exception.class);

        // The boundary left game state untouched: still PRE_FLOP with the same player to act.
        JsonNode afterStale = readGameState(gameId, hostToken);
        assertThat(afterStale.path("phase").asText()).isEqualTo("PRE_FLOP");
        assertThat(afterStale.path("currentPlayerName").asText()).isEqualTo(currentPlayer);

        // A valid-membership token connects and its legal action produces an observable effect:
        // the turn moves off the current player (or the phase advances).
        StompSession validSession = connectSession(stompClient, validActingToken);
        try {
            validSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CALL, null));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                JsonNode next = readGameState(gameId, hostToken);
                boolean advanced = !currentPlayer.equals(next.path("currentPlayerName").asText())
                        || !"PRE_FLOP".equals(next.path("phase").asText());
                assertThat(advanced)
                        .as("valid-membership action should advance the game; saw phase=%s currentPlayer=%s",
                                next.path("phase").asText(), next.path("currentPlayerName").asText())
                        .isTrue();
            });
        } finally {
            validSession.disconnect();
        }
    }
}
