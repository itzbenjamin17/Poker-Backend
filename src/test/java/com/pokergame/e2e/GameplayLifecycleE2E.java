package com.pokergame.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.PlayerFeedbackResponse;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end black-box REST and WebSocket STOMP tests covering game start, state privacy,
 * leaving active games, multi-street progression, disconnect claim-win, cleanup, and ready countdowns.
 */
@Tag("e2e")
@DisplayName("Gameplay lifecycle E2E")
class GameplayLifecycleE2E extends AbstractE2ESupport {

    private static final String HOST_NAME = "ActionHost";
    private static final String OTHER_NAME = "ActionOther";
    private static final String WRONG_GAME_DETAIL = "Token is not valid for this game.";
    private static final int TOTAL_CHIPS = 2000;
    private static final int BLINDS_POT = 30;

    @Nested
    @DisplayName("starting games")
    class StartGame {

        @Test
        @DisplayName("should reject start game requests when only one player is present")
        void givenSinglePlayerRoom_whenStartGame_thenReturnForbiddenAndKeepRoomInLobby() {
            JsonNode hostData = createRoom(uniqueName("SinglePlayerStartRoom"), "SoloHost", 6);
            String roomId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();

            HttpClientErrorException rejection = expectRejection(() -> startGame(roomId, hostToken));

            assertProblem(rejection, HttpStatus.FORBIDDEN,
                    "Need at least 2 players to start game. Please wait for more players to join.");
            assertThat(getRoomData(roomId, hostToken).path("gameStarted").asBoolean()).isFalse();
        }
    }

    @Nested
    @DisplayName("state privacy")
    class StatePrivacy {

        @Test
        @DisplayName("should hide hole cards publicly, give each player their own two cards, and refuse outsiders")
        void givenStartedGame_whenPlayersAndOutsiderReadState_thenOnlyOwnHoleCardsAreVisible() {
            String roomName = uniqueName("PrivacyRoom");
            JsonNode hostData = createRoom(roomName, "PrivacyHost", 6);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "PrivacyGuest").path("token").asString();
            startGame(gameId, hostToken);

            JsonNode publicState = readGameState(gameId, hostToken);
            assertThat(publicState.path("phase").asString()).isEqualTo("PRE_FLOP");
            assertThat(publicState.path("players")).hasSize(2);
            for (String viewerToken : List.of(hostToken, guestToken)) {
                for (JsonNode player : readGameState(gameId, viewerToken).path("players")) {
                    assertThat(isAbsent(player.path("holeCards")))
                            .as("public state must not reveal hole cards of %s", player.path("name").asString())
                            .isTrue();
                }
            }

            JsonNode hostPrivate = readPrivateState(gameId, hostToken);
            JsonNode guestPrivate = readPrivateState(gameId, guestToken);
            assertThat(hostPrivate.path("playerId").asString())
                    .isEqualTo(findPlayer(publicState, "PrivacyHost").path("id").asString());
            assertThat(guestPrivate.path("playerId").asString())
                    .isEqualTo(findPlayer(publicState, "PrivacyGuest").path("id").asString());
            assertThat(hostPrivate.path("holeCards")).hasSize(2);
            assertThat(guestPrivate.path("holeCards")).hasSize(2);
            Set<String> dealtCards = new HashSet<>();
            hostPrivate.path("holeCards").forEach(card -> dealtCards.add(card.asString()));
            guestPrivate.path("holeCards").forEach(card -> dealtCards.add(card.asString()));
            assertThat(dealtCards).as("all four dealt hole cards are distinct").hasSize(4);

