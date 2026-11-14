package com.pokergame.enums;

/**
 * Outcomes when evaluating whether to open the between-hand ready countdown gate.
 */
public enum ReadyCountdownStartOutcome {
    /** Gate evaluation is ignored because the game is over, a forfeit is pending, or the phase is not showdown. */
    IGNORE,
    /** Game has been decided because one or zero players with chips remain. */
    GAME_DECIDED,
    /** Gate entered the waiting-for-players state because fewer than two eligible players are connected. */
    WAITING_FOR_PLAYERS,
    /** Ready countdown was opened with a scheduled deadline. */
    OPENED,
    /** Ready countdown was opened and all eligible players are already marked ready. */
    ALL_READY
}
