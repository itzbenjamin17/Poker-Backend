package com.pokergame.model;

import com.pokergame.enums.*;
import com.pokergame.dto.response.GameEndResponse;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.service.HandEvaluatorService;
import java.util.*;
import com.pokergame.enums.ReadyGateState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
    // 8. Readiness Eligibility After Player State Changes
    // =========================================================================

    @Nested
    @DisplayName("Readiness eligibility after player state changes")
    class ReadinessEligibilityChanges {

        @Test
        @DisplayName("Disconnect leaves one eligible player and invalidates all-ready")
        void disconnectLeavesTooFewReadyEligiblePlayers() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setDisconnected(true);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("A ready player becomes eligible again on reconnect")
        void reconnectRestoresEligibilityForAnAlreadyReadyPlayer() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, true, true); // was ready, but disconnected
            List<Player> players = List.of(p1, p2);

            // Inactive because p2 is disconnected
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setDisconnected(false);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Losing all chips leaves one eligible player and invalidates all-ready")
        void losingAllChipsLeavesTooFewReadyEligiblePlayers() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setChips(0);

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Restoring chips makes a ready player eligible again")
        void restoringChipsMakesAReadyPlayerEligibleAgain() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 0, false, false, true); // ready, but 0 chips
            List<Player> players = List.of(p1, p2);

            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setChips(1500);

            assertEquals(2, ReadyGate.getReadyEligibleCount(players));
            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("Elimination leaves one eligible player and invalidates all-ready")
        void eliminationLeavesTooFewReadyEligiblePlayers() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);
            List<Player> players = List.of(p1, p2);

            assertTrue(ReadyGate.areAllReadyEligiblePlayersReady(players));

            p2.setIsOut();

            assertEquals(1, ReadyGate.getReadyEligibleCount(players));
            assertFalse(ReadyGate.areAllReadyEligiblePlayersReady(players));
        }

        @Test
        @DisplayName("An unready player reconnecting invalidates all-ready")
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
        @DisplayName("Transition: Force-ready while 1 player disconnected only marks connected player; reconnect requires manual ready")
        void forceReadyDuringPartialDisconnect_LeavesReconnectingPlayerUnready() {
            Player p1 = player("p1", "Alice", 1000, false, false, false);
            Player p2 = player("p2", "Bob", 1000, false, true, false); // disconnected
            List<Player> players = List.of(p1, p2);

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

    }

    @Nested
    @DisplayName("ReadyAndSchedulingEdgeCases")
    class ReadyAndSchedulingEdgeCases {

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

    @Nested
    @DisplayName("ReadySchedulingConcurrency")
    class ReadySchedulingConcurrency {

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

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class AggregateScenarios extends GameScenarioFixture {

        @Test
        void testAreAllReadyEligiblePlayersReadyExcludesZeroChipPlayers() {
                game.openReadyCountdown(System.currentTimeMillis() + 10_000);

                // Set player 1 to 0 chips
                game.getPlayers().get(1).doAction(PlayerAction.ALL_IN, 0, 0);

                // Only player 0 and player 2 have chips > 0
                game.getPlayers().get(0).setReadyForNextHand(true);
                assertFalse(game.areAllReadyEligiblePlayersReady());

                game.getPlayers().get(2).setReadyForNextHand(true);
                // Player 1 has 0 chips, so with player 0 and 2 ready, all eligible should be ready
                assertTrue(game.areAllReadyEligiblePlayersReady());
        }
    }

    // =========================================================================
    // Direct Tests: startOrWait, expireCountdown, normalizeRecoveredCountdown
    // =========================================================================

    @Nested
    @DisplayName("Countdown State Machine Transitions")
    class CountdownStateMachineTransitionTests {

        @Test
        @DisplayName("startOrWait ignores when forfeit is pending")
        void startOrWait_WhenForfeitPending_ReturnsIgnore() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), true, false, false, GamePhase.SHOWDOWN, 50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.IGNORE, transition.outcome());
            assertNull(transition.gateTransition());
        }

        @Test
        @DisplayName("startOrWait ignores when game is over")
        void startOrWait_WhenGameOver_ReturnsIgnore() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), false, true, false, GamePhase.SHOWDOWN, 50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.IGNORE, transition.outcome());
            assertNull(transition.gateTransition());
        }

        @Test
        @DisplayName("startOrWait returns TOURNAMENT_OVER when tournament is over")
        void startOrWait_WhenTournamentOver_ReturnsTournamentOver() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 0);

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), false, false, true, GamePhase.SHOWDOWN, 50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.TOURNAMENT_OVER, transition.outcome());
            assertNull(transition.gateTransition());
        }

        @Test
        @DisplayName("startOrWait ignores when phase is not SHOWDOWN")
        void startOrWait_WhenNotShowdown_ReturnsIgnore() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), false, false, false, GamePhase.TURN, 50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.IGNORE, transition.outcome());
            assertNull(transition.gateTransition());
        }

        @Test
        @DisplayName("startOrWait transitions to WAITING_FOR_PLAYERS when fewer than 2 eligible players exist")
        void startOrWait_WhenFewerThanTwoEligible_TransitionsToWaiting() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 0); // Broke

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), false, false, false, GamePhase.SHOWDOWN, 50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS, transition.outcome());
            assertNotNull(transition.gateTransition());
            assertFalse(transition.gateTransition().active());
            assertNull(transition.gateTransition().deadlineEpochMs());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, transition.gateTransition().state());
            assertFalse(p1.getIsReadyForNextHand(), "Flags must be reset");
        }

        @Test
        @DisplayName("startOrWait opens countdown when at least 2 eligible players and resets ready flags")
        void startOrWait_WhenTwoEligible_OpensCountdown() {
            Player p1 = player("p1", "Alice", 1000, false, false, true);
            Player p2 = player("p2", "Bob", 1000, false, false, true);

            ReadyGate.CountdownStartTransition transition = ReadyGate.startOrWait(
                    List.of(p1, p2), false, false, false, GamePhase.SHOWDOWN, 50_000L);

            // openCountdown resets ready flags to false, so outcome is OPENED
            assertEquals(Game.ReadyCountdownStartOutcome.OPENED, transition.outcome());
            assertNotNull(transition.gateTransition());
            assertTrue(transition.gateTransition().active());
            assertEquals(50_000L, transition.gateTransition().deadlineEpochMs());
            assertEquals(ReadyGateState.COUNTING_DOWN, transition.gateTransition().state());
            assertFalse(p1.getIsReadyForNextHand());
            assertFalse(p2.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("expireCountdown ignores when forfeit is pending, inactive, or stale deadline")
        void expireCountdown_StaleOrInactiveScenarios_ReturnsIgnoreStale() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            // forfeit pending
            assertEquals(Game.ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    ReadyGate.expireCountdown(List.of(p1, p2), true, true, 1000L, 1000L).outcome());

            // inactive
            assertEquals(Game.ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    ReadyGate.expireCountdown(List.of(p1, p2), false, false, 1000L, 1000L).outcome());

            // deadline mismatch
            assertEquals(Game.ReadyCountdownTimeoutOutcome.IGNORE_STALE,
                    ReadyGate.expireCountdown(List.of(p1, p2), false, true, 2000L, 1000L).outcome());
        }

        @Test
        @DisplayName("expireCountdown transitions to WAITING_FOR_PLAYERS when fewer than 2 eligible players")
        void expireCountdown_FewerThanTwoEligible_TransitionsToWaiting() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 0); // Broke

            ReadyGate.CountdownTimeoutTransition transition = ReadyGate.expireCountdown(
                    List.of(p1, p2), false, true, 1000L, 1000L);

            assertEquals(Game.ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, transition.outcome());
            assertNotNull(transition.gateTransition());
            assertFalse(transition.gateTransition().active());
            assertNull(transition.gateTransition().deadlineEpochMs());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, transition.gateTransition().state());
        }

        @Test
        @DisplayName("expireCountdown forces ready for eligible players and returns FORCED_READY")
        void expireCountdown_ValidTimeout_ForcesReady() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 800);
            Player pOut = player("p3", "Charlie", 0, true, false, false);

            ReadyGate.CountdownTimeoutTransition transition = ReadyGate.expireCountdown(
                    List.of(p1, p2, pOut), false, true, 1000L, 1000L);

            assertEquals(Game.ReadyCountdownTimeoutOutcome.FORCED_READY, transition.outcome());
            assertNull(transition.gateTransition());
            assertTrue(p1.getIsReadyForNextHand());
            assertTrue(p2.getIsReadyForNextHand());
            assertFalse(pOut.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("normalizeRecoveredCountdown returns null if inactive or deadline already present")
        void normalizeRecoveredCountdown_NoNormalizationNeeded_ReturnsNull() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            // inactive
            assertNull(ReadyGate.normalizeRecoveredCountdown(List.of(p1, p2), false, null, 5000L));

            // deadline already present
            assertNull(ReadyGate.normalizeRecoveredCountdown(List.of(p1, p2), true, 4000L, 5000L));
        }

        @Test
        @DisplayName("normalizeRecoveredCountdown transitions to WAITING_FOR_PLAYERS if active with null deadline and < 2 eligible")
        void normalizeRecoveredCountdown_FewerThanTwoEligible_TransitionsToWaiting() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 0);

            ReadyGate.ReadyGateStateTransition transition = ReadyGate.normalizeRecoveredCountdown(
                    List.of(p1, p2), true, null, 5000L);

            assertNotNull(transition);
            assertFalse(transition.active());
            assertNull(transition.deadlineEpochMs());
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, transition.state());
        }

        @Test
        @DisplayName("normalizeRecoveredCountdown opens countdown if active with null deadline and >= 2 eligible")
        void normalizeRecoveredCountdown_AtLeastTwoEligible_OpensCountdown() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);

            ReadyGate.ReadyGateStateTransition transition = ReadyGate.normalizeRecoveredCountdown(
                    List.of(p1, p2), true, null, 5000L);

            assertNotNull(transition);
            assertTrue(transition.active());
            assertEquals(5000L, transition.deadlineEpochMs());
            assertEquals(ReadyGateState.COUNTING_DOWN, transition.state());
        }
    }

    // =========================================================================
    // Game Ready Gate Transitions Delegation
    // =========================================================================

    @Nested
    @DisplayName("Game Ready Gate Transitions")
    class GameReadyGateTransitionTests {

        @Test
        @DisplayName("startOrWaitReadyCountdown with < 2 eligible clears READY_TIMEOUT task and waits")
        void startOrWaitReadyCountdown_FewerThanTwoEligible_ClearsTaskAndWaits() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = new Game("test-game", List.of(p1, p2), 10, 20, new HandEvaluatorService());
            game.conductShowdown();
            p2.setDisconnected(true);
            game.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 99_999L);

            Game.ReadyCountdownStartOutcome outcome = game.startOrWaitReadyCountdown(50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.WAITING_FOR_PLAYERS, outcome);
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("startOrWaitReadyCountdown with >= 2 eligible schedules READY_TIMEOUT task and opens")
        void startOrWaitReadyCountdown_TwoEligible_SchedulesTaskAndOpens() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = new Game("test-game", List.of(p1, p2), 10, 20, new HandEvaluatorService());
            game.conductShowdown();

            Game.ReadyCountdownStartOutcome outcome = game.startOrWaitReadyCountdown(50_000L);

            assertEquals(Game.ReadyCountdownStartOutcome.OPENED, outcome);
            assertEquals(ReadyGateState.COUNTING_DOWN, game.getReadyGateState());
            assertTrue(game.isReadyCountdownActive());
            assertEquals(50_000L, game.getReadyCountdownDeadlineEpochMs());
            assertEquals(50_000L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("expireReadyCountdown with < 2 eligible clears READY_TIMEOUT task and waits")
        void expireReadyCountdown_FewerThanTwoEligible_ClearsTaskAndWaits() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = new Game("test-game", List.of(p1, p2), 10, 20, new HandEvaluatorService());
            game.openReadyCountdown(50_000L);
            game.scheduleTask(ScheduledGameTask.READY_TIMEOUT, 50_000L);

            // Disconnect Bob so eligible count becomes 1
            p2.setDisconnected(true);

            Game.ReadyCountdownTimeoutOutcome outcome = game.expireReadyCountdown(50_000L);

            assertEquals(Game.ReadyCountdownTimeoutOutcome.WAITING_FOR_PLAYERS, outcome);
            assertEquals(ReadyGateState.WAITING_FOR_PLAYERS, game.getReadyGateState());
            assertFalse(game.isReadyCountdownActive());
            assertNull(game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("expireReadyCountdown with valid timeout forces eligible players ready")
        void expireReadyCountdown_ValidTimeout_ForcesReady() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = new Game("test-game", List.of(p1, p2), 10, 20, new HandEvaluatorService());
            game.openReadyCountdown(50_000L);

            Game.ReadyCountdownTimeoutOutcome outcome = game.expireReadyCountdown(50_000L);

            assertEquals(Game.ReadyCountdownTimeoutOutcome.FORCED_READY, outcome);
            assertTrue(p1.getIsReadyForNextHand());
            assertTrue(p2.getIsReadyForNextHand());
        }

        @Test
        @DisplayName("normalizeRecoveredReadyCountdown repairs missing deadline when active")
        void normalizeRecoveredReadyCountdown_RepairsMissingDeadline() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = Game.restoreBuilder()
                    .gameId("test-game")
                    .players(List.of(p1, p2))
                    .activePlayerIds(List.of(p1.getPlayerId(), p2.getPlayerId()))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .readyCountdownActive(true)
                    .readyCountdownDeadlineEpochMs(null)
                    .handEvaluator(new HandEvaluatorService())
                    .build();

            boolean normalized = game.normalizeRecoveredReadyCountdown(60_000L);

            assertTrue(normalized);
            assertTrue(game.isReadyCountdownActive());
            assertEquals(60_000L, game.getReadyCountdownDeadlineEpochMs());
            assertEquals(60_000L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }

        @Test
        @DisplayName("normalizeRecoveredReadyCountdown reschedules task if deadline not yet tracked")
        void normalizeRecoveredReadyCountdown_ReschedulesTaskIfDeadlineDiffers() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Game game = Game.restoreBuilder()
                    .gameId("test-game")
                    .players(List.of(p1, p2))
                    .activePlayerIds(List.of(p1.getPlayerId(), p2.getPlayerId()))
                    .smallBlind(10)
                    .bigBlind(20)
                    .currentPhase(GamePhase.SHOWDOWN)
                    .readyCountdownActive(true)
                    .readyCountdownDeadlineEpochMs(70_000L)
                    .handEvaluator(new HandEvaluatorService())
                    .build();

            boolean normalized = game.normalizeRecoveredReadyCountdown(60_000L);

            assertFalse(normalized, "No transition normalization needed since deadline already present");
            assertEquals(70_000L, game.getScheduledTaskDeadline(ScheduledGameTask.READY_TIMEOUT));
        }
    }
}

