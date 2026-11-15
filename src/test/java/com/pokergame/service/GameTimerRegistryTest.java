package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
    private GameTimerRegistry runtimeTimers;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        runtimeTimers = new GameTimerRegistry(taskScheduler, fixedClock);
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
        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, deadline, () -> executed.set(true));

        assertEquals(Instant.ofEpochMilli(deadline), instantCaptor.getValue());
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        runnableCaptor.getValue().run();
        assertTrue(executed.get());
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
    }

    @Test
    @DisplayName("schedule with past deadline clamps scheduled execution instant to clock now")
    void schedule_PastDeadline_ClampsDelayToZero() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), eq(FIXED_NOW));

        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.TURN_TIMEOUT, FIXED_NOW.toEpochMilli() - 5_000L, () -> {});

        verify(taskScheduler).schedule(any(Runnable.class), eq(FIXED_NOW));
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));
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
        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 5_000L,
                () -> firstRan.set(true));
        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 8_000L,
                () -> secondRan.set(true));

        verify(future1).cancel(false);
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        // Stale first callback captured before replacement must be a no-op
        callbacks.getAllValues().getFirst().run();
        assertFalse(firstRan.get());
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        callbacks.getAllValues().getLast().run();
        assertTrue(secondRan.get());
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
    }

    @Test
    @DisplayName("exception thrown by scheduled callback still removes timer registration")
    void schedule_CallbackThrows_CleansRegistration() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(taskScheduler).schedule(callbackCaptor.capture(), any(Instant.class));

        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.AUTO_ADVANCE, FIXED_NOW.toEpochMilli() + 1000L, () -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        assertDoesNotThrow(() -> callbackCaptor.getValue().run());
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
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

        runtimeTimers.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(),
                () -> runtimeTimers.scheduleInactivity(
                        ROOM_ID, FIXED_NOW.toEpochMilli() + 200L, FIXED_NOW.toEpochMilli(), replacementCallback));

        verify(first).cancel(false);
        verifyNoInteractions(replacement, replacementCallback);
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, true);
        verify(replacement).cancel(false);
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
    }

    @Test
    @DisplayName("cancelling inactivity before future attachment cancels returned future")
    void cancellingInactivityBeforeFutureAttachmentCancelsReturnedFuture() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            runtimeTimers.cancelInactivity(ROOM_ID);
            invocation.<Runnable>getArgument(0).run();
            return future;
        });

        runtimeTimers.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 200L, FIXED_NOW.toEpochMilli(),
                () -> fail("Unexpected timeout"));

        verify(future).cancel(false);
        runtimeTimers.cancelInactivity(ROOM_ID);
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

        runtimeTimers.scheduleInactivity(ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(), expire);
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
        callbacks.getValue().run();
        verifyNoInteractions(expire);

        assertSame(schedulerFailure, assertThrows(RuntimeException.class, () ->
                runtimeTimers.scheduleInactivity(
                        ROOM_ID, FIXED_NOW.toEpochMilli() + 100L, FIXED_NOW.toEpochMilli(), expire)));
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
        callbacks.getValue().run();
        verifyNoInteractions(expire);
    }

    @Test
    @DisplayName("null gameId or null deadline does not schedule inactivity")
    void missingGameIdOrDeadlineDoesNotScheduleInactivity() {
        runtimeTimers.scheduleInactivity(null, 100L, 100L, () -> fail("Unexpected timeout"));
        runtimeTimers.scheduleInactivity(ROOM_ID, null, 100L, () -> fail("Unexpected timeout"));
        runtimeTimers.cancelInactivity(null);

        verifyNoInteractions(taskScheduler);
        assertEquals(0, runtimeTimers.activeTaskCount(null));
    }

    @Test
    @DisplayName("cancelTask cancels single registration and removes it from tracking")
    void cancelTask_CancelsAndRemoves() {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, FIXED_NOW.toEpochMilli() + 10_000L, () -> {});
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelTask(ROOM_ID, ScheduledGameTask.CLEANUP);
        verify(future).cancel(false);
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
    }

    @Test
    @DisplayName("cancelProgressionForGame cancels hand-progression timers while preserving inactivity and terminal ones")
    void cancellingProgressionPreservesInactivityAndTerminalRegistrations() {
        long deadline = FIXED_NOW.toEpochMilli() + 60_000L;
        Map<ScheduledGameTask, ScheduledFuture<?>> futures = new EnumMap<>(ScheduledGameTask.class);
        ScheduledFuture<?> inactivity = mock(ScheduledFuture.class);
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            futures.put(task, future);
            doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
            runtimeTimers.schedule(ROOM_ID, task, deadline, () -> {});
        }
        doReturn(inactivity).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));
        runtimeTimers.scheduleInactivity(ROOM_ID, deadline, FIXED_NOW.toEpochMilli(), () -> {});

        runtimeTimers.cancelProgressionForGame(ROOM_ID);

        for (ScheduledGameTask task : List.of(
                ScheduledGameTask.TURN_TIMEOUT,
                ScheduledGameTask.AUTO_ADVANCE,
                ScheduledGameTask.READY_OPEN,
                ScheduledGameTask.READY_TIMEOUT,
                ScheduledGameTask.NEW_HAND)) {
            verify(futures.get(task)).cancel(false);
        }
        verifyNoInteractions(inactivity, futures.get(ScheduledGameTask.CLEANUP), futures.get(ScheduledGameTask.GAME_END));
        assertEquals(3, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, true);
        verify(inactivity).cancel(false);
        verify(futures.get(ScheduledGameTask.GAME_END)).cancel(false);
        verifyNoInteractions(futures.get(ScheduledGameTask.CLEANUP));
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, false);
        verify(futures.get(ScheduledGameTask.CLEANUP)).cancel(false);
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
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

        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.NEW_HAND, FIXED_NOW.toEpochMilli() + 5_000L, () -> {});
        runtimeTimers.schedule(ROOM_ID, ScheduledGameTask.CLEANUP, FIXED_NOW.toEpochMilli() + 60_000L, () -> {});
        assertEquals(2, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, true);
        verify(newHandFuture).cancel(false);
        verify(cleanupFuture, never()).cancel(false);
        assertEquals(1, runtimeTimers.activeTaskCount(ROOM_ID));

        runtimeTimers.cancelAllForGame(ROOM_ID, false);
        verify(cleanupFuture).cancel(false);
        assertEquals(0, runtimeTimers.activeTaskCount(ROOM_ID));
    }
}
