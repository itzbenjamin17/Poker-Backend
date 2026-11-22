package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.HandRank;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the package-private {@link PotCalculator} collaborator.
 */
@Tag("unit")
@DisplayName("PotCalculator collaborator")
class PotCalculatorTest {

    private static final String GAME_ID = "pot-calc-test";

    @Test
    void trackContribution_AddsToExistingLedger() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));
        Player player = player("p1", "Alice", 1000);

        PotCalculator.trackContribution(ledger, player, 25);

        assertEquals(75, ledger.get("p1"));
    }

    @Test
    void trackContribution_InitializesNewEntry() {
        Map<String, Integer> ledger = new HashMap<>();
        Player player = player("p2", "Bob", 1000);

        PotCalculator.trackContribution(ledger, player, 50);

        assertEquals(50, ledger.get("p2"));
    }

    @Test
    void trackContribution_IgnoresZeroOrNegativeAmount() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));
        Player player = player("p1", "Alice", 1000);

        PotCalculator.trackContribution(ledger, player, 0);
        PotCalculator.trackContribution(ledger, player, -20);

        assertEquals(50, ledger.get("p1"));
    }

    @Test
    void trackContribution_IgnoresNullPlayer() {
        Map<String, Integer> ledger = new HashMap<>(Map.of("p1", 50));

        PotCalculator.trackContribution(ledger, null, 50);

        assertEquals(1, ledger.size());
        assertEquals(50, ledger.get("p1"));
    }

    @Test
    void computePotAllocations_SimpleHeadsUp_ReturnsSingleAllocationNoUncalled() {
        Player p1 = player("p1", "Alice", 900);
        Player p2 = player("p2", "Bob", 900);
        List<Player> allPlayers = List.of(p1, p2);
        List<Player> contenders = List.of(p1, p2);
        Map<String, Integer> contributions = Map.of("p1", 100, "p2", 100);

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                200, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                350, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(shortStack, midStack, deepStack), result.allocations().get(0).eligiblePlayers());
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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                550, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

        assertEquals(3, result.allocations().size());
        assertEquals(200, result.allocations().get(0).amount());
        assertEquals(List.of(p1, p2, p3, p4), result.allocations().get(0).eligiblePlayers());
        assertEquals(150, result.allocations().get(1).amount());
        assertEquals(List.of(p2, p3, p4), result.allocations().get(1).eligiblePlayers());
        assertEquals(200, result.allocations().get(2).amount());
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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                510, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(30, result.allocations().get(0).amount());
        assertEquals(List.of(shover, sb, bb), result.allocations().get(0).eligiblePlayers());
        assertEquals(20, result.allocations().get(1).amount());
        assertEquals(List.of(shover, bb), result.allocations().get(1).eligiblePlayers());

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                550, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

        assertEquals(200, result.allocations().get(0).amount());
        assertEquals(List.of(a, b), result.allocations().get(0).eligiblePlayers());
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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                30, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                350, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(shortPlayer, midPlayer), result.allocations().get(0).eligiblePlayers());
        assertEquals(200, result.allocations().get(1).amount());
        assertEquals(List.of(midPlayer), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertFalse(result.uncalledByPlayer().containsKey("departed"));
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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                230, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                110, 10, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                50, 50, Map.of(), contenders, allPlayers, contenders, GAME_ID);

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

        assertThrows(IllegalStateException.class, () ->
                PotCalculator.allocatePots(
                        200, 0, contributions, contenders, allPlayers, contenders, GAME_ID));
    }

    @Test
    void computePotAllocations_ContestedLayerWithNoEligibleClaimant_ThrowsIllegalStateException() {
        Player a = foldedPlayer("a", "A", 950);
        Player b = foldedPlayer("b", "B", 950);
        List<Player> allPlayers = List.of(a, b);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 50);

        assertThrows(IllegalStateException.class, () ->
                PotCalculator.allocatePots(
                        100, 0, contributions, allPlayers, allPlayers, List.of(), GAME_ID));
    }

    @Test
    void computePotAllocations_CarryOverWithNoEligibleClaimant_ThrowsIllegalStateException() {
        Player a = foldedPlayer("a", "A", 950);
        Player b = foldedPlayer("b", "B", 950);
        List<Player> allPlayers = List.of(a, b);

        assertThrows(IllegalStateException.class, () ->
                PotCalculator.allocatePots(
                        30, 30, Map.of(), allPlayers, allPlayers, List.of(), GAME_ID));
    }

    @Test
    void getPotBreakdown_EmptyPot_ReturnsEmptyList() {
        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                0, 0, Map.of(), List.of(), List.of(), false, 0, GAME_ID));

        assertTrue(breakdown.isEmpty());
    }

    @Test
    void getPotBreakdown_SettledGame_ReturnsTotalPot() {
        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                100, 0, Map.of(), List.of(), List.of(), true, 0, GAME_ID));

        assertEquals(List.of(100), breakdown);
    }

    @Test
    void getPotBreakdown_LiveNoAllIn_ReturnsTotalPot() {
        Player p1 = playerWithBet("p1", "Alice", 1000, 50);
        Player p2 = playerWithBet("p2", "Bob", 1000, 50);
        List<Player> active = List.of(p1, p2);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                100, 0, Map.of("p1", 50, "p2", 50), active, active, false, 50, GAME_ID));

        assertEquals(List.of(100), breakdown);
    }

    @Test
    void getPotBreakdown_LiveWithAllInPendingCall_ProjectsLiveCaps() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player bettor = playerWithBet("bettor", "Bettor", 800, 250);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        List<Player> active = List.of(shortStack, bettor, owing);
        Map<String, Integer> contributions = Map.of("short", 50, "bettor", 250, "owing", 50);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                350, 0, contributions, active, active, false, 250, GAME_ID));

        assertEquals(List.of(150, 200), breakdown);
    }

    @Test
    void getPotBreakdown_LiveWithOwingBelowCap_ReturnsTotalPot() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = playerWithBet("s1", "SB", 990, 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                510, 0, contributions, active, active, false, 480, GAME_ID));

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

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                380, 10, contributions, active, active, false, 250, GAME_ID));

        assertEquals(List.of(180, 200), breakdown);
    }

    @Test
    void getPotBreakdown_PendingCallOnLaterStreet_CapComesFromWholeHandContributions() {
        Player allIn = allInPlayerWithBet("a", "A", 0);
        Player owing = playerWithBet("b", "B", 900, 0);
        Player bettor = playerWithBet("c", "C", 860, 40);
        List<Player> active = List.of(allIn, owing, bettor);
        Map<String, Integer> contributions = Map.of("a", 100, "b", 100, "c", 140);
        PotCalculator.PotInputs inputs = new PotCalculator.PotInputs(
                340, 0, contributions, active, active, false, 40, GAME_ID);

        assertEquals(List.of(300, 40), PotCalculator.getPotBreakdown(inputs));
        assertEquals(0, PotCalculator.getUncalledAmount(inputs));
    }

    @Test
    void getPotBreakdown_ActionCompleteWithAllIn_ReturnsContestedAllocations() {
        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player midStack = playerWithBet("mid", "Mid", 850, 150);
        Player deepStack = playerWithBet("deep", "Deep", 850, 150);
        List<Player> active = List.of(shortStack, midStack, deepStack);
        Map<String, Integer> contributions = Map.of("short", 50, "mid", 150, "deep", 150);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                350, 0, contributions, active, active, false, 150, GAME_ID));

        assertEquals(List.of(150, 200), breakdown);
    }

    @Test
    void getPotBreakdown_ActionCompleteWithUncalledShove_ExcludesUncalledLayer() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = allInPlayerWithBet("s1", "SB", 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                510, 0, contributions, active, active, false, 20, GAME_ID));

        assertEquals(List.of(30, 20), breakdown);
    }

    @Test
    void getPotBreakdown_OutPlayerMarkedAllIn_DoesNotSplitSinglePot() {
        Player outAllIn = Player.restoreBuilder()
                .playerId("o1").name("OutAllIn").chips(0).currentBet(0)
                .holeCards(List.of()).bestHand(List.of()).handRank(HandRank.NO_HAND)
                .hasFolded(false).allIn(true).out(true).disconnected(false).readyForNextHand(false)
                .build();
        Player p1 = playerWithBet("p1", "Alice", 900, 100);
        Player p2 = playerWithBet("p2", "Bob", 900, 100);
        List<Player> active = List.of(outAllIn, p1, p2);
        PotCalculator.PotInputs inputs = new PotCalculator.PotInputs(
                220, 0, Map.of("o1", 20, "p1", 100, "p2", 100), active, active, false, 100, GAME_ID);

        assertEquals(List.of(220), PotCalculator.getPotBreakdown(inputs));
        assertEquals(0, PotCalculator.getUncalledAmount(inputs));
    }

    @Test
    void getUncalledAmount_EmptySettledOrLivePending_ReturnsZero() {
        assertEquals(0, PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                0, 0, Map.of(), List.of(), List.of(), false, 0, GAME_ID)));
        assertEquals(0, PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                100, 0, Map.of(), List.of(), List.of(), true, 0, GAME_ID)));

        Player p1 = playerWithBet("p1", "Alice", 1000, 50);
        Player p2 = playerWithBet("p2", "Bob", 1000, 50);
        assertEquals(0, PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                100, 0, Map.of("p1", 50, "p2", 50), List.of(p1, p2), List.of(p1, p2), false, 50, GAME_ID)));

        Player shortStack = allInPlayerWithBet("short", "Short", 50);
        Player bettor = playerWithBet("bettor", "Bettor", 800, 250);
        Player owing = playerWithBet("owing", "Owing", 950, 50);
        List<Player> active = List.of(shortStack, bettor, owing);
        assertEquals(0, PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                350, 0, Map.of("short", 50, "bettor", 250, "owing", 50), active, active, false, 250, GAME_ID)));
    }

    @Test
    void getUncalledAmount_ActionCompleteWithUncalledShove_ReturnsUncalledTotal() {
        Player shover = allInPlayerWithBet("a1", "AllIn", 480);
        Player sb = allInPlayerWithBet("s1", "SB", 10);
        Player bb = playerWithBet("b1", "BB", 980, 20);
        List<Player> active = List.of(shover, sb, bb);
        Map<String, Integer> contributions = Map.of("a1", 480, "s1", 10, "b1", 20);

        assertEquals(460, PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                510, 0, contributions, active, active, false, 20, GAME_ID)));
    }

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                totalPot, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                totalPot, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                500, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

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
        Player p3 = allInPlayer("p3", "Charlie", 0);
        Player f1 = foldedPlayer("f1", "Folded", 1000);
        List<Player> allPlayers = List.of(p1, p2, p3, f1);
        List<Player> contenders = List.of(p1, p2, p3);

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                270, 0, Map.of("p1", 100, "p2", 100, "p3", 50, "f1", 20),
                contenders, allPlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(170, result.allocations().get(0).amount());
        assertEquals(List.of(p1, p2, p3), result.allocations().get(0).eligiblePlayers());
        assertEquals(100, result.allocations().get(1).amount());
        assertEquals(List.of(p1, p2), result.allocations().get(1).eligiblePlayers());
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

        int uncalled = PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                totalPot, 0, contributions, active, active, false, 300, GAME_ID));
        assertEquals(0, uncalled);

        List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                totalPot, 0, contributions, active, active, false, 300, GAME_ID));
        assertEquals(List.of(200, 150, 200), breakdown);
        assertEquals(totalPot, breakdown.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void challenge_DepartedPlayer_SoleTopLayerAndOutPlayerRoutedDownWithoutRefund() {
        Player activeA = player("a", "ActiveA", 50);
        Player activeB = player("b", "ActiveB", 100);
        List<Player> tablePlayers = List.of(activeA, activeB);
        List<Player> contenders = List.of(activeA, activeB);
        Map<String, Integer> contributions = Map.of("a", 50, "b", 100, "departed", 250);

        PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                400, 0, contributions, contenders, tablePlayers, contenders, GAME_ID);

        assertEquals(2, result.allocations().size());
        assertEquals(150, result.allocations().get(0).amount());
        assertEquals(List.of(activeA, activeB), result.allocations().get(0).eligiblePlayers());
        assertEquals(250, result.allocations().get(1).amount());
        assertEquals(List.of(activeB), result.allocations().get(1).eligiblePlayers());
        assertEquals(0, result.uncalledTotal());
        assertFalse(result.uncalledByPlayer().containsKey("departed"));

        Player outP = outPlayer("outP", "OutP", 0);
        PotCalculator.PotAllocations outResult = PotCalculator.allocatePots(
                400, 0, Map.of("a", 100, "outP", 300), List.of(activeA), List.of(activeA, outP), List.of(activeA), GAME_ID);
        assertEquals(1, outResult.allocations().size());
        assertEquals(400, outResult.allocations().get(0).amount());
        assertEquals(0, outResult.uncalledTotal());
        assertFalse(outResult.uncalledByPlayer().containsKey("outP"));
    }

    @Nested
    @DisplayName("PotAllocationEdgeCases")
    class PotAllocationEdgeCases {

        @Test
        void challenge_TenPlayerAllIn_DistinctTiers_DecomposesExactNinePotsAndOneRefund() {
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

            PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                    totalPot, 0, contributions, players, players, players, GAME_ID);

            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum + result.uncalledTotal());
            assertEquals(10, result.uncalledTotal());
            assertEquals(10, result.uncalledByPlayer().get("p10"));
            assertEquals(9, result.allocations().size());

            for (int i = 0; i < 9; i++) {
                PotCalculator.PotAllocation alloc = result.allocations().get(i);
                int expectedAmount = (10 - i) * 10;
                int expectedEligibleCount = 10 - i;
                assertEquals(expectedAmount, alloc.amount());
                assertEquals(expectedEligibleCount, alloc.eligiblePlayers().size());
                final String expectedId = "p" + (i + 1);
                assertTrue(alloc.eligiblePlayers().stream().anyMatch(p -> p.getPlayerId().equals(expectedId)));
            }

            List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                    totalPot, 0, contributions, players, players, false, 100, GAME_ID));
            assertEquals(List.of(100, 90, 80, 70, 60, 50, 40, 30, 20), breakdown);

            int uncalled = PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                    totalPot, 0, contributions, players, players, false, 100, GAME_ID));
            assertEquals(10, uncalled);
        }

        @Test
        void challenge_TenPlayers_AlternatingFolds_ActiveTopPlayer_GetsRefundAndRoutesDeadChips() {
            List<Player> allPlayers = new ArrayList<>();
            List<Player> contenders = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int totalPot = 0;

            for (int i = 1; i <= 10; i++) {
                String id = "p" + i;
                int amount = i * 10;
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

            PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                    totalPot, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum + result.uncalledTotal());
            assertEquals(10, result.uncalledTotal());
            assertEquals(10, result.uncalledByPlayer().get("p10"));

            for (PotCalculator.PotAllocation alloc : result.allocations()) {
                for (Player eligible : alloc.eligiblePlayers()) {
                    assertFalse(eligible.hasFolded());
                }
            }
            assertEquals(540, allocatedSum);
        }

        @Test
        void challenge_TenPlayers_TopPlayerFolded_DeadExcessRoutedToSurvivorsWithoutRefund() {
            List<Player> allPlayers = new ArrayList<>();
            List<Player> contenders = new ArrayList<>();
            Map<String, Integer> contributions = new LinkedHashMap<>();
            int totalPot = 0;

            for (int i = 1; i <= 10; i++) {
                String id = "p" + i;
                int amount = i * 10;
                boolean fold = (i % 2 == 0);
                Player p = fold ? foldedPlayer(id, "Folded_" + i, 0) : allInPlayer(id, "Active_" + i, 0);
                allPlayers.add(p);
                if (!fold) {
                    contenders.add(p);
                }
                contributions.put(id, amount);
                totalPot += amount;
            }
            assertEquals(550, totalPot);

            PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                    totalPot, 0, contributions, contenders, allPlayers, contenders, GAME_ID);

            assertEquals(0, result.uncalledTotal());
            assertTrue(result.uncalledByPlayer().isEmpty());

            int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
            assertEquals(550, allocatedSum);

            for (PotCalculator.PotAllocation alloc : result.allocations()) {
                for (Player eligible : alloc.eligiblePlayers()) {
                    assertFalse(eligible.hasFolded());
                }
            }
        }

        @Test
        void challenge_LargeCarryOverAndHugePot_NoSilentNegativeWrap() {
            int carryOver = 10_000_000;
            Player p1 = allInPlayer("p1", "Short", 0);
            Player p2 = player("p2", "Mid", 500);
            Player p3 = player("p3", "Deep", 500);
            List<Player> allPlayers = List.of(p1, p2, p3);
            Map<String, Integer> contributions = Map.of("p1", 100, "p2", 300, "p3", 300);
            int totalPot = carryOver + 700;

            PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                    totalPot, carryOver, contributions, allPlayers, allPlayers, allPlayers, GAME_ID);

            assertEquals(2, result.allocations().size());
            assertEquals(10_000_300, result.allocations().get(0).amount());
            assertEquals(400, result.allocations().get(1).amount());

            int p1Contrib = 500_000_000;
            int p2Contrib = 1_000_000_000;
            int hugePot = 1_500_000_000;
            List<Player> hugePlayers = List.of(p1, p2);
            PotCalculator.PotAllocations hugeResult = PotCalculator.allocatePots(
                    hugePot, 0, Map.of("p1", p1Contrib, "p2", p2Contrib), hugePlayers, hugePlayers, hugePlayers, GAME_ID);

            assertEquals(1, hugeResult.allocations().size());
            assertEquals(1_000_000_000, hugeResult.allocations().get(0).amount());
            assertEquals(500_000_000, hugeResult.uncalledTotal());
            assertEquals(500_000_000, hugeResult.uncalledByPlayer().get("p2"));
        }

        @Test
        void challenge_Boundary_AllInForOneChipAndTierCollisions() {
            Player micro = allInPlayer("m1", "Micro", 0);
            Player deep = player("d1", "Deep", 999);
            List<Player> pair = List.of(micro, deep);
            PotCalculator.PotAllocations oneChip = PotCalculator.allocatePots(
                    501, 0, Map.of("m1", 1, "d1", 500), pair, pair, pair, GAME_ID);
            assertEquals(1, oneChip.allocations().size());
            assertEquals(2, oneChip.allocations().get(0).amount());
            assertEquals(499, oneChip.uncalledTotal());

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
            PotCalculator.PotAllocations tiers = PotCalculator.allocatePots(
                    pot, 0, contributions, players, players, players, GAME_ID);
            assertEquals(0, tiers.uncalledTotal());
            assertEquals(3, tiers.allocations().size());
            assertEquals(400, tiers.allocations().get(0).amount());
            assertEquals(500, tiers.allocations().get(1).amount());
            assertEquals(300, tiers.allocations().get(2).amount());
        }

        @Test
        void challenge_PropertyBasedFuzzing_RandomizedScenarios_AllInvariantsHold() {
            Random rng = new Random(42_1337);

            for (int iteration = 0; iteration < 300; iteration++) {
                int playerCount = 2 + rng.nextInt(9);
                List<Player> allPlayers = new ArrayList<>();
                List<Player> activePlayers = new ArrayList<>();
                Map<String, Integer> contributions = new LinkedHashMap<>();
                int totalPot = 0;
                int carryOver = rng.nextDouble() < 0.2 ? rng.nextInt(100) : 0;
                totalPot += carryOver;

                for (int p = 0; p < playerCount; p++) {
                    String id = "fuzz_p" + p;
                    int contribution = 1 + rng.nextInt(500);
                    boolean folded = p > 0 && rng.nextDouble() < 0.3;
                    boolean allIn = !folded && rng.nextDouble() < 0.4;
                    boolean out = p > 0 && rng.nextDouble() < 0.1;
                    boolean departed = p > 0 && rng.nextDouble() < 0.1;

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

                PotCalculator.PotAllocations result = PotCalculator.allocatePots(
                        totalPot, carryOver, contributions, activePlayers, allPlayers, activePlayers, "fuzz-" + iteration);

                int allocatedSum = result.allocations().stream().mapToInt(PotCalculator.PotAllocation::amount).sum();
                assertEquals(totalPot, allocatedSum + result.uncalledTotal(),
                        "Iteration " + iteration + ": Pot conservation violated");

                int uncalledMapSum = result.uncalledByPlayer().values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(result.uncalledTotal(), uncalledMapSum,
                        "Iteration " + iteration + ": uncalledTotal does not match map sum");

                for (String refundedId : result.uncalledByPlayer().keySet()) {
                    assertTrue(allPlayers.stream().anyMatch(pl -> pl.getPlayerId().equals(refundedId)
                            && !pl.hasFolded() && !pl.isOut()));
                }

                Set<String> previousTierIds = null;
                for (PotCalculator.PotAllocation alloc : result.allocations()) {
                    assertTrue(alloc.amount() > 0);
                    for (Player eligible : alloc.eligiblePlayers()) {
                        assertFalse(eligible.hasFolded());
                        assertFalse(eligible.isOut());
                    }
                    Set<String> currentTierIds = alloc.eligiblePlayers().stream()
                            .map(Player::getPlayerId)
                            .collect(Collectors.toSet());
                    if (previousTierIds != null) {
                        assertTrue(previousTierIds.containsAll(currentTierIds));
                    }
                    previousTierIds = currentTierIds;
                }

                List<Integer> breakdown = PotCalculator.getPotBreakdown(new PotCalculator.PotInputs(
                        totalPot, carryOver, contributions, activePlayers, allPlayers, false, 0, GAME_ID));
                int uncalledQuery = PotCalculator.getUncalledAmount(new PotCalculator.PotInputs(
                        totalPot, carryOver, contributions, activePlayers, allPlayers, false, 0, GAME_ID));
                int breakdownSum = breakdown.stream().mapToInt(Integer::intValue).sum();
                assertEquals(totalPot, breakdownSum + uncalledQuery);
            }
        }
    }

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
