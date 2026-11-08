package com.pokergame.model;

import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ReadyGateState;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial empirical stress tests for {@link ReadyGate}, {@link GameScheduling},
 * and their integration with {@link Game}.
 *
 * Authored by reviewer/critic to verify edge cases, adversarial inputs,
 * state isolation, and invariant preservation.
 */
@Tag("unit")
@DisplayName("ReadyGate & GameScheduling Empirical Adversarial Challenge")
class ReadyGateAndGameSchedulingEmpiricalChallengeTest {

    private Player player(String id, String name, int chips) {
        return new Player(name, id, chips);
    }

    private Player player(String id, String name, int chips, boolean isOut, boolean isDisconnected, boolean isReady) {
        Player p = new Player(name, id, chips);
        if (isOut) {
            p.setIsOut();
        }
        p.setDisconnected(isDisconnected);
        p.setReadyForNextHand(isReady);
        return p;
    }

    // =========================================================================
    // 1. ReadyGate Adversarial Stress Tests
    // =========================================================================

    @Test
    @DisplayName("Challenge 1: 10-player table with varied disqualifications correctly computes eligibility")
    void challenge_TenPlayerVariedStates_EligibilityComputedAccurately() {
        List<Player> table = new ArrayList<>();
        // p1..p3: fully eligible
        table.add(player("p1", "Alice", 1000));
        table.add(player("p2", "Bob", 500));
        table.add(player("p3", "Charlie", 250));
        // p4..p5: eliminated (isOut)
        table.add(player("p4", "Out1", 0, true, false, false));
        table.add(player("p5", "Out2", 100, true, false, false));
        // p6..p7: disconnected
        table.add(player("p6", "Dc1", 300, false, true, false));
        table.add(player("p7", "Dc2", 0, false, true, false));
        // p8..p9: zero chips
        table.add(player("p8", "Broke1", 0, false, false, false));
        table.add(player("p9", "Broke2", 0, false, false, true));
        // p10: fully eligible
        table.add(player("p10", "Dave", 700));

        List<Player> eligible = ReadyGate.getEligiblePlayers(table);
        assertEquals(4, eligible.size());
        assertEquals(List.of("p1", "p2", "p3", "p10"),
                eligible.stream().map(Player::getPlayerId).toList());
        assertEquals(4, ReadyGate.getReadyEligibleCount(table));

        // When only 3 of 4 are ready
        eligible.get(0).setReadyForNextHand(true);
        eligible.get(1).setReadyForNextHand(true);
        eligible.get(2).setReadyForNextHand(true);
        eligible.get(3).setReadyForNextHand(false);
        assertFalse(ReadyGate.areAllEligiblePlayersReady(table));

        // When 4th becomes ready
        eligible.get(3).setReadyForNextHand(true);
        assertTrue(ReadyGate.areAllEligiblePlayersReady(table));

        // Ineligible player readiness does NOT affect outcome
        table.get(8).setReadyForNextHand(false); // Broke2
        assertTrue(ReadyGate.areAllEligiblePlayersReady(table));
    }

    @Test
    @DisplayName("Challenge 2: Edge boundary - 2 players seated, 1 eliminated/zero-chips cannot start hand")
    void challenge_TwoSeatedOneDisqualified_CannotStart() {
        Player p1 = player("p1", "Alice", 1000, false, false, true);
        Player p2 = player("p2", "Bob", 0, false, false, true); // 0 chips

        List<Player> table = List.of(p1, p2);
        assertEquals(1, ReadyGate.getReadyEligibleCount(table));
        // Must fail the >= 2 players rule
        assertFalse(ReadyGate.areAllEligiblePlayersReady(table));
    }

    @Test
    @DisplayName("Challenge 3: Disconnection dynamically invalidates ready gate even if player was previously ready")
    void challenge_PlayerDisconnectsWhileReady_InvalidatesReadiness() {
        Player p1 = player("p1", "Alice", 1000, false, false, true);
        Player p2 = player("p2", "Bob", 800, false, false, true);
        List<Player> table = List.of(p1, p2);

        assertTrue(ReadyGate.areAllEligiblePlayersReady(table));

        // Bob suddenly disconnects
        p2.setDisconnected(true);

        // Bob is now disqualified, so only Alice is eligible (count = 1 < 2)
        assertEquals(1, ReadyGate.getReadyEligibleCount(table));
        assertFalse(ReadyGate.areAllEligiblePlayersReady(table));
    }

