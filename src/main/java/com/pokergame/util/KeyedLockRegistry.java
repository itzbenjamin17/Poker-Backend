package com.pokergame.util;

import static java.util.Objects.requireNonNull;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A registry of reentrant locks, with one shared lock per key.
 * <p>
 * Each acquisition reserves the key's entry before attempting to lock it. This keeps the same
 * lock available to current owners, waiting threads, and reentrant acquisitions. Closing a lease
 * unlocks first, then releases its reservation. The registry removes an entry when its last
 * reservation is released. If {@code tryAcquire} fails, its reservation is released immediately.
 * </p>
 *
 * @param <K> the key type
 */
public class KeyedLockRegistry<K> {

    private final ConcurrentMap<K, LockEntry> entries = new ConcurrentHashMap<>();

    private static final class LockEntry {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger reservations = new AtomicInteger(0);
    }

    /**
     * Acquires an exclusive lease for the given key, blocking until the lock is available.
     *
     * @param key the key to lock
     * @return the active lock lease
     */
    public LockLease acquire(K key) {
        LockEntry entry = reserveEntry(key);

        entry.lock.lock();
        return new Lease(key, entry);
    }

    /**
     * Attempts to acquire an exclusive lease for the given key without waiting.
     *
     * @param key the key to lock
     * @return the lock lease if acquired, or {@code null} if the lock was held
     */
    public LockLease tryAcquire(K key) {
        LockEntry entry = reserveEntry(key);

        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock();
        } finally {
            if (!acquired) {
                releaseReservation(key, entry);
            }
        }

        if (!acquired) {
            return null;
        }
        return new Lease(key, entry);
    }

    /**
     * Attempts to acquire an exclusive lease for the given key, waiting up to the specified timeout.
     *
     * @param key     the key to lock
     * @param timeout the maximum time to wait
     * @param unit    the time unit of the timeout argument
     * @return the lock lease if acquired, or {@code null} if timed out or interrupted
     * @throws InterruptedException if interrupted while waiting
     */
    public LockLease tryAcquire(K key, long timeout, TimeUnit unit) throws InterruptedException {
        LockEntry entry = reserveEntry(key);

        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeout, unit);
        } finally {
            if (!acquired) {
                releaseReservation(key, entry);
            }
        }

        if (!acquired) {
            return null;
        }
        return new Lease(key, entry);
    }

    /**
     * Atomically gets or creates the entry for the key and increments its reservation count
     * before the caller attempts to acquire the lock.
     *
     * @param key the key to reserve
     * @return the reserved entry
     * @throws NullPointerException if the key is null
     */
    private LockEntry reserveEntry(K key) {
        requireNonNull(key, "key cannot be null");
        return entries.compute(key, (_, existing) -> {
            if (existing == null) {
                existing = new LockEntry();
            }
            existing.reservations.incrementAndGet();
            return existing;
        });
    }

    /**
     * Releases one reservation if the entry is still mapped to the key.
     * Removes the entry when its reservation count reaches zero.
     *
     * @param key the key whose reservation is being released
     * @param entry the entry that holds the reservation
     */
    private void releaseReservation(K key, LockEntry entry) {
        entries.compute(key, (_, existing) -> {
            if (existing == entry) {
                if (existing.reservations.decrementAndGet() == 0) {
                    return null;
                }
                return existing;
            }
            return existing;
        });
    }

    /**
     * Returns the reservation count for the specified key.
     *
     * @param key the key to inspect
     * @return the active reservation count (owners, waiters, nested acquisitions)
     */
    public int reservationCount(K key) {
        LockEntry entry = entries.get(key);
        return entry != null ? entry.reservations.get() : 0;
    }

    /**
     * Returns whether the lock for the given key is currently locked.
     *
     * @param key the key to inspect
     * @return true if the lock is held
     */
    public boolean isLocked(K key) {
        LockEntry entry = entries.get(key);
        return entry != null && entry.lock.isLocked();
    }

    /**
     * Returns whether the lock for the given key is held by the current thread.
     *
     * @param key the key to inspect
     * @return true if the lock is held by the current thread
     */
    public boolean isHeldByCurrentThread(K key) {
        if (key == null) {
            return false;
        }
        LockEntry entry = entries.get(key);
        return entry != null && entry.lock.isHeldByCurrentThread();
    }

    /**
     * Returns the number of distinct keys currently active in the registry.
     *
     * @return active key count
     */
    public int activeKeyCount() {
        return entries.size();
    }

    private final class Lease implements LockLease {
        private final K key;
        private final LockEntry entry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * Creates a lease for an acquired lock and its existing reservation.
         *
         * @param key the key used to release the reservation
         * @param entry the reserved entry whose lock the caller holds
         */
        private Lease(K key, LockEntry entry) {
            this.key = key;
            this.entry = entry;
        }

        /**
         * Unlocks the entry and releases this lease's reservation.
         * Calls made after the lease has closed return immediately.
         *
         * @throws IllegalMonitorStateException if the lease is open and the current thread
         *                                      does not hold the lock
         */
        @Override
        public void close() {
            if (closed.get()) {
                return;
            }
            if (!entry.lock.isHeldByCurrentThread()) {
                throw new IllegalMonitorStateException(
                        "Lease must be closed by the thread that acquired it");
            }

            if (closed.compareAndSet(false, true)) {
                try {
                    entry.lock.unlock();
                } finally {
                    releaseReservation(key, entry);
                }
            }
        }
    }
}
