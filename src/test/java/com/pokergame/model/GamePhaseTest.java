package com.pokergame.model;

import com.pokergame.enums.GamePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the GamePhase enum.
 */
@Tag("unit")
@DisplayName("Game phase enum")
class GamePhaseTest {

    /**
     * Protects the expected behavior for all phases exist.
     */
    @Test
    void testAllPhasesExist() {
        GamePhase[] phases = GamePhase.values();
        assertEquals(5, phases.length, "Should have 5 game phases");
    }

    /**
     * Protects the expected behavior for phase names.
     */
    @Test
    void testPhaseNames() {
        assertNotNull(GamePhase.PRE_FLOP);
        assertNotNull(GamePhase.FLOP);
        assertNotNull(GamePhase.TURN);
        assertNotNull(GamePhase.RIVER);
        assertNotNull(GamePhase.SHOWDOWN);
    }

    /**
     * Protects the expected behavior for value of.
     */
    @Test
    void testValueOf() {
        assertEquals(GamePhase.PRE_FLOP, GamePhase.valueOf("PRE_FLOP"));
        assertEquals(GamePhase.FLOP, GamePhase.valueOf("FLOP"));
        assertEquals(GamePhase.TURN, GamePhase.valueOf("TURN"));
        assertEquals(GamePhase.RIVER, GamePhase.valueOf("RIVER"));
        assertEquals(GamePhase.SHOWDOWN, GamePhase.valueOf("SHOWDOWN"));
    }

    /**
     * Protects the expected behavior for phase ordering.
     */
    @Test
    void testPhaseOrdering() {
        // Verify phases are in the expected game order
        GamePhase[] phases = GamePhase.values();
        assertEquals(GamePhase.PRE_FLOP, phases[0]);
        assertEquals(GamePhase.FLOP, phases[1]);
        assertEquals(GamePhase.TURN, phases[2]);
        assertEquals(GamePhase.RIVER, phases[3]);
        assertEquals(GamePhase.SHOWDOWN, phases[4]);
    }

    /**
     * Protects the expected behavior for enum equality.
     */
    @Test
    void testEnumEquality() {
        GamePhase preFlop1 = GamePhase.PRE_FLOP;
        GamePhase preFlop2 = GamePhase.PRE_FLOP;
        assertSame(preFlop1, preFlop2, "Enum instances should be identical");
    }

    /**
     * Protects the expected behavior for all phases unique.
     */
    @Test
    void testAllPhasesUnique() {
        GamePhase[] phases = GamePhase.values();
        for (int i = 0; i < phases.length; i++) {
            for (int j = i + 1; j < phases.length; j++) {
                assertNotEquals(phases[i], phases[j], "All phases should be unique");
            }
        }
    }
}
