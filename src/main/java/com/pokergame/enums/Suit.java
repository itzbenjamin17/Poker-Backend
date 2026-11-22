package com.pokergame.enums;

/**
 * Represents the suit of a card.
 */

public enum Suit {
    HEARTS("Hearts", "♥"),
    DIAMONDS("Diamonds", "♦"),
    CLUBS("Clubs", "♣"),
    SPADES("Spades", "♠");

    private final String displayName;
    private final String symbol;

    /**
     * Associates a suit with its display label and glyph.
     *
     * @param displayName human-readable suit name
     * @param symbol suit glyph
     */
    Suit(String displayName, String symbol) {
        this.displayName = displayName;
        this.symbol = symbol;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSymbol() {
        return symbol;
    }
}
