package com.pokergame.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.enums.ForfeitReason;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.ConflictException;
import com.pokergame.exception.ResourceNotFoundException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.model.Game;
import com.pokergame.model.HandPayout;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.testutil.MutableClock;
import com.pokergame.testutil.TestTimings;
import com.pokergame.util.LockLease;
import com.pokergame.util.RoomCoordinator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

/**
 * Unit tests for {@link GameCompletionHandler} final settlement, inactivity abandonment,
 * post-game review recovery/authorization, and cleanup behavior.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("GameCompletionHandler unit tests")
class GameCompletionHandlerTest {

    private static final String ROOM_ID = "test-room-id";
    private static final long BASE_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final GameTimingProperties DEFAULT_TIMINGS =
            TestTimings.game(30_000L, 4_000L, 10_000L);

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ObjectProvider<GameLifecycleService> lifecycleProvider;

    private MutableClock mutableClock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private GameTaskScheduler taskSchedulerFacade;
    private GameStateService realGameStateService;
    private GameCompletionHandler completionHandler;
    private Room testRoom;

    @BeforeEach
    void setUp() {
        mutableClock = new MutableClock(BASE_EPOCH_MS);

        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(taskScheduler, mutableClock);
        taskSchedulerFacade = new GameTaskScheduler(
                activeGames, timerRegistry, roomService, roomCoordinator, mutableClock, DEFAULT_TIMINGS, lifecycleProvider);
        realGameStateService = new GameStateService(roomService, messagingTemplate, mutableClock);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, realGameStateService, timerRegistry,
                taskSchedulerFacade, mutableClock, DEFAULT_TIMINGS);

        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));

        testRoom = new Room(ROOM_ID, "Test Room", "Host", 6, 5, 10, 100, null);
        testRoom.addPlayer("Host");
        testRoom.addPlayer("Player2");
        lenient().when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
    }

    private Game createAndRegisterStartedGame(List<Player> players) {
        Game game = new Game(ROOM_ID, players, testRoom.getMaxPlayers(), 5, 10);
        game.resetForNewHand();
        game.beginHand();
        game.dealHoleCards();
        game.postBlinds();
        registerGame(game);
        return game;
    }

    private void registerGame(Game game) {
        try (LockLease _ = roomCoordinator.acquire(game.getGameId())) {
            activeGames.replace(game);
        }
    }

    @Nested
    @DisplayName("handleGameEnd & handleGameEndWithForfeits")
    class FinalSettlementTests {

        @Test
        @DisplayName("handleGameEnd with missing game no-ops safely and empty roster throws IllegalStateException")
        void handleGameEnd_MissingNoOpsAndEmptyRosterThrows() {
            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire("nonexistent-id")) {
                assertDoesNotThrow(() -> completionHandler.handleGameEnd("nonexistent-id"));
            }
            verifyNoInteractions(messagingTemplate, taskScheduler);

            Game emptyGame = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            emptyGame.getPlayers().clear();
            emptyGame.getActivePlayers().clear();

            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                assertThrows(IllegalStateException.class, () -> completionHandler.handleGameEnd(ROOM_ID));
            }
            verifyNoInteractions(messagingTemplate, taskScheduler);
            assertNull(emptyGame.getScheduledTaskTime(ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("handleGameEnd skips when paused for disconnected survivor or game is already finally settled")
        void handleGameEnd_PausedForDisconnectedSurvivorOrAlreadySettled_NoOps() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.pauseForDisconnectedSurvivor(List.of());
            clearInvocations(messagingTemplate, taskScheduler);

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }
            verifyNoInteractions(messagingTemplate);
            assertFalse(game.isGameOver());

            game.clearPausedForDisconnectedSurvivor();
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }
            assertTrue(game.isGameOver());
            assertTrue(game.isFinalPotSettled());

            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }
            verifyNoInteractions(messagingTemplate);
        }

        @Test
        @DisplayName("handleGameEnd picks winner with chips in heads-up all-in and broadcasts GAME_END")
        void handleGameEnd_HeadsUpAllIn_ShouldPickWinnerWithChips() {
            Player host = new Player("Host", "host", 0);
            Player p2 = new Player("Player2", "p2", 2000);
            Game game = new Game(ROOM_ID, List.of(host, p2), 6, 5, 10);
            game.dealHoleCards();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            registerGame(game);

            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/game/" + ROOM_ID), captor.capture());
            PublicGameStateResponse response = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
            assertNotNull(response.gameEnd());
            assertEquals("Player2", response.gameEnd().winner());
            assertFalse(response.gameEnd().isForfeit());
            assertFalse(response.gameEnd().abandoned());
            assertTrue(game.isGameOver());
            assertTrue(testRoom.isFinished());
            assertNotNull(game.getScheduledTaskTime(ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("handleGameEndWithForfeits marks forfeited players out and broadcasts forfeit")
        void handleGameEndWithForfeits_MarksForfeitedPlayersAndBroadcastsForfeit() {
            Player host = new Player("Host", "host", 500);
            Player p2 = new Player("Player2", "p2", 1000);
            Game game = createAndRegisterStartedGame(List.of(host, p2));

            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEndWithForfeits(ROOM_ID, host, List.of("Player2"), ForfeitReason.PLAYER_LEFT);
            }

            assertTrue(p2.isOut());
            assertTrue(game.isGameOver());
            assertTrue(testRoom.isFinished());

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/game/" + ROOM_ID), captor.capture());
            PublicGameStateResponse response = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
            assertNotNull(response.gameEnd());
            assertEquals("Host", response.gameEnd().winner());
            assertTrue(response.gameEnd().isForfeit());
            assertEquals("PLAYER_LEFT", response.gameEnd().reason());
            assertEquals(List.of("Player2"), response.gameEnd().forfeitedPlayers());
            assertSame(response, game.getGameEndResult());
            assertEquals(BASE_EPOCH_MS + DEFAULT_TIMINGS.gameEndDisplayDelayMs(),
                    game.getScheduledTaskTime(ScheduledGameTask.CLEANUP));
            verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("final carry-over flows once into frozen PublicGameStateResponse.GameEnd and repeat call is idempotent")
        void finalCarryOver_FlowsIntoFrozenGameEndDtoOnce() {
            Player winner = Player.restoreBuilder().name("Host").playerId("host").chips(1000).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false).allIn(false)
                    .out(false).disconnected(false).readyForNextHand(false).build();
            Player eliminated = Player.restoreBuilder().name("Player2").playerId("p2").chips(0).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true).allIn(false)
                    .out(true).disconnected(false).readyForNextHand(false).build();
            Game finishedGame = Game.restoreBuilder().gameId(ROOM_ID).players(List.of(winner, eliminated))
                    .activePlayerIds(List.of("host")).remainingDeck(List.of()).communityCards(List.of()).pot(10)
                    .dealerPlayerId("host").smallBlindPlayerId("host").bigBlindPlayerId("p2")
                    .currentPlayerId("host").currentHighestBet(0).currentPhase(GamePhase.SHOWDOWN).gameOver(false).smallBlind(5).bigBlind(10)
                    .handContributions(Map.of()).carryOverPot(10)
                    .lastHandPayout(new HandPayout(Map.of(), Map.of(), Set.of(), 10, false))
                    .allPlayersHadInitialTurn(true).actedPlayersInRound(Set.of())
                    .scheduledTimes(Map.of()).build();
            registerGame(finishedGame);

            clearInvocations(messagingTemplate);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/game/" + ROOM_ID), captor.capture());
            PublicGameStateResponse result = assertInstanceOf(PublicGameStateResponse.class, captor.getValue());
            assertNotNull(result.gameEnd());
            assertEquals(1010, result.gameEnd().winnerChips());
            assertEquals(0, result.pot());
            assertEquals(1010, result.players().stream()
                    .filter(PublicGameStateResponse.PlayerState::isWinner).findFirst().orElseThrow().chips());
            assertTrue(finishedGame.isFinalPotSettled());

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }
            verify(messagingTemplate, times(1)).convertAndSend(eq("/game/" + ROOM_ID), any(Object.class));
            assertEquals(1010, winner.getChips());
        }

        @Test
        @DisplayName("handleGameEnd preserves existing runtime cleanup timer when cancelling timers on end")
        void handleGameEnd_CancelsProgressionTimersWhileRetainingCleanupSlot() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> cleanupFuture = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> turnFuture = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> endCleanupFuture = mock(ScheduledFuture.class);
            doReturn(cleanupFuture).doReturn(turnFuture).doReturn(endCleanupFuture)
                    .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, BASE_EPOCH_MS + 90_000L, () -> {});
            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT, BASE_EPOCH_MS + 10_000L, () -> {});

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }

            verify(turnFuture).cancel(false);
            verify(cleanupFuture).cancel(false); // replaced by scheduleGameCleanup inside handleGameEnd
            verify(endCleanupFuture, never()).cancel(false);
            assertEquals(BASE_EPOCH_MS + DEFAULT_TIMINGS.gameEndDisplayDelayMs(),
                    game.getScheduledTaskTime(ScheduledGameTask.CLEANUP));
        }
    }

    @Nested
    @DisplayName("Inactivity Abandonment & Expiration")
    class AbandonmentTests {

        @Test
        @DisplayName("abandonGameIfInactive guards against null room, changed deadline, and future deadline")
        void abandonGameIfInactive_GuardsStaleAndEarlyCallbacks() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            testRoom.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + 50_000L);

            when(roomService.getRoom("missing")).thenReturn(null);
            try (LockLease _ = roomCoordinator.acquire("missing")) {
                completionHandler.abandonGameIfInactive("missing", BASE_EPOCH_MS);
            }

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                // Deadline mismatch
                completionHandler.abandonGameIfInactive(ROOM_ID, BASE_EPOCH_MS + 10_000L);
                assertFalse(game.isGameOver());

                // Matches, but clock has not reached deadline yet
                completionHandler.abandonGameIfInactive(ROOM_ID, BASE_EPOCH_MS + 50_000L);
                assertFalse(game.isGameOver());
            }

            // Clock reaches deadline -> abandons
            mutableClock.setMillis(BASE_EPOCH_MS + 50_000L);
            clearInvocations(messagingTemplate);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.abandonGameIfInactive(ROOM_ID, BASE_EPOCH_MS + 50_000L);
            }

            assertTrue(game.isGameOver());
            assertTrue(testRoom.isFinished());
            assertNotNull(game.getGameEndResult());
            assertNotNull(game.getGameEndResult().gameEnd());
            assertTrue(game.getGameEndResult().gameEnd().abandoned());
            assertEquals("INACTIVITY", game.getGameEndResult().gameEnd().reason());
            assertEquals(BASE_EPOCH_MS + 50_000L + DEFAULT_TIMINGS.gameEndDisplayDelayMs(),
                    game.getScheduledTaskTime(ScheduledGameTask.CLEANUP));
            verify(messagingTemplate).convertAndSend(eq("/game/" + ROOM_ID), eq(game.getGameEndResult()));
        }

        @Test
        @DisplayName("abandonGameDueToInactivity clears timers, invalidates tasks, and is idempotent")
        void abandonGameDueToInactivity_ClearsProgressionAndIsIdempotent() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, BASE_EPOCH_MS + 5000L);
            game.scheduleTask(ScheduledGameTask.READY_OPEN, BASE_EPOCH_MS + 6000L);
            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT, BASE_EPOCH_MS + 5000L, () -> {});
            long timerVersionBefore = game.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT);

            clearInvocations(messagingTemplate);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.abandonGameDueToInactivity(ROOM_ID);
            }

            assertTrue(game.isGameOver());
            assertNull(game.getScheduledTaskTime(ScheduledGameTask.TURN_TIMEOUT));
            assertNull(game.getScheduledTaskTime(ScheduledGameTask.READY_OPEN));
            assertThat(game.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT)).isGreaterThan(timerVersionBefore);
            verify(messagingTemplate, times(1)).convertAndSend(eq("/game/" + ROOM_ID), any(PublicGameStateResponse.class));

            // Repeat call is idempotent
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.abandonGameDueToInactivity(ROOM_ID);
            }
            verify(messagingTemplate, times(1)).convertAndSend(eq("/game/" + ROOM_ID), any(PublicGameStateResponse.class));
        }

        @Test
        @DisplayName("handleGameEnd returns early without showdown when PRE_FLOP game is already abandoned")
        void handleGameEnd_WhenPreFlopGameAlreadyAbandoned_NoOpsWithoutShowdown() {
            Game game = new Game(
                    ROOM_ID,
                    List.of(new Player("Host", "host", 100), new Player("Player2", "p2", 100)),
                    testRoom.getMaxPlayers(),
                    5,
                    10);
            game.resetForNewHand();
            game.beginHand();
            game.dealHoleCards();
            game.postBlinds();
            registerGame(game);

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.abandonGameDueToInactivity(ROOM_ID);
            }
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertTrue(game.getCommunityCards().isEmpty());
            assertTrue(game.isGameOver());
            assertTrue(game.isAbandoned());
            assertFalse(game.isFinalPotSettled());

            clearInvocations(messagingTemplate, taskScheduler);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.handleGameEnd(ROOM_ID);
            }

            assertFalse(game.isFinalPotSettled());
            assertTrue(game.isAbandoned());
            verifyNoInteractions(messagingTemplate, taskScheduler);
        }

        @Test
        @DisplayName("rejectExpiredActivity throws GAME_EXPIRED without abandoning when room inactivity expired")
        void rejectExpiredActivity_WhenExpired_ThrowsWithoutAbandoning() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            testRoom.setInactivityDeadlineEpochMs(BASE_EPOCH_MS + 1000L);

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                when(roomService.getRoom(ROOM_ID)).thenReturn(null);
                assertDoesNotThrow(() -> completionHandler.rejectExpiredActivity(ROOM_ID));
                when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
                assertDoesNotThrow(() -> completionHandler.rejectExpiredActivity(ROOM_ID));
            }

            mutableClock.setMillis(BASE_EPOCH_MS + 1000L);
            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                BadRequestException ex = assertThrows(BadRequestException.class,
                        () -> completionHandler.rejectExpiredActivity(ROOM_ID));
                assertEquals("GAME_EXPIRED", ex.actionErrorCode());
            }
            assertFalse(game.isGameOver());
            assertNull(game.getGameEndResult());
        }
    }

    @Nested
    @DisplayName("getGameReview & Cleanup")
    class ReviewAndCleanupTests {

        @Test
        @DisplayName("getGameReview enforces 404, 409, 200 (winner, out, departed), and 403 authorization rules")
        void getGameReview_AuthorizationAndStatusRules() {
            testRoom.addPlayer("Player3");
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000),
                    new Player("Player3", "p3", 1000)));

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                assertThrows(ConflictException.class, () -> completionHandler.getGameReview(ROOM_ID, "Host"));

                completionHandler.handleGameEndWithForfeits(
                        ROOM_ID, game.getPlayers().getFirst(), List.of("Player2", "Player3"), ForfeitReason.DISCONNECTED);
                PublicGameStateResponse gameEndResult = game.getGameEndResult();
                assertNotNull(gameEndResult);

                assertSame(gameEndResult, completionHandler.getGameReview(ROOM_ID, "Host"));
                assertSame(gameEndResult, completionHandler.getGameReview(ROOM_ID, "Player2"));

                testRoom.removePlayer("Player3");
                assertSame(gameEndResult, completionHandler.getGameReview(ROOM_ID, "Player3"));

                assertThrows(UnauthorisedActionException.class,
                        () -> completionHandler.getGameReview(ROOM_ID, "Stranger"));

                // Expired game -> 404
                game.scheduleTask(ScheduledGameTask.CLEANUP, BASE_EPOCH_MS - 1000L);
                assertTrue(completionHandler.isGameExpired(game));
                assertThrows(ResourceNotFoundException.class,
                        () -> completionHandler.getGameReview(ROOM_ID, "Host"));
            }

            try (LockLease _ = roomCoordinator.acquire("non-existent-game")) {
                assertThrows(ResourceNotFoundException.class,
                        () -> completionHandler.getGameReview("non-existent-game", "Host"));
            }
        }

        @Test
        @DisplayName("getGameReview throws IllegalStateException when game is over with no stored result")
        void getGameReview_WhenGameOverAndResultNull_ThrowsIllegalStateException() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            game.markGameOver();
            game.setGameEndResult(null);

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                assertThrows(IllegalStateException.class, () -> completionHandler.getGameReview(ROOM_ID, "Host"));

                game.dealFlop();
                game.dealTurn();
                game.dealRiver();
                game.conductShowdown();
                assertThrows(IllegalStateException.class, () -> completionHandler.getGameReview(ROOM_ID, "Host"));
            }
        }

        @Test
        @DisplayName("deleteGameAndRoom cancels timers, removes game from registry, and destroys room")
        void deleteGameAndRoom_CleansRegistryAndDestroysRoom() {
            createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> cleanupFuture = mock(ScheduledFuture.class);
            doReturn(cleanupFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, BASE_EPOCH_MS + 10_000L, () -> {});

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                completionHandler.deleteGameAndRoom(ROOM_ID);
                assertNull(activeGames.get(ROOM_ID));
            }
            verify(cleanupFuture).cancel(false);
            verify(roomService).destroyRoom(ROOM_ID);
        }

        @Test
        @DisplayName("cleanupAbandonedGameIfRoomEmpty removes game only when zero contenders and zero room members")
        void cleanupAbandonedGameIfRoomEmpty_RespectsContendersAndRoomMembership() {
            Game game = createAndRegisterStartedGame(List.of(
                    new Player("Host", "host", 100),
                    new Player("Player2", "p2", 100)));
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> cleanupFuture = mock(ScheduledFuture.class);
            doReturn(cleanupFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, BASE_EPOCH_MS + 10_000L, () -> {});

            try (LockLease _ = roomCoordinator.acquire(ROOM_ID)) {
                // Still has contenders and room members -> no-op
                completionHandler.cleanupAbandonedGameIfRoomEmpty(ROOM_ID, testRoom, game);
                assertNotNull(activeGames.get(ROOM_ID));

                // Zero contenders, but room still has a member -> no-op
                game.getPlayers().forEach(Player::setIsOut);
                assertEquals(0, game.countContenders());
                completionHandler.cleanupAbandonedGameIfRoomEmpty(ROOM_ID, testRoom, game);
                assertNotNull(activeGames.get(ROOM_ID));

                // Room becomes empty -> clears tasks, cancels timers, removes game
                testRoom.removePlayer("Host");
                testRoom.removePlayer("Player2");
                completionHandler.cleanupAbandonedGameIfRoomEmpty(ROOM_ID, testRoom, game);
                assertNull(activeGames.get(ROOM_ID));
            }
            verify(cleanupFuture).cancel(false);
        }
    }
}
