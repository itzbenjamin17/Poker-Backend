package com.pokergame.persistence;

/**
 * Names storage boundaries where an interrupted process could otherwise leave an
 * ambiguous result. Tests use these points to prove recovery and health behavior.
 */
public enum WalFaultPoint {
    BEFORE_PREPARE_WRITE,
    BEFORE_COMMIT_WRITE,
    BEFORE_FLUSH,
    BEFORE_COMPACTION_REPLACE,
    BEFORE_DELETE
}
