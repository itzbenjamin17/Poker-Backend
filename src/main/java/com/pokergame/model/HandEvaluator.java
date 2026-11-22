package com.pokergame.model;

import static java.util.Objects.requireNonNull;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Works out the best poker hand a player can make and decides which of two equal-ranked hands
 * is stronger.
 */
public class HandEvaluator {

    private static final int HAND_SIZE = 5;

    /**
     * Finds the best 5-card poker hand a player can make from the community cards and their own
     * hole cards. The cards in the returned hand are always ordered from lowest to highest value,
     * whatever the hand rank. The ace counts as the highest card, so the ace-low straight comes
     * back as 2, 3, 4, 5, ace.
     *
     * @param communityCards  the community cards on the table
     * @param playerHoleCards the player's hole cards
     * @return the best 5-card hand (ordered low to high) and its rank
     * @throws IllegalArgumentException if there are fewer than 5 cards in total, for example
     *                                  because the board is not complete yet
     */
    public HandEvaluationResult getBestHand(List<Card> communityCards, List<Card> playerHoleCards) {
        requireNonNull(communityCards, "communityCards must not be null");
        requireNonNull(playerHoleCards, "playerHoleCards must not be null");

        List<Card> allCards = new ArrayList<>(playerHoleCards);
        allCards.addAll(communityCards);
        if (allCards.size() < HAND_SIZE) {
            throw new IllegalArgumentException(
                    "At least " + HAND_SIZE + " cards are required to evaluate a hand, got " + allCards.size());
        }

        List<Card> bestHand = null;
        HandRank bestRank = HandRank.HIGH_CARD;

        for (List<Card> combination : generateFiveCardCombinations(allCards)) {
            List<Card> sortedCombination = sortedByValue(combination);
            HandRank rank = evaluateHand(sortedCombination);
            if (bestHand == null || rank.beats(bestRank)
                    || (rank == bestRank && compareSameRank(sortedCombination, bestHand, rank) > 0)) {
                bestHand = sortedCombination;
                bestRank = rank;
            }
        }

        return new HandEvaluationResult(bestHand, bestRank);
    }

    /**
     * Breaks a tie between two 5-card hands sharing the same {@link HandRank}.
     *
     * @param first  first 5-card hand
     * @param second second 5-card hand
     * @param rank   hand rank both hands share
     * @return positive if {@code first} wins, negative if {@code second} wins, or {@code 0} on a tie
     * @throws IllegalStateException if either hand does not have 5 cards or does not match {@code rank}
     */
    public int compareSameRank(List<Card> first, List<Card> second, HandRank rank) {
        if (first.size() != HAND_SIZE || second.size() != HAND_SIZE) {
            throw new IllegalStateException("Hands being compared must have exactly " + HAND_SIZE
                    + " cards, got " + first.size() + " and " + second.size());
        }
        if (rank != HandRank.NO_HAND) {
            HandRank firstRank = evaluateHand(sortedByValue(first));
            HandRank secondRank = evaluateHand(sortedByValue(second));
            if (firstRank != rank || secondRank != rank) {
                throw new IllegalStateException("Hands being compared must both have rank " + rank
                        + ", got " + firstRank + " and " + secondRank);
            }
        }
        return switch (rank) {
            case ROYAL_FLUSH, NO_HAND -> 0;
            case STRAIGHT, STRAIGHT_FLUSH -> Integer.compare(straightTopValue(first), straightTopValue(second));
            case FOUR_OF_A_KIND, FULL_HOUSE, FLUSH, THREE_OF_A_KIND, TWO_PAIR, ONE_PAIR, HIGH_CARD ->
                compareValues(tiebreakValues(first), tiebreakValues(second));
        };
    }

    // Cards must be sorted lowest to highest, which getBestHand guarantees.
    private HandRank evaluateHand(List<Card> cards) {
        if (isRoyalFlush(cards))
            return HandRank.ROYAL_FLUSH;
        if (isStraightFlush(cards))
            return HandRank.STRAIGHT_FLUSH;
        if (isFourOfAKind(cards))
            return HandRank.FOUR_OF_A_KIND;
        if (isFullHouse(cards))
            return HandRank.FULL_HOUSE;
        if (isFlush(cards))
            return HandRank.FLUSH;
        if (isStraight(cards))
            return HandRank.STRAIGHT;
        if (isThreeOfAKind(cards))
            return HandRank.THREE_OF_A_KIND;
        if (isTwoPair(cards))
            return HandRank.TWO_PAIR;
        if (isOnePair(cards))
            return HandRank.ONE_PAIR;

        return HandRank.HIGH_CARD;
    }

