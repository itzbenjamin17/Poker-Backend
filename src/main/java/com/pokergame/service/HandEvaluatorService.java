package com.pokergame.service;

import com.pokergame.model.Card;
import java.util.*;
import java.util.stream.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pokergame.model.HandEvaluationResult;
import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;

import org.springframework.stereotype.Service;

/**
 * Service for evaluating poker hands and determining hand rankings.
 * Handles generation of card combinations, hand evaluation, and comparison
 * of hands with the same rank to determine winners.
 */
@Service
public class HandEvaluatorService {
    private static final Logger logger = LoggerFactory.getLogger(HandEvaluatorService.class);

    /**
     * Generates all possible combinations of a specified number of cards from a
     * given list.
     *
     * @param cards      the list of cards to generate combinations from
     * @param numOfCards the number of cards in each combination
     * @return list of all possible card combinations
     */
    private List<List<Card>> generateCombinations(List<Card> cards, int numOfCards) {
        List<List<Card>> results = new ArrayList<>();
        if (cards.size() < numOfCards) {
            return results;
        }

        generateCombinationsHelper(cards, numOfCards, 0, new ArrayList<>(), results);
        return results;
    }

    /**
     * Recursive helper method for generating card combinations.
     *
     * @param cards              the list of cards to choose from
     * @param numOfCards         the target number of cards in each combination
     * @param startIndex         the starting index for the current iteration
     * @param currentCombination the current combination being built
     * @param results            the accumulated list of completed combinations
     */
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

    /**
     * Determines the best possible 5-card poker hand from community cards and
     * player hole cards.
     * Evaluates all possible 5-card combinations and returns the highest-ranking
     * hand.
     *
     * @param communityCards  the community cards on the table
     * @param playerHoleCards the player's hole cards
     * @return HandEvaluationResult containing the best hand and its rank
     */
    public HandEvaluationResult getBestHand(List<Card> communityCards, List<Card> playerHoleCards) {
        List<Card> allCards = new ArrayList<>(playerHoleCards);
        allCards.addAll(communityCards);
        List<List<Card>> combinations = generateCombinations(allCards, 5);

        List<Card> bestHand = null;
        HandRank bestRank = HandRank.HIGH_CARD;

        for (List<Card> combination : combinations) {
            List<Card> sortedCombination = new ArrayList<>(combination);
            sortedCombination.sort(Comparator.comparing(Card::getValue));
            HandRank rank = evaluateHand(sortedCombination);
            if (bestHand == null || rank.beats(bestRank)) {
                bestHand = new ArrayList<>(sortedCombination);
                bestRank = rank;
            } else if (rank == bestRank && rank != HandRank.HIGH_CARD) {
                if (isBetterHandOfSameRank(sortedCombination, bestHand, rank)) {
                    bestHand = new ArrayList<>(sortedCombination);
                }
            }

        }

        if (bestRank == HandRank.HIGH_CARD) {
            bestHand = getBestHighCardHand(allCards);

        }

        return new HandEvaluationResult(bestHand, bestRank);
    }

    /**
     * Compares two hands of the same rank to determine which is better.
     * Uses rank-specific comparison rules (e.g. comparing kickers for pairs).
     *
     * @param sortedCombination the first hand to compare (sorted by card value)
     * @param bestHand          the second hand to compare (sorted by card value)
     * @param rank              the hand rank being compared
     * @return true if sortedCombination is better than bestHand, false otherwise
     */
    public boolean isBetterHandOfSameRank(List<Card> sortedCombination, List<Card> bestHand, HandRank rank) {
        switch (rank) {
            case FOUR_OF_A_KIND -> {
                return compareFourOfAKind(sortedCombination, bestHand);
            }
            case FULL_HOUSE -> {
                return compareFullHouse(sortedCombination, bestHand);
            }
            case FLUSH -> {
                return compareFlush(sortedCombination, bestHand);
            }
            case STRAIGHT, STRAIGHT_FLUSH -> {
                return compareStraight(sortedCombination, bestHand);
            }
            case THREE_OF_A_KIND -> {
                return compareThreeOfAKind(sortedCombination, bestHand);
            }
            case TWO_PAIR -> {
                return compareTwoPair(sortedCombination, bestHand);
            }
            case ONE_PAIR -> {
                return compareOnePair(sortedCombination, bestHand);
            }
            case HIGH_CARD -> {
                return compareHighCard(sortedCombination, bestHand);
            }
            default -> {
                return false;
            }

        }
    }

