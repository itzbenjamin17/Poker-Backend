package com.pokergame.service;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;

/**
 * Unit tests for the HandEvaluatorService class.
 */
@Tag("unit")
@DisplayName("Hand evaluator service")
class HandEvaluatorServiceTest {

        private HandEvaluatorService service;

        /**
         * Initializes the test fixtures before each scenario.
         */
        @BeforeEach
        void setUp() {
                service = new HandEvaluatorService();
        }

        @Test
        void highCardComparisonUsesDescendingBestHandsWithoutMutatingInputs() {
                List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.FOUR, Suit.HEARTS),
                                card(Rank.SEVEN, Suit.DIAMONDS), card(Rank.NINE, Suit.CLUBS),
                                card(Rank.JACK, Suit.SPADES));
                List<Card> aceHole = List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS));
                List<Card> kingHole = List.of(card(Rank.KING, Suit.HEARTS), card(Rank.EIGHT, Suit.DIAMONDS));
                List<Card> aceBefore = List.copyOf(aceHole);
                List<Card> kingBefore = List.copyOf(kingHole);
                HandEvaluationResult ace = service.getBestHand(board, aceHole);
                HandEvaluationResult king = service.getBestHand(board, kingHole);

                assertTrue(service.isBetterHandOfSameRank(ace.bestHand(), king.bestHand(), HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(king.bestHand(), ace.bestHand(), HandRank.HIGH_CARD));
                assertEquals(aceBefore, aceHole);
                assertEquals(kingBefore, kingHole);
        }

        @Test
        void highCardComparisonHandlesTiesAndKickersInEitherInputOrder() {
                List<Card> aceHigh = List.of(card(Rank.ACE, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                                card(Rank.NINE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES), card(Rank.FOUR, Suit.SPADES));
                List<Card> sameRanksOtherSuits = List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS),
                                card(Rank.NINE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS), card(Rank.FOUR, Suit.HEARTS));
                List<Card> lowerKicker = List.of(card(Rank.ACE, Suit.CLUBS), card(Rank.JACK, Suit.CLUBS),
                                card(Rank.NINE, Suit.CLUBS), card(Rank.SEVEN, Suit.CLUBS), card(Rank.THREE, Suit.CLUBS));
                List<Card> mutableDescending = new ArrayList<>(aceHigh);
                List<Card> before = List.copyOf(mutableDescending);

                assertFalse(service.isBetterHandOfSameRank(mutableDescending, sameRanksOtherSuits, HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(sameRanksOtherSuits, mutableDescending, HandRank.HIGH_CARD));
                assertTrue(service.isBetterHandOfSameRank(mutableDescending, lowerKicker, HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(lowerKicker, mutableDescending, HandRank.HIGH_CARD));
                assertEquals(before, mutableDescending);
        }

        private static Card card(Rank rank, Suit suit) { return new Card(rank, suit); }

        // Indirectly validates internal combination/high-card helpers through public
        // getBestHand API.
        /**
         * Protects the expected behavior for get best hand with insufficient total cards should return sorted available cards.
         */
        @Test
        void testGetBestHand_WithInsufficientTotalCards_ShouldReturnSortedAvailableCards() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FOUR, Suit.HEARTS));

                List<Card> holeCards = List.of(
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.HIGH_CARD, result.handRank());
                assertEquals(4, result.bestHand().size());
                assertEquals(Rank.KING, result.bestHand().get(0).rank());
                assertEquals(Rank.NINE, result.bestHand().get(1).rank());
                assertEquals(Rank.FOUR, result.bestHand().get(2).rank());
                assertEquals(Rank.TWO, result.bestHand().get(3).rank());
        }

        /**
         * Protects the expected behavior for get best hand with exactly five cards should evaluate the only combination.
         */
        @Test
        void testGetBestHand_WithExactlyFiveCards_ShouldEvaluateTheOnlyCombination() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.HEARTS));

                List<Card> holeCards = List.of(
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.HEARTS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.ROYAL_FLUSH, result.handRank());
                assertEquals(5, result.bestHand().size());
        }

        // Test evaluateHand - Royal Flush
        /**
         * Protects the expected behavior for evaluate hand royal flush.
         */
        @Test
        void testEvaluateHandRoyalFlush() {
                List<Card> royalFlush = List.of(
                                new Card(Rank.TEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.ACE, Suit.SPADES));

                assertEquals(HandRank.ROYAL_FLUSH, service.evaluateHand(royalFlush));
        }

        /**
         * Protects the expected behavior for evaluate hand straight flush.
         */
        @Test
        void testEvaluateHandStraightFlush() {
                List<Card> straightFlush = List.of(
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SIX, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.HEARTS),
                                new Card(Rank.EIGHT, Suit.HEARTS),
                                new Card(Rank.NINE, Suit.HEARTS));

                assertEquals(HandRank.STRAIGHT_FLUSH, service.evaluateHand(straightFlush));
        }

        /**
         * Protects the expected behavior for evaluate hand low ace straight flush.
         */
        @Test
        void testEvaluateHandLowAceStraightFlush() {
                List<Card> lowAceStraightFlush = List.of(
                                new Card(Rank.TWO, Suit.DIAMONDS),
                                new Card(Rank.THREE, Suit.DIAMONDS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.FIVE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.DIAMONDS));

                assertEquals(HandRank.STRAIGHT_FLUSH, service.evaluateHand(lowAceStraightFlush));
        }

        /**
         * Protects the expected behavior for evaluate hand four of a kind.
         */
        @Test
        void testEvaluateHandFourOfAKind() {
                List<Card> fourOfAKind = List.of(
                                new Card(Rank.NINE, Suit.HEARTS),
                                new Card(Rank.NINE, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                assertEquals(HandRank.FOUR_OF_A_KIND, service.evaluateHand(fourOfAKind));
        }

        /**
         * Protects the expected behavior for evaluate hand full house.
         */
        @Test
        void testEvaluateHandFullHouse() {
                List<Card> fullHouse = List.of(
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.FIVE, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS));

                assertEquals(HandRank.FULL_HOUSE, service.evaluateHand(fullHouse));
        }

        /**
         * Protects the expected behavior for evaluate hand flush.
         */
        @Test
        void testEvaluateHandFlush() {
                List<Card> flush = List.of(
                                new Card(Rank.TWO, Suit.CLUBS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.SEVEN, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.KING, Suit.CLUBS));

                assertEquals(HandRank.FLUSH, service.evaluateHand(flush));
        }

        /**
         * Protects the expected behavior for evaluate hand straight.
         */
        @Test
        void testEvaluateHandStraight() {
                List<Card> straight = List.of(
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SIX, Suit.DIAMONDS),
                                new Card(Rank.SEVEN, Suit.CLUBS),
                                new Card(Rank.EIGHT, Suit.SPADES),
                                new Card(Rank.NINE, Suit.HEARTS));

                assertEquals(HandRank.STRAIGHT, service.evaluateHand(straight));
        }

        /**
         * Protects the expected behavior for evaluate hand low ace straight.
         */
        @Test
        void testEvaluateHandLowAceStraight() {
                List<Card> lowAceStraight = List.of(
                                new Card(Rank.TWO, Suit.DIAMONDS),
                                new Card(Rank.THREE, Suit.CLUBS),
                                new Card(Rank.FOUR, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.HEARTS));

                assertEquals(HandRank.STRAIGHT, service.evaluateHand(lowAceStraight));
        }

        /**
         * Protects the expected behavior for evaluate hand three of a kind.
         */
        @Test
        void testEvaluateHandThreeOfAKind() {
                List<Card> threeOfAKind = List.of(
                                new Card(Rank.SEVEN, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.SEVEN, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.THREE, Suit.HEARTS));

                assertEquals(HandRank.THREE_OF_A_KIND, service.evaluateHand(threeOfAKind));
        }

        /**
         * Protects the expected behavior for evaluate hand two pair.
         */
        @Test
        void testEvaluateHandTwoPair() {
                List<Card> twoPair = List.of(
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.DIAMONDS),
                                new Card(Rank.THREE, Suit.CLUBS),
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                assertEquals(HandRank.TWO_PAIR, service.evaluateHand(twoPair));
        }

        /**
         * Protects the expected behavior for evaluate hand one pair.
         */
        @Test
        void testEvaluateHandOnePair() {
                List<Card> onePair = List.of(
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.TEN, Suit.DIAMONDS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.SEVEN, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                assertEquals(HandRank.ONE_PAIR, service.evaluateHand(onePair));
        }

        /**
         * Protects the expected behavior for evaluate hand high card.
         */
        @Test
        void testEvaluateHandHighCard() {
                List<Card> highCard = List.of(
                                new Card(Rank.TWO, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.DIAMONDS),
                                new Card(Rank.SEVEN, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                assertEquals(HandRank.HIGH_CARD, service.evaluateHand(highCard));
        }

        /**
         * Protects the expected behavior for evaluate hand invalid size.
         */
        @Test
        void testEvaluateHandInvalidSize() {
                List<Card> tooFewCards = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> service.evaluateHand(tooFewCards));
                assertTrue(exception.getMessage().contains("Invalid number of cards"));
        }

        // Test getBestHand
        /**
         * Protects the expected behavior for get best hand royal flush.
         */
        @Test
        void testGetBestHandRoyalFlush() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.TWO, Suit.HEARTS));

                List<Card> holeCards = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.THREE, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.ROYAL_FLUSH, result.handRank());
                assertEquals(5, result.bestHand().size());
        }

        /**
         * Protects the expected behavior for get best hand chooses best combination.
         */
        @Test
        void testGetBestHandChoosesBestCombination() {
                List<Card> communityCards = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> holeCards = List.of(
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.TWO, Suit.HEARTS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.FULL_HOUSE, result.handRank());
        }

        /**
         * Protects the expected behavior for get best hand high card.
         */
        @Test
        void testGetBestHandHighCard() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FOUR, Suit.HEARTS),
                                new Card(Rank.SIX, Suit.DIAMONDS),
                                new Card(Rank.EIGHT, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                List<Card> holeCards = List.of(
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.HIGH_CARD, result.handRank());
                // Should have the 5 highest cards
                assertTrue(result.bestHand().stream().anyMatch(c -> c.rank() == Rank.ACE));
                assertTrue(result.bestHand().stream().anyMatch(c -> c.rank() == Rank.KING));
        }

        /**
         * Protects the expected behavior for get best hand high card selection should keep top five cards.
         */
        @Test
        void testGetBestHand_HighCardSelection_ShouldKeepTopFiveCards() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                List<Card> holeCards = List.of(
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);
                List<Card> bestHand = result.bestHand();

                assertEquals(HandRank.HIGH_CARD, result.handRank());
                assertEquals(5, bestHand.size());
                assertEquals(Rank.ACE, bestHand.get(0).rank());
                assertEquals(Rank.KING, bestHand.get(1).rank());
                assertEquals(Rank.JACK, bestHand.get(2).rank());
                assertEquals(Rank.NINE, bestHand.get(3).rank());
                assertEquals(Rank.SEVEN, bestHand.get(4).rank());
        }

        // Test isBetterHandOfSameRank - One Pair
        /**
         * Protects the expected behavior for compare one pair higher pair wins.
         */
        @Test
        void testCompareOnePairHigherPairWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(hand2, hand1, HandRank.ONE_PAIR));
        }

        /**
         * Protects the expected behavior for compare one pair higher wins2.
         */
        @Test
        void testCompareOnePairHigherWins2() {
                List<Card> hand1 = List.of(
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.THREE, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.EIGHT, Suit.HEARTS),
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(hand2, hand1, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(hand1, hand2, HandRank.ONE_PAIR));
        }

        /**
         * Protects the expected behavior for compare one pair same pair better kicker.
         */
        @Test
        void testCompareOnePairSamePairBetterKicker() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.DIAMONDS),
                                new Card(Rank.TEN, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.ONE_PAIR));
        }

        // Test isBetterHandOfSameRank - Two Pair
        /**
         * Protects the expected behavior for compare two pair higher top pair wins.
         */
        @Test
        void testCompareTwoPairHigherTopPairWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.DIAMONDS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.TWO_PAIR));
        }

        /**
         * Protects the expected behavior for compare two pair same top pair better bottom pair.
         */
        @Test
        void testCompareTwoPairSameTopPairBetterBottomPair() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.TWO_PAIR));
        }

        // Test isBetterHandOfSameRank - Three of a Kind
        /**
         * Protects the expected behavior for compare three of a kind higher triplet wins.
         */
        @Test
        void testCompareThreeOfAKindHigherTripletWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.THREE_OF_A_KIND));
        }

        // Test isBetterHandOfSameRank - Straight
        /**
         * Protects the expected behavior for compare straight higher top card wins.
         */
        @Test
        void testCompareStraightHigherTopCardWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.TEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.DIAMONDS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.FIVE, Suit.SPADES),
                                new Card(Rank.SIX, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.EIGHT, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.STRAIGHT));
        }

        /**
         * Protects the expected behavior for compare straight low ace loses to regular straight.
         */
        @Test
        void testCompareStraightLowAceLosesToRegularStraight() {
                List<Card> lowAceStraight = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.THREE, Suit.HEARTS),
                                new Card(Rank.FOUR, Suit.DIAMONDS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.SPADES));

                List<Card> regularStraight = List.of(
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.FOUR, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.DIAMONDS),
                                new Card(Rank.SIX, Suit.CLUBS),
                                new Card(Rank.SEVEN, Suit.HEARTS));

                assertFalse(service.isBetterHandOfSameRank(lowAceStraight, regularStraight, HandRank.STRAIGHT));
        }

        // Test isBetterHandOfSameRank - Flush
        /**
         * Protects the expected behavior for compare flush higher cards win.
         */
        @Test
        void testCompareFlushHigherCardsWin() {
                List<Card> hand1 = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.SPADES),
                                new Card(Rank.SEVEN, Suit.SPADES),
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.TWO, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.HEARTS),
                                new Card(Rank.NINE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.FLUSH));
        }

        // Test isBetterHandOfSameRank - Full House
        /**
         * Protects the expected behavior for compare full house higher triplet wins.
         */
        @Test
        void testCompareFullHouseHigherTripletWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.FULL_HOUSE));
        }

        /**
         * Protects the expected behavior for compare full house same triplet better pair wins.
         */
        @Test
        void testCompareFullHouseSameTripletBetterPairWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.FULL_HOUSE));
        }

        // Test isBetterHandOfSameRank - Four of a Kind
        /**
         * Protects the expected behavior for compare four of a kind higher quad wins.
         */
        @Test
        void testCompareFourOfAKindHigherQuadWins() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.FOUR_OF_A_KIND));
        }

        /**
         * Protects the expected behavior for compare four of a kind same quad better kicker.
         */
        @Test
        void testCompareFourOfAKindSameQuadBetterKicker() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(hand1, hand2, HandRank.FOUR_OF_A_KIND));
        }

        // Test edge cases
        /**
         * Protects the expected behavior for compare IDentical hands.
         */
        @Test
        void testCompareIdenticalHands() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS),
                                new Card(Rank.QUEEN, Suit.CLUBS),
                                new Card(Rank.JACK, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.DIAMONDS));

                assertFalse(service.isBetterHandOfSameRank(hand1, hand2, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(hand2, hand1, HandRank.ONE_PAIR));
        }

        /**
         * Protects the expected behavior for compare high card ace high beats king high.
         */
        @Test
        void testCompareHighCardAceHighBeatsKingHigh() {
                List<Card> aceHigh = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.NINE, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.ACE, Suit.SPADES));

                List<Card> kingHigh = List.of(
                                new Card(Rank.TWO, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.JACK, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(aceHigh, kingHigh, HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(kingHigh, aceHigh, HandRank.HIGH_CARD));
        }

        /**
         * Protects the expected behavior for compare high card same top card uses next kicker.
         */
        @Test
        void testCompareHighCardSameTopCardUsesNextKicker() {
                List<Card> betterKicker = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.EIGHT, Suit.HEARTS),
                                new Card(Rank.TEN, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.DIAMONDS),
                                new Card(Rank.ACE, Suit.SPADES));

                List<Card> weakerKicker = List.of(
                                new Card(Rank.TWO, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS));

                assertTrue(service.isBetterHandOfSameRank(betterKicker, weakerKicker, HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(weakerKicker, betterKicker, HandRank.HIGH_CARD));
        }

        /**
         * Protects the expected behavior for compare high card IDentical returns tie edge case.
         */
        @Test
        void testCompareHighCardIdenticalReturnsTieEdgeCase() {
                List<Card> hand1 = List.of(
                                new Card(Rank.THREE, Suit.SPADES),
                                new Card(Rank.SEVEN, Suit.HEARTS),
                                new Card(Rank.NINE, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.KING, Suit.SPADES));

                List<Card> hand2 = List.of(
                                new Card(Rank.THREE, Suit.HEARTS),
                                new Card(Rank.SEVEN, Suit.DIAMONDS),
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.JACK, Suit.HEARTS),
                                new Card(Rank.KING, Suit.DIAMONDS));

                assertFalse(service.isBetterHandOfSameRank(hand1, hand2, HandRank.HIGH_CARD));
                assertFalse(service.isBetterHandOfSameRank(hand2, hand1, HandRank.HIGH_CARD));
        }

        /**
         * Protects the expected behavior for get best hand with seven cards.
         */
        @Test
        void testGetBestHandWithSevenCards() {
                List<Card> communityCards = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS),
                                new Card(Rank.QUEEN, Suit.DIAMONDS),
                                new Card(Rank.JACK, Suit.CLUBS),
                                new Card(Rank.TEN, Suit.SPADES));

                List<Card> holeCards = List.of(
                                new Card(Rank.NINE, Suit.HEARTS),
                                new Card(Rank.TWO, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                // Should form straight with A-K-Q-J-T
                assertEquals(HandRank.STRAIGHT, result.handRank());
        }

        /**
         * Protects the expected behavior for straight flush comparison in get best hand.
         */
        @Test
        void testStraightFlushComparisonInGetBestHand() {
                List<Card> communityCards = List.of(
                                new Card(Rank.FIVE, Suit.SPADES),
                                new Card(Rank.SIX, Suit.SPADES),
                                new Card(Rank.SEVEN, Suit.SPADES),
                                new Card(Rank.EIGHT, Suit.SPADES),
                                new Card(Rank.TWO, Suit.HEARTS));

                List<Card> holeCards = List.of(
                                new Card(Rank.NINE, Suit.SPADES),
                                new Card(Rank.THREE, Suit.DIAMONDS));

                HandEvaluationResult result = service.getBestHand(communityCards, holeCards);

                assertEquals(HandRank.STRAIGHT_FLUSH, result.handRank());
        }

        private static List<Card> hand(Card... cards) { return List.of(cards); }

        private static List<Rank> ranksOf(List<Card> cards) {
                return cards.stream().map(Card::rank).toList();
        }

        @Test
        @DisplayName("Royal flushes of different suits tie through the default comparison arm")
        void royalFlushesOfDifferentSuitsTie() {
                List<Card> spades = hand(card(Rank.TEN, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                                card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> hearts = hand(card(Rank.TEN, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS),
                                card(Rank.QUEEN, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                                card(Rank.ACE, Suit.HEARTS));

                assertEquals(HandRank.ROYAL_FLUSH, service.evaluateHand(spades));
                assertEquals(HandRank.ROYAL_FLUSH, service.evaluateHand(hearts));
                assertFalse(service.isBetterHandOfSameRank(spades, hearts, HandRank.ROYAL_FLUSH));
                assertFalse(service.isBetterHandOfSameRank(hearts, spades, HandRank.ROYAL_FLUSH));
        }

        @Test
        @DisplayName("Two players sharing a royal flush on the board tie")
        void royalFlushOnBoardIsATieForBothPlayers() {
                List<Card> board = List.of(card(Rank.TEN, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                                card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.SPADES));

                HandEvaluationResult first = service.getBestHand(board,
                                List.of(card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS)));
                HandEvaluationResult second = service.getBestHand(board,
                                List.of(card(Rank.FOUR, Suit.CLUBS), card(Rank.NINE, Suit.DIAMONDS)));

                assertEquals(HandRank.ROYAL_FLUSH, first.handRank());
                assertEquals(HandRank.ROYAL_FLUSH, second.handRank());
                assertEquals(first.bestHand(), second.bestHand());
                assertFalse(service.isBetterHandOfSameRank(first.bestHand(), second.bestHand(), first.handRank()));
                assertFalse(service.isBetterHandOfSameRank(second.bestHand(), first.bestHand(), second.handRank()));
        }

        @Test
        @DisplayName("The wheel (A-2-3-4-5) is evaluated as a straight ordered with the ace last")
        void wheelIsEvaluatedAsStraightFromSevenCards() {
                List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                                card(Rank.KING, Suit.SPADES));

                HandEvaluationResult result = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.NINE, Suit.DIAMONDS)));

                assertEquals(HandRank.STRAIGHT, result.handRank());
                assertEquals(List.of(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.ACE),
                                ranksOf(result.bestHand()));
        }

        @Test
        @DisplayName("A six-high straight is preferred over the wheel when both are available")
        void sixHighStraightIsChosenOverWheelInBestHand() {
                List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                                card(Rank.SIX, Suit.SPADES));

                HandEvaluationResult result = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.KING, Suit.DIAMONDS)));

                assertEquals(HandRank.STRAIGHT, result.handRank());
                assertEquals(List.of(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX),
                                ranksOf(result.bestHand()));
        }

        @Test
        @DisplayName("Wheel versus wheel is a tie in both directions")
        void wheelVersusWheelIsATie() {
                List<Card> first = hand(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> second = hand(card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS),
                                card(Rank.FOUR, Suit.CLUBS), card(Rank.FIVE, Suit.SPADES),
                                card(Rank.ACE, Suit.HEARTS));

                assertFalse(service.isBetterHandOfSameRank(first, second, HandRank.STRAIGHT));
                assertFalse(service.isBetterHandOfSameRank(second, first, HandRank.STRAIGHT));
        }

        @Test
        @DisplayName("A six-high straight beats the wheel and the wheel does not beat it")
        void wheelVersusSixHighStraight() {
                List<Card> wheel = hand(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.HEARTS),
                                card(Rank.FOUR, Suit.DIAMONDS), card(Rank.FIVE, Suit.CLUBS),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> sixHigh = hand(card(Rank.TWO, Suit.DIAMONDS), card(Rank.THREE, Suit.CLUBS),
                                card(Rank.FOUR, Suit.SPADES), card(Rank.FIVE, Suit.HEARTS),
                                card(Rank.SIX, Suit.DIAMONDS));

                assertTrue(service.isBetterHandOfSameRank(sixHigh, wheel, HandRank.STRAIGHT));
                assertFalse(service.isBetterHandOfSameRank(wheel, sixHigh, HandRank.STRAIGHT));
        }

        @Test
        @DisplayName("Straight flush comparison also treats the wheel as five-high")
        void wheelStraightFlushLosesToSixHighStraightFlush() {
                List<Card> wheel = hand(card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.HEARTS),
                                card(Rank.FOUR, Suit.HEARTS), card(Rank.FIVE, Suit.HEARTS),
                                card(Rank.ACE, Suit.HEARTS));
                List<Card> sixHigh = hand(card(Rank.TWO, Suit.SPADES), card(Rank.THREE, Suit.SPADES),
                                card(Rank.FOUR, Suit.SPADES), card(Rank.FIVE, Suit.SPADES),
                                card(Rank.SIX, Suit.SPADES));

                assertTrue(service.isBetterHandOfSameRank(sixHigh, wheel, HandRank.STRAIGHT_FLUSH));
                assertFalse(service.isBetterHandOfSameRank(wheel, sixHigh, HandRank.STRAIGHT_FLUSH));
        }

        @Test
        @DisplayName("Two pair with equal pairs is decided by the kicker")
        void twoPairWithEqualPairsIsDecidedByKicker() {
                List<Card> queenKicker = hand(card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS), card(Rank.ACE, Suit.CLUBS),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> jackKicker = hand(card(Rank.JACK, Suit.HEARTS), card(Rank.KING, Suit.CLUBS),
                                card(Rank.KING, Suit.SPADES), card(Rank.ACE, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(queenKicker, jackKicker, HandRank.TWO_PAIR));
                assertFalse(service.isBetterHandOfSameRank(jackKicker, queenKicker, HandRank.TWO_PAIR));
        }

        @Test
        @DisplayName("Two pair with equal pairs and an identical kicker is a tie")
        void twoPairWithIdenticalKickerIsATie() {
                List<Card> first = hand(card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS), card(Rank.ACE, Suit.CLUBS),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> second = hand(card(Rank.QUEEN, Suit.HEARTS), card(Rank.KING, Suit.CLUBS),
                                card(Rank.KING, Suit.SPADES), card(Rank.ACE, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.HEARTS));

                assertFalse(service.isBetterHandOfSameRank(first, second, HandRank.TWO_PAIR));
                assertFalse(service.isBetterHandOfSameRank(second, first, HandRank.TWO_PAIR));
        }

        @Test
        @DisplayName("With three pairs the highest remaining card is used as the two pair kicker")
        void threePairsUseHighestRemainingCardAsKicker() {
                List<Card> board = List.of(card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.QUEEN, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS),
                                card(Rank.JACK, Suit.SPADES));

                HandEvaluationResult aceKicker = service.getBestHand(board,
                                List.of(card(Rank.JACK, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS)));
                HandEvaluationResult jackKicker = service.getBestHand(board,
                                List.of(card(Rank.JACK, Suit.DIAMONDS), card(Rank.TWO, Suit.CLUBS)));

                assertEquals(HandRank.TWO_PAIR, aceKicker.handRank());
                assertTrue(ranksOf(aceKicker.bestHand()).contains(Rank.ACE));
                assertFalse(ranksOf(aceKicker.bestHand()).contains(Rank.JACK));
                assertEquals(HandRank.TWO_PAIR, jackKicker.handRank());
                assertEquals(1, ranksOf(jackKicker.bestHand()).stream().filter(Rank.JACK::equals).count());
                assertTrue(service.isBetterHandOfSameRank(aceKicker.bestHand(), jackKicker.bestHand(),
                                HandRank.TWO_PAIR));
                assertFalse(service.isBetterHandOfSameRank(jackKicker.bestHand(), aceKicker.bestHand(),
                                HandRank.TWO_PAIR));
        }

        @Test
        @DisplayName("Flushes with identical ranks in different suits tie")
        void flushesWithIdenticalRanksTie() {
                List<Card> spades = hand(card(Rank.THREE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.NINE, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> hearts = hand(card(Rank.THREE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS),
                                card(Rank.NINE, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                                card(Rank.ACE, Suit.HEARTS));

                assertFalse(service.isBetterHandOfSameRank(spades, hearts, HandRank.FLUSH));
                assertFalse(service.isBetterHandOfSameRank(hearts, spades, HandRank.FLUSH));
        }

        @Test
        @DisplayName("Flushes sharing the top card are decided by the first differing lower card")
        void flushesAreDecidedByFirstDifferingCard() {
                List<Card> aceKingNine = hand(card(Rank.THREE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.NINE, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> aceKingEight = hand(card(Rank.THREE, Suit.HEARTS), card(Rank.SEVEN, Suit.HEARTS),
                                card(Rank.EIGHT, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                                card(Rank.ACE, Suit.HEARTS));
                List<Card> aceQueen = hand(card(Rank.THREE, Suit.CLUBS), card(Rank.SEVEN, Suit.CLUBS),
                                card(Rank.NINE, Suit.CLUBS), card(Rank.QUEEN, Suit.CLUBS),
                                card(Rank.ACE, Suit.CLUBS));
                List<Card> lowestCardDiffers = hand(card(Rank.TWO, Suit.DIAMONDS), card(Rank.SEVEN, Suit.DIAMONDS),
                                card(Rank.NINE, Suit.DIAMONDS), card(Rank.KING, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.DIAMONDS));

                assertTrue(service.isBetterHandOfSameRank(aceKingNine, aceKingEight, HandRank.FLUSH));
                assertFalse(service.isBetterHandOfSameRank(aceKingEight, aceKingNine, HandRank.FLUSH));
                assertTrue(service.isBetterHandOfSameRank(aceKingNine, aceQueen, HandRank.FLUSH));
                assertFalse(service.isBetterHandOfSameRank(aceQueen, aceKingNine, HandRank.FLUSH));
                assertTrue(service.isBetterHandOfSameRank(aceKingNine, lowestCardDiffers, HandRank.FLUSH));
                assertFalse(service.isBetterHandOfSameRank(lowestCardDiffers, aceKingNine, HandRank.FLUSH));
        }

        @Test
        @DisplayName("With six suited cards the best five form the flush")
        void bestFlushUsesTheFiveHighestSuitedCards() {
                List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.NINE, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.THREE, Suit.HEARTS));

                HandEvaluationResult result = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.SPADES), card(Rank.FOUR, Suit.SPADES)));

                assertEquals(HandRank.FLUSH, result.handRank());
                assertEquals(List.of(Rank.FOUR, Rank.SEVEN, Rank.NINE, Rank.KING, Rank.ACE),
                                ranksOf(result.bestHand()));
        }

        @Test
        @DisplayName("Four of a kind with equal quads is decided by the kicker and ties on an equal kicker")
        void fourOfAKindWithEqualQuadsUsesKicker() {
                List<Card> kingKicker = hand(card(Rank.KING, Suit.SPADES), card(Rank.ACE, Suit.SPADES),
                                card(Rank.ACE, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.CLUBS));
                List<Card> queenKicker = hand(card(Rank.QUEEN, Suit.HEARTS), card(Rank.ACE, Suit.SPADES),
                                card(Rank.ACE, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.CLUBS));
                List<Card> kingKickerOtherSuit = hand(card(Rank.KING, Suit.HEARTS), card(Rank.ACE, Suit.SPADES),
                                card(Rank.ACE, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.CLUBS));

                assertFalse(service.isBetterHandOfSameRank(queenKicker, kingKicker, HandRank.FOUR_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(kingKicker, kingKickerOtherSuit, HandRank.FOUR_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(kingKickerOtherSuit, kingKicker, HandRank.FOUR_OF_A_KIND));
        }

        @Test
        @DisplayName("Quads on a shared board are decided by each player's best kicker")
        void sharedBoardQuadsAreDecidedByKicker() {
                List<Card> board = List.of(card(Rank.ACE, Suit.SPADES), card(Rank.ACE, Suit.HEARTS),
                                card(Rank.ACE, Suit.DIAMONDS), card(Rank.ACE, Suit.CLUBS),
                                card(Rank.TWO, Suit.SPADES));

                HandEvaluationResult kingKicker = service.getBestHand(board,
                                List.of(card(Rank.KING, Suit.HEARTS), card(Rank.QUEEN, Suit.DIAMONDS)));
                HandEvaluationResult fourKicker = service.getBestHand(board,
                                List.of(card(Rank.THREE, Suit.DIAMONDS), card(Rank.FOUR, Suit.DIAMONDS)));
                HandEvaluationResult alsoKingKicker = service.getBestHand(board,
                                List.of(card(Rank.KING, Suit.CLUBS), card(Rank.NINE, Suit.CLUBS)));

                assertEquals(HandRank.FOUR_OF_A_KIND, kingKicker.handRank());
                assertEquals(HandRank.FOUR_OF_A_KIND, fourKicker.handRank());
                assertTrue(service.isBetterHandOfSameRank(kingKicker.bestHand(), fourKicker.bestHand(),
                                HandRank.FOUR_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(fourKicker.bestHand(), kingKicker.bestHand(),
                                HandRank.FOUR_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(kingKicker.bestHand(), alsoKingKicker.bestHand(),
                                HandRank.FOUR_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(alsoKingKicker.bestHand(), kingKicker.bestHand(),
                                HandRank.FOUR_OF_A_KIND));
        }

        @Test
        @DisplayName("Full houses with the same triplet are decided by the pair and tie when equal")
        void fullHousesWithEqualTripletsAreDecidedByPair() {
                List<Card> kingsOverQueens = hand(card(Rank.QUEEN, Suit.SPADES), card(Rank.QUEEN, Suit.HEARTS),
                                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS));
                List<Card> kingsOverJacks = hand(card(Rank.JACK, Suit.SPADES), card(Rank.JACK, Suit.HEARTS),
                                card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS));
                List<Card> kingsOverQueensOtherSuits = hand(card(Rank.QUEEN, Suit.DIAMONDS),
                                card(Rank.QUEEN, Suit.CLUBS), card(Rank.KING, Suit.SPADES),
                                card(Rank.KING, Suit.HEARTS), card(Rank.KING, Suit.CLUBS));

                assertTrue(service.isBetterHandOfSameRank(kingsOverQueens, kingsOverJacks, HandRank.FULL_HOUSE));
                assertFalse(service.isBetterHandOfSameRank(kingsOverJacks, kingsOverQueens, HandRank.FULL_HOUSE));
                assertFalse(service.isBetterHandOfSameRank(kingsOverQueens, kingsOverQueensOtherSuits,
                                HandRank.FULL_HOUSE));
                assertFalse(service.isBetterHandOfSameRank(kingsOverQueensOtherSuits, kingsOverQueens,
                                HandRank.FULL_HOUSE));
        }

        @Test
        @DisplayName("Full houses built from a shared board are decided by the secondary pair")
        void sharedBoardFullHousesAreDecidedByPair() {
                List<Card> board = List.of(card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS),
                                card(Rank.JACK, Suit.SPADES));

                HandEvaluationResult queens = service.getBestHand(board,
                                List.of(card(Rank.QUEEN, Suit.SPADES), card(Rank.TWO, Suit.DIAMONDS)));
                HandEvaluationResult jacks = service.getBestHand(board,
                                List.of(card(Rank.JACK, Suit.HEARTS), card(Rank.TWO, Suit.CLUBS)));

                assertEquals(HandRank.FULL_HOUSE, queens.handRank());
                assertEquals(HandRank.FULL_HOUSE, jacks.handRank());
                assertTrue(service.isBetterHandOfSameRank(queens.bestHand(), jacks.bestHand(), HandRank.FULL_HOUSE));
                assertFalse(service.isBetterHandOfSameRank(jacks.bestHand(), queens.bestHand(), HandRank.FULL_HOUSE));
        }

        @Test
        @DisplayName("Two available triplets make the higher triplet and the lower pair")
        void twoTripletsMakeFullHouseWithHigherTriplet() {
                List<Card> board = List.of(card(Rank.KING, Suit.SPADES), card(Rank.KING, Suit.HEARTS),
                                card(Rank.KING, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS),
                                card(Rank.QUEEN, Suit.SPADES));

                HandEvaluationResult result = service.getBestHand(board,
                                List.of(card(Rank.QUEEN, Suit.HEARTS), card(Rank.TWO, Suit.DIAMONDS)));

                assertEquals(HandRank.FULL_HOUSE, result.handRank());
                assertEquals(3, ranksOf(result.bestHand()).stream().filter(Rank.KING::equals).count());
                assertEquals(2, ranksOf(result.bestHand()).stream().filter(Rank.QUEEN::equals).count());
        }

        @Test
        @DisplayName("One pair with equal pairs is decided by the first, middle or last differing kicker")
        void onePairWithEqualPairsIsDecidedByDifferingKicker() {
                List<Card> aceKingFive = hand(card(Rank.FIVE, Suit.SPADES), card(Rank.NINE, Suit.SPADES),
                                card(Rank.NINE, Suit.HEARTS), card(Rank.KING, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.CLUBS));
                List<Card> aceKingFour = hand(card(Rank.FOUR, Suit.HEARTS), card(Rank.NINE, Suit.DIAMONDS),
                                card(Rank.NINE, Suit.CLUBS), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.HEARTS));
                List<Card> aceQueenFive = hand(card(Rank.FIVE, Suit.HEARTS), card(Rank.NINE, Suit.SPADES),
                                card(Rank.NINE, Suit.CLUBS), card(Rank.QUEEN, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> kingQueenJack = hand(card(Rank.NINE, Suit.SPADES), card(Rank.NINE, Suit.DIAMONDS),
                                card(Rank.JACK, Suit.HEARTS), card(Rank.QUEEN, Suit.CLUBS),
                                card(Rank.KING, Suit.HEARTS));

                assertTrue(service.isBetterHandOfSameRank(aceKingFive, aceKingFour, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(aceKingFour, aceKingFive, HandRank.ONE_PAIR));
                assertTrue(service.isBetterHandOfSameRank(aceKingFive, aceQueenFive, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(aceQueenFive, aceKingFive, HandRank.ONE_PAIR));
                assertTrue(service.isBetterHandOfSameRank(aceQueenFive, kingQueenJack, HandRank.ONE_PAIR));
                assertFalse(service.isBetterHandOfSameRank(kingQueenJack, aceQueenFive, HandRank.ONE_PAIR));
        }

        @Test
        @DisplayName("Three of a kind with equal triplets is decided by the first or second kicker")
        void threeOfAKindWithEqualTripletsIsDecidedByKicker() {
                List<Card> aceThree = hand(card(Rank.THREE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.DIAMONDS),
                                card(Rank.ACE, Suit.CLUBS));
                List<Card> kingQueen = hand(card(Rank.SEVEN, Suit.SPADES), card(Rank.SEVEN, Suit.HEARTS),
                                card(Rank.SEVEN, Suit.CLUBS), card(Rank.QUEEN, Suit.DIAMONDS),
                                card(Rank.KING, Suit.HEARTS));
                List<Card> kingFive = hand(card(Rank.FIVE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.DIAMONDS),
                                card(Rank.KING, Suit.CLUBS));
                List<Card> kingFour = hand(card(Rank.FOUR, Suit.HEARTS), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.CLUBS),
                                card(Rank.KING, Suit.DIAMONDS));

                assertTrue(service.isBetterHandOfSameRank(aceThree, kingQueen, HandRank.THREE_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(kingQueen, aceThree, HandRank.THREE_OF_A_KIND));
                assertTrue(service.isBetterHandOfSameRank(kingFive, kingFour, HandRank.THREE_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(kingFour, kingFive, HandRank.THREE_OF_A_KIND));
        }

        @Test
        @DisplayName("Three of a kind with equal triplets and identical kickers is a tie")
        void threeOfAKindWithIdenticalKickersIsATie() {
                List<Card> first = hand(card(Rank.FIVE, Suit.SPADES), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.DIAMONDS),
                                card(Rank.KING, Suit.CLUBS));
                List<Card> second = hand(card(Rank.FIVE, Suit.HEARTS), card(Rank.SEVEN, Suit.SPADES),
                                card(Rank.SEVEN, Suit.HEARTS), card(Rank.SEVEN, Suit.CLUBS),
                                card(Rank.KING, Suit.DIAMONDS));

                assertFalse(service.isBetterHandOfSameRank(first, second, HandRank.THREE_OF_A_KIND));
                assertFalse(service.isBetterHandOfSameRank(second, first, HandRank.THREE_OF_A_KIND));
        }
}
