package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial empirical stress tests for ReadyGate, GameScheduling, and Game integration.
 * Designed by Forensic Auditor to verify:
 * 1. Monotonicity of task generation tokens under intense cycling
 * 2. Invalidation invariant strictly preserving CLEANUP across repeated invalidations
 * 3. Deep defensive snapshot isolation
 * 4. High-volume mixed player roster eligibility filtering (1000 randomized permutations)
 * 5. Extreme handNumber and stateRevision boundaries
 * 6. Integration delegation contract through Game aggregate root
 */
@Tag("unit")
@DisplayName("ReadyGate and GameScheduling Empirical Challenge")
class ReadyGateAndSchedulingEmpiricalChallengeTest {

    private Player createPlayer(String id, String name, int chips, boolean isOut, boolean isDisconnected, boolean isReady) {
        Player p = new Player(name, id, chips);
        if (isOut) {
            p.setIsOut();
        }
        p.setDisconnected(isDisconnected);
        p.setReadyForNextHand(isReady);
        return p;
    }

    // =========================================================================
    // 1. Invalidation Invariant: CLEANUP Isolation under Stress
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Repeated invalidation cycles never mutate CLEANUP deadline or generation")
    void challenge_InvalidateProgressionTasks_PreservesCleanupAcrossRepeatedCycles() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        long cleanupDeadline = 999_999_999L;
        GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, cleanupDeadline);
        assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));

        // Perform 50 cycles of scheduling progression tasks followed by invalidation
        for (int cycle = 1; cycle <= 50; cycle++) {
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 1000L + cycle);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.READY_TIMEOUT, 2000L + cycle);
            GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 3000L + cycle);

            GameScheduling.invalidateProgressionTasks(deadlines, generations);

            // Verify progression tasks cleared
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.AUTO_ADVANCE));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.READY_TIMEOUT));
            assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.NEW_HAND));

            // CLEANUP MUST remain untouched in deadlines and generations
            assertEquals(cleanupDeadline, GameScheduling.getScheduledTaskDeadline(deadlines, ScheduledGameTask.CLEANUP));
            assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP),
                    "CLEANUP generation was corrupted on cycle " + cycle);
        }

        // AUTO_ADVANCE had 50 schedule calls (+50) and 50 clear calls (+50) -> generation must be 100
        assertEquals(100L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.AUTO_ADVANCE));
    }

    // =========================================================================
    // 2. Monotonic Generation Token Invariant under Rapid Rescheduling
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Monotonic generation token strictly increments by 1 on every schedule and clear")
    void challenge_GenerationTokens_StrictlyMonotonicProgression() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        ScheduledGameTask task = ScheduledGameTask.READY_TIMEOUT;
        long expectedGen = 0L;
        assertEquals(expectedGen, GameScheduling.getScheduledTaskGeneration(generations, task));

        for (int i = 1; i <= 500; i++) {
            GameScheduling.scheduleTask(deadlines, generations, task, 10_000L + i);
            expectedGen++;
            assertEquals(expectedGen, GameScheduling.getScheduledTaskGeneration(generations, task));

            GameScheduling.clearScheduledTask(deadlines, generations, task);
            expectedGen++;
            assertEquals(expectedGen, GameScheduling.getScheduledTaskGeneration(generations, task));
        }

        assertEquals(1000L, GameScheduling.getScheduledTaskGeneration(generations, task));
    }

    // =========================================================================
    // 3. Snapshot Defensive Immutability and Anti-Tampering
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Snapshots cannot be used as a back-channel to tamper with internal state")
    void challenge_SnapshotImmutability_PreventsBackdoorTampering() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE, 5000L);
        GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 60000L);

        Map<ScheduledGameTask, Long> deadlineSnapshot = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
        Map<ScheduledGameTask, Long> generationSnapshot = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

        // Attempt direct modification
        assertThrows(UnsupportedOperationException.class, () -> deadlineSnapshot.put(ScheduledGameTask.READY_TIMEOUT, 1234L));
        assertThrows(UnsupportedOperationException.class, () -> deadlineSnapshot.remove(ScheduledGameTask.AUTO_ADVANCE));
        assertThrows(UnsupportedOperationException.class, () -> deadlineSnapshot.clear());

        assertThrows(UnsupportedOperationException.class, () -> generationSnapshot.put(ScheduledGameTask.READY_TIMEOUT, 999L));
        assertThrows(UnsupportedOperationException.class, () -> generationSnapshot.remove(ScheduledGameTask.AUTO_ADVANCE));
        assertThrows(UnsupportedOperationException.class, () -> generationSnapshot.clear());

        // Subsequent mutations to underlying maps do not reflect in snapshots
        GameScheduling.clearScheduledTask(deadlines, generations, ScheduledGameTask.AUTO_ADVANCE);
        assertEquals(5000L, deadlineSnapshot.get(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(1L, generationSnapshot.get(ScheduledGameTask.AUTO_ADVANCE));
    }

    // =========================================================================
    // 4. ReadyGate Randomized Property Fuzzing (1000 Permutations)
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Fuzzing 1,000 random player rosters preserves exact eligibility rules")
    void challenge_ReadyGate_FuzzingEligibilityAndReadinessRules() {
        Random rng = new Random(42);

        for (int run = 0; run < 1000; run++) {
            int playerCount = rng.nextInt(1, 10);
            List<Player> roster = new ArrayList<>();
            int expectedEligibleCount = 0;
            boolean allExpectedReady = true;

            for (int p = 0; p < playerCount; p++) {
                int chips = rng.nextBoolean() ? rng.nextInt(1, 10000) : 0;
                boolean isOut = rng.nextInt(10) < 2; // 20% out
                boolean isDc = rng.nextInt(10) < 2;  // 20% disconnected
                boolean isReady = rng.nextBoolean();

                Player player = createPlayer("p_" + p, "Player" + p, chips, isOut, isDc, isReady);
                roster.add(player);

                boolean isEligible = (!isOut) && (!isDc) && (chips > 0);
                if (isEligible) {
                    expectedEligibleCount++;
                    if (!isReady) {
                        allExpectedReady = false;
                    }
                }
            }

            // Invariant 1: Eligible count
            assertEquals(expectedEligibleCount, ReadyGate.getReadyEligibleCount(roster));
            List<Player> eligible = ReadyGate.getEligiblePlayers(roster);
            assertEquals(expectedEligibleCount, eligible.size());

            // Invariant 2: areAllReady requires >= 2 eligible AND all ready
            boolean expectedAreAllReady = (expectedEligibleCount >= 2) && allExpectedReady;
            assertEquals(expectedAreAllReady, ReadyGate.areAllEligiblePlayersReady(roster),
                    "Failed on run " + run + " with eligibleCount=" + expectedEligibleCount);

            // Invariant 3: forceReady sets all eligible players ready, but leaves ineligible unready
            ReadyGate.forceReadyForEligiblePlayers(roster);
            for (Player player : roster) {
                boolean isEligible = (!player.getIsOut()) && (!player.getIsDisconnected()) && (player.getChips() > 0);
                if (isEligible) {
                    assertTrue(player.getIsReadyForNextHand());
                }
            }
            if (expectedEligibleCount >= 2) {
                assertTrue(ReadyGate.areAllEligiblePlayersReady(roster));
            } else {
                assertFalse(ReadyGate.areAllEligiblePlayersReady(roster));
            }
        }
    }

    // =========================================================================
    // 5. Hand Number & State Revision Boundary Invariants
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Hand number and state revision boundary transitions")
    void challenge_HandNumberAndStateRevision_BoundaryConditions() {
        // Negative hand numbers defensively reset to 1
        assertEquals(1L, ReadyGate.beginHand(-1000L));
        assertEquals(1L, ReadyGate.beginHand(-1L));
        assertEquals(1L, ReadyGate.beginHand(0L));
        assertEquals(2L, ReadyGate.beginHand(1L));
        assertEquals(Long.MAX_VALUE, ReadyGate.beginHand(Long.MAX_VALUE - 1L));

        // State revision reset is always INITIAL_HAND_REVISION (1L)
        assertEquals(1L, GameScheduling.resetStateRevisionForNewHand());
        assertEquals(1L, GameScheduling.incrementStateRevision(0L));
        assertEquals(2L, GameScheduling.incrementStateRevision(1L));
        assertEquals(Long.MAX_VALUE, GameScheduling.incrementStateRevision(Long.MAX_VALUE - 1L));
    }

    // =========================================================================
    // 6. Game Aggregate Root Delegation Parity
    // =========================================================================

    @Test
    @DisplayName("Adversarial: Game aggregate root maintains faithful delegation without bypassing logic")
    void challenge_GameAggregateRoot_DelegatesCleanly() {
        Player p1 = new Player("Alice", "p1", 1000);
        Player p2 = new Player("Bob", "p2", 800);
        Game game = new Game("adv-delegation-game", List.of(p1, p2), 10, 20, null);

        // Initial state
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
        assertEquals(0L, game.getHandNumber());
        assertEquals(0L, game.getStateRevision());

        // Ready countdown open
        long deadline = 1750000000000L;
        p1.setReadyForNextHand(true);
        p2.setReadyForNextHand(true);
        game.openReadyCountdown(deadline);

        assertTrue(game.isReadyCountdownActive());
        assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());
        assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
        assertFalse(p1.getIsReadyForNextHand());
        assertFalse(p2.getIsReadyForNextHand());
        assertFalse(game.areAllReadyEligiblePlayersReady());

        // Force ready
        game.forceReadyForEligiblePlayers();
        assertTrue(p1.getIsReadyForNextHand());
        assertTrue(p2.getIsReadyForNextHand());
        assertTrue(game.areAllReadyEligiblePlayersReady());

        // Close countdown
        game.closeReadyCountdown();
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
        // Player flags preserved on closeCountdown
        assertTrue(p1.getIsReadyForNextHand());
        assertTrue(p2.getIsReadyForNextHand());

        // Wait for players
        game.waitForReadyPlayers();
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
        assertFalse(p1.getIsReadyForNextHand());
        assertFalse(p2.getIsReadyForNextHand());

        // Begin hand
        long handNum = game.beginHand();
        assertEquals(1L, handNum);
        assertEquals(1L, game.getHandNumber());
        assertEquals(1L, game.getStateRevision());

        // Scheduling delegation
        assertEquals(0L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));
        game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 5000L);
        assertEquals(5000L, game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(1L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));

        game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(2L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));

        // State revision increment delegation
        game.incrementStateRevision();
        assertEquals(2L, game.getStateRevision());

        // Retained terminal result
        assertNull(game.getRetainedTerminalResult());
        GameEndResponse endResp = new GameEndResponse("GAME_END", "adv-delegation-game", "Alice", 1800, false, "Won", null);
        game.setRetainedTerminalResult(endResp);
        assertEquals(endResp, game.getRetainedTerminalResult());
    }
}
