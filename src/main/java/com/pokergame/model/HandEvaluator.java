package com.pokergame.model;

import java.util.*;
import java.util.stream.*;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;

import static java.util.Objects.requireNonNull;

/**
 * Works out the best poker hand a player can make and decides which of two equal-ranked hands
 * is stronger.
 */
public class HandEvaluator {

    /** The number of cards in a poker hand. */
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

        for (List<Card> combination : generateCombinations(allCards, HAND_SIZE)) {
            List<Card> sortedCombination = new ArrayList<>(combination);
            sortedCombination.sort(Comparator.comparing(Card::getValue));
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
     * Works out which poker hand a 5-card hand makes. The cards must be sorted from lowest to
     * highest value, which {@link #getBestHand(List, List)} does before calling this.
     *
     * @param cards exactly {@value #HAND_SIZE} cards, sorted from lowest to highest value
     * @return the hand rank the cards make
     */
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

    /**
     * Compares two 5-card hands that both make the same hand rank.
     *
     * <p>
     * Each hand is turned into a list of card values, most important first, and the first value
     * that differs decides the winner. Groups of cards that share a value count first: a bigger
     * group comes before a smaller one, and a higher value comes before a lower one. So a full
     * house lists its three of a kind and then its pair, and two pair lists the higher pair, the
     * lower pair and then the extra card. Straights are the exception: only the top card counts,
     * and in the ace-low straight (A-2-3-4-5) that top card is the five. Royal flushes always tie.
     *
     * @param first  one 5-card hand
     * @param second another 5-card hand with the same rank
     * @param rank   the hand rank both hands share
     * @return a positive number if {@code first} wins, a negative number if {@code second} wins,
     *         or {@code 0} if the hands tie
     * @throws IllegalStateException if either hand does not have exactly 5 cards
     */
    public int compareSameRank(List<Card> first, List<Card> second, HandRank rank) {
        if (first.size() != HAND_SIZE || second.size() != HAND_SIZE) {
            throw new IllegalStateException("Hands being compared must have exactly " + HAND_SIZE
                    + " cards, got " + first.size() + " and " + second.size());
        }
        return switch (rank) {
            case ROYAL_FLUSH, NO_HAND -> 0;
            case STRAIGHT, STRAIGHT_FLUSH -> Integer.compare(straightTopValue(first), straightTopValue(second));
            case FOUR_OF_A_KIND, FULL_HOUSE, FLUSH, THREE_OF_A_KIND, TWO_PAIR, ONE_PAIR, HIGH_CARD ->
                compareValues(tiebreakValues(first), tiebreakValues(second));
        };
    }

    /**
     * Lists a hand's card values from most to least important: bigger groups of equal cards
     * first, and within a group size the higher value first.
     */
    private List<Integer> tiebreakValues(List<Card> hand) {
        return rankCounts(hand).entrySet().stream()
                .sorted(Comparator.<Map.Entry<Rank, Long>, Long>comparing(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().getValue())
                        .reversed())
                .map(entry -> entry.getKey().getValue())
                .toList();
    }

    private int compareValues(List<Integer> first, List<Integer> second) {
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
            int difference = Integer.compare(first.get(i), second.get(i));
            if (difference != 0) {
                return difference;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    /** The top card of a straight; the ace counts as low (so the top is five) in A-2-3-4-5. */
    private int straightTopValue(List<Card> straight) {
        boolean hasAce = straight.stream().anyMatch(card -> card.rank() == Rank.ACE);
        boolean hasTwo = straight.stream().anyMatch(card -> card.rank() == Rank.TWO);
        if (hasAce && hasTwo) {
            return Rank.FIVE.getValue();
        }
        return straight.stream().mapToInt(Card::getValue).max().orElseThrow();
    }

    private List<List<Card>> generateCombinations(List<Card> cards, int numOfCards) {
        List<List<Card>> results = new ArrayList<>();
        if (cards.size() < numOfCards) {
            return results;
        }

        generateCombinationsHelper(cards, numOfCards, 0, new ArrayList<>(), results);
        return results;
    }

    private void generateCombinationsHelper(List<Card> cards, int numOfCards, int startIndex,
            List<Card> currentCombination, List<List<Card>> results) {
        if (currentCombination.size() == numOfCards) {
            results.add(new ArrayList<>(currentCombination));
            return;
        }

        for (int i = startIndex; i < cards.size(); i++) {
            currentCombination.add(cards.get(i));
            generateCombinationsHelper(cards, numOfCards, i + 1, currentCombination, results);
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

    /** How many cards of each rank the hand holds. */
    private static Map<Rank, Long> rankCounts(List<Card> hand) {
        return hand.stream().collect(Collectors.groupingBy(Card::rank, Collectors.counting()));
    }

    /** The rank counts from largest to smallest, for example {@code [3, 2]} for a full house. */
    private static List<Long> sortedCounts(List<Card> hand) {
        return rankCounts(hand).values().stream().sorted(Collections.reverseOrder()).toList();
    }

}
