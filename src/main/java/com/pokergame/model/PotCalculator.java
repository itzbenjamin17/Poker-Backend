package com.pokergame.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure side-pot layering, uncalled-bet detection, and contribution tracking for {@link Game}.
 *
 * <p>{@link #allocatePots} slices committed hand contributions and any carry-over pot into contested
 * main/side pots plus uncalled chip refunds. Mid-hand display helpers ({@link #getPotBreakdown} and
 * {@link #getUncalledAmount}) expose client-visible layers while keeping uncalled chips in an open
 * top layer until all active players have acted on the current bet.
 */
final class PotCalculator {

    private PotCalculator() {
    }

    /**
     * Adds {@code amount} chips contributed by {@code player} during the current hand to {@code handContributions}.
     *
     * @param handContributions mutable map from player ID to total chips contributed this hand
     * @param player            contributing player
     * @param amount            chips added in this action (ignored if non-positive)
     */
    static void trackContribution(
            Map<String, Integer> handContributions,
            Player player,
            int amount) {
        if (player == null || amount <= 0) {
            return;
        }

        handContributions.merge(player.getPlayerId(), amount, Integer::sum);
    }

    /**
     * Layers current-hand contributions (plus {@code carryOverPot}) into contested main and side pots
     * and identifies any excess chips that only one player can claim as an uncalled bet.
     *
     * @param pot                    current total pot including carry-over chips
     * @param carryOverPot           chips carried over from an earlier hand with no eligible winner
     * @param handContributions      per-player chip contributions for this hand
     * @param activePlayers          players active in the hand
     * @param tablePlayers           full table roster for seat-order and player lookups
     * @param eligibleHandContenders contenders eligible to win pots at payout
     * @param gameId                 game identifier for diagnostics
     * @return computed pot allocations and uncalled chip amounts per player
     * @throws IllegalStateException if the contribution ledger plus carry-over does not equal {@code pot},
     *                               or if any chips are left without a pot or a refund owner
     */
    static PotAllocations allocatePots(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            List<Player> eligibleHandContenders,
            String gameId) {
        List<PotAllocation> allocations = new ArrayList<>();
        Map<String, Integer> uncalledByPlayer = new HashMap<>();

        List<Player> contenders = findContenders(eligibleHandContenders, activePlayers);
        Map<String, Integer> contributions = positiveContributions(handContributions);
        Map<String, Player> playersById = indexPlayersById(tablePlayers);

        if (!contributions.isEmpty()) {
            splitBetsIntoPots(contributions, tablePlayers, playersById, contenders, allocations, uncalledByPlayer);
        }

        addCarryOverToMainPot(carryOverPot, activePlayers, contenders, allocations);
        verifyAllChipsAccountedFor(pot, carryOverPot, contributions, allocations, uncalledByPlayer, gameId);

        int uncalledTotal = uncalledByPlayer.values().stream().mapToInt(Integer::intValue).sum();
        return new PotAllocations(allocations, uncalledByPlayer, uncalledTotal);
    }

    /**
     * Computes the pot layers clients see during a hand, ordered from main pot to outermost side pot.
     * Only the all-in case with no call owed excludes uncalled chips that will be refunded; otherwise
     * the returned layers sum to the entire pot.
     *
     * @param inputs the hand's pot state
     * @return pot layer amounts, or an empty list when the pot is empty
     */
    static List<Integer> getPotBreakdown(PotInputs inputs) {
        if (inputs.pot() <= 0) {
            return List.of();
        }
        if (inputs.settled()) {
            return List.of(inputs.pot());
        }
        if (BettingRound.anyPlayerNeedsToCall(inputs.activePlayers(), inputs.currentHighestBet())) {
            return potBreakdownWhileCallIsOwed(inputs);
        }
        if (!inputs.hasAllIn()) {
            return List.of(inputs.pot());
        }

        return allocateCurrentPots(inputs).allocations().stream()
                .map(PotAllocation::amount)
                .toList();
    }

    /**
     * Computes the total uncalled chip amount subject to refund when all-in players face no further action.
     *
     * @param inputs the hand's pot state
     * @return total uncalled chips awaiting refund, or {@code 0} when none apply
     */
    static int getUncalledAmount(PotInputs inputs) {
        if (inputs.pot() <= 0 || inputs.settled() || !inputs.hasAllIn()) {
            return 0;
        }
        if (BettingRound.anyPlayerNeedsToCall(inputs.activePlayers(), inputs.currentHighestBet())) {
            return 0;
        }
        return allocateCurrentPots(inputs).uncalledTotal();
    }

    /** Returns {@code eligibleHandContenders}, falling back to active players still in the hand. */
    private static List<Player> findContenders(
            List<Player> eligibleHandContenders,
            List<Player> activePlayers) {
        return !eligibleHandContenders.isEmpty()
                ? eligibleHandContenders
                : activePlayers.stream().filter(Player::isInHand).toList();
    }

    /** Copies only positive player contributions so zero entries do not create empty pot layers. */
    private static Map<String, Integer> positiveContributions(Map<String, Integer> handContributions) {
        return handContributions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Indexes seated players by ID for constant-time lookups while slicing pots. */
    private static Map<String, Player> indexPlayersById(List<Player> tablePlayers) {
        return tablePlayers.stream()
                .collect(Collectors.toMap(Player::getPlayerId, Function.identity(), (first, second) -> first));
    }

    /**
     * Walks sorted bet levels from lowest to highest, turning each slice into either a single-player
     * uncalled refund, a contested pot, or unclaimed chips rolled into a lower pot.
     */
    private static void splitBetsIntoPots(
            Map<String, Integer> contributions,
            List<Player> tablePlayers,
            Map<String, Player> playersById,
            List<Player> contenders,
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer) {
        List<Integer> betLevels = contributions.values().stream()
                .distinct()
                .sorted()
                .toList();

        int previousLevel = 0;
        for (int level : betLevels) {
            int betSlice = level - previousLevel;
            previousLevel = level;
            if (betSlice <= 0) {
                continue;
            }

            // Count everyone who put chips in up to this level so chips from folded or departed players stay in the pot.
            Set<String> contributorIds = contributions.entrySet().stream()
                    .filter(entry -> entry.getValue() >= level)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            int amount = betSlice * contributorIds.size();
            if (amount <= 0) {
                continue;
            }

            if (contributorIds.size() == 1) {
                String ownerId = contributorIds.iterator().next();
                Player owner = playersById.get(ownerId);
                if (owner != null && owner.isInHand()) {
                    uncalledByPlayer.merge(ownerId, amount, Integer::sum);
                } else {
                    addUnclaimedChipsToNearestPot(allocations, amount, contenders);
                }
                continue;
            }

            List<Player> eligibleContributors = tablePlayers.stream()
                    .filter(player -> contributorIds.contains(player.getPlayerId()) && player.isInHand())
                    .toList();
            if (!eligibleContributors.isEmpty()) {
                mergeOrAddPot(allocations, amount, eligibleContributors);
            } else {
                addUnclaimedChipsToNearestPot(allocations, amount, contenders);
            }
        }
    }

    /** Adds any carry-over chips from an earlier hand into the main pot (or creates one if none exists). */
    private static void addCarryOverToMainPot(
            int carryOverPot,
            List<Player> activePlayers,
            List<Player> contenders,
            List<PotAllocation> allocations) {
        if (carryOverPot <= 0) {
            return;
        }
        List<Player> inHand = activePlayers.stream().filter(Player::isInHand).toList();
        List<Player> eligible = !inHand.isEmpty() ? inHand : contenders;
        if (eligible.isEmpty()) {
            return;
        }
        if (allocations.isEmpty()) {
            allocations.add(new PotAllocation(carryOverPot, eligible));
        } else {
            PotAllocation main = allocations.getFirst();
            allocations.set(0, new PotAllocation(main.amount() + carryOverPot, main.eligiblePlayers()));
        }
    }

    /** Confirms recorded contributions plus carry-over match {@code pot} and every chip went to a pot or refund. */
    private static void verifyAllChipsAccountedFor(
            int pot,
            int carryOverPot,
            Map<String, Integer> contributions,
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            String gameId) {
        int ledgerTotal = contributions.values().stream().mapToInt(Integer::intValue).sum();
        if (ledgerTotal + carryOverPot != pot) {
            throw new IllegalStateException(
                    "Contribution ledger and carry-over do not match the committed pot for game " + gameId);
        }

        int uncalledTotal = uncalledByPlayer.values().stream().mapToInt(Integer::intValue).sum();
        int contestedTotal = allocations.stream().mapToInt(PotAllocation::amount).sum();
        int unallocatedAmount = pot - (contestedTotal + uncalledTotal);
        if (unallocatedAmount != 0) {
            throw new IllegalStateException("Pot allocation left " + unallocatedAmount
                    + " chips without a pot or a refund owner for game " + gameId);
        }
    }

    /**
     * Builds the client pot list while an active player still owes a call.
     *
     * <p>Shows one combined pot if there is no all-in or if anyone owing a call has not yet matched
     * the largest all-in total. Once every active player has reached the all-in thresholds, each
     * threshold closes a pot (with carry-over added to the main pot) and any chips above the highest
     * all-in stay in one open top pot until the pending call resolves.
     */
    private static List<Integer> potBreakdownWhileCallIsOwed(PotInputs inputs) {
        List<Integer> allInTotals = sortedAllInTotals(inputs);
        if (allInTotals.isEmpty()) {
            return List.of(inputs.pot());
        }

        int highestAllIn = allInTotals.getLast();
        if (someoneOwesLessThan(inputs, highestAllIn)) {
            return List.of(inputs.pot());
        }

        List<Integer> pots = new ArrayList<>();
        int previousAllIn = 0;
        int allocatedTotal = 0;
        for (int allInTotal : allInTotals) {
            int potAmount = sumChipsBetween(inputs.handContributions().values(), previousAllIn, allInTotal);
            if (pots.isEmpty()) {
                potAmount += inputs.carryOverPot();
            }
            if (potAmount > 0) {
                pots.add(potAmount);
                allocatedTotal += potAmount;
            }
            previousAllIn = allInTotal;
        }

        int remainingTopPot = inputs.pot() - allocatedTotal;
        if (remainingTopPot > 0) {
            pots.add(remainingTopPot);
        }
        return List.copyOf(pots);
    }

    /** Checks whether any player who still owes a call has contributed less than {@code highestAllIn}. */
    private static boolean someoneOwesLessThan(PotInputs inputs, int highestAllIn) {
        return inputs.activePlayers().stream().anyMatch(player -> player.canAct()
                && inputs.currentHighestBet() > player.getCurrentBet()
                && inputs.handContributions().getOrDefault(player.getPlayerId(), 0) < highestAllIn);
    }

    /** Sums every player's chips that fall inside the {@code (lowerBound, upperBound]} bet band. */
    private static int sumChipsBetween(
            Collection<Integer> contributions,
            int lowerBound,
            int upperBound) {
        int sum = 0;
        for (int contributed : contributions) {
            if (contributed > lowerBound) {
                sum += Math.min(contributed, upperBound) - lowerBound;
            }
        }
        return sum;
    }

    /** Returns the sorted, distinct hand bet totals of active all-in players still in the hand. */
    private static List<Integer> sortedAllInTotals(PotInputs inputs) {
        Set<String> allInIds = inputs.activePlayers().stream()
                .filter(player -> player.isAllIn() && player.isInHand())
                .map(Player::getPlayerId)
                .collect(Collectors.toSet());
        return inputs.handContributions().entrySet().stream()
                .filter(entry -> allInIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .sorted()
                .toList();
    }

    /** Runs full pot allocation for mid-hand display queries using active in-hand players as contenders. */
    private static PotAllocations allocateCurrentPots(PotInputs inputs) {
        List<Player> eligibleContenders = inputs.activePlayers().stream()
                .filter(Player::isInHand)
                .toList();
        return allocatePots(
                inputs.pot(), inputs.carryOverPot(), inputs.handContributions(), inputs.activePlayers(),
                inputs.tablePlayers(), eligibleContenders, inputs.gameId());
    }

    /** Rolls chips with no eligible contributors down into the highest lower pot, or into a fallback pot. */
    private static void addUnclaimedChipsToNearestPot(
            List<PotAllocation> allocations,
            int amount,
            List<Player> eligibleHandContenders) {
        for (int i = allocations.size() - 1; i >= 0; i--) {
            PotAllocation lower = allocations.get(i);
            if (!lower.eligiblePlayers().isEmpty()) {
                allocations.set(i, new PotAllocation(lower.amount() + amount, lower.eligiblePlayers()));
                return;
            }
        }
        if (!eligibleHandContenders.isEmpty()) {
            allocations.add(0, new PotAllocation(amount, List.copyOf(eligibleHandContenders)));
        }
    }

    /** Merges a pot slice into the previous pot if eligible players match, or starts a new pot. */
    private static void mergeOrAddPot(
            List<PotAllocation> allocations,
            int amount,
            List<Player> eligiblePlayers) {
        if (!allocations.isEmpty()) {
            PotAllocation previous = allocations.getLast();
            if (samePlayerIds(previous.eligiblePlayers(), eligiblePlayers)) {
                allocations.set(allocations.size() - 1,
                        new PotAllocation(previous.amount() + amount, previous.eligiblePlayers()));
                return;
            }
        }
        allocations.add(new PotAllocation(amount, List.copyOf(eligiblePlayers)));
    }

    private static boolean samePlayerIds(List<Player> first, List<Player> second) {
        if (first.size() != second.size()) {
            return false;
        }
        Set<String> firstIds = first.stream().map(Player::getPlayerId).collect(Collectors.toSet());
        return second.stream().allMatch(player -> firstIds.contains(player.getPlayerId()));
    }

    /**
     * Snapshot of pot state needed to project client-visible pot layers and uncalled amounts.
     *
     * @param pot               current total pot
     * @param carryOverPot      chips carried over from an earlier hand
     * @param handContributions per-player chip contributions for this hand
     * @param activePlayers     players active in the hand
     * @param tablePlayers      full table roster
     * @param settled           whether the hand's pot has already been paid out
     * @param currentHighestBet highest current street bet
     * @param gameId            game identifier for diagnostics
     */
    record PotInputs(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            boolean settled,
            int currentHighestBet,
            String gameId) {

        boolean hasAllIn() {
            return activePlayers.stream().anyMatch(player -> player.isAllIn() && player.isInHand());
        }
    }

    /** A single main or side pot slice together with the contenders eligible to win it. */
    record PotAllocation(int amount, List<Player> eligiblePlayers) {
        PotAllocation {
            eligiblePlayers = List.copyOf(eligiblePlayers);
        }
    }

    /** Result of layering hand contributions into pot allocations and uncalled chip refunds. */
    record PotAllocations(
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
        PotAllocations {
            allocations = List.copyOf(allocations);
            uncalledByPlayer = Map.copyOf(uncalledByPlayer);
        }
    }
}
