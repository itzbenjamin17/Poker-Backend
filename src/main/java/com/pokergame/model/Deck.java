package com.pokergame.model;

import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import com.pokergame.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a standard 52-card deck for poker games.
 * The deck is automatically shuffled upon creation.
 */
public class Deck {
    private static final Logger logger = LoggerFactory.getLogger(Deck.class);
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
     * Shuffles the deck randomly.
     */
    public void shuffle() {
        Collections.shuffle(cards);
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