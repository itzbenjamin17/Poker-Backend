package com.pokergame.enums;

/**
 * Identifies delayed game work that must survive process restart as an absolute
 * deadline rather than an in-memory future.
 */
public enum ScheduledGameTask {
    AUTO_ADVANCE,
    READY_OPEN,
    NEW_HAND,
    CLEANUP,
    GAME_END
}
