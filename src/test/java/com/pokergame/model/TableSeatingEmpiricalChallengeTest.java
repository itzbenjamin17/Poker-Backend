package com.pokergame.model;

import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical stress tests and property-based challenge suite for {@link TableSeating}.
 * Designed by Empirical Challenger to rigorously stress-test:
 * 1. Exhaustive table sizes from 2 to 10 players across all dealer positions.
 * 2. Cyclic dealer advancement permutations across N consecutive hands (no skipped players, full cycle).
 * 3. Progressive eliminations down to heads-up (2 players) and single survivor (1 player).
 * 4. Heads-up vs multi-way structural invariant transitions (dealer is SB in HU; SB=(dealer+1)%N in 3+).
 * 5. Departed dealer anchor stability across multiple consecutive eliminations and wrap-arounds.
 * 6. Actionable seat selection after dealer anchor under all combinations of folded/all-in/eliminated seats.
 * 7. Next player turn navigation stress under exhaustive folded/all-in bitmasks and boundary values.
 * 8. Player removal reconciliation preserving identity of current actor and valid index clamping.
 * 9. Monte Carlo randomized tournament simulations (200 tournaments) verifying invariant preservation.
 */
@Tag("unit")
@DisplayName("TableSeating Empirical Challenge")
class TableSeatingEmpiricalChallengeTest {

    // =========================================================================
    // Helper Factories
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
            int currentDealer = startSeat;

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
                currentDealer = result.dealerPosition();
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
