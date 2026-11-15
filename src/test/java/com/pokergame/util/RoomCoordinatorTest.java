package com.pokergame.util;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("unit")
@DisplayName("Room coordinator")
class RoomCoordinatorTest {

    private RoomCoordinator roomCoordinator;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
    }

    @Test
    @DisplayName("acquire provides exclusive locking and releases entry when closed")
    void acquireProvidesExclusiveLockingAndReleasesEntry() throws Exception {
        String roomId = "room-1";
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();

        try (LockLease lease = roomCoordinator.acquire(roomId)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);
            assertDoesNotThrow(() -> roomCoordinator.assertHeldByCurrentThread(roomId));

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                Future<LockLease> concurrent = pool.submit(() -> roomCoordinator.tryAcquire(roomId));
                assertThat(concurrent.get(2, TimeUnit.SECONDS)).isNull();

                Future<Boolean> otherThreadHeld = pool.submit(() -> roomCoordinator.isHeldByCurrentThread(roomId));
                assertThat(otherThreadHeld.get(2, TimeUnit.SECONDS)).isFalse();
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("tryAcquire succeeds when available and returns null when held by another thread")
    void tryAcquireSemantics() throws Exception {
        String roomId = "room-1";
        try (LockLease lease = roomCoordinator.tryAcquire(roomId)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();

            try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
                Future<LockLease> attempt = pool.submit(() -> roomCoordinator.tryAcquire(roomId));
                assertThat(attempt.get(2, TimeUnit.SECONDS)).isNull();
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
    }

    @Test
    @DisplayName("reentrancy on the same room joins the boundary without deadlocking")
    void sameRoomReentrancyJoinsBoundary() {
        String roomId = "room-1";

        try (LockLease outer = roomCoordinator.acquire(roomId)) {
            assertThat(outer).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);

            try (LockLease inner = roomCoordinator.acquire(roomId)) {
                assertThat(inner).isNotNull();
                assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
                assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(2);

                try (LockLease innermost = roomCoordinator.tryAcquire(roomId)) {
                    assertThat(innermost).isNotNull();
                    assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
                    assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(3);
                }

                assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(2);
            }

            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("cross-room nested coordination throws IllegalStateException across acquire and tryAcquire")
    void crossRoomNestedCoordinationRejection() {
        String roomA = "room-A";
        String roomB = "room-B";

        try (LockLease _ = roomCoordinator.acquire(roomA)) {
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();

            assertThatThrownBy(() -> roomCoordinator.acquire(roomB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomA)
                    .hasMessageContaining(roomB);

            assertThatThrownBy(() -> roomCoordinator.tryAcquire(roomB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomA)
                    .hasMessageContaining(roomB);

            // Room A lease must still be held and unaffected
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomB)).isEqualTo(0);
        }

        // After releasing room A, room B can now be acquired
        assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();
        try (LockLease leaseB = roomCoordinator.acquire(roomB)) {
            assertThat(leaseB).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isTrue();
        }
    }

    @Test
    @DisplayName("ownership assertions succeed when held and throw IllegalStateException when not held")
    void ownershipAssertions() {
        String roomA = "room-A";
        String roomB = "room-B";

        assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();
        assertThat(roomCoordinator.isHeldByCurrentThread(null)).isFalse();
        assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread(roomA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(roomA);

        assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread(null))
                .isInstanceOf(NullPointerException.class);

        try (LockLease _ = roomCoordinator.acquire(roomA)) {
            assertDoesNotThrow(() -> roomCoordinator.assertHeldByCurrentThread(roomA));
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isFalse();

            assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread(roomB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomB);
        }

        assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread(roomA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("exceptions inside try-with-resources lease release lock, decrement reservations, and clean context")
    void exceptionSafetyReleasesLeaseAndDecrementsReservations() {
        String roomId = "room-1";
        RuntimeException error = new IllegalStateException("Simulated business error");

        assertThatThrownBy(() -> {
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                roomCoordinator.assertHeldByCurrentThread(roomId);
                throw error;
            }
        }).isSameAs(error);

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);

        assertThatThrownBy(() -> {
            try (LockLease _ = roomCoordinator.tryAcquire(roomId)) {
                roomCoordinator.assertHeldByCurrentThread(roomId);
                throw error;
            }
        }).isSameAs(error);

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("closing lease multiple times is idempotent and does not break reentrancy count")
    void closingLeaseMultipleTimesIsIdempotent() {
        String roomId = "room-1";
        LockLease lease = roomCoordinator.acquire(roomId);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);

        lease.close();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);

        assertDoesNotThrow(lease::close);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
    }

    @Test
    @DisplayName("null arguments reject cleanly with NullPointerException")
    void nullArgumentsValidation() {
        assertThatThrownBy(() -> roomCoordinator.acquire(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.tryAcquire(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.assertHeldByCurrentThread(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.lockReservationCount(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("same-room contention strictly serializes operations")
    void sameRoomContentionSerializesOperationsBounded() throws Exception {
        String roomId = "room-contended";
        int threadCount = 8;
        int iterationsPerThread = 25;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger activeExecutions = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        AtomicInteger totalExecutions = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    for (int iter = 0; iter < iterationsPerThread; iter++) {
                        try (LockLease _ = roomCoordinator.acquire(roomId)) {
                            int current = activeExecutions.incrementAndGet();
                            maxObservedConcurrency.updateAndGet(max -> Math.max(max, current));

                            assertThat(current).isEqualTo(1);
                            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();

                            totalExecutions.incrementAndGet();
                            activeExecutions.decrementAndGet();
                        }
                    }
                    return null;
                }));
            }

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(totalExecutions.get()).isEqualTo(threadCount * iterationsPerThread);
        assertThat(maxObservedConcurrency.get()).isEqualTo(1);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("room independence: an operation held on room A does not block or delay operations on room B")
    void roomIndependenceBlockedRoomADoesNotDelayRoomB() throws Exception {
        String roomA = "room-A";
        String roomB = "room-B";

        AtomicBoolean releaseRoomA = new AtomicBoolean(false);
        AtomicBoolean roomAEntered = new AtomicBoolean(false);
        AtomicBoolean roomAFinished = new AtomicBoolean(false);

        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            Future<Void> taskA = executor.submit(() -> {
                try (LockLease _ = roomCoordinator.acquire(roomA)) {
                    roomAEntered.set(true);
                    Awaitility.await().atMost(Duration.ofSeconds(5)).untilTrue(releaseRoomA);
                }
                roomAFinished.set(true);
                return null;
            });

            Awaitility.await().atMost(Duration.ofSeconds(2))
                    .until(() -> roomAEntered.get() && roomCoordinator.lockReservationCount(roomA) >= 1);
            assertThat(roomAFinished.get()).isFalse();

            Future<String> taskB = executor.submit(() -> {
                try (LockLease _ = roomCoordinator.acquire(roomB)) {
                    roomCoordinator.assertHeldByCurrentThread(roomB);
                    return "room-B-done";
                }
            });

            assertThat(taskB.get(2, TimeUnit.SECONDS)).isEqualTo("room-B-done");
            assertThat(roomAFinished.get()).isFalse();

            releaseRoomA.set(true);
            taskA.get(2, TimeUnit.SECONDS);
            assertThat(roomAFinished.get()).isTrue();
        }

        assertThat(roomCoordinator.lockReservationCount(roomA)).isEqualTo(0);
        assertThat(roomCoordinator.lockReservationCount(roomB)).isEqualTo(0);
        assertThat(roomCoordinator.activeLockCount()).isEqualTo(0);
    }
}
