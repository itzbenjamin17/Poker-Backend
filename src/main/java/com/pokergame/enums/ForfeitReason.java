package com.pokergame.enums;

/** Why the players who forfeited a match left it, reported in the final result. */
public enum ForfeitReason {
    /** The player's turn timer ran out. */
    TURN_TIMEOUT,
    /** The player chose to leave the table. */
    PLAYER_LEFT,
    /** The player stayed disconnected past their reconnect grace period, or an opponent claimed the win. */
    DISCONNECTED
}
