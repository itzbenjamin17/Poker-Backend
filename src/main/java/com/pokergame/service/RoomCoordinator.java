package com.pokergame.service;

import com.pokergame.util.KeyedLockRegistry;
import com.pokergame.util.LockLease;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns and coordinates the single application concurrency boundary per room.
 * <p>
 * Room operations, projections, session transitions, and scheduled lifecycle
 * tasks acquire ownership of their room boundary through this coordinator.
 * Cross-room nested coordination is rejected on the same thread to prevent
 * distributed monitor deadlocks, while same-room reentrant operations join
 * the existing boundary.
 * </p>
 */
@Service
public class RoomCoordinator {

    private final KeyedLockRegistry<String> lockRegistry;
    private final ThreadLocal<RoomCoordinationState> activeContext = new ThreadLocal<>();

    private static final class RoomCoordinationState {
        final String roomId;
        int depth;

        RoomCoordinationState(String roomId) {
            this.roomId = roomId;
            this.depth = 1;
        }
    }

    public RoomCoordinator() {
        this(new KeyedLockRegistry<>());
    }

    public RoomCoordinator(KeyedLockRegistry<String> lockRegistry) {
        this.lockRegistry = requireNonNull(lockRegistry, "lockRegistry cannot be null");
    }

    /**
     * Acquires an exclusive lease for the given room, blocking until available.
     *
     * @param roomId the room identifier
     * @return the active lock lease
     * @throws IllegalStateException if the current thread is already coordinating a different room
     */
    public LockLease acquire(String roomId) {
        requireNonNull(roomId, "roomId cannot be null");
        checkCrossRoom(roomId);
        LockLease lease = lockRegistry.acquire(roomId);
        pushRoom(roomId);
        return new CoordinatorLease(roomId, lease);
    }

    /**
     * Attempts to acquire an exclusive lease for the given room without waiting.
     *
     * @param roomId the room identifier
     * @return the active lock lease, or {@code null} if held by another thread
     * @throws IllegalStateException if the current thread is already coordinating a different room
     */
    public LockLease tryAcquire(String roomId) {
        requireNonNull(roomId, "roomId cannot be null");
        checkCrossRoom(roomId);
        LockLease lease = lockRegistry.tryAcquire(roomId);
        if (lease == null) {
            return null;
        }
        pushRoom(roomId);
        return new CoordinatorLease(roomId, lease);
    }

    /**
     * Attempts to acquire an exclusive lease for the given room within the specified timeout.
     *
     * @param roomId  the room identifier
     * @param timeout maximum time to wait
     * @param unit    time unit of timeout
     * @return the active lock lease, or {@code null} if timed out
     * @throws InterruptedException  if interrupted while waiting
     * @throws IllegalStateException if the current thread is already coordinating a different room
     */
    public LockLease tryAcquire(String roomId, long timeout, TimeUnit unit) throws InterruptedException {
        requireNonNull(roomId, "roomId cannot be null");
        requireNonNull(unit, "unit cannot be null");
        checkCrossRoom(roomId);
        LockLease lease = lockRegistry.tryAcquire(roomId, timeout, unit);
        if (lease == null) {
            return null;
        }
        pushRoom(roomId);
        return new CoordinatorLease(roomId, lease);
    }

    /**
     * Executes an action under the exclusive coordination lease for the specified room.
     *
     * @param roomId the room identifier
     * @param action the action to execute
     */
    public void coordinate(String roomId, Runnable action) {
        requireNonNull(roomId, "roomId cannot be null");
        requireNonNull(action, "action cannot be null");
        try (LockLease _ = acquire(roomId)) {
            action.run();
        }
    }

    /**
     * Computes a result under the exclusive coordination lease for the specified room.
     *
     * @param roomId   the room identifier
     * @param supplier the supplier computing the result
     * @param <T>      return type
     * @return computed result
     */
    public <T> T coordinate(String roomId, Supplier<T> supplier) {
        requireNonNull(roomId, "roomId cannot be null");
        requireNonNull(supplier, "supplier cannot be null");
        try (LockLease _ = acquire(roomId)) {
            return supplier.get();
        }
    }

    /**
     * Checks whether the given room lease is held by the current thread.
     *
     * @param roomId the room identifier
     * @return true if held by current thread
     */
    public boolean isHeldByCurrentThread(String roomId) {
        if (roomId == null) {
            return false;
        }
        RoomCoordinationState current = activeContext.get();
        return current != null && current.roomId.equals(roomId) && lockRegistry.isHeldByCurrentThread(roomId);
    }

    /**
     * Asserts that the current thread holds the coordination lease for the given room.
     *
     * @param roomId the room identifier
     * @throws IllegalStateException if the lease is not held by the current thread
     */
    public void assertHeldByCurrentThread(String roomId) {
        requireNonNull(roomId, "roomId cannot be null");
        if (!isHeldByCurrentThread(roomId)) {
            throw new IllegalStateException("Current thread does not hold coordination lease for room: " + roomId);
        }
    }

    /**
     * Returns the underlying keyed lock registry.
     *
     * @return lock registry
     */
    public KeyedLockRegistry<String> getLockRegistry() {
        return lockRegistry;
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