    // Card values ordered most important first: larger groups first, higher values first within a group.
    private List<Integer> tiebreakValues(List<Card> hand) {
        return rankCounts(hand).entrySet().stream() // gives the list of pairs (Rank, count)
                .sorted(Comparator.<Map.Entry<Rank, Long>, Long>comparing(Map.Entry::getValue) // sorts by count
                        .thenComparing(entry -> entry.getKey().getValue()) // then sorts by rank
                        .reversed()) // biggest group first, then highest rank within group
                .map(entry -> entry.getKey().getValue()) // drops the counts and keeps only the ranks
                .toList();
    }

    private int compareValues(List<Integer> first, List<Integer> second) {
        for (int i = 0; i < first.size(); i++) {
            int difference = Integer.compare(first.get(i), second.get(i));
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    private static List<Card> sortedByValue(List<Card> cards) {
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt(Card::getValue));
        return sorted;
    }

    // Top card of a straight; ace counts as 5 in A-2-3-4-5.
    private int straightTopValue(List<Card> straight) {
        boolean hasAce = straight.stream().anyMatch(card -> card.rank() == Rank.ACE);
        boolean hasTwo = straight.stream().anyMatch(card -> card.rank() == Rank.TWO);
        if (hasAce && hasTwo) {
            return Rank.FIVE.getValue();
        }
        return straight.stream().mapToInt(Card::getValue).max().orElseThrow();
    }

    private List<List<Card>> generateFiveCardCombinations(List<Card> cards) {
        List<List<Card>> results = new ArrayList<>();
        generateCombinationsHelper(cards, 0, new ArrayList<>(), results);
        return results;
    }
    private void generateCombinationsHelper(
            List<Card> cards, int startIndex, List<Card> currentCombination, List<List<Card>> results) {
        if (currentCombination.size() == HAND_SIZE) {
            results.add(new ArrayList<>(currentCombination));
            return;
        }
        for (int i = startIndex; i < cards.size(); i++) {
            currentCombination.add(cards.get(i));
            generateCombinationsHelper(cards, i + 1, currentCombination, results);
            currentCombination.removeLast();
        }
    }

    private boolean isRoyalFlush(List<Card> hand) {
        return isFlush(hand) && hand.stream().allMatch(card -> card.getValue() >= 10);
    }

    private boolean isStraightFlush(List<Card> hand) {
        return isFlush(hand) && isStraight(hand);
    }

    private boolean isFourOfAKind(List<Card> hand) {
        return rankCounts(hand).containsValue(4L);
    }

    private boolean isFullHouse(List<Card> hand) {
        return sortedCounts(hand).equals(List.of(3L, 2L));
    }

    private boolean isFlush(List<Card> hand) {
        Suit firstCardSuit = hand.getFirst().suit();
        return hand.stream().allMatch(card -> card.suit().equals(firstCardSuit));
    }

    private boolean isStraight(List<Card> hand) {
        boolean regularStraight = true;
        for (int i = 0; i < hand.size() - 1; i++) {
            if (hand.get(i).getValue() + 1 != hand.get(i + 1).getValue()) {
                regularStraight = false;
                break;
            }
        }

        if (regularStraight)
            return true;

        // The ace-low straight A-2-3-4-5, with the ace sorted last.
        return hand.stream().map(Card::rank).toList()
                .equals(List.of(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.ACE));
    }

    private boolean isThreeOfAKind(List<Card> hand) {
        return rankCounts(hand).containsValue(3L);
    }

    private boolean isTwoPair(List<Card> hand) {
        return sortedCounts(hand).equals(List.of(2L, 2L, 1L));
    }

    private boolean isOnePair(List<Card> hand) {
        return rankCounts(hand).containsValue(2L);
    }

    private static Map<Rank, Long> rankCounts(List<Card> hand) {
        return hand.stream().collect(Collectors.groupingBy(Card::rank, Collectors.counting()));
    }

    private static List<Long> sortedCounts(List<Card> hand) {
        return rankCounts(hand).values().stream().sorted(Collections.reverseOrder()).toList();
    }

}
