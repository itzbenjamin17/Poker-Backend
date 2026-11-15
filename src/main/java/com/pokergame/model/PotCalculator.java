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

        Map<String, Integer> ledger = (handContributions == null) ? Map.of() : handContributions;
        List<Player> allPlayers = (tablePlayers == null) ? List.of() : tablePlayers;
        List<Player> safeActivePlayers = (activePlayers == null) ? List.of() : activePlayers;
        List<Player> contenders = (eligibleHandContenders != null && !eligibleHandContenders.isEmpty())
                ? eligibleHandContenders
                : safeActivePlayers.stream().filter(Player::isInHand).toList();

        Map<String, Integer> contributions = ledger.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .collect(HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);

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
                List<Player> participants = allPlayers.stream()
                        .filter(player -> contributorIds.contains(player.getPlayerId()))
                        .toList();

                int amount = layer * contributorIds.size();
                if (amount <= 0) {
                    continue;
                }

                if (contributorIds.size() == 1) {
                    String ownerId = contributorIds.getFirst();
                    Player owner = allPlayers.stream()
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
            List<Player> eligible = safeActivePlayers.stream()
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
     * Computes live pot and side-pot layer amounts for client display during a hand.
     *
     * @param pot                  current total pot
     * @param carryOverPot         chips carried over from an earlier hand
     * @param handContributions    per-player chip contributions for this hand
     * @param activePlayers        players active in the hand
     * @param tablePlayers         full table roster
     * @param lastSettlementResult previous settlement result, if already settled
     * @param currentHighestBet    highest current street bet
     * @param gameId               game identifier for diagnostics
     * @return list of pot layer amounts ordered from main pot to outermost side pot
     */
    static List<Integer> getPotBreakdown(
            int pot,
            int carryOverPot,
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

        if (BettingRound.hasActionablePlayerOwingCall(safeActivePlayers, currentHighestBet)) {
            int maxAllInCap = safeContributions.entrySet().stream()
                    .filter(entry -> safeActivePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && player.isInHand()))
                    .mapToInt(Map.Entry::getValue)
                    .max()
                    .orElse(0);
            boolean owingBelowCap = safeActivePlayers.stream().anyMatch(player -> player.canAct()
                    && currentHighestBet > player.getCurrentBet()
                    && safeContributions.getOrDefault(player.getPlayerId(), 0) < maxAllInCap);
            if (owingBelowCap || maxAllInCap == 0) {
                return List.of(pot);
            }

            List<Integer> caps = safeContributions.entrySet().stream()
                    .filter(entry -> safeActivePlayers.stream().anyMatch(player -> player.getPlayerId().equals(entry.getKey())
                            && player.getIsAllIn() && player.isInHand()))
                    .map(Map.Entry::getValue)
                    .distinct()
                    .sorted()
                    .toList();
            List<Integer> liveLayers = new ArrayList<>();
            int previousCap = 0;
            for (int cap : caps) {
                int layerWidth = cap - previousCap;
                int lowerBoundary = previousCap;
                int layer = safeContributions.values().stream()
                        .mapToInt(value -> Math.max(0, Math.min(value - lowerBoundary, layerWidth)))
                        .sum();
                if (liveLayers.isEmpty()) {
                    layer = Math.addExact(layer, carryOverPot);
                }
                if (layer > 0) {
                    liveLayers.add(layer);
                }
                previousCap = cap;
            }
            int projected = liveLayers.stream().mapToInt(Integer::intValue).sum();
            if (pot > projected) {
                liveLayers.add(pot - projected);
            }
            return List.copyOf(liveLayers);
        }

        if (!hasAllIn) {
            logger.debug("Pot breakdown for game {} without all-ins: [{}]", gameId, pot);
            return List.of(pot);
        }

        PotComputationResult computation = computeLiveAllocations(
                pot, carryOverPot, safeContributions, safeActivePlayers, tablePlayers, gameId);
        List<Integer> breakdown = computation.allocations().stream()
                .map(PotAllocation::amount)
                .toList();

        logger.debug("Pot breakdown for game {} with all-ins: {} | uncalled={}",
                gameId, breakdown, computation.uncalledTotal());

        return breakdown;
    }

    /**
     * Computes the total uncalled chip amount subject to refund when all-in players face no further action.
     *
     * @param pot                  current total pot
     * @param carryOverPot         chips carried over from an earlier hand
     * @param handContributions    per-player chip contributions for this hand
     * @param activePlayers        players active in the hand
     * @param tablePlayers         full table roster
     * @param lastSettlementResult previous settlement result, if already settled
     * @param currentHighestBet    highest current street bet
     * @param gameId               game identifier for diagnostics
     * @return total uncalled chips awaiting refund, or {@code 0} when none apply
     */
    static int getUncalledAmount(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            HandSettlementResult lastSettlementResult,
            int currentHighestBet,
            String gameId) {
        if (pot <= 0 || (lastSettlementResult != null && lastSettlementResult.settled())) {
            return 0;
        }

        List<Player> safeActivePlayers = (activePlayers == null) ? List.of() : activePlayers;
        Map<String, Integer> safeContributions = (handContributions == null) ? Map.of() : handContributions;

        boolean hasAllIn = safeActivePlayers.stream()
                .anyMatch(player -> player.getIsAllIn() && !player.getHasFolded());
        if (!hasAllIn || BettingRound.hasActionablePlayerOwingCall(safeActivePlayers, currentHighestBet)) {
            return 0;
        }

        return computeLiveAllocations(
                pot, carryOverPot, safeContributions, safeActivePlayers, tablePlayers, gameId).uncalledTotal();
    }

    /**
     * Returns player names for diagnostic logging.
     *
     * @param players players to summarize
     * @return immutable list of player names, or an empty list if {@code players} is {@code null}
     */
    static List<String> playerNames(List<Player> players) {
        if (players == null) {
            return List.of();
        }
        return players.stream().map(Player::getName).toList();
    }

    /**
     * Formats pot allocations into human-readable log entries.
     *
     * @param allocations computed pot allocations
     * @return summary strings showing each pot amount and eligible player names
     */
    static List<String> allocationSummary(List<PotAllocation> allocations) {
        if (allocations == null) {
            return List.of();
        }
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            summary.add("pot[" + i + "] amount=" + allocation.amount() + " eligible="
                    + playerNames(allocation.eligiblePlayers()));
        }
        return summary;
    }

    private static PotComputationResult computeLiveAllocations(
            int pot,
            int carryOverPot,
            Map<String, Integer> handContributions,
            List<Player> activePlayers,
            List<Player> tablePlayers,
            String gameId) {
        List<Player> eligibleContenders = activePlayers.stream()
                .filter(Player::isInHand)
                .toList();
        return computePotAllocationsAndUncalled(
                pot, carryOverPot, handContributions, activePlayers, tablePlayers, eligibleContenders, gameId);
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
        if (eligibleHandContenders != null && !eligibleHandContenders.isEmpty()) {
            allocations.add(0, new PotAllocation(amount, List.copyOf(eligibleHandContenders)));
        }
    }

    private static void appendAllocationCoalescingSameEligibility(
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

    /** A single main or side pot slice together with the contenders eligible to win it. */
    record PotAllocation(int amount, List<Player> eligiblePlayers) {
        PotAllocation {
            eligiblePlayers = (eligiblePlayers == null) ? List.of() : List.copyOf(eligiblePlayers);
        }
    }

    /** Result of layering hand contributions into pot allocations and uncalled chip refunds. */
    record PotComputationResult(
            List<PotAllocation> allocations,
            Map<String, Integer> uncalledByPlayer,
            int uncalledTotal) {
        PotComputationResult {
            allocations = (allocations == null) ? List.of() : List.copyOf(allocations);
            uncalledByPlayer = (uncalledByPlayer == null) ? Map.of() : Map.copyOf(uncalledByPlayer);
        }
    }
}
