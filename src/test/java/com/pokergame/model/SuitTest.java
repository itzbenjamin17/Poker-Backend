package com.pokergame.model;

import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the Suit enum.
 */
@Tag("unit")
@DisplayName("Suit enum")
class SuitTest {

    @Test
    void standardDeckHasFourSuits() {
        assertEquals(4, Suit.values().length);
    }

    /**
     * Display names and symbols are a wire contract the frontend renders, so the exact
     * strings are pinned per suit rather than left to Java enum mechanics.
     */
    @ParameterizedTest
    @CsvSource({
            "HEARTS,Hearts,♥",
            "DIAMONDS,Diamonds,♦",
            "CLUBS,Clubs,♣",
            "SPADES,Spades,♠"
    })
    void suitExposesDisplayNameAndSymbol(Suit suit, String displayName, String symbol) {
        assertEquals(displayName, suit.getDisplayName());
        assertEquals(symbol, suit.getSymbol());
    }
}
