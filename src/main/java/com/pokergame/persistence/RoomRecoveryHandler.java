package com.pokergame.persistence;

import com.pokergame.persistence.snapshot.RecoveredAggregate;

/**
 * Applies domain restart rules to one recovered room while the caller holds the room lock.
 */
public interface RoomRecoveryHandler {

    /**
     * Applies the restart rules to one recovered room. The caller holds the room lock.
     *
     * @param recovered the deserialized room and optional game snapshot
     * @return {@link RecoveryDecision#KEEP} if the room remains active, or
     *         {@link RecoveryDecision#DISCARD} if its persisted WAL should be deleted
     */
    RecoveryDecision restore(RecoveredAggregate recovered);
}
