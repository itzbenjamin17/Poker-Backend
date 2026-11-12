package com.pokergame.service;

import com.pokergame.model.HandEvaluationResult;
import com.pokergame.dto.request.PlayerActionRequest;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.dto.response.PublicGameStateResponse;
import com.pokergame.dto.response.PublicPlayerState;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.PlayerAction;
import com.pokergame.enums.Rank;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.enums.Suit;
import com.pokergame.model.Card;
import com.pokergame.model.Game;
import com.pokergame.model.HandSettlementResult;
import com.pokergame.model.Player;
import com.pokergame.model.Room;
import com.pokergame.service.GameTaskScheduler.TaskIntent;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GameTimerRegistry} in-memory scheduler registrations and
 * scheduled task execution guards migrated from {@link GameLifecycleServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GameTimerRegistry and Scheduled Task Dispatch")
class GameTimerRegistryTest {

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
    private GameTimerRegistry runtimeTimers;
    private Room testRoom;
    private static final String ROOM_ID = "test-room-id";

    @BeforeEach
    void setUp() {
        runtimeTimers = new GameTimerRegistry(taskScheduler);
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

    @Test
    void immediateInactivityCompletionDoesNotRemoveReplacement() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> replacement = mock(ScheduledFuture.class);
        Runnable replacementCallback = mock(Runnable.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return first;
                })
                .thenAnswer(invocation -> replacement);

        runtimeTimers.scheduleInactivity(ROOM_ID, 100L, 100L,
                () -> runtimeTimers.scheduleInactivity(ROOM_ID, 200L, 100L, replacementCallback));

        verify(first).cancel(false);
        verifyNoInteractions(replacement, replacementCallback);
        assertEquals(0, runtimeTimers.countForGame(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, true);
        verify(replacement).cancel(false);
    }

    @Test
    void cancellingInactivityBeforeFutureAttachmentCancelsReturnedFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            runtimeTimers.cancelInactivity(ROOM_ID);
            invocation.<Runnable>getArgument(0).run();
            return future;
        });

        runtimeTimers.scheduleInactivity(ROOM_ID, 200L, 100L, () -> fail("Unexpected timeout"));

        verify(future).cancel(false);
        runtimeTimers.cancelInactivity(ROOM_ID);
        verifyNoMoreInteractions(future);
    }

    @Test
    void failedInactivitySchedulingLeavesNoRunnableRegistration() {
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        RuntimeException schedulerFailure = new IllegalStateException("Scheduler unavailable");
        when(taskScheduler.schedule(callbacks.capture(), any(Instant.class)))
                .thenReturn(null)
                .thenThrow(schedulerFailure);
        Runnable expire = mock(Runnable.class);

        runtimeTimers.scheduleInactivity(ROOM_ID, 100L, 100L, expire);
        callbacks.getValue().run();
        verifyNoInteractions(expire);
        assertSame(schedulerFailure, assertThrows(RuntimeException.class,
                () -> runtimeTimers.scheduleInactivity(ROOM_ID, 100L, 100L, expire)));

        callbacks.getValue().run();
        verifyNoInteractions(expire);
    }

    @Test
    void missingGameIdDoesNotScheduleInactivity() {
        runtimeTimers.scheduleInactivity(null, 100L, 100L, () -> fail("Unexpected timeout"));

        verifyNoInteractions(taskScheduler);
    }

    @Test
    void firstHandKeepsTheOriginalInactivityCallbackExecutable() {
        Instant startedAt = Instant.now();
        List<Runnable> inactivityCallbacks = captureInactivityCallbacks();
        gameLifecycleService.setClock(Clock.fixed(startedAt, ZoneOffset.UTC));
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);

        gameLifecycleService.createGameFromRoom(ROOM_ID);

        long deadline = startedAt.toEpochMilli() + GameLifecycleService.GAME_INACTIVITY_ALLOWANCE_MS;
        assertEquals(deadline, gameLifecycleService.getInactivityDeadline(ROOM_ID));
        assertEquals(1, inactivityCallbacks.size());
        assertEquals(1, gameLifecycleService.getRuntimeTaskCount(ROOM_ID), "Only the turn timer is counted");
        gameLifecycleService.setClock(Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC));
        inactivityCallbacks.getFirst().run();
        Game game = gameLifecycleService.getGame(ROOM_ID);
        assertTrue(game.isGameOver(), "The original inactivity callback must abandon the first hand");
        assertTrue(testRoom.isTerminal());
    }

    @Test
    void readinessActivitySupersedesTheOldInactivityCallbackAndTheNextHandKeepsItsReplacement() {
        Instant startedAt = Instant.now();
        List<Runnable> inactivityCallbacks = captureInactivityCallbacks();
        gameLifecycleService.setClock(Clock.fixed(startedAt, ZoneOffset.UTC));
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long firstHand = game.getHandNumber();
        PlayerActionService actions = new PlayerActionService(gameLifecycleService, gameStateService);
        actions.processPlayerAction(ROOM_ID, new PlayerActionRequest(PlayerAction.FOLD, null),
                game.getCurrentPlayer().getName());
        Runnable previousInactivity = inactivityCallbacks.getLast();

        Instant readiedAt = startedAt.plusSeconds(1);
        gameLifecycleService.setClock(Clock.fixed(readiedAt, ZoneOffset.UTC));
        gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Host");
        gameLifecycleService.markPlayerReadyForNextHand(ROOM_ID, "Player2");

        long deadline = readiedAt.toEpochMilli() + GameLifecycleService.GAME_INACTIVITY_ALLOWANCE_MS;
        assertEquals(firstHand + 1, game.getHandNumber());
        assertEquals(deadline, gameLifecycleService.getInactivityDeadline(ROOM_ID));
        assertNotSame(previousInactivity, inactivityCallbacks.getLast());
        gameLifecycleService.setClock(Clock.fixed(Instant.ofEpochMilli(deadline - 1), ZoneOffset.UTC));
        previousInactivity.run();
        assertFalse(game.isGameOver());
        gameLifecycleService.setClock(Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC));
        inactivityCallbacks.getLast().run();
        assertTrue(game.isGameOver(), "The replacement inactivity callback must abandon the next hand");
        assertTrue(testRoom.isTerminal());
    }

    /** Captures the 15-minute allowance separately from shorter turn and ready timers. */
    private List<Runnable> captureInactivityCallbacks() {
        List<Runnable> callbacks = new ArrayList<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            if (call.getArgument(1, Instant.class).isAfter(Instant.now().plusSeconds(600))) {
                callbacks.add(call.getArgument(0));
            }
            return mock(ScheduledFuture.class);
        });
        return callbacks;
    }

    @Test
    void startedReadyCallbackCannotExpireAReplacementWithTheSameDeadline() throws Exception {
        Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        gameLifecycleService.setClock(clock);
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        List<Runnable> callbacks = new ArrayList<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            callbacks.add(call.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        new PlayerActionService(gameLifecycleService, gameStateService).processPlayerAction(
                ROOM_ID, new PlayerActionRequest(PlayerAction.FOLD, null), game.getCurrentPlayer().getName());
        gameLifecycleService.startReadyCountdown(ROOM_ID, 60_000);
        long hand = game.getHandNumber();
        long deadline = game.getReadyCountdownDeadlineEpochMs();
        long oldGeneration = game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT);
        HandSettlementResult settled = game.getLastSettlementResult();
        Runnable oldCallback = callbacks.getLast();
        RoomCoordinator coordinator = gameLifecycleService.getRoomCoordinator();

        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> started;
            try (var lease = coordinator.acquire(ROOM_ID)) {
                started = executor.submit(oldCallback);
                // The second reservation can only come from the callback, after its runtime guard.
                await().atMost(Duration.ofSeconds(5)).until(
                        () -> coordinator.getLockRegistry().reservationCount(ROOM_ID) == 2);
                gameLifecycleService.startReadyCountdown(ROOM_ID, 60_000);
                assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());
                assertNotEquals(oldGeneration, game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT));
                gameLifecycleService.setClock(Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC));
            }
            started.get(5, TimeUnit.SECONDS);

            long generation = game.getScheduledTaskGeneration(ScheduledGameTask.READY_TIMEOUT);
            assertEquals(hand, game.getHandNumber(), "Old callback must not deal the next hand");
            assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());
            assertEquals(deadline, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertSame(settled, game.getLastSettlementResult());

            gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.READY_TIMEOUT,
                    deadline, hand - 1, generation);
            assertEquals(hand, game.getHandNumber());
            assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());

            callbacks.getLast().run();
            gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.READY_TIMEOUT,
                    deadline, hand, generation);
            assertEquals(hand + 1, game.getHandNumber());
            assertNull(game.getReadyCountdownDeadlineEpochMs());
            assertEquals(200, game.getPlayers().stream().mapToInt(Player::getChips).sum() + game.getPot());
        } finally {
            stopExecutor(executor);
        }
    }

    /** Exercises registration through the scheduler's authoritative identity check. */
    private void submitTask(TaskIntent intent, Game game, GameTaskScheduler.ScheduledTaskExecutor callback) {
        ActiveGameRegistry games = new ActiveGameRegistry();
        RoomCoordinator coordinator = gameLifecycleService.getRoomCoordinator();
        GameTaskScheduler scheduler = new GameTaskScheduler(games, runtimeTimers, coordinator, roomService,
                gameLifecycleService::getClock, callback, (id, deadline) -> {});
        coordinator.coordinate(intent.gameId(), () -> {
            if (game != null) games.replace(game);
            scheduler.scheduleRuntimeTask(intent);
        });
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

    private static Player restoredPlayer(String name, String id, int chips, int bet, boolean folded, boolean allIn) {
        return Player.restoreBuilder().name(name).playerId(id).chips(chips).holeCards(List.of())
                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(bet).hasFolded(folded).allIn(allIn)
                .out(false).disconnected(false).readyForNextHand(false).build();
    }

    // =========================================================================
    // 1. Direct GameTimerRegistry Unit Tests
    // =========================================================================

    @Nested
    @DisplayName("Direct GameTimerRegistry Unit Tests")
    class DirectRuntimeTimersTests {

        @Test
        @DisplayName("captureAllIntents returns empty list when no tasks scheduled")
        void captureAllIntents_WhenNoTasks_ReturnsEmptyList() {
            Game game = startedGame();
            game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
            List<TaskIntent> intents = GameTaskScheduler.captureAllIntents(ROOM_ID, game);
            assertTrue(intents.isEmpty());
        }

        @Test
        @DisplayName("captureAllIntents captures all scheduled tasks with accurate tokens")
        void captureAllIntents_WithMultipleTasks_CapturesCorrectIntents() {
            Game game = startedGame();
            game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
            game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 10_000L);
            game.scheduleTask(ScheduledGameTask.CLEANUP, 60_000L);

            List<TaskIntent> intents = GameTaskScheduler.captureAllIntents(ROOM_ID, game);
            assertEquals(2, intents.size());

            TaskIntent autoAdvanceIntent = intents.stream()
                    .filter(i -> i.task() == ScheduledGameTask.AUTO_ADVANCE)
                    .findFirst()
                    .orElseThrow();
            assertEquals(ROOM_ID, autoAdvanceIntent.gameId());
            assertEquals(10_000L, autoAdvanceIntent.deadline());
            assertEquals(game.getHandNumber(), autoAdvanceIntent.handNumber());
            assertEquals(game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE), autoAdvanceIntent.generation());

            TaskIntent cleanupIntent = intents.stream()
                    .filter(i -> i.task() == ScheduledGameTask.CLEANUP)
                    .findFirst()
                    .orElseThrow();
            assertEquals(ROOM_ID, cleanupIntent.gameId());
            assertEquals(60_000L, cleanupIntent.deadline());
            assertEquals(game.getHandNumber(), cleanupIntent.handNumber());
            assertEquals(game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP), cleanupIntent.generation());
        }

        @Test
        @DisplayName("schedule ignores task when game is null")
        void schedule_WhenGameIsNull_DoesNothing() {
            TaskIntent intent = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, 5000L, 1L, 1L);
            submitTask(intent, null, (g, t, d, h, gen) -> {});
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));
            verifyNoInteractions(taskScheduler);
        }

        @Test
        @DisplayName("schedule ignores task when handNumber, generation, or deadline is mismatched under lock")
        void schedule_WhenTokensMismatched_DoesNotSchedule() {
            Game game = startedGame();
            clearInvocations(taskScheduler);
            game.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long gen = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);

            TaskIntent staleHand = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, 5000L, game.getHandNumber() + 1, gen);
            submitTask(staleHand, game, (g, t, d, h, gToken) -> {});
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));

            TaskIntent staleGen = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, 5000L, game.getHandNumber(), gen - 1);
            submitTask(staleGen, game, (g, t, d, h, gToken) -> {});
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));

            TaskIntent staleDeadline = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, 6000L, game.getHandNumber(), gen);
            submitTask(staleDeadline, game, (g, t, d, h, gToken) -> {});
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));

            verifyNoInteractions(taskScheduler);
        }

        @Test
        @DisplayName("schedule successfully registers task and executes callback")
        void schedule_Success_RegistersAndExecutes() {
            Game game = startedGame();
            long deadline = System.currentTimeMillis() + 10_000L;
            game.scheduleTask(ScheduledGameTask.NEW_HAND, deadline);
            long gen = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);

            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future = mock(ScheduledFuture.class);
            ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
            doReturn(future).when(taskScheduler).schedule(runnableCaptor.capture(), any(Instant.class));

            AtomicBoolean executed = new AtomicBoolean(false);
            TaskIntent intent = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, game.getHandNumber(), gen);
            submitTask(intent, game, (g, t, d, h, gToken) -> {
                assertEquals(ROOM_ID, g);
                assertEquals(ScheduledGameTask.NEW_HAND, t);
                assertEquals(deadline, d);
                assertEquals(game.getHandNumber(), h);
                assertEquals(gen, gToken);
                executed.set(true);
            });

            assertEquals(1, runtimeTimers.countForGame(ROOM_ID));
            assertNotNull(runnableCaptor.getValue());

            runnableCaptor.getValue().run();
            assertTrue(executed.get());
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));
        }

        @Test
        @DisplayName("schedule cancels previous registration for same task type")
        void schedule_ReplacesAndCancelsPreviousTask() {
            Game game = startedGame();
            long deadline1 = System.currentTimeMillis() + 5000L;
            game.scheduleTask(ScheduledGameTask.NEW_HAND, deadline1);
            long gen1 = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);

            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future1 = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future2 = mock(ScheduledFuture.class);
            doReturn(future1).doReturn(future2).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            TaskIntent intent1 = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline1, game.getHandNumber(), gen1);
            submitTask(intent1, game, (g, t, d, h, gen) -> {});
            assertEquals(1, runtimeTimers.countForGame(ROOM_ID));

            long deadline2 = System.currentTimeMillis() + 8000L;
            game.scheduleTask(ScheduledGameTask.NEW_HAND, deadline2);
            long gen2 = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
            TaskIntent intent2 = new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline2, game.getHandNumber(), gen2);
            submitTask(intent2, game, (g, t, d, h, gen) -> {});

            verify(future1).cancel(false);
            assertEquals(1, runtimeTimers.countForGame(ROOM_ID));
        }

        @Test
        @DisplayName("cancelTask cancels registration and removes from tracking")
        void cancelTask_CancelsAndRemoves() {
            Game game = startedGame();
            long deadline = System.currentTimeMillis() + 10_000L;
            game.scheduleTask(ScheduledGameTask.CLEANUP, deadline);
            long gen = game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP);

            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future = mock(ScheduledFuture.class);
            doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            TaskIntent intent = new TaskIntent(ROOM_ID, ScheduledGameTask.CLEANUP, deadline, game.getHandNumber(), gen);
            submitTask(intent, game, (g, t, d, h, gToken) -> {});
            assertEquals(1, runtimeTimers.countForGame(ROOM_ID));

            runtimeTimers.cancelTask(ROOM_ID, ScheduledGameTask.CLEANUP);
            verify(future).cancel(false);
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));
        }

        @Test
        void cancellingProgressionPreservesInactivityAndTerminalRegistrations() {
            Game game = startedGame();
            long now = System.currentTimeMillis();
            long deadline = now + 60_000;
            Map<ScheduledGameTask, ScheduledFuture<?>> futures = new EnumMap<>(ScheduledGameTask.class);
            ScheduledFuture<?> inactivity = mock(ScheduledFuture.class);
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                futures.put(task, future);
                doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
                game.scheduleTask(task, deadline);
                submitTask(GameTaskScheduler.captureIntent(ROOM_ID, game, task, deadline),
                        game, (g, t, d, h, generation) -> {});
            }
            doReturn(inactivity).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            runtimeTimers.scheduleInactivity(ROOM_ID, deadline, now, () -> {});

            runtimeTimers.cancelProgressionForGame(ROOM_ID);

            for (ScheduledGameTask task : List.of(ScheduledGameTask.TURN_TIMEOUT, ScheduledGameTask.AUTO_ADVANCE,
                    ScheduledGameTask.READY_OPEN, ScheduledGameTask.READY_TIMEOUT, ScheduledGameTask.NEW_HAND)) {
                verify(futures.get(task)).cancel(false);
            }
            verifyNoInteractions(inactivity, futures.get(ScheduledGameTask.CLEANUP), futures.get(ScheduledGameTask.GAME_END));
            assertEquals(2, runtimeTimers.countForGame(ROOM_ID));

            runtimeTimers.cancelAllForGame(ROOM_ID, true);

            verify(inactivity).cancel(false);
            verify(futures.get(ScheduledGameTask.GAME_END)).cancel(false);
            verifyNoInteractions(futures.get(ScheduledGameTask.CLEANUP));
            runtimeTimers.cancelAllForGame(ROOM_ID, false);
            verify(futures.get(ScheduledGameTask.CLEANUP)).cancel(false);
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));
        }

        @Test
        @DisplayName("cancelAllForGame cancels all tasks or retains cleanup based on keepCleanup flag")
        void cancelAllForGame_CancelsCorrectTasks() {
            Game game = startedGame();
            long deadline1 = System.currentTimeMillis() + 5000L;
            long deadline2 = System.currentTimeMillis() + 60000L;
            game.scheduleTask(ScheduledGameTask.NEW_HAND, deadline1);
            game.scheduleTask(ScheduledGameTask.CLEANUP, deadline2);

            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future1 = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<Object> future2 = mock(ScheduledFuture.class);
            doReturn(future1).doReturn(future2).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            submitTask(new TaskIntent(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline1, game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND)), game, (g, t, d, h, gen) -> {});
            submitTask(new TaskIntent(ROOM_ID, ScheduledGameTask.CLEANUP, deadline2, game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP)), game, (g, t, d, h, gen) -> {});
            assertEquals(2, runtimeTimers.countForGame(ROOM_ID));

            runtimeTimers.cancelAllForGame(ROOM_ID, true);
            verify(future1).cancel(false);
            verify(future2, never()).cancel(false);
            assertEquals(1, runtimeTimers.countForGame(ROOM_ID));

            runtimeTimers.cancelAllForGame(ROOM_ID, false);
            verify(future2).cancel(false);
            assertEquals(0, runtimeTimers.countForGame(ROOM_ID));
        }
    }

    // =========================================================================
    // 2. Migrated Runtime Timers & Dispatch Guard Tests
    // =========================================================================

    @Test
    void departedAllInDuringAutoAdvanceMakesCapturedCallbackStale() {
        Room room = new Room("stale-auto", "Stale auto", "A", 4, 5, 10, 100, null);
        for (String name : List.of("A", "B", "C", "D")) room.addPlayer(name);
        when(roomService.getRoom(room.getRoomId())).thenReturn(room);
        GameLifecycleService lifecycle = new GameLifecycleService(roomService, handEvaluator, gameStateService,
                messagingTemplate, taskScheduler);
        ReflectionTestUtils.setField(lifecycle, "roundEndDisplayDelayMs", 2_500L);
        List<Card> board = List.of(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES),
                new Card(Rank.QUEEN, Suit.SPADES));
        List<Card> deck = List.of(new Card(Rank.JACK, Suit.SPADES), new Card(Rank.TEN, Suit.SPADES));
        Player a = restoredPlayer("A", "a", 0, 50, false, true);
        Player b = restoredPlayer("B", "b", 100, 0, true, false);
        Player c = restoredPlayer("C", "c", 0, 50, false, true);
        Player d = restoredPlayer("D", "d", 100, 0, true, false);
        Game game = Game.restoreBuilder().gameId(room.getRoomId()).players(List.of(a, b, c, d))
                .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(deck).communityCards(board)
                .pot(100).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(0)
                .currentHighestBet(50).currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                .handContributions(Map.of("a", 50, "c", 50)).preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                .dealerAnchorPlayerId("a").smallBlindPlayerId("b").bigBlindPlayerId("c")
                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                .scheduledTaskDeadlines(Map.of()).handEvaluator(handEvaluator).build();
        lifecycle.restoreGame(game);

        lifecycle.scheduleAutoAdvance(room.getRoomId());
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(callbacks.capture(), any(Instant.class));
        Runnable staleAutoAdvance = callbacks.getValue();
        int winnerChipsBefore = a.getChips();

        long before = System.currentTimeMillis();
        lifecycle.departParticipants(room.getRoomId(), List.of("C"));
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(3, game.getCommunityCards().size());
        assertEquals(100, a.getChips() - winnerChipsBefore);
        assertFalse(game.isReadyCountdownActive());
        Long readyOpen = game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN);
        assertNotNull(readyOpen);
        assertTrue(readyOpen >= before + 2_500);
        HandSettlementResult settled = game.getLastSettlementResult();

        staleAutoAdvance.run();

        assertEquals(3, game.getCommunityCards().size());
        assertEquals(0, game.getPot());
        assertSame(settled, game.getLastSettlementResult());
        assertEquals(100, a.getChips() - winnerChipsBefore);
    }

    @Test
    void executeScheduledTask_WhenGameEnd_ShouldBroadcastGameEnd() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() + 5000;
        game.scheduleTask(ScheduledGameTask.GAME_END, deadline);
        reset(gameStateService);
        stubGameEndCapture();
        gameLifecycleService.executeScheduledTask(
                ROOM_ID,
                ScheduledGameTask.GAME_END,
                deadline,
                game.getHandNumber(),
                game.getScheduledTaskGeneration(ScheduledGameTask.GAME_END));

        verify(gameStateService).sendGameEnd(any(GameEndResponse.class));
    }

    @Test
    void executeScheduledCleanup_ReleasesRoomMonitorBeforeImmediateCleanupCallback() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        Game game = gameLifecycleService.getGame(ROOM_ID);
        long deadline = System.currentTimeMillis() + 5000;
        game.scheduleTask(ScheduledGameTask.CLEANUP, deadline);
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(game), "Immediate post-commit cleanup must run outside synchronized(game)");
            return null;
        }).when(roomService).destroyRoom(ROOM_ID);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.CLEANUP, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        verify(roomService).destroyRoom(ROOM_ID);
        assertFalse(gameLifecycleService.gameExists(ROOM_ID));
    }

    // =========================================================================
    // 3. executeScheduledTask guard Tests
    // =========================================================================

    private Game startedGame() {
        when(roomService.getRoom(ROOM_ID)).thenReturn(testRoom);
        gameLifecycleService.createGameFromRoom(ROOM_ID);
        return gameLifecycleService.getGame(ROOM_ID);
    }

    private Game showdownGame() {
        Game game = startedGame();
        game.conductShowdown();
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        return game;
    }

    private Game riverGame() {
        Game game = startedGame();
        game.dealFlop();
        game.dealTurn();
        game.dealRiver();
        assertEquals(GamePhase.RIVER, game.getCurrentPhase());
        return game;
    }

    private long scheduleForTest(Game game, ScheduledGameTask task) {
        long deadline = System.currentTimeMillis() + 60_000;
        game.scheduleTask(task, deadline);
        return deadline;
    }

    private void runScheduled(Game game, ScheduledGameTask task, long deadline) {
        gameLifecycleService.executeScheduledTask(ROOM_ID, task, deadline,
                game.getHandNumber(), game.getScheduledTaskGeneration(task));
    }

    private void assertNothingRan(Game game, ScheduledGameTask task, long deadline, long generation, long handNumber) {
        assertEquals(deadline, game.getScheduledTaskDeadline(task), "The guarded task must stay scheduled");
        assertEquals(generation, game.getScheduledTaskGeneration(task), "The guarded task must not be cleared");
        assertEquals(handNumber, game.getHandNumber(), "No new hand may be dealt");
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask does nothing while a forfeit is pending")
    void executeScheduledTask_WhenForfeitPending_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        game.setForfeitPending(true);
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
        assertTrue(game.isForfeitPending());
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer captured for an earlier hand")
    void executeScheduledTask_WhenHandNumberIsStale_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber - 1, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer captured before the task was rescheduled")
    void executeScheduledTask_WhenGenerationIsStale_DoesNothing() {
        Game game = showdownGame();
        scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long staleGeneration = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        assertNotEquals(staleGeneration, generation);
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, handNumber, staleGeneration);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask on a finished game clears and cancels a non-cleanup task")
    void executeScheduledTask_WhenGameOverAndTaskIsNotCleanup_ClearsAndCancelsTask() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        Game game = startedGame();
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
        gameLifecycleService.cancelTurnTimeout(ROOM_ID);
        clearInvocations(future);
        gameLifecycleService.scheduleAutoAdvance(ROOM_ID);
        long deadline = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE);
        long handNumber = game.getHandNumber();
        assertEquals(1, gameLifecycleService.getRuntimeTaskCount(ROOM_ID));
        game.markGameOver();
        clearInvocations(gameStateService, messagingTemplate);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, deadline, handNumber, generation);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(0, gameLifecycleService.getRuntimeTaskCount(ROOM_ID));
        verify(future).cancel(false);
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase(), "A finished game cannot auto-advance");
        verifyNoInteractions(gameStateService, messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer whose deadline no longer matches")
    void executeScheduledTask_WhenDeadlineIsStale_DoesNothing() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long generation = game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline + 1, handNumber, generation);

        assertNothingRan(game, ScheduledGameTask.NEW_HAND, deadline, generation, handNumber);
    }

    @Test
    @DisplayName("executeScheduledTask ignores a timer for a task that is no longer scheduled")
    void executeScheduledTask_WhenTaskIsNotScheduled_DoesNothing() {
        Game game = showdownGame();
        long handNumber = game.getHandNumber();
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        gameLifecycleService.executeScheduledTask(ROOM_ID, ScheduledGameTask.NEW_HAND,
                System.currentTimeMillis() + 60_000, handNumber, 0);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        assertEquals(0, game.getScheduledTaskGeneration(ScheduledGameTask.NEW_HAND));
        assertEquals(handNumber, game.getHandNumber());
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask NEW_HAND clears the task and deals the next hand")
    void executeScheduledTask_NewHand_ClearsTaskAndStartsNextHand() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.NEW_HAND);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService);

        runScheduled(game, ScheduledGameTask.NEW_HAND, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.NEW_HAND));
        assertEquals(handNumber + 1, game.getHandNumber());
        assertEquals(GamePhase.PRE_FLOP, game.getCurrentPhase());
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), eq(game));
    }

    @Test
    @DisplayName("executeScheduledTask READY_OPEN clears the task and opens the ready countdown")
    void executeScheduledTask_ReadyOpen_ClearsTaskAndOpensReadyCountdown() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.READY_OPEN);
        long handNumber = game.getHandNumber();
        assertFalse(game.isReadyCountdownActive());
        clearInvocations(gameStateService);

        runScheduled(game, ScheduledGameTask.READY_OPEN, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertTrue(game.isReadyCountdownActive());
        assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertEquals(handNumber, game.getHandNumber(), "Opening the gate must not deal a hand");
        verify(gameStateService).broadcastGameState(eq(ROOM_ID), eq(game));
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE in SHOWDOWN clears the task and stops")
    void executeScheduledTask_AutoAdvanceInShowdown_ClearsTaskAndStops() {
        Game game = showdownGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long handNumber = game.getHandNumber();
        clearInvocations(gameStateService, messagingTemplate, taskScheduler);

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertEquals(handNumber, game.getHandNumber());
        assertFalse(game.isReadyCountdownActive(), "A late auto-advance timer cannot open the ready gate");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verifyNoInteractions(gameStateService, taskScheduler);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a tournament schedules game end")
    void executeScheduledTask_AutoAdvanceComplete_WhenTournamentOver_SchedulesGameEnd() {
        Game game = riverGame();
        Player winner = game.getPlayers().get(0);
        Player loser = game.getPlayers().get(1);
        loser.setChips(0);
        lenient().when(handEvaluator.getBestHand(anyList(), anyList())).thenAnswer(invocation -> {
            List<Card> holeCards = invocation.getArgument(1);
            HandRank rank = holeCards.equals(winner.getHoleCards()) ? HandRank.ROYAL_FLUSH : HandRank.HIGH_CARD;
            return new HandEvaluationResult(List.of(), rank);
        });
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        clearInvocations(taskScheduler);
        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);
        long after = System.currentTimeMillis();

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertTrue(game.isTournamentOver());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        Long gameEndDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END);
        assertNotNull(gameEndDeadline);
        assertTrue(gameEndDeadline >= before + GameLifecycleService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
        assertTrue(gameEndDeadline <= after + GameLifecycleService.FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
        assertFalse(game.isReadyCountdownActive(), "A finished tournament must not open the ready gate");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        verify(gameStateService).broadcastAutoAdvanceComplete(ROOM_ID, game);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a hand opens the ready gate when there is no display delay")
    void executeScheduledTask_AutoAdvanceComplete_WithNoDisplayDelay_OpensReadyCountdown() {
        Game game = riverGame();
        ReflectionTestUtils.setField(gameLifecycleService, "roundEndDisplayDelayMs", 0L);
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);

        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertFalse(game.isTournamentOver());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertTrue(game.isReadyCountdownActive());
        assertNotNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(gameStateService).broadcastAutoAdvanceComplete(ROOM_ID, game);
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE finishing a hand persists READY_OPEN when a display delay is configured")
    void executeScheduledTask_AutoAdvanceComplete_WithDisplayDelay_PersistsReadyOpen() {
        Game game = riverGame();
        long configuredRoundEndDelayMs = 5_000L;
        ReflectionTestUtils.setField(gameLifecycleService, "roundEndDisplayDelayMs", configuredRoundEndDelayMs);
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        clearInvocations(taskScheduler);
        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);
        long after = System.currentTimeMillis();

        assertEquals(GamePhase.SHOWDOWN, game.getCurrentPhase());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        Long readyOpenDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN);
        assertNotNull(readyOpenDeadline);
        assertTrue(readyOpenDeadline >= before + configuredRoundEndDelayMs);
        assertTrue(readyOpenDeadline <= after + configuredRoundEndDelayMs);
        assertFalse(game.isReadyCountdownActive(), "The ready gate waits for the display delay");
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    @DisplayName("executeScheduledTask AUTO_ADVANCE with more streets to deal reschedules itself")
    void executeScheduledTask_AutoAdvanceNotComplete_ReschedulesAutoAdvance() {
        Game game = startedGame();
        long deadline = scheduleForTest(game, ScheduledGameTask.AUTO_ADVANCE);
        long before = System.currentTimeMillis();

        clearInvocations(taskScheduler);
        runScheduled(game, ScheduledGameTask.AUTO_ADVANCE, deadline);
        long after = System.currentTimeMillis();

        assertEquals(GamePhase.FLOP, game.getCurrentPhase());
        Long nextDeadline = game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE);
        assertNotNull(nextDeadline);
        long autoAdvanceStepDelayMs = 4_000L;
        assertTrue(nextDeadline > before);
        assertTrue(nextDeadline <= after + autoAdvanceStepDelayMs);
        assertNotEquals(deadline, nextDeadline);
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_OPEN));
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.GAME_END));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
    }
}
