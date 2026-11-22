package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pokergame.enums.HandRank;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HandEvaluationResult}.
 */
@Tag("unit")
@DisplayName("Hand evaluation result")
class HandEvaluationResultTest {

    @Test
    void rejectsNullBestHandOrHandRank() {
        List<Card> hand = List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.HEARTS));

        IllegalStateException nullHand = assertThrows(
                IllegalStateException.class,
                () -> new HandEvaluationResult(null, HandRank.HIGH_CARD));
        assertEquals("An error occurred evaluating hand. Please try again.", nullHand.getMessage());

        IllegalStateException nullRank = assertThrows(
                IllegalStateException.class,
                () -> new HandEvaluationResult(hand, null));
        assertEquals("An error occurred evaluating hand. Please try again.", nullRank.getMessage());

        assertThrows(IllegalStateException.class, () -> new HandEvaluationResult(null, null));
    }

    @Test
    void bestHandIsDefensivelyCopiedAndImmutable() {
        List<Card> hand = new ArrayList<>(List.of(
                new Card(Rank.ACE, Suit.SPADES),
                new Card(Rank.KING, Suit.HEARTS),
                new Card(Rank.QUEEN, Suit.DIAMONDS),
                new Card(Rank.JACK, Suit.CLUBS),
                new Card(Rank.TEN, Suit.SPADES)));

        HandEvaluationResult result = new HandEvaluationResult(hand, HandRank.STRAIGHT);
        hand.add(new Card(Rank.NINE, Suit.HEARTS));

        assertEquals(5, result.bestHand().size());
        assertEquals(HandRank.STRAIGHT, result.handRank());
        assertThrows(UnsupportedOperationException.class,
                () -> result.bestHand().add(new Card(Rank.EIGHT, Suit.CLUBS)));
    }
}
