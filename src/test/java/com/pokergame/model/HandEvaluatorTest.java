package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;

/**
 * Unit tests for the HandEvaluator class.
 */
@Tag("unit")
@DisplayName("Hand evaluator")
class HandEvaluatorTest {

        private HandEvaluator service;

        /**
         * Initializes the test fixtures before each scenario.
         */
        @BeforeEach
        void setUp() {
                service = new HandEvaluator();
        }

        @Test
        void highCardComparisonUsesLowToHighBestHandsWithoutMutatingInputs() {
                List<Card> board = List.of(card(Rank.TWO, Suit.SPADES), card(Rank.FOUR, Suit.HEARTS),
                                card(Rank.SEVEN, Suit.DIAMONDS), card(Rank.NINE, Suit.CLUBS),
                                card(Rank.JACK, Suit.SPADES));
                List<Card> aceHole = List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.THREE, Suit.DIAMONDS));
                List<Card> kingHole = List.of(card(Rank.KING, Suit.HEARTS), card(Rank.EIGHT, Suit.DIAMONDS));
                List<Card> aceBefore = List.copyOf(aceHole);
                List<Card> kingBefore = List.copyOf(kingHole);
                HandEvaluationResult ace = service.getBestHand(board, aceHole);
                HandEvaluationResult king = service.getBestHand(board, kingHole);

                assertEquals(List.of(Rank.FOUR, Rank.SEVEN, Rank.NINE, Rank.JACK, Rank.ACE),
                                ranksOf(ace.bestHand()));
                assertEquals(List.of(Rank.SEVEN, Rank.EIGHT, Rank.NINE, Rank.JACK, Rank.KING),
                                ranksOf(king.bestHand()));
                assertTrue(service.compareSameRank(ace.bestHand(), king.bestHand(), HandRank.HIGH_CARD) > 0);
                assertTrue(service.compareSameRank(king.bestHand(), ace.bestHand(), HandRank.HIGH_CARD) < 0);
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

                assertEquals(0, service.compareSameRank(mutableDescending, sameRanksOtherSuits, HandRank.HIGH_CARD));
                assertEquals(0, service.compareSameRank(sameRanksOtherSuits, mutableDescending, HandRank.HIGH_CARD));
                assertTrue(service.compareSameRank(mutableDescending, lowerKicker, HandRank.HIGH_CARD) > 0);
                assertTrue(service.compareSameRank(lowerKicker, mutableDescending, HandRank.HIGH_CARD) < 0);
                assertEquals(before, mutableDescending);
        }

        private static Card card(Rank rank, Suit suit) { return new Card(rank, suit); }

        // Indirectly validates internal combination/high-card helpers through public
        // getBestHand API.
        /**
         * Protects the expected behavior for get best hand with fewer than five cards throwing IllegalArgumentException.
         */
        @Test
        void testGetBestHand_WithFewerThanFiveCards_ShouldThrowIllegalArgumentException() {
                List<Card> communityCards = List.of(
                                new Card(Rank.TWO, Suit.SPADES),
                                new Card(Rank.FOUR, Suit.HEARTS));

                List<Card> holeCards = List.of(
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.NINE, Suit.DIAMONDS));

                IllegalArgumentException exception = assertThrows(
                                IllegalArgumentException.class,
                                () -> service.getBestHand(communityCards, holeCards));
                assertTrue(exception.getMessage().contains("5"));
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

                assertEquals(HandRank.ROYAL_FLUSH, rankOf(royalFlush));
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

                assertEquals(HandRank.STRAIGHT_FLUSH, rankOf(straightFlush));
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

                assertEquals(HandRank.STRAIGHT_FLUSH, rankOf(lowAceStraightFlush));
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

                assertEquals(HandRank.FOUR_OF_A_KIND, rankOf(fourOfAKind));
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

                assertEquals(HandRank.FULL_HOUSE, rankOf(fullHouse));
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

                assertEquals(HandRank.FLUSH, rankOf(flush));
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

                assertEquals(HandRank.STRAIGHT, rankOf(straight));
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

                assertEquals(HandRank.STRAIGHT, rankOf(lowAceStraight));
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

                assertEquals(HandRank.THREE_OF_A_KIND, rankOf(threeOfAKind));
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

                assertEquals(HandRank.TWO_PAIR, rankOf(twoPair));
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

                assertEquals(HandRank.ONE_PAIR, rankOf(onePair));
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

                assertEquals(HandRank.HIGH_CARD, rankOf(highCard));
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
         * Protects the expected behavior for get best hand high card selection should keep top five cards low to high.
         */
        @Test
        void testGetBestHand_HighCardSelection_ShouldKeepTopFiveCardsLowToHigh() {
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
                assertEquals(List.of(Rank.SEVEN, Rank.NINE, Rank.JACK, Rank.KING, Rank.ACE),
                                ranksOf(bestHand));
        }

        @Test
        @DisplayName("High-card best hands compare ties and kickers accurately from seven cards")
        void highCardBestHandsCompareTiesAndKickersFromSevenCards() {
                List<Card> board = List.of(
                                card(Rank.TWO, Suit.SPADES),
                                card(Rank.FIVE, Suit.HEARTS),
                                card(Rank.SEVEN, Suit.DIAMONDS),
                                card(Rank.JACK, Suit.CLUBS),
                                card(Rank.KING, Suit.SPADES));

                HandEvaluationResult aceQueen = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.HEARTS), card(Rank.QUEEN, Suit.DIAMONDS)));
                HandEvaluationResult aceTen = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.CLUBS), card(Rank.TEN, Suit.DIAMONDS)));
                HandEvaluationResult aceQueenTie = service.getBestHand(board,
                                List.of(card(Rank.ACE, Suit.DIAMONDS), card(Rank.QUEEN, Suit.CLUBS)));

                assertEquals(HandRank.HIGH_CARD, aceQueen.handRank());
                assertEquals(List.of(Rank.SEVEN, Rank.JACK, Rank.QUEEN, Rank.KING, Rank.ACE),
                                ranksOf(aceQueen.bestHand()));
                assertEquals(List.of(Rank.SEVEN, Rank.TEN, Rank.JACK, Rank.KING, Rank.ACE),
                                ranksOf(aceTen.bestHand()));
                assertTrue(service.compareSameRank(aceQueen.bestHand(), aceTen.bestHand(), HandRank.HIGH_CARD) > 0);
                assertTrue(service.compareSameRank(aceTen.bestHand(), aceQueen.bestHand(), HandRank.HIGH_CARD) < 0);
                assertEquals(0, service.compareSameRank(aceQueen.bestHand(), aceQueenTie.bestHand(),
                        HandRank.HIGH_CARD));
                assertEquals(0, service.compareSameRank(aceQueenTie.bestHand(), aceQueen.bestHand(),
                        HandRank.HIGH_CARD));
        }

        // Test compareSameRank - One Pair
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.ONE_PAIR) > 0);
                assertTrue(service.compareSameRank(hand2, hand1, HandRank.ONE_PAIR) < 0);
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

                assertTrue(service.compareSameRank(hand2, hand1, HandRank.ONE_PAIR) > 0);
                assertTrue(service.compareSameRank(hand1, hand2, HandRank.ONE_PAIR) < 0);
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.ONE_PAIR) > 0);
        }

        // Test compareSameRank - Two Pair
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.TWO_PAIR) > 0);
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.TWO_PAIR) > 0);
        }

        // Test compareSameRank - Three of a Kind
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.THREE_OF_A_KIND) > 0);
        }

        // Test compareSameRank - Straight
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.STRAIGHT) > 0);
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

                assertTrue(service.compareSameRank(lowAceStraight, regularStraight, HandRank.STRAIGHT) < 0);
        }

        // Test compareSameRank - Flush
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.FLUSH) > 0);
        }

        // Test compareSameRank - Full House
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.FULL_HOUSE) > 0);
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.FULL_HOUSE) > 0);
        }

        // Test compareSameRank - Four of a Kind
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.FOUR_OF_A_KIND) > 0);
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

                assertTrue(service.compareSameRank(hand1, hand2, HandRank.FOUR_OF_A_KIND) > 0);
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

                assertEquals(0, service.compareSameRank(hand1, hand2, HandRank.ONE_PAIR));
                assertEquals(0, service.compareSameRank(hand2, hand1, HandRank.ONE_PAIR));
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

                assertTrue(service.compareSameRank(aceHigh, kingHigh, HandRank.HIGH_CARD) > 0);
                assertTrue(service.compareSameRank(kingHigh, aceHigh, HandRank.HIGH_CARD) < 0);
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

                assertTrue(service.compareSameRank(betterKicker, weakerKicker, HandRank.HIGH_CARD) > 0);
                assertTrue(service.compareSameRank(weakerKicker, betterKicker, HandRank.HIGH_CARD) < 0);
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

                assertEquals(0, service.compareSameRank(hand1, hand2, HandRank.HIGH_CARD));
                assertEquals(0, service.compareSameRank(hand2, hand1, HandRank.HIGH_CARD));
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

        /** The hand rank a 5-card hand makes, found through the public best-hand lookup. */
        private HandRank rankOf(List<Card> fiveCards) {
                return service.getBestHand(List.of(), fiveCards).handRank();
        }

        private static List<Rank> ranksOf(List<Card> cards) {
                return cards.stream().map(Card::rank).toList();
        }

        @Test
        @DisplayName("Royal flushes of different suits tie")
        void royalFlushesOfDifferentSuitsTie() {
                List<Card> spades = hand(card(Rank.TEN, Suit.SPADES), card(Rank.JACK, Suit.SPADES),
                                card(Rank.QUEEN, Suit.SPADES), card(Rank.KING, Suit.SPADES),
                                card(Rank.ACE, Suit.SPADES));
                List<Card> hearts = hand(card(Rank.TEN, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS),
                                card(Rank.QUEEN, Suit.HEARTS), card(Rank.KING, Suit.HEARTS),
                                card(Rank.ACE, Suit.HEARTS));

                assertEquals(HandRank.ROYAL_FLUSH, rankOf(spades));
                assertEquals(HandRank.ROYAL_FLUSH, rankOf(hearts));
                assertEquals(0, service.compareSameRank(spades, hearts, HandRank.ROYAL_FLUSH));
                assertEquals(0, service.compareSameRank(hearts, spades, HandRank.ROYAL_FLUSH));
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
                assertEquals(0, service.compareSameRank(first.bestHand(), second.bestHand(), first.handRank()));
                assertEquals(0, service.compareSameRank(second.bestHand(), first.bestHand(), second.handRank()));
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

                assertEquals(0, service.compareSameRank(first, second, HandRank.STRAIGHT));
                assertEquals(0, service.compareSameRank(second, first, HandRank.STRAIGHT));
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

                assertTrue(service.compareSameRank(sixHigh, wheel, HandRank.STRAIGHT) > 0);
                assertTrue(service.compareSameRank(wheel, sixHigh, HandRank.STRAIGHT) < 0);
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

                assertTrue(service.compareSameRank(sixHigh, wheel, HandRank.STRAIGHT_FLUSH) > 0);
                assertTrue(service.compareSameRank(wheel, sixHigh, HandRank.STRAIGHT_FLUSH) < 0);
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

                assertTrue(service.compareSameRank(queenKicker, jackKicker, HandRank.TWO_PAIR) > 0);
                assertTrue(service.compareSameRank(jackKicker, queenKicker, HandRank.TWO_PAIR) < 0);
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

                assertEquals(0, service.compareSameRank(first, second, HandRank.TWO_PAIR));
                assertEquals(0, service.compareSameRank(second, first, HandRank.TWO_PAIR));
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
                assertTrue(service.compareSameRank(aceKicker.bestHand(), jackKicker.bestHand(), HandRank.TWO_PAIR) > 0);
                assertTrue(service.compareSameRank(jackKicker.bestHand(), aceKicker.bestHand(), HandRank.TWO_PAIR) < 0);
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

                assertEquals(0, service.compareSameRank(spades, hearts, HandRank.FLUSH));
                assertEquals(0, service.compareSameRank(hearts, spades, HandRank.FLUSH));
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

                assertTrue(service.compareSameRank(aceKingNine, aceKingEight, HandRank.FLUSH) > 0);
                assertTrue(service.compareSameRank(aceKingEight, aceKingNine, HandRank.FLUSH) < 0);
                assertTrue(service.compareSameRank(aceKingNine, aceQueen, HandRank.FLUSH) > 0);
                assertTrue(service.compareSameRank(aceQueen, aceKingNine, HandRank.FLUSH) < 0);
                assertTrue(service.compareSameRank(aceKingNine, lowestCardDiffers, HandRank.FLUSH) > 0);
                assertTrue(service.compareSameRank(lowestCardDiffers, aceKingNine, HandRank.FLUSH) < 0);
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

                assertTrue(service.compareSameRank(kingKicker, queenKicker, HandRank.FOUR_OF_A_KIND) > 0);
                assertTrue(service.compareSameRank(queenKicker, kingKicker, HandRank.FOUR_OF_A_KIND) < 0);
                assertEquals(0, service.compareSameRank(kingKicker, kingKickerOtherSuit, HandRank.FOUR_OF_A_KIND));
                assertEquals(0, service.compareSameRank(kingKickerOtherSuit, kingKicker, HandRank.FOUR_OF_A_KIND));
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
                assertTrue(service.compareSameRank(kingKicker.bestHand(), fourKicker.bestHand(),
                        HandRank.FOUR_OF_A_KIND) > 0);
                assertTrue(service.compareSameRank(fourKicker.bestHand(), kingKicker.bestHand(),
                        HandRank.FOUR_OF_A_KIND) < 0);
                assertEquals(0, service.compareSameRank(kingKicker.bestHand(), alsoKingKicker.bestHand(),
                        HandRank.FOUR_OF_A_KIND));
                assertEquals(0, service.compareSameRank(alsoKingKicker.bestHand(), kingKicker.bestHand(),
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

                assertTrue(service.compareSameRank(kingsOverQueens, kingsOverJacks, HandRank.FULL_HOUSE) > 0);
                assertTrue(service.compareSameRank(kingsOverJacks, kingsOverQueens, HandRank.FULL_HOUSE) < 0);
                assertEquals(0, service.compareSameRank(kingsOverQueens, kingsOverQueensOtherSuits,
                        HandRank.FULL_HOUSE));
                assertEquals(0, service.compareSameRank(kingsOverQueensOtherSuits, kingsOverQueens,
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
                assertTrue(service.compareSameRank(queens.bestHand(), jacks.bestHand(), HandRank.FULL_HOUSE) > 0);
                assertTrue(service.compareSameRank(jacks.bestHand(), queens.bestHand(), HandRank.FULL_HOUSE) < 0);
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

                assertTrue(service.compareSameRank(aceKingFive, aceKingFour, HandRank.ONE_PAIR) > 0);
                assertTrue(service.compareSameRank(aceKingFour, aceKingFive, HandRank.ONE_PAIR) < 0);
                assertTrue(service.compareSameRank(aceKingFive, aceQueenFive, HandRank.ONE_PAIR) > 0);
                assertTrue(service.compareSameRank(aceQueenFive, aceKingFive, HandRank.ONE_PAIR) < 0);
                assertTrue(service.compareSameRank(aceQueenFive, kingQueenJack, HandRank.ONE_PAIR) > 0);
                assertTrue(service.compareSameRank(kingQueenJack, aceQueenFive, HandRank.ONE_PAIR) < 0);
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

                assertTrue(service.compareSameRank(aceThree, kingQueen, HandRank.THREE_OF_A_KIND) > 0);
                assertTrue(service.compareSameRank(kingQueen, aceThree, HandRank.THREE_OF_A_KIND) < 0);
                assertTrue(service.compareSameRank(kingFive, kingFour, HandRank.THREE_OF_A_KIND) > 0);
                assertTrue(service.compareSameRank(kingFour, kingFive, HandRank.THREE_OF_A_KIND) < 0);
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

                assertEquals(0, service.compareSameRank(first, second, HandRank.THREE_OF_A_KIND));
                assertEquals(0, service.compareSameRank(second, first, HandRank.THREE_OF_A_KIND));
        }

        // ---- Characterization of every hand rank, the straights and shared boards ----

        /** Compares two same-rank hands: positive if first wins, negative if second wins, 0 for a tie. */
        private int compare(List<Card> first, List<Card> second, HandRank rank) {
                return Integer.signum(service.compareSameRank(first, second, rank));
        }

        /** Compares two evaluated hands of any rank: the rank decides first, then the same-rank comparison. */
        private int compare(HandEvaluationResult first, HandEvaluationResult second) {
                if (first.handRank() != second.handRank()) {
                        return Integer.signum(first.handRank().getRank() - second.handRank().getRank());
                }
                return compare(first.bestHand(), second.bestHand(), first.handRank());
        }

        /** Parses cards such as {@code "AS TD 9C"} (rank character then suit character). */
        private static List<Card> cards(String spec) {
                List<Card> result = new ArrayList<>();
                for (String token : spec.split(" ")) {
                        int value = "23456789TJQKA".indexOf(token.charAt(0)) + 2;
                        Rank rank = Arrays.stream(Rank.values())
                                        .filter(r -> r.getValue() == value).findFirst().orElseThrow();
                        Suit suit = Arrays.stream(Suit.values())
                                        .filter(s -> s.name().charAt(0) == token.charAt(1)).findFirst().orElseThrow();
                        result.add(new Card(rank, suit));
                }
                return result;
        }

        private HandEvaluationResult best(String board, String hole) {
                return service.getBestHand(cards(board), cards(hole));
        }

        @Test
        @DisplayName("Comparing hands that do not have exactly five cards throws")
        void compareSameRankRejectsHandsWithoutFiveCards() {
                List<Card> five = cards("2S 5H 7D 9C KS");
                assertThrows(IllegalStateException.class,
                                () -> service.compareSameRank(List.of(), five, HandRank.HIGH_CARD));
                assertThrows(IllegalStateException.class,
                                () -> service.compareSameRank(five, cards("2S 5H 7D 9C"), HandRank.HIGH_CARD));
        }

        @Test
        @DisplayName("Every hand rank is found in seven cards and outranks every rank below it")
        void everyHandRankIsRecognisedAndOutranksTheRanksBelowIt() {
                String[][] sevenCardHands = {
                                { "HIGH_CARD", "AS KD 9C 7H 5S", "3D 2C" },
                                { "ONE_PAIR", "AS AD 9C 7H 5S", "3D 2C" },
                                { "TWO_PAIR", "AS AD 9C 9H 5S", "3D 2C" },
                                { "THREE_OF_A_KIND", "AS AD AC 9H 5S", "3D 2C" },
                                { "STRAIGHT", "9S 8D 7C 6H 5S", "5D 2C" },
                                { "FLUSH", "AS 9S 7S 5S 3S", "KD KH" },
                                { "FULL_HOUSE", "KS KD KC 5H 5S", "5D 2C", "5H 5S KS KD KC" },
                                { "FOUR_OF_A_KIND", "9S 9D 9C 9H 5S", "5D 5C", "5S 9S 9D 9C 9H" },
                                { "STRAIGHT_FLUSH", "9H 8H 7H 6H 5H", "5S 5D" },
                                { "ROYAL_FLUSH", "AS KS QS JS TS", "9S 2D" } };
                HandRank previous = null;
                for (String[] row : sevenCardHands) {
                        HandRank expected = HandRank.valueOf(row[0]);
                        HandEvaluationResult result = best(row[1], row[2]);
                        assertEquals(expected, result.handRank(), row[0]);
                        if (row.length > 3) {
                                assertEquals(ranksOf(cards(row[3])), ranksOf(result.bestHand()), row[0]);
                        }
                        if (previous != null) {
                                assertTrue(expected.beats(previous), row[0] + " should beat " + previous);
                        }
                        previous = expected;
                }
        }

        @Test
        @DisplayName("Straights are ranked by their top card, and the ace is low only in the wheel")
        void straightsAreRankedByTopCardWithAceLowOnlyInTheWheel() {
                List<Card> broadway = cards("TS JH QD KC AS");
                List<Card> broadwayOtherSuits = cards("TH JD QC KS AH");
                List<Card> nineHigh = cards("5S 6H 7D 8C 9S");
                List<Card> wheel = cards("2H 3S 4D 5C AH");

                assertTrue(compare(broadway, nineHigh, HandRank.STRAIGHT) > 0);
                assertTrue(compare(broadway, wheel, HandRank.STRAIGHT) > 0);
                assertTrue(compare(nineHigh, wheel, HandRank.STRAIGHT) > 0);
                assertTrue(compare(wheel, broadway, HandRank.STRAIGHT) < 0);
                assertEquals(0, compare(broadway, broadwayOtherSuits, HandRank.STRAIGHT));
                assertEquals(HandRank.STRAIGHT, best("TS JH QD KC AS", "2C 2D").handRank());
                // The ace does not wrap round: Q-K-A-2-3 is not a straight.
                assertEquals(HandRank.HIGH_CARD, best("QS KH AD 2C 3S", "7C 9D").handRank());

                List<Card> nineHighFlush = cards("5S 6S 7S 8S 9S");
                List<Card> nineHighFlushOtherSuit = cards("5H 6H 7H 8H 9H");
                List<Card> wheelFlush = cards("2D 3D 4D 5D AD");
                assertTrue(compare(nineHighFlush, wheelFlush, HandRank.STRAIGHT_FLUSH) > 0);
                assertTrue(compare(wheelFlush, nineHighFlush, HandRank.STRAIGHT_FLUSH) < 0);
                assertEquals(0, compare(nineHighFlush, nineHighFlushOtherSuit, HandRank.STRAIGHT_FLUSH));
        }

        @Test
        @DisplayName("When the board is each player's best hand, they tie for every kind of board")
        void playersWhoBothPlayTheBoardTie() {
                String[][] boardsAndHoles = {
                                { "STRAIGHT", "5S 6H 7D 8C 9S", "2H 2D", "KC 3C" },
                                { "FLUSH", "KS QS 9S 6S 3S", "2H 4D", "5C 7D" },
                                { "FULL_HOUSE", "KS KD KC QH QS", "2H 3D", "4C 5D" },
                                { "HIGH_CARD", "AS KD JC 9H 7S", "2H 3D", "4C 3S" } };
                for (String[] row : boardsAndHoles) {
                        HandEvaluationResult first = best(row[1], row[2]);
                        HandEvaluationResult second = best(row[1], row[3]);
                        assertEquals(HandRank.valueOf(row[0]), first.handRank(), row[0]);
                        assertEquals(first.handRank(), second.handRank(), row[0]);
                        assertEquals(0, compare(first, second), row[0]);
                        assertEquals(0, compare(second, first), row[0]);
                }
        }
}
