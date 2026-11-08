package com.pokergame.model;

import com.pokergame.enums.HandRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated unit tests for the package-private PotCalculator collaborator.
 * Verifies side-pot layering, uncalled-bet refunds, pot breakdowns,
 * dead-layer routing, eligibility coalescing, and contribution tracking.
 */
@Tag("unit")
@DisplayName("PotCalculator collaborator")
class PotCalculatorTest {

    private static final String GAME_ID = "pot-calc-test";

    // =========================================================================
    // 1. Contribution Tracking (trackContribution)
    // =========================================================================

    @Test
    void trackContribution_AddsToExistingLedger() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));
        Player player = player("p1", "Alice", 1000);

        PotCalculator.trackContribution(ledger, player, 25, GAME_ID);

        assertEquals(75, ledger.get("p1"));
    }

    @Test
    void trackContribution_InitializesNewEntry() {
        Map<String, Integer> ledger = new HashMap<>();
        Player player = player("p2", "Bob", 1000);

        PotCalculator.trackContribution(ledger, player, 50, GAME_ID);

        assertEquals(50, ledger.get("p2"));
    }

    @Test
    void trackContribution_IgnoresZeroOrNegativeAmount() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));
        Player player = player("p1", "Alice", 1000);

        PotCalculator.trackContribution(ledger, player, 0, GAME_ID);
        PotCalculator.trackContribution(ledger, player, -20, GAME_ID);

        assertEquals(50, ledger.get("p1"));
    }

    @Test
    void trackContribution_IgnoresNullPlayer() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));

        PotCalculator.trackContribution(ledger, null, 50, GAME_ID);

        assertEquals(1, ledger.size());
        assertEquals(50, ledger.get("p1"));
    }

    // =========================================================================
    // 2. Pot Decomposition (computePotAllocationsAndUncalled)
    // =========================================================================

    @Test
    void computePotAllocations_SimpleHeadsUp_ReturnsSingleAllocationNoUncalled() {
        Player p1 = player("p1", "Alice", 900);
        Player p2 = player("p2", "Bob", 900);
        List<Player> allPlayers = List.of(p1, p2);
        List<Player> contenders = List.of(p1, p2);
        Map<String, Integer> contributions = Map.of("p1", 100, "p2", 100);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                200, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(200, result.allocations().get(0).amount());
        assertEquals(List.of(p1, p2), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertTrue(result.uncalledByPlayer().isEmpty());
    }

    @Test
    void computePotAllocations_ShortStackAllIn_CreatesMainAndSidePots() {
        Player shortStack = allInPlayer("s1", "Short", 0);
        Player midStack = player("m1", "Mid", 150);
        Player deepStack = player("d1", "Deep", 150);
        List<Player> allPlayers = List.of(shortStack, midStack, deepStack);
        List<Player> contenders = List.of(shortStack, midStack, deepStack);
        Map<String, Integer> contributions = Map.of("s1", 50, "m1", 150, "d1", 150);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                350, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        // Main pot: 50 * 3 = 150, eligible: Short, Mid, Deep
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(shortStack, midStack, deepStack), result.allocations().get(0).eligiblePlayers());
        // Side pot: 100 * 2 = 200, eligible: Mid, Deep
        assertEquals(200, result.allocations().get(1).amount());
        assertEquals(List.of(midStack, deepStack), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_MultipleAllInStacks_CreatesMultipleSidePots() {
        Player p1 = allInPlayer("p1", "Shortest", 0);
        Player p2 = allInPlayer("p2", "Short", 0);
        Player p3 = player("p3", "Mid", 100);
        Player p4 = player("p4", "Deep", 100);
        List<Player> allPlayers = List.of(p1, p2, p3, p4);
        List<Player> contenders = List.of(p1, p2, p3, p4);
        Map<String, Integer> contributions = Map.of("p1", 50, "p2", 100, "p3", 200, "p4", 200);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                550, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(3, result.allocations().size());
        assertEquals(200, result.allocations().get(0).amount()); // 50 * 4
        assertEquals(List.of(p1, p2, p3, p4), result.allocations().get(0).eligiblePlayers());
        assertEquals(150, result.allocations().get(1).amount()); // 50 * 3
        assertEquals(List.of(p2, p3, p4), result.allocations().get(1).eligiblePlayers());
        assertEquals(200, result.allocations().get(2).amount()); // 100 * 2
        assertEquals(List.of(p3, p4), result.allocations().get(2).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_UncalledShove_CalculatesRefundForOwner() {
        Player shover = allInPlayer("a1", "AllIn", 0);
        Player sb = player("s1", "SmallBlind", 990);
        Player bb = player("b1", "BigBlind", 980);
        List<Player> allPlayers = List.of(shover, sb, bb);
        List<Player> contenders = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                510, 0, contributions, allPlayers, contenders, GAME_ID);

        // Contested allocations: Layer 1 (10*3=30), Layer 2 (10*2=20)
        assertEquals(2, result.allocations().size());
        assertEquals(30, result.allocations().get(0).amount());
        assertEquals(List.of(shover, sb, bb), result.allocations().get(0).eligiblePlayers());
        assertEquals(20, result.allocations().get(1).amount());
        assertEquals(List.of(shover, bb), result.allocations().get(1).eligiblePlayers());

        // Uncalled refund: 480 - 20 = 460 refunded to a1
        assertEquals(460, result.uncalledTotal());
        assertEquals(460, result.uncalledByPlayer().get("a1"));
    }

    @Test
    void computePotAllocations_DeadUpperLayers_RoutedToNearestLowerEligibleAllocation() {
        Player a = player("a", "A", 0);
        Player b = player("b", "B", 0);
        Player c = foldedPlayer("c", "C", 0);
        Player d = foldedPlayer("d", "D", 0);
        List<Player> allPlayers = List.of(a, b, c, d);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 100, "c", 200, "d", 200);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                550, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        // Layer 1: 50 * 4 = 200, eligible: [a, b]
        assertEquals(200, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
        // Layer 2: 50 * 3 = 150, dead layer (100 * 2 = 200) routed down to Layer 2 -> 150 + 200 = 350
        assertEquals(350, result.allocations().get(1).amount());
        assertEquals(List.of(b), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_DeadLayersWithZeroContributionContender_RoutedToContender() {
        Player contender = player("a", "Contender", 100);
        Player foldedSB = foldedPlayer("b", "Folded SB", 90);
        Player foldedBB = foldedPlayer("c", "Folded BB", 80);
        List<Player> allPlayers = List.of(contender, foldedSB, foldedBB);
        List<Player> contenders = List.of(contender);
        Map<String, Integer> contributions = Map.of("b", 10, "c", 20);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                30, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(30, result.allocations().get(0).amount());
        assertEquals(List.of(contender), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_DepartedContributor_RetainsSidePotWithoutRefund() {
        Player shortPlayer = player("s", "Short", 0);
        Player midPlayer = player("m", "Mid", 150);
        List<Player> allPlayers = List.of(shortPlayer, midPlayer);
        List<Player> contenders = List.of(shortPlayer, midPlayer);
        Map<String, Integer> contributions = Map.of("s", 50, "m", 150, "departed", 150);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                350, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(shortPlayer, midPlayer), result.allocations().get(0).eligiblePlayers());
        assertEquals(200, result.allocations().get(1).amount());
        assertEquals(List.of(midPlayer), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_FoldedContributionBoundaries_CoalesceSameEligibility() {
        Player a = player("a", "A", 900);
        Player b = player("b", "B", 900);
        Player f1 = foldedPlayer("f1", "F1", 995);
        Player f2 = foldedPlayer("f2", "F2", 990);
        Player f3 = foldedPlayer("f3", "F3", 985);
        List<Player> allPlayers = List.of(a, b, f1, f2, f3);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 100, "b", 100, "f1", 5, "f2", 10, "f3", 15);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                230, 0, contributions, allPlayers, contenders, GAME_ID);

        // Slices coalesce into single allocation so odd-chip remainder does not fragment across identical contenders
        assertEquals(1, result.allocations().size());
        assertEquals(230, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void computePotAllocations_PreHandCarryOver_AddedToMainAllocation() {
        Player a = player("a", "A", 950);
        Player b = player("b", "B", 950);
        List<Player> allPlayers = List.of(a, b);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 50);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                110, 10, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(110, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
    }

    @Test
    void computePotAllocations_PreHandCarryOverWithZeroContributions_CreatesAllocation() {
        Player a = player("a", "A", 1000);
        Player b = player("b", "B", 1000);
        List<Player> allPlayers = List.of(a, b);
        List<Player> contenders = List.of(a, b);

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                50, 50, Map.of(), allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(50, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
    }

    @Test
    void computePotAllocations_LedgerMismatch_ThrowsIllegalStateException() {
        Player a = player("a", "A", 950);
        Player b = player("b", "B", 950);
        List<Player> allPlayers = List.of(a, b);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 50);

        // Pot is 200 but ledger has only 100 and carry-over is 0
        assertThrows(IllegalStateException.class, () ->
                PotCalculator.computePotAllocationsAndUncalled(200, 0, contributions, allPlayers, contenders, GAME_ID));
    }

    // =========================================================================
    // 3. Pot Breakdown Queries (getPotBreakdown)
    // =========================================================================

    @Test
    void getPotBreakdown_EmptyPot_ReturnsEmptyList() {
        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                0, null, List.of(), 0, Map.of(), 0, List.of(), GAME_ID);

        assertTrue(breakdown.isEmpty());
    }

    @Test
    void getPotBreakdown_SettledGame_ReturnsTotalPot() {
        HandSettlementResult settled = new HandSettlementResult(Map.of(), Map.of(), Set.of(), 1, true);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                100, settled, List.of(), 0, Map.of(), 0, List.of(), GAME_ID);

        assertEquals(List.of(100), breakdown);
    }

    @Test
    void getPotBreakdown_LiveNoAllIn_ReturnsTotalPot() {
        Player p1 = playerWithBet("p1", "Alice", 1000, 50);
        Player p2 = playerWithBet("p2", "Bob", 1000, 50);
        List<Player> active = List.of(p1, p2);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                100, null, active, 50, Map.of("p1", 50, "p2", 50), 0, active, GAME_ID);

        assertEquals(List.of(100), breakdown);
    }

    @Test
    void getPotBreakdown_LiveWithAllInPendingCall_ProjectsLiveCaps() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player bettor = playerWithBet("bettor", "Bettor", 800, 250);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        List<Player> active = List.of(shortStack, bettor, owing);
        Map<String, Integer> contributions = Map.of("short", 50, "bettor", 250, "owing", 50);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                350, null, active, 250, contributions, 0, active, GAME_ID);

        assertEquals(List.of(150, 200), breakdown);
    }

    @Test
    void getPotBreakdown_LiveWithOwingBelowCap_ReturnsTotalPot() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = playerWithBet("s1", "SB", 990, 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        // SB and BB owe call below 480 cap -> entire pot remains pending single layer
        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                510, null, active, 480, contributions, 0, active, GAME_ID);

        assertEquals(List.of(510), breakdown);
    }

    @Test
    void getPotBreakdown_LiveWithFoldedBlindAndCarryOver_IncludesInMainAllocation() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player bettor = playerWithBet("bettor", "Bettor", 800, 250);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        Player folded = foldedPlayerWithBet("folded", "Folded", 980, 20);
        List<Player> active = List.of(shortStack, bettor, owing, folded);
        Map<String, Integer> contributions = Map.of("short", 50, "bettor", 250, "owing", 50, "folded", 20);

        // Pot 380, carry-over 10
        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                380, null, active, 250, contributions, 10, active, GAME_ID);

        assertEquals(List.of(180, 200), breakdown);
    }

    @Test
    void getPotBreakdown_ActionCompleteWithAllIn_ReturnsContestedAllocations() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player midStack = playerWithBet("mid", "Mid", 850, 150);
        Player deepStack = playerWithBet("deep", "Deep", 850, 150);
        List<Player> active = List.of(shortStack, midStack, deepStack);
        Map<String, Integer> contributions = Map.of("short", 50, "mid", 150, "deep", 150);

        // Current highest bet is 150, all active non-all-in players have matched 150 (no owing calls)
        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                350, null, active, 150, contributions, 0, active, GAME_ID);

        assertEquals(List.of(150, 200), breakdown);
    }

    @Test
    void getPotBreakdown_ActionCompleteWithUncalledShove_ExcludesUncalledLayer() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = allInPlayerWithBet("s1", "SB", 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        // Action is complete (currentHighestBet = 20, SB is all-in for 10, BB matched 20)
        List<Integer> breakdown = PotCalculator.getPotBreakdown(
                510, null, active, 20, contributions, 0, active, GAME_ID);

        // Contested breakdown only: 30 and 20; the 460 uncalled is excluded from contested breakdown
        assertEquals(List.of(30, 20), breakdown);
    }

    // =========================================================================
    // 4. Uncalled Amount Queries (getUncalledAmount)
    // =========================================================================

    @Test
    void getUncalledAmount_EmptyPot_ReturnsZero() {
        assertEquals(0, PotCalculator.getUncalledAmount(
                0, null, List.of(), 0, Map.of(), 0, List.of(), GAME_ID));
    }

    @Test
    void getUncalledAmount_SettledGame_ReturnsZero() {
        HandSettlementResult settled = new HandSettlementResult(Map.of(), Map.of(), Set.of(), 1, true);

        assertEquals(0, PotCalculator.getUncalledAmount(
                100, settled, List.of(), 0, Map.of(), 0, List.of(), GAME_ID));
    }

    @Test
    void getUncalledAmount_LiveNoAllIn_ReturnsZero() {
        Player p1 = playerWithBet("p1", "Alice", 1000, 50);
        Player p2 = playerWithBet("p2", "Bob", 1000, 50);

        assertEquals(0, PotCalculator.getUncalledAmount(
                100, null, List.of(p1, p2), 50, Map.of("p1", 50, "p2", 50), 0, List.of(p1, p2), GAME_ID));
    }

    @Test
    void getUncalledAmount_LiveWithPendingCall_ReturnsZero() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player bettor = playerWithBet("bettor", "Bettor", 800, 250);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        List<Player> active = List.of(shortStack, bettor, owing);

        assertEquals(0, PotCalculator.getUncalledAmount(
                350, null, active, 250, Map.of("short", 50, "bettor", 250, "owing", 50), 0, active, GAME_ID));
    }

    @Test
    void getUncalledAmount_ActionCompleteWithUncalledShove_ReturnsUncalledTotal() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = allInPlayerWithBet("s1", "SB", 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        // Action complete with currentHighestBet = 20 (SB is all-in for 10, BB matched 20)
        assertEquals(460, PotCalculator.getUncalledAmount(
                510, null, active, 20, contributions, 0, active, GAME_ID));
    }

    // =========================================================================
    // 5. Algorithm Helpers
    // =========================================================================

    @Test
    void routeDeadLayerToNearestEligible_RollsIntoPreviousAllocation() {
        Player p1 = player("p1", "Alice", 1000);
        List<PotCalculator.PotAllocation> allocations = new ArrayList<>();
        allocations.add(new PotCalculator.PotAllocation(100, List.of(p1)));

        PotCalculator.routeDeadLayerToNearestEligible(allocations, 50, List.of());

        assertEquals(1, allocations.size());
        assertEquals(150, allocations.get(0).amount());
        assertEquals(List.of(p1), allocations.get(0).eligiblePlayers());
    }

    @Test
    void routeDeadLayerToNearestEligible_PrependToContendersWhenNoAllocations() {
        Player p1 = player("p1", "Alice", 1000);
        List<PotCalculator.PotAllocation> allocations = new ArrayList<>();

        PotCalculator.routeDeadLayerToNearestEligible(allocations, 50, List.of(p1));

        assertEquals(1, allocations.size());
        assertEquals(50, allocations.get(0).amount());
        assertEquals(List.of(p1), allocations.get(0).eligiblePlayers());
    }

    @Test
    void appendAllocationCoalescingSameEligibility_MergesMatchingContenders() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        List<PotCalculator.PotAllocation> allocations = new ArrayList<>();
        allocations.add(new PotCalculator.PotAllocation(100, List.of(p1, p2)));

        PotCalculator.appendAllocationCoalescingSameEligibility(allocations, 50, List.of(p2, p1));

        assertEquals(1, allocations.size());
        assertEquals(150, allocations.get(0).amount());
    }

    @Test
    void appendAllocationCoalescingSameEligibility_AppendsWhenContendersDiffer() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        List<PotCalculator.PotAllocation> allocations = new ArrayList<>();
        allocations.add(new PotCalculator.PotAllocation(100, List.of(p1, p2)));

        PotCalculator.appendAllocationCoalescingSameEligibility(allocations, 50, List.of(p1));

        assertEquals(2, allocations.size());
        assertEquals(100, allocations.get(0).amount());
        assertEquals(50, allocations.get(1).amount());
    }

    @Test
    void allocationSummary_FormatsCorrectly() {
        Player p1 = player("p1", "Alice", 1000);
        List<PotCalculator.PotAllocation> allocations = List.of(
                new PotCalculator.PotAllocation(100, List.of(p1)));

        List<String> summary = PotCalculator.allocationSummary(allocations);

        assertEquals(1, summary.size());
        assertTrue(summary.get(0).contains("pot[0] amount=100"));
        assertTrue(summary.get(0).contains("Alice"));
    }

    @Test
    void playerNames_ExtractsNamesInOrder() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);

        List<String> names = PotCalculator.playerNames(List.of(p1, p2));

        assertEquals(List.of("Alice", "Bob"), names);
    }

    // =========================================================================
    // 6. Adversarial Stress & Edge Case Challenges
    // =========================================================================

    @Test
    void challenge_DeadLayerRouting_CascadingMultiLevelFoldsRollsDown() {
        Player a = allInPlayer("a", "A", 0);
        Player e = allInPlayer("e", "E", 0);
        Player b = foldedPlayer("b", "B", 0);
        Player c = foldedPlayer("c", "C", 0);
        Player d = foldedPlayer("d", "D", 0);
        List<Player> allPlayers = List.of(a, b, c, d, e);
        List<Player> contenders = List.of(a, e);
        Map<String, Integer> contributions = Map.of("a", 25, "e", 25, "b", 50, "c", 100, "d", 200);
        int totalPot = 25 + 25 + 50 + 100 + 200;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(400, result.allocations().get(0).amount());
        assertEquals(List.of(a, e), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertTrue(result.uncalledByPlayer().isEmpty());
    }

    @Test
    void challenge_DeadLayerRouting_IntermediateFoldedGapPreservesSidePot() {
        Player a = allInPlayer("a", "A", 0);
        Player b = player("b", "B", 100);
        Player c = foldedPlayer("c", "C", 0);
        Player d = foldedPlayer("d", "D", 0);
        List<Player> allPlayers = List.of(a, b, c, d);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 150, "c", 100, "d", 250);
        int totalPot = 50 + 150 + 100 + 250;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(200, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
        assertEquals(350, result.allocations().get(1).amount());
        assertEquals(List.of(b), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
    }

    @Test
    void challenge_DeadLayerRouting_SoleFoldedPlayerTopLayerNotRefunded() {
        Player a = player("a", "A", 900);
        Player b = player("b", "B", 900);
        Player c = foldedPlayer("c", "C", 700);
        List<Player> allPlayers = List.of(a, b, c);
        List<Player> contenders = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 100, "b", 100, "c", 300);
        int totalPot = 500;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, allPlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(500, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertTrue(result.uncalledByPlayer().isEmpty());
    }

    @Test
    void challenge_LayerCoalescing_OrderIndependenceAndSubsets() {
        Player p1 = player("p1", "Alice", 1000);
        Player p2 = player("p2", "Bob", 1000);
        Player p3 = player("p3", "Charlie", 1000);

        List<PotCalculator.PotAllocation> allocations = new ArrayList<>();
        allocations.add(new PotCalculator.PotAllocation(100, List.of(p1, p2, p3)));

        PotCalculator.appendAllocationCoalescingSameEligibility(allocations, 50, List.of(p3, p1, p2));
        assertEquals(1, allocations.size());
        assertEquals(150, allocations.get(0).amount());

        PotCalculator.appendAllocationCoalescingSameEligibility(allocations, 75, List.of(p1, p2));
        assertEquals(2, allocations.size());
        assertEquals(150, allocations.get(0).amount());
        assertEquals(75, allocations.get(1).amount());
        assertEquals(List.of(p1, p2), allocations.get(1).eligiblePlayers());
    }

    @Test
    void challenge_LiveWagerProjection_ActionablePlayerOwingCall_SingleAllInCap() {
        Player allIn = allInPlayerWithBet("short", "Short", 100);
        Player owing = playerWithBet("owing", "Owing", 900, 100);
        Player bettor = playerWithBet("bettor", "Bettor", 700, 300);
        List<Player> active = List.of(allIn, owing, bettor);
        Map<String, Integer> contributions = Map.of("short", 100, "owing", 100, "bettor", 300);
        int totalPot = 500;

        int uncalled = PotCalculator.getUncalledAmount(totalPot, 0, contributions, active, active, null, 300, GAME_ID);
        assertEquals(0, uncalled);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(totalPot, 0, contributions, active, active, null, 300, GAME_ID);
        assertEquals(List.of(300, 200), breakdown);
        assertEquals(totalPot, breakdown.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void challenge_LiveWagerProjection_ActionablePlayerOwingCall_MultipleAllInCaps() {
        Player p1 = allInPlayerWithBet("p1", "Shortest", 50);
        Player p2 = allInPlayerWithBet("p2", "Short", 100);
        Player owing = playerWithBet("owing", "Owing", 900, 100);
        Player bettor = playerWithBet("bettor", "Bettor", 700, 300);
        List<Player> active = List.of(p1, p2, owing, bettor);
        Map<String, Integer> contributions = Map.of("p1", 50, "p2", 100, "owing", 100, "bettor", 300);
        int totalPot = 550;

        int uncalled = PotCalculator.getUncalledAmount(totalPot, 0, contributions, active, active, null, 300, GAME_ID);
        assertEquals(0, uncalled);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(totalPot, 0, contributions, active, active, null, 300, GAME_ID);
        assertEquals(List.of(200, 150, 200), breakdown);
        assertEquals(totalPot, breakdown.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void challenge_LiveWagerProjection_OwingBelowCap_CollapsesToSinglePot() {
        Player allIn = allInPlayerWithBet("allIn", "AllIn", 500);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        List<Player> active = List.of(allIn, owing);
        Map<String, Integer> contributions = Map.of("allIn", 500, "owing", 50);
        int totalPot = 550;

        int uncalled = PotCalculator.getUncalledAmount(totalPot, 0, contributions, active, active, null, 500, GAME_ID);
        assertEquals(0, uncalled);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(totalPot, 0, contributions, active, active, null, 500, GAME_ID);
        assertEquals(List.of(550), breakdown);
    }

    @Test
    void challenge_DepartedPlayer_DefinesSidePotWithActivePlayer_NoRefund() {
        Player activeA = player("a", "ActiveShort", 0);
        Player activeB = player("b", "ActiveMid", 150);
        List<Player> tablePlayers = List.of(activeA, activeB);
        List<Player> contenders = List.of(activeA, activeB);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 150, "departed", 150);
        int totalPot = 350;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, tablePlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(activeA, activeB), result.allocations().get(0).eligiblePlayers());
        assertEquals(200, result.allocations().get(1).amount());
        assertEquals(List.of(activeB), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertFalse(result.uncalledByPlayer().containsKey("departed"));
    }

    @Test
    void challenge_DepartedPlayer_SoleTopLayerRoutedDownWithoutRefund() {
        Player activeA = player("a", "ActiveA", 50);
        Player activeB = player("b", "ActiveB", 100);
        List<Player> tablePlayers = List.of(activeA, activeB);
        List<Player> contenders = List.of(activeA, activeB);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 100, "departed", 250);
        int totalPot = 400;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, tablePlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(activeA, activeB), result.allocations().get(0).eligiblePlayers());
        assertEquals(250, result.allocations().get(1).amount());
        assertEquals(List.of(activeB), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertFalse(result.uncalledByPlayer().containsKey("departed"));
    }

    @Test
    void challenge_DepartedPlayer_MarkedOutInTable_NoRefund() {
        Player activeA = player("a", "ActiveA", 100);
        Player outPlayer = outPlayer("outP", "OutP", 0);
        List<Player> tablePlayers = List.of(activeA, outPlayer);
        List<Player> contenders = List.of(activeA);
        Map<String, Integer> contributions = Map.of("a", 100, "outP", 300);
        int totalPot = 400;

        PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                totalPot, 0, contributions, tablePlayers, contenders, GAME_ID);

        assertEquals(1, result.allocations().size());
        assertEquals(400, result.allocations().get(0).amount());
        assertEquals(List.of(activeA), result.allocations().get(0).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertFalse(result.uncalledByPlayer().containsKey("outP"));
    }

    @Test
    void challenge_FuzzInvariants_100PseudoRandomScenarios() {
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            int playerCount = 2 + rng.nextInt(6);
            List<Player> allPlayers = new ArrayList<>();
            List<Player> activePlayers = new ArrayList<>();
            Map<String, Integer> contributions = new HashMap<>();
            int totalPot = 0;
            int carryOver = rng.nextInt(5) == 0 ? rng.nextInt(100) : 0;
            totalPot += carryOver;

            for (int p = 0; p < playerCount; p++) {
                String id = "p" + p;
                int contribution = 10 + rng.nextInt(500);
                boolean folded = rng.nextDouble() < 0.3;
                boolean allIn = !folded && rng.nextDouble() < 0.4;
                boolean out = rng.nextDouble() < 0.1;
                boolean departed = rng.nextDouble() < 0.1;

                contributions.put(id, contribution);
                totalPot += contribution;

                if (!departed) {
                    Player pl = Player.restoreBuilder()
                            .playerId(id).name("P" + p).chips(allIn ? 0 : 500).currentBet(0)
                            .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                            .hasFolded(folded).allIn(allIn).out(out).disconnected(false).readyForNextHand(false)
                            .build();
                    allPlayers.add(pl);
                    if (!folded && !out) {
                        activePlayers.add(pl);
                    }
                }
            }

            if (activePlayers.isEmpty()) {
                Player survivor = Player.restoreBuilder()
                        .playerId("survivor").name("Survivor").chips(100).currentBet(0)
                        .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                        .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                        .build();
                allPlayers.add(survivor);
                activePlayers.add(survivor);
                contributions.put("survivor", 10);
                totalPot += 10;
            }

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, carryOver, contributions, activePlayers, allPlayers, GAME_ID);

            int allocated = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(totalPot, allocated + result.uncalledTotal(),
                    "Total pot must equal allocated + uncalled for run " + i);

            int uncalledMapSum = result.uncalledByPlayer().values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(result.uncalledTotal(), uncalledMapSum,
                    "Uncalled total must match uncalled map sum for run " + i);

            for (PotCalculator.PotAllocation alloc : result.allocations()) {
                assertTrue(alloc.amount() > 0, "Pot layer must be positive for run " + i);
                for (Player el : alloc.eligiblePlayers()) {
                    assertFalse(el.getHasFolded(), "Eligible player must not be folded");
                    assertFalse(el.getIsOut(), "Eligible player must not be out");
                }
            }

            for (String uncalledId : result.uncalledByPlayer().keySet()) {
                assertTrue(allPlayers.stream().anyMatch(p -> p.getPlayerId().equals(uncalledId)
                        && !p.getHasFolded() && !p.getIsOut()), "Uncalled recipient must be active seated player");
            }

            List<Integer> breakdown = PotCalculator.getPotBreakdown(
                    totalPot, carryOver, contributions, activePlayers, allPlayers, null, 0, GAME_ID);
            int uncalledQuery = PotCalculator.getUncalledAmount(
                    totalPot, carryOver, contributions, activePlayers, allPlayers, null, 0, GAME_ID);
            int breakdownSum = breakdown.stream().mapToInt(Integer::intValue).sum();
            assertEquals(totalPot, breakdownSum + uncalledQuery,
                    "Breakdown sum + uncalled query must equal total pot for run " + i);
        }
    }

    // =========================================================================
    // Test Fixture Builders
    // =========================================================================

    private static Player outPlayer(String id, String name, int chips) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(true).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player player(String id, String name, int chips) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player playerWithBet(String id, String name, int chips, int bet) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(bet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player allInPlayer(String id, String name, int chips) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(true).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player allInPlayerWithBet(String id, String name, int bet) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(0).currentBet(bet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(true).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player foldedPlayer(String id, String name, int chips) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }

    private static Player foldedPlayerWithBet(String id, String name, int chips, int bet) {
        return Player.restoreBuilder()
                .playerId(id).name(name).chips(chips).currentBet(bet)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                .build();
    }
}
