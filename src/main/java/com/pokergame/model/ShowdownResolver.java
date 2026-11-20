package com.pokergame.model;

import com.pokergame.model.PotCalculator.PotAllocation;
import com.pokergame.model.PotCalculator.PotAllocations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    private static final HandEvaluator HAND_EVALUATOR = new HandEvaluator();

    private ShowdownResolver() {
    }

    /**
     * Outcome of distributing the pot at showdown.
     *
     * @param winners   ordered unique players who won at least one allocation
     * @param remainder chips left in the table pot when an allocation has no eligible winners
     * @param payout    payout summary for the hand
     */
    record Distribution(List<Player> winners, int remainder, HandPayout payout) {
    }

    /**
     * Evaluates the best hand for each player and stores it on the player.
     *
     * @param communityCards board cards
     * @param players players to evaluate
     */
    static void evaluateHands(List<Card> communityCards, List<Player> players) {
        for (Player player : players) {
            HandEvaluationResult result = HAND_EVALUATOR.getBestHand(communityCards, player.getHoleCards());
            player.setBestHand(result.bestHand());
            player.setHandRank(result.handRank());
        }
    }

    /**
     * Refunds uncalled chips and awards each contested allocation to its winner(s).
     * Odd chips from uneven splits are awarded one per winner clockwise starting to the dealer's left.
     *
     * @param gameId         game identifier (logging only)
     * @param computation    pot layers and uncalled amounts
     * @param players        all seated players (used to resolve refund recipients and fallback seat order)
     * @param handSeatOrder  clockwise player IDs for the current hand; falls back to {@code players} order when empty
     * @param dealerPlayerId player ID holding the dealer button for odd-chip ordering, or {@code null}
     * @param wentToShowdown whether two or more players had their hands evaluated, recorded on the settlement
     * @return the distribution result
     */
    static Distribution distribute(String gameId,
            PotAllocations computation, List<Player> players,
            List<String> handSeatOrder, String dealerPlayerId, boolean wentToShowdown) {
        List<PotAllocation> allocations = computation.allocations();
        Map<String, Player> playersById = players.stream()
                .collect(Collectors.toMap(Player::getPlayerId, Function.identity(), (first, second) -> first));

        Map<String, Integer> refunds = new HashMap<>();
        // Refund single-owner (uncalled) layers before contested awards.
        for (Map.Entry<String, Integer> entry : computation.uncalledByPlayer().entrySet()) {
            Player player = playersById.get(entry.getKey());

            if (player == null || !player.isInHand() || entry.getValue() <= 0) {
                continue;
            }

            player.addChips(entry.getValue());
            refunds.merge(player.getPlayerId(), entry.getValue(), Integer::sum);
        }

        List<String> seatOrder = resolveSeatOrder(handSeatOrder, players);
        Set<Player> uniqueWinners = new LinkedHashSet<>();
        int remainder = 0;
        Map<String, Integer> awards = new HashMap<>();

        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            List<Player> potWinners = determineWinners(allocation.eligiblePlayers());

            if (potWinners.isEmpty()) {
                logger.warn("Pot {} has no winners. Keeping {} chips in pot.", i, allocation.amount());
                remainder += allocation.amount();
                continue;
            }

            int baseShare = allocation.amount() / potWinners.size();
            int oddChips = allocation.amount() % potWinners.size();
            List<Player> payoutOrder = oddChips > 0
                    ? orderClockwiseFromDealer(potWinners, seatOrder, dealerPlayerId)
                    : potWinners;

            for (int w = 0; w < payoutOrder.size(); w++) {
                Player winner = payoutOrder.get(w);
                int payout = baseShare + (w < oddChips ? 1 : 0);
                winner.addChips(payout);
                awards.merge(winner.getPlayerId(), payout, Integer::sum);
            }

            uniqueWinners.addAll(potWinners);
        }

        HandPayout payout = new HandPayout(awards, refunds,
                uniqueWinners.stream().map(Player::getPlayerId).collect(Collectors.toSet()),
                remainder, wentToShowdown);
        logger.debug("Showdown distribution complete for game {} | pots={} | winners={} | refunds={} | remainder={}",
                gameId, allocations.size(), payout.winnerIds(), refunds, remainder);

        return new Distribution(new ArrayList<>(uniqueWinners), remainder, payout);
    }

    /**
     * Determines the winning player(s) by comparing hand ranks, handling ties.
     *
     * @param players players to compare (already evaluated)
     * @return winners; several on a tie, empty if no players
     */
    static List<Player> determineWinners(List<Player> players) {
        if (players.isEmpty()) {
            return List.of();
        }

        List<Player> sortablePlayers = new ArrayList<>(players);
        sortablePlayers.sort(Comparator.comparing(Player::getHandRank).reversed());

        Player bestPlayer = sortablePlayers.getFirst();
        List<Player> winners = new ArrayList<>();
        winners.add(bestPlayer);

        for (int i = 1; i < sortablePlayers.size(); i++) {
            Player currentPlayer = sortablePlayers.get(i);
            if (currentPlayer.getHandRank() == bestPlayer.getHandRank()) {
                int comparison = HAND_EVALUATOR.compareSameRank(
                        currentPlayer.getBestHand(),
                        bestPlayer.getBestHand(),
                        bestPlayer.getHandRank());

                if (comparison > 0) {
                    bestPlayer = currentPlayer;
                    winners.clear();
                    winners.add(currentPlayer);
                } else if (comparison == 0) {
                    winners.add(currentPlayer);
                }
            } else {
                break;
            }
        }
        return winners;
    }

    private static List<String> resolveSeatOrder(List<String> handSeatOrder, List<Player> players) {
        if (!handSeatOrder.isEmpty()) {
            return handSeatOrder;
        }
        if (players.isEmpty()) {
            return List.of();
        }
        return players.stream()
                .map(Player::getPlayerId)
                .toList();
    }

    private static List<Player> orderClockwiseFromDealer(
            List<Player> potWinners, List<String> seatOrder, String dealerPlayerId) {
        if (seatOrder.isEmpty() || potWinners.size() <= 1) {
            return potWinners;
        }
        int anchorIndex = dealerPlayerId != null ? seatOrder.indexOf(dealerPlayerId) : -1;
        int seatCount = seatOrder.size();
        List<Player> ordered = new ArrayList<>(potWinners);
        ordered.sort(Comparator.comparingInt(winner -> {
            int seatIndex = seatOrder.indexOf(winner.getPlayerId());
            if (seatIndex < 0) {
                return Integer.MAX_VALUE;
            }
            if (anchorIndex < 0) {
                return seatIndex;
            }
            return Math.floorMod(seatIndex - anchorIndex - 1, seatCount);
        }));
        return ordered;
    }
}
