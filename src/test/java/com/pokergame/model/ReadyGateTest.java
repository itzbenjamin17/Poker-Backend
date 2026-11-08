package com.pokergame.model;

import com.pokergame.enums.ReadyGateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for the package-private {@link ReadyGate} collaborator.
 * Verifies inter-hand eligibility filtering, countdown state transitions,
 * force-ready mutations, and hand numbering progression.
 */
@Tag("unit")
@DisplayName("ReadyGate collaborator")
class ReadyGateTest {

    // =========================================================================
    // Helper Factories
    // =========================================================================

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
    // 1. Eligibility Filtering (getEligiblePlayers / getReadyEligiblePlayers)
    // =========================================================================

    @Nested
    @DisplayName("Eligibility Filtering")
    class EligibilityFilteringTests {

        @Test
        @DisplayName("All active connected players with chips are eligible")
        void getEligiblePlayers_AllActiveWithChips_ReturnsAll() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 800);
            Player p3 = player("p3", "Charlie", 500);

            List<Player> eligible = ReadyGate.getEligiblePlayers(List.of(p1, p2, p3));

            assertEquals(3, eligible.size());
            assertTrue(eligible.containsAll(List.of(p1, p2, p3)));
        }

        @Test
        @DisplayName("Player with isOut == true is excluded from eligibility")
        void getEligiblePlayers_FiltersOutEliminatedPlayers() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 0, true, false, false); // isOut
            Player p3 = player("p3", "Charlie", 500);

            List<Player> eligible = ReadyGate.getEligiblePlayers(List.of(p1, p2, p3));

            assertEquals(2, eligible.size());
            assertTrue(eligible.contains(p1));
            assertTrue(eligible.contains(p3));
            assertFalse(eligible.contains(p2));
        }

        @Test
        @DisplayName("Player with 0 chips is excluded from eligibility even if connected and not out")
        void getEligiblePlayers_FiltersOutZeroChipPlayers() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "BrokeBob", 0, false, false, false); // chips == 0
            Player p3 = player("p3", "Charlie", 500);

            List<Player> eligible = ReadyGate.getEligiblePlayers(List.of(p1, p2, p3));

            assertEquals(2, eligible.size());
            assertFalse(eligible.contains(p2));
        }

        @Test
        @DisplayName("Player with isDisconnected == true is excluded from eligibility")
        void getEligiblePlayers_FiltersOutDisconnectedPlayers() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "DcBob", 800, false, true, false); // disconnected
            Player p3 = player("p3", "Charlie", 500);

            List<Player> eligible = ReadyGate.getEligiblePlayers(List.of(p1, p2, p3));

            assertEquals(2, eligible.size());
            assertFalse(eligible.contains(p2));
        }

        @Test
        @DisplayName("Filters out combined disqualifications (out, zero chips, disconnected)")
        void getEligiblePlayers_FiltersOutCombinedDisqualifications() {
            Player p1 = player("p1", "Alice", 1000);
            Player pOut = player("p2", "Out", 0, true, false, false);
            Player pDc = player("p3", "Dc", 500, false, true, false);
            Player pZero = player("p4", "Zero", 0, false, false, false);
            Player p5 = player("p5", "Eve", 750);

            List<Player> eligible = ReadyGate.getEligiblePlayers(List.of(p1, pOut, pDc, pZero, p5));

            assertEquals(2, eligible.size());
            assertEquals(List.of(p1, p5), eligible);
        }

        @Test
        @DisplayName("Empty player list returns empty eligible list")
        void getEligiblePlayers_EmptyList_ReturnsEmpty() {
            assertTrue(ReadyGate.getEligiblePlayers(List.of()).isEmpty());
        }

        @Test
        @DisplayName("Defensively skips null players without throwing NullPointerException")
        void getEligiblePlayers_HandlesNullElementsDefensively() {
            Player p1 = player("p1", "Alice", 1000);
            List<Player> playersWithNull = new ArrayList<>();
            playersWithNull.add(p1);
            playersWithNull.add(null);

            List<Player> eligible = ReadyGate.getEligiblePlayers(playersWithNull);

            assertEquals(1, eligible.size());
            assertEquals(p1, eligible.getFirst());
        }

        @Test
        @DisplayName("getReadyEligibleCount returns exact size of eligible players")
        void getReadyEligibleCount_MatchesEligiblePlayersSize() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 0);
            Player p3 = player("p3", "Charlie", 500);

            assertEquals(2, ReadyGate.getReadyEligibleCount(List.of(p1, p2, p3)));
        }
    }

    // =========================================================================
    // 2. All Ready Evaluation (areAllReadyEligiblePlayersReady)
    // =========================================================================

    @Nested
    @DisplayName("All Ready Evaluation")
    class AllReadyEvaluationTests {

        @Test
        @DisplayName("Returns false when fewer than 2 eligible players exist, even if the lone player is ready")
        void areAllReadyEligiblePlayersReady_FewerThanTwoEligible_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, true); // ready!
            Player p2 = player("p2", "OutBob", 0, true, false, false);   // out
            Player p3 = player("p3", "DcCharlie", 500, false, true, false); // dc

            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2, p3)));
        }

        @Test
        @DisplayName("Returns false when 0 eligible players exist")
        void areAllReadyEligiblePlayersReady_ZeroEligible_ReturnsFalse() {
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(List.of()));
        }

        @Test
        @DisplayName("Returns true when all 2 eligible players are marked ready")
        void areAllReadyEligiblePlayersReady_TwoEligibleBothReady_ReturnsTrue() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 800, false, false, true);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2)));
        }

        @Test
        @DisplayName("Returns false when 1 of 2 eligible players is not ready")
        void areAllReadyEligiblePlayersReady_TwoEligibleOneNotReady_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 800, false, false, false); // not ready

            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2)));
        }

        @Test
        @DisplayName("Returns true when 3 of 3 eligible players are ready")
        void areAllReadyEligiblePlayersReady_ThreeEligibleAllReady_ReturnsTrue() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 800, false, false, true);
            Player p3 = player("p3", "Charlie", 500, false, false, true);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2, p3)));
        }

        @Test
        @DisplayName("Returns false when 1 of 3 eligible players is not ready")
        void areAllReadyEligiblePlayersReady_ThreeEligibleOneNotReady_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 800, false, false, true);
            Player p3 = player("p3", "Charlie", 500, false, false, false); // not ready

            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2, p3)));
        }

        @Test
        @DisplayName("Excludes zero-chip players from ready evaluation (parity with GameTest:2070)")
        void areAllReadyEligiblePlayersReady_ExcludesZeroChipPlayers() {
            Player p0 = player("p0", "Alice", 1000, false, false, true);
            Player p1 = player("p1", "BrokeBob", 0, false, false, false); // not ready, 0 chips
            Player p2 = player("p2", "Charlie", 500, false, false, true);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p0, p1, p2)));
        }

        @Test
        @DisplayName("Excludes disconnected players from ready evaluation")
        void areAllReadyEligiblePlayersReady_ExcludesDisconnectedPlayers() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "DcBob", 800, false, true, false); // not ready, disconnected
            Player p3 = player("p3", "Charlie", 500, false, false, true);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2, p3)));
        }

        @Test
        @DisplayName("Excludes eliminated players from ready evaluation")
        void areAllReadyEligiblePlayersReady_ExcludesEliminatedPlayers() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "OutBob", 0, true, false, false); // out
            Player p3 = player("p3", "Charlie", 500, false, false, true);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(List.of(p1, p2, p3)));
        }
    }

    // =========================================================================
    // 3. Ready Countdown State Transitions (openCountdown, closeCountdown, waitForPlayers)
    // =========================================================================

    @Nested
    @DisplayName("Countdown State Transitions")
    class CountdownStateTransitionsTests {

        @Test
        @DisplayName("openCountdown activates gate, sets deadline, sets COUNTING_DOWN, and resets all player readiness")
        void openCountdown_SetsStateAndResetsPlayerReadiness() {
            Player p1 = player("p1", "Alice", 1000, false, false, true); // was ready
            Player p2 = player("p2", "Bob", 800, false, false, true);   // was ready
            List<Player> players = List.of(p1, p2);

            long deadline = System.currentTimeMillis() + 15_000L;
            ReadyGate.ReadyGateStateTransition transition = ReadyGate.openCountdown(players, deadline);

            assertTrue(transition.active());
            assertEquals(deadline, transition.deadlineEpochMs());
            assertEquals(ReadyGateState.COUNTING_DOWN, transition.state());

            // All players must have ready flag cleared
            assertFalse(p1.getIsReadyForNextHand());
            assertFalse(p2.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("closeCountdown deactivates gate, clears deadline, and sets CLOSED without altering player flags")
        void closeCountdown_SetsStateClosedAndClearsDeadline() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);

            ReadyGate.ReadyGateStateTransition transition = ReadyGate.closeCountdown();

            assertFalse(transition.active());
            assertNull(transition.deadlineEpochMs());
            assertEquals(ReadyGateState.CLOSED, transition.state());
            // Does not reset player readiness
            assertTrue(p1.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("waitForPlayers sets WAITING_FOR_PLAYERS, clears deadline, and resets all player readiness")
        void waitForPlayers_SetsStateWaitingAndResetsPlayerReadiness() {
            Player p1 = player("p1", "Alice", 1000, false, false, true); // was ready
            Player p2 = player("p2", "Bob", 800, false, false, true);   // was ready
            List<Player> players = List.of(p1, p2);

            ReadyGate.ReadyGateStateTransition transition = ReadyGate.waitForPlayers(players);

            assertFalse(transition.active());
            assertNull(transition.deadlineEpochMs());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, transition.state());

            // All players must have ready flag cleared
            assertFalse(p1.getIsReadyForNextHand());
            assertFalse(p2.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("Transitions handle empty player rosters gracefully")
        void transitions_HandleEmptyRosterGracefully() {
            assertDoesNotThrow(() -> ReadyGate.openCountdown(List.of(), 10000L));
            assertDoesNotThrow(ReadyGate::closeCountdown);
            assertDoesNotThrow(() -> ReadyGate.waitForPlayers(List.of()));
        }
    }

    // =========================================================================
    // 4. Force Ready Behavior (forceReadyForEligiblePlayers)
    // =========================================================================

    @Nested
    @DisplayName("Force Ready Behavior")
    class ForceReadyBehaviorTests {

        @Test
        @DisplayName("forceReady marks only eligible players as ready and leaves ineligible players unready")
        void forceReadyForEligiblePlayers_MarksOnlyEligiblePlayers() {
            Player pEligible1 = player("p1", "Alice", 1000, false, false, false);
            Player pEligible2 = player("p2", "Bob", 800, false, false, false);
            Player pOut = player("p3", "Out", 0, true, false, false);
            Player pDc = player("p4", "Dc", 500, false, true, false);
            Player pZero = player("p5", "Zero", 0, false, false, false);
            List<Player> players = List.of(pEligible1, pEligible2, pOut, pDc, pZero);

            ReadyGate.forceReadyForEligiblePlayers(players);

            // Eligible players marked ready
            assertTrue(pEligible1.getIsReadyForNextHand());
            assertTrue(pEligible2.getIsReadyForNextHand());

            // Ineligible players remain unready
            assertFalse(pOut.getIsReadyForNextHand());
            assertFalse(pDc.getIsReadyForNextHand());
            assertFalse(pZero.getIsReadyForNextHand());

            // Since both eligible players are now ready, gate opens
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("forceReady on table with only 1 eligible player marks that player, but areAllReady is still false")
        void forceReadyForEligiblePlayers_SingleEligiblePlayer_AreAllReadyStillFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            Player p2 = player("p2", "OutBob", 0, true, false, false);
            List<Player> players = List.of(p1, p2);

            ReadyGate.forceReadyForEligiblePlayers(players);

            assertTrue(p1.getIsReadyForNextHand());
            assertFalse(p2.getIsReadyForNextHand());
            // < 2 eligible players rule still holds
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("forceReady handles empty player roster gracefully")
        void forceReadyForEligiblePlayers_EmptyList_DoesNotThrow() {
            assertDoesNotThrow(() -> ReadyGate.forceReadyForEligiblePlayers(List.of()));
        }
    }

    // =========================================================================
    // 5. Hand Number Progression (beginHand)
    // =========================================================================

    @Nested
    @DisplayName("Hand Number Progression")
    class HandNumberProgressionTests {

        @Test
        @DisplayName("beginHand from initial 0 transitions to 1")
        void beginHand_InitialHand_TransitionsFromZeroToOne() {
            assertEquals(1L, ReadyGate.beginHand(0L));
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, 2L, 5L, 42L, 1000L})
        @DisplayName("beginHand increments hand number monotonically")
        void beginHand_SubsequentHands_IncrementsMonotonically(long currentHand) {
            assertEquals(currentHand + 1, ReadyGate.beginHand(currentHand));
        }

        @Test
        @DisplayName("beginHand from negative value resets to 1 defensively")
        void beginHand_NegativeValue_ResetsToOne() {
            assertEquals(1L, ReadyGate.beginHand(-5L));
        }
    }

    // =========================================================================
    // 6. Restore Fallback State Resolution
    // =========================================================================

    @Nested
    @DisplayName("Restore Fallback State Resolution")
    class RestoreFallbackTests {

        @Test
        @DisplayName("resolveInitialState preserves explicit gateState when provided")
        void resolveInitialState_ExplicitState_Preserved() {
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS,
                    ReadyGate.resolveInitialState(ReadyGateState.WAITING_FOR_PLAYERS, true));
            assertEquals(ReadyGateState.CLOSED,
                    ReadyGate.resolveInitialState(ReadyGateState.CLOSED, false));
        }

        @Test
        @DisplayName("resolveInitialState falls back to COUNTING_DOWN or CLOSED when gateState is null")
        void resolveInitialState_NullState_FallbackToCountdownOrClosed() {
            assertEquals(ReadyGateState.COUNTING_DOWN,
                    ReadyGate.resolveInitialState(null, true));
            assertEquals(ReadyGateState.CLOSED,
                    ReadyGate.resolveInitialState(null, false));
        }
    }

    // =========================================================================
    // 7. Adversarial Challenge: Eligibility Edge Cases (0, 1, 2 eligible players)
    // =========================================================================

    @Nested
    @DisplayName("Adversarial: Exact 0, 1, 2 Player Eligibility")
    class AdversarialEligibilityEdgeCases {

        @Test
        @DisplayName("Exactly 0 eligible: empty table yields 0 eligible and areAllReady == false")
        void zeroEligible_EmptyTable() {
            List<Player> players = List.of();
            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.getEligiblePlayers(players).isEmpty());
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 0 eligible: null table yields 0 eligible and areAllReady == false")
        void zeroEligible_NullTable() {
            assertEquals(0, ReadyGate.getReadyEligibleCount(null));
            assertTrue(ReadyGate.getEligiblePlayers(null).isEmpty());
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(null));
        }

        @Test
        @DisplayName("Exactly 0 eligible: all 5 players eliminated (isOut == true)")
        void zeroEligible_AllEliminated() {
            List<Player> players = List.of(
                    player("p1", "Alice", 1000, true, false, true),
                    player("p2", "Bob", 1000, true, false, true),
                    player("p3", "Charlie", 1000, true, false, true),
                    player("p4", "David", 1000, true, false, true),
                    player("p5", "Eve", 1000, true, false, true)
            );
            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 0 eligible: all 4 players disconnected (isDisconnected == true)")
        void zeroEligible_AllDisconnected() {
            List<Player> players = List.of(
                    player("p1", "Alice", 1000, false, true, true),
                    player("p2", "Bob", 1000, false, true, true),
                    player("p3", "Charlie", 1000, false, true, true),
                    player("p4", "David", 1000, false, true, true)
            );
            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 0 eligible: all 4 players have 0 chips")
        void zeroEligible_AllZeroChips() {
            List<Player> players = List.of(
                    player("p1", "Alice", 0, false, false, true),
                    player("p2", "Bob", 0, false, false, true),
                    player("p3", "Charlie", 0, false, false, true),
                    player("p4", "David", 0, false, false, true)
            );
            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 0 eligible: players with negative chips (debt/invalid state)")
        void zeroEligible_NegativeChipsExcluded() {
            Player p1 = player("p1", "Alice", 100);
            p1.setChips(-50);
            Player p2 = player("p2", "Bob", 100);
            p2.setChips(-1);

            List<Player> players = List.of(p1, p2);
            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 0 eligible: mix of out, disconnected, zero chips, negative chips, and nulls")
        void zeroEligible_MixedDisqualifications() {
            Player pNeg = player("p1", "Neg", 100);
            pNeg.setChips(-10);
            List<Player> players = new ArrayList<>();
            players.add(player("p2", "Out", 0, true, false, true));
            players.add(player("p3", "Dc", 1000, false, true, true));
            players.add(player("p4", "Zero", 0, false, false, true));
            players.add(pNeg);
            players.add(null);

            assertEquals(0, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 1 eligible: solo seated player who is ready still returns false (< 2 players invariant)")
        void oneEligible_SoloPlayer_Ready_ReturnsFalse() {
            Player solo = player("p1", "Alice", 1000, false, false, true);
            List<Player> players = List.of(solo);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players),
                    "ReadyGate must enforce minimum of 2 eligible players for a hand to begin");
        }

        @Test
        @DisplayName("Exactly 1 eligible: solo player after forceReady still returns false")
        void oneEligible_SoloPlayer_ForceReady_ReturnsFalse() {
            Player solo = player("p1", "Alice", 1000, false, false, false);
            List<Player> players = List.of(solo);

            ReadyGate.forceReadyForEligiblePlayers(players);
            assertTrue(solo.getIsReadyForNextHand());
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 1 eligible: 4-player table where 3 are eliminated and 1 active player is ready")
        void oneEligible_TournamentWinnerScenario_ReturnsFalse() {
            Player winner = player("p1", "Champion", 4000, false, false, true);
            Player p2 = player("p2", "Out2", 0, true, false, false);
            Player p3 = player("p3", "Out3", 0, true, false, false);
            Player p4 = player("p4", "Out4", 0, true, false, false);
            List<Player> players = List.of(winner, p2, p3, p4);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 1 eligible: 1 connected player, 1 disconnected player with chips, both marked ready")
        void oneEligible_OneConnectedOneDisconnected_ReturnsFalse() {
            Player active = player("p1", "Alice", 1000, false, false, true);
            Player dc = player("p2", "Bob", 1000, false, true, true);
            List<Player> players = List.of(active, dc);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 1 eligible: 1 player with chips, 1 player with zero chips, both marked ready")
        void oneEligible_OneWithChipsOneZeroChips_ReturnsFalse() {
            Player active = player("p1", "Alice", 1000, false, false, true);
            Player zero = player("p2", "Bob", 0, false, false, true);
            List<Player> players = List.of(active, zero);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 2 eligible: heads-up both ready returns true")
        void twoEligible_BothReady_ReturnsTrue() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 2 eligible: heads-up 1 ready 1 unready returns false")
        void twoEligible_OneReadyOneUnready_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, false);
            List<Player> players = List.of(p1, p2);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 2 eligible: heads-up neither ready returns false")
        void twoEligible_NeitherReady_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            Player p2 = player("p2", "Bob", 1000, false, false, false);
            List<Player> players = List.of(p1, p2);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 2 eligible on a 6-player table where both eligible are ready and 4 disqualified are unready")
        void twoEligible_SixPlayerTable_BothEligibleReady_ReturnsTrue() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            Player p3Out = player("p3", "Out", 0, true, false, false);
            Player p4Dc = player("p4", "Dc", 500, false, true, false);
            Player p5Zero = player("p5", "Zero", 0, false, false, false);
            Player p6OutDc = player("p6", "OutDc", 0, true, true, false);

            List<Player> players = List.of(p1, p2, p3Out, p4Dc, p5Zero, p6OutDc);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Exactly 2 eligible on 6-player table: 1 eligible unready returns false even if disqualified players are ready")
        void twoEligible_SixPlayerTable_OneEligibleUnready_ReturnsFalse() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, false); // not ready
            Player p3Out = player("p3", "Out", 0, true, false, true); // ready but out!
            Player p4Dc = player("p4", "Dc", 500, false, true, true);  // ready but dc!

            List<Player> players = List.of(p1, p2, p3Out, p4Dc);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }
    }

    // =========================================================================
    // 8. Adversarial Challenge: Player State Transitions During Countdown
    // =========================================================================

    @Nested
    @DisplayName("Adversarial: Transitions During Countdown")
    class AdversarialTransitionsDuringCountdown {

        @Test
        @DisplayName("Transition: Disconnect during countdown drops eligible count from 2 to 1, gate closes")
        void disconnectDuringCountdown_DropsEligibility_GateCloses() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            // Gate initially ready
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Bob disconnects during countdown
            p2.setDisconnected(true);

            // Gate must now immediately evaluate to false
            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Reconnect during countdown restores eligibility and gate readiness")
        void reconnectDuringCountdown_RestoresReadiness() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, true, true); // was ready, but disconnected
            List<Player> players = List.of(p1, p2);

            // Inactive because p2 is disconnected
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Bob reconnects before countdown expires
            p2.setDisconnected(false);

            // Both are connected and ready -> gate becomes ready
            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Zero chips during countdown (bust/penalty) drops eligibility, gate closes")
        void zeroChipsDuringCountdown_DropsEligibility_GateCloses() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Bob loses chips to 0 during inter-hand transition
            p2.setChips(0);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Rebuy during countdown restores eligibility and gate readiness")
        void rebuyDuringCountdown_RestoresReadiness() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 0, false, false, true); // ready, but 0 chips
            List<Player> players = List.of(p1, p2);

            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Bob completes rebuy
            p2.setChips(1500);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Elimination during countdown closes gate")
        void eliminationDuringCountdown_ClosesGate() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setIsOut();

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Third player reconnects during countdown as unready, invalidating allReady")
        void lateReconnectUnready_InvalidatesAllReady() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            Player p3 = player("p3", "Charlie", 1000, false, true, false); // disconnected, not ready
            List<Player> players = List.of(p1, p2, p3);

            // Initially p1 and p2 are eligible and ready (2 of 2 eligible ready)
            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Charlie reconnects! He is eligible, but not ready
            p3.setDisconnected(false);

            assertEquals(3, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players),
                    "When a 3rd player reconnects unready, all-ready must become false");

            // Charlie now clicks ready
            p3.setReadyForNextHand(true);
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Transition: Rapid flapping connection status cycles gate state deterministically")
        void rapidFlappingConnection_CyclesDeterministically() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            for (int i = 0; i < 200; i++) {
                p2.setDisconnected(true);
                assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

                p2.setDisconnected(false);
                assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
            }
        }

        @Test
        @DisplayName("Transition: Force-ready while 1 player disconnected only marks connected player; reconnect requires manual ready")
        void forceReadyDuringPartialDisconnect_LeavesReconnectingPlayerUnready() {
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            Player p2 = player("p2", "Bob", 1000, false, true, false); // disconnected
            List<Player> players = List.of(p1, p2);

            // Timer expires and force-ready runs
            ReadyGate.forceReadyForEligiblePlayers(players);

            assertTrue(p1.getIsReadyForNextHand(), "Connected player marked ready");
            assertFalse(p2.getIsReadyForNextHand(), "Disconnected player not marked ready");
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players), "Only 1 player ready and 1 eligible");

            // Bob reconnects
            p2.setDisconnected(false);

            // Bob is now eligible, but still not ready
            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            // Force-ready invoked again now that Bob is connected
            ReadyGate.forceReadyForEligiblePlayers(players);
            assertTrue(p2.getIsReadyForNextHand());
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }
    }

    // =========================================================================
    // 9. Adversarial Challenge: Force-Ready Mutations Across Table Configurations
    // =========================================================================

    @Nested
    @DisplayName("Adversarial: Force-Ready Mutations Across Table Configurations")
    class AdversarialForceReadyMutations {

        @Test
        @DisplayName("Force-ready across massive 20-player table with diverse disqualifications")
        void forceReady_MassiveTable_20Players() {
            List<Player> players = new ArrayList<>();
            // 5 active with chips (eligible)
            for (int i = 1; i <= 5; i++) {
                players.add(player("active_" + i, "Active" + i, 1000, false, false, false));
            }
            // 5 disconnected with chips (ineligible)
            for (int i = 1; i <= 5; i++) {
                players.add(player("dc_" + i, "Dc" + i, 1000, false, true, false));
            }
            // 5 eliminated with 0 chips (ineligible)
            for (int i = 1; i <= 5; i++) {
                players.add(player("out_" + i, "Out" + i, 0, true, false, false));
            }
            // 5 active with 0 chips (ineligible)
            for (int i = 1; i <= 5; i++) {
                players.add(player("zero_" + i, "Zero" + i, 0, false, false, false));
            }

            assertEquals(20, players.size());
            assertEquals(5, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            ReadyGate.forceReadyForEligiblePlayers(players);

            // Verify exactly the 5 active players are ready
            for (int i = 0; i < 5; i++) {
                assertTrue(players.get(i).getIsReadyForNextHand(),
                        "Eligible player " + players.get(i).getName() + " must be marked ready");
            }
            // Verify all 15 ineligible players remain unready
            for (int i = 5; i < 20; i++) {
                assertFalse(players.get(i).getIsReadyForNextHand(),
                        "Ineligible player " + players.get(i).getName() + " must NOT be marked ready");
            }

            // Since all 5 eligible players are now ready, areAllReady must be true
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Force-ready on entirely ineligible table changes no player flags")
        void forceReady_EntirelyIneligibleTable() {
            List<Player> players = List.of(
                    player("p1", "Out", 0, true, false, false),
                    player("p2", "Dc", 1000, false, true, false),
                    player("p3", "Zero", 0, false, false, false)
            );

            ReadyGate.forceReadyForEligiblePlayers(players);

            for (Player p : players) {
                assertFalse(p.getIsReadyForNextHand());
            }
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Force-ready is strictly idempotent across repeated calls")
        void forceReady_StrictlyIdempotent() {
            List<Player> players = List.of(
                    player("p1", "Alice", 1000, false, false, false),
                    player("p2", "Bob", 800, false, false, false),
                    player("p3", "Charlie", 0, false, false, false)
            );

            for (int i = 0; i < 10; i++) {
                ReadyGate.forceReadyForEligiblePlayers(players);
                assertTrue(players.get(0).getIsReadyForNextHand());
                assertTrue(players.get(1).getIsReadyForNextHand());
                assertFalse(players.get(2).getIsReadyForNextHand());
                assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
            }
        }

        @Test
        @DisplayName("Force-ready defends against null elements in player list without throwing")
        void forceReady_NullElements_DefensivelyHandled() {
            List<Player> players = new ArrayList<>();
            players.add(null);
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            players.add(p1);
            players.add(null);
            Player p2 = player("p2", "Bob", 800, false, false, false);
            players.add(p2);
            players.add(null);

            assertDoesNotThrow(() -> ReadyGate.forceReadyForEligiblePlayers(players));
            assertTrue(p1.getIsReadyForNextHand());
            assertTrue(p2.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("Combinatorial verification of all 16 state permutations")
        void forceReady_ExhaustiveStatePermutations() {
            // Test all combinations of (isOut, isDisconnected, chips > 0, initial isReady)
            boolean[] booleans = {false, true};

            for (boolean isOut : booleans) {
                for (boolean isDc : booleans) {
                    for (boolean hasChips : booleans) {
                        for (boolean initialReady : booleans) {
                            int chips = hasChips ? 500 : 0;
                            Player p = player("id", "Test", chips, isOut, isDc, initialReady);
                            boolean expectedEligible = !isOut && !isDc && hasChips;

                            List<Player> singleList = List.of(p);
                            assertEquals(expectedEligible ? 1 : 0, ReadyGate.getReadyEligibleCount(singleList),
                                    String.format("Mismatch for state [out=%b, dc=%b, chips=%d, ready=%b]",
                                            isOut, isDc, chips, initialReady));

                            ReadyGate.forceReadyForEligiblePlayers(singleList);

                            if (expectedEligible) {
                                assertTrue(p.getIsReadyForNextHand());
                            } else {
                                // If ineligible, its readiness should remain whatever it was initially
                                assertEquals(initialReady, p.getIsReadyForNextHand());
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 10. Adversarial Challenge: Hand Progression Boundaries & Overflow Resistance
    // =========================================================================

    @Nested
    @DisplayName("Adversarial: Hand Progression Boundaries & Overflow Resistance")
    class AdversarialHandProgressionBoundaries {

        @Test
        @DisplayName("Hand progression handles 32-bit Integer.MAX_VALUE without truncation")
        void beginHand_IntegerMaxBoundary_No32BitTruncation() {
            long intMax = Integer.MAX_VALUE; // 2,147,483,647L
            long nextHand = ReadyGate.beginHand(intMax);

            assertEquals(2_147_483_648L, nextHand,
                    "Hand number must advance past 32-bit Integer.MAX_VALUE without truncation");
        }

        @Test
        @DisplayName("Hand progression handles unsigned 32-bit boundary (4,294,967,295L)")
        void beginHand_Unsigned32BitBoundary_NoTruncation() {
            long boundary = 4_294_967_295L;
            long nextHand = ReadyGate.beginHand(boundary);

            assertEquals(4_294_967_296L, nextHand);
        }

        @Test
        @DisplayName("Hand progression handles high 64-bit values (10 billion hands)")
        void beginHand_TenBillionHands() {
            long tenBillion = 10_000_000_000L;
            assertEquals(10_000_000_001L, ReadyGate.beginHand(tenBillion));
        }

        @Test
        @DisplayName("Hand progression handles Long.MAX_VALUE - 1L safely reaching Long.MAX_VALUE")
        void beginHand_NearLongMax() {
            assertEquals(Long.MAX_VALUE, ReadyGate.beginHand(Long.MAX_VALUE - 1L));
        }

        @Test
        @DisplayName("Hand progression overflow at Long.MAX_VALUE self-heals back to 1 on subsequent hand")
        void beginHand_LongMaxOverflow_SelfHealing() {
            // When at Long.MAX_VALUE, standard 64-bit addition wraps to Long.MIN_VALUE
            long overflowed = ReadyGate.beginHand(Long.MAX_VALUE);
            assertEquals(Long.MIN_VALUE, overflowed);

            // Defensive check (currentHandNumber <= 0 -> 1L) guarantees that if an overflow ever occurred,
            // the very next hand automatically resets safely to 1L rather than corrupting into negative territory
            long healed = ReadyGate.beginHand(overflowed);
            assertEquals(1L, healed, "Hand number must defensively heal back to 1L after any overflow");
        }

        @Test
        @DisplayName("Hand progression strictly handles all negative values defensively returning 1L")
        void beginHand_AllNegativeValues_DefensivelyResetToOne() {
            assertEquals(1L, ReadyGate.beginHand(-1L));
            assertEquals(1L, ReadyGate.beginHand(-100L));
            assertEquals(1L, ReadyGate.beginHand(-1_000_000L));
            assertEquals(1L, ReadyGate.beginHand(Long.MIN_VALUE));
        }

        @Test
        @DisplayName("Hand progression stress test: 500,000 monotonic increments")
        void beginHand_MonotonicIncrementStress() {
            long hand = 0L;
            for (int i = 1; i <= 500_000; i++) {
                hand = ReadyGate.beginHand(hand);
                assertEquals((long) i, hand);
            }
        }
    }
}

