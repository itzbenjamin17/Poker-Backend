package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.SecureRandom;

/**
 * Represents a standard 52-card deck for poker games.
 * The deck is automatically shuffled upon creation.
 */
public class Deck {
    private static final SecureRandom SHUFFLE_RANDOM = new SecureRandom();
    private final List<Card> cards;

    /**
     * Creates a new shuffled deck containing all 52 standard playing cards.
     */
    public Deck() {
        this.cards = new ArrayList<>();
        initializeDeck();
        shuffle();
    }

    /**
     * Reconstructs an already shuffled remainder without consuming randomness. This
     * path exists only for exact restart recovery; normal games use {@link #Deck()}.
     */
    private Deck(List<Card> cards) {
        this.cards = new ArrayList<>(cards);
    }

    /**
     * Restores the future draw order exactly because reshuffling after restart would
     * change private cards and game outcomes.
     *
     * @param cards remaining cards in persisted draw order
     * @return deck that will deal the same future sequence
     */
    public static Deck restore(List<Card> cards) {
        return new Deck(cards);
    }

    /**
     * Returns an immutable copy so persistence can capture exact draw order without
     * exposing the live deck to mutation.
     *
     * @return remaining cards in draw order
     */
    public List<Card> getRemainingCardsSnapshot() {
        return List.copyOf(cards);
    }

    private void initializeDeck() {
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(new Card(rank, suit));
            }
        }
    }

    /**
     * Uses a cryptographically strong random source so future private cards are not
     * predictable from the weaker default pseudo-random generator.
     */
    public void shuffle() {
        Collections.shuffle(cards, SHUFFLE_RANDOM);
    }

    /**
     * Deals one card from the deck.
     *
     * @return the dealt card
     * @throws IllegalStateException if the deck is empty
     */
    public Card dealCard() {
        if (cards.isEmpty()) {
            throw new IllegalStateException("No more cards in the deck");
        }
        return cards.removeLast();
    }

    /**
     * Deals multiple cards from the deck.
     *
     * @param numberOfCards the number of cards to deal
     * @return a list of dealt cards
     * @throws IllegalStateException if numberOfCards is invalid or there aren't
     *                               enough cards in the deck
     */
    public List<Card> dealCards(int numberOfCards) {
        if (numberOfCards <= 0) {
            throw new IllegalStateException("Number of cards must be positive");
        }
        if (numberOfCards > cards.size()) {
            throw new IllegalStateException(
                    "Not enough cards in deck. Requested: " + numberOfCards +
                            ", Available: " + cards.size());
        }

        List<Card> dealtCards = new ArrayList<>();
        for (int i = 0; i < numberOfCards; i++) {
            dealtCards.add(cards.removeLast());
        }
        return dealtCards;
    }
}
