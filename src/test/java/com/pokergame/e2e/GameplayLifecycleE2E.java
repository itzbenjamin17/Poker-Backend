package com.pokergame.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.ActionErrorResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.RejectedAction;
import com.pokergame.enums.ResponseMessage;
import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end black-box REST and WebSocket STOMP tests covering game start, state projections,
 * leaving active games, multi-street progression, disconnect claim-win, cleanup, and ready countdowns.
 */
@Tag("e2e")
@DisplayName("Gameplay lifecycle E2E")
class GameplayLifecycleE2E extends AbstractE2ESupport {

    private static final String HOST_NAME = "ActionHost";
    private static final String OTHER_NAME = "ActionOther";

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

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> startGame(roomId, nonHostToken));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Only the room host can start the game");
        }

        @Test
        @DisplayName("should reject start game requests when only one player is present")
        void givenSinglePlayerRoom_whenStartGame_thenReturnForbidden() throws Exception {
            JsonNode hostData = createRoom(uniqueName("SinglePlayerStartRoom"), "SoloHost", 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> startGame(roomId, hostToken));

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

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> readGameState(gameId, outsiderToken));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this game.");
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

            JsonNode privateState = readPrivateState(gameId, hostToken);

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

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> readPrivateState(gameId, outsiderToken));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this game.");
        }
    }

    @Nested
    @DisplayName("leaving games")
    class LeaveGame {

        @Test
        @DisplayName("should reject room leave requests without a token")
        void givenMissingToken_whenLeaveRoom_thenReturnUnauthorized() {
            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class, () -> restClient.post()
                    .uri("/api/room/some-room-id/leave")
                    .retrieve()
                    .body(String.class));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should return forbidden for an unknown room id due to token mismatch")
        void givenUnknownRoom_whenLeaveRoom_thenReturnForbidden() throws Exception {
            String hostToken = createRoom(uniqueName("UnknownRoom"), "HostUnknownRoom", 6).path("token").asText();

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> leaveRoom(uniqueName("missing-room"), hostToken));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString()).contains("Token is not valid for this room.");
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

            String leaveBody = leaveRoom(roomId, secondPlayerToken);

            assertThat(leaveBody).contains("Successfully left room");
        }

        @Test
        @DisplayName("should keep a three-player game responsive after a non-current player leaves")
        void givenThreePlayerGame_whenNonCurrentPlayerLeaves_thenRemainingPlayersCanStillAct() throws Exception {
            String roomName = uniqueName("LeaveThreePlayerRoom");
            JsonNode hostData = createRoom(roomName, "HostLeaveThree", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String secondToken = joinRoom(roomName, "SecondLeaveThree").path("token").asText();
            String thirdToken = joinRoom(roomName, "ThirdLeaveThree").path("token").asText();
            startGame(gameId, hostToken);

            JsonNode initialState = readGameState(gameId, hostToken);
            String initialCurrent = initialState.path("currentPlayerName").asText();
            boolean secondIsCurrent = "SecondLeaveThree".equals(initialCurrent);
            String leavingToken = secondIsCurrent ? thirdToken : secondToken;
            String remainingGuestName = secondIsCurrent ? "SecondLeaveThree" : "ThirdLeaveThree";
            String remainingGuestToken = secondIsCurrent ? secondToken : thirdToken;

            String leaveBody = leaveRoom(gameId, leavingToken);

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, remainingGuestToken);
            PlayerActor hostActor = new PlayerActor("HostLeaveThree", hostToken, hostSession);
            PlayerActor guestActor = new PlayerActor(remainingGuestName, remainingGuestToken, guestSession);

            JsonNode beforeFirstAction = readGameState(gameId, hostToken);
            String firstCurrentPlayer = beforeFirstAction.path("currentPlayerName").asText();
            String firstPhase = beforeFirstAction.path("phase").asText();
            String firstActor = performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CALL, 0),
                    hostActor,
                    guestActor,
                    state -> !firstCurrentPlayer.equals(state.path("currentPlayerName").asText())
                            || !firstPhase.equals(state.path("phase").asText()));

            JsonNode beforeSecondAction = readGameState(gameId, hostToken);
            String secondCurrentPlayer = beforeSecondAction.path("currentPlayerName").asText();
            String secondActor = performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.FOLD, 0),
                    hostActor,
                    guestActor,
                    state -> "SHOWDOWN".equals(state.path("phase").asText())
                            || playerHasFolded(state, secondCurrentPlayer));

            assertThat(leaveBody).contains("Successfully left room");
            assertThat(firstActor).isIn("HostLeaveThree", remainingGuestName);
            assertThat(secondActor).isIn("HostLeaveThree", remainingGuestName);
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

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor host = new PlayerActor("RoundHost", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guest = new PlayerActor("RoundGuest", guestToken, connectSession(stompClient, guestToken));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CALL, 0),
                    host,
                    guest,
                    state -> "PRE_FLOP".equals(state.path("phase").asText())
                            && "RoundHost".equals(state.path("currentPlayerName").asText()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "FLOP".equals(state.path("phase").asText())
                            && state.path("communityCards").size() == 3
                            && "RoundHost".equals(state.path("currentPlayerName").asText()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "FLOP".equals(state.path("phase").asText())
                            && "RoundGuest".equals(state.path("currentPlayerName").asText()));

            performActionByCurrentPlayer(
                    gameId,
                    new PlayerActionRequest(PlayerAction.CHECK, 0),
                    host,
                    guest,
                    state -> "TURN".equals(state.path("phase").asText())
                            && state.path("communityCards").size() == 4);
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
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "EndGuest").path("token").asText();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            connectSession(stompClient, guestToken);

            String leaveBody = leaveRoom(gameId, guestToken);

            assertThat(leaveBody).contains("Successfully left room");
            awaitRoomDestruction(gameId, hostToken, DEFAULT_TIMEOUT);
        }

        @Test
        @DisplayName("should allow claim win when every other non-out player is disconnected")
        void givenDisconnectedOpponents_whenClaimWin_thenReturnSuccessAndCleanupRoom() throws Exception {
            String roomName = uniqueName("ClaimWinRoom");
            JsonNode hostData = createRoom(roomName, "ClaimHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "ClaimGuest").path("token").asText();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);
            guestSession.disconnect();

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode state = readGameState(gameId, hostToken);
                assertThat(findPlayer(state, "ClaimGuest").path("status").asText()).isEqualTo("DISCONNECTED");
            });

            JsonNode response = claimWin(gameId, hostToken);

            assertThat(response.path("message").asText()).isEqualTo("Win claimed successfully");
            awaitRoomDestruction(gameId, hostToken, DEFAULT_TIMEOUT);
        }

        @Test
        @DisplayName("should reject stale claim win requests once an opponent has reconnected")
        void givenReconnectedOpponent_whenClaimWin_thenReturnForbidden() throws Exception {
            String roomName = uniqueName("ClaimRejectRoom");
            JsonNode hostData = createRoom(roomName, "ClaimRejectHost", 6);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "ClaimRejectGuest").path("token").asText();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            connectSession(stompClient, hostToken);
            StompSession guestSession = connectSession(stompClient, guestToken);
            guestSession.disconnect();

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode state = readGameState(gameId, hostToken);
                assertThat(findPlayer(state, "ClaimRejectGuest").path("status").asText()).isEqualTo("DISCONNECTED");
            });

            connectSession(stompClient, guestToken);
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode state = readGameState(gameId, hostToken);
                assertThat(findPlayer(state, "ClaimRejectGuest").path("status").asText()).isEqualTo("ACTIVE");
            });

            HttpClientErrorException exception = assertThrows(HttpClientErrorException.class,
                    () -> claimWin(gameId, hostToken));

            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exception.getResponseBodyAsString().toLowerCase()).contains("claim");
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
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);

            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> nextStateFuture = new CompletableFuture<>();

            hostSession.subscribe("/game/" + roomId, new MatchingPublicStateFrameHandler(
                    initialStateFuture,
                    response -> response.phase() == GamePhase.PRE_FLOP
                            && !Boolean.TRUE.equals(response.isReadyCountdownActive())));
            awaitBrokerSubscription("/game/" + roomId);

            startGame(roomId, hostToken);

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            hostSession.subscribe("/game/" + roomId, new MatchingPublicStateFrameHandler(
                    nextStateFuture,
                    response -> response.players().stream().anyMatch(player ->
                            player.name().equals(initialState.currentPlayerName()) && player.hasFolded())));
            awaitBrokerSubscriptions("/game/" + roomId, 2);

            StompSession actingSession = HOST_NAME.equals(initialState.currentPlayerName())
                    ? hostSession
                    : otherSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            PublicGameStateResponse nextState = nextStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(nextState.players()).anySatisfy(player -> {
                assertThat(player.name()).isEqualTo(initialState.currentPlayerName());
                assertThat(player.hasFolded()).isTrue();
            });
        }

        @Test
        @DisplayName("should continue processing actions after the active client reconnects")
        void givenReconnectDuringGame_whenActionIsSent_thenGameStillBroadcastsState() throws Exception {
            String roomName = uniqueName("WsReconnectAction");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession observerSession = connectSession(stompClient, hostToken);
            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();

            observerSession.subscribe("/game/" + roomId, new PublicStateFrameHandler(initialStateFuture));
            awaitBrokerSubscription("/game/" + roomId);

            startGame(roomId, hostToken);

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            observerSession.disconnect();

            String actingToken = HOST_NAME.equals(initialState.currentPlayerName()) ? hostToken : otherToken;
            StompSession reconnectedSession = connectSession(stompClient, actingToken);
            CompletableFuture<PublicGameStateResponse> nextStateFuture = new CompletableFuture<>();
            reconnectedSession.subscribe("/game/" + roomId, new PublicStateFrameHandler(nextStateFuture));
            awaitBrokerSubscription("/game/" + roomId);

            reconnectedSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            PublicGameStateResponse nextState = nextStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(nextState).isNotNull();
        }

        @Test
        @DisplayName("should advance immediately when all eligible players mark READY")
        void givenReadyGate_whenAllEligiblePlayersReady_thenAdvanceWithoutWaitingForTimeout() throws Exception {
            String roomName = uniqueName("WsAllReady");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);

            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> readyGateOpenFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> nextHandFuture = new CompletableFuture<>();

            hostSession.subscribe("/game/" + roomId,
                    readyGateHandler(initialStateFuture, readyGateOpenFuture, nextHandFuture));
            awaitBrokerSubscription("/game/" + roomId);

            startGame(roomId, hostToken);

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            StompSession actingSession = HOST_NAME.equals(initialState.currentPlayerName())
                    ? hostSession
                    : otherSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            PublicGameStateResponse readyGateState = readyGateOpenFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(readyGateState.phase()).isEqualTo(GamePhase.SHOWDOWN);
            assertThat(readyGateState.isReadyCountdownActive()).isTrue();
            assertThat(readyGateState.readyCountdownDeadlineEpochMs()).isNotNull();

            hostSession.send("/app/" + roomId + "/ready", "");
            otherSession.send("/app/" + roomId + "/ready", "");

            PublicGameStateResponse nextHand = nextHandFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(nextHand.phase()).isEqualTo(GamePhase.PRE_FLOP);
            assertThat(Boolean.TRUE.equals(nextHand.isReadyCountdownActive())).isFalse();
        }

        @Test
        @DisplayName("should auto-advance on ready timeout when some players stay unready")
        void givenReadyGate_whenOnePlayerStaysUnready_thenTimeoutForcesAdvance() throws Exception {
            String roomName = uniqueName("WsReadyTimeout");
            JsonNode hostData = createRoom(roomName, HOST_NAME, 6);
            String roomId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String otherToken = joinRoom(roomName, OTHER_NAME).path("token").asText();

            WebSocketStompClient stompClient = createStompClient();
            StompSession hostSession = connectSession(stompClient, hostToken);
            StompSession otherSession = connectSession(stompClient, otherToken);

            CompletableFuture<PublicGameStateResponse> initialStateFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> readyGateOpenFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> partiallyReadyFuture = new CompletableFuture<>();
            CompletableFuture<PublicGameStateResponse> nextHandFuture = new CompletableFuture<>();
            AtomicBoolean readyGateSeen = new AtomicBoolean(false);

            hostSession.subscribe("/game/" + roomId, new StompFrameHandler() {
                @Override
                public Type getPayloadType(@NonNull StompHeaders headers) {
                    return PublicGameStateResponse.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    if (!(payload instanceof PublicGameStateResponse response)) {
                        return;
                    }
                    if (!initialStateFuture.isDone()
                            && response.phase() == GamePhase.PRE_FLOP
                            && !Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                        initialStateFuture.complete(response);
                    }
                    if (!readyGateOpenFuture.isDone()
                            && response.phase() == GamePhase.SHOWDOWN
                            && Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                        readyGateSeen.set(true);
                        readyGateOpenFuture.complete(response);
                    }
                    if (!partiallyReadyFuture.isDone()
                            && readyGateSeen.get()
                            && response.phase() == GamePhase.SHOWDOWN
                            && Boolean.TRUE.equals(response.isReadyCountdownActive())
                            && countReadyEligiblePlayers(response) == 1) {
                        partiallyReadyFuture.complete(response);
                    }
                    if (!nextHandFuture.isDone()
                            && readyGateSeen.get()
                            && response.phase() == GamePhase.PRE_FLOP
                            && !Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                        nextHandFuture.complete(response);
                    }
                }
            });
            awaitBrokerSubscription("/game/" + roomId);

            startGame(roomId, hostToken);

            PublicGameStateResponse initialState = initialStateFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            StompSession actingSession = HOST_NAME.equals(initialState.currentPlayerName())
                    ? hostSession
                    : otherSession;
            actingSession.send("/app/" + roomId + "/action", new PlayerActionRequest(PlayerAction.FOLD, 0));

            PublicGameStateResponse readyGateState = readyGateOpenFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(readyGateState.phase()).isEqualTo(GamePhase.SHOWDOWN);
            assertThat(readyGateState.isReadyCountdownActive()).isTrue();

            hostSession.send("/app/" + roomId + "/ready", "");

            PublicGameStateResponse partiallyReadyState = partiallyReadyFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(countReadyEligiblePlayers(partiallyReadyState)).isEqualTo(1);

            PublicGameStateResponse nextHand = nextHandFuture.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(nextHand.phase()).isEqualTo(GamePhase.PRE_FLOP);
            assertThat(Boolean.TRUE.equals(nextHand.isReadyCountdownActive())).isFalse();
        }
    }

    @Nested
    @DisplayName("ready countdown flow")
    class ReadyCountdownFlow {

        @Test
        @DisplayName("should start new hand when all players are ready")
        void givenShowdown_whenAllPlayersReady_thenStartNewHand() throws Exception {
            String roomName = uniqueName("ReadyFlowRoom");
            JsonNode hostData = createRoom(roomName, "ReadyHost", 2);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "ReadyGuest").path("token").asText();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor host = new PlayerActor("ReadyHost", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guest = new PlayerActor("ReadyGuest", guestToken, connectSession(stompClient, guestToken));

            checkDownToShowdown(gameId, host, guest);

            JsonNode showdownState = readGameState(gameId, hostToken);
            assertThat(showdownState.path("isReadyCountdownActive").asBoolean()).isTrue();

            host.session().send("/app/" + gameId + "/ready", "{}");
            guest.session().send("/app/" + gameId + "/ready", "{}");

            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                JsonNode nextHandState = readGameState(gameId, hostToken);
                assertThat(nextHandState.path("phase").asText()).isEqualTo("PRE_FLOP");
                assertThat(nextHandState.path("pot").asInt()).isEqualTo(30);
            });
        }

        @Test
        @DisplayName("should reject stray action after showdown and conserve total chips")
        void givenShowdown_whenStrayActionSubmitted_thenRejectAndConserveChips() throws Exception {
            String roomName = uniqueName("GhostActionRoom");
            JsonNode hostData = createRoom(roomName, "GhostHost", 2);
            String gameId = hostData.path("roomId").asText();
            String hostToken = hostData.path("token").asText();
            String guestToken = joinRoom(roomName, "GhostGuest").path("token").asText();
            startGame(gameId, hostToken);

            WebSocketStompClient stompClient = createStompClient();
            PlayerActor host = new PlayerActor("GhostHost", hostToken, connectSession(stompClient, hostToken));
            PlayerActor guest = new PlayerActor("GhostGuest", guestToken, connectSession(stompClient, guestToken));

            checkDownToShowdown(gameId, host, guest);

            int totalExpectedChips = 2000;
            JsonNode beforeStray = readGameState(gameId, hostToken);
            long revisionBeforeStray = beforeStray.path("stateRevision").asLong();

            CompletableFuture<ActionErrorResponse> rejection = new CompletableFuture<>();
            host.session().subscribe("/user/queue/private", new StompFrameHandler() {
                @Override
                public Type getPayloadType(@NonNull StompHeaders headers) {
                    return ActionErrorResponse.class;
                }

                @Override
                public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                    if (payload instanceof ActionErrorResponse response && !rejection.isDone()) {
                        rejection.complete(response);
                    }
                }
            });

            host.session().send("/app/" + gameId + "/action", new PlayerActionRequest(PlayerAction.CHECK, 0));

            ActionErrorResponse error = rejection.get(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(error.type()).isEqualTo(ResponseMessage.ACTION_ERROR);
            assertThat(error.action()).isEqualTo(RejectedAction.CHECK);

            Awaitility.await().atMost(DEFAULT_TIMEOUT).untilAsserted(() -> {
                JsonNode showdownState = readGameState(gameId, hostToken);
                assertThat(showdownState.path("phase").asText()).isEqualTo("SHOWDOWN");
                assertThat(showdownState.path("stateRevision").asLong()).isEqualTo(revisionBeforeStray);
                int sumChips = 0;
                for (JsonNode player : showdownState.path("players")) {
                    sumChips += player.path("chips").asInt();
                }
                assertThat(sumChips + showdownState.path("pot").asInt()).isEqualTo(totalExpectedChips);
            });
        }
    }

    private void checkDownToShowdown(String gameId, PlayerActor host, PlayerActor guest) throws Exception {
        performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CALL, 0), host, guest, s -> true);
        for (int step = 0; step < 6; step++) {
            performActionByCurrentPlayer(gameId, new PlayerActionRequest(PlayerAction.CHECK, 0), host, guest, s -> true);
        }
        performActionByCurrentPlayer(
                gameId,
                new PlayerActionRequest(PlayerAction.CHECK, 0),
                host,
                guest,
                state -> "SHOWDOWN".equals(state.path("phase").asText()));
    }

    private static long countReadyEligiblePlayers(PublicGameStateResponse response) {
        return response.players().stream()
                .filter(player -> !"OUT".equals(player.status()) && !"DISCONNECTED".equals(player.status()))
                .filter(player -> Boolean.TRUE.equals(player.isReadyForNextHand()))
                .count();
    }

    private static @NonNull StompFrameHandler readyGateHandler(
            CompletableFuture<PublicGameStateResponse> initialStateFuture,
            CompletableFuture<PublicGameStateResponse> readyGateOpenFuture,
            CompletableFuture<PublicGameStateResponse> nextHandFuture) {
        AtomicBoolean readyGateSeen = new AtomicBoolean(false);
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(@NonNull StompHeaders headers) {
                return PublicGameStateResponse.class;
            }

            @Override
            public void handleFrame(@NonNull StompHeaders headers, Object payload) {
                if (!(payload instanceof PublicGameStateResponse response)) {
                    return;
                }
                if (!initialStateFuture.isDone()
                        && response.phase() == GamePhase.PRE_FLOP
                        && !Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                    initialStateFuture.complete(response);
                }
                if (!readyGateOpenFuture.isDone()
                        && response.phase() == GamePhase.SHOWDOWN
                        && Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                    readyGateSeen.set(true);
                    readyGateOpenFuture.complete(response);
                }
                if (!nextHandFuture.isDone()
                        && readyGateSeen.get()
                        && response.phase() == GamePhase.PRE_FLOP
                        && !Boolean.TRUE.equals(response.isReadyCountdownActive())) {
                    nextHandFuture.complete(response);
                }
            }
        };
    }

    private static final class PublicStateFrameHandler implements StompFrameHandler {
        private final CompletableFuture<PublicGameStateResponse> future;

        private PublicStateFrameHandler(CompletableFuture<PublicGameStateResponse> future) {
            this.future = future;
        }

        @Override
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return PublicGameStateResponse.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            if (payload instanceof PublicGameStateResponse response && !future.isDone()) {
                future.complete(response);
            }
        }
    }

    private static final class MatchingPublicStateFrameHandler implements StompFrameHandler {
        private final CompletableFuture<PublicGameStateResponse> future;
        private final Predicate<PublicGameStateResponse> predicate;

        private MatchingPublicStateFrameHandler(
                CompletableFuture<PublicGameStateResponse> future,
                Predicate<PublicGameStateResponse> predicate) {
            this.future = future;
            this.predicate = predicate;
        }

        @Override
        public Type getPayloadType(@NonNull StompHeaders headers) {
            return PublicGameStateResponse.class;
        }

        @Override
        public void handleFrame(@NonNull StompHeaders headers, Object payload) {
            if (payload instanceof PublicGameStateResponse response
                    && !future.isDone()
                    && predicate.test(response)) {
                future.complete(response);
            }
        }
    }
}
