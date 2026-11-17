package com.pokergame.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure calculation logic for side-pot layering, uncalled-bet detection,
 * pot breakdown representations, and contribution tracking.
 *
 * <p>{@link #computePotAllocationsAndUncalled} is the single side-pot algorithm. It decides payouts at
 * showdown, and the client breakdown and uncalled amount read its allocations when the hand has an all-in
 * and no active player owes a call. A settled hand or a hand with no all-in shows one pot of the whole pot
 * without using the allocations.
 *
 * <p>While an active player still owes a call, the breakdown comes from a separate projection
 * ({@code projectLayersWhileCallIsOwed}). Until that player calls or folds, the chips above the highest
 * all-in are not yet known to be uncalled, so the display shows them as an open top layer. The payout
 * algorithm would refund them, so it cannot produce this projection without changing what players see
 * mid-hand.
 */
final class PotCalculator {

    private static final Logger logger = LoggerFactory.getLogger(PotCalculator.class);

    private PotCalculator() {
    }

    /**
     * Adds {@code amount} chips contributed by {@code player} during the current hand to {@code handContributions}.
     *
     * @param handContributions mutable map from player ID to total chips contributed this hand
     * @param player            contributing player
     * @param amount            chips added in this action (ignored if non-positive)
     * @param gameId            game identifier for diagnostics
     */
    static void trackContribution(
            Map<String, Integer> handContributions,
            Player player,
            int amount,
            String gameId) {
        if (player == null || amount <= 0) {
            return;
        }

        handContributions.merge(player.getPlayerId(), amount, Integer::sum);

        logger.debug("Tracked contribution in game {} | player={} | added={} | totalContribution={} | contributions={}",
                gameId,
                player.getName(),
                amount,
                handContributions.getOrDefault(player.getPlayerId(), 0),
                handContributions);
    }

    /**
     * Layers current-hand contributions (plus {@code carryOverPot}) into contested main and side pots
     * and identifies any excess chips that only one contender can claim as an uncalled bet.
     *
     * @param pot                    current total pot including carry-over chips
     * @param carryOverPot           chips carried over from an earlier split/remainder hand
     * @param handContributions      per-player chip contributions for this hand
     * @param activePlayers          players active in the hand
     * @param tablePlayers           full table roster for name/seat lookups
     * @param eligibleHandContenders contenders eligible to win pots at settlement
     * @param gameId                 game identifier for diagnostics
     * @return computed pot allocations and uncalled chip amounts per player
     * @throws IllegalStateException if the contribution ledger plus carry-over does not equal {@code pot},
     *                               or if any chips are left without a pot or a refund owner
     */
    static PotComputationResult computePotAllocationsAndUncalled(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            List<Player> eligibleHandContenders,
            String gameId) {
        List<PotAllocation> allocations = new ArrayList<>();
        Map<String, Integer> uncalledByPlayer = new HashMap<>();

        logger.debug("Building pot allocations for game {} | pot={} | contributions={}",
                gameId, pot, handContributions);

        List<Player> contenders = !eligibleHandContenders.isEmpty()
                ? eligibleHandContenders
                : activePlayers.stream().filter(Player::isInHand).toList();

        Map<String, Integer> contributions = handContributions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (!contributions.isEmpty()) {
            List<Integer> contributionLevels = contributions.values().stream()
                    .distinct()
                    .sorted()
                    .toList();

            logger.debug("Contribution levels for game {}: {}", gameId, contributionLevels);

            int previousLevel = 0;
            for (int level : contributionLevels) {
                int layer = level - previousLevel;
                previousLevel = level;
                if (layer <= 0) {
                    continue;
                }

                // The contribution ledger is authoritative for layer size, including folded/departed contributors.
                List<String> contributorIds = contributions.entrySet().stream()
                        .filter(entry -> entry.getValue() >= level)
                        .map(Map.Entry::getKey)
                        .toList();
                List<Player> participants = tablePlayers.stream()
                        .filter(player -> contributorIds.contains(player.getPlayerId()))
                        .toList();

                int amount = layer * contributorIds.size();
                if (amount <= 0) {
                    continue;
                }

                if (contributorIds.size() == 1) {
                    String ownerId = contributorIds.getFirst();
                    Player owner = tablePlayers.stream()
                            .filter(p -> p.getPlayerId().equals(ownerId) && p.isInHand())
                            .findFirst()
                            .orElse(null);
                    if (owner != null) {
                        uncalledByPlayer.merge(ownerId, amount, Integer::sum);
                    } else {
                        routeDeadLayerToNearestEligible(allocations, amount, contenders);
                    }
                    logger.debug("Detected uncalled layer for game {} | level={} | owner={} | amount={}",
                            gameId, level, ownerId, amount);
                    continue;
                }

                List<Player> eligiblePlayers = participants.stream()
                        .filter(Player::isInHand)
                        .toList();

                if (!eligiblePlayers.isEmpty()) {
                    appendAllocationCoalescingSameEligibility(allocations, amount, eligiblePlayers);
                    logger.debug(
                            "Allocation layer for game {} | level={} | layer={} | participants={} | eligible={} | amount={}",
                            gameId, level, layer, playerNames(participants), playerNames(eligiblePlayers), amount);
                } else {
                    routeDeadLayerToNearestEligible(allocations, amount, contenders);
                }
            }
        } else {
            logger.debug("No tracked contributions for game {} while building allocations", gameId);
        }

        int uncalledTotal = uncalledByPlayer.values().stream().mapToInt(Integer::intValue).sum();
        int allocatedAmount = allocations.stream().mapToInt(PotAllocation::amount).sum();
        int accounted = allocatedAmount + uncalledTotal;
        int ledgerTotal = contributions.values().stream().mapToInt(Integer::intValue).sum();
        if (ledgerTotal + carryOverPot != pot) {
            throw new IllegalStateException("Contribution ledger and carry-over do not match the committed pot for game " + gameId);
        }
        if (carryOverPot > 0) {
            List<Player> eligible = activePlayers.stream()
                    .filter(Player::isInHand)
                    .toList();
            if (eligible.isEmpty() && !contenders.isEmpty()) {
                eligible = contenders;
            }
            if (!eligible.isEmpty()) {
                if (allocations.isEmpty()) {
                    allocations.add(new PotAllocation(carryOverPot, eligible));
                } else {
                    PotAllocation main = allocations.getFirst();
                    allocations.set(0, new PotAllocation(main.amount() + carryOverPot, main.eligiblePlayers()));
                }
                accounted += carryOverPot;
            }
        }
        int unallocatedAmount = pot - accounted;

        logger.debug("Allocation totals for game {} | allocated={} | uncalled={} | unallocated={} | pot={}",
                gameId, allocatedAmount, uncalledTotal, unallocatedAmount, pot);
        if (unallocatedAmount != 0) {
            throw new IllegalStateException("Pot allocation left " + unallocatedAmount
                    + " chips without a pot or a refund owner for game " + gameId);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Final allocations for game {}: {}", gameId, allocationSummary(allocations));
            logger.debug("Uncalled map for game {}: {}", gameId, uncalledByPlayer);
        }

        return new PotComputationResult(allocations, uncalledByPlayer, uncalledTotal);
    }

    /**
     * Computes the pot layers clients see during a hand, ordered from main pot to outermost side pot.
     * Only the all-in case with no call owed excludes uncalled chips that will be refunded. A settled hand,
     * a hand with no all-in, and a hand where a call is still owed return layers that add up to the whole pot.
     *
     * @param inputs the hand's pot state
     * @return pot layer amounts, or an empty list when the pot is empty
     */
    static List<Integer> getPotBreakdown(PotInputs inputs) {
        if (inputs.pot() <= 0) {
            logger.debug("Pot breakdown requested for game {} with empty pot", inputs.gameId());
            return List.of();
        }
        if (inputs.isSettled()) {
            return List.of(inputs.pot());
        }
        if (BettingRound.hasActionablePlayerOwingCall(inputs.activePlayers(), inputs.currentHighestBet())) {
            return projectLayersWhileCallIsOwed(inputs);
        }
        if (!inputs.hasAllIn()) {
            logger.debug("Pot breakdown for game {} without all-ins: [{}]", inputs.gameId(), inputs.pot());
            return List.of(inputs.pot());
        }

        PotComputationResult computation = computeLiveAllocations(inputs);
        List<Integer> breakdown = computation.allocations().stream()
                .map(PotAllocation::amount)
                .toList();

        logger.debug("Pot breakdown for game {} with all-ins: {} | uncalled={}",
                inputs.gameId(), breakdown, computation.uncalledTotal());

        return breakdown;
    }

    /**
     * Computes the total uncalled chip amount subject to refund when all-in players face no further action.
     *
     * @param inputs the hand's pot state
     * @return total uncalled chips awaiting refund, or {@code 0} when none apply
     */
    static int getUncalledAmount(PotInputs inputs) {
        if (inputs.pot() <= 0 || inputs.isSettled()) {
            return 0;
        }
        if (!inputs.hasAllIn()
                || BettingRound.hasActionablePlayerOwingCall(inputs.activePlayers(), inputs.currentHighestBet())) {
            return 0;
        }
        return computeLiveAllocations(inputs).uncalledTotal();
    }

    /**
     * Projects display layers while an active player still owes a call. Nothing is uncalled yet because the
     * owing player may still match the top bet, so chips above the highest all-in stay in an open top layer
     * and the layers always add up to the whole pot.
     */
    private static List<Integer> projectLayersWhileCallIsOwed(PotInputs inputs) {
        List<Integer> caps = allInContributionCaps(inputs);
        int maxAllInCap = caps.isEmpty() ? 0 : caps.getLast();
        boolean owingBelowCap = inputs.activePlayers().stream().anyMatch(player -> player.canAct()
                && inputs.currentHighestBet() > player.getCurrentBet()
                && inputs.handContributions().getOrDefault(player.getPlayerId(), 0) < maxAllInCap);
        if (owingBelowCap || maxAllInCap == 0) {
            return List.of(inputs.pot());
        }

        List<Integer> liveLayers = new ArrayList<>();
        int previousCap = 0;
        for (int cap : caps) {
            int layerWidth = cap - previousCap;
            int lowerBoundary = previousCap;
            int layer = inputs.handContributions().values().stream()
                    .mapToInt(value -> Math.max(0, Math.min(value - lowerBoundary, layerWidth)))
                    .sum();
            if (liveLayers.isEmpty()) {
                layer = Math.addExact(layer, inputs.carryOverPot());
            }
            if (layer > 0) {
                liveLayers.add(layer);
            }
            previousCap = cap;
        }
        int projected = liveLayers.stream().mapToInt(Integer::intValue).sum();
        if (inputs.pot() > projected) {
            liveLayers.add(inputs.pot() - projected);
        }
        return List.copyOf(liveLayers);
    }

    /** Distinct hand contributions of the all-in players still in the hand, lowest first. */
    private static List<Integer> allInContributionCaps(PotInputs inputs) {
        Set<String> allInIds = inputs.activePlayers().stream()
                .filter(player -> player.getIsAllIn() && player.isInHand())
                .map(Player::getPlayerId)
                .collect(Collectors.toSet());
        return inputs.handContributions().entrySet().stream()
                .filter(entry -> allInIds.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Returns player names for diagnostic logging.
     *
     * @param players players to summarize
     * @return immutable list of player names
     */
    static List<String> playerNames(List<Player> players) {
        return players.stream().map(Player::getName).toList();
    }

    /**
     * Formats pot allocations into human-readable log entries.
     *
     * @param allocations computed pot allocations
     * @return summary strings showing each pot amount and eligible player names
     */
    static List<String> allocationSummary(List<PotAllocation> allocations) {
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            summary.add("pot[" + i + "] amount=" + allocation.amount() + " eligible="
                    + playerNames(allocation.eligiblePlayers()));
        }
        return summary;
    }

    private static PotComputationResult computeLiveAllocations(PotInputs inputs) {
        List<Player> eligibleContenders = inputs.activePlayers().stream()
                .filter(Player::isInHand)
                .toList();
        return computePotAllocationsAndUncalled(
                inputs.pot(), inputs.carryOverPot(), inputs.handContributions(), inputs.activePlayers(),
                inputs.tablePlayers(), eligibleContenders, inputs.gameId());
    }

    private static void routeDeadLayerToNearestEligible(
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

    /** Callers pass a non-empty {@code eligiblePlayers}. */
    private static void appendAllocationCoalescingSameEligibility(
            List<PotAllocation> allocations,
            int amount,
            List<Player> eligiblePlayers) {
        if (!allocations.isEmpty()) {
            PotAllocation previous = allocations.getLast();
            Set<String> previousIds = previous.eligiblePlayers().stream().map(Player::getPlayerId).collect(Collectors.toSet());
            Set<String> currentIds = eligiblePlayers.stream().map(Player::getPlayerId).collect(Collectors.toSet());
            if (previousIds.equals(currentIds)) {
                allocations.set(allocations.size() - 1,
                        new PotAllocation(previous.amount() + amount, previous.eligiblePlayers()));
                return;
            }
        }
        allocations.add(new PotAllocation(amount, List.copyOf(eligiblePlayers)));
    }

    /**
     * Everything needed to describe a hand's pot to clients: chip totals, the contribution ledger, the players
     * and the last settlement. Build one per call so the breakdown and the uncalled amount read the same state.
     *
     * @param pot                  current total pot
     * @param carryOverPot         chips carried over from an earlier hand
     * @param handContributions    per-player chip contributions for this hand
     * @param activePlayers        players active in the hand
     * @param tablePlayers         full table roster
     * @param lastSettlementResult previous settlement result, or {@code null} before the hand settles
     * @param currentHighestBet    highest current street bet
     * @param gameId               game identifier for diagnostics
     */
    record PotInputs(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            HandSettlementResult lastSettlementResult,
            int currentHighestBet,
            String gameId) {

        boolean isSettled() {
            return lastSettlementResult != null && lastSettlementResult.settled();
        }

        boolean hasAllIn() {
            return activePlayers.stream().anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());
        }
    }

    /** A single main or side pot slice together with the contenders eligible to win it. */
    record PotAllocation(int amount, List<Player> eligiblePlayers) {
        PotAllocation {
            eligiblePlayers = List.copyOf(eligiblePlayers);
        }
    }

    /** Result of layering hand contributions into pot allocations and uncalled chip refunds. */
    record PotComputationResult(
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
        PotComputationResult {
            allocations = List.copyOf(allocations);
            uncalledByPlayer = Map.copyOf(uncalledByPlayer);
        }
    }
}
