package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.GameTaskScheduler.ScheduledTimer;
import com.pokergame.testutil.MutableClock;
import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GameTaskScheduler} timer descriptors, generation/deadline validation,
 * inactivity rescheduling, and runtime timer claim guards.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("GameTaskScheduler unit tests")
class GameTaskSchedulerTest {

    private static final String GAME_ID = "scheduler-room";
    private static final long BASE_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    @Mock
    private TaskScheduler springScheduler;

    @Mock
    private RoomService roomService;

    @Mock
    private HandEvaluatorService handEvaluator;

    @Mock
    private ObjectProvider<GameLifecycleService> lifecycleProvider;

    @Mock
    private GameLifecycleService gameLifecycleService;

    private MutableClock mutableClock;
    private RoomCoordinator roomCoordinator;
    private ActiveGameRegistry activeGames;
    private GameTimerRegistry timerRegistry;
    private GameTaskScheduler scheduler;
    private Room room;

    @BeforeEach
    void setUp() {
        mutableClock = new MutableClock(BASE_EPOCH_MS);

        roomCoordinator = new RoomCoordinator();
        activeGames = new ActiveGameRegistry(roomCoordinator);
        timerRegistry = new GameTimerRegistry(springScheduler, mutableClock);
        scheduler = new GameTaskScheduler(activeGames, timerRegistry, roomService, mutableClock, lifecycleProvider);

        lenient().when(lifecycleProvider.getObject()).thenReturn(gameLifecycleService);
        lenient().when(springScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(_ -> mock(ScheduledFuture.class));

        room = new Room(GAME_ID, "Room", "Host", 6, 5, 10, 100, null);
        room.addPlayer("Host");
        room.addPlayer("Player2");
        lenient().when(roomService.getRoom(GAME_ID)).thenReturn(room);
    }

    private Game createAndRegisterGame() {
        Game game = new Game(GAME_ID, List.of(
                new Player("Host", "host", 1000),
                new Player("Player2", "p2", 1000)), 6, 5, 10, handEvaluator);
        game.resetForNewHand();
        game.beginHand();
        game.dealHoleCards();
        game.postBlinds();
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            activeGames.replace(game);
        }
        return game;
    }

    @Test
    @DisplayName("listActiveTimers captures all scheduled deadlines with matching hand and generation")
    void listActiveTimers_CapturesAllScheduledTasks() {
        Game game = createAndRegisterGame();
        assertTrue(GameTaskScheduler.listActiveTimers(GAME_ID, game).isEmpty());

        game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, BASE_EPOCH_MS + 4_000L);
        game.scheduleTask(ScheduledGameTask.CLEANUP, BASE_EPOCH_MS + 60_000L);

        List<ScheduledTimer> timers = GameTaskScheduler.listActiveTimers(GAME_ID, game);
        assertEquals(2, timers.size());

