package com.pokergame.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HandRank} poker hand hierarchy and dual comparison contracts.
 */
@Tag("unit")
@DisplayName("Hand rank enum")
class HandRankTest {

    @Test
    @DisplayName("values are declared in exact ascending poker hand order")
    void valuesAreDeclaredInExactAscendingPokerOrder() {
        assertThat(HandRank.values()).containsExactly(
                HandRank.NO_HAND,
                HandRank.HIGH_CARD,
                HandRank.ONE_PAIR,
                HandRank.TWO_PAIR,
                HandRank.THREE_OF_A_KIND,
                HandRank.STRAIGHT,
                HandRank.FLUSH,
                HandRank.FULL_HOUSE,
                HandRank.FOUR_OF_A_KIND,
                HandRank.STRAIGHT_FLUSH,
                HandRank.ROYAL_FLUSH);
    }

    @Test
    @DisplayName("beats and natural compareTo agree across all pairwise rank combinations")
    void beatsAndNaturalCompareToAgreeAcrossAllPairs() {
        HandRank[] values = HandRank.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < values.length; j++) {
                assertThat(values[i].beats(values[j])).isEqualTo(i > j);
                assertThat(Integer.signum(values[i].compareTo(values[j]))).isEqualTo(Integer.compare(i, j));
            }
        }
    }
}
