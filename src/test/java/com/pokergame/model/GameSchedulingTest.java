package com.pokergame.model;

import com.pokergame.enums.ScheduledGameTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        void scheduleTask_NewTask_SetsDeadlineAndInitializesGeneration() {
            long deadline = 1700000000000L;
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, deadline);

            assertEquals(deadline, scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        void scheduleTask_ExistingTaskRescheduled_UpdatesDeadlineAndIncrementsGeneration() {
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 1000L);
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.READY_TIMEOUT));

            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 2000L);
            assertEquals(2000L, scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.READY_TIMEOUT));

            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 3000L);
            assertEquals(3000L, scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(3L, scheduling.getGeneration(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void scheduleTask_MultipleTasks_TrackIndependently() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 100L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 200L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 300L);
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 400L);

            assertEquals(400L, scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(200L, scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(300L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));

            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.CLEANUP));
        }

        @Test
        void scheduleTask_NullTask_HandledGracefully() {
            assertDoesNotThrow(() -> scheduling.scheduleTask(null, 500L));
            assertTrue(scheduling.deadlinesSnapshot().isEmpty());
            assertTrue(scheduling.generationsSnapshot().isEmpty());
        }
    }

    @Nested
    class TaskClearingTests {

        @Test
        void clearTask_ScheduledTask_RemovesDeadlineAndIncrementsGeneration() {
            scheduling.scheduleTask(ScheduledGameTask.READY_OPEN, 5000L);
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.READY_OPEN));

            scheduling.clearTask(ScheduledGameTask.READY_OPEN);

            assertNull(scheduling.getDeadline(ScheduledGameTask.READY_OPEN));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.READY_OPEN));
        }

        @Test
        void clearTask_UnscheduledTask_IncrementsGeneration() {
            scheduling.clearTask(ScheduledGameTask.NEW_HAND);

            assertNull(scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void clearTask_DoesNotAffectOtherTasks() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1000L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 2000L);

            scheduling.clearTask(ScheduledGameTask.AUTO_ADVANCE);

            assertNull(scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2000L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.CLEANUP));
        }

        @Test
        void clearTask_NullTask_HandledGracefully() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1000L);
            assertDoesNotThrow(() -> scheduling.clearTask(null));
            assertEquals(1000L, scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
        }
    }

    @Nested
    class TaskInvalidationTests {

        @Test
        void invalidateProgressionTasks_PreservesCleanup_ClearsAllOthers() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 100L);
            scheduling.scheduleTask(ScheduledGameTask.READY_OPEN, 200L);
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 300L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 400L);
            scheduling.scheduleTask(ScheduledGameTask.GAME_END, 500L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 600L);

            scheduling.invalidateProgressionTasks();

            assertNull(scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertNull(scheduling.getDeadline(ScheduledGameTask.READY_OPEN));
            assertNull(scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertNull(scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertNull(scheduling.getDeadline(ScheduledGameTask.GAME_END));

            assertEquals(600L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.CLEANUP));

            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.READY_OPEN));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.GAME_END));
        }

        @Test
        void invalidateProgressionTasks_OnlyCleanupScheduled_LeavesIntact() {
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 9999L);

            scheduling.invalidateProgressionTasks();

            assertEquals(9999L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.CLEANUP));
        }

        @Test
        void invalidateProgressionTasks_NoCleanupScheduled_ClearsAll() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 100L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 200L);

            scheduling.invalidateProgressionTasks();

            assertTrue(scheduling.deadlinesSnapshot().isEmpty());
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void invalidateProgressionTasks_EmptyMaps_DoesNotThrow() {
            assertDoesNotThrow(() -> scheduling.invalidateProgressionTasks());
            assertTrue(scheduling.deadlinesSnapshot().isEmpty());
        }

        @Test
        void clearAllTasks_ClearsEveryTaskIncludingCleanupAndBumpsGenerations() {
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                scheduling.scheduleTask(task, 100L + task.ordinal());
            }

            scheduling.clearAllTasks();

            assertTrue(scheduling.deadlinesSnapshot().isEmpty());
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                assertNull(scheduling.getDeadline(task));
                assertEquals(2L, scheduling.getGeneration(task));
            }
        }
    }

    @Nested
    class MonotonicGenerationTokenTests {

        @Test
        void generationToken_StrictlyIncreasesAcrossLifecycle() {
            ScheduledGameTask task = ScheduledGameTask.GAME_END;
            assertEquals(0L, scheduling.getGeneration(task));

            scheduling.scheduleTask(task, 1000L);
            long g1 = scheduling.getGeneration(task);

            scheduling.scheduleTask(task, 2000L);
            long g2 = scheduling.getGeneration(task);

            scheduling.clearTask(task);
            long g3 = scheduling.getGeneration(task);

            scheduling.scheduleTask(task, 3000L);
            long g4 = scheduling.getGeneration(task);

            scheduling.invalidateProgressionTasks();
            long g5 = scheduling.getGeneration(task);

            assertTrue(g1 < g2 && g2 < g3 && g3 < g4 && g4 < g5);
            assertEquals(5L, g5);
        }

        @ParameterizedTest
        @EnumSource(ScheduledGameTask.class)
        void getGeneration_UnscheduledTask_ReturnsZero(ScheduledGameTask task) {
            assertEquals(0L, scheduling.getGeneration(task));
        }
    }

    @Nested
    class SnapshotIsolationTests {

        @Test
        void deadlinesSnapshot_ReturnsImmutableMap() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 5000L);
            Map<ScheduledGameTask, Long> snapshot = scheduling.deadlinesSnapshot();

            assertEquals(1, snapshot.size());
            assertEquals(5000L, snapshot.get(ScheduledGameTask.AUTO_ADVANCE));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put(ScheduledGameTask.CLEANUP, 1000L));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(ScheduledGameTask.AUTO_ADVANCE));
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.putAll(Map.of(ScheduledGameTask.NEW_HAND, 2000L)));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.replaceAll((k, v) -> 999L));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.keySet().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.values().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.entrySet().clear());
            assertThrows(UnsupportedOperationException.class, () -> {
                var it = snapshot.entrySet().iterator();
                it.next();
                it.remove();
            });
        }

        @Test
        void deadlinesSnapshot_IsolatedFromUnderlyingMutations() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 5000L);
            Map<ScheduledGameTask, Long> snapshot = scheduling.deadlinesSnapshot();

            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 8000L);
            scheduling.clearTask(ScheduledGameTask.AUTO_ADVANCE);

            assertEquals(1, snapshot.size());
            assertEquals(5000L, snapshot.get(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        void generationsSnapshot_ReturnsImmutableMap() {
            scheduling.scheduleTask(ScheduledGameTask.READY_OPEN, 5000L);
            Map<ScheduledGameTask, Long> snapshot = scheduling.generationsSnapshot();

            assertThrows(UnsupportedOperationException.class, () -> snapshot.put(ScheduledGameTask.CLEANUP, 10L));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(ScheduledGameTask.READY_OPEN));
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.putAll(Map.of(ScheduledGameTask.GAME_END, 5L)));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.replaceAll((k, v) -> 999L));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.keySet().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.values().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.entrySet().clear());
        }

        @Test
        void generationsSnapshot_IsolatedFromUnderlyingMutations() {
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 1000L);
            Map<ScheduledGameTask, Long> snapshot = scheduling.generationsSnapshot();

            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 2000L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 3000L);

            assertEquals(1, snapshot.size());
            assertEquals(1L, snapshot.get(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void snapshots_MultiGenerationalIsolation() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1000L);
            Map<ScheduledGameTask, Long> d1 = scheduling.deadlinesSnapshot();
            Map<ScheduledGameTask, Long> g1 = scheduling.generationsSnapshot();

            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 2000L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 60000L);
            Map<ScheduledGameTask, Long> d2 = scheduling.deadlinesSnapshot();
            Map<ScheduledGameTask, Long> g2 = scheduling.generationsSnapshot();

            scheduling.invalidateProgressionTasks();
            Map<ScheduledGameTask, Long> d3 = scheduling.deadlinesSnapshot();
            Map<ScheduledGameTask, Long> g3 = scheduling.generationsSnapshot();

            assertEquals(1, d1.size());
            assertEquals(1000L, d1.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g1.get(ScheduledGameTask.AUTO_ADVANCE));

            assertEquals(2, d2.size());
            assertEquals(2000L, d2.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, d2.get(ScheduledGameTask.CLEANUP));
            assertEquals(2L, g2.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g2.get(ScheduledGameTask.CLEANUP));

            assertEquals(1, d3.size());
            assertFalse(d3.containsKey(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, d3.get(ScheduledGameTask.CLEANUP));
            assertEquals(3L, g3.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g3.get(ScheduledGameTask.CLEANUP));
        }

        @Test
        void restoreConstructor_NullMaps_ReturnEmptyImmutableSnapshots() {
            GameScheduling restored = new GameScheduling(null, null, 0L);

            Map<ScheduledGameTask, Long> deadlines = restored.deadlinesSnapshot();
            Map<ScheduledGameTask, Long> generations = restored.generationsSnapshot();

            assertTrue(deadlines.isEmpty());
            assertTrue(generations.isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> deadlines.put(ScheduledGameTask.AUTO_ADVANCE, 1L));
            assertThrows(UnsupportedOperationException.class, () -> generations.put(ScheduledGameTask.AUTO_ADVANCE, 1L));
        }
    }

    @Nested
    class StateRevisionTests {

        @Test
        void incrementStateRevision_MonotonicAcrossSequence() {
            for (int i = 1; i <= 10000; i++) {
                scheduling.incrementStateRevision();
                assertEquals((long) i, scheduling.getStateRevision());
            }

            GameScheduling nearMax = new GameScheduling(null, null, Long.MAX_VALUE - 1);
            nearMax.incrementStateRevision();
            assertEquals(Long.MAX_VALUE, nearMax.getStateRevision());
        }

        @Test
        void resetStateRevisionForNewHand_ResetsToInitialHandRevision() {
            scheduling.incrementStateRevision();
            scheduling.incrementStateRevision();

            scheduling.resetStateRevisionForNewHand();

            assertEquals(1L, GameScheduling.INITIAL_HAND_REVISION);
            assertEquals(1L, scheduling.getStateRevision());
        }
    }

    @Nested
    class NullSafetyTests {

        @Test
        void nullTaskArgumentsReturnSafeDefaults() {
            assertDoesNotThrow(() -> scheduling.scheduleTask(null, 0L));
            assertDoesNotThrow(() -> scheduling.clearTask(null));
            assertNull(scheduling.getDeadline(null));
            assertEquals(0L, scheduling.getGeneration(null));
        }
    }

    @Nested
    class InterleavedSchedulingAndCleanupInvariantsTests {

        @Test
        void interleavedOperationsAcrossAllTasksPreserveIndependentMonotonicCounters() {
            ScheduledGameTask[] allTasks = ScheduledGameTask.values();

            for (int step = 1; step <= 50; step++) {
                for (ScheduledGameTask task : allTasks) {
                    long expectedBefore = (long) (step - 1) * 2;
                    assertEquals(expectedBefore, scheduling.getGeneration(task));

                    scheduling.scheduleTask(task, 10000L + step);
                    assertEquals(expectedBefore + 1, scheduling.getGeneration(task));
                    assertEquals(10000L + step, scheduling.getDeadline(task));

                    scheduling.clearTask(task);
                    assertEquals(expectedBefore + 2, scheduling.getGeneration(task));
                    assertNull(scheduling.getDeadline(task));
                }
            }

            for (ScheduledGameTask task : allTasks) {
                assertEquals(100L, scheduling.getGeneration(task));
                assertNull(scheduling.getDeadline(task));
            }
        }

        @Test
        void scheduleTask_SameDeadline_IncrementsGeneration() {
            long fixedDeadline = 1710000000000L;
            for (long expectedGen = 1; expectedGen <= 10; expectedGen++) {
                scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, fixedDeadline);
                assertEquals(fixedDeadline, scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT));
                assertEquals(expectedGen, scheduling.getGeneration(ScheduledGameTask.READY_TIMEOUT));
            }
        }

        @Test
        void clearTask_RepeatedClears_ContinuouslyIncrementsGeneration() {
            assertNull(scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(0L, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));

            for (long expectedGen = 1; expectedGen <= 10; expectedGen++) {
                scheduling.clearTask(ScheduledGameTask.NEW_HAND);
                assertNull(scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
                assertEquals(expectedGen, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
            }
        }

        @Test
        void invalidateProgressionTasks_InterleavedWithCleanup_PreservesCleanupAcrossCycles() {
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 77777L);

            for (int cycle = 1; cycle <= 10; cycle++) {
                scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1000L + cycle);
                scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 2000L + cycle);

                scheduling.invalidateProgressionTasks();

                assertEquals(77777L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
                assertEquals(1L, scheduling.getGeneration(ScheduledGameTask.CLEANUP));
                assertEquals(1, scheduling.deadlinesSnapshot().size());
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

            Long retrieved = scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT);
            assertNotNull(retrieved);
            assertEquals(extremeDeadline, retrieved.longValue());
            assertEquals(extremeDeadline, scheduling.deadlinesSnapshot().get(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        void distinctBoundaryDeadlinesCoexistAcrossDifferentTasksSimultaneously() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, Long.MIN_VALUE);
            scheduling.scheduleTask(ScheduledGameTask.READY_OPEN, 0L);
            scheduling.scheduleTask(ScheduledGameTask.READY_TIMEOUT, -1L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 1700000000000L);
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, Long.MAX_VALUE);
            scheduling.scheduleTask(ScheduledGameTask.GAME_END, 32503680000000L);

            assertEquals(Long.MIN_VALUE, scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(0L, scheduling.getDeadline(ScheduledGameTask.READY_OPEN));
            assertEquals(-1L, scheduling.getDeadline(ScheduledGameTask.READY_TIMEOUT));
            assertEquals(1700000000000L, scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(Long.MAX_VALUE, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(32503680000000L, scheduling.getDeadline(ScheduledGameTask.GAME_END));
        }
    }

    @Nested
    class TaskClaimAndExpiryTests {

        @Test
        void claimTask_WhenForfeitPending_ReturnsFalseWithoutClearing() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.NEW_HAND);

            boolean claimed = scheduling.claimTask(
                    true, false, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertFalse(claimed);
            assertEquals(5000L, scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(gen, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void claimTask_WhenHandNumberStale_ReturnsFalseForNonCleanupTask() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.NEW_HAND);

            boolean claimed = scheduling.claimTask(
                    false, false, 2L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertFalse(claimed);
            assertEquals(5000L, scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void claimTask_WhenGenerationStale_ReturnsFalse() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 6000L);
            long currentGen = scheduling.getGeneration(ScheduledGameTask.NEW_HAND);

            boolean claimed = scheduling.claimTask(
                    false, false, 1L, ScheduledGameTask.NEW_HAND, 6000L, 1L, currentGen - 1);

            assertFalse(claimed);
            assertEquals(6000L, scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
        }

        @Test
        void claimTask_WhenGameOverAndNonCleanup_ClearsAndReturnsFalse() {
            scheduling.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE);

            boolean claimed = scheduling.claimTask(
                    false, true, 1L, ScheduledGameTask.AUTO_ADVANCE, 5000L, 1L, gen);

            assertFalse(claimed);
            assertNull(scheduling.getDeadline(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(gen + 1, scheduling.getGeneration(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        void claimTask_WhenGameOverAndCleanup_ReturnsTrueAndRetainsDeadline() {
            scheduling.scheduleTask(ScheduledGameTask.CLEANUP, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.CLEANUP);

            boolean claimed = scheduling.claimTask(
                    false, true, 99L, ScheduledGameTask.CLEANUP, 5000L, 1L, gen);

            assertTrue(claimed);
            assertEquals(5000L, scheduling.getDeadline(ScheduledGameTask.CLEANUP));
            assertEquals(gen, scheduling.getGeneration(ScheduledGameTask.CLEANUP));
        }

        @Test
        void claimTask_WhenDeadlineMismatchedOrUnscheduled_ReturnsFalse() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.NEW_HAND);

            assertFalse(scheduling.claimTask(false, false, 1L, ScheduledGameTask.NEW_HAND, 4000L, 1L, gen));
            assertFalse(scheduling.claimTask(false, false, 1L, ScheduledGameTask.READY_OPEN, 5000L, 1L, 0L));
        }

        @Test
        void claimTask_Success_ClearsNonCleanupTaskAndIncrementsGeneration() {
            scheduling.scheduleTask(ScheduledGameTask.NEW_HAND, 5000L);
            long gen = scheduling.getGeneration(ScheduledGameTask.NEW_HAND);

            boolean claimed = scheduling.claimTask(
                    false, false, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertTrue(claimed);
            assertNull(scheduling.getDeadline(ScheduledGameTask.NEW_HAND));
            assertEquals(gen + 1, scheduling.getGeneration(ScheduledGameTask.NEW_HAND));
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
