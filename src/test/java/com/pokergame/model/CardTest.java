package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit tests for the Card class.
 */
@Tag("unit")
@DisplayName("Card model")
class CardTest {

    @Test
    void exposesRankAndSuitComponents() {
        Card card = new Card(Rank.ACE, Suit.SPADES);
        assertEquals(Rank.ACE, card.rank());
        assertEquals(Suit.SPADES, card.suit());
    }

    @Test
    void getValueDelegatesToRank() {
        assertEquals(14, new Card(Rank.ACE, Suit.HEARTS).getValue());
        assertEquals(13, new Card(Rank.KING, Suit.DIAMONDS).getValue());
        assertEquals(2, new Card(Rank.TWO, Suit.CLUBS).getValue());
    }

    /**
     * The {@code toString} form is a human-readable label surfaced in logs and messages, so the
     * exact rendering is pinned.
     */
    @ParameterizedTest
    @CsvSource({
            "ACE,SPADES,Ace of Spades",
            "KING,HEARTS,King of Hearts",
            "TWO,CLUBS,2 of Clubs"
    })
    void rendersReadableLabel(Rank rank, Suit suit, String expected) {
        assertEquals(expected, new Card(rank, suit).toString());
    }

    /**
     * Card equality is value-based (rank + suit); the game relies on it for deck uniqueness and
     * dealt-card set membership, so it is asserted rather than assumed from the record.
     */
    @Test
    void equalsIsValueBasedOnRankAndSuit() {
        assertEquals(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.ACE, Suit.SPADES));
        assertEquals(new Card(Rank.ACE, Suit.SPADES).hashCode(), new Card(Rank.ACE, Suit.SPADES).hashCode());
        assertNotEquals(new Card(Rank.ACE, Suit.SPADES), new Card(Rank.KING, Suit.SPADES));
    }

    @Test
    void everyRankAndSuitCombinationIsConstructible() {
        for (Rank rank : Rank.values()) {
            for (Suit suit : Suit.values()) {
                Card card = new Card(rank, suit);
                assertEquals(rank, card.rank());
                assertEquals(suit, card.suit());
                assertEquals(rank.getValue(), card.getValue());
            }
        }
    }
}
