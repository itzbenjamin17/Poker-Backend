package com.pokergame.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.pokergame.enums.ScheduledGameTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

/**
 * Unit tests for {@link GameTimerRegistry} runtime timer registration, replacement, failure
 * cleanup, and targeted cancellation behavior.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("GameTimerRegistry unit tests")
class GameTimerRegistryTest {

    private static final String ROOM_ID = "test-room-id";
    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskScheduler taskScheduler;

    private Clock fixedClock;
    private GameTimerRegistry timerRegistry;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        timerRegistry = new GameTimerRegistry(taskScheduler, fixedClock);
    }

    @Test
    @DisplayName("schedule registers task with clamped delay, runs callback, and clears registration")
    void schedule_Success_RegistersAndExecutes() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        doReturn(future).when(taskScheduler).schedule(runnableCaptor.capture(), instantCaptor.capture());

        long deadline = FIXED_NOW.toEpochMilli() + 10_000L;
        AtomicBoolean executed = new AtomicBoolean(false);
        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, () -> executed.set(true));

        assertEquals(Instant.ofEpochMilli(deadline), instantCaptor.getValue());

        runnableCaptor.getValue().run();
        assertTrue(executed.get());
        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.NEW_HAND);
        verify(future, never()).cancel(false);
    }

    @Test
    @DisplayName("schedule with past deadline clamps scheduled execution instant to clock now")
    void schedule_PastDeadline_ClampsDelayToZero() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), eq(FIXED_NOW));

        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT, FIXED_NOW.toEpochMilli() - 5_000L, () -> {});

        verify(taskScheduler).schedule(any(Runnable.class), eq(FIXED_NOW));
        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT);
        verify(future).cancel(false);
    }

    @Test
    @DisplayName("schedule replaces previous task for same slot, cancels its future, and ignores stale callback")
    void schedule_ReplacesAndCancelsPreviousTask() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future1 = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future2 = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future1).doReturn(future2).when(taskScheduler).schedule(callbacks.capture(), any(Instant.class));

        AtomicBoolean firstRan = new AtomicBoolean(false);
        AtomicBoolean secondRan = new AtomicBoolean(false);
        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 5_000L,
                () -> firstRan.set(true));
        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 8_000L,
                () -> secondRan.set(true));

        verify(future1).cancel(false);
        verify(future2, never()).cancel(false);

        // Stale first callback captured before replacement must be a no-op
        callbacks.getAllValues().getFirst().run();
        assertFalse(firstRan.get());
        verify(future2, never()).cancel(false);

        callbacks.getAllValues().getLast().run();
        assertTrue(secondRan.get());
        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.NEW_HAND);
        verify(future2, never()).cancel(false);
    }

    @Test
    @DisplayName("exception thrown by scheduled callback still removes timer registration")
    void schedule_CallbackThrows_CleansRegistration() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(callbackCaptor.capture(), any(Instant.class));

        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, FIXED_NOW.toEpochMilli() + 1000L, () -> {
            throw new IllegalStateException("boom");
        });

        assertDoesNotThrow(() -> callbackCaptor.getValue().run());
        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.AUTO_ADVANCE);
        verify(future, never()).cancel(false);
    }

    @Test
    @DisplayName("immediate inactivity callback completion replacing itself does not evict replacement")
    void immediateInactivityCompletionDoesNotRemoveReplacement() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> replacement = mock(ScheduledFuture.class);
        Runnable replacementCallback = mock(Runnable.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return first;
                })
                .thenAnswer(_ -> replacement);

        timerRegistry.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(),
                () -> timerRegistry.scheduleInactivity(
                        ROOM_ID, FIXED_NOW.toEpochMilli() + 200L, FIXED_NOW.toEpochMilli(), replacementCallback));

        verify(first).cancel(false);
        verifyNoInteractions(replacement, replacementCallback);

        timerRegistry.cancelAllForGame(ROOM_ID, true);
        verify(replacement).cancel(false);
        timerRegistry.cancelAllForGame(ROOM_ID, true);
        verifyNoMoreInteractions(replacement);
    }

    @Test
    @DisplayName("cancelling inactivity before future attachment cancels returned future")
    void cancellingInactivityBeforeFutureAttachmentCancelsReturnedFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            timerRegistry.cancelInactivity(ROOM_ID);
            invocation.<Runnable>getArgument(0).run();
            return future;
        });

        timerRegistry.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 200L, FIXED_NOW.toEpochMilli(),
                () -> fail("Unexpected timeout"));

        verify(future).cancel(false);
        timerRegistry.cancelInactivity(ROOM_ID);
        verifyNoMoreInteractions(future);
    }

    @Test
    @DisplayName("failed or null scheduler return leaves no active registration")
    void failedInactivitySchedulingLeavesNoRunnableRegistration() {
        ArgumentCaptor<Runnable> callbacks = ArgumentCaptor.forClass(Runnable.class);
        RuntimeException schedulerFailure = new IllegalStateException("Scheduler unavailable");
        when(taskScheduler.schedule(callbacks.capture(), any(Instant.class)))
                .thenReturn(null)
                .thenThrow(schedulerFailure);
        Runnable expire = mock(Runnable.class);

        timerRegistry.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(), expire);
        callbacks.getValue().run();
        verifyNoInteractions(expire);

        assertSame(schedulerFailure, assertThrows(RuntimeException.class, () ->
                timerRegistry.scheduleInactivity(
                        ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(), expire)));
        callbacks.getValue().run();
        verifyNoInteractions(expire);
    }

    @Test
    @DisplayName("null gameId or null deadline does not schedule inactivity")
    void missingGameIdOrDeadlineDoesNotScheduleInactivity() {
        timerRegistry.scheduleInactivity(null, 100L, 100L, () -> fail("Unexpected timeout"));
        timerRegistry.scheduleInactivity(ROOM_ID, null, 100L, () -> fail("Unexpected timeout"));
        timerRegistry.cancelInactivity(null);

        verifyNoInteractions(taskScheduler);
    }

    @Test
    @DisplayName("cancelTask cancels single registration and removes it from tracking")
    void cancelTask_CancelsAndRemoves() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, FIXED_NOW.toEpochMilli() + 10_000L, () -> {});

        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.CLEANUP);
        verify(future).cancel(false);
        timerRegistry.cancelTask(ROOM_ID, ScheduledGameTask.CLEANUP);
        verifyNoMoreInteractions(future);
    }

    @Test
    @DisplayName("cancelProgressionForGame cancels hand-progression timers while preserving inactivity and game-end ones")
    void cancellingProgressionPreservesInactivityAndGameEndRegistrations() {
        long deadline = FIXED_NOW.toEpochMilli() + 60_000L;
        Map<ScheduledGameTask, ScheduledFuture<?>> futures = new EnumMap<>(ScheduledGameTask.class);
        ScheduledFuture<?> inactivity = mock(ScheduledFuture.class);
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.put(task, future);
            doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            timerRegistry.schedule(ROOM_ID, task, deadline, () -> {});
        }
        doReturn(inactivity).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        timerRegistry.scheduleInactivity(ROOM_ID, deadline, FIXED_NOW.toEpochMilli(), () -> {});

        timerRegistry.cancelProgressionForGame(ROOM_ID);

        for (ScheduledGameTask task : List.of(
                ScheduledGameTask.TURN_TIMEOUT,
                ScheduledGameTask.AUTO_ADVANCE,
                ScheduledGameTask.READY_OPEN,
                ScheduledGameTask.READY_TIMEOUT,
                ScheduledGameTask.NEW_HAND)) {
            verify(futures.get(task)).cancel(false);
        }
        verifyNoInteractions(inactivity, futures.get(ScheduledGameTask.CLEANUP), futures.get(ScheduledGameTask.GAME_END));

        timerRegistry.cancelAllForGame(ROOM_ID, true);
        verify(inactivity).cancel(false);
        verify(futures.get(ScheduledGameTask.GAME_END)).cancel(false);
        verifyNoInteractions(futures.get(ScheduledGameTask.CLEANUP));

        timerRegistry.cancelAllForGame(ROOM_ID, false);
        verify(futures.get(ScheduledGameTask.CLEANUP)).cancel(false);
        timerRegistry.cancelAllForGame(ROOM_ID, false);
        verifyNoMoreInteractions(inactivity, futures.get(ScheduledGameTask.GAME_END), futures.get(ScheduledGameTask.CLEANUP));
    }

    @Test
    @DisplayName("cancelAllForGame retains or cancels cleanup according to keepCleanup flag")
    void cancelAllForGame_CancelsCorrectTasks() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> newHandFuture = mock(ScheduledFuture.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> cleanupFuture = mock(ScheduledFuture.class);
        doReturn(newHandFuture).doReturn(cleanupFuture)
                .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 5_000L, () -> {});
        timerRegistry.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, FIXED_NOW.toEpochMilli() + 60_000L, () -> {});

        timerRegistry.cancelAllForGame(ROOM_ID, true);
        verify(newHandFuture).cancel(false);
        verify(cleanupFuture, never()).cancel(false);

        timerRegistry.cancelAllForGame(ROOM_ID, false);
        verify(cleanupFuture).cancel(false);
        timerRegistry.cancelAllForGame(ROOM_ID, false);
        verifyNoMoreInteractions(newHandFuture, cleanupFuture);
    }
}
