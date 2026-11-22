package com.pokergame.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Deck}.
 */
@Tag("unit")
@DisplayName("Deck model")
class DeckTest {

    private Deck deck;

    @BeforeEach
    void setUp() {
        deck = new Deck();
    }

    @Test
    void testDeckContainsAllCards() {
        Set<Card> dealtCards = new HashSet<>();

        for (int i = 0; i < 52; i++) {
            Card card = deck.dealCard();
            assertNotNull(card);
            assertTrue(dealtCards.add(card), "Each card should be unique");
        }

        assertEquals(52, dealtCards.size());
        for (Rank rank : Rank.values()) {
            for (Suit suit : Suit.values()) {
                assertTrue(dealtCards.contains(new Card(rank, suit)),
                        "Deck should contain " + rank + " of " + suit);
            }
        }
    }

    @Test
    void testDealCardThrowsExceptionWhenEmpty() {
        for (int i = 0; i < 52; i++) {
            deck.dealCard();
        }

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> deck.dealCard());
        assertEquals("No more cards in the deck", exception.getMessage());
    }

    @Test
    void testDealMultipleCardsRemovesFromDeck() {
        List<Card> initialTen = deck.dealCards(10);
        assertEquals(10, new HashSet<>(initialTen).size());

        Set<Card> remainingCards = new HashSet<>();
        for (int i = 0; i < 42; i++) {
            remainingCards.add(deck.dealCard());
        }

        assertEquals(42, remainingCards.size());
        assertThrows(IllegalStateException.class, () -> deck.dealCard());
    }

    @Test
    void testDealMultipleCardsWithInvalidNumber() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> deck.dealCards(0));
        assertEquals("Number of cards must be positive", exception.getMessage());

        assertThrows(IllegalStateException.class, () -> deck.dealCards(-1));
        assertThrows(IllegalStateException.class, () -> deck.dealCards(-5));
    }

    @Test
    void testDealMultipleCardsNotEnoughInDeck() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> deck.dealCards(53));
        assertTrue(exception.getMessage().contains("Not enough cards in deck"));
        assertTrue(exception.getMessage().contains("Requested: 53"));
        assertTrue(exception.getMessage().contains("Available: 52"));
    }

    @Test
    void testDealMultipleCardsPartiallyEmptyDeck() {
        deck.dealCards(50);

        List<Card> cards = deck.dealCards(2);
        assertEquals(2, cards.size());

        assertThrows(IllegalStateException.class, () -> deck.dealCards(1));
    }

    @Test
    void testRestorePreservesExactFutureDrawOrderAndDefensivelyCopiesSnapshot() {
        deck.dealCards(12);
        List<Card> snapshot = deck.getRemainingCardsSnapshot();
        assertEquals(40, snapshot.size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new Card(Rank.ACE, Suit.SPADES)));

        List<Card> mutableInput = new ArrayList<>(snapshot);
        Deck restored = Deck.restore(mutableInput);
        mutableInput.clear();

        assertEquals(snapshot, restored.getRemainingCardsSnapshot());
        for (int i = 0; i < 40; i++) {
            assertEquals(deck.dealCard(), restored.dealCard());
        }
        assertThrows(IllegalStateException.class, restored::dealCard);
    }
}
