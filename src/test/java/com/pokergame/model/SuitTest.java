package com.pokergame.model;

import com.pokergame.enums.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Suit enum.
 */
@Tag("unit")
@DisplayName("Suit enum")
class SuitTest {

    @Test
    void testAllSuitsExist() {
        Suit[] suits = Suit.values();
        assertEquals(4, suits.length, "Should have 4 suits in a standard deck");
    }

    @Test
    void testSuitDisplayNames() {
        assertEquals("Hearts", Suit.HEARTS.getDisplayName());
        assertEquals("Diamonds", Suit.DIAMONDS.getDisplayName());
        assertEquals("Clubs", Suit.CLUBS.getDisplayName());
        assertEquals("Spades", Suit.SPADES.getDisplayName());
    }

    @Test
    void testSuitSymbols() {
        assertEquals("♥", Suit.HEARTS.getSymbol());
        assertEquals("♦", Suit.DIAMONDS.getSymbol());
        assertEquals("♣", Suit.CLUBS.getSymbol());
        assertEquals("♠", Suit.SPADES.getSymbol());
    }

    @Test
    void testValueOf() {
        assertEquals(Suit.HEARTS, Suit.valueOf("HEARTS"));
        assertEquals(Suit.DIAMONDS, Suit.valueOf("DIAMONDS"));
        assertEquals(Suit.CLUBS, Suit.valueOf("CLUBS"));
        assertEquals(Suit.SPADES, Suit.valueOf("SPADES"));
    }

    @Test
    void testEnumEquality() {
        Suit hearts1 = Suit.HEARTS;
        Suit hearts2 = Suit.HEARTS;
        assertSame(hearts1, hearts2, "Enum instances should be identical");
    }

    @Test
    void testAllSuitsUnique() {
        Suit[] suits = Suit.values();
        for (int i = 0; i < suits.length; i++) {
            for (int j = i + 1; j < suits.length; j++) {
                assertNotEquals(suits[i], suits[j], "All suits should be unique");
                assertNotEquals(suits[i].getDisplayName(), suits[j].getDisplayName());
                assertNotEquals(suits[i].getSymbol(), suits[j].getSymbol());
            }
        }
    }
}
