package com.pokergame.util;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Coordinates the single per-room lock so all reads and writes to a room or game happen under
 * one exclusive lock per room ID.
 * <p>
 * Re-entering the same room lock on the same thread is allowed. Attempting to acquire a lock
 * for a different room while already holding one throws {@link IllegalStateException} to prevent
 * deadlocks.
 * </p>
 */
@Service
public class RoomCoordinator {
    private final KeyedLockRegistry<String> lockRegistry;

    private final ThreadLocal<RoomCoordinationState> activeContext = new ThreadLocal<>();

    public RoomCoordinator() {
        this(new KeyedLockRegistry<>());
    }

    public RoomCoordinator(KeyedLockRegistry<String> lockRegistry) {
        this.lockRegistry = requireNonNull(lockRegistry, "lockRegistry must not be null");
    }

    /**
     * Acquires an exclusive lock for {@code roomId}, waiting if another thread holds it.
     *
     * @param roomId the unique ID of the room
     * @return the acquired lock lease (close when finished)
     * @throws IllegalStateException if the current thread already holds a lock for a different room
     */
    public LockLease acquire(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        checkCrossRoom(roomId);
        LockLease lease = lockRegistry.acquire(roomId);
        pushRoom(roomId);
        return new CoordinatorLease(roomId, lease);
    }

    /**
     * Attempts to acquire an exclusive lock for {@code roomId} immediately without waiting.
     *
     * @param roomId the unique ID of the room
     * @return the acquired lock lease, or {@code null} if currently held by another thread
     * @throws IllegalStateException if the current thread already holds a lock for a different room
     */
    public LockLease tryAcquire(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        checkCrossRoom(roomId);
        LockLease lease = lockRegistry.tryAcquire(roomId);
        if (lease == null) {
            return null;
        }
        pushRoom(roomId);
        return new CoordinatorLease(roomId, lease);
    }

    /**
     * Returns whether the current thread already holds the lock for {@code roomId}.
     *
     * @param roomId the unique ID of the room
     * @return {@code true} if the current thread holds the lock for {@code roomId}
     */
    public boolean isHeldByCurrentThread(String roomId) {
        if (roomId == null) {
            return false;
        }
        RoomCoordinationState current = activeContext.get();
        return current != null && current.roomId.equals(roomId) && lockRegistry.isHeldByCurrentThread(roomId);
    }

    /**
     * Verifies that the current thread already holds the lock for {@code roomId}.
     *
     * @param roomId the unique ID of the room
     * @throws IllegalStateException if the current thread does not hold the lock for {@code roomId}
     */
    public void assertHeldByCurrentThread(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        if (!isHeldByCurrentThread(roomId)) {
            throw new IllegalStateException("Current thread does not hold room lock for room: " + roomId);
        }
    }

    /**
     * Returns the number of rooms that currently have an active lock entry or waiting thread.
     *
     * @return active lock key count
     */
    @VisibleForTesting
    public int activeLockCount() {
        return lockRegistry.activeKeyCount();
    }

    /**
     * Returns the active reservation count (owners, waiters, and nested acquisitions) for {@code roomId}.
     *
     * @param roomId the unique ID of the room
     * @return active reservation count for {@code roomId}
     */
    @VisibleForTesting
    public int lockReservationCount(String roomId) {
        requireNonNull(roomId, "roomId must not be null");
        return lockRegistry.reservationCount(roomId);
    }

    private void checkCrossRoom(String roomId) {
        RoomCoordinationState current = activeContext.get();
        if (current != null && !current.roomId.equals(roomId)) {
            throw new IllegalStateException("Cannot coordinate room " + roomId
                    + " while already coordinating room " + current.roomId);
        }
    }

    private void pushRoom(String roomId) {
        RoomCoordinationState current = activeContext.get();
        if (current == null) {
            activeContext.set(new RoomCoordinationState(roomId));
        } else {
            current.depth++;
        }
    }

    private void popRoom(String roomId) {
        RoomCoordinationState current = activeContext.get();
        if (current != null && current.roomId.equals(roomId)) {
            current.depth--;
            if (current.depth <= 0) {
                activeContext.remove();
            }
        }
    }

    private static final class RoomCoordinationState {
        final String roomId;
        int depth;

        RoomCoordinationState(String roomId) {
            this.roomId = roomId;
            this.depth = 1;
        }
    }

    private final class CoordinatorLease implements LockLease {
        private final String roomId;
        private final LockLease innerLease;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private CoordinatorLease(String roomId, LockLease innerLease) {
            this.roomId = roomId;
            this.innerLease = innerLease;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    popRoom(roomId);
                } finally {
                    innerLease.close();
                }
            }
        }
    }
}
