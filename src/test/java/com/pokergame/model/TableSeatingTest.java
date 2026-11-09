package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.pokergame.service.HandEvaluatorService;

import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;

import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for the package-private {@link TableSeating} collaborator.
 * Verifies heads-up and multi-player blinds rules, position rotations with eliminated players,
 * turn navigation skipping folded/all-in players, dealer anchor stability, and player removal seating.
 */
@Tag("unit")
@DisplayName("TableSeating collaborator")
class TableSeatingTest {

    // =========================================================================
    // Helper Factories
    // =========================================================================

    private Player player(String id, String name, int chips) {
        return new Player(name, id, chips);
    }

    private Player player(String id, String name, int chips, boolean isOut, boolean hasFolded, boolean isAllIn) {
        Player p = new Player(name, id, chips);
        if (isOut) {
            p.setIsOut();
        }
        if (hasFolded) {
            p.doAction(PlayerAction.FOLD, 0, 0);
        }
        if (isAllIn) {
            p.doAction(PlayerAction.ALL_IN, 0, 0);
        }
        return p;
    }

    // =========================================================================
    // 1. Heads-Up Blinds Rules (tableSize == 2)
    // =========================================================================

    @Nested
    @DisplayName("Heads-Up Blinds Rules")
    class HeadsUpBlindsTests {

        @Test
        @DisplayName("Dealer at seat 0 is small blind and acts first pre-flop")
        void computePositions_HeadsUp_DealerAtSeatZero_DealerIsSmallBlindAndActsFirst() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(2, 0);

            assertEquals(0, positions.smallBlindPosition(), "In heads-up, dealer is small blind");
            assertEquals(1, positions.bigBlindPosition(), "In heads-up, opponent is big blind");
            assertEquals(0, positions.currentPlayerPosition(), "In heads-up, dealer/small blind acts first pre-flop");
        }

        @Test
        @DisplayName("Dealer at seat 1 is small blind and acts first pre-flop")
        void computePositions_HeadsUp_DealerAtSeatOne_DealerIsSmallBlindAndActsFirst() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(2, 1);

