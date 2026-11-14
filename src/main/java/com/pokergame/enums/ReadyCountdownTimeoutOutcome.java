package com.pokergame.enums;

/**
 * Outcomes when a scheduled ready-countdown timer expires.
 */
public enum ReadyCountdownTimeoutOutcome {
    /** Callback is stale or blocked by a pending forfeit and should be ignored. */
    IGNORE_STALE,
    /** Fewer than two eligible players remained, so the gate moved to waiting for players. */
    WAITING_FOR_PLAYERS,
    /** At least two eligible players remained, so they were forced ready for the next hand. */
    FORCED_READY
}