            String outsiderToken = createRoom(uniqueName("PrivacyOutsiderRoom"), "PrivacyOutsider", 6)
                    .path("token")
                    .asString();
            assertProblem(expectRejection(() -> readGameState(gameId, outsiderToken)),
                    HttpStatus.FORBIDDEN, WRONG_GAME_DETAIL);
            assertProblem(expectRejection(() -> readPrivateState(gameId, outsiderToken)),
                    HttpStatus.FORBIDDEN, WRONG_GAME_DETAIL);
        }
    }

    @Nested
    @DisplayName("leaving games")
    class LeaveGame {

        @Test
        @DisplayName("should remove a non-current player who leaves a three-player game and let the rest keep playing")
        void givenThreePlayerGame_whenNonCurrentPlayerLeaves_thenRemainingPlayersCanStillAct() throws Exception {
            String roomName = uniqueName("LeaveThreePlayerRoom");
            JsonNode hostData = createRoom(roomName, "HostLeaveThree", 6);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String secondToken = joinRoom(roomName, "SecondLeaveThree").path("token").asString();
            String thirdToken = joinRoom(roomName, "ThirdLeaveThree").path("token").asString();
            startGame(gameId, hostToken);

            String initialCurrent = readGameState(gameId, hostToken).path("currentPlayerName").asString();
            boolean secondIsCurrent = "SecondLeaveThree".equals(initialCurrent);
            String leavingName = secondIsCurrent ? "ThirdLeaveThree" : "SecondLeaveThree";
            String leavingToken = secondIsCurrent ? thirdToken : secondToken;
            String remainingGuestName = secondIsCurrent ? "SecondLeaveThree" : "ThirdLeaveThree";
            String remainingGuestToken = secondIsCurrent ? secondToken : thirdToken;

            int leaveStatus = leaveRoom(gameId, leavingToken);

            assertThat(leaveStatus).isEqualTo(204);
            JsonNode afterLeave = readGameState(gameId, hostToken);
            assertThat(afterLeave.path("players")).hasSize(2);
            assertThat(afterLeave.path("players"))
                    .extracting(player -> player.path("name").asString())
                    .containsExactlyInAnyOrder("HostLeaveThree", remainingGuestName)
                    .doesNotContain(leavingName);

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor hostActor = new PlayerActor("HostLeaveThree", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guestActor = new PlayerActor(
                    remainingGuestName, remainingGuestToken, connectSession(stompClient, remainingGuestToken));
            awaitActive(gameId, hostToken, "HostLeaveThree", remainingGuestName);

            JsonNode beforeFirstAction = readGameState(gameId, hostToken);
            String firstCurrentPlayer = beforeFirstAction.path("currentPlayerName").asString();
            String firstPhase = beforeFirstAction.path("phase").asString();
            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CALL, 0),
                    hostActor,
                    guestActor,
                    state -> !firstCurrentPlayer.equals(state.path("currentPlayerName").asString())
                            || !firstPhase.equals(state.path("phase").asString()));

            String folder = readGameState(gameId, hostToken).path("currentPlayerName").asString();
            String winner = "HostLeaveThree".equals(folder) ? remainingGuestName : "HostLeaveThree";
            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.FOLD, 0),
                    hostActor,
                    guestActor,
                    state -> "SHOWDOWN".equals(state.path("phase").asString()));

            JsonNode afterFold = readGameState(gameId, hostToken);
            assertThat(playerHasFolded(afterFold, folder)).isTrue();
            assertThat(afterFold.path("winners")).extracting(JsonNode::asString).containsExactly(winner);
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
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "RoundGuest").path("token").asString();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor host = new PlayerActor("RoundHost", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guest = new PlayerActor("RoundGuest", guestToken, connectSession(stompClient, guestToken));
            awaitActive(gameId, hostToken, "RoundHost", "RoundGuest");

            String firstToAct = readGameState(gameId, hostToken).path("currentPlayerName").asString();
            String secondToAct = "RoundHost".equals(firstToAct) ? "RoundGuest" : "RoundHost";

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CALL, 0),
                    host,
                    guest,
                    state -> "PRE_FLOP".equals(state.path("phase").asString())
                            && secondToAct.equals(state.path("currentPlayerName").asString()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "FLOP".equals(state.path("phase").asString())
                            && state.path("communityCards").size() == 3
                            && secondToAct.equals(state.path("currentPlayerName").asString()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "FLOP".equals(state.path("phase").asString())
                            && firstToAct.equals(state.path("currentPlayerName").asString()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "TURN".equals(state.path("phase").asString())
                            && state.path("communityCards").size() == 4);

            JsonNode turnState = readGameState(gameId, hostToken);
            assertThat(turnState.path("pot").asInt()).isEqualTo(40);
        }
    }

    @Nested
    @DisplayName("cleanup and claim win")
    class CleanupAndClaimWin {

        @Test
        @DisplayName("should eventually destroy the room when one player remains after a game leave")
        void givenOnePlayerRemaining_whenGameEnds_thenRoomIsDestroyed() throws Exception {
            String roomName = uniqueName("EndGameCleanupRoom");
            JsonNode hostData = createRoom(roomName, "EndHost", 6);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "EndGuest").path("token").asString();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            connectSession(stompClient, guestToken);

            int leaveStatus = leaveRoom(gameId, guestToken);

            assertThat(leaveStatus).isEqualTo(204);
            awaitRoomDestruction(gameId, hostToken);
        }

        @Test
        @DisplayName("should allow claim win when every other non-out player is disconnected")
        void givenDisconnectedOpponents_whenClaimWin_thenReturnFinalResultAndCleanupRoom() throws Exception {
            String roomName = uniqueName("ClaimWinRoom");
            JsonNode hostData = createRoom(roomName, "ClaimHost", 6);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "ClaimGuest").path("token").asString();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);
            awaitActive(gameId, hostToken, "ClaimHost", "ClaimGuest");
            disconnectSession(guestSession);
            awaitPlayerStatus(gameId, hostToken, "ClaimGuest", "DISCONNECTED");

            JsonNode claim = claimWin(gameId, hostToken);

            assertThat(claim.path("gameEnd").path("winner").asString()).isEqualTo("ClaimHost");
            assertThat(claim.path("gameEnd").path("isForfeit").asBoolean()).isTrue();
            JsonNode review = readGameReview(gameId, hostToken);
            assertThat(review.path("gameEnd")).isEqualTo(claim.path("gameEnd"));
            awaitRoomDestruction(gameId, hostToken);
        }

        @Test
        @DisplayName("should reject stale claim win requests once an opponent has reconnected")
        void givenReconnectedOpponent_whenClaimWin_thenReturnForbiddenAndKeepGameRunning() throws Exception {
            String roomName = uniqueName("ClaimRejectRoom");
            JsonNode hostData = createRoom(roomName, "ClaimRejectHost", 6);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "ClaimRejectGuest").path("token").asString();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);
            awaitActive(gameId, hostToken, "ClaimRejectHost", "ClaimRejectGuest");
            disconnectSession(guestSession);
            awaitPlayerStatus(gameId, hostToken, "ClaimRejectGuest", "DISCONNECTED");

            connectSession(stompClient, guestToken);
            awaitPlayerStatus(gameId, hostToken, "ClaimRejectGuest", "ACTIVE");

            HttpClientErrorException rejection = expectRejection(() -> claimWin(gameId, hostToken));

            assertProblem(rejection, HttpStatus.FORBIDDEN, "Cannot claim win right now.");
            JsonNode state = readGameState(gameId, hostToken);
            assertThat(state.path("phase").asString()).isEqualTo("PRE_FLOP");
            assertThat(isAbsent(state.path("gameEnd"))).isTrue();
        }
    }

    @Nested
    @DisplayName("WebSocket gameplay and ready gates")
    class WebSocketGameplay {

        @Test
        @DisplayName("should broadcast a public game update when a valid action is sent")
        void givenValidAction_whenSendOverWebSocket_thenBroadcastUpdatedGameState() throws Exception {
            String roomName = uniqueName("WsActionRoom");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asString();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);
            List<JsonNode> broadcasts = recordGameBroadcasts(hostSession, roomId);

            startGame(roomId, hostToken);

            JsonNode initialState = awaitBroadcast(broadcasts, GameplayLifecycleE2E::isFreshHand);
            awaitActive(roomId, hostToken, HOST_NAME, OTHER_NAME);
            String folder = initialState.path("currentPlayerName").asString();
            String winner = HOST_NAME.equals(folder) ? OTHER_NAME : HOST_NAME;
            StompSession actingSession = HOST_NAME.equals(folder) ? hostSession : otherSession;

            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            JsonNode afterFold = awaitBroadcast(broadcasts, state ->
                    state.path("stateRevision").asLong() > initialState.path("stateRevision").asLong()
                            && playerHasFolded(state, folder));
            assertThat(afterFold.path("handNumber").asLong()).isEqualTo(initialState.path("handNumber").asLong());
            assertThat(afterFold.path("phase").asString()).isEqualTo("SHOWDOWN");
            assertThat(afterFold.path("winners")).extracting(JsonNode::asString).containsExactly(winner);
        }

        @Test
        @DisplayName("should advance immediately when all eligible players mark READY")
        void givenReadyGate_whenAllEligiblePlayersReady_thenAdvanceWithoutWaitingForTimeout() throws Exception {
            String roomName = uniqueName("WsAllReady");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asString();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);
            List<JsonNode> broadcasts = recordGameBroadcasts(hostSession, roomId);

            startGame(roomId, hostToken);

            JsonNode initialState = awaitBroadcast(broadcasts, GameplayLifecycleE2E::isFreshHand);
            awaitActive(roomId, hostToken, HOST_NAME, OTHER_NAME);
            StompSession actingSession = HOST_NAME.equals(initialState.path("currentPlayerName").asString())
                    ? hostSession
                    : otherSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            JsonNode readyGateState = awaitBroadcast(broadcasts, GameplayLifecycleE2E::isReadyGate);
            assertThat(readyGateState.path("phase").asString()).isEqualTo("SHOWDOWN");
            assertThat(readyGateState.path("readyCountdownDeadlineEpochMs").asLong()).isPositive();

            hostSession.send("/app/" + roomId + "/ready", "");
            otherSession.send("/app/" + roomId + "/ready", "");

            long firstHandNumber = initialState.path("handNumber").asLong();
            JsonNode nextHand = awaitBroadcast(broadcasts, state ->
                    state.path("handNumber").asLong() > firstHandNumber && isFreshHand(state));
            assertThat(nextHand.path("handNumber").asLong()).isEqualTo(firstHandNumber + 1);
            assertThat(nextHand.path("phase").asString()).isEqualTo("PRE_FLOP");
            assertThat(nextHand.path("pot").asInt()).isEqualTo(BLINDS_POT);
            assertThat(nextHand.path("serverTimeEpochMs").asLong())
                    .as("the next hand must start before the ready countdown would have expired")
                    .isLessThan(readyGateState.path("readyCountdownDeadlineEpochMs").asLong());
        }

        @Test
        @DisplayName("should auto-advance on ready timeout when some players stay unready")
        void givenReadyGate_whenOnePlayerStaysUnready_thenTimeoutForcesAdvance() throws Exception {
            String roomName = uniqueName("WsReadyTimeout");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asString();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);
            List<JsonNode> broadcasts = recordGameBroadcasts(hostSession, roomId);

            startGame(roomId, hostToken);

            JsonNode initialState = awaitBroadcast(broadcasts, GameplayLifecycleE2E::isFreshHand);
            awaitActive(roomId, hostToken, HOST_NAME, OTHER_NAME);
            StompSession actingSession = HOST_NAME.equals(initialState.path("currentPlayerName").asString())
                    ? hostSession
                    : otherSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            JsonNode readyGateState = awaitBroadcast(broadcasts, GameplayLifecycleE2E::isReadyGate);
            assertThat(readyGateState.path("phase").asString()).isEqualTo("SHOWDOWN");

            hostSession.send("/app/" + roomId + "/ready", "");

            awaitBroadcast(broadcasts, state -> isReadyGate(state)
                    && isReadyForNextHand(state, HOST_NAME)
                    && !isReadyForNextHand(state, OTHER_NAME));

            long firstHandNumber = initialState.path("handNumber").asLong();
            JsonNode nextHand = awaitBroadcast(broadcasts, state ->
                    state.path("handNumber").asLong() > firstHandNumber && isFreshHand(state));
            assertThat(nextHand.path("handNumber").asLong()).isEqualTo(firstHandNumber + 1);
            assertThat(nextHand.path("pot").asInt()).isEqualTo(BLINDS_POT);
            assertThat(nextHand.path("serverTimeEpochMs").asLong())
                    .as("with one player unready the next hand must wait for the countdown deadline")
                    .isGreaterThanOrEqualTo(readyGateState.path("readyCountdownDeadlineEpochMs").asLong() - 250);
        }

        @Test
        @DisplayName("should reject a stray action after showdown and conserve total chips")
        void givenShowdown_whenStrayActionSubmitted_thenRejectAndConserveChips() throws Exception {
            String roomName = uniqueName("GhostActionRoom");
            JsonNode hostData = createRoom(roomName, "GhostHost", 2);
            String gameId = hostData.path("roomId").asString();
            String hostToken = hostData.path("token").asString();
            String guestToken = joinRoom(roomName, "GhostGuest").path("token").asString();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor host = new PlayerActor("GhostHost", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guest = new PlayerActor("GhostGuest", guestToken, connectSession(stompClient, guestToken));
            awaitActive(gameId, hostToken, "GhostHost", "GhostGuest");
            List<PlayerFeedbackResponse> feedback = recordPrivateFeedback(host.session(), hostToken);

            checkDownToShowdown(gameId, host, guest);
            assertThat(feedback).as("legal check-down actions must not be rejected").isEmpty();
            long revisionBeforeStray = readGameState(gameId, hostToken).path("stateRevision").asLong();

            host.session().send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));

            PlayerFeedbackResponse error = await()
                    .until(() -> feedback.stream().findFirst(), Optional::isPresent)
                    .orElseThrow();
            assertThat(error.type()).isEqualTo(PlayerFeedbackResponse.Type.ACTION_ERROR);
            assertThat(error.code()).isEqualTo("HAND_COMPLETE");
            assertThat(error.action()).isEqualTo(RejectedAction.CHECK);
            assertThat(error.message()).isEqualTo("This hand is over. Wait for the next one.");

            JsonNode showdownState = readGameState(gameId, hostToken);
            assertThat(showdownState.path("phase").asString()).isEqualTo("SHOWDOWN");
            assertThat(showdownState.path("stateRevision").asLong()).isEqualTo(revisionBeforeStray);
            int sumChips = 0;
            for (JsonNode player : showdownState.path("players")) {
                sumChips += player.path("chips").asInt();
            }
            assertThat(sumChips + showdownState.path("pot").asInt()).isEqualTo(TOTAL_CHIPS);
            assertThat(feedback).containsExactly(error);
        }
    }

    private void awaitActive(String gameId, String viewerToken, String... playerNames) {
        for (String playerName : playerNames) {
            awaitPlayerStatus(gameId, viewerToken, playerName, "ACTIVE");
        }
    }

    // Heads-up check-down: the CALL settles pre-flop, then every street takes two CHECKs; each step must
    // move the state forward to the phase listed, and the last one waits for the ready countdown to open.
    private void checkDownToShowdown(String gameId, PlayerActor host, PlayerActor guest) {
        String[] phaseAfterStep = {"PRE_FLOP", "FLOP", "FLOP", "TURN", "TURN", "RIVER", "RIVER", "SHOWDOWN"};
        for (int step = 0; step < phaseAfterStep.length; step++) {
            String expectedPhase = phaseAfterStep[step];
            boolean showdownStep = step == phaseAfterStep.length - 1;
            PlayerAction action = step == 0 ? PlayerAction.CALL : PlayerAction.CHECK;
            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(action, 0),
                    host,
                    guest,
                    state -> expectedPhase.equals(state.path("phase").asString())
                            && (!showdownStep || state.path("isReadyCountdownActive").asBoolean()));
        }
    }

    private static boolean isFreshHand(JsonNode state) {
        return "PRE_FLOP".equals(state.path("phase").asString()) && !state.path("isReadyCountdownActive").asBoolean();
    }

    private static boolean isReadyGate(JsonNode state) {
        return "SHOWDOWN".equals(state.path("phase").asString()) && state.path("isReadyCountdownActive").asBoolean();
    }

    private static boolean isReadyForNextHand(JsonNode state, String playerName) {
        return findPlayer(state, playerName).path("isReadyForNextHand").asBoolean();
    }
}
