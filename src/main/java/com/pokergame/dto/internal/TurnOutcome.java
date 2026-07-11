package com.pokergame.dto.internal;

import com.pokergame.model.Player;
import java.util.List;

/**
 * Represents the outcome of a player's decision during their turn.
 * Used to communicate state changes from the Game model back to the orchestrating service.
 *
 * @param type The type of outcome resulting from the action.
 * @param conversionMessage An optional message if the action was converted (e.g., CALL to ALL_IN).
 * @param winners The list of winning players, populated only if the outcome is SHOWDOWN.
 * @param winningsPerPlayer The amount of chips awarded to each winner, populated only if SHOWDOWN.
 */
public record TurnOutcome(
    TurnOutcomeType type,
    String conversionMessage,
    List<Player> winners,
    int winningsPerPlayer
) {
    /**
     * Defines the possible game progressions after a player's action.
     */
    public enum TurnOutcomeType {
        /** The current betting round continues; the next player must act. */
        NEXT_PLAYER,
        
        /** The betting round finished and the game advanced to the next phase (Flop, Turn, or River). */
        PHASE_ADVANCED,
        
        /** The betting round finished and multiple players are all-in; the rest of the board will be dealt automatically. */
        AUTO_ADVANCING,
        
        /** The hand is over and winners have been evaluated. */
        SHOWDOWN
    }
}