    /**
     * Compares two one-pair hands. First compares the pair values, then compares
     * kickers
     * in descending order if pairs are equal.
     *
     * @param sortedCombination the first one-pair hand
     * @param bestHand          the second one-pair hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareOnePair(List<Card> sortedCombination, List<Card> bestHand) {
        // Get pair and kickers for sortedCombination
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups1 = sortedCombination.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> pair1 = groups1.values().stream()
                .filter(group -> group.size() == 2)
                .toList().getFirst();

        List<Card> kickers1 = sortedCombination.stream()
                .filter(card -> !pair1.contains(card))
                .sorted((c1, c2) -> Integer.compare(c2.getValue(), c1.getValue()))
                .toList();

        // Get pair and kickers for bestHand
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups2 = bestHand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> pair2 = groups2.values().stream()
                .filter(group -> group.size() == 2)
                .toList().getFirst();

        List<Card> kickers2 = bestHand.stream()
                .filter(card -> !pair2.contains(card))
                .sorted((c1, c2) -> Integer.compare(c2.getValue(), c1.getValue()))
                .toList();

        // Compare pair values first
        // noinspection DuplicatedCode
        if (pair1.getFirst().getValue() != pair2.getFirst().getValue()) {
            return pair1.getFirst().getValue() > pair2.getFirst().getValue();
        }

        // Compare kickers
        for (int i = 0; i < kickers1.size(); i++) {
            if (kickers1.get(i).getValue() != kickers2.get(i).getValue()) {
                return kickers1.get(i).getValue() > kickers2.get(i).getValue();
            }
        }

        return false; // Identical hands
    }

    /**
     * Compares two two-pair hands. Compares the higher pair first, then lower pair,
     * then kicker if necessary.
     *
     * @param sortedCombination the first two-pair hand
     * @param bestHand          the second two-pair hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareTwoPair(List<Card> sortedCombination, List<Card> bestHand) {
        // Get pairs and kicker for sortedCombination
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups1 = sortedCombination.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<List<Card>> pairs1 = groups1.values().stream()
                .filter(group -> group.size() == 2)
                .sorted((g1, g2) -> Integer.compare(g2.getFirst().getValue(), g1.getFirst().getValue()))
                .toList();

        Card kicker1 = groups1.values().stream()
                .filter(group -> group.size() == 1)
                .toList().getFirst().getFirst();

        // Get pairs and kicker for bestHand
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups2 = bestHand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<List<Card>> pairs2 = groups2.values().stream()
                .filter(group -> group.size() == 2)
                .sorted((g1, g2) -> Integer.compare(g2.getFirst().getValue(), g1.getFirst().getValue()))
                .toList();

        Card kicker2 = groups2.values().stream()
                .filter(group -> group.size() == 1)
                .toList().getFirst().getFirst();

        // Compare higher pair first
        if (pairs1.get(0).getFirst().getValue() != pairs2.get(0).getFirst().getValue()) {
            return pairs1.getFirst().getFirst().getValue() > pairs2.getFirst().getFirst().getValue();
        }

        // Compare lower pair
        if (pairs1.get(1).getFirst().getValue() != pairs2.get(1).getFirst().getValue()) {
            return pairs1.get(1).getFirst().getValue() > pairs2.get(1).getFirst().getValue();
        }

        // Compare kicker
        return kicker1.getValue() > kicker2.getValue();
    }

    /**
     * Compares two three-of-a-kind hands. First compares the triplet values,
     * then compares kickers in descending order if triplets are equal.
     *
     * @param sortedCombination the first three-of-a-kind hand
     * @param bestHand          the second three-of-a-kind hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareThreeOfAKind(List<Card> sortedCombination, List<Card> bestHand) {
        // Get three-of-a-kind and kickers for sortedCombination
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups1 = sortedCombination.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> threeOfKind1 = groups1.values().stream()
                .filter(group -> group.size() == 3)
                .toList().getFirst();

        List<Card> kickers1 = sortedCombination.stream()
                .filter(card -> !threeOfKind1.contains(card))
                .sorted((c1, c2) -> Integer.compare(c2.getValue(), c1.getValue()))
                .toList();

        // Get three-of-a-kind and kickers for bestHand
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups2 = bestHand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> threeOfKind2 = groups2.values().stream()
                .filter(group -> group.size() == 3)
                .toList().getFirst();

        List<Card> kickers2 = bestHand.stream()
                .filter(card -> !threeOfKind2.contains(card))
                .sorted((c1, c2) -> Integer.compare(c2.getValue(), c1.getValue()))
                .toList();

        // Compare three-of-a-kind values first
        // noinspection DuplicatedCode
        if (threeOfKind1.getFirst().getValue() != threeOfKind2.getFirst().getValue()) {
            return threeOfKind1.getFirst().getValue() > threeOfKind2.getFirst().getValue();
        }

        // Compare kickers
        for (int i = 0; i < kickers1.size(); i++) {
            if (kickers1.get(i).getValue() != kickers2.get(i).getValue()) {
                return kickers1.get(i).getValue() > kickers2.get(i).getValue();
            }
        }

        return false; // Identical hands
    }

    /**
     * Compares two straight hands by their highest card.
     * Handles the special case of low Ace straight (A,2,3,4,5) which is treated as
     * 5-high.
     *
     * @param sortedCombination the first straight hand (sorted ascending)
     * @param bestHand          the second straight hand (sorted ascending)
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareStraight(List<Card> sortedCombination, List<Card> bestHand) {
        // Get the highest card value (assuming cards are sorted ascending)
        int highCard1 = sortedCombination.get(4).getValue();
        int highCard2 = bestHand.get(4).getValue();

        // Handle low Ace straight (A,2,3,4,5) - treat as 5-high
        if (sortedCombination.get(0).rank().equals(Rank.TWO) &&
                sortedCombination.get(4).rank().equals(Rank.ACE)) {
            highCard1 = 5;
        }

        if (bestHand.get(0).rank().equals(Rank.TWO) &&
                bestHand.get(4).rank().equals(Rank.ACE)) {
            highCard2 = 5;
        }

        return highCard1 > highCard2;
    }

    /**
     * Compares two flush hands by comparing cards from highest to lowest.
     *
     * @param sortedCombination the first flush hand
     * @param bestHand          the second flush hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareFlush(List<Card> sortedCombination, List<Card> bestHand) {
        List<Card> sorted1 = new ArrayList<>(sortedCombination.reversed());
        List<Card> sorted2 = new ArrayList<>(bestHand.reversed());

        for (int i = 0; i < 5; i++) {
            if (sorted1.get(i).getValue() != sorted2.get(i).getValue()) {
                return sorted1.get(i).getValue() > sorted2.get(i).getValue();
            }
        }

        return false;
    }

    private boolean compareHighCard(List<Card> sortedCombination, List<Card> bestHand) {
        for (int i = 4; i >= 0; i--) {
            int currentValue = sortedCombination.get(i).getValue();
            int bestValue = bestHand.get(i).getValue();

            if (currentValue != bestValue) {
                return currentValue > bestValue;
            }
        }

        return false;
    }

    /**
     * Compares two full house hands. First compares the three-of-a-kind values,
     * then compares pair values if triplets are equal.
     *
     * @param sortedCombination the first full house hand
     * @param bestHand          the second full house hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareFullHouse(List<Card> sortedCombination, List<Card> bestHand) {
        // Get three-of-a-kind and pair for sortedCombination
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups1 = sortedCombination.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> threeOfKind1 = groups1.values().stream()
                .filter(group -> group.size() == 3)
                .toList().getFirst();

        List<Card> pair1 = groups1.values().stream()
                .filter(group -> group.size() == 2)
                .toList().getFirst();

        // Get three-of-a-kind and pair for bestHand
        @SuppressWarnings("DuplicatedCode")
        Map<Rank, List<Card>> groups2 = bestHand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        List<Card> threeOfKind2 = groups2.values().stream()
                .filter(group -> group.size() == 3)
                .toList().getFirst();

        List<Card> pair2 = groups2.values().stream()
                .filter(group -> group.size() == 2)
                .toList().getFirst();

        // Compare three-of-a-kind values first
        int threeValue1 = threeOfKind1.getFirst().getValue();
        int threeValue2 = threeOfKind2.getFirst().getValue();

        if (threeValue1 != threeValue2) {
            return threeValue1 > threeValue2;
        }

        // Compare pair values
        return pair1.getFirst().getValue() > pair2.getFirst().getValue();
    }

    /**
     * Compares two four-of-a-kind hands. First compares the quad values,
     * then compares kickers if quads are equal.
     *
     * @param sortedCombination the first four-of-a-kind hand
     * @param bestHand          the second four-of-a-kind hand
     * @return true if sortedCombination is better, false otherwise
     */
    private boolean compareFourOfAKind(List<Card> sortedCombination, List<Card> bestHand) {
        // Get four-of-a-kind values
        int quadValue1 = getFourOfAKindValue(sortedCombination);
        int quadValue2 = getFourOfAKindValue(bestHand);

        // Compare four-of-a-kind first
        if (quadValue1 != quadValue2) {
            return quadValue1 > quadValue2;
        }

        // If four-of-a-kind values are equal, compare kickers
        int kicker1 = getKickerValue(sortedCombination);
        int kicker2 = getKickerValue(bestHand);

        return kicker1 > kicker2;
    }