    @Test
    @DisplayName("Challenge 4: forceReadyForEligiblePlayers does not contaminate ineligible players")
    void challenge_ForceReady_DoesNotContaminateIneligiblePlayers() {
        Player p1 = player("p1", "Alice", 1000, false, false, false);
        Player p2 = player("p2", "Bob", 800, false, false, false);
        Player pOut = player("p3", "Out", 0, true, false, false);
        Player pDc = player("p4", "Dc", 500, false, true, false);
        Player pZero = player("p5", "Zero", 0, false, false, false);
        List<Player> table = List.of(p1, p2, pOut, pDc, pZero);

        ReadyGate.forceReadyForEligiblePlayers(table);

        assertTrue(p1.getIsReadyForNextHand());
        assertTrue(p2.getIsReadyForNextHand());
        assertFalse(pOut.getIsReadyForNextHand(), "Eliminated player must NOT be forced ready");
        assertFalse(pDc.getIsReadyForNextHand(), "Disconnected player must NOT be forced ready");
        assertFalse(pZero.getIsReadyForNextHand(), "Zero-chip player must NOT be forced ready");
    }

    @Test
    @DisplayName("Challenge 5: Hand numbering boundary and overflow checks")
    void challenge_HandNumbering_BoundaryCases() {
        assertEquals(1L, ReadyGate.beginHand(0L));
        assertEquals(1L, ReadyGate.beginHand(-1L));
        assertEquals(1L, ReadyGate.beginHand(-999999L));
        assertEquals(2L, ReadyGate.beginHand(1L));
        assertEquals(Long.MAX_VALUE, ReadyGate.beginHand(Long.MAX_VALUE - 1L));
    }

    // =========================================================================
    // 2. GameScheduling Adversarial Stress Tests
    // =========================================================================

