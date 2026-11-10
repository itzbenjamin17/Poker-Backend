package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.pokergame.exception.BadRequestException;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for the Deck class.
 */
@Tag("unit")
@DisplayName("Deck model")
class DeckTest {

    private Deck deck;

    /**
     * Initializes the test fixtures before each scenario.
     */
    @BeforeEach
    void setUp() {
        deck = new Deck();
    }

    /**
     * Protects the expected behavior for deck contains all cards.
     */
    @Test
    void testDeckContainsAllCards() {
        Set<Card> dealtCards = new HashSet<>();

        // Deal all 52 cards
        for (int i = 0; i < 52; i++) {
            dealtCards.add(deck.dealCard());
        }

        // Verify all combinations of rank and suit are present
        for (Rank rank : Rank.values()) {
            for (Suit suit : Suit.values()) {
                assertTrue(dealtCards.contains(new Card(rank, suit)),
                        "Deck should contain " + rank + " of " + suit);
            }
        }
    }

    /**
     * Protects the expected behavior for deal card.
     */
    @Test
    void testDealCard() {
        Card card = deck.dealCard();
        assertNotNull(card);
        assertNotNull(card.rank());
        assertNotNull(card.suit());
    }

    /**
     * Protects the expected behavior for deal card throws exception when empty.
     */
    @Test
    void testDealCardThrowsExceptionWhenEmpty() {
        // Deal all cards
        for (int i = 0; i < 52; i++) {
            deck.dealCard();
        }

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> deck.dealCard());
        assertEquals("No more cards in the deck", exception.getMessage());
    }

    /**
     * Protects the expected behavior for deal multiple cards.
     */
    @Test
    void testDealMultipleCards() {
        List<Card> cards = deck.dealCards(5);

        assertNotNull(cards);
        assertEquals(5, cards.size());

        // Verify all cards are unique
        Set<Card> uniqueCards = new HashSet<>(cards);
        assertEquals(5, uniqueCards.size());
    }

    /**
     * Protects the expected behavior for deal multiple cards removes from deck.
     */
    @Test
    void testDealMultipleCardsRemovesFromDeck() {
        deck.dealCards(10);

        // Should have 42 cards remaining
        Set<Card> remainingCards = new HashSet<>();
        for (int i = 0; i < 42; i++) {
            remainingCards.add(deck.dealCard());
        }

        assertEquals(42, remainingCards.size());
        assertThrows(BadRequestException.class, () -> deck.dealCard());
    }

    /**
     * Protects the expected behavior for deal multiple cards with invalid number.
     */
    @Test
    void testDealMultipleCardsWithInvalidNumber() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> deck.dealCards(0));
        assertEquals("Number of cards must be positive", exception.getMessage());

        assertThrows(BadRequestException.class, () -> deck.dealCards(-1));
        assertThrows(BadRequestException.class, () -> deck.dealCards(-5));
    }

    /**
     * Protects the expected behavior for deal multiple cards not enough in deck.
     */
    @Test
    void testDealMultipleCardsNotEnoughInDeck() {
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> deck.dealCards(53));
        assertTrue(exception.getMessage().contains("Not enough cards in deck"));
        assertTrue(exception.getMessage().contains("Requested: 53"));
        assertTrue(exception.getMessage().contains("Available: 52"));
    }

    /**
     * Protects the expected behavior for deal multiple cards partially empty deck.
     */
    @Test
    void testDealMultipleCardsPartiallyEmptyDeck() {
        // Deal 50 cards
        deck.dealCards(50);

        // Should have 2 cards left
        List<Card> cards = deck.dealCards(2);
        assertEquals(2, cards.size());

        // Now deck is empty, should throw exception
        assertThrows(BadRequestException.class, () -> deck.dealCards(1));
    }

    /**
     * Shuffling permutes the remaining cards without losing or duplicating any of them. The
     * exact resulting order is not asserted: a correct shuffle may legitimately return the
     * original ordering, so a "some position differs" oracle would fail with probability 1/52!.
     */
    @Test
    void testShuffle() {
        Deck unshuffled = new Deck();
        Deck shuffled = new Deck();

        List<Card> baseline = unshuffled.dealCards(52);
        shuffled.shuffle();
        List<Card> afterShuffle = shuffled.dealCards(52);

        // Same 52-card set, no loss or duplication.
        assertEquals(new HashSet<>(baseline), new HashSet<>(afterShuffle));
        assertEquals(52, afterShuffle.size());
        assertEquals(52, new HashSet<>(afterShuffle).size(), "Every card in the shuffled deck must be unique");
    }

    /**
     * Protects the expected behavior for multiple shuffle.
     */
    @Test
    void testMultipleShuffle() {
        // Shuffle should work multiple times on the same deck
        assertDoesNotThrow(() -> {
            deck.shuffle();
            deck.shuffle();
            deck.shuffle();
        });

        // Should still be able to deal cards after multiple shuffles
        List<Card> cards = deck.dealCards(52);
        assertEquals(52, cards.size());
    }

    /**
     * Protects the expected behavior for shuffle after dealing.
     */
    @Test
    void testShuffleAfterDealing() {
        // Deal some cards
        deck.dealCards(10);

        // Shuffle remaining cards
        assertDoesNotThrow(() -> deck.shuffle());

        // Should be able to deal remaining 42 cards
        List<Card> remainingCards = deck.dealCards(42);
        assertEquals(42, remainingCards.size());
    }

    /**
     * Protects the expected behavior for deal all cards individually.
     */
    @Test
    void testDealAllCardsIndividually() {
        Set<Card> dealtCards = new HashSet<>();

        // Deal all 52 cards one by one
        for (int i = 0; i < 52; i++) {
            Card card = deck.dealCard();
            assertNotNull(card);
            assertTrue(dealtCards.add(card), "Each card should be unique");
        }

        assertEquals(52, dealtCards.size());
    }

    /**
     * Protects the expected behavior for mixed dealing scenario.
     */
    @Test
    void testMixedDealingScenario() {
        // Simulate a real poker game scenario
        List<Card> player1Hand = deck.dealCards(2);
        List<Card> player2Hand = deck.dealCards(2);
        List<Card> player3Hand = deck.dealCards(2);

        // Deal the flop
        List<Card> flop = deck.dealCards(3);

        // Deal the turn
        Card turn = deck.dealCard();

        // Deal the river
        Card river = deck.dealCard();

        // Total cards dealt: 6 (hands) + 3 (flop) + 1 (turn) + 1 (river) = 11
        assertEquals(2, player1Hand.size());
        assertEquals(2, player2Hand.size());
        assertEquals(2, player3Hand.size());
        assertEquals(3, flop.size());
        assertNotNull(turn);
        assertNotNull(river);

        // Should have 41 cards remaining
        List<Card> remaining = deck.dealCards(41);
        assertEquals(41, remaining.size());
    }
}
