package com.pokergame.model;

import com.pokergame.enums.PlayerAction;
import com.pokergame.service.HandEvaluatorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical challenger test suite for TableSeating and Game table player removal,
 * turn reconciliation, rapid multi-step sequential removals, heads-up transition,
 * single survivor termination, and extreme index/boundary safety.
 */
@Tag("unit")
@DisplayName("TableSeating Player Removal Empirical Challenge")
class TableSeatingPlayerRemovalEmpiricalChallengeTest {

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
            List<Player> initial = List.copyOf(game.getActivePlayers());
            Player p0 = initial.get(0);
            Player p1 = initial.get(1);
            Player p2 = initial.get(2);

            // In 3-player game: dealer=0 (p0), SB=1 (p1), BB=2 (p2), current=0 (p0 acts first UTG)
            assertEquals(0, game.getDealerPosition());
            assertEquals(1, game.getSmallBlindPosition());
            assertEquals(2, game.getBigBlindPosition());

            // p2 departs
            game.removePlayerFromGame(p2);
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
