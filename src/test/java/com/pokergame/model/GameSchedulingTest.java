package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for the package-private {@link GameScheduling} collaborator.
 * Verifies durable task scheduling, generation token increments, progression invalidation
 * (preserving CLEANUP), defensive snapshot immutability, stateRevision increments,
 * and terminal result retention.
 */
@Tag("unit")
@DisplayName("GameScheduling collaborator")
class GameSchedulingTest {

    private Map<ScheduledGameTask, Long> deadlines;
    private Map<ScheduledGameTask, Long> generations;

    @BeforeEach
    void setUp() {
        deadlines = new EnumMap<>(ScheduledGameTask.class);
        generations = new EnumMap<>(ScheduledGameTask.class);
    }

    // =========================================================================
    // 1. Task Scheduling (scheduleTask)
    // =========================================================================

    @Nested
    @DisplayName("Task Scheduling")
    class TaskSchedulingTests {

        @Test
        @DisplayName("scheduleTask sets absolute deadline and initializes generation to 1")
        void scheduleTask_NewTask_SetsDeadlineAndInitializesGeneration() {
            long deadline = 1700000005000L;
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, deadline);

            assertEquals(deadline, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("Rescheduling existing task updates deadline and increments generation token")
        void scheduleTask_ExistingTaskRescheduled_UpdatesDeadlineAndIncrementsGeneration() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));

            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 2000L);
            assertEquals(2000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));

            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 3000L);
            assertEquals(3000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(3L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("Multiple distinct tasks track deadlines and generations independently")
        void scheduleTask_MultipleTasks_TrackIndependently() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 2000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);

            // Reschedule only AUTO_ADVANCE
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1500L);

            assertEquals(1500L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));

            assertEquals(2000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_TIMEOUT));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.READY_TIMEOUT));

            assertEquals(60000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("Passing null task is handled gracefully without corrupting state")
        void scheduleTask_NullTask_HandledGracefully() {
            assertDoesNotThrow(() -> GameScheduling.scheduleTask(deadlines, generations, null, 1000L));
            assertTrue(deadlines.isEmpty());
            assertTrue(generations.isEmpty());
        }
    }

    // =========================================================================
    // 2. Task Clearing (clearScheduledTask)
    // =========================================================================

    @Nested
    @DisplayName("Task Clearing")
    class TaskClearingTests {

        @Test
        @DisplayName("clearScheduledTask removes deadline and increments generation")
        void clearScheduledTask_ScheduledTask_RemovesDeadlineAndIncrementsGeneration() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));

            GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE);

            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("clearScheduledTask on unscheduled task increments generation without error")
        void clearScheduledTask_UnscheduledTask_IncrementsGeneration() {
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));

            GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE);

            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("clearScheduledTask does not affect other scheduled tasks")
        void clearScheduledTask_DoesNotAffectOtherTasks() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);

            GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE);

            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("Passing null task is handled gracefully")
        void clearScheduledTask_NullTask_HandledGracefully() {
            assertDoesNotThrow(() -> GameScheduling.clearScheduledTask(deadlines, generations, null));
        }
    }

    // =========================================================================
    // 3. Task Invalidation (invalidateProgressionTasks)
    // =========================================================================

    @Nested
    @DisplayName("Task Invalidation Invariant (Preserving CLEANUP)")
    class TaskInvalidationTests {

        @Test
        @DisplayName("invalidateProgressionTasks clears all progression tasks but preserves CLEANUP intact")
        void invalidateProgressionTasks_PreservesCleanup_ClearsAllOthers() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_OPEN, 2000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 3000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 4000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.GAME_END, 5000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);

            GameScheduling.invalidateProgressionTasks(deadlines, generations);

            // Progression tasks removed from deadlines
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_OPEN));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_TIMEOUT));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.GAME_END));

            // Progression tasks generations incremented
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.READY_OPEN));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.READY_TIMEOUT));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.NEW_HAND));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.GAME_END));

            // CLEANUP task preserved in deadlines and its generation UNTOUCHED
            assertEquals(60000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("When only CLEANUP is scheduled, invalidation leaves deadlines and generations unchanged")
        void invalidateProgressionTasks_OnlyCleanupScheduled_LeavesIntact() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);

            GameScheduling.invalidateProgressionTasks(deadlines, generations);

            assertEquals(60000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("When no CLEANUP is scheduled, all progression tasks cleared and deadlines empty")
        void invalidateProgressionTasks_NoCleanupScheduled_ClearsAll() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 2000L);

            GameScheduling.invalidateProgressionTasks(deadlines, generations);

            assertTrue(deadlines.isEmpty());
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("Invalidation on empty maps is a safe no-op")
        void invalidateProgressionTasks_EmptyMaps_DoesNotThrow() {
            assertDoesNotThrow(() -> GameScheduling.invalidateProgressionTasks(deadlines, generations));
            assertTrue(deadlines.isEmpty());
            assertTrue(generations.isEmpty());
        }
    }

    // =========================================================================
    // 4. Strict Monotonic Generation Invariant
    // =========================================================================

    @Nested
    @DisplayName("Monotonic Generation Token")
    class MonotonicGenerationTokenTests {

        @Test
        @DisplayName("Generations strictly increase monotonically across complex lifecycle sequence")
        void generationToken_StrictlyIncreasesAcrossLifecycle() {
            ScheduledGameTask task = ScheduledGameTask.AUTO_ADVANCE;

            assertEquals(0L, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.scheduleTask(deadlines, generations, task, 1000L);
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.scheduleTask(deadlines, generations, task, 1500L);
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.clearScheduledTask(deadlines, generations, task);
            assertEquals(3L, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.scheduleTask(deadlines, generations, task, 2000L);
            assertEquals(4L, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.invalidateProgressionTasks(deadlines, generations);
            assertEquals(5L, GameScheduling.getScheduledTaskGeneration(generations, task));
        }

        @ParameterizedTest
        @EnumSource(ScheduledGameTask.class)
        @DisplayName("Initial generation lookup for any unscheduled task returns 0L")
        void getScheduledTaskGeneration_UnscheduledTask_ReturnsZero(ScheduledGameTask task) {
            assertEquals(0L, GameScheduling.getScheduledTaskGeneration(generations, task));
        }
    }

    // =========================================================================
    // 5. Snapshot Immutability and Defensive Isolation
    // =========================================================================

    @Nested
    @DisplayName("Snapshot Immutability and Defensive Isolation")
    class SnapshotIsolationTests {

        @Test
        @DisplayName("Deadlines snapshot is unmodifiable and throws on modification attempt")
        void getScheduledTaskDeadlinesSnapshot_ReturnsImmutableMap() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);

            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);

            assertEquals(1, snapshot.size());
            assertEquals(1000L, snapshot.get(ScheduledGameTask.AUTO_ADVANCE));

            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.put(ScheduledGameTask.CLEANUP, 2000L));
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.remove(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("Underlying mutations do not affect previously captured deadlines snapshot")
        void getScheduledTaskDeadlinesSnapshot_IsolatedFromUnderlyingMutations() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);

            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);

            // Mutate live map
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);
            GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE);

            // Snapshot remains untouched
            assertEquals(1, snapshot.size());
            assertEquals(1000L, snapshot.get(ScheduledGameTask.AUTO_ADVANCE));
            assertFalse(snapshot.containsKey(ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("Generations snapshot is unmodifiable and throws on modification attempt")
        void getScheduledTaskGenerationsSnapshot_ReturnsImmutableMap() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);

            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

            assertEquals(1, snapshot.size());
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.put(ScheduledGameTask.CLEANUP, 99L));
        }

        @Test
        @DisplayName("Underlying mutations do not affect previously captured generations snapshot")
        void getScheduledTaskGenerationsSnapshot_IsolatedFromUnderlyingMutations() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);

            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

            // Mutate live map
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 2000L);

            // Snapshot maintains original value
            assertEquals(1L, snapshot.get(ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("Snapshots on null maps return empty immutable maps")
        void snapshots_NullMaps_ReturnEmptyImmutableMap() {
            Map<ScheduledGameTask, Long> deadlineSnapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(null);
            assertNotNull(deadlineSnapshot);
            assertTrue(deadlineSnapshot.isEmpty());

            Map<ScheduledGameTask, Long> genSnapshot = GameScheduling.getScheduledTaskGenerationsSnapshot(null);
            assertNotNull(genSnapshot);
            assertTrue(genSnapshot.isEmpty());
        }
    }

    // =========================================================================
    // 6. State Revision Monotonic Counter
    // =========================================================================

    @Nested
    @DisplayName("State Revision Monotonic Counter")
    class StateRevisionTests {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 42L, 9999L, Long.MAX_VALUE - 1})
        @DisplayName("incrementStateRevision strictly increments revision by 1")
        void incrementStateRevision_IncrementsByOne(long currentRevision) {
            assertEquals(currentRevision + 1, GameScheduling.incrementStateRevision(currentRevision));
        }

        @Test
        @DisplayName("resetStateRevisionForNewHand resets counter to 1L")
        void resetStateRevisionForNewHand_ResetsToOne() {
            assertEquals(1L, GameScheduling.resetStateRevisionForNewHand());
        }
    }

    // =========================================================================
    // 7. Retained Terminal Result Retention
    // =========================================================================

    @Nested
    @DisplayName("Retained Terminal Result Retention")
    class RetainedTerminalResultTests {

        @Test
        @DisplayName("Terminal result can be set, retained, and retrieved without mutation")
        void retainedTerminalResult_RetainedFaithfully() {
            GameEndResponse endResponse = new GameEndResponse(
                    "GAME_END", "game-123", "WinnerAlice", 1500, false, "Alice won", null);

            assertNotNull(endResponse);
            assertEquals("WinnerAlice", endResponse.winner());
            assertEquals(1500, endResponse.winnerChips());
            assertEquals("game-123", endResponse.gameId());
        }
    }

    // =========================================================================
    // 8. Null Safety and Defensive Checks
    // =========================================================================

    @Nested
    @DisplayName("Null Safety and Defensive Checks")
    class NullSafetyTests {

        @Test
        @DisplayName("null guards return safe defaults without throwing NPE")
        void nullGuards_SafeDefaults() {
            assertDoesNotThrow(() -> GameScheduling.scheduleTask(null, null, null, 0L));
            assertDoesNotThrow(() -> GameScheduling.clearScheduledTask(null, null, null));
            assertDoesNotThrow(() -> GameScheduling.invalidateProgressionTasks(null, null));
            assertNull(GameScheduling.getScheduledTaskDeadline(null, null));
            assertEquals(0L, GameScheduling.getScheduledTaskGeneration(null, null));
            assertTrue(GameScheduling.getScheduledTaskDeadlinesSnapshot(null).isEmpty());
            assertTrue(GameScheduling.getScheduledTaskGenerationsSnapshot(null).isEmpty());
        }
    }

    // =========================================================================
    // 9. Interleaved Scheduling Operations
    // =========================================================================

    @Nested
    @DisplayName("Interleaved Scheduling Operations")
    class InterleavedSchedulingOperations {

        @Test
        @DisplayName("Interleaved operations across all enum tasks preserve independent monotonic counters")
        void testMonotonicGenerations_InterleavedOperationsAcrossAllTasks() {
            ScheduledGameTask[] allTasks = ScheduledGameTask.values();

            for (int step = 1; step <= 50; step++) {
                for (ScheduledGameTask task : allTasks) {
                    long expectedBefore = (long) (step - 1) * 2;
                    assertEquals(expectedBefore, GameScheduling.getScheduledTaskGeneration(generations, task));

                    // Schedule operation (increments by 1)
                    GameScheduling.scheduleTask(deadlines, generations, task, 10000L + step);
                    assertEquals(expectedBefore + 1, GameScheduling.getScheduledTaskGeneration(generations, task));
                    assertEquals(10000L + step, GameScheduling.getScheduledTaskDeadline(deadlines, task));

                    // Clear operation (increments by 1)
                    GameScheduling.clearScheduledTask(deadlines, generations, task);
                    assertEquals(expectedBefore + 2, GameScheduling.getScheduledTaskGeneration(generations, task));
                    assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task));
                }
            }

            // Verify final state: each task has generation 100, no deadlines remain
            for (ScheduledGameTask task : allTasks) {
                assertEquals(100L, GameScheduling.getScheduledTaskGeneration(generations, task));
                assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task));
            }
        }

        @Test
        @DisplayName("Scheduling with identical deadline still strictly increments generation token")
        void testScheduleTask_SameDeadline_IncrementsGeneration() {
            ScheduledGameTask task = ScheduledGameTask.READY_TIMEOUT;
            long fixedDeadline = 1710000000000L;

            for (long expectedGen = 1; expectedGen <= 10; expectedGen++) {
                GameScheduling.scheduleTask(deadlines, generations, task, fixedDeadline);
                assertEquals(fixedDeadline, GameScheduling.getScheduledTaskDeadline(deadlines, task));
                assertEquals(expectedGen, GameScheduling.getScheduledTaskGeneration(generations, task));
            }
        }

        @Test
        @DisplayName("Clearing already-cleared or never-scheduled task continuously increments generation monotonically")
        void testClearTask_RepeatedClears_ContinuouslyIncrementsGeneration() {
            ScheduledGameTask task = ScheduledGameTask.NEW_HAND;
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task));
            assertEquals(0L, GameScheduling.getScheduledTaskGeneration(generations, task));

            for (long expectedGen = 1; expectedGen <= 10; expectedGen++) {
                GameScheduling.clearScheduledTask(deadlines, generations, task);
                assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task));
                assertEquals(expectedGen, GameScheduling.getScheduledTaskGeneration(generations, task));
            }
        }
    }

    // =========================================================================
    // 10. Adversarial Task Invalidation and CLEANUP Invariants
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Task Invalidation and CLEANUP Invariants")
    class AdversarialCleanupPreservationTests {

        @Test
        @DisplayName("invalidateProgressionTasks preserves CLEANUP deadline and generation unconditionally when all tasks active")
        void testInvalidateProgressionTasks_PreservesCleanup_DeadlinesAndGenerationIntact() {
            // Schedule all tasks with distinct deadlines
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1001L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_OPEN, 1002L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 1003L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 1004L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.GAME_END, 1005L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.TURN_TIMEOUT, 1006L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 999999L);

            // Verify initial generations are all 1L
            for (ScheduledGameTask task : ScheduledGameTask.values()) {
                assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, task));
            }

            // Invalidate progression tasks
            GameScheduling.invalidateProgressionTasks(deadlines, generations);

            ScheduledGameTask[] progressionTasks = {
                    ScheduledGameTask.AUTO_ADVANCE,
                    ScheduledGameTask.READY_OPEN,
                    ScheduledGameTask.READY_TIMEOUT,
                    ScheduledGameTask.NEW_HAND,
                    ScheduledGameTask.GAME_END,
                    ScheduledGameTask.TURN_TIMEOUT
            };
            for (ScheduledGameTask task : progressionTasks) {
                assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task),
                        "Progression task deadline must be removed: " + task);
                assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, task),
                        "Progression task generation must increment: " + task);
            }

            // CLEANUP must be completely untouched
            assertEquals(999999L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP),
                    "CLEANUP deadline must be strictly preserved");
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP),
                    "CLEANUP generation must be strictly preserved without increment");
            assertEquals(1, deadlines.size(), "Only CLEANUP must remain in deadlines");
        }

        @Test
        @DisplayName("Calling invalidateProgressionTasks repeatedly is idempotent and leaves CLEANUP untouched")
        void testInvalidateProgressionTasks_CalledRepeatedly_CleanupRemainsUnchanged() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 50000L);

            // First invalidation
            GameScheduling.invalidateProgressionTasks(deadlines, generations);
            assertEquals(50000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));

            // Repeated invalidations (subsequent calls should be no-ops for both deadlines and generations)
            for (int i = 0; i < 5; i++) {
                GameScheduling.invalidateProgressionTasks(deadlines, generations);
                assertEquals(50000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
                assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
                assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
                assertEquals(1, deadlines.size());
            }
        }

        @Test
        @DisplayName("Interleaving progression tasks and invalidations never alters CLEANUP token")
        void testInvalidateProgressionTasks_InterleavedWithCleanup_StrictIsolation() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 77777L);

            for (int cycle = 1; cycle <= 10; cycle++) {
                GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L + cycle);
                GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 2000L + cycle);

                GameScheduling.invalidateProgressionTasks(deadlines, generations);

                assertEquals(77777L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
                assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
                assertEquals(1, deadlines.size());
            }
        }
    }

    // =========================================================================
    // 11. Adversarial Boundary and Extreme Deadline Tests
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Boundary and Extreme Deadline Tests")
    class AdversarialBoundaryDeadlinesTests {

        @ParameterizedTest
        @ValueSource(longs = {
                0L,
                1L,
                -1L,
                -999999999999L,
                Long.MIN_VALUE,
                Long.MAX_VALUE,
                1700000000000L,
                32503680000000L
        })
        @DisplayName("scheduleTask preserves extreme boundary deadlines without truncation or error")
        void testBoundaryDeadlines_ExtremeValuesPreserved(long extremeDeadline) {
            ScheduledGameTask task = ScheduledGameTask.READY_TIMEOUT;

            GameScheduling.scheduleTask(deadlines, generations, task, extremeDeadline);

            Long retrieved = GameScheduling.getScheduledTaskDeadline(deadlines, task);
            assertNotNull(retrieved);
            assertEquals(extremeDeadline, retrieved.longValue());

            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
            assertEquals(extremeDeadline, snapshot.get(task).longValue());
        }

        @Test
        @DisplayName("Distinct boundary deadlines coexist across different tasks simultaneously")
        void testBoundaryDeadlines_CoexistenceOfExtremeDeadlines() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, Long.MIN_VALUE);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_OPEN, 0L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, -1L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 1700000000000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, Long.MAX_VALUE);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.GAME_END, 32503680000000L);

            assertEquals(Long.MIN_VALUE, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(0L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_OPEN));
            assertEquals(-1L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_TIMEOUT));
            assertEquals(1700000000000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertEquals(Long.MAX_VALUE, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(32503680000000L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.GAME_END));
        }
    }

    // =========================================================================
    // 12. Adversarial Snapshot Immutability and Mutation Isolation
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Snapshot Immutability and Mutation Isolation")
    class AdversarialSnapshotDeepImmutabilityTests {

        @Test
        @DisplayName("Deadlines snapshot rejects every mutative collection operation")
        void testDeadlinesSnapshot_AllMutativeOperationsThrow() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 5000L);
            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);

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
        @DisplayName("Generations snapshot rejects every mutative collection operation")
        void testGenerationsSnapshot_AllMutativeOperationsThrow() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_OPEN, 5000L);
            Map<ScheduledGameTask, Long> snapshot = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

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
        @DisplayName("Multi-generational snapshot isolation: mutating live map across cycles never alters earlier snapshots")
        void testSnapshots_MultiGenerationalIsolation() {
            // S1: 1 task
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L);
            Map<ScheduledGameTask, Long> d1 = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
            Map<ScheduledGameTask, Long> g1 = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

            // S2: 2 tasks, 1 updated
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 2000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);
            Map<ScheduledGameTask, Long> d2 = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
            Map<ScheduledGameTask, Long> g2 = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

            // S3: invalidation (clears AUTO_ADVANCE, keeps CLEANUP)
            GameScheduling.invalidateProgressionTasks(deadlines, generations);
            Map<ScheduledGameTask, Long> d3 = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
            Map<ScheduledGameTask, Long> g3 = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

            // Verify S1
            assertEquals(1, d1.size());
            assertEquals(1000L, d1.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g1.get(ScheduledGameTask.AUTO_ADVANCE));

            // Verify S2
            assertEquals(2, d2.size());
            assertEquals(2000L, d2.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, d2.get(ScheduledGameTask.CLEANUP));
            assertEquals(2L, g2.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g2.get(ScheduledGameTask.CLEANUP));

            // Verify S3
            assertEquals(1, d3.size());
            assertFalse(d3.containsKey(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(60000L, d3.get(ScheduledGameTask.CLEANUP));
            assertEquals(3L, g3.get(ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(1L, g3.get(ScheduledGameTask.CLEANUP));
        }
    }

    // =========================================================================
    // 13. Adversarial StateRevision Lifecycle and beginHand Integration
    // =========================================================================

    @Nested
    @DisplayName("Adversarial StateRevision Lifecycle and beginHand Integration")
    class AdversarialStateRevisionLifecycleTests {

        @Test
        @DisplayName("incrementStateRevision monotonic across long sequence and extreme boundary")
        void testStateRevision_IncrementMonotonicallyAcrossLongSequence() {
            long revision = 0L;
            for (int i = 1; i <= 10000; i++) {
                revision = GameScheduling.incrementStateRevision(revision);
                assertEquals((long) i, revision);
            }

            long nearMax = Long.MAX_VALUE - 1;
            assertEquals(Long.MAX_VALUE, GameScheduling.incrementStateRevision(nearMax));
        }

        @Test
        @DisplayName("resetStateRevisionForNewHand always returns INITIAL_HAND_REVISION (1L)")
        void testStateRevision_ResetAlwaysReturnsOne() {
            assertEquals(1L, GameScheduling.INITIAL_HAND_REVISION);
            assertEquals(1L, GameScheduling.resetStateRevisionForNewHand());
        }

        @Test
        @DisplayName("End-to-end Game aggregate root integration: stateRevision increments during hand and resets on beginHand()")
        void testStateRevision_IntegrationWithGameAggregateRoot() {
            HandEvaluatorService mockEvaluator = Mockito.mock(HandEvaluatorService.class);
            Player p1 = new Player("Alice", "p1", 1000);
            Player p2 = new Player("Bob", "p2", 1000);
            Game game = new Game("game-rev-test", List.of(p1, p2), 10, 20, mockEvaluator);

            // Initially stateRevision is 0 and handNumber is 0
            assertEquals(0L, game.getStateRevision());
            assertEquals(0L, game.getHandNumber());

            // First hand begins: handNumber becomes 1, stateRevision becomes 1L
            long hand1 = game.beginHand();
            assertEquals(1L, hand1);
            assertEquals(1L, game.getHandNumber());
            assertEquals(1L, game.getStateRevision());

            // Multiple betting turns occur in hand 1: stateRevision increments
            for (int i = 0; i < 25; i++) {
                game.incrementStateRevision();
            }
            assertEquals(26L, game.getStateRevision());

            // Second hand begins: handNumber advances to 2, stateRevision strictly resets to 1L
            long hand2 = game.beginHand();
            assertEquals(2L, hand2);
            assertEquals(2L, game.getHandNumber());
            assertEquals(1L, game.getStateRevision(), "beginHand() must reset stateRevision to 1L");

            // Further increments in hand 2
            game.incrementStateRevision();
            game.incrementStateRevision();
            assertEquals(3L, game.getStateRevision());
        }

        @Test
        @DisplayName("Game aggregate root restored from snapshot increments and resets stateRevision correctly")
        void testStateRevision_RestoredGameLifecycle() {
            HandEvaluatorService mockEvaluator = Mockito.mock(HandEvaluatorService.class);
            Player p1 = new Player("Alice", "p1", 1000);
            Player p2 = new Player("Bob", "p2", 1000);

            Game restoredGame = Game.restoreBuilder()
                    .gameId("game-restore-rev")
                    .players(List.of(p1, p2))
                    .activePlayerIds(List.of("p1", "p2"))
                    .smallBlind(10)
                    .bigBlind(20)
                    .handEvaluator(mockEvaluator)
                    .handNumber(42L)
                    .stateRevision(199L)
                    .scheduledTaskDeadlines(Map.of())
                    .scheduledTaskGenerations(Map.of())
                    .build();

            assertEquals(42L, restoredGame.getHandNumber());
            assertEquals(199L, restoredGame.getStateRevision());

            restoredGame.incrementStateRevision();
            assertEquals(200L, restoredGame.getStateRevision());

            long nextHand = restoredGame.beginHand();
            assertEquals(43L, nextHand);
            assertEquals(43L, restoredGame.getHandNumber());
            assertEquals(1L, restoredGame.getStateRevision());
        }
    }

    // =========================================================================
    // 14. API Method Alias Parity Tests
    // =========================================================================

    @Nested
    @DisplayName("API Method Alias Parity Tests")
    class MethodAliasParityTests {

        @Test
        @DisplayName("clearTask and clearScheduledTask are fully identical in behavior")
        void testAlias_ClearTask() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.NEW_HAND);
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.NEW_HAND));

            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 6000L);
            GameScheduling.clearTask(deadlines, generations, ScheduledGameTask.NEW_HAND);
            assertNull(GameScheduling.getDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertEquals(4L, GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND));
        }

        @Test
        @DisplayName("getDeadline, getScheduledTaskDeadline, and getTaskDeadline return identical results")
        void testAlias_GetDeadline() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 44444L);

            assertEquals(44444L, GameScheduling.getDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(44444L, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(44444L, GameScheduling.getTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("getGeneration, getScheduledTaskGeneration, and getTaskGeneration return identical results")
        void testAlias_GetGeneration() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 44444L);

            assertEquals(1L, GameScheduling.getGeneration(generations, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getTaskGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("copyDeadlines and getScheduledTaskDeadlinesSnapshot return identical results")
        void testAlias_CopyDeadlines() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 44444L);

            Map<ScheduledGameTask, Long> snap1 = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
            Map<ScheduledGameTask, Long> snap2 = GameScheduling.copyDeadlines(deadlines);
            assertEquals(snap1, snap2);
        }

        @Test
        @DisplayName("copyGenerations and getScheduledTaskGenerationsSnapshot return identical results")
        void testAlias_CopyGenerations() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 44444L);

            Map<ScheduledGameTask, Long> snap1 = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);
            Map<ScheduledGameTask, Long> snap2 = GameScheduling.copyGenerations(generations);
            assertEquals(snap1, snap2);
        }
    }

    // =========================================================================
    // 15. Task Claim and Expiry Tests
    // =========================================================================

    @Nested
    @DisplayName("Task Claim and Expiry")
    class TaskClaimAndExpiryTests {

        @Test
        @DisplayName("claimTaskExecution returns IGNORE when forfeit is pending")
        void claimTaskExecution_WhenForfeitPending_ReturnsIgnore() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, true, false, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertEquals(Game.TaskExecutionClaim.IGNORE, claim);
            assertEquals(5000L, GameScheduling.getDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertEquals(gen, GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND));
        }

        @Test
        @DisplayName("claimTaskExecution returns IGNORE when hand number is stale")
        void claimTaskExecution_WhenHandNumberStale_ReturnsIgnore() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, false, 2L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertEquals(Game.TaskExecutionClaim.IGNORE, claim);
            assertEquals(5000L, GameScheduling.getDeadline(deadlines, ScheduledGameTask.NEW_HAND));
        }

        @Test
        @DisplayName("claimTaskExecution returns IGNORE when generation token is stale")
        void claimTaskExecution_WhenGenerationStale_ReturnsIgnore() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 6000L);
            long currentGen = GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, false, 1L, ScheduledGameTask.NEW_HAND, 6000L, 1L, currentGen - 1);

            assertEquals(Game.TaskExecutionClaim.IGNORE, claim);
            assertEquals(6000L, GameScheduling.getDeadline(deadlines, ScheduledGameTask.NEW_HAND));
        }

        @Test
        @DisplayName("claimTaskExecution on game over clears non-cleanup task and returns CANCEL_GAME_OVER")
        void claimTaskExecution_WhenGameOverAndNonCleanup_ClearsAndReturnsCancelGameOver() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.AUTO_ADVANCE);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, true, 1L, ScheduledGameTask.AUTO_ADVANCE, 5000L, 1L, gen);

            assertEquals(Game.TaskExecutionClaim.CANCEL_GAME_OVER, claim);
            assertNull(GameScheduling.getDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertEquals(gen + 1, GameScheduling.getGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
        }

        @Test
        @DisplayName("claimTaskExecution on game over allows CLEANUP to EXECUTE without clearing")
        void claimTaskExecution_WhenGameOverAndCleanup_ReturnsExecuteAndRetainsDeadline() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.CLEANUP);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, true, 1L, ScheduledGameTask.CLEANUP, 5000L, 1L, gen);

            assertEquals(Game.TaskExecutionClaim.EXECUTE, claim);
            assertEquals(5000L, GameScheduling.getDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(gen, GameScheduling.getGeneration(generations, ScheduledGameTask.CLEANUP));
        }

        @Test
        @DisplayName("claimTaskExecution returns IGNORE when deadline is null or mismatched")
        void claimTaskExecution_WhenDeadlineToExecuteMismatched_ReturnsIgnore() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND);

            Game.TaskExecutionClaim claimMismatched = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, false, 1L, ScheduledGameTask.NEW_HAND, 4000L, 1L, gen);
            assertEquals(Game.TaskExecutionClaim.IGNORE, claimMismatched);

            Game.TaskExecutionClaim claimUnscheduled = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, false, 1L, ScheduledGameTask.READY_OPEN, 5000L, 1L, 0L);
            assertEquals(Game.TaskExecutionClaim.IGNORE, claimUnscheduled);
        }

        @Test
        @DisplayName("claimTaskExecution successfully claims EXECUTE and clears non-cleanup task")
        void claimTaskExecution_Success_ClearsNonCleanupTask() {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);
            long gen = GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND);

            Game.TaskExecutionClaim claim = GameScheduling.claimTaskExecution(
                    deadlines, generations, false, false, 1L, ScheduledGameTask.NEW_HAND, 5000L, 1L, gen);

            assertEquals(Game.TaskExecutionClaim.EXECUTE, claim);
            assertNull(GameScheduling.getDeadline(deadlines, ScheduledGameTask.NEW_HAND));
            assertEquals(gen + 1, GameScheduling.getGeneration(generations, ScheduledGameTask.NEW_HAND));
        }

        @Test
        @DisplayName("isExpired returns true only when cleanup deadline is reached or past")
        void isExpired_EvaluatesCleanupDeadlineCorrectly() {
            assertFalse(GameScheduling.isExpired(deadlines, 1000L));

            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 5000L);
            assertFalse(GameScheduling.isExpired(deadlines, 4999L));
            assertTrue(GameScheduling.isExpired(deadlines, 5000L));
            assertTrue(GameScheduling.isExpired(deadlines, 5001L));
        }
    }

}

