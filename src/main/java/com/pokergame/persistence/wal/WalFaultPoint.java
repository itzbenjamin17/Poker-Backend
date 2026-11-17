package com.pokergame.persistence.wal;

import com.pokergame.util.VisibleForTesting;

/**
 * Defines specific points during the save process where a crash might cause problems.
 * These are used by our tests to simulate crashes at the worst possible moments
 * and make sure the game can still recover correctly.
 */
@VisibleForTesting
public enum WalFaultPoint {
    BEFORE_PREPARE_WRITE,
    BEFORE_COMMIT_WRITE,
    BEFORE_FLUSH,
    BEFORE_COMPACTION_REPLACE,
    BEFORE_DELETE
}
