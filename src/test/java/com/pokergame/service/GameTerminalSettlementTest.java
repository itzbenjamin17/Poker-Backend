package com.pokergame.service;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.HandEvaluationResult;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit and integration tests for {@link GameTerminalSettlement}, terminal state helper
 * methods on {@link Game}, and migrated terminal settlement lifecycle tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GameTerminalSettlement & Terminal State Management")
class GameTerminalSettlementTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private HandEvaluatorService handEvaluator;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private GameLifecycleService gameLifecycleService;
    private GameTerminalSettlement terminalSettlement;
    private Room testRoom;
    private static final String ROOM_ID = "test-room-id";

    @BeforeEach
    void setUp() {
        GameTimerRegistry runtimeTimers = new GameTimerRegistry(taskScheduler);
        terminalSettlement = new GameTerminalSettlement(gameStateService, runtimeTimers);
        gameLifecycleService = new GameLifecycleService(roomService, handEvaluator, gameStateService, messagingTemplate,
                taskScheduler);
        lenient().when(handEvaluator.getBestHand(anyList(), anyList()))
                .thenReturn(new HandEvaluationResult(List.of(), HandRank.HIGH_CARD));

        testRoom = new Room(
                ROOM_ID,
                "Test Room",
                "Host",
                6,
                5,
                10,
                100,
                null);
        testRoom.addPlayer("Host");
        testRoom.addPlayer("Player2");
        stubGameEndCapture();
    }

    private void stubGameEndCapture() {
        Answer<GameEndResponse> buildResponse = invocation -> {
            String id = invocation.getArgument(0);
            Game game = invocation.getArgument(1);
            Player winner = invocation.getArgument(2);
            boolean forfeit = invocation.getArgument(3);
            int maxPlayers = invocation.getArgument(4);
            PublicGameStateResponse finalState = new PublicGameStateResponse(
                    maxPlayers, 0, List.of(), 0, GamePhase.SHOWDOWN, 0, List.of(),
                    game != null && game.getPlayers() != null ? game.getPlayers().stream().map(p -> new PublicPlayerState(
                            p.getPlayerId(), p.getName(), p.getChips(), 0, "status", false, false, false,
                            false, false, null, List.of(), p.equals(winner), null, null, null, false)).toList() : List.of(),
                    null, null, List.of(winner.getName()), null, false, null, false, null, false, null,
                    true, null, List.of(), null, null, 1, ReadyGateState.CLOSED, System.currentTimeMillis(),
                    game != null ? game.getStateRevision() : 1);
            return new GameEndResponse("GAME_END", id, winner.getName(), winner.getChips(), forfeit,
                    winner.getName() + " wins", finalState);
        };
        lenient().when(gameStateService.captureGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean(), anyInt()))
                .thenAnswer(buildResponse);
        lenient().when(gameStateService.projectGameEnd(anyString(), any(Game.class), any(Player.class), anyBoolean(), anyInt()))
                .thenAnswer(buildResponse);
    }

    @Nested
    @DisplayName("Game Domain Terminal Settlement Helpers")
    class GameTerminalHelperTests {

        @Test
        @DisplayName("isFallbackForfeit returns true for non-SHOWDOWN phases")
        void isFallbackForfeit_NonShowdown_ReturnsTrue() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertTrue(game.isFallbackForfeit());
        }

        @Test
        @DisplayName("isFallbackForfeit returns false for settled SHOWDOWN phase")
        void isFallbackForfeit_SettledShowdown_ReturnsFalse() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            game.conductShowdown();
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
            assertTrue(game.isHandSettled());
            assertFalse(game.isFallbackForfeit());
        }

        @Test
        @DisplayName("settleTerminalState marks forfeited players out and resolves winner")
        void settleTerminalState_ForfeitedPlayersAndWinnerResolution() {
            Player p1 = new Player("Host", "p1", 500);
            Player p2 = new Player("Player2", "p2", 200);
            Player p3 = new Player("Player3", "p3", 300);
            Game game = new Game(ROOM_ID, List.of(p1, p2, p3), 5, 10, handEvaluator);

            Player winner = game.settleTerminalState(null, Set.of("Player2"));
            assertTrue(p2.getIsOut());
            assertFalse(p1.getIsOut());
            assertEquals(p1, winner);
            assertTrue(game.isGameOver());
            assertTrue(game.isTerminalCarryOverSettled());
        }

        @Test
        @DisplayName("settleTerminalState honors preferredWinner when provided")
        void settleTerminalState_HonorsPreferredWinner() {
            Player p1 = new Player("Host", "p1", 500);
            Player p2 = new Player("Player2", "p2", 1000);
            Game game = new Game(ROOM_ID, List.of(p1, p2), 5, 10, handEvaluator);

            Player winner = game.settleTerminalState(p1, Set.of("Player2"));
            assertEquals(p1, winner);
            assertTrue(game.isGameOver());
        }

        @Test
        @DisplayName("isParticipantInTerminalRoster checks both retained DTO and game players")
        void isParticipantInTerminalRoster_ChecksBothSources() {
            Player p1 = new Player("Host", "p1", 500);
            Player p2 = new Player("Player2", "p2", 500);
            Game game = new Game(ROOM_ID, List.of(p1, p2), 5, 10, handEvaluator);

            assertTrue(game.isParticipantInTerminalRoster("Host", null));
            assertTrue(game.isParticipantInTerminalRoster("Player2", null));
            assertFalse(game.isParticipantInTerminalRoster("Stranger", null));

            PublicPlayerState retainedPlayer = new PublicPlayerState("p3", "Player3", 100, 0, "status",
                    false, false, false, false, false, null, List.of(), false, null, null, null, false);
            PublicGameStateResponse state = new PublicGameStateResponse(3, 0, List.of(), 0, GamePhase.SHOWDOWN,
                    0, List.of(), List.of(retainedPlayer), null, null, List.of("Player3"), null, false, null,
                    false, null, false, null, true, null, List.of(), null, null, 1, ReadyGateState.CLOSED,
                    System.currentTimeMillis(), 1);
            GameEndResponse retained = new GameEndResponse("GAME_END", ROOM_ID, "Player3", 100, false, "wins", state);

            assertTrue(game.isParticipantInTerminalRoster("Player3", retained));
            assertFalse(game.isParticipantInTerminalRoster("Stranger", retained));
        }
    }

    @Nested
    @DisplayName("GameTerminalSettlement Collaborator Unit Tests")
    class CollaboratorUnitTests {

        @Test
        @DisplayName("settleUnderLock no-ops when forfeit is pending and preferredWinner is null")
        void settleUnderLock_ForfeitPending_NoOps() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.setForfeitPending(true);

            GameTerminalSettlement.SettlementOutcome outcome =
                    terminalSettlement.settleUnderLock(ROOM_ID, game, testRoom, false, null, Set.of());
            assertFalse(outcome.settledNow());
            assertNull(outcome.winner());
        }

        @Test
        @DisplayName("settleUnderLock no-ops when game is over and carryover already settled")
        void settleUnderLock_AlreadyGameOverAndCarryoverSettled_NoOps() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            game.settleTerminalState(null, Set.of());
            assertTrue(game.isGameOver());
            assertTrue(game.isTerminalCarryOverSettled());

            GameTerminalSettlement.SettlementOutcome outcome =
                    terminalSettlement.settleUnderLock(ROOM_ID, game, testRoom, false, null, Set.of());
            assertFalse(outcome.settledNow());
        }

        @Test
        @DisplayName("settleUnderLock captures retained terminal result and cancels runtime timers")
        void settleUnderLock_CapturesTerminalResult() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            reset(gameStateService);
            stubGameEndCapture();

            GameTerminalSettlement.SettlementOutcome outcome =
                    terminalSettlement.settleUnderLock(ROOM_ID, game, testRoom, false, null, Set.of());
            assertTrue(outcome.settledNow());
            assertNotNull(outcome.winner());
            assertNotNull(outcome.event());
            assertSame(outcome.event(), game.getRetainedTerminalResult());
            verify(gameStateService).clearRetainedSettlement(ROOM_ID);
        }

        @Test
        @DisplayName("resolveTerminalResultForParticipant throws UnauthorisedActionException for stranger")
        void resolveTerminalResultForParticipant_UnauthorizedThrows() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.markGameOver();

            assertThrows(UnauthorisedActionException.class, () ->
                    terminalSettlement.resolveTerminalResultForParticipant(ROOM_ID, game, testRoom, "Stranger", false));
        }

        @Test
        @DisplayName("ensureRetainedClaimResult captures claim result when retained is null")
        void ensureRetainedClaimResult_CapturesForClaimant() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.markGameOver();
            assertNull(game.getRetainedTerminalResult());

            GameEndResponse response = terminalSettlement.ensureRetainedClaimResult(ROOM_ID, game, testRoom, "Host");
            assertNotNull(response);
            assertEquals("Host", response.winner());
            assertSame(response, game.getRetainedTerminalResult());
        }
    }

    @Nested
    @DisplayName("Migrated Terminal Settlement & Review Tests")
    class MigratedLifecycleTests {

        /**
         * Protects the contract that handle game end with winner should broadcast end.
         */
        @Test
        void handleGameEnd_WithWinner_ShouldBroadcastEnd() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            reset(gameStateService);
            stubGameEndCapture();

            gameLifecycleService.handleGameEnd(ROOM_ID, false);

            verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
        }

        @Test
        void terminalCarryOverFlowsThroughLifecycleIntoFrozenGameEndDtoOnce() {
            Room room = testRoom;
            when(roomService.getRoom(ROOM_ID)).thenReturn(room);
            SimpMessagingTemplate publisher = messagingTemplate;
            GameStateService realStateService = new GameStateService(roomService, publisher);
            GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, realStateService,
                    publisher, taskScheduler);
            Player winner = Player.restoreBuilder().name("Host").playerId("host").chips(1000).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false).allIn(false)
                    .out(false).disconnected(false).readyForNextHand(false).build();
            Player eliminated = Player.restoreBuilder().name("Player2").playerId("p2").chips(0).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true).allIn(false)
                    .out(true).disconnected(false).readyForNextHand(false).build();
            Game terminal = Game.restoreBuilder().gameId(ROOM_ID).players(List.of(winner, eliminated))
                    .activePlayerIds(List.of("host")).remainingDeck(List.of()).communityCards(List.of()).pot(10)
                    .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0)
                    .currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false).smallBlind(5).bigBlind(10)
                    .handContributions(Map.of()).preHandCarryOver(10)
                    .lastSettlementResult(new HandSettlementResult(Map.of(), Map.of(), Set.of(), 10, true))
                    .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                    .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
            lifecycle.restoreGame(terminal);

            lifecycle.handleGameEnd(ROOM_ID, false);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(publisher).convertAndSend(eq("/game/" + ROOM_ID), captor.capture());
            GameEndResponse result = (GameEndResponse) captor.getValue();
            assertEquals(1010, result.winnerChips());
            assertEquals(0, result.finalState().pot());
            assertEquals(1010, result.finalState().players().stream().filter(PublicPlayerState::isWinner)
                    .findFirst().orElseThrow().chips());
            assertTrue(terminal.isTerminalCarryOverSettled());

            lifecycle.handleGameEnd(ROOM_ID, false);
            verify(publisher, times(1)).convertAndSend(eq("/game/" + ROOM_ID), any(Object.class));
            assertEquals(1010, winner.getChips());
        }

        /**
         * Protects the contract that handle game end with null game should not broadcast.
         */
        @Test
        void handleGameEnd_WithNullGame_ShouldNotBroadcast() {
            gameLifecycleService.handleGameEnd("nonexistent-id", false);

            verify(gameStateService, never()).sendGameEnd(any(GameEndResponse.class));
        }

        /**
         * Protects the contract that handle game end when no players remain should not throw.
         */
        @Test
        void handleGameEnd_WhenNoPlayersRemain_ShouldNotThrow() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);

            Game game = gameLifecycleService.getGame(ROOM_ID);
            game.getPlayers().clear();
            game.getActivePlayers().clear();

            assertDoesNotThrow(() -> gameLifecycleService.handleGameEnd(ROOM_ID, false));
        }

        @Test
        @DisplayName("handleGameEnd should pick winner with chips in heads-up all-in")
        void handleGameEnd_HeadsUpAllIn_ShouldPickWinnerWithChips() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            // Host is index 0, Player2 is index 1
            Player host = game.getPlayers().get(0);
            Player p2 = game.getPlayers().get(1);

            // Host is busted (0 chips)
            host.setChips(0);
            p2.setChips(2000);

            reset(gameStateService);
            stubGameEndCapture();
            gameLifecycleService.handleGameEnd(ROOM_ID, false);

            // Should broadcast p2 as winner
            verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals(p2.getName()) && !event.isForfeit()));
            assertTrue(game.isGameOver());
        }

        @Test
        @DisplayName("handleGameEnd should broadcast forfeit when opponent is removed")
        void handleGameEnd_ForfeitPath_ShouldBroadcastForfeit() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            Player host = game.getPlayers().get(0);
            Player p2 = game.getPlayers().get(1);

            // p2 is out
            p2.setIsOut();

            reset(gameStateService);
            stubGameEndCapture();
            gameLifecycleService.handleGameEnd(ROOM_ID, true);

            // Host should be winner
            verify(gameStateService).sendGameEnd(argThat(event -> event != null && event.winner().equals(host.getName()) && event.isForfeit()));
            assertTrue(game.isGameOver());
        }

        @Test
        @DisplayName("getGameReview enforces 200, 409, 404, 403 authorization rules")
        void getGameReview_AuthorizationAndStatusRules() {
            testRoom.addPlayer("Player3");
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);

            // 1. Active game -> 409 Conflict
            assertThrows(ConflictException.class,
                    () -> gameLifecycleService.getGameReview(ROOM_ID, "Host"));

            // End the game
            gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
            gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player3", System.currentTimeMillis() + 120_000);
            GameEndResponse terminal = gameLifecycleService.claimWin(ROOM_ID, "Host");

            // 2. Winner can review -> 200
            GameEndResponse reviewWinner = gameLifecycleService.getGameReview(ROOM_ID, "Host");
            assertSame(terminal, reviewWinner);

            // 3. OUT / forfeit player in terminal roster can review -> 200
            GameEndResponse reviewOutPlayer = gameLifecycleService.getGameReview(ROOM_ID, "Player2");
            assertSame(terminal, reviewOutPlayer);

            // 4. Departed room member (removed from Room, but in terminal roster) can review -> 200
            testRoom.removePlayer("Player3");
            assertFalse(testRoom.getPlayers().contains("Player3"));
            GameEndResponse reviewDepartedMember = gameLifecycleService.getGameReview(ROOM_ID, "Player3");
            assertSame(terminal, reviewDepartedMember);

            // 5. Unauthorized player (not in terminal roster) -> 403 Forbidden
            assertThrows(UnauthorisedActionException.class,
                    () -> gameLifecycleService.getGameReview(ROOM_ID, "Stranger"));

            // 6. Non-existent game -> 404 Not Found
            assertThrows(ResourceNotFoundException.class,
                    () -> gameLifecycleService.getGameReview("non-existent-game", "Host"));

            // 7. Expired game (now >= cleanupDeadline) -> 404 Not Found
            game.scheduleTask(ScheduledGameTask.CLEANUP, System.currentTimeMillis() - 1000);
            assertTrue(gameLifecycleService.isGameExpired(game));
            assertFalse(gameLifecycleService.gameExists(ROOM_ID));
            assertThrows(ResourceNotFoundException.class,
                    () -> gameLifecycleService.getGameReview(ROOM_ID, "Host"));
        }

        @Test
        @DisplayName("getGameReview recovers terminal GameEndResponse after game is over without 404 or mutating stateRevision")
        void getGameReview_WhenGameOverAndRetainedResultNull_RecoversWithout404() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);
            Game game = gameLifecycleService.getGame(ROOM_ID);
            assertNotNull(game);

            // Mark game over with null retainedTerminalResult (during PRE_FLOP -> forfeit)
            game.markGameOver();
            game.setRetainedTerminalResult(null);
            long revisionBefore = game.getStateRevision();

            reset(gameStateService);
            stubGameEndCapture();

            // getGameReview should project the terminal result without mutating durable state or incrementing stateRevision
            GameEndResponse review = gameLifecycleService.getGameReview(ROOM_ID, "Host");
            assertNotNull(review, "getGameReview must return non-null GameEndResponse");
            assertEquals("GAME_END", review.type());
            assertNotNull(review.winner(), "Winner must be resolved");
            assertTrue(review.isForfeit(), "Pre-showdown game over must synthesize isForfeit = true");
            assertNotNull(review.finalState(), "finalState must be synthesized");
            assertEquals(revisionBefore, game.getStateRevision(), "Read-only getGameReview must not increment stateRevision");
            assertNull(game.getRetainedTerminalResult(), "Read-only getGameReview must not mutate retainedTerminalResult");
            verify(gameStateService, never()).captureGameEnd(anyString(), any(), any(), anyBoolean(), anyInt());
            verify(gameStateService).projectGameEnd(eq(ROOM_ID), eq(game), any(Player.class), eq(true), eq(testRoom.getMaxPlayers()));

            // When game ended via settled SHOWDOWN, both getGameReview and claimWin derive isForfeit = false
            game.conductShowdown();
            assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
            GameEndResponse showdownReview = gameLifecycleService.getGameReview(ROOM_ID, "Host");
            assertFalse(showdownReview.isForfeit(), "Settled SHOWDOWN game over must synthesize isForfeit = false in getGameReview");

            GameEndResponse showdownClaim = gameLifecycleService.claimWin(ROOM_ID, "Host");
            assertFalse(showdownClaim.isForfeit(), "Settled SHOWDOWN game over must synthesize isForfeit = false in claimWin");
            assertSame(showdownClaim, game.getRetainedTerminalResult(), "Durable claimWin retains synthesized terminal result");
        }

        @Test
        @DisplayName("Empty terminal room preserves container until cleanup, closed to joining")
        void terminalRoom_PreservesContainerWhenEmptyUntilCleanup() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            gameLifecycleService.createGameFromRoom(ROOM_ID);

            gameLifecycleService.markPlayerDisconnected(ROOM_ID, "Player2", System.currentTimeMillis() + 120_000);
            gameLifecycleService.claimWin(ROOM_ID, "Host");

            Game game = gameLifecycleService.getGame(ROOM_ID);
            assertTrue(game.isGameOver());
            assertTrue(testRoom.isTerminal());

            // Host leaves after terminal settlement
            gameLifecycleService.departParticipants(ROOM_ID, List.of("Host"));
            assertTrue(testRoom.isTerminal());
            // Verify room was NOT destroyed upon reaching 0 members because it's terminal
            verify(roomService, never()).destroyRoom(ROOM_ID);

            // Perform cleanup durably destroys room and removes game
            gameLifecycleService.performGameCleanup(ROOM_ID);
            assertNull(gameLifecycleService.getGame(ROOM_ID));
            verify(roomService, times(1)).destroyRoom(ROOM_ID);
        }

        @Test
        @DisplayName("performGameCleanup and evictGame clear retained settlement in GameStateService")
        void performGameCleanupAndEvictGame_ClearRetainedSettlementInGameStateService() {
            when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
            GameStateService realStateService = new GameStateService(roomService, messagingTemplate);
            GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, realStateService,
                    messagingTemplate, taskScheduler);

            lifecycle.createGameFromRoom(ROOM_ID);
            Game game = lifecycle.getGame(ROOM_ID);
            realStateService.broadcastShowdownResults(ROOM_ID, game, List.of(game.getPlayers().getFirst()), 50);
            assertTrue(realStateService.hasRetainedSettlement(ROOM_ID));

            lifecycle.performGameCleanup(ROOM_ID);
            assertFalse(realStateService.hasRetainedSettlement(ROOM_ID),
                    "performGameCleanup must evict retained settlement for cleaned-up game");

            lifecycle.restoreGame(game);
            realStateService.broadcastShowdownResults(ROOM_ID, game, List.of(game.getPlayers().getFirst()), 50);
            assertTrue(realStateService.hasRetainedSettlement(ROOM_ID));

            lifecycle.evictGame(ROOM_ID);
            assertFalse(realStateService.hasRetainedSettlement(ROOM_ID),
                    "evictGame must evict retained settlement for evicted game");
        }
    }
}