    /**
     * Extracts the value of the four-of-a-kind from a hand.
     *
     * @param hand the hand containing four-of-a-kind
     * @return the value of the four matching cards, or 0 if not found
     */
    private int getFourOfAKindValue(List<Card> hand) {
        Map<Rank, List<Card>> rankGroups = hand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        return rankGroups.values().stream()
                .filter(group -> group.size() == 4)
                .findFirst()
                .map(group -> group.getFirst().getValue()) // Use getValue() directly!
                .orElse(0);
    }

    /**
     * Extracts the value of the kicker (single card) from a hand.
     *
     * @param hand the hand to extract the kicker from
     * @return the value of the kicker card, or 0 if not found
     */
    private int getKickerValue(List<Card> hand) {
        Map<Rank, List<Card>> rankGroups = hand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        // Find the group with 1 card and get its value
        return rankGroups.values().stream()
                .filter(group -> group.size() == 1)
                .findFirst()
                .map(group -> group.getFirst().getValue()) // Use getValue() directly!
                .orElse(0);
    }

    /**
     * Returns the best 5-card high card hand from a list of cards.
     * Selects the five highest-value cards.
     *
     * @param cards the list of cards to select from
     * @return the best 5-card high card hand
     */
    private List<Card> getBestHighCardHand(List<Card> cards) {
        if (cards == null) {
            throw new BadRequestException("Card list cannot be null");
        }

        List<Card> sortedCards = new ArrayList<>(cards);
        sortedCards.sort((c1, c2) -> Integer.compare(c2.getValue(), c1.getValue()));
        // Not crashing if there are less than 5 cards, just return as many as we have
        // If there are less than 5 cards, something went wrong but just log it and
        // return what we have instead of crashing
        if (sortedCards.size() < 5) {
            logger.warn("Less than 5 cards available for high card hand: {}", sortedCards.size());
        }
        int handSize = Math.min(5, sortedCards.size());
        return new ArrayList<>(sortedCards.subList(0, handSize));
    }

