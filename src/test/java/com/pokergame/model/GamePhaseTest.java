package com.pokergame.model;

import com.pokergame.enums.GamePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Unit tests for the GamePhase enum.
 */
@Tag("unit")
@DisplayName("Game phase enum")
class GamePhaseTest {

    /**
     * The betting round progresses in declaration order, so the exact sequence is a domain
     * contract rather than an incidental enum ordering.
     */
    @Test
    void phasesAreDeclaredInBettingRoundOrder() {
        assertArrayEquals(
                new GamePhase[]{
                        GamePhase.PRE_FLOP,
                        GamePhase.FLOP,
                        GamePhase.TURN,
                        GamePhase.RIVER,
                        GamePhase.SHOWDOWN
                },
                GamePhase.values());
    }
}
