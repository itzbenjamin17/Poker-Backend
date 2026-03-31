package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.client.HttpClientErrorException;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Game lifecycle integration")
class GameLifecycleIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private JwtService jwtService;

    @Nested
    @DisplayName("starting games")
    class StartGame {

        @Test
        @DisplayName("should allow the host to start a game when at least two players have joined")
        void givenTwoPlayersAndHostToken_whenStartGame_thenReturnGameId() throws Exception {
            String roomName = uniqueName("StartGameRoom");
            JsonNode hostData = createRoom(roomName, "HostStart", 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "SecondStartPlayer");

            JsonNode response = startGame(roomId, hostToken);

            assertThat(response.path("message").asText()).isEqualTo("Game started successfully");
            assertThat(response.path("data").asText()).isEqualTo(roomId);
        }

        @Test
        @DisplayName("should reject start game requests from non-host players")
        void givenNonHostToken_whenStartGame_thenReturnForbidden() throws Exception {
            String roomName = uniqueName("NonHostStartRoom");
            JsonNode hostData = createRoom(roomName, "HostOnly", 6);
            String roomId = hostData.path("roomId").asText();
            String nonHostToken = joinRoom(roomName, "NonHostUser").path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + nonHostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Only the room host can start the game");
        }

        @Test
        @DisplayName("should reject start game requests when only one player is present")
        void givenSinglePlayerRoom_whenStartGame_thenReturnForbidden() throws Exception {
            JsonNode hostData = createRoom(uniqueName("SinglePlayerStartRoom"), "SoloHost", 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/" + roomId + "/start-game")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Need at least 2 players to start game");
        }
    }

    @Nested
    @DisplayName("state endpoints")
    class StateEndpoints {

        @Test
        @DisplayName("should return the public game state to an active player")
        void givenActivePlayer_whenGetGameState_thenReturnCurrentState() throws Exception {
            String roomName = uniqueName("StateSnapshotRoom");
            JsonNode hostData = createRoom(roomName, "StateHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "StateGuest");
            startGame(gameId, hostToken);

            JsonNode state = readGameState(gameId, hostToken);

            assertThat(state.path("phase").asText()).isEqualTo("PRE_FLOP");
            assertThat(state.path("players").isArray()).isTrue();
            assertThat(state.path("players").size()).isEqualTo(2);
        }

        @Test
        @DisplayName("should reject public game state requests from players outside the game")
        void givenAuthenticatedOutsider_whenGetGameState_thenReturnForbidden() throws Exception {
            String roomName = uniqueName("StateForbiddenTarget");
            JsonNode hostData = createRoom(roomName, "StateTargetHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "StateTargetGuest");
            startGame(gameId, hostToken);

            String outsiderToken = createRoom(uniqueName("StateForbiddenOutsider"), "StateOutsider", 6)
                    .path("token")
                    .asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/" + gameId + "/state")
                    .header("Authorization", "Bearer " + outsiderToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("no longer part of this game");
        }

        @Test
        @DisplayName("should return the private state with hole cards to an active player")
        void givenActivePlayer_whenGetPrivateState_thenReturnHoleCards() throws Exception {
            String roomName = uniqueName("PrivateStateRoom");
            JsonNode hostData = createRoom(roomName, "PrivateStateHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "PrivateStateGuest");
            startGame(gameId, hostToken);

            JsonNode privateState = objectMapper.readTree(restClient.get()
                    .uri("/api/game/" + gameId + "/private-state")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(privateState.path("playerId").asText()).isNotBlank();
            assertThat(privateState.path("holeCards").size()).isEqualTo(2);
        }

        @Test
        @DisplayName("should reject private state requests from players outside the game")
        void givenAuthenticatedOutsider_whenGetPrivateState_thenReturnForbidden() throws Exception {
            String roomName = uniqueName("PrivateStateForbiddenTarget");
            JsonNode hostData = createRoom(roomName, "PrivateStateTargetHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "PrivateStateTargetGuest");
            startGame(gameId, hostToken);

            String outsiderToken = createRoom(uniqueName("PrivateStateForbiddenOutsider"), "PrivateStateOutsider", 6)
                    .path("token")
                    .asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.get()
                    .uri("/api/game/" + gameId + "/private-state")
                    .header("Authorization", "Bearer " + outsiderToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("no longer part of this game");
        }
    }

    @Nested
    @DisplayName("leaving games")
    class LeaveGame {

        @Test
        @DisplayName("should reject game leave requests without a token")
        void givenMissingToken_whenLeaveGame_thenReturnForbidden() {
            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/some-game-id/leave")
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("should return not found for an unknown game id")
        void givenUnknownGame_whenLeaveGame_thenReturnNotFound() throws Exception {
            String hostToken = createRoom(uniqueName("UnknownGameRoom"), "HostUnknownGame", 6).path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/" + uniqueName("missing-game") + "/leave")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.getResponseBodyAsString()).contains("Game not found");
        }

        @Test
        @DisplayName("should allow a player to leave an active game")
        void givenStartedGame_whenPlayerLeaves_thenReturnSuccessResponse() throws Exception {
            String roomName = uniqueName("LeaveGameRoom");
            JsonNode hostData = createRoom(roomName, "HostLeaveGame", 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String secondPlayerToken = joinRoom(roomName, "SecondLeaveGame").path("token").asText();
            startGame(roomId, hostToken);

            String leaveBody = restClient.post()
                    .uri("/api/game/" + roomId + "/leave")
                    .header("Authorization", "Bearer " + secondPlayerToken)
                    .retrieve()
                    .body(String.class);

            assertThat(leaveBody).contains("Successfully left game");
        }

        @Test
        @DisplayName("should keep a three-player game responsive after a non-current player leaves")
        void givenThreePlayerGame_whenNonCurrentPlayerLeaves_thenRemainingPlayersCanStillAct() throws Exception {
            String roomName = uniqueName("LeaveThreePlayerRoom");
            JsonNode hostData = createRoom(roomName, "HostLeaveThree", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String secondPlayerToken = joinRoom(roomName, "SecondLeaveThree").path("token").asText();
            String thirdPlayerToken = joinRoom(roomName, "ThirdLeaveThree").path("token").asText();
            startGame(gameId, hostToken);

            String leaveBody = restClient.post()
                    .uri("/api/game/" + gameId + "/leave")
                    .header("Authorization", "Bearer " + secondPlayerToken)
                    .retrieve()
                    .body(String.class);

            String firstActingToken = performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CALL, null),
                    hostToken,
                    thirdPlayerToken);
            String secondActingToken = performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.FOLD, null),
                    hostToken,
                    thirdPlayerToken);

            assertThat(leaveBody).contains("Successfully left game");
            assertThat(firstActingToken).isIn(hostToken, thirdPlayerToken);
            assertThat(secondActingToken).isIn(hostToken, thirdPlayerToken);
        }
    }

    @Nested
    @DisplayName("game progression")
    class GameProgression {

        @Test
        @DisplayName("should advance through multiple streets when players act through the public action API")
        void givenTwoPlayerGame_whenPlayersActAcrossRounds_thenGameAdvancesToTheTurn() throws Exception {
            String roomName = uniqueName("FullRoundRoom");
            JsonNode hostData = createRoom(roomName, "RoundHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "RoundGuest").path("token").asText();
            startGame(gameId, hostToken);

            performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CALL, null), hostToken, guestToken);
            performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
            performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);
            performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken);

            JsonNode state = readGameState(gameId, hostToken);

            assertThat(state.path("phase").asText()).isEqualTo("TURN");
        }
    }

    @Nested
    @DisplayName("cleanup and claim win")
    class CleanupAndClaimWin {

        @Test
        @Tag("slow")
        @DisplayName("should eventually destroy the room when one player remains after a game leave")
        void givenOnePlayerRemaining_whenGameEnds_thenRoomIsDestroyed() throws Exception {
            String roomName = uniqueName("EndGameCleanupRoom");
            JsonNode hostData = createRoom(roomName, "EndHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "EndGuest").path("token").asText();
            startGame(gameId, hostToken);

            String leaveBody = restClient.post()
                    .uri("/api/game/" + gameId + "/leave")
                    .header("Authorization", "Bearer " + guestToken)
                    .retrieve()
                    .body(String.class);

            assertThat(leaveBody).contains("Successfully left game");
            awaitRoomDestruction(gameId, hostToken, Duration.ofSeconds(12));
        }

        @Test
        @Tag("slow")
        @DisplayName("should allow claim win when every other non-out player is disconnected")
        void givenDisconnectedOpponents_whenClaimWin_thenReturnSuccessAndCleanupRoom() throws Exception {
            String roomName = uniqueName("ClaimWinRoom");
            JsonNode hostData = createRoom(roomName, "ClaimHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "ClaimGuest");
            startGame(gameId, hostToken);

            gameLifecycleService.markPlayerDisconnected(gameId, "ClaimGuest", System.currentTimeMillis() + 120_000);

            JsonNode response = objectMapper.readTree(restClient.post()
                    .uri("/api/game/" + gameId + "/claim-win")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(response.path("message").asText()).isEqualTo("Win claimed successfully");
            awaitRoomDestruction(gameId, hostToken, Duration.ofSeconds(12));
        }

        @Test
        @DisplayName("should reject stale claim win requests once an opponent has reconnected")
        void givenReconnectedOpponent_whenClaimWin_thenReturnForbidden() throws Exception {
            String roomName = uniqueName("ClaimRejectRoom");
            JsonNode hostData = createRoom(roomName, "ClaimRejectHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            joinRoom(roomName, "ClaimRejectGuest");
            startGame(gameId, hostToken);

            gameLifecycleService.markPlayerDisconnected(gameId, "ClaimRejectGuest", System.currentTimeMillis() + 120_000);
            gameLifecycleService.markPlayerReconnected(gameId, "ClaimRejectGuest");

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/" + gameId + "/claim-win")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString().toLowerCase()).contains("claim");
        }
    }

    private String performActionByCurrentPlayer(
            String gameId,
            PlayerActionRequest request,
            String tokenA,
            String tokenB) throws Exception {
        JsonNode state = readGameState(gameId, tokenA);
        String currentPlayerName = state.path("currentPlayerName").asText();
        String actingToken = jwtService.extractPlayerName(tokenA).equals(currentPlayerName) ? tokenA : tokenB;

        var stompClient = createStompClient();
        StompSession session = connectSession(stompClient, actingToken);
        CompletableFuture<Object> nextStateFuture = new CompletableFuture<>();

        session.subscribe("/game/" + gameId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return Object.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (!nextStateFuture.isDone()) {
                    nextStateFuture.complete(payload);
                }
            }
        });

        session.send("/app/" + gameId + "/action", request);
        nextStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        session.disconnect();
        stompClient.stop();
        return actingToken;
    }

}
