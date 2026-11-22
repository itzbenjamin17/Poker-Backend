package com.pokergame.persistence;

/**
 * Outcome returned by {@link RoomRecoveryHandler} for one recovered room snapshot.
 */
public enum RecoveryDecision {
    /** Keep the restored room and its persisted WAL. */
    KEEP,
    /** Discard the expired room and delete its persisted WAL. */
    DISCARD
}
