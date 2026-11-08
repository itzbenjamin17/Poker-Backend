package com.pokergame.model;

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
            Player p2 = player("p2", "Bob", 0, true, false, false); // isOut
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
}