        ScheduledTimer autoAdvance = timers.stream()
                .filter(t -> t.task() == ScheduledGameTask.AUTO_ADVANCE)
                .findFirst()
                .orElseThrow();
        assertEquals(GAME_ID, autoAdvance.gameId());
        assertEquals(BASE_EPOCH_MS + 4_000L, autoAdvance.deadline());
        assertEquals(game.getHandNumber(), autoAdvance.handNumber());
        assertEquals(game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE), autoAdvance.generation());

        ScheduledTimer cleanup = timers.stream()
                .filter(t -> t.task() == ScheduledGameTask.CLEANUP)
                .findFirst()
                .orElseThrow();
        assertEquals(BASE_EPOCH_MS + 60_000L, cleanup.deadline());
        assertEquals(game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP), cleanup.generation());
    }

    @Test
    @DisplayName("registerTimer ignores null game and stale handNumber, generation, or deadline tokens")
    void registerTimer_IgnoresNullGameAndMismatchedTokens() {
        ScheduledTimer missingGameTimer = new ScheduledTimer(GAME_ID, ScheduledGameTask.NEW_HAND, 5000L, 1L, 1L);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.registerTimer(missingGameTimer);
        }
        verifyNoInteractions(springScheduler);

        Game game = createAndRegisterGame();
        game.scheduleTask(ScheduledGameTask.NEW_HAND, BASE_EPOCH_MS + 5000L);
        long deadline = game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND);
        long hand = game.getHandNumber();
        long gen = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        clearInvocations(springScheduler);

        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.registerTimer(new ScheduledTimer(GAME_ID, ScheduledGameTask.NEW_HAND, deadline, hand + 1, gen));
            scheduler.registerTimer(new ScheduledTimer(GAME_ID, ScheduledGameTask.NEW_HAND, deadline, hand, gen - 1));
            scheduler.registerTimer(new ScheduledTimer(GAME_ID, ScheduledGameTask.NEW_HAND, deadline + 1, hand, gen));
        }

        verifyNoInteractions(springScheduler);
    }

    @Test
    @DisplayName("registerTimer schedules callback that forwards exact timer tokens to lifecycle service")
    void registerTimer_ValidTokens_SchedulesAndInvokesLifecycle() {
        Game game = createAndRegisterGame();
        long deadline = BASE_EPOCH_MS + 10_000L;
        game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, deadline);
        long hand = game.getHandNumber();
        long gen = game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.registerTimer(new ScheduledTimer(GAME_ID, ScheduledGameTask.TURN_TIMEOUT, deadline, hand, gen));
        }

        verify(springScheduler).schedule(callbackCaptor.capture(), eq(Instant.ofEpochMilli(deadline)));

        callbackCaptor.getValue().run();
        verify(gameLifecycleService).executeScheduledTask(
                GAME_ID, ScheduledGameTask.TURN_TIMEOUT, deadline, hand, gen);
    }

    @Test
    @DisplayName("scheduleReadyCountdownOpen opens immediately when delay is zero and schedules when positive")
    void scheduleReadyCountdownOpen_DelaySemantics() {
        Game game = createAndRegisterGame();
        clearInvocations(springScheduler);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            assertTrue(scheduler.scheduleReadyCountdownOpen(GAME_ID, 0L));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));

            assertFalse(scheduler.scheduleReadyCountdownOpen(GAME_ID, 3_500L));
            assertEquals(BASE_EPOCH_MS + 3_500L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));

            assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleReadyCountdownOpen(GAME_ID, -1L));
            assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleGameCleanup(GAME_ID, -5L));
            assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleGameEnd(GAME_ID, -10L));
        }
        verify(springScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 3_500L)));
        verifyNoMoreInteractions(springScheduler);
    }

    @Test
    @DisplayName("scheduling helpers record deadlines on game and ignore null or forfeit-pending games")
    void schedulingHelpers_RespectForfeitPendingAndMissingGames() {
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.scheduleAutoAdvance(GAME_ID);
            scheduler.scheduleTurnTimeout(GAME_ID);
            verifyNoInteractions(springScheduler);
        }

        Game game = createAndRegisterGame();
        game.setForfeitPending(true);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.scheduleTurnTimeout(GAME_ID);
            scheduler.scheduleGameEnd(GAME_ID, 2_000L);
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        }

        game.setForfeitPending(false);
        clearInvocations(springScheduler);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.scheduleTurnTimeout(GAME_ID);
            scheduler.scheduleAutoAdvance(GAME_ID);
            scheduler.scheduleGameCleanup(GAME_ID, 12_000L);
        }

        assertEquals(BASE_EPOCH_MS + GameTaskScheduler.TURN_ALLOWANCE_MS,
                game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
        assertEquals(BASE_EPOCH_MS + 4_000L,
                game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(BASE_EPOCH_MS + 12_000L,
                game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        verify(springScheduler).schedule(any(Runnable.class),
                eq(Instant.ofEpochMilli(BASE_EPOCH_MS + GameTaskScheduler.TURN_ALLOWANCE_MS)));
        verify(springScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 4_000L)));
        verify(springScheduler).schedule(any(Runnable.class), eq(Instant.ofEpochMilli(BASE_EPOCH_MS + 12_000L)));
        verifyNoMoreInteractions(springScheduler);
    }

    @Test
    @DisplayName("resetGameInactivity extends room inactivity deadline and wires abandonment callback")
    void resetGameInactivity_ExtendsDeadlineAndRegistersCallback() {
        Game game = createAndRegisterGame();
        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        doReturn(mock(ScheduledFuture.class)).when(springScheduler)
                .schedule(callbackCaptor.capture(), instantCaptor.capture());

        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.resetGameInactivity(GAME_ID);
        }

        long expectedDeadline = BASE_EPOCH_MS + Room.GAME_INACTIVITY_ALLOWANCE_MS;
        assertEquals(expectedDeadline, room.getInactivityDeadlineEpochMs());
        assertEquals(Instant.ofEpochMilli(expectedDeadline), instantCaptor.getValue());

        callbackCaptor.getValue().run();
        verify(gameLifecycleService).abandonGameIfInactive(GAME_ID, expectedDeadline);

        // Once game is over, resetGameInactivity is a no-op
        game.markGameOver();
        mutableClock.setMillis(BASE_EPOCH_MS + 5_000L);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.resetGameInactivity(GAME_ID, game);
        }
        assertEquals(expectedDeadline, room.getInactivityDeadlineEpochMs());
    }

    @Test
    @DisplayName("claimTimer claims valid token once, rejects null/stale tokens, and cancels non-cleanup futures on game over")
    void claimTimer_ValidatesTokensAndCancelsOnGameOver() {
        assertFalse(scheduler.claimTimer(null, ScheduledGameTask.TURN_TIMEOUT, 100L, 1L, 1L));

        Game game = createAndRegisterGame();
        long deadline = BASE_EPOCH_MS + 5_000L;
        game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, deadline);
        long hand = game.getHandNumber();
        long gen = game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);

        // Stale deadline rejected without clearing
        assertFalse(scheduler.claimTimer(game, ScheduledGameTask.TURN_TIMEOUT, deadline + 1, hand, gen));
        assertEquals(deadline, game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));

        // Exact match succeeds once and clears deadline
        assertTrue(scheduler.claimTimer(game, ScheduledGameTask.TURN_TIMEOUT, deadline, hand, gen));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.TURN_TIMEOUT));
        assertFalse(scheduler.claimTimer(game, ScheduledGameTask.TURN_TIMEOUT, deadline, hand, gen));

        // Game over cancels registered non-cleanup runtime timer
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> turnFuture = mock(ScheduledFuture.class);
        doReturn(turnFuture).when(springScheduler).schedule(any(Runnable.class), any(Instant.class));
        long secondDeadline = BASE_EPOCH_MS + 10_000L;
        game.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, secondDeadline);
        long secondGen = game.getScheduledTaskGeneration(ScheduledGameTask.TURN_TIMEOUT);
        try (LockLease _ = roomCoordinator.acquire(GAME_ID)) {
            scheduler.registerTimer(new ScheduledTimer(
                    GAME_ID, ScheduledGameTask.TURN_TIMEOUT, secondDeadline, hand, secondGen));
        }

        game.markGameOver();
        assertFalse(scheduler.claimTimer(game, ScheduledGameTask.TURN_TIMEOUT, secondDeadline, hand, secondGen));
        verify(turnFuture).cancel(false);
        timerRegistry.cancelTask(GAME_ID, ScheduledGameTask.TURN_TIMEOUT);
        verifyNoMoreInteractions(turnFuture);
    }
}