            assertEquals(1, positions.smallBlindPosition(), "In heads-up, dealer at seat 1 is small blind");
            assertEquals(0, positions.bigBlindPosition(), "In heads-up, opponent at seat 0 is big blind");
            assertEquals(1, positions.currentPlayerPosition(), "In heads-up, dealer at seat 1 acts first pre-flop");
        }

        @Test
        @DisplayName("Dealer rotates between seat 0 and seat 1 alternately in heads-up")
        void advancePositions_HeadsUp_AlternatesDealerBetweenHands() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            List<Player> active = List.of(p1, p2);
            List<String> seatOrder = List.of("p1", "p2");

            // Hand 1: starting from p1 as dealer anchor
            TableSeating.PositionRotationResult hand1 = TableSeating.advancePositions(active, seatOrder, "p1");
            assertEquals(1, hand1.dealerPosition(), "Dealer advances from seat 0 to seat 1");
            assertEquals("p2", hand1.dealerAnchorPlayerId());
            assertEquals(1, hand1.smallBlindPosition(), "Dealer at seat 1 is small blind");
            assertEquals(0, hand1.bigBlindPosition(), "Opponent at seat 0 is big blind");
            assertEquals(1, hand1.currentPlayerPosition(), "Dealer at seat 1 acts first pre-flop");

            // Hand 2: starting from p2 as dealer anchor
            TableSeating.PositionRotationResult hand2 = TableSeating.advancePositions(active, seatOrder, "p2");
            assertEquals(0, hand2.dealerPosition(), "Dealer advances from seat 1 back to seat 0");
            assertEquals("p1", hand2.dealerAnchorPlayerId());
            assertEquals(0, hand2.smallBlindPosition(), "Dealer at seat 0 is small blind");
            assertEquals(1, hand2.bigBlindPosition(), "Opponent at seat 1 is big blind");
            assertEquals(0, hand2.currentPlayerPosition(), "Dealer at seat 0 acts first pre-flop");
        }

        @Test
        @DisplayName("Transition from 3 players to heads-up applies heads-up rules immediately")
        void advancePositions_TransitionFromThreePlayersToHeadsUp_AppliesHeadsUpRules() {
            Player p1 = player("p1", "Alice", 1000);
            Player p3 = player("p3", "Charlie", 1000);
            // p2 was eliminated, only p1 and p3 active
            List<Player> active = List.of(p1, p3);
            List<String> seatOrder = List.of("p1", "p2", "p3");

            // Anchor was p3, next active candidate is p1
            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p3");
            assertEquals(0, result.dealerPosition(), "p1 is dealer at active index 0");
            assertEquals("p1", result.dealerAnchorPlayerId());
            assertEquals(0, result.smallBlindPosition(), "Heads-up rules apply: dealer is small blind");
            assertEquals(1, result.bigBlindPosition(), "Opponent is big blind");
            assertEquals(0, result.currentPlayerPosition(), "Dealer acts first pre-flop");
        }
    }

    // =========================================================================
    // 2. 3+ Player Blinds Rules (tableSize >= 3)
    // =========================================================================

    @Nested
    @DisplayName("3+ Player Blinds Rules")
    class ThreePlusPlayerBlindsTests {

        @Test
        @DisplayName("3 players with dealer at seat 0: SB=1, BB=2, UTG=0")
        void computePositions_ThreePlayers_DealerAtSeatZero_SequentialPositions() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(3, 0);

            assertEquals(1, positions.smallBlindPosition());
            assertEquals(2, positions.bigBlindPosition());
            assertEquals(0, positions.currentPlayerPosition(), "In 3-player, dealer is also UTG and acts first");
        }

        @Test
        @DisplayName("3 players with dealer at seat 1: SB=2, BB=0, UTG=1")
        void computePositions_ThreePlayers_DealerAtSeatOne_WrapsCorrectly() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(3, 1);

            assertEquals(2, positions.smallBlindPosition());
            assertEquals(0, positions.bigBlindPosition());
            assertEquals(1, positions.currentPlayerPosition());
        }

        @Test
        @DisplayName("4 players with dealer at seat 0: SB=1, BB=2, UTG=3")
        void computePositions_FourPlayers_DealerAtSeatZero_UTGIsSeatThree() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(4, 0);

            assertEquals(1, positions.smallBlindPosition());
            assertEquals(2, positions.bigBlindPosition());
            assertEquals(3, positions.currentPlayerPosition(), "UTG is seat 3 in 4-player game");
        }

        @Test
        @DisplayName("6 players with dealer at seat 5 wraps clockwise: SB=0, BB=1, UTG=2")
        void computePositions_SixPlayers_DealerAtSeatFive_WrapsClockwise() {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(6, 5);

            assertEquals(0, positions.smallBlindPosition());
            assertEquals(1, positions.bigBlindPosition());
            assertEquals(2, positions.currentPlayerPosition());
        }

        @ParameterizedTest(name = "Table size {0}: dealer={1} -> SB={2}, BB={3}, current={4}")
        @CsvSource({
                "3, 2, 0, 1, 2",
                "4, 2, 3, 0, 1",
                "5, 4, 0, 1, 2",
                "9, 8, 0, 1, 2"
        })
        @DisplayName("Parameterized table size and dealer wrap checks")
        void computePositions_ParameterizedChecks(int tableSize, int dealer, int expectedSb, int expectedBb, int expectedCurrent) {
            TableSeating.TablePositions positions = TableSeating.computePositionsForTable(tableSize, dealer);

            assertEquals(expectedSb, positions.smallBlindPosition());
            assertEquals(expectedBb, positions.bigBlindPosition());
            assertEquals(expectedCurrent, positions.currentPlayerPosition());
        }

        @Test
        @DisplayName("normalizeBlindPositions preserves valid blind indices")
        void normalizeBlindPositions_ValidIndices_PreservesIndices() {
            TableSeating.BlindPositions blinds = TableSeating.normalizeBlindPositions(4, 1, 2);

            assertEquals(1, blinds.smallBlindPosition());
            assertEquals(2, blinds.bigBlindPosition());
        }

        @Test
        @DisplayName("normalizeBlindPositions wraps out-of-bounds indices modulo table size")
        void normalizeBlindPositions_IndicesExceedTableSize_WrapsModulo() {
            TableSeating.BlindPositions blinds = TableSeating.normalizeBlindPositions(3, 4, 5);

            assertEquals(1, blinds.smallBlindPosition(), "4 % 3 == 1");
            assertEquals(2, blinds.bigBlindPosition(), "5 % 3 == 2");
        }

        @Test
        @DisplayName("normalizeBlindPositions handles negative indices via floorMod")
        void normalizeBlindPositions_NegativeIndices_FloorModHandlesCorrectly() {
            TableSeating.BlindPositions blinds = TableSeating.normalizeBlindPositions(3, -1, -2);

            assertEquals(2, blinds.smallBlindPosition(), "floorMod(-1, 3) == 2");
            assertEquals(1, blinds.bigBlindPosition(), "floorMod(-2, 3) == 1");
        }

        @Test
        @DisplayName("normalizeBlindPositions with zero or negative table size returns 0,0 safely")
        void normalizeBlindPositions_ZeroOrNegativeTableSize_ReturnsZeroZero() {
            TableSeating.BlindPositions blindsZero = TableSeating.normalizeBlindPositions(0, 1, 2);
            assertEquals(0, blindsZero.smallBlindPosition());
            assertEquals(0, blindsZero.bigBlindPosition());

            TableSeating.BlindPositions blindsNeg = TableSeating.normalizeBlindPositions(-1, 1, 2);
            assertEquals(0, blindsNeg.smallBlindPosition());
            assertEquals(0, blindsNeg.bigBlindPosition());
        }
    }

    // =========================================================================
    // 3. Position Rotations with Eliminated / Out Players
    // =========================================================================

    @Nested
    @DisplayName("Position Rotations with Eliminated Players")
    class PositionRotationWithEliminatedPlayersTests {

        @Test
        @DisplayName("Standard clockwise advance without eliminations")
        void advancePositions_StandardClockwiseAdvance_NoEliminations() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            Player p3 = player("p3", "Charlie", 1000);
            List<Player> active = List.of(p1, p2, p3);
            List<String> seatOrder = List.of("p1", "p2", "p3");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p1");

            assertEquals(1, result.dealerPosition(), "Dealer moves to index 1 (p2)");
            assertEquals("p2", result.dealerAnchorPlayerId());
            assertEquals(2, result.smallBlindPosition());
            assertEquals(0, result.bigBlindPosition());
            assertEquals(1, result.currentPlayerPosition());
        }

        @Test
        @DisplayName("Skips player who is eliminated (isOut) in seat order")
        void advancePositions_SkipsEliminatedPlayer_NextActiveBecomesDealer() {
            Player p1 = player("p1", "Alice", 1000);
            Player p3 = player("p3", "Charlie", 1000);
            Player p4 = player("p4", "Dave", 1000);
            // activePlayers only contains non-out players
            List<Player> active = List.of(p1, p3, p4);
            List<String> seatOrder = List.of("p1", "p2", "p3", "p4");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p1");

            // Candidate p2 is skipped because not active / isOut; candidate p3 is chosen
            assertEquals(1, result.dealerPosition(), "p3 is at index 1 in activePlayers");
            assertEquals("p3", result.dealerAnchorPlayerId());
        }

        @Test
        @DisplayName("Skips multiple consecutive eliminated players")
        void advancePositions_MultipleEliminatedPlayers_SkipsConsecutiveEliminations() {
            Player p1 = player("p1", "Alice", 1000);
            Player p4 = player("p4", "Dave", 1000);
            Player p5 = player("p5", "Eve", 1000);
            // p2 and p3 eliminated
            List<Player> active = List.of(p1, p4, p5);
            List<String> seatOrder = List.of("p1", "p2", "p3", "p4", "p5");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p1");

            assertEquals(1, result.dealerPosition(), "p4 is at index 1 in activePlayers");
            assertEquals("p4", result.dealerAnchorPlayerId());
        }

        @Test
        @DisplayName("Wraps around seat order boundary skipping eliminated head player")
        void advancePositions_WraparoundAcrossSeatOrderBoundary() {
            Player p2 = player("p2", "Bob", 1000);
            Player p3 = player("p3", "Charlie", 1000);
            // p1 is eliminated. Active = [p2, p3]. Anchor was p3.
            List<Player> active = List.of(p2, p3);
            List<String> seatOrder = List.of("p1", "p2", "p3");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p3");

            // From p3: offset 1 is p1 (eliminated, skipped); offset 2 is p2 (active!)
            assertEquals(0, result.dealerPosition(), "p2 is at active index 0");
            assertEquals("p2", result.dealerAnchorPlayerId());
        }

        @Test
        @DisplayName("Dealer anchor departed in prior hand still anchors next clockwise rotation")
        void advancePositions_DepartedDealerAnchor_AdvancesFromDepartedAnchorCorrectly() {
            Player p2 = player("p2", "Bob", 1000);
            Player p3 = player("p3", "Charlie", 1000);
            // Dealer was p1, but p1 departed. Anchor is still "p1".
            List<Player> active = List.of(p2, p3);
            List<String> seatOrder = List.of("p1", "p2", "p3");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p1");

            assertEquals(0, result.dealerPosition(), "p2 is next active after departed p1");
            assertEquals("p2", result.dealerAnchorPlayerId());
        }

        @Test
        @DisplayName("Empty active players returns safe fallback record")
        void advancePositions_EmptyActivePlayers_ReturnsSafeFallback() {
            TableSeating.PositionRotationResult result = TableSeating.advancePositions(List.of(), List.of("p1"), "p1");

            assertEquals(0, result.dealerPosition());
            assertNull(result.dealerAnchorPlayerId());
            assertEquals(0, result.smallBlindPosition());
            assertEquals(0, result.bigBlindPosition());
            assertEquals(0, result.currentPlayerPosition());
        }

        @Test
        @DisplayName("Dealer anchor not in seat order falls back gracefully to first active player")
        void advancePositions_DealerAnchorNotInSeatOrder_FallsBackGracefully() {
            Player p1 = player("p1", "Alice", 1000);
            Player p2 = player("p2", "Bob", 1000);
            List<Player> active = List.of(p1, p2);
            List<String> seatOrder = List.of("p1", "p2");

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "unknown-id");

            assertEquals(0, result.dealerPosition());
            assertEquals("p1", result.dealerAnchorPlayerId());
        }
    }

    // =========================================================================
    // 4. Next Player Turn Order Skipping Folded and All-in Players
    // =========================================================================

    @Nested
    @DisplayName("Next Player Turn Navigation")
    class NextPlayerTurnOrderTests {

        @Test
        @DisplayName("Standard step advances to immediate next active player")
        void nextPlayer_StandardStep_MovesToNextSeat() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(1, next);
        }

        @Test
        @DisplayName("Skips player who has folded")
        void nextPlayer_SkipsFoldedPlayer() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000, false, true, false); // folded
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(2, next, "Skips folded p1 and selects p2");
        }

        @Test
        @DisplayName("Skips player who is all-in")
        void nextPlayer_SkipsAllInPlayer() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 0, false, false, true); // all-in
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(2, next, "Skips all-in p1 and selects p2");
        }

        @Test
        @DisplayName("Skips consecutive folded and all-in players")
        void nextPlayer_SkipsConsecutiveFoldedAndAllInPlayers() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000, false, true, false); // folded
            Player p2 = player("p2", "Charlie", 0, false, false, true); // all-in
            Player p3 = player("p3", "Dave", 1000, false, true, false); // folded
            Player p4 = player("p4", "Eve", 1000);
            List<Player> active = List.of(p0, p1, p2, p3, p4);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(4, next, "Skips p1, p2, p3 and selects p4");
        }

        @Test
        @DisplayName("Wraps around table boundary")
        void nextPlayer_WrapsAroundTable() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            int next = TableSeating.nextPlayer(active, 2);
            assertEquals(0, next, "Wraps from seat 2 to seat 0");
        }

        @Test
        @DisplayName("Terminates cleanly at original position when all other players are folded or all-in")
        void nextPlayer_AllOtherPlayersFoldedOrAllIn_ReturnsOriginalPosition() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000, false, true, false); // folded
            Player p2 = player("p2", "Charlie", 0, false, false, true); // all-in
            List<Player> active = List.of(p0, p1, p2);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(0, next, "Terminates at original position without looping infinitely");
        }

        @Test
        @DisplayName("All players non-actionable terminates in one loop without hanging")
        void nextPlayer_AllPlayersNonActionable_TerminatesWithoutInfiniteLoop() {
            Player p0 = player("p0", "Alice", 0, false, false, true); // all-in
            Player p1 = player("p1", "Bob", 1000, false, true, false); // folded
            List<Player> active = List.of(p0, p1);

            int next = TableSeating.nextPlayer(active, 0);
            assertEquals(0, next);
        }

        @Test
        @DisplayName("Empty active players returns 0 safely")
        void nextPlayer_EmptyActivePlayers_ReturnsZero() {
            int next = TableSeating.nextPlayer(List.of(), 2);
            assertEquals(0, next);
        }

        @Test
        @DisplayName("Out-of-bounds current position normalizes before advancing")
        void nextPlayer_CurrentPositionOutOfBounds_NormalizesBeforeAdvancing() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            // current = 5 normalizes to 5 % 3 = 2; then advances (2 + 1) % 3 = 0
            int next = TableSeating.nextPlayer(active, 5);
            assertEquals(0, next);
        }
    }

    // =========================================================================
    // 5. Dealer Anchor Stability Across Hand Transitions & Player Removals
    // =========================================================================

    @Nested
    @DisplayName("Dealer Anchor Stability")
    class DealerAnchorStabilityTests {

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor selects immediate seat clockwise after dealer")
        void firstActionableSeatAfterDealerAnchor_StandardClockwise() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);
            List<Player> active = List.of(p0, p1, p2, p3);
            List<String> seatOrder = List.of("p0", "p1", "p2", "p3");

            // Dealer is p3 (seat index 3); post-flop first actor should be p0 (index 0)
            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(active, seatOrder, "p3", 3);
            assertEquals(0, firstActor);
        }

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor skips folded and all-in seats")
        void firstActionableSeatAfterDealerAnchor_SkipsFoldedAndAllInPlayers() {
            Player p0 = player("p0", "Alice", 1000, false, true, false); // folded
            Player p1 = player("p1", "Bob", 0, false, false, true); // all-in
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);
            List<Player> active = List.of(p0, p1, p2, p3);
            List<String> seatOrder = List.of("p0", "p1", "p2", "p3");

            // Dealer is p3. Candidates after p3: p0 (folded, skip), p1 (all-in, skip), p2 (actionable!)
            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(active, seatOrder, "p3", 3);
            assertEquals(2, firstActor);
        }

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor works when dealer departed during pre-flop")
        void firstActionableSeatAfterDealerAnchor_DealerDepartedDuringPreFlop_UsesAnchor() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            // p3 departed; active players = [p0, p1, p2]; anchor remains "p3"
            List<Player> active = List.of(p0, p1, p2);
            List<String> seatOrder = List.of("p0", "p1", "p2", "p3");

            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(active, seatOrder, "p3", 2);
            assertEquals(0, firstActor, "Anchor p3 in seatOrder determines clockwise next is p0");
        }

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor when small blind departed during pre-flop")
        void firstActionableSeatAfterDealerAnchor_SmallBlindDeparted_PostFlopActorStartsAfterDealer() {
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);
            // p0 (SB) departed. Active = [p1, p2, p3]. Dealer anchor is p3.
            List<Player> active = List.of(p1, p2, p3);
            List<String> seatOrder = List.of("p0", "p1", "p2", "p3");

            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(active, seatOrder, "p3", 2);
            assertEquals(0, firstActor, "p1 is at active index 0, first actionable after departed p0");
        }

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor falls back to dealerPosition when anchor not found")
        void firstActionableSeatAfterDealerAnchor_AnchorNotInSeatOrder_FallsBackToDealerPosition() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            List<Player> active = List.of(p0, p1);
            List<String> seatOrder = List.of("p0", "p1");

            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(active, seatOrder, "missing", 0);
            assertEquals(1, firstActor, "Offset from clamped dealerPosition 0 is 1");
        }

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor returns 0 on empty active players")
        void firstActionableSeatAfterDealerAnchor_EmptyActivePlayers_ReturnsZero() {
            int firstActor = TableSeating.firstActionableSeatAfterDealerAnchor(List.of(), List.of("p0"), "p0", 0);
            assertEquals(0, firstActor);
        }
    }

    // =========================================================================
    // 6. Player Removal Seating Reconciliations
    // =========================================================================

    @Nested
    @DisplayName("Player Removal Seating")
    class PlayerRemovalSeatingTests {

        @Test
        @DisplayName("Non-current player removed: preserves the current actor's identity and turn")
        void reconcileSeatingAfterRemoval_NonCurrentPlayerRemoved_PreservesCurrentPlayerIdentity() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);

            // Active list before removal: [p0, p1, p2, p3]. Current actor was p2 (index 2).
            // p0 is removed. Active list becomes: [p1, p2, p3].
            List<Player> activeAfter = new ArrayList<>(List.of(p1, p2, p3));
            Player playerToRemove = p0;
            Player previousCurrent = p2;

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, playerToRemove, previousCurrent,
                    0, "p0", "p1", "p2", 2);

            assertEquals(1, result.currentPlayerPosition(), "p2 shifted from index 2 to index 1; turn must point to 1");
        }

        @Test
        @DisplayName("Current player removed: turn advances to immediate actionable successor")
        void reconcileSeatingAfterRemoval_CurrentPlayerRemoved_AdvancesToImmediateSuccessor() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);

            // Current actor was p1 (index 1). p1 is removed.
            // Active list becomes: [p0, p2, p3]. Successor at index 1 is now p2.
            List<Player> activeAfter = new ArrayList<>(List.of(p0, p2, p3));
            Player playerToRemove = p1;
            Player previousCurrent = p1;

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, playerToRemove, previousCurrent,
                    0, "p0", "p1", "p2", 1);

            assertEquals(1, result.currentPlayerPosition(), "Turn passes to successor at index 1 (p2)");
        }

        @Test
        @DisplayName("Current player removed and successor is folded: skips to next actionable player")
        void reconcileSeatingAfterRemoval_CurrentPlayerRemoved_SuccessorFolded_SkipsToNextActionable() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000, false, true, false); // folded
            Player p3 = player("p3", "Dave", 1000);

            // Current actor was p1. p1 removed. Active becomes [p0, p2, p3].
            // Seat index 1 is p2 (folded). Must skip to seat index 2 (p3).
            List<Player> activeAfter = new ArrayList<>(List.of(p0, p2, p3));
            Player playerToRemove = p1;
            Player previousCurrent = p1;

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, playerToRemove, previousCurrent,
                    0, "p0", "p1", "p2", 1);

            assertEquals(2, result.currentPlayerPosition(), "Successor p2 was folded, skipped to p3 (index 2)");
        }

        @Test
        @DisplayName("Dealer removed: dealerPosition aligns with anchor if active, or stays valid")
        void reconcileSeatingAfterRemoval_DealerRemoved_DealerPositionRetainsValidIndex() {
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p0 = player("p0", "Alice", 1000); // dealer "p0" removed

            List<Player> activeAfter = new ArrayList<>(List.of(p1, p2));

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, p0, p1,
                    0, "p0", "p1", "p2", 0);

            assertTrue(result.dealerPosition() >= 0 && result.dealerPosition() < activeAfter.size());
        }

        @Test
        @DisplayName("Retains small blind and big blind positions aligned with player IDs")
        void reconcileSeatingAfterRemoval_RetainsSmallAndBigBlindPositions() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            Player p3 = player("p3", "Dave", 1000);

            // p0 removed. Active becomes [p1, p2, p3].
            // SB was "p1" (now at index 0); BB was "p2" (now at index 1).
            List<Player> activeAfter = new ArrayList<>(List.of(p1, p2, p3));

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, p0, p1,
                    0, "p0", "p1", "p2", 1);

            assertEquals(0, result.smallBlindPosition(), "SB p1 is now at index 0");
            assertEquals(1, result.bigBlindPosition(), "BB p2 is now at index 1");
        }

        @Test
        @DisplayName("All players removed returns zeroed positions record")
        void reconcileSeatingAfterRemoval_AllPlayersRemoved_ReturnsZeros() {
            Player p0 = player("p0", "Alice", 1000);
            List<Player> activeAfter = List.of();

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    activeAfter, p0, p0,
                    0, "p0", null, null, 0);

            assertEquals(0, result.dealerPosition());
            assertEquals(0, result.smallBlindPosition());
            assertEquals(0, result.bigBlindPosition());
            assertEquals(0, result.currentPlayerPosition());
        }

        @Test
        @DisplayName("Null player to remove returns current positions safely")
        void reconcileSeatingAfterRemoval_NullPlayerToRemove_SafelyHandles() {
            Player p0 = player("p0", "Alice", 1000);
            List<Player> active = List.of(p0);

            TableSeating.RemovalSeatingResult result = TableSeating.reconcileSeatingAfterRemoval(
                    active, null, p0,
                    0, "p0", "p0", "p0", 0);

            assertEquals(0, result.dealerPosition());
            assertEquals(0, result.currentPlayerPosition());
        }
    }

    // =========================================================================
    // 7. Helpers & Index Queries
    // =========================================================================

    @Nested
    @DisplayName("Helpers and Index Queries")
    class HelperAndIndexQueryTests {

        @Test
        @DisplayName("indexOfActivePlayer finds existing player index")
        void indexOfActivePlayer_FindsExistingIndex() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            List<Player> active = List.of(p0, p1);

            assertEquals(0, TableSeating.indexOfActivePlayer(active, "p0"));
            assertEquals(1, TableSeating.indexOfActivePlayer(active, "p1"));
            assertEquals(-1, TableSeating.indexOfActivePlayer(active, "unknown"));
            assertEquals(-1, TableSeating.indexOfActivePlayer(null, "p0"));
            assertEquals(-1, TableSeating.indexOfActivePlayer(active, null));
        }

        @Test
        @DisplayName("normalizeCurrentPlayerPosition handles standard, excess, and negative positions")
        void normalizeCurrentPlayerPosition_BoundsCorrectly() {
            Player p0 = player("p0", "Alice", 1000);
            Player p1 = player("p1", "Bob", 1000);
            Player p2 = player("p2", "Charlie", 1000);
            List<Player> active = List.of(p0, p1, p2);

            assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(active, 0));
            assertEquals(2, TableSeating.normalizeCurrentPlayerPosition(active, 2));
            assertEquals(1, TableSeating.normalizeCurrentPlayerPosition(active, 4));
            assertEquals(2, TableSeating.normalizeCurrentPlayerPosition(active, -1));
            assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(List.of(), 3));
            assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(null, 3));
        }
    }

    @Nested
    @DisplayName("SeatingEdgeCases")
    class SeatingEdgeCases {

        // =========================================================================
        // Helper Factories
        // =========================================================================

        private Player createPlayer(String id, String name, int chips) {
            return new Player(name, id, chips);
        }

        private List<Player> createPlayerList(int size) {
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                players.add(createPlayer("p" + i, "Player_" + i, 1000));
            }
            return players;
        }

        // =========================================================================
        // 1. Exhaustive Table Sizes [2..10] and All Dealer Positions
        // =========================================================================

        @Test
        @DisplayName("Exhaustive verification of computePositionsForTable for all table sizes 2..10 and all dealer indices")
        void challenge_ExhaustiveTableSizes2to10_AllDealerPositions_InvariantsHold() {
            for (int tableSize = 2; tableSize <= 10; tableSize++) {
                for (int dealer = 0; dealer < tableSize; dealer++) {
                    TableSeating.TablePositions positions = TableSeating.computePositionsForTable(tableSize, dealer);

                    int sb = positions.smallBlindPosition();
                    int bb = positions.bigBlindPosition();
                    int current = positions.currentPlayerPosition();

                    // Bounds invariant
                    assertTrue(sb >= 0 && sb < tableSize, "SB out of bounds for size=" + tableSize + ", dealer=" + dealer);
                    assertTrue(bb >= 0 && bb < tableSize, "BB out of bounds for size=" + tableSize + ", dealer=" + dealer);
                    assertTrue(current >= 0 && current < tableSize, "Current out of bounds for size=" + tableSize + ", dealer=" + dealer);

                    if (tableSize == 2) {
                        // Heads-up invariants:
                        // 1. Dealer is small blind
                        assertEquals(dealer, sb, "In heads-up, dealer must be small blind");
                        // 2. Opponent is big blind
                        assertEquals((dealer + 1) % 2, bb, "In heads-up, opponent must be big blind");
                        // 3. Dealer acts first pre-flop
                        assertEquals(dealer, current, "In heads-up, dealer/small blind acts first pre-flop");
                        // SB != BB
                        assertNotEquals(sb, bb, "In heads-up, SB and BB must be distinct seats");
                    } else {
                        // Multi-way (3+) invariants:
                        // 1. SB is immediately clockwise from dealer
                        assertEquals((dealer + 1) % tableSize, sb, "SB must be (dealer + 1) % size");
                        // 2. BB is immediately clockwise from SB
                        assertEquals((sb + 1) % tableSize, bb, "BB must be (SB + 1) % size");
                        // 3. UTG (first to act) is immediately clockwise from BB
                        assertEquals((bb + 1) % tableSize, current, "Current/UTG must be (BB + 1) % size");

                        // Distinctness: SB and BB can never be the same seat when tableSize >= 3
                        assertNotEquals(sb, bb, "SB and BB must be distinct seats");
                        assertNotEquals(bb, current, "BB and current/UTG must be distinct seats");

                        if (tableSize >= 4) {
                            // For 4+ players, dealer, SB, BB, and UTG are 4 distinct seats
                            Set<Integer> distinctSeats = Set.of(dealer, sb, bb, current);
                            assertEquals(4, distinctSeats.size(), "Dealer, SB, BB, and current must all be distinct for size >= 4");
                        } else {
                            // For 3 players, UTG is the dealer
                            assertEquals(dealer, current, "In 3-player game, dealer is also UTG");
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. Cyclic Dealer Advancement Across N Consecutive Hands
        // =========================================================================

        @ParameterizedTest(name = "Cyclic advancement for table size {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("Dealer rotates systematically clockwise through all seats over N hands without skips or stalls")
        void challenge_CyclicAdvancement_AllTableSizes_EveryPlayerReceivesButtonOncePerCycle(int tableSize) {
            List<Player> active = createPlayerList(tableSize);
            List<String> seatOrder = active.stream().map(Player::getPlayerId).toList();

            // Test starting from each possible player as the initial dealer anchor
            for (int startSeat = 0; startSeat < tableSize; startSeat++) {
                String currentAnchor = "p" + startSeat;
                Set<Integer> dealerPositionsVisited = new HashSet<>();

                for (int hand = 1; hand <= tableSize; hand++) {
                    TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, currentAnchor);

                    int expectedDealer = (startSeat + hand) % tableSize;
                    String expectedAnchor = "p" + expectedDealer;

                    assertEquals(expectedDealer, result.dealerPosition(),
                            "Hand " + hand + " for size " + tableSize + " failed dealer position expectation");
                    assertEquals(expectedAnchor, result.dealerAnchorPlayerId(),
                            "Hand " + hand + " for size " + tableSize + " failed anchor ID expectation");

                    dealerPositionsVisited.add(result.dealerPosition());

                    // Verify blind and current player invariants for this hand
                    TableSeating.TablePositions expectedPositions = TableSeating.computePositionsForTable(tableSize, expectedDealer);
                    assertEquals(expectedPositions.smallBlindPosition(), result.smallBlindPosition());
                    assertEquals(expectedPositions.bigBlindPosition(), result.bigBlindPosition());
                    assertEquals(expectedPositions.currentPlayerPosition(), result.currentPlayerPosition());

                    // Prepare next hand
                    currentAnchor = result.dealerAnchorPlayerId();
                }

                // Every seat was dealer exactly once in the N-hand cycle
                assertEquals(tableSize, dealerPositionsVisited.size(),
                        "Every seat must be dealer exactly once in an N-hand cycle for size=" + tableSize);
                // Anchor returned to original starting player
                assertEquals("p" + startSeat, currentAnchor,
                        "After N hands, dealer anchor must return to starting seat");
            }
        }

        // =========================================================================
        // 3. Successive Eliminations Down to Heads-Up and Single Survivor
        // =========================================================================

        @Test
        @DisplayName("Systematic elimination from 10 players down to 1 survivor maintains seating invariants")
        void challenge_SuccessiveEliminations_From10DownTo1_InvariantsPreservedAtEveryStep() {
            // Table of 10 players: p0 to p9
            List<Player> allPlayers = createPlayerList(10);
            List<String> originalSeatOrder = allPlayers.stream().map(Player::getPlayerId).toList();

            List<Player> active = new ArrayList<>(allPlayers);
            String currentAnchor = "p0";

            // Eliminate one player per round until 1 remains
            // Test eliminating players at different relative positions (e.g., current dealer, SB, BB, or non-blind)
            int[] eliminationIndices = {2, 5, 0, 3, 1, 0, 1, 0}; // 8 eliminations: 10 -> 2 players

            for (int step = 0; step < eliminationIndices.length; step++) {
                int currentTableSize = active.size();
                int elimIndex = eliminationIndices[step] % currentTableSize;
                Player eliminated = active.get(elimIndex);
                eliminated.setIsOut();
                active.remove(elimIndex);

                int remainingSize = active.size();
                assertEquals(currentTableSize - 1, remainingSize);

                TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, originalSeatOrder, currentAnchor);

                // Invariant 1: Dealer must belong to an active player
                assertTrue(result.dealerPosition() >= 0 && result.dealerPosition() < remainingSize);
                Player dealerPlayer = active.get(result.dealerPosition());
                assertEquals(dealerPlayer.getPlayerId(), result.dealerAnchorPlayerId());
                assertFalse(dealerPlayer.getIsOut(), "Eliminated player was selected as dealer");

                // Invariant 2: Blind and current position rules
                if (remainingSize == 2) {
                    // Heads-up rules must immediately kick in
                    assertEquals(result.dealerPosition(), result.smallBlindPosition(), "In heads-up, dealer is SB");
                    assertEquals((result.dealerPosition() + 1) % 2, result.bigBlindPosition(), "In heads-up, opponent is BB");
                    assertEquals(result.dealerPosition(), result.currentPlayerPosition(), "In heads-up, dealer acts first pre-flop");
                } else if (remainingSize >= 3) {
                    // Multi-way rules
                    assertEquals((result.dealerPosition() + 1) % remainingSize, result.smallBlindPosition());
                    assertEquals((result.smallBlindPosition() + 1) % remainingSize, result.bigBlindPosition());
                    assertEquals((result.bigBlindPosition() + 1) % remainingSize, result.currentPlayerPosition());
                }

                currentAnchor = result.dealerAnchorPlayerId();
            }

            // Final transition: heads-up (2 players) down to single survivor (1 player)
            assertEquals(2, active.size(), "Table must now be heads-up");
            Player secondPlace = active.remove(1);
            secondPlace.setIsOut();
            assertEquals(1, active.size(), "Table now has 1 survivor");

            // advancePositions with 1 survivor must not crash, throw arithmetic exception, or loop infinitely
            TableSeating.PositionRotationResult survivorResult = TableSeating.advancePositions(active, originalSeatOrder, currentAnchor);
            assertNotNull(survivorResult);
            assertEquals(0, survivorResult.dealerPosition());
            assertEquals(active.getFirst().getPlayerId(), survivorResult.dealerAnchorPlayerId());

            // advancePositions with 0 players returns safe fallback
            TableSeating.PositionRotationResult emptyResult = TableSeating.advancePositions(List.of(), originalSeatOrder, currentAnchor);
            assertNotNull(emptyResult);
            assertEquals(0, emptyResult.dealerPosition());
            assertNull(emptyResult.dealerAnchorPlayerId());
        }

        // =========================================================================
        // 4. Departed Dealer Anchor Shift & Robustness
        // =========================================================================

        @Test
        @DisplayName("Dealer anchor departed in prior hand correctly anchors clockwise search from its old seat")
        void challenge_DepartedDealerAnchor_MultipleConsecutiveDepartures_ClocksClockwiseCorrectly() {
            // Seat order: p0, p1, p2, p3, p4, p5, p6, p7
            List<Player> allPlayers = createPlayerList(8);
            List<String> seatOrder = allPlayers.stream().map(Player::getPlayerId).toList();

            // Suppose p2 was the dealer anchor.
            // During the hand, p2, p3, and p4 all bust and leave!
            // Active remaining players: p0, p1, p5, p6, p7.
            List<Player> active = new ArrayList<>(List.of(allPlayers.get(0), allPlayers.get(1), allPlayers.get(5), allPlayers.get(6), allPlayers.get(7)));

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p2");

            // The search from departed anchor p2:
            // offset 1: p3 (out)
            // offset 2: p4 (out)
            // offset 3: p5 (active! index 2 in active list: [p0, p1, p5, p6, p7])
            assertEquals("p5", result.dealerAnchorPlayerId(), "Dealer must advance to p5 past departed p2, p3, p4");
            assertEquals(2, result.dealerPosition(), "p5 is at index 2 in active list");

            // In 5-player table, check blinds:
            // dealer = 2 (p5)
            // SB = (2 + 1) % 5 = 3 (p6)
            // BB = (3 + 1) % 5 = 4 (p7)
            // UTG = (4 + 1) % 5 = 0 (p0)
            assertEquals(3, result.smallBlindPosition());
            assertEquals(4, result.bigBlindPosition());
            assertEquals(0, result.currentPlayerPosition());
        }

        @Test
        @DisplayName("Departed dealer anchor wraps around seat order boundary to find head player")
        void challenge_DepartedDealerAnchor_WrapsAroundTailToHead() {
            // Seat order: p0, p1, p2, p3
            List<Player> all = createPlayerList(4);
            List<String> seatOrder = all.stream().map(Player::getPlayerId).toList();

            // p3 was anchor and departed. p0 also departed.
            // Active: p1, p2. (Heads-up!)
            List<Player> active = List.of(all.get(1), all.get(2));

            TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p3");

            // From p3:
            // offset 1: p0 (departed)
            // offset 2: p1 (active! index 0 in active list [p1, p2])
            assertEquals("p1", result.dealerAnchorPlayerId());
            assertEquals(0, result.dealerPosition());

            // Heads-up invariants:
            assertEquals(0, result.smallBlindPosition(), "Dealer p1 is SB in heads-up");
            assertEquals(1, result.bigBlindPosition(), "p2 is BB in heads-up");
            assertEquals(0, result.currentPlayerPosition(), "Dealer p1 acts first pre-flop in heads-up");
        }

        @Test
        @DisplayName("Unknown or null dealerAnchorPlayerId falls back gracefully without exception")
        void challenge_UnknownOrNullDealerAnchor_FallsBackGracefully() {
            List<Player> active = createPlayerList(3);
            List<String> seatOrder = active.stream().map(Player::getPlayerId).toList();

            // Null anchor
            TableSeating.PositionRotationResult nullAnchor = TableSeating.advancePositions(active, seatOrder, null);
            assertNotNull(nullAnchor);
            assertEquals("p0", nullAnchor.dealerAnchorPlayerId());
            assertEquals(0, nullAnchor.dealerPosition());

            // Unknown anchor not in seat order
            TableSeating.PositionRotationResult unknownAnchor = TableSeating.advancePositions(active, seatOrder, "ghost-id");
            assertNotNull(unknownAnchor);
            assertEquals("p0", unknownAnchor.dealerAnchorPlayerId());
            assertEquals(0, unknownAnchor.dealerPosition());
        }

        // =========================================================================
        // 5. Post-Flop Actionable Seat After Dealer Anchor
        // =========================================================================

        @Test
        @DisplayName("firstActionableSeatAfterDealerAnchor exhaustively finds first non-folded, non-all-in seat clockwise")
        void challenge_FirstActionableSeatAfterDealerAnchor_ExhaustiveCombinations() {
            for (int tableSize = 2; tableSize <= 6; tableSize++) {
                List<String> seatOrder = new ArrayList<>();
                for (int i = 0; i < tableSize; i++) {
                    seatOrder.add("p" + i);
                }

                for (int dealer = 0; dealer < tableSize; dealer++) {
                    String dealerAnchor = "p" + dealer;

                    // Test state bitmasks: each player can be actionable (0), folded (1), or all-in (2)
                    // For table sizes, test several critical state configurations
                    // 1. All players actionable: first actionable must be (dealer + 1) % tableSize
                    List<Player> allActionable = createPlayerList(tableSize);
                    int firstSeat = TableSeating.firstActionableSeatAfterDealerAnchor(allActionable, seatOrder, dealerAnchor, dealer);
                    assertEquals((dealer + 1) % tableSize, firstSeat);

                    // 2. Next seat is folded, next-next is actionable
                    List<Player> oneFolded = createPlayerList(tableSize);
                    int foldedSeat = (dealer + 1) % tableSize;
                    oneFolded.get(foldedSeat).doAction(PlayerAction.FOLD, 0, 0);

                    int expectedNextActionable = (dealer + 2) % tableSize;
                    int actualNextActionable = TableSeating.firstActionableSeatAfterDealerAnchor(oneFolded, seatOrder, dealerAnchor, dealer);
                    assertEquals(expectedNextActionable, actualNextActionable);

                    // 3. Next seat is all-in, next-next is actionable
                    List<Player> oneAllIn = createPlayerList(tableSize);
                    int allInSeat = (dealer + 1) % tableSize;
                    oneAllIn.get(allInSeat).doAction(PlayerAction.ALL_IN, 0, 0);

                    int actualAfterAllIn = TableSeating.firstActionableSeatAfterDealerAnchor(oneAllIn, seatOrder, dealerAnchor, dealer);
                    assertEquals(expectedNextActionable, actualAfterAllIn);

                    // 4. All players except dealer are folded/all-in
                    List<Player> onlyDealerActionable = createPlayerList(tableSize);
                    for (int i = 0; i < tableSize; i++) {
                        if (i != dealer) {
                            onlyDealerActionable.get(i).doAction(PlayerAction.FOLD, 0, 0);
                        }
                    }
                    int actualOnlyDealer = TableSeating.firstActionableSeatAfterDealerAnchor(onlyDealerActionable, seatOrder, dealerAnchor, dealer);
                    assertEquals(dealer, actualOnlyDealer, "Clockwise loop returns dealer when all others folded");

                    // 5. ALL players folded/all-in terminates at 0 fallback
                    List<Player> allFolded = createPlayerList(tableSize);
                    for (Player p : allFolded) {
                        p.doAction(PlayerAction.FOLD, 0, 0);
                    }
                    int fallback = TableSeating.firstActionableSeatAfterDealerAnchor(allFolded, seatOrder, dealerAnchor, dealer);
                    assertEquals(0, fallback);
                }
            }
        }

        // =========================================================================
        // 6. Next Player Turn Navigation Stress
        // =========================================================================

        @Test
        @DisplayName("nextPlayer correctly navigates across all combinations of folded and all-in players without infinite loops")
        void challenge_NextPlayer_ExhaustiveFoldedAllInBitmasks_NeverHangsAndFindsNextActionable() {
            for (int tableSize = 2; tableSize <= 6; tableSize++) {
                int totalStates = (int) Math.pow(3, tableSize); // 3 states: 0=active, 1=folded, 2=all-in
                // Sample representative states or up to 243 states for small tables
                int sampleStep = tableSize <= 4 ? 1 : 3;

                for (int stateMask = 0; stateMask < totalStates; stateMask += sampleStep) {
                    int temp = stateMask;
                    List<Player> players = new ArrayList<>();
                    boolean[] actionable = new boolean[tableSize];

                    for (int i = 0; i < tableSize; i++) {
                        int mode = temp % 3;
                        temp /= 3;
                        Player p = createPlayer("p" + i, "Player_" + i, mode == 2 ? 0 : 1000);
                        if (mode == 1) {
                            p.doAction(PlayerAction.FOLD, 0, 0);
                            actionable[i] = false;
                        } else if (mode == 2) {
                            p.doAction(PlayerAction.ALL_IN, 0, 0);
                            actionable[i] = false;
                        } else {
                            actionable[i] = true;
                        }
                        players.add(p);
                    }

                    for (int current = 0; current < tableSize; current++) {
                        int next = TableSeating.nextPlayer(players, current);

                        assertTrue(next >= 0 && next < tableSize, "nextPlayer out of bounds");

                        // Check if there is ANY actionable player other than current
                        boolean hasOtherActionable = false;
                        for (int i = 0; i < tableSize; i++) {
                            if (i != current && actionable[i]) {
                                hasOtherActionable = true;
                                break;
                            }
                        }

                        if (hasOtherActionable) {
                            // next MUST be actionable and NOT equal to current
                            assertTrue(actionable[next], "nextPlayer returned non-actionable seat when actionable seats exist");
                            // Must be the first actionable seat clockwise from current
                            int expected = (current + 1) % tableSize;
                            while (!actionable[expected]) {
                                expected = (expected + 1) % tableSize;
                            }
                            assertEquals(expected, next, "nextPlayer did not find immediate clockwise actionable seat");
                        } else {
                            // When no other player is actionable, nextPlayer must return original position or current
                            // without hanging in an infinite loop
                            assertTrue(next == current || !actionable[next]);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("nextPlayer handles extreme out-of-bounds indices and empty lists gracefully")
        void challenge_NextPlayer_ExtremeIndicesAndEmptyLists() {
            List<Player> active = createPlayerList(3);

            // Positive extreme: 1000 % 3 = 1 -> next is (1 + 1) % 3 = 2
            assertEquals(2, TableSeating.nextPlayer(active, 1000));

            // Negative extreme
            // Math.floorMod(-1, 3) = 2 -> next is (2 + 1) % 3 = 0
            assertEquals(0, TableSeating.nextPlayer(active, -1));

            // Empty and null active list returns 0
            assertEquals(0, TableSeating.nextPlayer(List.of(), 5));
            assertEquals(0, TableSeating.nextPlayer(null, 5));
        }

        // =========================================================================
        // 7. Player Removal Seating Reconciliation Invariants
        // =========================================================================

        @Test
        @DisplayName("removePlayerFromGame preserves current actor identity when non-current player is removed")
        void challenge_RemovePlayer_NonCurrentRemoved_PreservesCurrentPlayerIdentity() {
            for (int tableSize = 3; tableSize <= 8; tableSize++) {
                for (int removedIdx = 0; removedIdx < tableSize; removedIdx++) {
                    for (int currentIdx = 0; currentIdx < tableSize; currentIdx++) {
                        if (removedIdx == currentIdx) continue;

                        List<Player> players = createPlayerList(tableSize);
                        List<Player> active = new ArrayList<>(players);

                        Player toRemove = active.get(removedIdx);
                        Player expectedCurrentPlayer = active.get(currentIdx);

                        TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                                players, active, toRemove,
                                0, 1, 2, currentIdx, "p0", "p1", "p2"
                        );

                        assertEquals(tableSize - 1, active.size(), "Active list must shrink by 1");
                        assertEquals(tableSize - 1, players.size(), "Players list must shrink by 1");

                        // The turn MUST point to expectedCurrentPlayer in the new active list
                        Player actualCurrentPlayer = active.get(result.currentPlayerPosition());
                        assertSame(expectedCurrentPlayer, actualCurrentPlayer,
                                "Turn identity lost when removing player at index " + removedIdx + " while current was " + currentIdx);

                        // Dealer index must be within valid bounds
                        assertTrue(result.dealerPosition() >= 0 && result.dealerPosition() < active.size());
                    }
                }
            }
        }

        @Test
        @DisplayName("removePlayerFromGame advances to immediate actionable successor when current player is removed")
        void challenge_RemovePlayer_CurrentRemoved_PassesTurnToSuccessor() {
            for (int tableSize = 3; tableSize <= 6; tableSize++) {
                for (int currentIdx = 0; currentIdx < tableSize; currentIdx++) {
                    List<Player> players = createPlayerList(tableSize);
                    List<Player> active = new ArrayList<>(players);

                    Player toRemove = active.get(currentIdx);
                    // The successor in original list is (currentIdx + 1) % tableSize
                    Player expectedSuccessor = active.get((currentIdx + 1) % tableSize);

                    TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                            players, active, toRemove,
                            0, 1, 2, currentIdx, "p0", "p1", "p2"
                    );

                    Player actualCurrent = active.get(result.currentPlayerPosition());
                    assertSame(expectedSuccessor, actualCurrent,
                            "Turn did not pass to expected successor after current player removed");
                }
            }
        }

        // =========================================================================
        // 8. Normalization Invariants and Idempotency
        // =========================================================================

        @Test
        @DisplayName("normalizeBlindPositions is strictly idempotent and handles extreme integer ranges")
        void challenge_NormalizeBlindPositions_IdempotenceAndExtremes() {
            for (int tableSize = 2; tableSize <= 10; tableSize++) {
                int[] testValues = {-1000, -tableSize, -1, 0, 1, tableSize - 1, tableSize, tableSize + 1, 9999, Integer.MAX_VALUE - 1};

                for (int sb : testValues) {
                    for (int bb : testValues) {
                        TableSeating.BlindPositions firstNorm = TableSeating.normalizeBlindPositions(tableSize, sb, bb);

                        // Invariant: strictly bounded
                        assertTrue(firstNorm.smallBlindPosition() >= 0 && firstNorm.smallBlindPosition() < tableSize);
                        assertTrue(firstNorm.bigBlindPosition() >= 0 && firstNorm.bigBlindPosition() < tableSize);

                        // Idempotence: normalize(normalize(x)) == normalize(x)
                        TableSeating.BlindPositions secondNorm = TableSeating.normalizeBlindPositions(
                                tableSize, firstNorm.smallBlindPosition(), firstNorm.bigBlindPosition());
                        assertEquals(firstNorm, secondNorm, "normalizeBlindPositions must be strictly idempotent");
                    }
                }
            }
        }

        // =========================================================================
        // 9. Monte Carlo Randomized Tournament Simulations (200 Tournaments)
        // =========================================================================

        @Test
        @DisplayName("200 randomized tournaments from 10 players down to 1 survivor verify seating invariants at every hand")
        void challenge_MonteCarloRandomizedTournaments_AllInvariantsHoldThroughEveryHand() {
            Random random = new Random(42_1337);

            for (int tourney = 1; tourney <= 200; tourney++) {
                int initialSize = 10;
                List<Player> allPlayers = createPlayerList(initialSize);
                List<String> originalSeatOrder = allPlayers.stream().map(Player::getPlayerId).toList();

                List<Player> active = new ArrayList<>(allPlayers);
                String currentAnchor = "p" + random.nextInt(initialSize);

                int handCount = 0;
                while (active.size() > 1 && handCount < 100) {
                    handCount++;

                    // Advance positions for new hand
                    TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, originalSeatOrder, currentAnchor);

                    int size = active.size();
                    int dealer = result.dealerPosition();
                    int sb = result.smallBlindPosition();
                    int bb = result.bigBlindPosition();
                    int current = result.currentPlayerPosition();

                    // 1. Bounds checks
                    assertTrue(dealer >= 0 && dealer < size);
                    assertTrue(sb >= 0 && sb < size);
                    assertTrue(bb >= 0 && bb < size);
                    assertTrue(current >= 0 && current < size);

                    // 2. Dealer anchor consistency
                    assertEquals(active.get(dealer).getPlayerId(), result.dealerAnchorPlayerId());

                    // 3. Heads-up vs Multi-way invariants
                    if (size == 2) {
                        assertEquals(dealer, sb, "Tourney " + tourney + " Hand " + handCount + ": Dealer is SB in heads-up");
                        assertEquals((dealer + 1) % 2, bb, "Tourney " + tourney + " Hand " + handCount + ": Opponent is BB in heads-up");
                        assertEquals(dealer, current, "Tourney " + tourney + " Hand " + handCount + ": Dealer acts first in heads-up");
                    } else {
                        assertEquals((dealer + 1) % size, sb, "Tourney " + tourney + " Hand " + handCount + ": SB=(dealer+1)%size");
                        assertEquals((sb + 1) % size, bb, "Tourney " + tourney + " Hand " + handCount + ": BB=(SB+1)%size");
                        assertEquals((bb + 1) % size, current, "Tourney " + tourney + " Hand " + handCount + ": UTG=(BB+1)%size");
                    }

                    currentAnchor = result.dealerAnchorPlayerId();

                    // Simulate hand outcome: randomly eliminate 0 or 1 player
                    if (random.nextDouble() < 0.35 && active.size() > 2) {
                        int elimIdx = random.nextInt(active.size());
                        Player eliminated = active.remove(elimIdx);
                        eliminated.setIsOut();
                    } else if (active.size() == 2 && random.nextDouble() < 0.15) {
                        // Heads-up elimination
                        Player loser = active.remove(random.nextInt(2));
                        loser.setIsOut();
                    }
                }

                // Exactly 1 survivor or loop limit reached
                assertTrue(active.size() >= 1);
            }
        }
    }

    @Nested
    @DisplayName("PlayerRemovalEdgeCases")
    class PlayerRemovalEdgeCases {

        private final HandEvaluatorService handEvaluator = new HandEvaluatorService();

        // =========================================================================
        // Helpers
        // =========================================================================

        private Player createPlayer(String id, String name, int chips) {
            return new Player(name, id, chips);
        }

        private Player createPlayer(String id, String name, int chips, boolean isOut, boolean hasFolded, boolean isAllIn) {
            Player p = new Player(name, id, chips);
            if (isOut) {
                p.setIsOut();
            }
            if (hasFolded) {
                p.doAction(PlayerAction.FOLD, 0, 0);
            }
            if (isAllIn) {
                p.doAction(PlayerAction.ALL_IN, 0, 0);
            }
            return p;
        }

        private Game createGame(int numPlayers, int chips) {
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < numPlayers; i++) {
                players.add(createPlayer("p" + i, "Player_" + i, chips));
            }
            return new Game("game-" + UUID.randomUUID(), players, 10, 20, handEvaluator);
        }

        // =========================================================================
        // 1. Removal of Dealer, Small Blind, Big Blind, and Current Player
        // =========================================================================

        @Nested
        @DisplayName("Removal of Key Table Positions (Dealer, SB, BB, Current)")
        class KeyPositionRemovalTests {

            @Test
            @DisplayName("Removal of dealer preserves current player identity and adjusts dealer index safely")
            void removalOfDealer_PreservesTurnAndAdjustsDealerIndex() {
                Game game = createGame(5, 1000);
                List<Player> players = game.getActivePlayers();
                Player dealer = players.get(0); // p0 is initial dealer
                Player current = players.get(game.getCurrentPlayerPosition()); // p3 (UTG in 5-player)
                assertEquals("p0", dealer.getPlayerId());
                assertEquals("p3", current.getPlayerId());

                game.removePlayerFromGame(dealer);

                assertEquals(4, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(dealer));
                assertTrue(game.getDealerPosition() >= 0 && game.getDealerPosition() < game.getActivePlayers().size());
                assertEquals("p3", game.getCurrentPlayer().getPlayerId(),
                        "Current player must remain p3 after dealer departure");

                // Advance positions for next hand: next dealer rotates clockwise from departed dealer anchor
                game.advancePositions();
                assertEquals("p1", game.getActivePlayers().get(game.getDealerPosition()).getPlayerId(),
                        "Next dealer clockwise from departed p0 must be p1");
            }

            @Test
            @DisplayName("Removal of small blind preserves current player turn")
            void removalOfSmallBlind_PreservesTurn() {
                Game game = createGame(4, 1000);
                Player sbPlayer = game.getActivePlayers().get(game.getSmallBlindPosition()); // p1
                assertEquals("p1", sbPlayer.getPlayerId());
                assertEquals("p3", game.getCurrentPlayer().getPlayerId()); // UTG is p3

                game.removePlayerFromGame(sbPlayer);

                assertEquals(3, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(sbPlayer));
                assertEquals("p3", game.getCurrentPlayer().getPlayerId(),
                        "Turn must stay on p3 after small blind leaves");
            }

            @Test
            @DisplayName("Removal of big blind preserves current player turn")
            void removalOfBigBlind_PreservesTurn() {
                Game game = createGame(4, 1000);
                Player bbPlayer = game.getActivePlayers().get(game.getBigBlindPosition()); // p2
                assertEquals("p2", bbPlayer.getPlayerId());
                assertEquals("p3", game.getCurrentPlayer().getPlayerId());

                game.removePlayerFromGame(bbPlayer);

                assertEquals(3, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(bbPlayer));
                assertEquals("p3", game.getCurrentPlayer().getPlayerId(),
                        "Turn must stay on p3 after big blind leaves");
            }

            @Test
            @DisplayName("Removal of current player passes turn to immediate successor seat")
            void removalOfCurrentPlayer_PassesTurnToImmediateSuccessor() {
                Game game = createGame(4, 1000);
                Player currentPlayer = game.getCurrentPlayer(); // p3
                assertEquals("p3", currentPlayer.getPlayerId());

                game.removePlayerFromGame(currentPlayer);

                assertEquals(3, game.getActivePlayers().size());
                assertFalse(game.getActivePlayers().contains(currentPlayer));
                // In [p0, p1, p2], seat 3 wrapped to seat 0 (p0)
                assertEquals("p0", game.getCurrentPlayer().getPlayerId(),
                        "Turn passes to successor seat p0 after p3 departs");
            }

            @Test
            @DisplayName("Removal of current player when immediate successor is folded skips to next actionable")
            void removalOfCurrentPlayer_SuccessorFolded_SkipsToNextActionable() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                Player p2 = createPlayer("p2", "Charlie", 1000, false, true, false); // folded
                Player p3 = createPlayer("p3", "Dave", 1000);

                List<Player> active = new ArrayList<>(List.of(p0, p1, p2, p3));
                List<Player> table = new ArrayList<>(active);

                // Turn is currently on p1 (index 1)
                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, p1, 0, 1, 2, 1, "p0", "p1", "p2");

                // p1 removed -> active is [p0, p2 (folded), p3].
                // Seat index 1 is p2 (folded), so turn must skip to index 2 (p3).
                assertEquals(2, result.currentPlayerPosition());
                assertEquals("p3", active.get(result.currentPlayerPosition()).getPlayerId());
            }

            @Test
            @DisplayName("Removal of current player when immediate successor is all-in skips to next actionable")
            void removalOfCurrentPlayer_SuccessorAllIn_SkipsToNextActionable() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                Player p2 = createPlayer("p2", "Charlie", 0, false, false, true); // all-in
                Player p3 = createPlayer("p3", "Dave", 1000);

                List<Player> active = new ArrayList<>(List.of(p0, p1, p2, p3));
                List<Player> table = new ArrayList<>(active);

                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, p1, 0, 1, 2, 1, "p0", "p1", "p2");

                // p1 removed -> active is [p0, p2 (all-in), p3].
                // Seat index 1 is p2 (all-in), so turn must skip to index 2 (p3).
                assertEquals(2, result.currentPlayerPosition());
                assertEquals("p3", active.get(result.currentPlayerPosition()).getPlayerId());
            }

            @Test
            @DisplayName("Removal of current player at tail of active list wraps around to seat 0")
            void removalOfCurrentPlayer_TailOfList_WrapsAroundToHead() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                Player p2 = createPlayer("p2", "Charlie", 1000);

                List<Player> active = new ArrayList<>(List.of(p0, p1, p2));
                List<Player> table = new ArrayList<>(active);

                // Turn is on p2 (index 2, tail). p2 is removed.
                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, p2, 0, 1, 2, 2, "p0", "p1", "p2");

                // active is [p0, p1], new index must wrap to 0 (p0)
                assertEquals(0, result.currentPlayerPosition());
                assertEquals("p0", active.get(result.currentPlayerPosition()).getPlayerId());
            }
        }

        // =========================================================================
        // 2. Removal When Remaining Players are Non-Actionable (All-in or Folded)
        // =========================================================================

        @Nested
        @DisplayName("Removal Under All-In and Folded Constraint States")
        class NonActionableRemainingPlayersTests {

            @Test
            @DisplayName("Current player removed when all remaining players are all-in or folded terminates cleanly")
            void removalOfCurrentPlayer_AllRemainingNonActionable_TerminatesWithoutHanging() {
                Player p0 = createPlayer("p0", "Alice", 0, false, false, true); // all-in
                Player p1 = createPlayer("p1", "Bob", 1000); // active current
                Player p2 = createPlayer("p2", "Charlie", 1000, false, true, false); // folded

                List<Player> active = new ArrayList<>(List.of(p0, p1, p2));
                List<Player> table = new ArrayList<>(active);

                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, p1, 0, 1, 2, 1, "p0", "p1", "p2");

                // active is now [p0 (all-in), p2 (folded)]. Neither is actionable.
                // Turn navigation must terminate cleanly without infinite loop.
                assertTrue(result.currentPlayerPosition() >= 0 && result.currentPlayerPosition() < active.size());
            }

            @Test
            @DisplayName("Removal of player when only one all-in player remains")
            void removalOfPlayer_OnlyOneAllInRemains() {
                Player p0 = createPlayer("p0", "Alice", 0, false, false, true); // all-in
                Player p1 = createPlayer("p1", "Bob", 1000);

                List<Player> active = new ArrayList<>(List.of(p0, p1));
                List<Player> table = new ArrayList<>(active);

                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, p1, 0, 0, 1, 1, "p0", "p0", "p1");

                assertEquals(1, active.size());
                assertEquals(0, result.currentPlayerPosition());
                assertEquals("p0", active.get(result.currentPlayerPosition()).getPlayerId());
            }
        }

        // =========================================================================
        // 3. Multi-Step Rapid Sequential Removals Under Diverse Permutations
        // =========================================================================

        @Nested
        @DisplayName("Multi-Step Rapid Removals and Invariant Fuzzing")
        class RapidSequentialRemovalTests {

            @Test
            @DisplayName("Sequential removal in forward order down to zero players maintains index validity")
            void rapidRemoval_ForwardOrder_DownToZero() {
                Game game = createGame(6, 1000);

                while (!game.getActivePlayers().isEmpty()) {
                    Player playerToRemove = game.getActivePlayers().getFirst();
                    int sizeBefore = game.getActivePlayers().size();

                    game.removePlayerFromGame(playerToRemove);

                    int sizeAfter = game.getActivePlayers().size();
                    assertEquals(sizeBefore - 1, sizeAfter);

                    if (sizeAfter > 0) {
                        assertTrue(game.getDealerPosition() >= 0 && game.getDealerPosition() < sizeAfter,
                                "Dealer position must remain in bounds: " + game.getDealerPosition());
                        assertNotNull(game.getCurrentPlayer(), "Current player must be resolvable");
                    } else {
                        assertThrows(IllegalStateException.class, game::getCurrentPlayer);
                    }
                }
            }

            @Test
            @DisplayName("Sequential removal in reverse order down to zero players maintains index validity")
            void rapidRemoval_ReverseOrder_DownToZero() {
                Game game = createGame(6, 1000);

                while (!game.getActivePlayers().isEmpty()) {
                    Player playerToRemove = game.getActivePlayers().getLast();
                    int sizeBefore = game.getActivePlayers().size();

                    game.removePlayerFromGame(playerToRemove);

                    int sizeAfter = game.getActivePlayers().size();
                    assertEquals(sizeBefore - 1, sizeAfter);

                    if (sizeAfter > 0) {
                        assertTrue(game.getDealerPosition() >= 0 && game.getDealerPosition() < sizeAfter);
                        assertNotNull(game.getCurrentPlayer());
                    } else {
                        assertThrows(IllegalStateException.class, game::getCurrentPlayer);
                    }
                }
            }

            @Test
            @DisplayName("Sequential removal of the active current player on every step")
            void rapidRemoval_CurrentPlayerEveryStep() {
                Game game = createGame(6, 1000);

                while (game.getActivePlayers().size() > 1) {
                    Player currentActor = game.getCurrentPlayer();
                    assertNotNull(currentActor);

                    game.removePlayerFromGame(currentActor);

                    assertFalse(game.getActivePlayers().contains(currentActor));
                    assertTrue(game.getDealerPosition() >= 0 && game.getDealerPosition() < game.getActivePlayers().size());
                    assertNotNull(game.getCurrentPlayer());
                }

                assertEquals(1, game.getActivePlayers().size());
                assertEquals(0, game.getDealerPosition());
                assertNotNull(game.getCurrentPlayer());
            }

            @ParameterizedTest(name = "Fuzz removal permutation seed {0}")
            @ValueSource(ints = {11, 23, 42, 77, 99, 137, 256, 512, 1024, 2048})
            @DisplayName("Randomized removal permutation fuzzing preserves all seating invariants")
            void rapidRemoval_FuzzRandomPermutations(int seed) {
                Game game = createGame(6, 1000);
                List<Player> removalQueue = new ArrayList<>(game.getActivePlayers());
                Collections.shuffle(removalQueue, new Random(seed));

                for (Player target : removalQueue) {
                    game.removePlayerFromGame(target);

                    int remaining = game.getActivePlayers().size();
                    if (remaining > 0) {
                        int dealer = game.getDealerPosition();
                        assertTrue(dealer >= 0 && dealer < remaining,
                                "Dealer position " + dealer + " out of bounds for table size " + remaining);
                        Player current = game.getCurrentPlayer();
                        assertNotNull(current);
                        assertTrue(game.getActivePlayers().contains(current));
                    }
                }

                assertEquals(0, game.getActivePlayers().size());
                assertThrows(IllegalStateException.class, game::getCurrentPlayer);
            }
        }

        // =========================================================================
        // 4. Transitions: 3 to 2 Players (Heads-Up) and 2 to 1 Player (Survivor)
        // =========================================================================

        @Nested
        @DisplayName("Table Size Transitions (Heads-Up & Single Survivor)")
        class TableSizeTransitionTests {

            @Test
            @DisplayName("Transition from 3 to 2 players applies heads-up rules immediately on advancePositions")
            void transitionThreeToTwo_AdvancePositions_AppliesHeadsUpImmediately() {
                Game game = createGame(3, 1000);

                // In 3-player game: dealer=0 (p0), SB=1 (p1), BB=2 (p2), current=0 (p0 acts first UTG)
                assertEquals(0, game.getDealerPosition());
                assertEquals(1, game.getSmallBlindPosition());
                assertEquals(2, game.getBigBlindPosition());

                // p2 departs
                game.removePlayerFromGame(game.getActivePlayers().get(2));
                assertEquals(2, game.getActivePlayers().size());

                // Next hand starts: advance positions
                game.advancePositions();

                // Heads-up rules must apply immediately:
                // Dealer advances clockwise from p0 to p1 (index 1).
                // In heads-up: dealer is small blind, opponent is big blind, dealer acts first!
                assertEquals(1, game.getDealerPosition(), "p1 is dealer at active index 1");
                assertEquals(1, game.getSmallBlindPosition(), "In heads-up, dealer is small blind");
                assertEquals(0, game.getBigBlindPosition(), "In heads-up, opponent is big blind");
                assertEquals(1, game.getCurrentPlayerPosition(), "In heads-up, dealer acts first pre-flop");
                assertEquals("p1", game.getCurrentPlayer().getPlayerId());
            }

            @Test
            @DisplayName("TableSeating.computePositionsForTable produces exact heads-up positions for size 2")
            void computePositionsForTable_HeadsUpExact() {
                // Dealer at seat 0
                TableSeating.TablePositions hu0 = TableSeating.computePositionsForTable(2, 0);
                assertEquals(0, hu0.smallBlindPosition());
                assertEquals(1, hu0.bigBlindPosition());
                assertEquals(0, hu0.currentPlayerPosition());

                // Dealer at seat 1
                TableSeating.TablePositions hu1 = TableSeating.computePositionsForTable(2, 1);
                assertEquals(1, hu1.smallBlindPosition());
                assertEquals(0, hu1.bigBlindPosition());
                assertEquals(1, hu1.currentPlayerPosition());
            }

            @Test
            @DisplayName("Transition from 2 players to 1 single survivor handles advancePositions cleanly")
            void transitionTwoToOne_Survivor_AdvancePositionsSafe() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                List<Player> active = List.of(p0);
                List<String> seatOrder = List.of("p0", "p1");

                TableSeating.PositionRotationResult result = TableSeating.advancePositions(active, seatOrder, "p1");

                assertEquals(0, result.dealerPosition());
                assertEquals("p0", result.dealerAnchorPlayerId());
                assertEquals(0, result.smallBlindPosition());
                assertEquals(0, result.bigBlindPosition());
                assertEquals(0, result.currentPlayerPosition());
            }

            @Test
            @DisplayName("Game with 1 active player marks game over upon cleanupAfterHand")
            void cleanupAfterHand_SingleSurvivor_MarksGameOver() {
                Game game = createGame(2, 1000);
                Player p1 = game.getActivePlayers().get(1);

                game.removePlayerFromGame(p1);
                assertEquals(1, game.getActivePlayers().size());

                game.cleanupAfterHand();
                assertTrue(game.isGameOver(), "Game must be marked over when only 1 active player remains");
            }
        }

        // =========================================================================
        // 5. Index Safety and Exception Resistance Under Extreme / Malformed Inputs
        // =========================================================================

        @Nested
        @DisplayName("Index Bounds Safety and Malformed Input Resistance")
        class IndexSafetyAndExtremeInputsTests {

            @Test
            @DisplayName("Null and empty inputs to all TableSeating static methods do not throw exceptions")
            void nullAndEmptyInputs_SafeExecution() {
                assertDoesNotThrow(() -> {
                    TableSeating.indexOfActivePlayer(null, "p1");
                    TableSeating.indexOfActivePlayer(List.of(), "p1");
                    TableSeating.indexOfActivePlayer(List.of(createPlayer("p1", "P", 10)), null);

                    TableSeating.normalizeCurrentPlayerPosition(null, 5);
                    TableSeating.normalizeCurrentPlayerPosition(List.of(), 5);

                    TableSeating.computePositionsForTable(0, 0);
                    TableSeating.computePositionsForTable(-1, 0);
                    TableSeating.computePositionsForTable(1, 0);

                    TableSeating.updatePositionsForCurrentTable((List<Player>) null, 0);
                    TableSeating.updatePositionsForCurrentTable(List.of(), 0);
                    TableSeating.updatePositionsForCurrentTable(null, "p0", "p1", "p2");
                    TableSeating.updatePositionsForCurrentTable(List.of(), "p0", "p1", "p2");

                    TableSeating.normalizeBlindPositions(0, 1, 2);
                    TableSeating.normalizeBlindPositions(-1, 1, 2);
                    TableSeating.normalizeBlindPositions((List<Player>) null, 1, 2);
                    TableSeating.normalizeBlindPositions(List.of(), 1, 2);
                    TableSeating.normalizeBlindPositions((List<Player>) null, 0);
                    TableSeating.normalizeBlindPositions(List.of(), 0);

                    TableSeating.advancePositions(null, null, null);
                    TableSeating.advancePositions(List.of(), null, null);
                    TableSeating.advancePositions((List<Player>) null, 0, 0, 0, 0);
                    TableSeating.advancePositions(List.of(), 0, 0, 0, 0);

                    TableSeating.nextPlayer(null, 0);
                    TableSeating.nextPlayer(List.of(), 0);

                    TableSeating.firstActionableSeatAfterDealerAnchor(null, null, null, 0);
                    TableSeating.firstActionableSeatAfterDealerAnchor(List.of(), null, null, 0);
                    TableSeating.firstActionableSeatAfterDealerAnchor(null, "anchor");
                    TableSeating.firstActionableSeatAfterDealerAnchor(List.of(), "anchor");

                    TableSeating.reconcileSeatingAfterRemoval(null, null, null, 0, 0, 0, null, null, null, 0);
                    TableSeating.reconcileSeatingAfterRemoval(List.of(), null, null, 0, 0, 0, null, null, null, 0);

                    TableSeating.removePlayerFromGame(null, null, (Player) null, 0, 0, 0, 0, null, null, null);
                    TableSeating.removePlayerFromGame(null, null, (String) null, 0, 0, 0, 0, null, null, null);
                });
            }

            @Test
            @DisplayName("Extreme negative and oversized indices handled safely via floorMod")
            void extremeIndices_HandledSafely() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                List<Player> active = List.of(p0, p1);

                assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(active, 0));
                assertEquals(1, TableSeating.normalizeCurrentPlayerPosition(active, -1));
                assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(active, -2));
                assertEquals(0, TableSeating.normalizeCurrentPlayerPosition(active, 100));
                assertEquals(1, TableSeating.normalizeCurrentPlayerPosition(active, 101));

                // Integer overflow extremes
                int minNormalized = TableSeating.normalizeCurrentPlayerPosition(active, Integer.MIN_VALUE);
                assertTrue(minNormalized >= 0 && minNormalized < active.size());
                int maxNormalized = TableSeating.normalizeCurrentPlayerPosition(active, Integer.MAX_VALUE);
                assertTrue(maxNormalized >= 0 && maxNormalized < active.size());
            }

            @Test
            @DisplayName("nextPlayer with extreme negative and positive positions wraps safely")
            void nextPlayer_ExtremePositions_WrapsSafely() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                List<Player> active = List.of(p0, p1);

                int nextNeg = TableSeating.nextPlayer(active, -100);
                assertTrue(nextNeg >= 0 && nextNeg < active.size());

                int nextLarge = TableSeating.nextPlayer(active, 10000);
                assertTrue(nextLarge >= 0 && nextLarge < active.size());
            }

            @Test
            @DisplayName("firstActionableSeatAfterDealerAnchor with anchor not in seat order clamps safely")
            void firstActionableSeatAfterDealerAnchor_AnchorNotInSeatOrder_ClampsSafely() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                List<Player> active = List.of(p0, p1);
                List<String> disjointSeats = List.of("x1", "x2", "x3");

                int actor = TableSeating.firstActionableSeatAfterDealerAnchor(active, disjointSeats, "missing", -5);
                assertTrue(actor >= 0 && actor < active.size());

                int actor2 = TableSeating.firstActionableSeatAfterDealerAnchor(active, disjointSeats, "missing", 999);
                assertTrue(actor2 >= 0 && actor2 < active.size());
            }

            @Test
            @DisplayName("removePlayerFromGame when removing non-existent player ID leaves lists untouched")
            void removePlayerFromGame_NonExistentPlayerId_LeavesListsUntouched() {
                Player p0 = createPlayer("p0", "Alice", 1000);
                Player p1 = createPlayer("p1", "Bob", 1000);
                List<Player> active = new ArrayList<>(List.of(p0, p1));
                List<Player> table = new ArrayList<>(List.of(p0, p1));

                TableSeating.PlayerRemovalResult result = TableSeating.removePlayerFromGame(
                        table, active, "non-existent-id", 0, 0, 1, 0, "p0");

                assertEquals(2, active.size());
                assertEquals(2, table.size());
                assertEquals(0, result.currentPlayerPosition());
            }
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class AggregateScenarios extends GameScenarioFixture {

        @Test
        void departedBlindIdentityStaysAnchoredAndNextDealerWalksOriginalSeatOrder() {
                Player dealer = new Player("D", "d", 100);
                Player smallBlind = new Player("A", "a", 100);
                Player bigBlind = new Player("B", "b", 100);
                Player c = new Player("C", "c", 100);
                Game anchored = Game.restoreBuilder().gameId("position-anchor")
                                .players(List.of(dealer, smallBlind, bigBlind, c)).activePlayerIds(List.of("d", "a", "b", "c"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(0)
                                .smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(3).currentHighestBet(0)
                                .currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of()).preHandCarryOver(0).handSeatOrder(List.of("d", "a", "b", "c"))
                                .dealerAnchorPlayerId("d").smallBlindPlayerId("a").bigBlindPlayerId("b")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true).actedPlayersInRound(Set.of())
                                .scheduledTaskDeadlines(Map.of()).handEvaluator(mockHandEvaluator).build();

                anchored.removePlayerFromGame(dealer);
                anchored.removePlayerFromGame(bigBlind);

                assertEquals("a", anchored.getSmallBlindPlayerId());
                assertEquals("b", anchored.getBigBlindPlayerId(), "The departed payer identity is retained, not reassigned");
                assertFalse(anchored.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals("b")));
                anchored.advancePositions();
                assertEquals("a", anchored.getDealerAnchorPlayerId());
                assertEquals("a", anchored.getSmallBlindPlayerId());
                assertEquals("c", anchored.getBigBlindPlayerId());
        }

        @Test
        void postFlopActorUsesDealerAnchorAfterSmallBlindLeavesDuringPreFlop() {
                Player a = restoredPlayer("A", "a", 990, 10, false);
                Player b = restoredPlayer("B", "b", 980, 20, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Player d = restoredPlayer("D", "d", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("post-flop-seat-anchor")
                                .players(List.of(a, b, c, d)).activePlayerIds(List.of("a", "b", "c", "d"))
                                .remainingDeck(List.of(new Card(Rank.TWO, Suit.SPADES), new Card(Rank.THREE, Suit.SPADES),
                                                new Card(Rank.FOUR, Suit.SPADES), new Card(Rank.FIVE, Suit.SPADES),
                                                new Card(Rank.SIX, Suit.SPADES))).communityCards(List.of()).pot(30).dealerPosition(3)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                                .currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP).gameOver(false)
                                .smallBlind(10).bigBlind(20).handContributions(Map.of("a", 10, "b", 20))
                                .preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                                .dealerAnchorPlayerId("d").smallBlindPlayerId("a").bigBlindPlayerId("b")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(a);
                assertEquals("c", hand.getCurrentPlayer().getPlayerId());
                hand.processPlayerDecision(c, new PlayerDecision(PlayerAction.CALL, 0, "c"));
                hand.processPlayerDecision(d, new PlayerDecision(PlayerAction.CALL, 0, "d"));
                hand.processPlayerDecision(b, new PlayerDecision(PlayerAction.CHECK, 0, "b"));

                assertEquals(GamePhase.FLOP, hand.getCurrentPhase());
                assertEquals("b", hand.getCurrentPlayer().getPlayerId(),
                                "First post-flop action starts after departed dealer D's stable hand anchor");
                assertEquals("d", hand.getDealerAnchorPlayerId());
                assertEquals("a", hand.getSmallBlindPlayerId(), "The departed blind payer identity remains recorded");

                Player a2 = restoredPlayer("A2", "a2", 1000, 0, false);
                Player b2 = restoredPlayer("B2", "b2", 1000, 0, false);
                Player c2 = restoredPlayer("C2", "c2", 1000, 0, false);
                Player d2 = restoredPlayer("D2", "d2", 1000, 0, false);
                Game dealerLeft = Game.restoreBuilder().gameId("departed-dealer-post-flop")
                                .players(List.of(a2, b2, c2, d2)).activePlayerIds(List.of("a2", "b2", "c2", "d2"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(3)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                                .currentHighestBet(0).currentPhase(GamePhase.FLOP).gameOver(false)
                                .smallBlind(10).bigBlind(20).handContributions(Map.of()).preHandCarryOver(0)
                                .handSeatOrder(List.of("a2", "b2", "c2", "d2")).dealerAnchorPlayerId("d2")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                dealerLeft.removePlayerFromGame(d2);
                dealerLeft.resetBetsForRound();
                assertEquals("a2", dealerLeft.getCurrentPlayer().getPlayerId(),
                                "A departed dealer anchor still determines the clockwise first seat");
        }

        @Test
        void removingCurrentPlayerKeepsImmediateEligibleSuccessorAsActor() {
                Player a = restoredPlayer("A", "a", 1000, 0, false);
                Player b = restoredPlayer("B", "b", 1000, 0, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Player d = restoredPlayer("D", "d", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("leave-successor")
                                .players(List.of(a, b, c, d)).activePlayerIds(List.of("a", "b", "c", "d"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(0).dealerPosition(0)
                                .smallBlindPosition(1).bigBlindPosition(2).currentPlayerPosition(1)
                                .currentHighestBet(0).currentPhase(GamePhase.FLOP).gameOver(false).smallBlind(5).bigBlind(10)
                                .handContributions(Map.of()).preHandCarryOver(0).handSeatOrder(List.of("a", "b", "c", "d"))
                                .dealerAnchorPlayerId("a").readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(b);
                TurnOutcome outcome = hand.reconcileAfterPlayerRemoval();

                assertEquals(TurnOutcome.TurnOutcomeType.NEXT_PLAYER, outcome.type());
                assertEquals("c", hand.getCurrentPlayer().getPlayerId(), "The successor is selected exactly once");
        }

        @Test
        void departureThatCompletesMatchedFlopTurnsAdvancesToTurnOnce() {
                Player a = restoredPlayer("A", "a", 1000, 0, false);
                Player b = restoredPlayer("B", "b", 1000, 0, false);
                Player c = restoredPlayer("C", "c", 1000, 0, false);
                Game hand = Game.restoreBuilder().gameId("leave-completes-flop")
                                .players(List.of(a, b, c)).activePlayerIds(List.of("a", "b", "c"))
                                .remainingDeck(List.of(new Card(Rank.SIX, Suit.CLUBS))).communityCards(List.of(
                                                new Card(Rank.TWO, Suit.CLUBS), new Card(Rank.THREE, Suit.DIAMONDS),
                                                new Card(Rank.FOUR, Suit.HEARTS)))
                                .pot(0).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2)
                                .currentPlayerPosition(2).currentHighestBet(0).currentPhase(GamePhase.FLOP)
                                .gameOver(false).smallBlind(5).bigBlind(10).handContributions(Map.of()).preHandCarryOver(0)
                                .handSeatOrder(List.of("a", "b", "c")).dealerAnchorPlayerId("a")
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(false)
                                .actedPlayersInRound(Set.of("a", "b")).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                hand.removePlayerFromGame(c);
                TurnOutcome outcome = hand.reconcileAfterPlayerRemoval();

                assertEquals(TurnOutcome.TurnOutcomeType.PHASE_ADVANCED, outcome.type());
                assertEquals(GamePhase.TURN, hand.getCurrentPhase());
                assertEquals(4, hand.getCommunityCards().size(), "The turn card is dealt once after the three-card flop");
        }

        /**
         * Protects the expected behavior for heads up post blinds dealer posts small blind.
         */
        @Test
        void testHeadsUpPostBlindsDealerPostsSmallBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-sb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(0, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().getFirst().getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up post blinds non dealer posts big blind.
         */
        @Test
        void testHeadsUpPostBlindsNonDealerPostsBigBlind() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-bb", headsUpPlayers, 10, 20, mockHandEvaluator);
                headsUpGame.postBlinds();

                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());
        }

        /**
         * Protects the expected behavior for heads up advance positions blinds rotate correctly between hands edge case.
         */
        @Test
        void testHeadsUpAdvancePositionsBlindsRotateCorrectlyBetweenHandsEdgeCase() {
                List<Player> headsUpPlayers = new ArrayList<>();
                headsUpPlayers.add(new Player("Dealer", "d1", 1000));
                headsUpPlayers.add(new Player("Opponent", "o1", 1000));

                Game headsUpGame = new Game("heads-up-rotate", headsUpPlayers, 10, 20, mockHandEvaluator);

                headsUpGame.postBlinds();
                assertEquals(10, headsUpGame.getActivePlayers().get(0).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(1).getCurrentBet());

                headsUpGame.resetForNewHand();
                headsUpGame.postBlinds();

                assertEquals(1, headsUpGame.getDealerPosition());
                assertEquals(10, headsUpGame.getActivePlayers().get(1).getCurrentBet());
                assertEquals(20, headsUpGame.getActivePlayers().get(0).getCurrentBet());
        }

        /**
         * Protects the expected behavior for advance positions.
         */
        @Test
        void testAdvancePositions() {
                int initialDealer = game.getDealerPosition();

                game.advancePositions();

                assertEquals((initialDealer + 1) % 3, game.getDealerPosition());
        }

        /**
         * Protects the expected behavior for next player.
         */
        @Test
        void testNextPlayer() {
                Player firstPlayer = game.getCurrentPlayer();
                game.nextPlayer();
                Player secondPlayer = game.getCurrentPlayer();

                assertNotEquals(firstPlayer, secondPlayer);
        }

        /**
         * Protects the contract that remove player from game when non current player leaves before current should preserve current player.
         */
        @Test
        void removePlayerFromGame_WhenNonCurrentPlayerLeavesBeforeCurrent_ShouldPreserveCurrentPlayer() {
                game.nextPlayer();
                game.nextPlayer();
                Player currentBeforeRemoval = game.getCurrentPlayer();

                Player playerToRemove = game.getActivePlayers().stream()
                                .filter(p -> !p.equals(currentBeforeRemoval))
                                .findFirst()
                                .orElseThrow();

                game.removePlayerFromGame(playerToRemove);

                assertEquals(currentBeforeRemoval, game.getCurrentPlayer());
                assertEquals(2, game.getActivePlayers().size());
        }

        /**
         * Protects the contract that get big blind player ID when non current player leaves and blind index stale should not throw.
         */
        @Test
        void getBigBlindPlayerId_WhenNonCurrentPlayerLeavesAndBlindIndexStale_ShouldNotThrow() {
                game.postBlinds();

                Player playerToRemove = game.getActivePlayers().get(1);
                assertNotEquals(playerToRemove, game.getCurrentPlayer());

                game.removePlayerFromGame(playerToRemove);

                String bigBlindPlayerId = assertDoesNotThrow(() -> game.getBigBlindPlayerId());

                assertNotNull(bigBlindPlayerId);
                assertTrue(game.getActivePlayers().stream().anyMatch(p -> p.getPlayerId().equals(bigBlindPlayerId)));
        }
    }
}
