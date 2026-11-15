package com.pokergame.model;

import java.util.*;
import java.util.stream.*;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;

import static java.util.Objects.requireNonNull;

/**
 * Evaluates poker hands and determines hand rankings.
 * Handles generation of card combinations, hand evaluation, and comparison
 * of hands with the same rank to determine winners.
 */
public class HandEvaluator {

    /**
     * Determines the best possible 5-card poker hand from the community cards and a player's hole
     * cards, ordered low to high by card value for every hand rank.
     *
     * @param communityCards  the community cards on the table
     * @param playerHoleCards the player's hole cards
     * @return the best 5-card hand (ordered low to high) and its rank
     * @throws IllegalArgumentException if fewer than 5 total cards are provided
     */
    public HandEvaluationResult getBestHand(List<Card> communityCards, List<Card> playerHoleCards) {
        requireNonNull(communityCards, "communityCards must not be null");
        requireNonNull(playerHoleCards, "playerHoleCards must not be null");

        List<Card> allCards = new ArrayList<>(playerHoleCards);
        allCards.addAll(communityCards);
        if (allCards.size() < 5) {
            throw new IllegalArgumentException(
                    "At least 5 cards are required to evaluate a hand, got " + allCards.size());
        }

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
            } else if (rank == bestRank) {
                if (isBetterHandOfSameRank(sortedCombination, bestHand, rank)) {
                    bestHand = new ArrayList<>(sortedCombination);
                }
            }
        }

        return new HandEvaluationResult(bestHand, bestRank);
    }

    /**
     * Evaluates a 5-card poker hand and returns its rank. Cards must be sorted in ascending
     * order by value (which {@link #getBestHand(List, List)} does automatically).
     *
     * @param cards the 5-card hand sorted by ascending card value
     * @return the evaluated {@link HandRank}
     * @throws IllegalStateException if {@code cards} does not contain exactly 5 cards
     */
    public HandRank evaluateHand(List<Card> cards) {
        if (cards.size() != 5) {
            throw new IllegalStateException("Invalid number of cards: " + cards.size());
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
     * Compares two 5-card hands of the same rank to determine whether {@code sortedCombination}
     * beats {@code bestHand}.
     *
     * @param sortedCombination the candidate hand sorted by ascending card value
     * @param bestHand          the current best hand sorted by ascending card value
     * @param rank              the shared hand rank being compared
     * @return {@code true} if {@code sortedCombination} beats {@code bestHand}
     */
    public boolean isBetterHandOfSameRank(List<Card> sortedCombination, List<Card> bestHand, HandRank rank) {
        return switch (rank) {
            case FOUR_OF_A_KIND -> compareFourOfAKind(sortedCombination, bestHand);
            case FULL_HOUSE -> compareFullHouse(sortedCombination, bestHand);
            case FLUSH -> compareFlush(sortedCombination, bestHand);
            case STRAIGHT, STRAIGHT_FLUSH -> compareStraight(sortedCombination, bestHand);
            case THREE_OF_A_KIND -> compareThreeOfAKind(sortedCombination, bestHand);
            case TWO_PAIR -> compareTwoPair(sortedCombination, bestHand);
            case ONE_PAIR -> compareOnePair(sortedCombination, bestHand);
            case HIGH_CARD -> compareHighCard(sortedCombination, bestHand);
            default -> false;
        };
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
        // Normalize copies to ascending value order so callers passing either ascending
        // or descending 5-card hands compare identically without mutating inputs.
        List<Card> first = new ArrayList<>(sortedCombination);
        List<Card> second = new ArrayList<>(bestHand);
        first.sort(Comparator.comparing(Card::getValue));
        second.sort(Comparator.comparing(Card::getValue));
        for (int i = 4; i >= 0; i--) {
            int currentValue = first.get(i).getValue();
            int bestValue = second.get(i).getValue();

            if (currentValue != bestValue) {
                return currentValue > bestValue;
            }
        }

        return false;
    }

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

    private int getFourOfAKindValue(List<Card> hand) {
        Map<Rank, List<Card>> rankGroups = hand.stream()
                .collect(Collectors.groupingBy(Card::rank));

        return rankGroups.values().stream()
                .filter(group -> group.size() == 4)
                .findFirst()
                .map(group -> group.getFirst().getValue()) // Use getValue() directly!
                .orElse(0);
    }

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

    private boolean isRoyalFlush(List<Card> hand) {
        Suit firstCardSuit = hand.getFirst().suit();

        return hand.stream().allMatch(card -> card.suit().equals(firstCardSuit) && card.getValue() >= 10);
    }

    private boolean isStraightFlush(List<Card> hand) {
        return isFlush(hand) && isStraight(hand);
    }

    private boolean isFourOfAKind(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        return rankCounts.containsValue(4L);
    }

    private boolean isFullHouse(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        List<Long> counts = rankCounts.values().stream()
                .sorted(Collections.reverseOrder())
                .toList();

        return counts.size() == 2 && counts.get(0) == 3L && counts.get(1) == 2L;
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

    private boolean isTwoPair(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        List<Long> counts = rankCounts.values().stream()
                .sorted(Collections.reverseOrder())
                .toList();

        return counts.size() == 3 && counts.get(0) == 2L && counts.get(1) == 2L && counts.get(2) == 1L;
    }

    private boolean isOnePair(List<Card> hand) {
        Map<Rank, Long> rankCounts = hand.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()));

        return rankCounts.containsValue(2L);
    }

}
