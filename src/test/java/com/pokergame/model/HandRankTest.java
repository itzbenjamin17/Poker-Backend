package com.pokergame.model;

import com.pokergame.enums.HandRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the HandRank enum.
 */
@Tag("unit")
@DisplayName("Hand rank enum")
class HandRankTest {

    /**
     * The numeric rank drives showdown comparison and is serialized, so the exact value per
     * hand category is a domain/wire contract.
     */
    @ParameterizedTest
    @CsvSource({
            "NO_HAND,-1",
            "HIGH_CARD,1",
            "ONE_PAIR,2",
            "TWO_PAIR,3",
            "THREE_OF_A_KIND,4",
            "STRAIGHT,5",
            "FLUSH,6",
            "FULL_HOUSE,7",
            "FOUR_OF_A_KIND,8",
            "STRAIGHT_FLUSH,9",
            "ROYAL_FLUSH,10"
    })
    void handRankExposesExpectedNumericRank(HandRank rank, int expected) {
        assertEquals(expected, rank.getRank());
    }

    @Test
    void beatsFollowsAscendingPokerOrder() {
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
    void beatsIsFalseForLowerOrEqualRanks() {
        assertFalse(HandRank.ONE_PAIR.beats(HandRank.TWO_PAIR));
        assertFalse(HandRank.FLUSH.beats(HandRank.FULL_HOUSE));
        assertFalse(HandRank.HIGH_CARD.beats(HandRank.HIGH_CARD));
    }

    @Test
    void royalFlushBeatsEveryOtherRank() {
        for (HandRank rank : HandRank.values()) {
            if (rank != HandRank.ROYAL_FLUSH) {
                assertTrue(HandRank.ROYAL_FLUSH.beats(rank),
                        "Royal Flush should beat " + rank);
            }
        }
    }

    @Test
    void everyRankBeatsNoHand() {
        for (HandRank rank : HandRank.values()) {
            if (rank != HandRank.NO_HAND) {
                assertTrue(rank.beats(HandRank.NO_HAND),
                        rank + " should beat NO_HAND");
            }
        }
    }

    @Test
    void handRanksAreOrderedAscendingFromHighCard() {
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
}
