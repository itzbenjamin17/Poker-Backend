package com.pokergame.model;

import com.pokergame.model.PotCalculator.PotAllocation;
import com.pokergame.model.PotCalculator.PotAllocations;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless showdown logic: hand evaluation, winner determination and pot payout.
 * <p>
 * {@link Game} remains the owner of all mutable state; this class only mutates the
 * {@link Player} chip stacks and hand fields it is handed, and returns the {@link HandPayout}.
 * </p>
 */
final class ShowdownResolver {
    private static final Logger logger = LoggerFactory.getLogger(ShowdownResolver.class);
    private static final HandEvaluator HAND_EVALUATOR = new HandEvaluator();

    private ShowdownResolver() {
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
     * Refunds uncalled bets, awards each pot to its winner(s), and credits chips directly to
     * the winning and refunded {@link Player} stacks.
     * <p>
     * Odd chips from uneven splits go one per winner clockwise starting to the dealer's left.
     * Any pot with no eligible winners is left in {@code remainder}.
     * </p>
     *
     * @param gameId         game ID for logging
     * @param potAllocations main/side pots and uncalled chips from {@link PotCalculator}
     * @param players        all seated players (used for refunds and fallback seat order)
     * @param handSeatOrder  clockwise player IDs for this hand; falls back to {@code players} order when empty
     * @param dealerPlayerId dealer's player ID for odd-chip ordering, or {@code null} to use seat order as-is
     * @param wentToShowdown whether two or more players reached showdown (recorded on {@link HandPayout})
     * @return unique winners, any unclaimed remainder chips, and the {@link HandPayout} summary
     */
    static Distribution distribute(String gameId,
            PotAllocations potAllocations, List<Player> players,
            List<String> handSeatOrder, String dealerPlayerId, boolean wentToShowdown) {
        Map<String, Integer> refunds = refundUncalledChips(potAllocations.uncalledByPlayer(), players);

        List<String> seatOrder = resolveSeatOrder(handSeatOrder, players);
        Set<Player> uniqueWinners = new LinkedHashSet<>();
        Map<String, Integer> potWinnings = new HashMap<>();
        int remainder = awardPots(
                potAllocations.allocations(), seatOrder, dealerPlayerId, uniqueWinners, potWinnings);

        HandPayout payout = new HandPayout(potWinnings, refunds,
                uniqueWinners.stream().map(Player::getPlayerId).collect(Collectors.toSet()),
                remainder, wentToShowdown);
        logger.debug("Showdown distribution complete for game {} | pots={} | winners={} | refunds={} | remainder={}",
                gameId, potAllocations.allocations().size(), payout.winnerIds(), refunds, remainder);

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

    // Refund single-owner (uncalled) chips before awarding contested pots.
    private static Map<String, Integer> refundUncalledChips(
            Map<String, Integer> uncalledByPlayer, List<Player> players) {
        Map<String, Player> playersById = players.stream()
                .collect(Collectors.toMap(Player::getPlayerId, Function.identity(), (first, second) -> first));
        Map<String, Integer> refunds = new HashMap<>();

        for (Map.Entry<String, Integer> entry : uncalledByPlayer.entrySet()) {
            Player player = playersById.get(entry.getKey());
            if (player == null || !player.isInHand() || entry.getValue() <= 0) {
                continue;
            }
            player.addChips(entry.getValue());
            refunds.merge(player.getPlayerId(), entry.getValue(), Integer::sum);
        }
        return refunds;
    }

    /**
     * Awards each main or side pot to its strongest eligible player(s), splitting ties evenly
     * and handing any odd chips one per winner clockwise starting to the dealer's left.
     *
     * @param allocations    main and side pots with their eligible contenders
     * @param seatOrder      clockwise player IDs used to order odd-chip payouts
     * @param dealerPlayerId dealer's player ID for odd-chip ordering, or {@code null} to keep seat order
     * @param uniqueWinners  accumulator populated with every player who won at least one pot
     * @param potWinnings    accumulator of total pot chips awarded per player ID
     * @return total chips from pots with no eligible winners (kept on the table for the next hand)
     */
    private static int awardPots(
            List<PotAllocation> allocations,
            List<String> seatOrder,
            String dealerPlayerId,
            Set<Player> uniqueWinners,
            Map<String, Integer> potWinnings) {
        int remainder = 0;

        // Looping through each pot 'layer' finding its winner(s) and awarding chips
        for (int i = 0; i < allocations.size(); i++) {
            PotAllocation allocation = allocations.get(i);
            List<Player> potWinners = determineWinners(allocation.eligiblePlayers());
            if (potWinners.isEmpty()) {
                // No contender is eligible for this layer (e.g. everyone eligible folded/left); carry it forward.
                logger.warn("Pot {} has no winners. Keeping {} chips in pot.", i, allocation.amount());
                remainder += allocation.amount();
                continue;
            }

            int baseShare = allocation.amount() / potWinners.size();
            int oddChips = allocation.amount() % potWinners.size();
            // On an uneven split, standard poker rules give the extra 1-chip remainders clockwise from the dealer's left.
            List<Player> payoutOrder = oddChips > 0
                    ? orderClockwiseFromDealer(potWinners, seatOrder, dealerPlayerId)
                    : potWinners;

            for (int w = 0; w < payoutOrder.size(); w++) {
                Player winner = payoutOrder.get(w);
                int payout = baseShare + (w < oddChips ? 1 : 0);
                winner.addChips(payout);
                potWinnings.merge(winner.getPlayerId(), payout, Integer::sum);
            }

            uniqueWinners.addAll(potWinners);
        }
        return remainder;
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

    /**
     * Outcome of distributing the pot at showdown.
     *
     * @param winners   ordered unique players who won at least one pot
     * @param remainder chips left in the table pot when a pot has no eligible winners
     * @param payout    payout summary for the hand
     */
    record Distribution(List<Player> winners, int remainder, HandPayout payout) {
    }
}
