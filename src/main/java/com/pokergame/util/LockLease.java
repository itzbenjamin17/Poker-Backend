package com.pokergame.util;

/**
 * An auto-closeable lease on a scoped keyed lock.
 * <p>
 * Releasing this lease unlocks the underlying lock before relinquishing
 * the key reservation.
 * </p>
 */
public interface LockLease extends AutoCloseable {

    @Override
    void close();

    /**
     * Returns a no-op lease that performs no locking or unlocking.
     *
     * @return a no-op lock lease
     */
    static LockLease none() {
        return NoOpLockLease.INSTANCE;
    }
}

enum NoOpLockLease implements LockLease {
    INSTANCE;

    @Override
    public void close() {
    }
}
