package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.ScheduledGameTask;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link GameScheduling}. */
@Tag("unit")
class GameSchedulingTest {

    private GameScheduling scheduling;

    @BeforeEach
    void setUp() {
        scheduling = new GameScheduling();
    }

    @Nested
    class TaskSchedulingTests {

        @Test
        void scheduleTask_NewTask_SetsDeadlineAndInitializesTimerVersion() {
            long deadline = 1700000000000L;
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, deadline);

            assertEquals(deadline, scheduling.getScheduledTime(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        void scheduleTask_ExistingTaskRescheduled_UpdatesDeadlineAndIncrementsTimerVersion() {
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 1000L);
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.READY_TIMEOUT));

            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 2000L);
            assertEquals(2000L, scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(2L, scheduling.getTimerVersion(ScheduledGameTask.READY_TIMEOUT));

            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 3000L);
            assertEquals(3000L, scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(3L, scheduling.getTimerVersion(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void scheduleTask_MultipleTasks_TrackIndependently() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 100L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 200L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 300L);
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 400L);

            assertEquals(400L, scheduling.getScheduledTime(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(200L, scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
            assertEquals(300L, scheduling.getScheduledTime(ScheduledGameTask.CLEANUP));

            assertEquals(2L, scheduling.getTimerVersion(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.CLEANUP));
        }
    }

    @Nested
    class TaskClearingTests {

        @Test
        void clearTask_ScheduledTask_RemovesDeadlineAndIncrementsTimerVersion() {
            scheduling.scheduleTask(ScheduledGameTask.READY_OPEN, 5000L);
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.READY_OPEN));

            scheduling.clearTask(ScheduledGameTask.READY_OPEN);

            assertNull(scheduling.getScheduledTime(ScheduledGameTask.READY_OPEN));
            assertEquals(2L, scheduling.getTimerVersion(ScheduledGameTask.READY_OPEN));
        }

        @Test
        void clearTask_UnscheduledTask_IncrementsTimerVersion() {
            scheduling.clearTask(ScheduledGameTask.NEW_HAND);

            assertNull(scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void clearTask_DoesNotAffectOtherTasks() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1000L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 2000L);

            scheduling.clearTask(ScheduledGameTask.AUTO_ADVANCE);

            assertNull(scheduling.getScheduledTime(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2000L, scheduling.getScheduledTime(ScheduledGameTask.CLEANUP));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.CLEANUP));
        }
    }

    @Nested
    class TaskInvalidationTests {

        @Test
        void invalidateProgressionTasks_PreservesCleanup_ClearsAllOthers() {
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                scheduling.scheduleTask(task, 100L + task.ordinal());
            }

            scheduling.invalidateProgressionTasks();

            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                if (task == ScheduledGameTask.CLEANUP) {
                    assertEquals(100L + task.ordinal(), scheduling.getScheduledTime(task));
                    assertEquals(1L, scheduling.getTimerVersion(task));
                } else {
                    assertNull(scheduling.getScheduledTime(task));
                    assertEquals(2L, scheduling.getTimerVersion(task));
                }
            }
        }

        @Test
        void invalidateProgressionTasks_OnlyCleanupScheduled_LeavesIntact() {
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 9999L);

            scheduling.invalidateProgressionTasks();

            assertEquals(9999L, scheduling.getScheduledTime(ScheduledGameTask.CLEANUP));
            assertEquals(1L, scheduling.getTimerVersion(ScheduledGameTask.CLEANUP));
        }

        @Test
        void invalidateProgressionTasks_NoCleanupScheduled_ClearsAll() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 100L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 200L);

            scheduling.invalidateProgressionTasks();

            assertTrue(scheduling.scheduledTimesSnapshot().isEmpty());
            assertEquals(2L, scheduling.getTimerVersion(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void invalidateProgressionTasks_EmptyMaps_DoesNotThrow() {
            assertDoesNotThrow(() -> scheduling.invalidateProgressionTasks());
            assertTrue(scheduling.scheduledTimesSnapshot().isEmpty());
        }

        @Test
        void clearAllTasks_ClearsEveryTaskIncludingCleanupAndBumpsTimerVersions() {
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                scheduling.scheduleTask(task, 100L + task.ordinal());
            }

            scheduling.clearAllTasks();

            assertTrue(scheduling.scheduledTimesSnapshot().isEmpty());
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                assertNull(scheduling.getScheduledTime(task));
                assertEquals(2L, scheduling.getTimerVersion(task));
            }
        }
    }

    @Nested
    class MonotonicTimerVersionTokenTests {

        @Test
        void timerVersionToken_StrictlyIncreasesAcrossLifecycle() {
            ScheduledGameTask task = ScheduledGameTask.GAME_END;
            assertEquals(0L, scheduling.getTimerVersion(task));

            scheduling.scheduleTask(task, 1000L);
            long v1 = scheduling.getTimerVersion(task);

            scheduling.scheduleTask(task, 2000L);
            long v2 = scheduling.getTimerVersion(task);

            scheduling.clearTask(task);
            long v3 = scheduling.getTimerVersion(task);

            scheduling.scheduleTask(task, 3000L);
            long v4 = scheduling.getTimerVersion(task);

            scheduling.invalidateProgressionTasks();
            long v5 = scheduling.getTimerVersion(task);

            assertTrue(v1 < v2 && v2 < v3 && v3 < v4 && v4 < v5);
            assertEquals(5L, v5);
        }

        @ParameterizedTest
        @EnumSource(ScheduledGameTask.class)
        void getTimerVersion_UnscheduledTask_ReturnsZero(ScheduledGameTask task) {
            assertEquals(0L, scheduling.getTimerVersion(task));
        }
    }

    @Nested
    class SnapshotIsolationTests {

        @Test
        void scheduledTimesAndTimerVersionsSnapshots_AreImmutableAndIsolatedFromMutations() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 5000L);
            Map<ScheduledGameTask, Long> timesSnapshot = scheduling.scheduledTimesSnapshot();
            Map<ScheduledGameTask, Long> versionsSnapshot = scheduling.timerVersionsSnapshot();

            assertEquals(Map.of(ScheduledGameTask.AUTO_ADVANCE, 5000L), timesSnapshot);
            assertEquals(Map.of(ScheduledGameTask.AUTO_ADVANCE, 1L), versionsSnapshot);
            assertThrows(UnsupportedOperationException.class,
                    () -> timesSnapshot.put(ScheduledGameTask.CLEANUP, 1000L));
            assertThrows(UnsupportedOperationException.class,
                    () -> versionsSnapshot.put(ScheduledGameTask.CLEANUP, 10L));

            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 8000L);
            scheduling.clearTask(ScheduledGameTask.AUTO_ADVANCE);

            assertEquals(Map.of(ScheduledGameTask.AUTO_ADVANCE, 5000L), timesSnapshot);
            assertEquals(Map.of(ScheduledGameTask.AUTO_ADVANCE, 1L), versionsSnapshot);
        }

        @Test
        void restoreConstructor_DefensivelyCopiesMapsAndContinuesTimerVersions() {
            Map<ScheduledGameTask, Long> mutableTimes = new EnumMap<>(ScheduledGameTask.class);
            mutableTimes.put(ScheduledGameTask.TURN_TIMEOUT, 12_000L);

            Map<ScheduledGameTask, Long> mutableVersions = new EnumMap<>(ScheduledGameTask.class);
            mutableVersions.put(ScheduledGameTask.TURN_TIMEOUT, 4L);

            GameScheduling restored = new GameScheduling(mutableTimes, mutableVersions);
            mutableTimes.put(ScheduledGameTask.CLEANUP, 99_000L);
            mutableVersions.put(ScheduledGameTask.TURN_TIMEOUT, 99L);

            assertEquals(12_000L, restored.getScheduledTime(ScheduledGameTask.TURN_TIMEOUT));
            assertNull(restored.getScheduledTime(ScheduledGameTask.CLEANUP));
            assertEquals(4L, restored.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT));

            restored.scheduleTask(ScheduledGameTask.TURN_TIMEOUT, 15_000L);
            assertEquals(15_000L, restored.getScheduledTime(ScheduledGameTask.TURN_TIMEOUT));
            assertEquals(5L, restored.getTimerVersion(ScheduledGameTask.TURN_TIMEOUT));
        }

        @Test
        void restoreConstructor_NullMaps_ReturnEmptyImmutableSnapshots() {
            GameScheduling restored = new GameScheduling(null, null);

            Map<ScheduledGameTask, Long> deadlines = restored.scheduledTimesSnapshot();
            Map<ScheduledGameTask, Long> timerVersions = restored.timerVersionsSnapshot();

            assertTrue(deadlines.isEmpty());
            assertTrue(timerVersions.isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> deadlines.put(ScheduledGameTask.AUTO_ADVANCE, 1L));
            assertThrows(UnsupportedOperationException.class, () -> timerVersions.put(ScheduledGameTask.AUTO_ADVANCE, 1L));
        }
    }

    @Nested
    class InterleavedSchedulingAndCleanupInvariantsTests {

        @Test
        void interleavedOperationsAcrossAllTasksPreserveIndependentMonotonicCounters() {
            ScheduledGameTask[] allTasks = ScheduledGameTask.values();

            for (int step = 1; step <= 10; step++) {
                for (ScheduledGameTask task : allTasks) {
                    long expectedBefore = (long) (step - 1) * 2;
                    assertEquals(expectedBefore, scheduling.getTimerVersion(task));

                    scheduling.scheduleTask(task, 10000L + step);
                    assertEquals(expectedBefore + 1, scheduling.getTimerVersion(task));
                    assertEquals(10000L + step, scheduling.getScheduledTime(task));

                    scheduling.clearTask(task);
                    assertEquals(expectedBefore + 2, scheduling.getTimerVersion(task));
                    assertNull(scheduling.getScheduledTime(task));
                }
            }

            for (ScheduledGameTask task : allTasks) {
                assertEquals(20L, scheduling.getTimerVersion(task));
                assertNull(scheduling.getScheduledTime(task));
            }
        }

        @Test
        void scheduleTask_SameDeadline_IncrementsTimerVersion() {
            long fixedDeadline = 1710000000000L;
            for (long expectedVersion = 1; expectedVersion <= 5; expectedVersion++) {
                scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, fixedDeadline);
                assertEquals(fixedDeadline, scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT));
                assertEquals(expectedVersion, scheduling.getTimerVersion(ScheduledGameTask.READY_TIMEOUT));
            }
        }
    }

    @Nested
    class AdversarialBoundaryDeadlinesTests {

        @ParameterizedTest
        @ValueSource(longs = {
                0L, 1L, -1L, -999999999999L, Long.MIN_VALUE, Long.MAX_VALUE, 1700000000000L, 32503680000000L
        })
        void scheduleTask_PreservesExtremeDeadlinesWithoutTruncation(long extremeDeadline) {
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, extremeDeadline);

            Long retrieved = scheduling.getScheduledTime(ScheduledGameTask.READY_TIMEOUT);
            assertNotNull(retrieved);
            assertEquals(extremeDeadline, retrieved.longValue());
            assertEquals(extremeDeadline, scheduling.scheduledTimesSnapshot().get(ScheduledGameTask.READY_TIMEOUT));
        }
    }

    @Nested
    class TaskClaimAndExpiryTests {

        @Test
        void startScheduledTaskIfCurrent_WhenPausedForDisconnectedSurvivor_ReturnsFalseWithoutClearing() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long timerVersion = scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND);

            boolean started = scheduling.startScheduledTaskIfCurrent(
                    true, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, timerVersion);

            assertFalse(started);
            assertEquals(5000L, scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
            assertEquals(timerVersion, scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void startScheduledTaskIfCurrent_WhenHandNumberStale_ReturnsFalseForNonCleanupTask() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long timerVersion = scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND);

            boolean started = scheduling.startScheduledTaskIfCurrent(
                    false, 2L, ScheduledGameTask.NEW_HAND, 5000L, 1L, timerVersion);

            assertFalse(started);
            assertEquals(5000L, scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void startScheduledTaskIfCurrent_WhenTimerVersionStale_ReturnsFalse() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 6000L);
            long currentVersion = scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND);

            boolean started = scheduling.startScheduledTaskIfCurrent(
                    false, 1L, ScheduledGameTask.NEW_HAND, 6000L, 1L, currentVersion - 1);

            assertFalse(started);
            assertEquals(6000L, scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void startScheduledTaskIfCurrent_WhenCleanupTask_ReturnsTrueAndRetainsDeadlineAcrossHands() {
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 5000L);
            long timerVersion = scheduling.getTimerVersion(ScheduledGameTask.CLEANUP);

            boolean started = scheduling.startScheduledTaskIfCurrent(
                    false, 99L, ScheduledGameTask.CLEANUP, 5000L, 1L, timerVersion);

            assertTrue(started);
            assertEquals(5000L, scheduling.getScheduledTime(ScheduledGameTask.CLEANUP));
            assertEquals(timerVersion, scheduling.getTimerVersion(ScheduledGameTask.CLEANUP));
        }

        @Test
        void startScheduledTaskIfCurrent_WhenDeadlineMismatchedOrUnscheduled_ReturnsFalse() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long timerVersion = scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND);

            assertFalse(scheduling.startScheduledTaskIfCurrent(false, 1L, ScheduledGameTask.NEW_HAND, 4000L, 1L, timerVersion));
            assertFalse(scheduling.startScheduledTaskIfCurrent(false, 1L, ScheduledGameTask.READY_OPEN, 5000L, 1L, 0L));
        }

        @Test
        void startScheduledTaskIfCurrent_Success_ClearsNonCleanupTaskAndIncrementsTimerVersion() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long timerVersion = scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND);

            boolean started = scheduling.startScheduledTaskIfCurrent(
                    false, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, timerVersion);

            assertTrue(started);
            assertNull(scheduling.getScheduledTime(ScheduledGameTask.NEW_HAND));
            assertEquals(timerVersion + 1, scheduling.getTimerVersion(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void isCleanupExpired_EvaluatesCleanupDeadlineCorrectly() {
            assertFalse(scheduling.isCleanupExpired(1000L));

            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 5000L);
            assertFalse(scheduling.isCleanupExpired(4999L));
            assertTrue(scheduling.isCleanupExpired(5000L));
            assertTrue(scheduling.isCleanupExpired(5001L));
        }
    }
}
