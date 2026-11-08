package com.pokergame.model;

import com.pokergame.dto.internal.PlayerDecision;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.*;
import com.pokergame.exception.BadRequestException;
import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.service.HandEvaluatorService;
import java.util.ArrayList;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.mockito.Mockito.*;

import com.pokergame.enums.GamePhase;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;

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

    @Nested
    @DisplayName("PotAllocationEdgeCases")
    class PotAllocationEdgeCases {

        private static final String GAME_ID = "adversarial-challenge-game";

        // =========================================================================
        // 1. 10-Player Multi-Way All-Ins With Distinct Contribution Tiers
        // =========================================================================

        @Test
        void challenge_TenPlayerAllIn_DistinctTiers_DecomposesExactNinePotsAndOneRefund() {
            // 10 players with contributions 10, 20, 30, 40, 50, 60, 70, 80, 90, 100
            List<Player> players = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int totalPot = 0;

            for (int i = 1; i <= 10; i++) {
                String id = "p" + i;
                int amount = i * 10;
                players.add(allInPlayer(id, "Player_" + i, 0));
                contributions.put(id, amount);
                totalPot += amount;
            }
            assertEquals(550, totalPot);

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, 0, contributions, players, players, GAME_ID);

            // Verification of Invariant: Conservation
            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum + result.uncalledTotal(), "Conservation invariant failed");

            // Layer 10 (90 to 100): P10 is the sole contributor, so top 10 chips must be refunded as uncalled!
            assertEquals(10, result.uncalledTotal());
            assertEquals(10, result.uncalledByPlayer().get("p10"));

            // Exactly 9 contested allocations
            assertEquals(9, result.allocations().size());

            // Verify each layer's expected amount and eligible contenders:
            // Layer 1: 10 * 10 = 100, eligible p1..p10 (10 players)
            // Layer 2: 10 * 9 = 90, eligible p2..p10 (9 players)
            // Layer 3: 10 * 8 = 80, eligible p3..p10 (8 players)
            // Layer 4: 10 * 7 = 70, eligible p4..p10 (7 players)
            // Layer 5: 10 * 6 = 60, eligible p5..p10 (6 players)
            // Layer 6: 10 * 5 = 50, eligible p6..p10 (5 players)
            // Layer 7: 10 * 4 = 40, eligible p7..p10 (4 players)
            // Layer 8: 10 * 3 = 30, eligible p8..p10 (3 players)
            // Layer 9: 10 * 2 = 20, eligible p9..p10 (2 players)
            for (int i = 0; i < 9; i++) {
                PotCalculator.PotAllocation alloc = result.allocations().get(i);
                int expectedAmount = (10 - i) * 10;
                int expectedEligibleCount = 10 - i;
                assertEquals(expectedAmount, alloc.amount(), "Layer " + (i + 1) + " amount mismatch");
                assertEquals(expectedEligibleCount, alloc.eligiblePlayers().size(), "Layer " + (i + 1) + " eligible count mismatch");
                final String expectedId = "p" + (i + 1);
                assertTrue(alloc.eligiblePlayers().stream().anyMatch(p -> p.getPlayerId().equals(expectedId)));
            }

            // Test public getPotBreakdown() when action complete:
            // Contested breakdown should be [100, 90, 80, 70, 60, 50, 40, 30, 20]
            List<Integer> breakdown = PotCalculator.getPotBreakdown(
                    totalPot, 0, contributions, players, players, null, 100, GAME_ID);
            assertEquals(List.of(100, 90, 80, 70, 60, 50, 40, 30, 20), breakdown);

            // Test public getUncalledAmount()
            int uncalled = PotCalculator.getUncalledAmount(
                    totalPot, 0, contributions, players, players, null, 100, GAME_ID);
            assertEquals(10, uncalled);
        }

        // =========================================================================
        // 2. Folded Players Contributing at Various Intermediate Tiers
        // =========================================================================

        @Test
        void challenge_TenPlayers_AlternatingFolds_ActiveTopPlayer_GetsRefundAndRoutesDeadChips() {
            // 10 players: Even indices 2, 4, 6, 8 fold; 1, 3, 5, 7, 9, 10 stay active
            // P1(10), P2(20, fold), P3(30), P4(40, fold), P5(50),
            // P6(60, fold), P7(70), P8(80, fold), P9(90), P10(100, active)
            List<Player> allPlayers = new ArrayList<>();
            List<Player> contenders = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int totalPot = 0;

            for (int i = 1; i <= 10; i++) {
                String id = "p" + i;
                int amount = i * 10;
                // P10 is active, 2, 4, 6, 8 are folded
                boolean fold = (i % 2 == 0 && i != 10);
                Player p = fold ? foldedPlayer(id, "Folded_" + i, 0) : allInPlayer(id, "Active_" + i, 0);
                allPlayers.add(p);
                if (!fold) {
                    contenders.add(p);
                }
                contributions.put(id, amount);
                totalPot += amount;
            }
            assertEquals(550, totalPot);

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, 0, contributions, allPlayers, contenders, GAME_ID);

            // Pot conservation check
            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum + result.uncalledTotal());

            // Level 10 (90-100): P10 is active, alone -> 10 uncalled refunded to P10
            assertEquals(10, result.uncalledTotal());
            assertEquals(10, result.uncalledByPlayer().get("p10"));

            // Verify that NO folded player is present in ANY allocation's eligible players
            for (PotCalculator.PotAllocation alloc : result.allocations()) {
                for (Player eligible : alloc.eligiblePlayers()) {
                    assertFalse(eligible.getHasFolded(), "Folded player found in eligible contenders: " + eligible.getPlayerId());
                }
            }

            // Verify that dead chips from folded players are fully absorbed into contested pots
            assertEquals(540, allocatedSum);
        }

        @Test
        void challenge_TenPlayers_TopPlayerFolded_DeadExcessRoutedToSurvivorsWithoutRefund() {
            // 10 players: P10 folds after betting 100!
            // P1(10, active), P2..P10 all fold! Or alternating where P10 folds!
            List<Player> allPlayers = new ArrayList<>();
            List<Player> contenders = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int totalPot = 0;

            for (int i = 1; i <= 10; i++) {
                String id = "p" + i;
                int amount = i * 10;
                boolean fold = (i % 2 == 0); // P2, P4, P6, P8, P10 fold! P1, P3, P5, P7, P9 active
                Player p = fold ? foldedPlayer(id, "Folded_" + i, 0) : allInPlayer(id, "Active_" + i, 0);
                allPlayers.add(p);
                if (!fold) {
                    contenders.add(p);
                }
                contributions.put(id, amount);
                totalPot += amount;
            }
            assertEquals(550, totalPot);

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, 0, contributions, allPlayers, contenders, GAME_ID);

            // Invariant: Because top player (P10) folded, NO uncalled refund is issued!
            assertEquals(0, result.uncalledTotal());
            assertTrue(result.uncalledByPlayer().isEmpty());

            // Entire 550 chips must be contested among active players
            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum);

            // No folded player is eligible
            for (PotCalculator.PotAllocation alloc : result.allocations()) {
                for (Player eligible : alloc.eligiblePlayers()) {
                    assertFalse(eligible.getHasFolded());
                }
            }
        }

        @Test
        void challenge_DeepFoldedWhale_AllExcessDeadMoneyAbsorbedBySurvivors() {
            // P1 contributes 50 (active), P2 contributes 50 (active)
            // P3 is a whale who contributed 5000 chips and then FOLDED!
            Player p1 = player("p1", "Survivor1", 100);
            Player p2 = player("p2", "Survivor2", 100);
            Player p3 = foldedPlayer("p3", "FoldedWhale", 100);
            List<Player> allPlayers = List.of(p1, p2, p3);
            List<Player> contenders = List.of(p1, p2);
            Map<String, Integer> contributions = Map.of("p1", 50, "p2", 50, "p3", 5000);
            int pot = 5100;

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    pot, 0, contributions, allPlayers, contenders, GAME_ID);

            // The whale folded, so CANNOT receive an uncalled refund.
            assertEquals(0, result.uncalledTotal());
            assertTrue(result.uncalledByPlayer().isEmpty());

            // All 5,100 chips must be coalesced into a single allocation contested by [p1, p2]
            assertEquals(1, result.allocations().size());
            assertEquals(5100, result.allocations().get(0).amount());
            assertEquals(List.of(p1, p2), result.allocations().get(0).eligiblePlayers());
        }

        // =========================================================================
        // 3. Large Carry-Over Remainders and Large Chip Limits
        // =========================================================================

        @Test
        void challenge_LargeCarryOver_AddedToMainPotWithoutDistortingSidePots() {
            // Carry-over from previous hand: 10,000,000 chips
            int carryOver = 10_000_000;
            Player p1 = allInPlayer("p1", "Short", 0);
            Player p2 = player("p2", "Mid", 500);
            Player p3 = player("p3", "Deep", 500);
            List<Player> allPlayers = List.of(p1, p2, p3);
            List<Player> contenders = List.of(p1, p2, p3);

            Map<String, Integer> contributions = Map.of("p1", 100, "p2", 300, "p3", 300);
            int totalPot = carryOver + 700;

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, carryOver, contributions, allPlayers, contenders, GAME_ID);

            // Conservation check
            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(totalPot, allocatedSum + result.uncalledTotal());

            // Side pot: (300 - 100) * 2 = 400, eligible: [p2, p3]
            // Main pot: 100 * 3 + carryOver = 300 + 10,000,000 = 10,000,300, eligible: [p1, p2, p3]
            assertEquals(2, result.allocations().size());
            assertEquals(10_000_300, result.allocations().get(0).amount());
            assertEquals(List.of(p1, p2, p3), result.allocations().get(0).eligiblePlayers());
            assertEquals(400, result.allocations().get(1).amount());
            assertEquals(List.of(p2, p3), result.allocations().get(1).eligiblePlayers());
        }

        @Test
        void challenge_HugePotNearMaxInteger_NoSilentNegativeWrap() {
            // Handled amounts summing to 1.5 billion chips (fits in standard signed 32-bit int)
            int p1Contrib = 500_000_000;
            int p2Contrib = 1_000_000_000;
            int totalPot = 1_500_000_000;

            Player p1 = allInPlayer("p1", "Huge1", 0);
            Player p2 = allInPlayer("p2", "Huge2", 0);
            List<Player> allPlayers = List.of(p1, p2);

            Map<String, Integer> contributions = Map.of("p1", p1Contrib, "p2", p2Contrib);

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    totalPot, 0, contributions, allPlayers, allPlayers, GAME_ID);

            // Layer 1: 500M * 2 = 1,000,000,000 (positive, no overflow)
            assertEquals(1, result.allocations().size());
            assertEquals(1_000_000_000, result.allocations().get(0).amount());

            // Uncalled: 500,000,000 refunded to p2
            assertEquals(500_000_000, result.uncalledTotal());
            assertEquals(500_000_000, result.uncalledByPlayer().get("p2"));

            // Sum matches
            assertEquals(totalPot, result.allocations().get(0).amount() + result.uncalledTotal());
        }

        // =========================================================================
        // 4. Boundary Cases (Empty pot, Zero chips, All-in for 1 chip)
        // =========================================================================

        @Test
        void challenge_Boundary_EmptyPot_SafeEmptyReturns() {
            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    0, 0, Map.of(), List.of(), List.of(), GAME_ID);

            assertTrue(result.allocations().isEmpty());
            assertTrue(result.uncalledByPlayer().isEmpty());
            assertEquals(0, result.uncalledTotal());

            List<Integer> breakdown = PotCalculator.getPotBreakdown(
                    0, 0, Map.of(), List.of(), List.of(), null, 0, GAME_ID);
            assertTrue(breakdown.isEmpty());

            int uncalled = PotCalculator.getUncalledAmount(
                    0, 0, Map.of(), List.of(), List.of(), null, 0, GAME_ID);
            assertEquals(0, uncalled);
        }

        @Test
        void challenge_Boundary_AllInForOneChip_VersusLargeStack() {
            Player micro = allInPlayer("m1", "Micro", 0);
            Player deep = player("d1", "Deep", 999);
            List<Player> allPlayers = List.of(micro, deep);
            Map<String, Integer> contributions = Map.of("m1", 1, "d1", 500);
            int pot = 501;

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    pot, 0, contributions, allPlayers, allPlayers, GAME_ID);

            // Main pot: 1 * 2 = 2 chips contested by [micro, deep]
            assertEquals(1, result.allocations().size());
            assertEquals(2, result.allocations().get(0).amount());
            assertEquals(List.of(micro, deep), result.allocations().get(0).eligiblePlayers());

            // Uncalled: 499 chips refunded to deep
            assertEquals(499, result.uncalledTotal());
            assertEquals(499, result.uncalledByPlayer().get("d1"));

            assertEquals(pot, result.allocations().get(0).amount() + result.uncalledTotal());
        }

        @Test
        void challenge_Boundary_MultiplePlayersAllInForOneChip_TiedContribution() {
            // 5 players all-in for 1 chip each
            List<Player> players = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) {
                players.add(allInPlayer("p" + i, "P" + i, 0));
                contributions.put("p" + i, 1);
            }

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    5, 0, contributions, players, players, GAME_ID);

            assertEquals(1, result.allocations().size());
            assertEquals(5, result.allocations().get(0).amount());
            assertEquals(5, result.allocations().get(0).eligiblePlayers().size());
            assertEquals(0, result.uncalledTotal());
        }

        // =========================================================================
        // 5. Tier Collisions (Multiple players with identical contributions)
        // =========================================================================

        @Test
        void challenge_TierCollision_ClustersOfIdenticalStacks() {
            // Three tiers with multiple players at each tier:
            // Tier 50: 3 players (p1, p2, p3)
            // Tier 150: 3 players (p4, p5, p6)
            // Tier 300: 2 players (p7, p8)
            List<Player> players = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int pot = 0;

            for (int i = 1; i <= 3; i++) {
                players.add(allInPlayer("p" + i, "Tier1_" + i, 0));
                contributions.put("p" + i, 50);
                pot += 50;
            }
            for (int i = 4; i <= 6; i++) {
                players.add(allInPlayer("p" + i, "Tier2_" + i, 0));
                contributions.put("p" + i, 150);
                pot += 150;
            }
            for (int i = 7; i <= 8; i++) {
                players.add(allInPlayer("p" + i, "Tier3_" + i, 0));
                contributions.put("p" + i, 300);
                pot += 300;
            }
            // Total pot: 3*50 + 3*150 + 2*300 = 150 + 450 + 600 = 1200
            assertEquals(1200, pot);

            PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                    pot, 0, contributions, players, players, GAME_ID);

            // Since both top players contributed 300, no uncalled excess exists!
            assertEquals(0, result.uncalledTotal());

            // Exactly 3 distinct tiers -> exactly 3 allocation layers
            assertEquals(3, result.allocations().size());

            // Layer 1 (0 to 50): 8 players * 50 = 400
            assertEquals(400, result.allocations().get(0).amount());
            assertEquals(8, result.allocations().get(0).eligiblePlayers().size());

            // Layer 2 (50 to 150): 5 players * 100 = 500
            assertEquals(500, result.allocations().get(1).amount());
            assertEquals(5, result.allocations().get(1).eligiblePlayers().size());

            // Layer 3 (150 to 300): 2 players * 150 = 300
            assertEquals(300, result.allocations().get(2).amount());
            assertEquals(2, result.allocations().get(2).eligiblePlayers().size());

            // Conservation check
            assertEquals(1200, result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum());
        }

        // =========================================================================
        // 6. Property-Based Fuzzing / Stress Harness (1,000 Iterations)
        // =========================================================================

        @Test
        void challenge_PropertyBasedFuzzing_1000RandomizedScenarios_AllInvariantsHold() {
            Random rng = new Random(42_1337);

            for (int iteration = 0; iteration < 1000; iteration++) {
                int playerCount = 2 + rng.nextInt(9); // 2 to 10 players
                List<Player> allPlayers = new ArrayList<>();
                List<Player> contenders = new ArrayList<>();
                Map<String, Integer> contributions = new LinkedHashMap<>();
                int pot = 0;

                for (int p = 0; p < playerCount; p++) {
                    String id = "fuzz_p" + p;
                    int contribution = rng.nextInt(500) + 1; // 1 to 500 chips
                    // Ensure at least one player does not fold
                    boolean fold = (p > 0) && (rng.nextDouble() < 0.35);

                    Player player = fold
                            ? foldedPlayer(id, "Fold_" + p, 0)
                            : allInPlayer(id, "Active_" + p, 0);

                    allPlayers.add(player);
                    if (!fold) {
                        contenders.add(player);
                    }
                    contributions.put(id, contribution);
                    pot += contribution;
                }

                int carryOver = (rng.nextDouble() < 0.2) ? rng.nextInt(100) : 0;
                pot += carryOver;

                PotCalculator.PotComputationResult result = PotCalculator.computePotAllocationsAndUncalled(
                        pot, carryOver, contributions, allPlayers, contenders, "fuzz-" + iteration);

                // Invariant 1: Conservation of Chips
                int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
                int totalAccounted = allocatedSum + result.uncalledTotal();
                assertEquals(pot, totalAccounted,
                        "Iteration " + iteration + ": Pot conservation violated! pot=" + pot + " accounted=" + totalAccounted);

                // Invariant 2: Uncalled Sum Matches Map Values
                int uncalledMapSum = result.uncalledByPlayer().values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(result.uncalledTotal(), uncalledMapSum,
                        "Iteration " + iteration + ": uncalledTotal does not match map sum");

                // Invariant 3: No Folded Player in Uncalled Refund
                for (String refundedId : result.uncalledByPlayer().keySet()) {
                    Player refundedPlayer = allPlayers.stream().filter(pl -> pl.getPlayerId().equals(refundedId)).findFirst().orElseThrow();
                    assertFalse(refundedPlayer.getHasFolded(),
                            "Iteration " + iteration + ": Folded player received refund: " + refundedId);
                }

                // Invariant 4: No Folded Player in Contested Allocations
                for (PotCalculator.PotAllocation alloc : result.allocations()) {
                    assertTrue(alloc.amount() > 0, "Allocation amount must be strictly positive");
                    for (Player eligible : alloc.eligiblePlayers()) {
                        assertFalse(eligible.getHasFolded(),
                                "Iteration " + iteration + ": Folded player found in eligible contenders");
                        assertFalse(eligible.getIsOut(),
                                "Iteration " + iteration + ": Out player found in eligible contenders");
                    }
                }

                // Invariant 5: Monotonic Non-Increasing Eligibility Sets
                // As contribution tiers rise, the set of eligible players cannot expand
                Set<String> previousTierIds = null;
                for (PotCalculator.PotAllocation alloc : result.allocations()) {
                    Set<String> currentTierIds = alloc.eligiblePlayers().stream().map(Player::getPlayerId).collect(Collectors.toSet());
                    if (previousTierIds != null) {
                        assertTrue(previousTierIds.containsAll(currentTierIds),
                                "Iteration " + iteration + ": Eligibility set illegally expanded in higher tier! "
                                        + "prev=" + previousTierIds + " curr=" + currentTierIds);
                    }
                    previousTierIds = currentTierIds;
                }
            }
        }

        // =========================================================================
        // 7. Game Showdown Integration and Odd-Chip Carry-Over Lifecycle
        // =========================================================================

        @Test
        void challenge_GameShowdown_OddChipCarryOver_LifecycleContinuity() {
            com.pokergame.service.HandEvaluatorService handEvaluator = new com.pokergame.service.HandEvaluatorService();

            // 3 players: p1, p2, p3
            Player p1 = Player.restoreBuilder().name("P1").playerId("p1").chips(0).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(33).hasFolded(false)
                    .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
            Player p2 = Player.restoreBuilder().name("P2").playerId("p2").chips(0).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(33).hasFolded(false)
                    .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
            Player p3 = Player.restoreBuilder().name("P3").playerId("p3").chips(0).holeCards(List.of())
                    .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(34).hasFolded(false)
                    .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();

            // Pot = 33 + 33 + 34 = 100 chips. P1, P2 contributed 33, P3 contributed 34.
            // At level 33-34, P3 is the sole contributor -> 1 chip uncalled refund to P3!
            // Contested pot: 99 chips (33 * 3) between P1, P2, P3.
            Game game = Game.restoreBuilder().gameId("odd-chip-game")
                    .players(List.of(p1, p2, p3)).activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(List.of()).communityCards(List.of()).pot(100)
                    .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                    .currentHighestBet(34).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                    .smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("p1", 33, "p2", 33, "p3", 34))
                    .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                    .handEvaluator(handEvaluator).build();

            // Verify uncalled amount and pot breakdown before showdown
            assertEquals(1, game.getUncalledAmount());
            assertEquals(List.of(99), game.getPotBreakdown());

            // Now suppose pot was 101 with contributions 33, 33, 35 (2 uncalled to P3, 99 contested)
            // What if contested pot has an odd remainder? E.g. contributions 33, 34, 34 -> pot 101
            // Level 0-33: 33 * 3 = 99. Contested by P1, P2, P3.
            // Level 33-34: 1 * 2 = 2. Contested by P2, P3.
            // Total contested: 99 + 2 = 101 chips.
            Game oddGame = Game.restoreBuilder().gameId("odd-game-split")
                    .players(List.of(p1, p2, p3)).activePlayerIds(List.of("p1", "p2", "p3"))
                    .remainingDeck(List.of()).communityCards(List.of()).pot(101)
                    .dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(2)
                    .currentHighestBet(34).currentPhase(GamePhase.SHOWDOWN).gameOver(false)
                    .smallBlind(10).bigBlind(20)
                    .handContributions(Map.of("p1", 33, "p2", 34, "p3", 34))
                    .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                    .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                    .handEvaluator(handEvaluator).build();

            assertEquals(0, oddGame.getUncalledAmount());
            assertEquals(List.of(99, 2), oddGame.getPotBreakdown());
        }

        // =========================================================================
        // Helpers
        // =========================================================================

        private static Player player(String id, String name, int chips) {
            return Player.restoreBuilder()
                    .playerId(id).name(name).chips(chips).currentBet(0)
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

        private static Player foldedPlayer(String id, String name, int chips) {
            return Player.restoreBuilder()
                    .playerId(id).name(name).chips(chips).currentBet(0)
                    .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                    .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false)
                    .build();
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class AggregateScenarios extends GameScenarioFixture {

        @Test
        void foldedContributionBoundariesDoNotCreateOddChipsAcrossSameEligiblePot() {
                List<Card> highCard = List.of(new Card(Rank.ACE, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(highCard, HandRank.HIGH_CARD));
                when(mockHandEvaluator.isBetterHandOfSameRank(any(), any(), any())).thenReturn(false);
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(900).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(100).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player b = Player.restoreBuilder().name("B").playerId("b").chips(900).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(100).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f1 = Player.restoreBuilder().name("F1").playerId("f1").chips(995).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(5).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f2 = Player.restoreBuilder().name("F2").playerId("f2").chips(990).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player f3 = Player.restoreBuilder().name("F3").playerId("f3").chips(985).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(15).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game ordinary = Game.restoreBuilder().gameId("coalesce-ordinary-pots")
                                .players(List.of(a, b, f1, f2, f3)).activePlayerIds(List.of("a", "b", "f1", "f2", "f3"))
                                .remainingDeck(List.of()).communityCards(List.of()).pot(230).dealerPosition(0)
                                .smallBlindPosition(0).bigBlindPosition(1).currentPlayerPosition(0).currentHighestBet(100)
                                .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("a", 100, "b", 100, "f1", 5, "f2", 10, "f3", 15))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                ordinary.conductShowdown();

                assertEquals(1015, a.getChips());
                assertEquals(1015, b.getChips());
                assertEquals(0, ordinary.getPot());
        }

        @Test
        void deadUpperLayersJoinNearestEligibleLowerAllocation() {
                Player a = Player.restoreBuilder().name("A").playerId("a").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player b = Player.restoreBuilder().name("B").playerId("b").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player c = Player.restoreBuilder().name("C").playerId("c").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player d = Player.restoreBuilder().name("D").playerId("d").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(0).hasFolded(true)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("dead-upper-layer").players(List.of(a, b, c, d))
                                .activePlayerIds(List.of("a", "b", "c", "d")).remainingDeck(List.of())
                                .communityCards(List.of()).pot(550).dealerPosition(0).smallBlindPosition(1)
                                .bigBlindPosition(2).currentPlayerPosition(0).currentHighestBet(200)
                                .currentPhase(GamePhase.RIVER).gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("a", 50, "b", 100, "c", 200, "d", 200))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                List<Card> high = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> low = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(high, HandRank.ROYAL_FLUSH))
                                .thenReturn(new HandEvaluationResult(low, HandRank.ONE_PAIR));

                assertEquals(List.of(a, b), game.conductShowdown());
                assertEquals(200, a.getChips());
                assertEquals(350, b.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("a", 200, "b", 350), game.getLastSettlementResult().contestedAwards());
                assertTrue(game.getLastSettlementResult().refunds().isEmpty());
        }

        @Test
        void departedContributionStillDefinesSidePotAndCannotReceiveRefund() {
                Player shortPlayer = new Player("Short", "s", 0);
                Player midPlayer = new Player("Mid", "m", 150);
                Game game = Game.restoreBuilder().gameId("departed-contributor").players(List.of(shortPlayer, midPlayer))
                                .activePlayerIds(List.of("s", "m")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(350).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(0).currentHighestBet(150).currentPhase(GamePhase.RIVER)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("s", 50, "m", 150, "departed", 150))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();
                List<Card> high = List.of(new Card(Rank.ACE, Suit.SPADES));
                List<Card> low = List.of(new Card(Rank.KING, Suit.SPADES));
                when(mockHandEvaluator.getBestHand(any(), any()))
                                .thenReturn(new HandEvaluationResult(high, HandRank.ROYAL_FLUSH))
                                .thenReturn(new HandEvaluationResult(low, HandRank.ONE_PAIR));

                game.conductShowdown();

                assertEquals(150, shortPlayer.getChips());
                assertEquals(350, midPlayer.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("s", 150, "m", 200), game.getLastSettlementResult().contestedAwards());
                assertTrue(game.getLastSettlementResult().refunds().isEmpty());
        }

        @Test
        void deadBlindLayersPayRetainedZeroContributionContender() {
                Player contender = new Player("Contender", "a", 100);
                Player foldedSmallBlind = Player.restoreBuilder().name("Folded SB").playerId("b").chips(90)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(10)
                                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player foldedBigBlind = Player.restoreBuilder().name("Folded BB").playerId("c").chips(80)
                                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(20)
                                .hasFolded(true).allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game game = Game.restoreBuilder().gameId("zero-contribution-contender")
                                .players(List.of(contender, foldedSmallBlind, foldedBigBlind))
                                .activePlayerIds(List.of("a", "b", "c")).remainingDeck(List.of()).communityCards(List.of())
                                .pot(30).dealerPosition(0).smallBlindPosition(1).bigBlindPosition(2)
                                .currentPlayerPosition(0).currentHighestBet(20).currentPhase(GamePhase.PRE_FLOP)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("b", 10, "c", 20)).preHandCarryOver(0)
                                .readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                game.conductShowdown();

                assertEquals(130, contender.getChips());
                assertEquals(0, game.getPot());
                assertEquals(Map.of("a", 30), game.getLastSettlementResult().contestedAwards());
        }

        /**
         * Protects the expected behavior for get pot breakdown treats single player layer as uncalled.
         */
        @Test
        void testGetPotBreakdown_TreatsSinglePlayerLayerAsUncalled() {
                List<Player> playersWithShove = new ArrayList<>();
                playersWithShove.add(new Player("AllIn", "a1", 480));
                playersWithShove.add(new Player("SmallBlind", "s1", 1000));
                playersWithShove.add(new Player("BigBlind", "b1", 1000));

                Game shoveGame = new Game("uncalled-breakdown", playersWithShove, 10, 20, mockHandEvaluator);
                shoveGame.postBlinds();

                Player allInPlayer = shoveGame.getCurrentPlayer();
                shoveGame.processPlayerDecision(allInPlayer,
                                new PlayerDecision(PlayerAction.ALL_IN, 0, allInPlayer.getPlayerId()));

                assertEquals(List.of(510), shoveGame.getPotBreakdown());
                assertEquals(0, shoveGame.getUncalledAmount());
        }

        @Test
        void livePotKeepsEstablishedAllInCapAndPendingBetVisible() {
                Player shortStack = Player.restoreBuilder().name("Short").playerId("short").chips(0).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(50).hasFolded(false)
                                .allIn(true).out(false).disconnected(false).readyForNextHand(false).build();
                Player bettor = Player.restoreBuilder().name("Bettor").playerId("bettor").chips(800).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(250).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Player owing = Player.restoreBuilder().name("Owing").playerId("owing").chips(950).holeCards(List.of())
                                .bestHand(List.of()).handRank(HandRank.NO_HAND).currentBet(50).hasFolded(false)
                                .allIn(false).out(false).disconnected(false).readyForNextHand(false).build();
                Game live = Game.restoreBuilder().gameId("live-cap-pending-bet").players(List.of(shortStack, bettor, owing))
                                .activePlayerIds(List.of("short", "bettor", "owing")).remainingDeck(List.of())
                                .communityCards(List.of()).pot(350).dealerPosition(0).smallBlindPosition(0).bigBlindPosition(1)
                                .currentPlayerPosition(2).currentHighestBet(250).currentPhase(GamePhase.FLOP)
                                .gameOver(false).smallBlind(10).bigBlind(20)
                                .handContributions(Map.of("short", 50, "bettor", 250, "owing", 50))
                                .preHandCarryOver(0).readyCountdownActive(false).everyoneHasHadInitialTurn(true)
                                .actedPlayersInRound(Set.of()).scheduledTaskDeadlines(Map.of())
                                .handEvaluator(mockHandEvaluator).build();

                assertEquals(List.of(150, 200), live.getPotBreakdown());
                assertEquals(0, live.getUncalledAmount());
        }

        @Test
        void liveCapSlicesIncludeFoldedChipsBelowCapAndPreHandCarryOver() {
                Game live = livePotWithFoldedBlindAndCarryOver(370, 0);
                assertEquals(List.of(170, 200), live.getPotBreakdown());
                assertEquals(0, live.getUncalledAmount());

                Game carried = livePotWithFoldedBlindAndCarryOver(380, 10);
                assertEquals(List.of(180, 200), carried.getPotBreakdown(),
                                "Pre-hand carry-over belongs in the main live allocation");
                assertEquals(0, carried.getUncalledAmount());
        }
    }
}
