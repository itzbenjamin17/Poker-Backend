package com.pokergame.model;

import com.pokergame.enums.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Rank enum.
 */
@Tag("unit")
@DisplayName("Rank enum")
class RankTest {

    /**
     * Protects the expected behavior for all ranks exist.
     */
    @Test
    void testAllRanksExist() {
        Rank[] ranks = Rank.values();
        assertEquals(13, ranks.length, "Should have 13 ranks in a standard deck");
    }

    /**
     * Protects the expected behavior for rank values.
     */
    @Test
    void testRankValues() {
        assertEquals(2, Rank.TWO.getValue());
        assertEquals(3, Rank.THREE.getValue());
        assertEquals(4, Rank.FOUR.getValue());
        assertEquals(5, Rank.FIVE.getValue());
        assertEquals(6, Rank.SIX.getValue());
        assertEquals(7, Rank.SEVEN.getValue());
        assertEquals(8, Rank.EIGHT.getValue());
        assertEquals(9, Rank.NINE.getValue());
        assertEquals(10, Rank.TEN.getValue());
        assertEquals(11, Rank.JACK.getValue());
        assertEquals(12, Rank.QUEEN.getValue());
        assertEquals(13, Rank.KING.getValue());
        assertEquals(14, Rank.ACE.getValue());
    }

    /**
     * Protects the expected behavior for rank display names.
     */
    @Test
    void testRankDisplayNames() {
        assertEquals("2", Rank.TWO.getDisplayName());
        assertEquals("3", Rank.THREE.getDisplayName());
        assertEquals("4", Rank.FOUR.getDisplayName());
        assertEquals("5", Rank.FIVE.getDisplayName());
        assertEquals("6", Rank.SIX.getDisplayName());
        assertEquals("7", Rank.SEVEN.getDisplayName());
        assertEquals("8", Rank.EIGHT.getDisplayName());
        assertEquals("9", Rank.NINE.getDisplayName());
        assertEquals("10", Rank.TEN.getDisplayName());
        assertEquals("Jack", Rank.JACK.getDisplayName());
        assertEquals("Queen", Rank.QUEEN.getDisplayName());
        assertEquals("King", Rank.KING.getDisplayName());
        assertEquals("Ace", Rank.ACE.getDisplayName());
    }

    /**
     * Protects the expected behavior for rank ordering.
     */
    @Test
    void testRankOrdering() {
        // Verify ranks are in ascending order by value
        Rank[] ranks = Rank.values();
        for (int i = 0; i < ranks.length - 1; i++) {
            assertTrue(ranks[i].getValue() < ranks[i + 1].getValue(),
                    ranks[i] + " should have lower value than " + ranks[i + 1]);
        }
    }

    /**
     * Protects the expected behavior for value of.
     */
    @Test
    void testValueOf() {
        assertEquals(Rank.ACE, Rank.valueOf("ACE"));
        assertEquals(Rank.KING, Rank.valueOf("KING"));
        assertEquals(Rank.TWO, Rank.valueOf("TWO"));
    }

}
