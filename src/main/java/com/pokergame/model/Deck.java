package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.SecureRandom;

/**
 * Represents a standard 52-card deck for poker games.
 * The deck is automatically shuffled upon creation.
 */
public class Deck {
    private static final Logger logger = LoggerFactory.getLogger(Deck.class);
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
     *
     * @param cards remaining cards in their persisted draw order
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

    /**
     * Initialises the deck with all 52 cards.
     */
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
     * @throws BadRequestException if the deck is empty
     */
    public Card dealCard() {
        if (cards.isEmpty()) {
            logger.error("Attempted to deal card from empty deck");
            throw new BadRequestException("No more cards in the deck");
        }
        return cards.removeLast();
    }

    /**
     * Deals multiple cards from the deck.
     *
     * @param numberOfCards the number of cards to deal
     * @return a list of dealt cards
     * @throws BadRequestException if numberOfCards is invalid or there aren't
     *                             enough cards in the deck
     */
    public List<Card> dealCards(int numberOfCards) {
        if (numberOfCards <= 0) {
            logger.error("Invalid number of cards requested: {}", numberOfCards);
            throw new BadRequestException("Number of cards must be positive");
        }
        if (numberOfCards > cards.size()) {
            logger.error("Not enough cards: requested {}, available {}", numberOfCards, cards.size());
            throw new BadRequestException(
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
