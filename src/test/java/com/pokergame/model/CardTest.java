package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Card class.
 */
@Tag("unit")
@DisplayName("Card model")
class CardTest {

    @Test
    void testCardCreation() {
        Card card = new Card(Rank.ACE, Suit.SPADES);
        assertNotNull(card);
        assertEquals(Rank.ACE, card.rank());
        assertEquals(Suit.SPADES, card.suit());
    }

    @Test
    void testGetValue() {
        Card aceCard = new Card(Rank.ACE, Suit.HEARTS);
        assertEquals(14, aceCard.getValue());

        Card kingCard = new Card(Rank.KING, Suit.DIAMONDS);
        assertEquals(13, kingCard.getValue());

        Card twoCard = new Card(Rank.TWO, Suit.CLUBS);
        assertEquals(2, twoCard.getValue());
    }

    @Test
    void testRankAccessor() {
        Card card = new Card(Rank.QUEEN, Suit.HEARTS);
        assertEquals(Rank.QUEEN, card.rank());
    }

    @Test
    void testSuitAccessor() {
        Card card = new Card(Rank.JACK, Suit.DIAMONDS);
        assertEquals(Suit.DIAMONDS, card.suit());
    }

    @Test
    void testToString() {
        Card card = new Card(Rank.ACE, Suit.SPADES);
        assertEquals("Ace of Spades", card.toString());

        Card kingHearts = new Card(Rank.KING, Suit.HEARTS);
        assertEquals("King of Hearts", kingHearts.toString());

        Card twoClubs = new Card(Rank.TWO, Suit.CLUBS);
        assertEquals("2 of Clubs", twoClubs.toString());
    }

    @Test
    void testCardEquality() {
        Card card1 = new Card(Rank.ACE, Suit.SPADES);
        Card card2 = new Card(Rank.ACE, Suit.SPADES);
        Card card3 = new Card(Rank.KING, Suit.SPADES);

        // Records automatically implement equals based on components
        assertEquals(card1, card2);
        assertNotEquals(card1, card3);
    }

    @Test
    void testCardHashCode() {
        Card card1 = new Card(Rank.QUEEN, Suit.HEARTS);
        Card card2 = new Card(Rank.QUEEN, Suit.HEARTS);

        // Records automatically implement consistent hashCode
        assertEquals(card1.hashCode(), card2.hashCode());
    }

    @Test
    void testAllRanksAndSuits() {
        // Test that all combinations of ranks and suits can be created
        for (Rank rank : Rank.values()) {
            for (Suit suit : Suit.values()) {
                Card card = new Card(rank, suit);
                assertNotNull(card);
                assertEquals(rank, card.rank());
                assertEquals(suit, card.suit());
                assertEquals(rank.getValue(), card.getValue());
            }
        }
    }

    @Test
    void testCardImmutability() {
        // As a record, Card is immutable by design
        Card card = new Card(Rank.TEN, Suit.DIAMONDS);
        assertEquals(Rank.TEN, card.rank());
        assertEquals(Suit.DIAMONDS, card.suit());

        // Verify accessors return consistent values
        assertEquals(card.rank(), card.rank());
        assertEquals(card.suit(), card.suit());
    }
}
