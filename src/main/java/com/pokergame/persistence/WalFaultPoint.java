package com.pokergame.persistence;

public enum WalFaultPoint {
    BEFORE_PREPARE_WRITE,
    BEFORE_COMMIT_WRITE,
    BEFORE_FLUSH,
    BEFORE_COMPACTION_REPLACE,
    BEFORE_DELETE
}