    /**
     * Evaluates a 5-card poker hand and returns its rank.
     *
     * <p>
     * <b>IMPORTANT:</b> This method requires cards to be sorted in ascending order
     * by value
     * Use {@link #getBestHand(List, List)} which
     * handles
     * sorting automatically, or manually sort cards before calling this method.
     * </p>
     *
     * @param cards the 5-card hand to evaluate (must contain exactly 5 cards,
     *              sorted by value ascending)
     * @return the HandRank of the evaluated hand
     * @throws BadRequestException if the hand doesn't contain exactly 5 cards
     */
    public HandRank evaluateHand(List<Card> cards) {
        if (cards.size() != 5) {
            logger.error("Invalid hand size: {}, expected 5", cards.size());
            throw new BadRequestException("Invalid number of cards: " + cards.size());
        }

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
     * Checks if a hand is a royal flush (10, J, Q, K, A of the same suit).
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is a royal flush, false otherwise
     */
    private boolean isRoyalFlush(List<Card> hand) {
        Suit firstCardSuit = hand.getFirst().suit();

        return hand.stream().allMatch(card -> card.suit().equals(firstCardSuit) && card.getValue() >= 10);
    }

    /**
     * Checks if a hand is a straight flush (five consecutive cards of the same
     * suit).
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is a straight flush, false otherwise
     */
    private boolean isStraightFlush(List<Card> hand) {
        return isFlush(hand) && isStraight(hand);
    }

    private boolean isFourOfAKind(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        return rankCounts.containsValue(4L);
    }

    /**
     * Checks if a hand is a full house (three cards of one rank and two of
     * another).
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is a full house, false otherwise
     */
    private boolean isFullHouse(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        List<Long> counts = rankCounts.values().stream()
                .sorted(Collections.reverseOrder())
                .toList();

        return counts.size() == 2 && counts.get(0) == 3L && counts.get(1) == 2L;
    }

    /**
     * Checks if a hand is a flush (five cards of the same suit).
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is a flush, false otherwise
     */
    private boolean isFlush(List<Card> hand) {
        Suit firstCardSuit = hand.getFirst().suit();
        return hand.stream().allMatch(card -> card.suit().equals(firstCardSuit));
    }

    /**
     * Checks if a hand is a straight (five consecutive cards).
     * Handles the special case of low Ace straight (A,2,3,4,5).
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is a straight, false otherwise
     */
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

        return hand.get(0).rank().equals(Rank.TWO) &&
                hand.get(1).rank().equals(Rank.THREE) &&
                hand.get(2).rank().equals(Rank.FOUR) &&
                hand.get(3).rank().equals(Rank.FIVE) &&
                hand.get(4).rank().equals(Rank.ACE);
    }

    private boolean isThreeOfAKind(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        return rankCounts.containsValue(3L);
    }

    /**
     * Checks if a hand contains two pairs of cards with matching ranks.
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is two pair, false otherwise
     */
    private boolean isTwoPair(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        List<Long> counts = rankCounts.values().stream()
                .sorted(Collections.reverseOrder())
                .toList();

        return counts.size() == 3 && counts.get(0) == 2L && counts.get(1) == 2L && counts.get(2) == 1L;
    }

    /**
     * Checks if a hand contains exactly one pair of cards with matching ranks.
     *
     * @param hand the 5-card hand to check
     * @return true if the hand is one pair, false otherwise
     */
    private boolean isOnePair(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        return rankCounts.containsValue(2L);
    }

}
