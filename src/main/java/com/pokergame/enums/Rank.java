package com.pokergame.enums;

/**
 * Represents the rank of a card.
 */

public enum Rank {
    TWO(2, "2"),
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "Jack"),
    QUEEN(12, "Queen"),
    KING(13, "King"),
    ACE(14, "Ace");

    private final int value;
    private final String displayName;

    /**
     * Associates a card rank with its comparison value and display label.
     *
     * @param value numeric poker value
     * @param displayName human-readable label
     */
    Rank(int value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    /**
     * Returns the numeric poker value of this rank.
     *
     * @return value from 2 through 14
     */
    public int getValue() {
        return value;
    }

    /**
     * Returns the human-readable rank label.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }
}
