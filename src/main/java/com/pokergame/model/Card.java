package com.pokergame.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.pokergame.enums.Rank;
import com.pokergame.enums.Suit;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Represents a playing card with a non-null rank and suit.
 * Cards are immutable once created.
 *
 * @param rank card rank
 * @param suit card suit
 */
public record Card(Rank rank, Suit suit) {
    /**
     * Creates a new Card with the specified rank and suit.
     *
     * @param rank the rank of the card
     * @param suit the suit of the card
     */
    public Card {
        Objects.requireNonNull(rank, "rank must not be null");
        Objects.requireNonNull(suit, "suit must not be null");
    }

    /**
     * Serializes the card using the compact rank-and-suit wire format expected by
     * the frontend instead of exposing a JSON object.
     *
     * @return compact card code such as {@code AS} or {@code 10D}
     */
    @JsonValue
    public String toJsonString() {
        String r = switch (rank) {
            case JACK -> "J";
            case QUEEN -> "Q";
            case KING -> "K";
            case ACE -> "A";
            default -> String.valueOf(rank.getValue());
        };
        return r + suit.name().charAt(0);
    }

    public int getValue() {
        return rank.getValue();
    }

    /**
     * Returns a string representation of this card in "Rank of Suit" format.
     *
     * @return formatted string (e.g. "Ace of Hearts")
     */
    @Override
    @NonNull
    public String toString() {
        return rank.getDisplayName() + " of " + suit.getDisplayName();
    }
}
