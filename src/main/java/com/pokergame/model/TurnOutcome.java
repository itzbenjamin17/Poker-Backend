package com.pokergame.model;

/**
 * Represents the outcome of a player's decision, forced blinds, or departure during a hand.
 * Communicates state transitions from {@link Game} back to the orchestrating services.
 *
 * @param type               the resulting progression step for the hand
 * @param playerActionNotice optional notice when an action was converted (e.g., CALL to ALL_IN)
 */
public record TurnOutcome(
        TurnOutcomeType type,
        String playerActionNotice) {

    /**
     * Defines the possible game progressions after a player's action or table event.
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