    @Test
    @DisplayName("Challenge 6: Invalidate progression tasks strictly preserves CLEANUP across all combinations")
    void challenge_InvalidateProgressionTasks_PreservesCleanupAcrossPermutations() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        // Schedule every single enum value
        long baseDeadline = 10000L;
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            GameScheduling.scheduleTask(deadlines, generations, task, baseDeadline++);
        }

        assertEquals(ScheduledGameTask.values().length, deadlines.size());

        // Invalidate progression tasks
        GameScheduling.invalidateProgressionTasks(deadlines, generations);

        // Only CLEANUP must remain in deadlines
        assertEquals(1, deadlines.size());
        assertTrue(deadlines.containsKey(ScheduledGameTask.CLEANUP));
        assertEquals(10000L + ScheduledGameTask.CLEANUP.ordinal(), deadlines.get(ScheduledGameTask.CLEANUP));

        // CLEANUP generation must still be 1L
        assertEquals(1L, GameScheduling.getScheduledTaskGeneration(generations, ScheduledGameTask.CLEANUP));

        // All OTHER tasks must have been removed from deadlines and had their generation incremented to 2L
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            if (task != ScheduledGameTask.CLEANUP) {
                assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task),
                        "Task " + task + " should have deadline cleared");
                assertEquals(2L, GameScheduling.getScheduledTaskGeneration(generations, task),
                        "Task " + task + " should have generation bumped to 2L");
            }
        }
    }

    @Test
    @DisplayName("Challenge 7: Monotonic generation token survives 1000 rapid lifecycle mutations")
    void challenge_MonotonicGeneration_1000Mutations() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        ScheduledGameTask task = ScheduledGameTask.AUTO_ADVANCE;
        long previousGen = 0L;

        for (int i = 1; i <= 500; i++) {
            // Schedule
            GameScheduling.scheduleTask(deadlines, generations, task, 1000L + i);
            long genAfterSched = GameScheduling.getScheduledTaskGeneration(generations, task);
            assertTrue(genAfterSched > previousGen, "Generation must increase strictly monotonically on schedule");
            previousGen = genAfterSched;

            // Clear
            GameScheduling.clearScheduledTask(deadlines, generations, task);
            long genAfterClear = GameScheduling.getScheduledTaskGeneration(generations, task);
            assertTrue(genAfterClear > previousGen, "Generation must increase strictly monotonically on clear");
            previousGen = genAfterClear;
        }

        assertEquals(1000L, previousGen);
        assertNull(GameScheduling.getScheduledTaskDeadline(deadlines, task));
    }

    @Test
    @DisplayName("Challenge 8: Snapshot isolation under post-snapshot mutations")
    void challenge_SnapshotIsolation_UnderPostSnapshotMutations() {
        Map<ScheduledGameTask, Long> deadlines = new EnumMap<>(ScheduledGameTask.class);
        Map<ScheduledGameTask, Long> generations = new EnumMap<>(ScheduledGameTask.class);

        GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.NEW_HAND, 5000L);

        Map<ScheduledGameTask, Long> snapDeadlines = GameScheduling.getScheduledTaskDeadlinesSnapshot(deadlines);
        Map<ScheduledGameTask, Long> snapGens = GameScheduling.getScheduledTaskGenerationsSnapshot(generations);

        // Perform severe live mutations
        deadlines.clear();
        generations.clear();
        GameScheduling.scheduleTask(deadlines, generations, ScheduledGameTask.CLEANUP, 9999L);

        // Snapshots must be unchanged
        assertEquals(1, snapDeadlines.size());
        assertEquals(5000L, snapDeadlines.get(ScheduledGameTask.NEW_HAND));
        assertEquals(1, snapGens.size());
        assertEquals(1L, snapGens.get(ScheduledGameTask.NEW_HAND));
    }

    // =========================================================================
    // 3. Game Aggregate Root Delegations Integration Tests
    // =========================================================================

    @Test
    @DisplayName("Challenge 9: Game aggregate root delegates ReadyGate operations seamlessly while owning state")
    void challenge_GameAggregateRoot_ReadyGateDelegation() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Game game = new Game("game-adversarial-1", List.of(p1, p2), 10, 20, evaluator);

        assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertEquals(0L, game.getHandNumber());

        // Open countdown
        long deadline = System.currentTimeMillis() + 10_000L;
        game.openReadyCountdown(deadline);

        assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
        assertTrue(game.isReadyCountdownActive());
        assertEquals(deadline, game.getReadyCountdownDeadlineEpochMs());
        assertFalse(p1.getIsReadyForNextHand());
        assertFalse(p2.getIsReadyForNextHand());
        assertFalse(game.areAllReadyEligiblePlayersReady());

        // Force ready
        game.forceReadyForEligiblePlayers();
        assertTrue(p1.getIsReadyForNextHand());
        assertTrue(p2.getIsReadyForNextHand());
        assertTrue(game.areAllReadyEligiblePlayersReady());

        // Begin hand
        long hand1 = game.beginHand();
        assertEquals(1L, hand1);
        assertEquals(1L, game.getHandNumber());
        assertEquals(1L, game.getStateRevision());

        long hand2 = game.beginHand();
        assertEquals(2L, hand2);
        assertEquals(2L, game.getHandNumber());
        assertEquals(1L, game.getStateRevision()); // resets to 1 on beginHand

        // Close countdown
        game.closeReadyCountdown();
        assertEquals(ReadyGateState.CLOSED, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());

        // Wait for players
        game.waitForReadyPlayers();
        assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
        assertFalse(game.isReadyCountdownActive());
        assertNull(game.getReadyCountdownDeadlineEpochMs());
        assertFalse(p1.getIsReadyForNextHand());
        assertFalse(p2.getIsReadyForNextHand());
    }

    @Test
    @DisplayName("Challenge 10: Game aggregate root delegates Scheduling operations seamlessly while owning state")
    void challenge_GameAggregateRoot_SchedulingDelegation() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        HandEvaluatorService evaluator = new HandEvaluatorService();
        Game game = new Game("game-adversarial-2", List.of(p1, p2), 10, 20, evaluator);

        assertEquals(0L, game.getStateRevision());
        assertNull(game.getRetainedTerminalResult());

        // Schedule tasks
        game.scheduleTask(ScheduledGameTask.AUTO_ADVANCE, 1500L);
        game.scheduleTask(ScheduledGameTask.CLEANUP, 60000L);

        assertEquals(1500L, game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(1L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(60000L, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        assertEquals(1L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        // State revision increment
        game.incrementStateRevision();
        assertEquals(1L, game.getStateRevision());
        game.incrementStateRevision();
        assertEquals(2L, game.getStateRevision());

        // Invalidate progression tasks
        game.invalidateProgressionTasks();

        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE));
        assertEquals(2L, game.getScheduledTaskGeneration(ScheduledGameTask.AUTO_ADVANCE));

        // CLEANUP preserved!
        assertEquals(60000L, game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        assertEquals(1L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        // Clear cleanup
        game.clearScheduledTask(ScheduledGameTask.CLEANUP);
        assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.CLEANUP));
        assertEquals(2L, game.getScheduledTaskGeneration(ScheduledGameTask.CLEANUP));

        // Retained terminal result
        GameEndResponse endResponse = new GameEndResponse(
                "GAME_END", "game-adversarial-2", "Alice", 2000, false, "Game Over", null);
        game.setRetainedTerminalResult(endResponse);
        assertSame(endResponse, game.getRetainedTerminalResult());
    }
}
