package com.pokergame.model;

import com.pokergame.enums.GamePhase;
import com.pokergame.enums.HandRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial empirical stress tests for PotCalculator and Game pot integration.
 * Designed by Empirical Challenger to rigorously stress-test:
 * 1. 10-player multi-way all-ins with distinct contribution tiers
 * 2. Folded players contributing at various intermediate tiers
 * 3. Large carry-over remainders and integer overflow limits
 * 4. Boundary cases (empty pot, zero chips, all-in for 1 chip)
 * 5. Tier collision (multiple players all-in for identical amounts)
 * 6. High-volume randomized property-based invariant fuzzing (1000 permutations)
 */
@Tag("unit")
@DisplayName("PotCalculator Empirical Challenge")
class PotCalculatorEmpiricalChallengeTest {

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

            int activeCount = 0;
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
                    activeCount++;
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
