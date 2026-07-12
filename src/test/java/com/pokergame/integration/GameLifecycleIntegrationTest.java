package com.pokergame.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.enums.PlayerAction;
import com.pokergame.integration.support.AbstractIntegrationTestSupport;
import com.pokergame.security.JwtService;
import com.pokergame.service.GameLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests game lifecycle integration behavior. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Game lifecycle integration")
class GameLifecycleIntegrationTest extends AbstractIntegrationTestSupport {

    @Autowired
    private GameLifecycleService gameLifecycleService;

    @Autowired
    private JwtService jwtService;

    /** Groups test scenarios for start game. */
    @Nested
    @DisplayName("starting games")
    class StartGame {

        /**
         * Protects the contract that the system should allow the host to start a game when at least two players have joined.
         */
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

        /**
         * Protects the contract that the system should reject start game requests from non-host players.
         */
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

        /**
         * Protects the contract that the system should reject start game requests when only one player is present.
         */
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

    /** Groups test scenarios for state endpoints. */
    @Nested
    @DisplayName("state endpoints")
    class StateEndpoints {

        /**
         * Protects the contract that the system should return the public game state to an active player.
         */
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

        /**
         * Protects the contract that the system should reject public game state requests from players outside the game.
         */
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
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this game.");
        }

        /**
         * Protects the contract that the system should return the private state with hole cards to an active player.
         */
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

