package com.pokergame.service;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import com.pokergame.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HandEvaluatorService class.
 */
class HandEvaluatorServiceTest {

        private HandEvaluatorService service;

        @BeforeEach
        void setUp() {
                service = new HandEvaluatorService();
        }

        // Indirectly validates internal combination/high-card helpers through public
        // getBestHand API.
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
}
