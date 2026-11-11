package com.pokergame.util;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A scoped keyed-lock registry that manages per-key reentrant locks without lock identity splitting.
 * <p>
 * A lease reserves its entry atomically before waiting; owners, waiters, and nested acquisitions
 * all hold a reservation count. Upon lease closure, the underlying lock is unlocked before
 * releasing that reservation, and the entry is removed from the registry only when its
 * reservation count reaches zero. A failed {@code tryAcquire} releases its reservation immediately.
 * </p>
 *
 * @param <K> the key type
 */
public class KeyedLockRegistry<K> {

    private final ConcurrentMap<K, LockEntry<K>> entries = new ConcurrentHashMap<>();

    private static final class LockEntry<K> {
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
        Objects.requireNonNull(key, "key cannot be null");
        LockEntry<K> entry = entries.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new LockEntry<>();
            }
            existing.reservations.incrementAndGet();
            return existing;
        });

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
        Objects.requireNonNull(key, "key cannot be null");
        LockEntry<K> entry = entries.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new LockEntry<>();
            }
            existing.reservations.incrementAndGet();
            return existing;
        });

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
        Objects.requireNonNull(key, "key cannot be null");
        LockEntry<K> entry = entries.compute(key, (k, existing) -> {
            if (existing == null) {
                existing = new LockEntry<>();
            }
            existing.reservations.incrementAndGet();
            return existing;
        });

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

    private void releaseReservation(K key, LockEntry<K> entry) {
        entries.compute(key, (k, existing) -> {
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
        LockEntry<K> entry = entries.get(key);
        return entry != null ? entry.reservations.get() : 0;
    }

    /**
     * Returns whether the lock for the given key is currently locked.
     *
     * @param key the key to inspect
     * @return true if the lock is held
     */
    public boolean isLocked(K key) {
        LockEntry<K> entry = entries.get(key);
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
        LockEntry<K> entry = entries.get(key);
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
        private final LockEntry<K> entry;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Lease(K key, LockEntry<K> entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
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