        /**
         * Protects the contract that the system should reject private state requests from players outside the game.
         */
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
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this game.");
        }
    }

    /** Groups test scenarios for leave game. */
    @Nested
    @DisplayName("leaving games")
    class LeaveGame {

        /**
         * Protects the contract that the system should reject game leave requests without a token.
         */
        @Test
        @DisplayName("should reject game leave requests without a token")
        void givenMissingToken_whenLeaveGame_thenReturnForbidden() {
            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/some-game-id/leave")
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        /**
         * Protects the contract that the system should return forbidden for an unknown game id due to token mismatch.
         */
        @Test
        @DisplayName("should return forbidden for an unknown game id due to token mismatch")
        void givenUnknownGame_whenLeaveGame_thenReturnForbidden() throws Exception {
            String hostToken = createRoom(uniqueName("UnknownGameRoom"), "HostUnknownGame", 6).path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/game/" + uniqueName("missing-game") + "/leave")
                    .header("Authorization", "Bearer " + hostToken)
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this game.");
        }

        /**
         * Protects the contract that the system should allow a player to leave an active game.
         */
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

        /**
         * Protects the contract that the system should keep a three-player game responsive after a non-current player leaves.
         */
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

            var stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession thirdSession = connectSession(stompClient, thirdPlayerToken);

            String firstActingToken;
            String secondActingToken;
            try {
                JsonNode beforeFirstAction = readGameState(gameId, hostToken);
                String firstCurrentPlayer = beforeFirstAction.path("currentPlayerName").asText();
                String firstPhase = beforeFirstAction.path("phase").asText();
                firstActingToken = performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.CALL, null),
                        hostToken,
                        thirdPlayerToken,
                        hostSession,
                        thirdSession,
                        state -> !firstCurrentPlayer.equals(state.path("currentPlayerName").asText())
                                || !firstPhase.equals(state.path("phase").asText()));

                JsonNode beforeSecondAction = readGameState(gameId, hostToken);
                String secondCurrentPlayer = beforeSecondAction.path("currentPlayerName").asText();
                secondActingToken = performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.FOLD, null),
                        hostToken,
                        thirdPlayerToken,
                        hostSession,
                        thirdSession,
                        state -> "SHOWDOWN".equals(state.path("phase").asText())
                                || playerHasFolded(state, secondCurrentPlayer));
            } finally {
                hostSession.disconnect();
                thirdSession.disconnect();
                stompClient.stop();
            }

            assertThat(leaveBody).contains("Successfully left game");
            assertThat(firstActingToken).isIn(hostToken, thirdPlayerToken);
            assertThat(secondActingToken).isIn(hostToken, thirdPlayerToken);
        }
    }

    /** Groups test scenarios for game progression. */
    @Nested
    @DisplayName("game progression")
    class GameProgression {

        /**
         * Protects the contract that the system should advance through multiple streets when players act through the public action API.
         */
        @Test
        @DisplayName("should advance through multiple streets when players act through the public action API")
        void givenTwoPlayerGame_whenPlayersActAcrossRounds_thenGameAdvancesToTheTurn() throws Exception {
            String roomName = uniqueName("FullRoundRoom");
            JsonNode hostData = createRoom(roomName, "RoundHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "RoundGuest").path("token").asText();
            startGame(gameId, hostToken);

            var stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);

            try {
                performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.CALL, null),
                        hostToken,
                        guestToken,
                        hostSession,
                        guestSession,
                        state -> "PRE_FLOP".equals(state.path("phase").asText())
                                && "RoundHost".equals(state.path("currentPlayerName").asText()));

                performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.CHECK, null),
                        hostToken,
                        guestToken,
                        hostSession,
                        guestSession,
                        state -> "FLOP".equals(state.path("phase").asText())
                                && state.path("communityCards").size() == 3
                                && "RoundHost".equals(state.path("currentPlayerName").asText()));

                performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.CHECK, null),
                        hostToken,
                        guestToken,
                        hostSession,
                        guestSession,
                        state -> "FLOP".equals(state.path("phase").asText())
                                && "RoundGuest".equals(state.path("currentPlayerName").asText()));

                performActionByCurrentPlayer(
                        gameId,
                        new PlayerActionRequest(PlayerAction.CHECK, null),
                        hostToken,
                        guestToken,
                        hostSession,
                        guestSession,
                        state -> "TURN".equals(state.path("phase").asText())
                                && state.path("communityCards").size() == 4);
            } finally {
                hostSession.disconnect();
                guestSession.disconnect();
                stompClient.stop();
            }
        }
    }

    /** Groups test scenarios for cleanup and claim win. */
    @Nested
    @DisplayName("cleanup and claim win")
    class CleanupAndClaimWin {

        /**
         * Protects the contract that the system should eventually destroy the room when one player remains after a game leave.
         */
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

        /**
         * Protects the contract that the system should allow claim win when every other non-out player is disconnected.
         */
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

        /**
         * Protects the contract that the system should reject stale claim win requests once an opponent has reconnected.
         */
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

    /** Groups test scenarios for ready countdown flow. */
    @Nested
    @DisplayName("ready countdown flow")
    class ReadyCountdownFlow {

        /**
         * Protects the contract that the system should start new hand when all players are ready.
         */
        @Test
        @DisplayName("should start new hand when all players are ready")
        void givenShowdown_whenAllPlayersReady_thenStartNewHand() throws Exception {
            String roomName = uniqueName("ReadyFlowRoom");
            JsonNode hostData = createRoom(roomName, "ReadyHost", 2);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "ReadyGuest").path("token").asText();
            startGame(gameId, hostToken);

            var stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);

            try {
                // Reach showdown by checking down all streets
                // Pre-flop
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CALL, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                // Flop
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                // Turn
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                // River
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, s -> true);
                performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, null), hostToken, guestToken, hostSession, guestSession, 
                    state -> "SHOWDOWN".equals(state.path("phase").asText()));

                // Verify countdown active
                JsonNode showdownState = readGameState(gameId, hostToken);
                assertThat(showdownState.path("isReadyCountdownActive").asBoolean()).isTrue();

                // Mark both ready
                hostSession.send("/app/" + gameId + "/ready", "{}");
                guestSession.send("/app/" + gameId + "/ready", "{}");

                // Wait for new hand (PRE_FLOP)
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    JsonNode nextHandState = readGameState(gameId, hostToken);
                    assertThat(nextHandState.path("phase").asText()).isEqualTo("PRE_FLOP");
                    // Pot should be reset to blinds (10 + 20 = 30)
                    assertThat(nextHandState.path("pot").asInt()).isEqualTo(30);
                });

            } finally {
                hostSession.disconnect();
                guestSession.disconnect();
                stompClient.stop();
            }
        }
    }

    /**
     * Performs action by current player for the test.
     * @param gameId game ID supplied to the fixture
     * @param request request supplied to the fixture
     * @param tokenA token a supplied to the fixture
     * @param tokenB token b supplied to the fixture
     * @param sessionA session a supplied to the fixture
     * @param sessionB session b supplied to the fixture
     * @param expectedState expected state supplied to the fixture
     * @return public state observed after the action
     */
    private String performActionByCurrentPlayer(
            String gameId,
            PlayerActionRequest request,
            String tokenA,
            String tokenB,
            StompSession sessionA,
            StompSession sessionB,
            Predicate<JsonNode> expectedState) throws Exception {
        JsonNode state = readGameState(gameId, tokenA);
        String currentPlayerName = state.path("currentPlayerName").asText();
        // jwtService.extractPlayerName now returns just the playerName (subject)
        boolean tokenAIsCurrentPlayer = jwtService.extractPlayerName(tokenA).equals(currentPlayerName);
        String actingToken = tokenAIsCurrentPlayer ? tokenA : tokenB;
        StompSession actingSession = tokenAIsCurrentPlayer ? sessionA : sessionB;

        actingSession.send("/app/" + gameId + "/action", request);

        await().atMost(DEFAULT_TIMEOUT).untilAsserted(() -> {
            JsonNode nextState = readGameState(gameId, tokenA);
            assertThat(expectedState.test(nextState))
                    .as("Expected game state after %s but saw phase=%s currentPlayer=%s communityCards=%s",
                            request.action(),
                            nextState.path("phase").asText(),
                            nextState.path("currentPlayerName").asText(),
                            nextState.path("communityCards").size())
                    .isTrue();
        });

        return actingToken;
    }

    /**
     * Reports whether player has folded.
     * @param state state supplied to the fixture
     * @param playerName player name supplied to the fixture
     * @return whether player has folded
     */
    private boolean playerHasFolded(JsonNode state, String playerName) {
        for (JsonNode player : state.path("players")) {
            if (playerName.equals(player.path("name").asText())) {
                return player.path("hasFolded").asBoolean();
            }
        }
        return false;
    }

}
