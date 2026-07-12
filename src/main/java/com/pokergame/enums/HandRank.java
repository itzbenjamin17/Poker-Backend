package com.pokergame.enums;

/**
 * Represents the rank of a poker hand.
 */

public enum HandRank {
    NO_HAND(-1),
    HIGH_CARD(1),
    ONE_PAIR(2),
    TWO_PAIR(3),
    THREE_OF_A_KIND(4),
    STRAIGHT(5),
    FLUSH(6),
    FULL_HOUSE(7),
    FOUR_OF_A_KIND(8),
    STRAIGHT_FLUSH(9),
    ROYAL_FLUSH(10);

    private final int rank;

    /**
     * Associates a hand category with its comparison strength.
     *
     * @param rank numeric comparison strength
     */
    HandRank(int rank) {
        this.rank = rank;
    }

    /**
     * Returns this category's comparison strength.
     *
     * @return numeric rank
     */
    public int getRank() {
        return rank;
    }

    /**
     * Reports whether this category outranks another category.
     *
     * @param other category to compare against
     * @return {@code true} when this category is stronger
     */
    public boolean beats(HandRank other) {
        return this.rank > other.rank;
    }
}
