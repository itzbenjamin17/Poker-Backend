package com.pokergame.enums;

/**
 * Outcomes when claiming execution of a scheduled game task callback.
 */
public enum TaskExecutionClaim {
    /** Task matches current game state and should execute. */
    EXECUTE,
    /** Game has already finished; non-cleanup progression tasks should be cancelled. */
    CANCEL_GAME_OVER,
    /** Callback is stale, superseded, or blocked by a pending forfeit and should be ignored. */
    IGNORE
}
