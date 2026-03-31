package com.pokergame.model;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.pokergame.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HandEvaluationResult record.
 */
@Tag("unit")
@DisplayName("Hand evaluation result")
class HandEvaluationResultTest {

        @Test
        void testRecordCreation() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.SPADES),
                                new Card(Rank.QUEEN, Suit.SPADES),
                                new Card(Rank.JACK, Suit.SPADES),
                                new Card(Rank.TEN, Suit.SPADES));

                HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.ROYAL_FLUSH);

                assertNotNull(result);
                assertEquals(5, result.bestHand().size());
                assertEquals(HandRank.ROYAL_FLUSH, result.handRank());
        }

        @Test
        void testBestHandAccessor() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.ACE, Suit.DIAMONDS),
                                new Card(Rank.KING, Suit.CLUBS),
                                new Card(Rank.QUEEN, Suit.HEARTS),
                                new Card(Rank.JACK, Suit.SPADES));

                HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.ONE_PAIR);

                assertEquals(hand, result.bestHand());
        }

        @Test
        void testHandRankAccessor() {
                List<Card> hand = List.of(
                                new Card(Rank.FIVE, Suit.HEARTS),
                                new Card(Rank.FIVE, Suit.DIAMONDS),
                                new Card(Rank.FIVE, Suit.CLUBS),
                                new Card(Rank.TWO, Suit.HEARTS),
                                new Card(Rank.TWO, Suit.SPADES));

                HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.FULL_HOUSE);

                assertEquals(HandRank.FULL_HOUSE, result.handRank());
        }

        @Test
        void testNullBestHandThrowsException() {
                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new HandEvaluationResult(null, HandRank.HIGH_CARD));
                assertEquals("An error occured evaluating hand. Please try again.", exception.getMessage());
        }

        @Test
        void testNullHandRankThrowsException() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                BadRequestException exception = assertThrows(
                                BadRequestException.class,
                                () -> new HandEvaluationResult(hand, null));
                assertEquals("An error occured evaluating hand. Please try again.", exception.getMessage());
        }

        @Test
        void testBothNullThrowsException() {
                assertThrows(
                                BadRequestException.class,
                                () -> new HandEvaluationResult(null, null));
        }

        @Test
        void testBestHandIsImmutable() {
                List<Card> hand = new ArrayList<>();
                hand.add(new Card(Rank.ACE, Suit.SPADES));
                hand.add(new Card(Rank.KING, Suit.HEARTS));
                hand.add(new Card(Rank.QUEEN, Suit.DIAMONDS));
                hand.add(new Card(Rank.JACK, Suit.CLUBS));
                hand.add(new Card(Rank.TEN, Suit.SPADES));

                HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.STRAIGHT);

                // Modify original list
                hand.add(new Card(Rank.NINE, Suit.HEARTS));

                // Result should still have 5 cards
                assertEquals(5, result.bestHand().size());

                // Result's bestHand should be unmodifiable
                assertThrows(UnsupportedOperationException.class,
                                () -> result.bestHand().add(new Card(Rank.EIGHT, Suit.CLUBS)));
        }

        @Test
        void testRecordEquality() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                HandEvaluationResult result1 = new HandEvaluationResult(hand1, HandRank.HIGH_CARD);
                HandEvaluationResult result2 = new HandEvaluationResult(hand2, HandRank.HIGH_CARD);

                assertEquals(result1, result2);
        }

        @Test
        void testRecordHashCode() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                HandEvaluationResult result1 = new HandEvaluationResult(hand, HandRank.HIGH_CARD);
                HandEvaluationResult result2 = new HandEvaluationResult(hand, HandRank.HIGH_CARD);

                assertEquals(result1.hashCode(), result2.hashCode());
        }

        @Test
        void testRecordToString() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.SPADES));

                HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.HIGH_CARD);
                String str = result.toString();

                assertNotNull(str);
                assertTrue(str.contains("HandEvaluationResult"));
        }

        @Test
        void testDifferentHandRanksNotEqual() {
                List<Card> hand = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                HandEvaluationResult result1 = new HandEvaluationResult(hand, HandRank.HIGH_CARD);
                HandEvaluationResult result2 = new HandEvaluationResult(hand, HandRank.ONE_PAIR);

                assertNotEquals(result1, result2);
        }

        @Test
        void testDifferentHandsNotEqual() {
                List<Card> hand1 = List.of(
                                new Card(Rank.ACE, Suit.SPADES),
                                new Card(Rank.KING, Suit.HEARTS));

                List<Card> hand2 = List.of(
                                new Card(Rank.ACE, Suit.HEARTS),
                                new Card(Rank.KING, Suit.SPADES));

                HandEvaluationResult result1 = new HandEvaluationResult(hand1, HandRank.HIGH_CARD);
                HandEvaluationResult result2 = new HandEvaluationResult(hand2, HandRank.HIGH_CARD);

                assertNotEquals(result1, result2);
        }
}
