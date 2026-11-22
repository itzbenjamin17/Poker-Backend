package com.pokergame.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end test verifying real WebSocket disconnect detection and reconnect grace restoration.
 */
@Tag("e2e")
@DisplayName("Player reconnect E2E")
class PlayerReconnectE2E extends AbstractE2ESupport {

    private static final String HOST_NAME = "ReconnectHost";
    private static final String GUEST_NAME = "ReconnectGuest";

    @Test
    @DisplayName("should let a disconnected player reconnect, receive broadcasts again, and keep acting")
    void givenActiveGame_whenPlayerDisconnectsAndReconnects_thenBroadcastsResumeAndPlayerCanAct() throws Exception {
        String roomName = uniqueName("ReconnectRoom");
        JsonNode hostData = createRoom(roomName, HOST_NAME, 2);
        String gameId = hostData.path("roomId").asString();
        String hostToken = hostData.path("token").asString();
        String guestToken = joinRoom(roomName, GUEST_NAME).path("token").asString();
        startGame(gameId, hostToken);

        String currentName = readGameState(gameId, hostToken).path("currentPlayerName").asString();
        boolean hostActsFirst = HOST_NAME.equals(currentName);
        String currentToken = hostActsFirst ? hostToken : guestToken;
        String otherToken = hostActsFirst ? guestToken : hostToken;
        String currentPlayerName = hostActsFirst ? HOST_NAME : GUEST_NAME;
        String otherPlayerName = hostActsFirst ? GUEST_NAME : HOST_NAME;

        WebSocketStompClient stompClient = createStompClient();
        PlayerActor current = new PlayerActor(currentPlayerName, currentToken, connectSession(stompClient, currentToken));
        StompSession otherSession = connectSession(stompClient, otherToken);
        PlayerActor other = new PlayerActor(otherPlayerName, otherToken, otherSession);
        awaitPlayerStatus(gameId, currentToken, currentPlayerName, "ACTIVE");
        awaitPlayerStatus(gameId, currentToken, otherPlayerName, "ACTIVE");

        performActionByCurrentPlayer(
                gameId,
                new PlayerActionRequest(PlayerAction.CALL, 0),
                current,
                other,
                state -> "PRE_FLOP".equals(state.path("phase").asString())
                        && otherPlayerName.equals(state.path("currentPlayerName").asString()));

        // The other player subscribes to the game topic, then drops; the broker must forget that subscription.
        recordGameBroadcasts(otherSession, gameId);
        disconnectSession(otherSession);
        awaitPlayerStatus(gameId, currentToken, otherPlayerName, "DISCONNECTED");
        awaitNoBrokerSubscription("/game/" + gameId);

        StompSession reconnectedSession = connectSession(stompClient, otherToken);
        awaitPlayerStatus(gameId, currentToken, otherPlayerName, "ACTIVE");
        List<JsonNode> broadcasts = recordGameBroadcasts(reconnectedSession, gameId);
        long revisionBeforeCheck = readGameState(gameId, currentToken).path("stateRevision").asLong();

        reconnectedSession.send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));

        JsonNode flopBroadcast = awaitBroadcast(broadcasts, state -> "FLOP".equals(state.path("phase").asString()));
        assertThat(flopBroadcast.path("communityCards")).hasSize(3);
        assertThat(flopBroadcast.path("pot").asInt()).isEqualTo(40);
        assertThat(flopBroadcast.path("stateRevision").asLong()).isGreaterThan(revisionBeforeCheck);

        JsonNode flopState = await().until(
                () -> readGameState(gameId, currentToken),
                state -> "FLOP".equals(state.path("phase").asString()));
        assertThat(flopState.path("stateRevision").asLong()).isGreaterThan(revisionBeforeCheck);
        assertThat(flopState.path("communityCards")).hasSize(3);
        assertThat(flopState.path("pot").asInt()).isEqualTo(40);
        for (JsonNode player : flopState.path("players")) {
            assertThat(player.path("status").asString()).isEqualTo("ACTIVE");
            assertThat(isAbsent(player.path("disconnectDeadlineEpochMs")))
                    .as("%s must have no reconnect deadline", player.path("name").asString())
                    .isTrue();
        }
    }
}
