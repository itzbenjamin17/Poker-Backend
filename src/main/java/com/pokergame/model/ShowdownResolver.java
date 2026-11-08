package com.pokergame.model;

import com.pokergame.model.PotCalculator.PotAllocation;
import com.pokergame.model.PotCalculator.PotComputationResult;
import com.pokergame.service.HandEvaluatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stateless showdown logic: hand evaluation, winner determination and pot payout.
 * <p>
 * {@link Game} remains the owner of all mutable state; this class only mutates the
 * {@link Player} chip stacks and hand fields it is handed, and returns the settlement.
 * </p>
 */
final class ShowdownResolver {
    private static final Logger logger = LoggerFactory.getLogger(ShowdownResolver.class);

    private ShowdownResolver() {
    }

    /**
     * Outcome of distributing the pot at showdown.
     *
     * @param winners   ordered unique players who won at least one allocation
     * @param remainder chips left in the table pot (integer-division remainders)
     * @param settlement settlement record for the hand
     */
    record Distribution(List<Player> winners, int remainder, HandSettlementResult settlement) {
    }

    /**
     * Evaluates the best hand for each player and stores it on the player.
     *
     * @param handEvaluator evaluator service
     * @param communityCards board cards
     * @param players players to evaluate
     */
    static void evaluateHands(HandEvaluatorService handEvaluator, List<Card> communityCards, List<Player> players) {
        for (Player player : players) {
            HandEvaluationResult result = handEvaluator.getBestHand(communityCards, player.getHoleCards());
            player.setBestHand(result.bestHand());
            player.setHandRank(result.handRank());
        }
    }

    /**
     * Refunds uncalled chips and awards each contested allocation to its winner(s).
     *
     * @param gameId game identifier (logging only)
     * @param handEvaluator evaluator service used for same-rank tie-breaks
     * @param computation pot layers and uncalled amounts
     * @param players all seated players (used to resolve refund recipients)
     * @return the distribution result
     */
    static Distribution distribute(String gameId, HandEvaluatorService handEvaluator,
            PotComputationResult computation, List<Player> players) {
        List<PotAllocation> allocations = computation.allocations();
        if (logger.isDebugEnabled()) {
            logger.debug("Side-pot allocations for game {}: {}", gameId, PotCalculator.allocationSummary(allocations));
        }

        Map<String, Integer> refunds = new HashMap<>();
        // Refund single-owner (uncalled) layers before contested awards.
        for (Map.Entry<String, Integer> entry : computation.uncalledByPlayer().entrySet()) {
            Player player = players.stream()
                    .filter(p -> p.getPlayerId().equals(entry.getKey()))
                    .findFirst()
                    .orElse(null);

            if (player == null || player.getHasFolded() || player.getIsOut() || entry.getValue() <= 0) {
                continue;
            }

            player.addChips(entry.getValue());
            refunds.merge(player.getPlayerId(), entry.getValue(), Integer::sum);
            logger.info("Refunded uncalled chips for game {} | player={} | amount={}",
                    gameId, player.getName(), entry.getValue());
        }

        Set<Player> uniqueWinners = new LinkedHashSet<>();
        int remainder = 0;
        Map<String, Integer> awards = new HashMap<>();

        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            List<Player> potWinners = determineWinners(handEvaluator, allocation.eligiblePlayers());

            if (potWinners.isEmpty()) {
                logger.warn("Pot {} has no winners. Keeping {} chips in pot.", i, allocation.amount());
                remainder += allocation.amount();
                continue;
            }

            int share = allocation.amount() / potWinners.size();
            // Keep modulo chips as table remainder.
            int potRemainder = allocation.amount() % potWinners.size();
            remainder += potRemainder;

            logger.info(
                    "Resolved side pot {} for game {} | amount={} | eligible={} | winners={} | share={} | remainder={}",
                    i, gameId, allocation.amount(),
                    PotCalculator.playerNames(allocation.eligiblePlayers()),
                    PotCalculator.playerNames(potWinners),
                    share, potRemainder);

            for (Player winner : potWinners) {
                winner.addChips(share);
                awards.merge(winner.getPlayerId(), share, Integer::sum);
            }

            uniqueWinners.addAll(potWinners);
        }

        HandSettlementResult settlement = new HandSettlementResult(awards, refunds,
                uniqueWinners.stream().map(Player::getPlayerId).collect(Collectors.toSet()),
                remainder, true);
        logger.info(
                "Side-pot distribution complete for game {} | uniqueWinners={} | refundedUncalled={} | potRemainder={}",
                gameId,
                PotCalculator.playerNames(new ArrayList<>(uniqueWinners)),
                refunds.values().stream().mapToInt(Integer::intValue).sum(),
                remainder);

        return new Distribution(new ArrayList<>(uniqueWinners), remainder, settlement);
    }

    /**
     * Determines the winning player(s) by comparing hand ranks, handling ties.
     *
     * @param handEvaluator evaluator service used for same-rank comparison
     * @param players players to compare (already evaluated)
     * @return winners; several on a tie, empty if no players
     */
    static List<Player> determineWinners(HandEvaluatorService handEvaluator, List<Player> players) {
        List<Player> sortablePlayers = new ArrayList<>(players);
        sortablePlayers.sort(Comparator.comparing(Player::getHandRank).reversed());

        if (sortablePlayers.isEmpty()) {
            return new ArrayList<>();
        }

        Player bestPlayer = sortablePlayers.getFirst();
        List<Player> winners = new ArrayList<>();
        winners.add(bestPlayer);

        for (int i = 1; i < sortablePlayers.size(); i++) {
            Player currentPlayer = sortablePlayers.get(i);
            if (currentPlayer.getHandRank() == bestPlayer.getHandRank()) {
                boolean challengerBeatsBest = handEvaluator.isBetterHandOfSameRank(
                        currentPlayer.getBestHand(),
                        bestPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                boolean bestBeatsChallenger = handEvaluator.isBetterHandOfSameRank(
                        bestPlayer.getBestHand(),
                        currentPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                if (challengerBeatsBest && !bestBeatsChallenger) {
                    bestPlayer = currentPlayer;
                    winners.clear();
                    winners.add(currentPlayer);
                } else if (!challengerBeatsBest && !bestBeatsChallenger) {
                    winners.add(currentPlayer);
                }
            } else {
                break;
            }
        }
        return winners;
    }
}
