package com.pokergame.model;

import com.pokergame.enums.PlayerAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the PlayerAction enum.
 */
@Tag("unit")
@DisplayName("Player action enum")
class PlayerActionTest {

    @Test
    void testAllActionsExist() {
        PlayerAction[] actions = PlayerAction.values();
        assertEquals(6, actions.length, "Should have 6 player actions");
    }

    @Test
    void testActionNames() {
        assertNotNull(PlayerAction.FOLD);
        assertNotNull(PlayerAction.CHECK);
        assertNotNull(PlayerAction.CALL);
        assertNotNull(PlayerAction.BET);
        assertNotNull(PlayerAction.RAISE);
        assertNotNull(PlayerAction.ALL_IN);
    }

    @Test
    void testValueOf() {
        assertEquals(PlayerAction.FOLD, PlayerAction.valueOf("FOLD"));
        assertEquals(PlayerAction.CHECK, PlayerAction.valueOf("CHECK"));
        assertEquals(PlayerAction.CALL, PlayerAction.valueOf("CALL"));
        assertEquals(PlayerAction.BET, PlayerAction.valueOf("BET"));
        assertEquals(PlayerAction.RAISE, PlayerAction.valueOf("RAISE"));
        assertEquals(PlayerAction.ALL_IN, PlayerAction.valueOf("ALL_IN"));
    }

    @Test
    void testEnumEquality() {
        PlayerAction fold1 = PlayerAction.FOLD;
        PlayerAction fold2 = PlayerAction.FOLD;
        assertSame(fold1, fold2, "Enum instances should be identical");
    }

    @Test
    void testAllActionsUnique() {
        PlayerAction[] actions = PlayerAction.values();
        for (int i = 0; i < actions.length; i++) {
            for (int j = i + 1; j < actions.length; j++) {
                assertNotEquals(actions[i], actions[j], "All actions should be unique");
            }
        }
    }
}
