package com.pokergame.model;

import com.pokergame.enums.HandRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the HandRank enum.
 */
@Tag("unit")
@DisplayName("Hand rank enum")
class HandRankTest {

    @Test
    void testAllHandRanksExist() {
        HandRank[] ranks = HandRank.values();
        assertEquals(11, ranks.length, "Should have 11 hand ranks");
    }

    @Test
    void testHandRankValues() {
        assertEquals(-1, HandRank.NO_HAND.getRank());
        assertEquals(1, HandRank.HIGH_CARD.getRank());
        assertEquals(2, HandRank.ONE_PAIR.getRank());
        assertEquals(3, HandRank.TWO_PAIR.getRank());
        assertEquals(4, HandRank.THREE_OF_A_KIND.getRank());
        assertEquals(5, HandRank.STRAIGHT.getRank());
        assertEquals(6, HandRank.FLUSH.getRank());
        assertEquals(7, HandRank.FULL_HOUSE.getRank());
        assertEquals(8, HandRank.FOUR_OF_A_KIND.getRank());
        assertEquals(9, HandRank.STRAIGHT_FLUSH.getRank());
        assertEquals(10, HandRank.ROYAL_FLUSH.getRank());
    }

    @Test
    void testBeatsMethod() {
        assertTrue(HandRank.ROYAL_FLUSH.beats(HandRank.STRAIGHT_FLUSH));
        assertTrue(HandRank.STRAIGHT_FLUSH.beats(HandRank.FOUR_OF_A_KIND));
        assertTrue(HandRank.FOUR_OF_A_KIND.beats(HandRank.FULL_HOUSE));
        assertTrue(HandRank.FULL_HOUSE.beats(HandRank.FLUSH));
        assertTrue(HandRank.FLUSH.beats(HandRank.STRAIGHT));
        assertTrue(HandRank.STRAIGHT.beats(HandRank.THREE_OF_A_KIND));
        assertTrue(HandRank.THREE_OF_A_KIND.beats(HandRank.TWO_PAIR));
        assertTrue(HandRank.TWO_PAIR.beats(HandRank.ONE_PAIR));
        assertTrue(HandRank.ONE_PAIR.beats(HandRank.HIGH_CARD));
        assertTrue(HandRank.HIGH_CARD.beats(HandRank.NO_HAND));
    }

    @Test
    void testBeatsReturnsFalseForLowerOrEqualRanks() {
        assertFalse(HandRank.ONE_PAIR.beats(HandRank.TWO_PAIR));
        assertFalse(HandRank.FLUSH.beats(HandRank.FULL_HOUSE));
        assertFalse(HandRank.HIGH_CARD.beats(HandRank.HIGH_CARD));
    }

    @Test
    void testEqualsMethod() {
        assertTrue(HandRank.ROYAL_FLUSH.equals(HandRank.ROYAL_FLUSH));
        assertTrue(HandRank.FLUSH.equals(HandRank.FLUSH));
        assertTrue(HandRank.ONE_PAIR.equals(HandRank.ONE_PAIR));

        assertFalse(HandRank.ROYAL_FLUSH.equals(HandRank.STRAIGHT_FLUSH));
        assertFalse(HandRank.TWO_PAIR.equals(HandRank.ONE_PAIR));
    }

    @Test
    void testRoyalFlushIsStrongest() {
        HandRank[] ranks = HandRank.values();
        for (HandRank rank : ranks) {
            if (rank != HandRank.ROYAL_FLUSH) {
                assertTrue(HandRank.ROYAL_FLUSH.beats(rank),
                        "Royal Flush should beat " + rank);
            }
        }
    }

    @Test
    void testNoHandIsWeakest() {
        HandRank[] ranks = HandRank.values();
        for (HandRank rank : ranks) {
            if (rank != HandRank.NO_HAND) {
                assertTrue(rank.beats(HandRank.NO_HAND),
                        rank + " should beat NO_HAND");
            }
        }
    }

    @Test
    void testHandRankOrdering() {
        // Verify ranks are in ascending order (except NO_HAND)
        HandRank[] ranks = {
                HandRank.HIGH_CARD,
                HandRank.ONE_PAIR,
                HandRank.TWO_PAIR,
                HandRank.THREE_OF_A_KIND,
                HandRank.STRAIGHT,
                HandRank.FLUSH,
                HandRank.FULL_HOUSE,
                HandRank.FOUR_OF_A_KIND,
                HandRank.STRAIGHT_FLUSH,
                HandRank.ROYAL_FLUSH
        };

        for (int i = 0; i < ranks.length - 1; i++) {
            assertTrue(ranks[i].getRank() < ranks[i + 1].getRank(),
                    ranks[i] + " should have lower rank value than " + ranks[i + 1]);
        }
    }

    @Test
    void testValueOf() {
        assertEquals(HandRank.ROYAL_FLUSH, HandRank.valueOf("ROYAL_FLUSH"));
        assertEquals(HandRank.STRAIGHT_FLUSH, HandRank.valueOf("STRAIGHT_FLUSH"));
        assertEquals(HandRank.ONE_PAIR, HandRank.valueOf("ONE_PAIR"));
        assertEquals(HandRank.NO_HAND, HandRank.valueOf("NO_HAND"));
    }

    @Test
    void testEnumEquality() {
        HandRank flush1 = HandRank.FLUSH;
        HandRank flush2 = HandRank.FLUSH;
        assertSame(flush1, flush2, "Enum instances should be identical");
    }
}
