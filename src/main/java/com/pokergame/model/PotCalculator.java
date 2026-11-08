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
 * Package-private collaborator extracted from {@link Game}.
 */
final class PotCalculator {

    private static final Logger logger = LoggerFactory.getLogger(PotCalculator.class);

    private PotCalculator() {
        // Utility collaborator class, prevent instantiation
    }

    /**
     * Represents a single pot layer (main pot or a side pot) and the players
     * who are eligible to win that layer.
     */
    record PotAllocation(int amount, List<Player> eligiblePlayers) {
        PotAllocation {
            eligiblePlayers = (eligiblePlayers == null) ? List.of() : List.copyOf(eligiblePlayers);
        }
    }

    /**
     * Result container for pot decomposition:
     * - allocations: contested pots to award at showdown
     * - uncalledByPlayer: chips that must be refunded (single-participant layers)
     * - uncalledTotal: sum of all uncalled chips
     */
    record PotComputationResult(
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
        PotComputationResult {
            allocations = (allocations == null) ? List.of() : List.copyOf(allocations);
            uncalledByPlayer = (uncalledByPlayer == null) ? Map.of() : Map.copyOf(uncalledByPlayer);
        }
    }

    /**
     * Updates the hand-level contribution ledger when a player commits chips to the pot.
     *
     * @param handContributions map of player ID to cumulative contribution
     * @param player the player whose contribution changed
     * @param amount number of chips newly committed to the pot
     * @param gameId game identifier for diagnostic logging
     */
    static void trackContribution(
            Map<String, Integer> handContributions,
            Player player,
            int amount,
            String gameId) {
        if (player == null || amount <= 0 || handContributions == null) {
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
     * Decomposes the pot into contested allocation layers and uncalled layers.
     *
     * @param pot total chips committed in the pot
     * @param preHandCarryOver unallocated chips carried over from previous hands
     * @param handContributions contribution ledger mapping player ID to chips committed
     * @param activePlayers active hand contenders
     * @param tablePlayers all seated players at the table
     * @param gameId game identifier for logging and error reporting
     * @return pot computation result containing allocations, uncalled refunds map, and uncalled total
     */
    static PotComputationResult computePotAllocationsAndUncalled(
            int pot,
            int preHandCarryOver,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            String gameId) {
        List<Player> eligibleContenders = (activePlayers == null)
                ? List.of()
                : activePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut()).toList();
        return computePotAllocationsAndUncalled(
                pot, preHandCarryOver, handContributions, activePlayers, tablePlayers, eligibleContenders, gameId);
    }

    /**
     * Decomposes the pot into contested allocation layers and uncalled layers with explicit eligible hand contenders.
     *
     * @param pot total chips committed in the pot
     * @param preHandCarryOver unallocated chips carried over from previous hands
     * @param handContributions contribution ledger mapping player ID to chips committed
     * @param activePlayers active hand contenders
     * @param tablePlayers all seated players at the table
     * @param eligibleHandContenders explicit contenders eligible for awards / dead layer fallback
     * @param gameId game identifier for logging and error reporting
     * @return pot computation result containing allocations, uncalled refunds map, and uncalled total
     */
    static PotComputationResult computePotAllocationsAndUncalled(
            int pot,
            int preHandCarryOver,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            List<Player> eligibleHandContenders,
            String gameId) {
        List<PotAllocation> allocations = new ArrayList<>();
        Map<String, Integer> uncalledByPlayer = new HashMap<>();

        logger.debug("Building pot allocations for game {} | pot={} | contributions={}",
                gameId,
                pot,
                handContributions);

        Map<String, Integer> ledger = (handContributions == null) ? Map.of() : handContributions;
        List<Player> allPlayers = (tablePlayers == null) ? List.of() : tablePlayers;
        List<Player> safeActivePlayers = (activePlayers == null) ? List.of() : activePlayers;
        List<Player> contenders = (eligibleHandContenders != null && !eligibleHandContenders.isEmpty())
                ? eligibleHandContenders
                : safeActivePlayers.stream().filter(p -> !p.getHasFolded() && !p.getIsOut()).toList();

        // Only considering players who contributed chips to the pot.
        Map<String, Integer> contributions = ledger.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .collect(HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

        if (!contributions.isEmpty()) {
            // Side-pot boundaries are the distinct total contribution amounts.
            List<Integer> contributionLevels = contributions.values().stream()
                    .distinct()
                    .sorted()
                    .toList();

            logger.debug("Contribution levels for game {}: {}", gameId, contributionLevels);

            int previousLevel = 0;
            for (int level : contributionLevels) {
                // Processing each incremental layer between adjacent contribution levels.
                int layer = level - previousLevel;
                previousLevel = level;

                if (layer <= 0) {
                    continue;
                }

                // The contribution ledger is authoritative for layer size, including
                // contributors who folded or departed after committing chips.
                List<String> contributorIds = contributions.entrySet().stream()
                        .filter(entry -> entry.getValue() >= level)
                        .map(Map.Entry::getKey)
                        .toList();
                List<Player> participants = allPlayers.stream()
                        .filter(player -> contributorIds.contains(player.getPlayerId()))
                        .toList();

                int amount = layer * contributorIds.size();
                if (amount <= 0) {
                    continue;
                }

                if (contributorIds.size() == 1) {
                    // Refund only genuinely unmatched excess to a retained eligible owner.
                    String ownerId = contributorIds.getFirst();
                    Player owner = allPlayers.stream().filter(p -> p.getPlayerId().equals(ownerId)
                                    && !p.getHasFolded() && !p.getIsOut()).findFirst().orElse(null);
                    if (owner != null) {
                        uncalledByPlayer.merge(ownerId, amount, Integer::sum);
                    } else {
                        routeDeadLayerToNearestEligible(allocations, amount, contenders);
                    }
                    logger.debug("Detected uncalled layer for game {} | level={} | owner={} | amount={}",
                            gameId,
                            level,
                            ownerId,
                            amount);
                    continue;
                }

                List<Player> eligiblePlayers = participants.stream()
                        .filter(player -> !player.getHasFolded() && !player.getIsOut())
                        .toList();

                if (!eligiblePlayers.isEmpty()) {
                    // Multi-participant layers become contested allocations.
                    appendAllocationCoalescingSameEligibility(allocations, amount, eligiblePlayers);
                    logger.debug(
                            "Allocation layer for game {} | level={} | layer={} | participants={} | eligible={} | amount={}",
                            gameId,
                            level,
                            layer,
                            playerNames(participants),
                            playerNames(eligiblePlayers),
                            amount);
                } else {
                    // Dead upper layers follow the nearest lower allocation that
                    // still has an eligible recipient.
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
        if (ledgerTotal + preHandCarryOver != pot) {
            throw new IllegalStateException("Contribution ledger and carry-over do not match the committed pot for game " + gameId);
        }
        if (preHandCarryOver > 0) {
            List<Player> eligible = safeActivePlayers.stream()
                    .filter(p -> !p.getHasFolded() && !p.getIsOut())
                    .toList();
            if (eligible.isEmpty() && !contenders.isEmpty()) {
                eligible = contenders;
            }
            if (!eligible.isEmpty()) {
                if (allocations.isEmpty()) {
                    allocations.add(new PotAllocation(preHandCarryOver, eligible));
                } else {
                    PotAllocation main = allocations.getFirst();
                    allocations.set(0, new PotAllocation(main.amount() + preHandCarryOver, main.eligiblePlayers()));
                }
                accounted += preHandCarryOver;
            }
        }
        int unallocatedAmount = pot - accounted;

        logger.debug("Allocation totals for game {} | allocated={} | uncalled={} | unallocated={} | pot={}",
                gameId,
                allocatedAmount,
                uncalledTotal,
                unallocatedAmount,
                pot);

        if (logger.isDebugEnabled()) {
            logger.debug("Final allocations for game {}: {}", gameId, allocationSummary(allocations));
            logger.debug("Uncalled map for game {}: {}", gameId, uncalledByPlayer);
        }

        return new PotComputationResult(allocations, uncalledByPlayer, uncalledTotal);
    }

    /**
     * Re-routes dead layers that have no remaining eligible contenders to the nearest
     * lower allocation, or to eligible hand contenders.
     */
    static void routeDeadLayerToNearestEligible(
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
        if (eligibleHandContenders != null && !eligibleHandContenders.isEmpty()) {
            allocations.add(0, new PotAllocation(amount, List.copyOf(eligibleHandContenders)));
        }
    }

    /**
     * Appends an allocation, coalescing with the previous allocation if both share
     * identical eligible contenders.
     */
    static void appendAllocationCoalescingSameEligibility(
            List<PotAllocation> allocations,
            int amount,
            List<Player> eligiblePlayers) {
        if (eligiblePlayers == null || eligiblePlayers.isEmpty()) {
            return;
        }
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
     * Returns the contested pot amounts for display (main pot first, then side pots).
     * Uncalled amounts are intentionally excluded because they are refunded.
     */
    static List<Integer> getPotBreakdown(
            int pot,
            int preHandCarryOver,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            HandSettlementResult lastSettlementResult,
            int currentHighestBet,
            String gameId) {
        if (pot <= 0) {
            logger.debug("Pot breakdown requested for game {} with empty pot", gameId);
            return List.of();
        }

        if (lastSettlementResult != null && lastSettlementResult.settled()) {
            return List.of(pot);
        }

        List<Player> safeActivePlayers = (activePlayers == null) ? List.of() : activePlayers;
        Map<String, Integer> safeContributions = (handContributions == null) ? Map.of() : handContributions;

        boolean hasAllIn = safeActivePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        // Live money above an all-in cap remains pending until the owing players respond.
        boolean pendingResponse = hasActionablePlayerOwingCall(safeActivePlayers, currentHighestBet);
        if (pendingResponse) {
            int maxAllInCap = safeContributions.entrySet().stream()
                    .filter(entry -> safeActivePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && !player.getHasFolded() && !player.getIsOut()))
                    .mapToInt(Map.Entry::getValue).max().orElse(0);
            boolean owingBelowCap = safeActivePlayers.stream().anyMatch(player -> !player.getHasFolded()
                    && !player.getIsOut() && !player.getIsAllIn() && currentHighestBet > player.getCurrentBet()
                    && safeContributions.getOrDefault(player.getPlayerId(), 0) < maxAllInCap);
            if (owingBelowCap || maxAllInCap == 0) return List.of(pot);

            List<Integer> caps = safeContributions.entrySet().stream()
                    .filter(entry -> safeActivePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && !player.getHasFolded() && !player.getIsOut()))
                    .map(Map.Entry::getValue).distinct().sorted().toList();
            List<Integer> liveLayers = new ArrayList<>();
            int previousCap = 0;
            for (int cap : caps) {
                int layerWidth = cap - previousCap;
                int lowerBoundary = previousCap;
                int layer = safeContributions.values().stream()
                        .mapToInt(value -> Math.max(0, Math.min(value - lowerBoundary, layerWidth)))
                        .sum();
                if (liveLayers.isEmpty()) layer = Math.addExact(layer, preHandCarryOver);
                if (layer > 0) liveLayers.add(layer);
                previousCap = cap;
            }
            int projected = liveLayers.stream().mapToInt(Integer::intValue).sum();
            if (pot > projected) liveLayers.add(pot - projected);
            return List.copyOf(liveLayers);
        }

        if (!hasAllIn) {
            logger.debug("Pot breakdown for game {} without all-ins: [{}]", gameId, pot);
            return List.of(pot);
        }

        PotComputationResult computation = computePotAllocationsAndUncalled(
                pot, preHandCarryOver, safeContributions, safeActivePlayers, tablePlayers, gameId);
        List<Integer> breakdown = computation.allocations().stream()
                .map(PotAllocation::amount)
                .toList();

        logger.debug("Pot breakdown for game {} with all-ins: {} | uncalled={}",
                gameId,
                breakdown,
                computation.uncalledTotal());

        return breakdown;
    }

    /**
     * Overload for getPotBreakdown matching alternative parameter order.
     */
    static List<Integer> getPotBreakdown(
            int pot,
            HandSettlementResult lastSettlementResult,
            List<Player> activePlayers,
            int currentHighestBet,
            Map<String, Integer> handContributions,
            int preHandCarryOver,
            List<Player> allPlayers,
            String gameId) {
        return getPotBreakdown(
                pot,
                preHandCarryOver,
                handContributions,
                activePlayers,
                allPlayers,
                lastSettlementResult,
                currentHighestBet,
                gameId);
    }

    /**
     * Returns the total uncalled amount currently detected in the pot decomposition.
     */
    static int getUncalledAmount(
            int pot,
            int preHandCarryOver,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            HandSettlementResult lastSettlementResult,
            int currentHighestBet,
            String gameId) {
        if (pot <= 0) {
            return 0;
        }

        if (lastSettlementResult != null && lastSettlementResult.settled()) {
            return 0;
        }

        List<Player> safeActivePlayers = (activePlayers == null) ? List.of() : activePlayers;
        Map<String, Integer> safeContributions = (handContributions == null) ? Map.of() : handContributions;

        boolean hasAllIn = safeActivePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());

        boolean pendingResponse = hasActionablePlayerOwingCall(safeActivePlayers, currentHighestBet);
        if (pendingResponse) return 0;

        if (!hasAllIn) {
            return 0;
        }

        return computePotAllocationsAndUncalled(
                pot, preHandCarryOver, safeContributions, safeActivePlayers, tablePlayers, gameId).uncalledTotal();
    }

    /**
     * Overload for getUncalledAmount matching PROJECT.md interface contract.
     */
    static int getUncalledAmount(
            int pot,
            int preHandCarryOver,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            String gameId) {
        return getUncalledAmount(pot, preHandCarryOver, handContributions, activePlayers, tablePlayers, null, 0, gameId);
    }

    /**
     * Overload for getUncalledAmount matching alternative parameter order.
     */
    static int getUncalledAmount(
            int pot,
            HandSettlementResult lastSettlementResult,
            List<Player> activePlayers,
            int currentHighestBet,
            Map<String, Integer> handContributions,
            int preHandCarryOver,
            List<Player> allPlayers,
            String gameId) {
        return getUncalledAmount(
                pot,
                preHandCarryOver,
                handContributions,
                activePlayers,
                allPlayers,
                lastSettlementResult,
                currentHighestBet,
                gameId);
    }

    /**
     * Checks if any active player owes a call to currentHighestBet.
     */
    static boolean hasActionablePlayerOwingCall(List<Player> activePlayers, int currentHighestBet) {
        if (activePlayers == null) return false;
        return activePlayers.stream().anyMatch(p -> !p.getHasFolded() && !p.getIsOut() && !p.getIsAllIn()
                && p.getCurrentBet() < currentHighestBet);
    }

    /**
     * Helper for concise player-name logging in side-pot diagnostics.
     */
    static List<String> playerNames(List<Player> players) {
        if (players == null) return List.of();
        return players.stream().map(Player::getName).toList();
    }

    /**
     * Helper for concise allocation logging in side-pot diagnostics.
     */
    static List<String> allocationSummary(List<PotAllocation> allocations) {
        if (allocations == null) return List.of();
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            summary.add("pot[" + i + "] amount=" + allocation.amount() + " eligible="
                    + playerNames(allocation.eligiblePlayers()));
        }
        return summary;
    }
}
