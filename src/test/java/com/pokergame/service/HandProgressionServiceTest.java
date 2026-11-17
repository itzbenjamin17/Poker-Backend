package com.pokergame.service;

import com.pokergame.util.RoomCoordinator;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import com.pokergame.testutil.TestTimings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HandProgressionService} hand initialization, turn-outcome transitions,
 * auto-advance stepping, and post-showdown scheduling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("HandProgressionService unit tests")
class HandProgressionServiceTest {

    private static final String GAME_ID = "progression-room";
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskScheduler springScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private GameStateService gameStateService;

    @Mock
    private ObjectProvider<GameLifecycleService> lifecycleProvider;

    private Clock fixedClock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private GameTaskScheduler taskScheduler;
    private GameCompletionHandler completionHandler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(springScheduler, fixedClock);
        taskScheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, fixedClock, TestTimings.DEFAULT_GAME, lifecycleProvider);
        completionHandler = new GameCompletionHandler(
                activeGames, roomService, gameStateService, timerRegistry, taskScheduler,
                fixedClock, TestTimings.game(30_000L, 4_000L, 10_000L));

        lenient().when(springScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));

        Room room = new Room(GAME_ID, "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer("Host");
        room.addPlayer("Player2");
        lenient().when(roomService.getRoom(GAME_ID)).thenReturn(room);
    }

    private HandProgressionService createProgression(long roundEndDisplayDelayMs) {
        return new HandProgressionService(
                activeGames,
                timerRegistry,
                taskScheduler,
                completionHandler,
                gameStateService,
                TestTimings.game(30_000L, roundEndDisplayDelayMs, 10_000L));
    }

    private Game createGame(List<Player> players) {
        Game game = new Game(GAME_ID, players, 6, 5, 10);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            activeGames.replace(game);
        }
        return game;
    }

    private Game createStartedGame() {
        Game game = createGame(List.of(
                new Player("Host", "host", 1000),
                new Player("Player2", "p2", 1000)));
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            createProgression(4_000L).startNewHand(GAME_ID);
        }
        return game;
    }

    @Nested
    @DisplayName("startNewHand")
    class StartNewHandTests {

        @Test
        @DisplayName("starts hand, deals cards, posts blinds, and schedules turn timeout")
        void startNewHand_NormalStart_DealsAndSchedulesTurnTimeout() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            game.scheduleTask(ScheduledGameTask.CLEANUP, FIXED_NOW.toEpochMilli() + 10_000L);

            boolean openReadyGateNow;
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                openReadyGateNow = progression.startNewHand(GAME_ID);
            }

            assertFalse(openReadyGateNow);
            assertEquals(1L, game.getHandNumber());
            assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
            assertNotNull(game.getCurrentPlayer());
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(FIXED_NOW.toEpochMilli() + TestTimings.TURN_ALLOWANCE_MS,
                    game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            verify(gameStateService).broadcastGameState(game);
        }

        @Test
        @DisplayName("guards missing game, pending forfeit, game over, unsettled hand, and terminal reset")
        void startNewHand_GuardsNonStartableStates() {
            HandProgressionService progression = createProgression(4_000L);

            try (LockLease _ = roomCoordinator.acquire("missing")) {
                assertFalse(progression.startNewHand("missing"));
            }

            Game game = createStartedGame();
            clearInvocations(gameStateService, springScheduler);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                // Hand 1 is mid-round -> unsettled -> ignored
                assertFalse(progression.startNewHand(GAME_ID));
                assertEquals(1L, game.getHandNumber());

                // Forfeit pending -> ignored
                game.setForfeitPending(true);
                assertFalse(progression.startNewHand(GAME_ID));
                game.setForfeitPending(false);

                // Game over without settled final pot -> settles via completion handler
                game.dealFlop();
                game.dealTurn();
                game.dealRiver();
                game.markGameOver();
                assertFalse(progression.startNewHand(GAME_ID));
                assertTrue(game.isFinalPotSettled());
                verify(gameStateService).advanceRevisionAndBuildGameEnd(eq(game), any(Player.class), eq(false));
                verify(gameStateService).sendGameEnd(any());
            }
        }

        @Test
        @DisplayName("triggers completion handler when zero-chip elimination leaves single contender on reset")
        void startNewHand_SingleContenderAfterReset_TriggersGameEnd() {
            HandProgressionService progression = createProgression(4_000L);
            Player p1 = new Player("Host", "host", 1000);
            Player p2 = new Player("Player2", "p2", 0);
            Game game = createGame(List.of(p1, p2));

            boolean openReadyGate;
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                openReadyGate = progression.startNewHand(GAME_ID);
            }

            assertFalse(openReadyGate);
            assertTrue(game.isGameOver());
            verify(gameStateService).advanceRevisionAndBuildGameEnd(game, p1, false);
            verify(gameStateService).sendGameEnd(any());
        }
    }

    @Nested
    @DisplayName("applyTurnOutcome")
    class ApplyTurnOutcomeTests {

        @Test
        @DisplayName("NEXT_PLAYER cancels auto-advance, schedules turn timeout, and broadcasts state")
        void applyTurnOutcome_NextPlayer_SchedulesTurnAndCancelsAutoAdvance() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createStartedGame();
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> autoFuture = mock(ScheduledFuture.class);
            doReturn(autoFuture).when(springScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(GAME_ID, ScheduledGameTask.AUTO_ADVANCE, FIXED_NOW.toEpochMilli() + 2000L, () -> {});
            game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, FIXED_NOW.toEpochMilli() + 2000L);
            clearInvocations(gameStateService);

            boolean openGate;
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                openGate = progression.applyTurnOutcome(
                        GAME_ID, game, new TurnOutcome(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, null));
            }

            assertFalse(openGate);
            verify(autoFuture).cancel(false);
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            verify(gameStateService).broadcastGameState(game);
        }

        @Test
        @DisplayName("AUTO_ADVANCING clears turn timeout, schedules auto-advance, and uses selected broadcast")
        void applyTurnOutcome_AutoAdvancing_SchedulesAndBroadcastsNotification() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createStartedGame();
            clearInvocations(gameStateService);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(progression.applyTurnOutcome(
                        GAME_ID, game, new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, null), true));
            }

            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
            verify(gameStateService).broadcastAutoAdvanceNotification(game);

            clearInvocations(gameStateService);
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(progression.applyTurnOutcome(
                        GAME_ID, game, new TurnOutcome(TurnOutcome.TurnOutcomeType.AUTO_ADVANCING, null), false));
            }
            verify(gameStateService).broadcastGameStateWithAutoAdvance(game, "Auto-advancing...");
        }

        @Test
        @DisplayName("SHOWDOWN broadcasts results and opens ready gate immediately when display delay is zero")
        void applyTurnOutcome_ShowdownZeroDelay_ReturnsTrueToOpenReadyGate() {
            HandProgressionService progression = createProgression(0L);
            Game game = createStartedGame();
            clearInvocations(gameStateService);

            boolean openGateNow;
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                openGateNow = progression.applyTurnOutcome(
                        GAME_ID, game, new TurnOutcome(TurnOutcome.TurnOutcomeType.SHOWDOWN, null));
            }

            assertTrue(openGateNow);
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
            verify(gameStateService).broadcastShowdownResults(game);
        }
    }

    @Nested
    @DisplayName("Auto-advance stepping")
    class AutoAdvanceTests {

        @Test
        @DisplayName("advanceAutoAdvanceStep deals flop, turn, river, then resolves showdown")
        void advanceAutoAdvanceStep_WalksAllStreetsThenShowdown() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createStartedGame();
            clearInvocations(gameStateService);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(progression.advanceAutoAdvanceStep(GAME_ID));
                assertEquals(GamePhase.FLOP, game.getCurrentPhase());
                assertEquals(3, game.getCommunityCards().size());
                verify(gameStateService).broadcastGameStateWithAutoAdvance(game, "Dealing flop...");

                assertFalse(progression.advanceAutoAdvanceStep(GAME_ID));
                assertEquals(GamePhase.TURN, game.getCurrentPhase());
                assertEquals(4, game.getCommunityCards().size());
                verify(gameStateService).broadcastGameStateWithAutoAdvance(game, "Dealing turn...");

                assertFalse(progression.advanceAutoAdvanceStep(GAME_ID));
                assertEquals(GamePhase.RIVER, game.getCurrentPhase());
                assertEquals(5, game.getCommunityCards().size());
                verify(gameStateService).broadcastGameStateWithAutoAdvance(game, "Dealing river...");

                assertTrue(progression.advanceAutoAdvanceStep(GAME_ID));
                assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
                verify(gameStateService).broadcastShowdownResults(game);
                verify(gameStateService).broadcastAutoAdvanceComplete(game);
            }
        }

        @Test
        @DisplayName("advanceAutoAdvanceStep skips duplicate distribution in SHOWDOWN or already settled games")
        void advanceAutoAdvanceStep_SkipsShowdownAndSettledGames() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createStartedGame();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.conductShowdown();
            HandSettlementResult settled = game.getLastSettlementResult();
            clearInvocations(gameStateService);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertTrue(progression.advanceAutoAdvanceStep(GAME_ID));
            }

            assertSame(settled, game.getLastSettlementResult());
            verifyNoInteractions(gameStateService);
        }

        @Test
        @DisplayName("applyAutoAdvanceTimeout schedules next step, GAME_END on tournament end, or READY_OPEN on hand end")
        void applyAutoAdvanceTimeout_BranchesCleanly() {
            HandProgressionService delayedProgression = createProgression(5_000L);
            Game preFlopGame = createStartedGame();
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(delayedProgression.applyAutoAdvanceTimeout(GAME_ID, preFlopGame));
            }
            assertEquals(GamePhase.FLOP, preFlopGame.getCurrentPhase());
            assertNotNull(preFlopGame.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));

            // Finish river with display delay -> schedules READY_OPEN
            preFlopGame.dealTurn();
            preFlopGame.dealRiver();
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(delayedProgression.applyAutoAdvanceTimeout(GAME_ID, preFlopGame));
            }
            assertEquals(GamePhase.SHOWDOWN, preFlopGame.getCurrentPhase());
            assertEquals(FIXED_NOW.toEpochMilli() + 5_000L,
                    preFlopGame.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));

            // Finish river with zero display delay -> returns true to open ready gate immediately
            HandProgressionService immediateProgression = createProgression(0L);
            Game riverZeroDelay = createGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 1000)));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                immediateProgression.startNewHand(GAME_ID);
                riverZeroDelay.dealFlop();
                riverZeroDelay.dealTurn();
                riverZeroDelay.dealRiver();
                assertTrue(immediateProgression.applyAutoAdvanceTimeout(GAME_ID, riverZeroDelay));
            }
            assertNull(riverZeroDelay.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));

            // Finish river when tournament decided -> schedules GAME_END after display delay
            Game riverTournamentOver = createGame(List.of(
                    new Player("Host", "host", 1000),
                    new Player("Player2", "p2", 5)));
            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                delayedProgression.startNewHand(GAME_ID);
                riverTournamentOver.dealFlop();
                riverTournamentOver.dealTurn();
                riverTournamentOver.dealRiver();
                Player winner = riverTournamentOver.getPlayers().get(0);
                Player loser = riverTournamentOver.getPlayers().get(1);
                riverTournamentOver.getCommunityCards().clear();
                riverTournamentOver.getCommunityCards().addAll(List.of(
                        new Card(Rank.TEN, Suit.SPADES), new Card(Rank.JACK, Suit.SPADES),
                        new Card(Rank.QUEEN, Suit.SPADES), new Card(Rank.TWO, Suit.DIAMONDS),
                        new Card(Rank.THREE, Suit.CLUBS)));
                winner.getHoleCards().clear();
                winner.getHoleCards().addAll(List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES)));
                loser.getHoleCards().clear();
                loser.getHoleCards().addAll(List.of(new Card(Rank.FOUR, Suit.HEARTS), new Card(Rank.NINE, Suit.DIAMONDS)));
                assertFalse(delayedProgression.applyAutoAdvanceTimeout(GAME_ID, riverTournamentOver));
            }
            assertTrue(riverTournamentOver.isGameDecided());
            assertEquals(FIXED_NOW.toEpochMilli() + TestTimings.FINAL_SHOWDOWN_DISPLAY_DELAY_MS,
                    riverTournamentOver.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        }

        @Test
        @DisplayName("applyAutoAdvanceTimeout in SHOWDOWN clears task and returns false")
        void applyAutoAdvanceTimeout_InShowdown_ClearsTaskAndStops() {
            HandProgressionService progression = createProgression(4_000L);
            Game game = createStartedGame();
            game.dealFlop();
            game.dealTurn();
            game.dealRiver();
            game.conductShowdown();
            game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, FIXED_NOW.toEpochMilli() + 4_000L);
            clearInvocations(gameStateService, springScheduler);

            try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
                assertFalse(progression.applyAutoAdvanceTimeout(GAME_ID, game));
            }

            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
            verifyNoInteractions(gameStateService, springScheduler);
        }
    }
}
