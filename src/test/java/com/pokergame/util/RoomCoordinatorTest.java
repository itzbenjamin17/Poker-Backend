package com.pokergame.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RoomCoordinator}. */
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
        assertThat(roomCoordinator.activeLockCount()).isZero();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();

        try (LockLease lease = roomCoordinator.acquire(roomId)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);
            assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread(roomId)).doesNotThrowAnyException();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<LockLease> concurrent = pool.submit(() -> roomCoordinator.tryAcquire(roomId));
                assertThat(concurrent.get(2, TimeUnit.SECONDS)).isNull();

                Future<Boolean> otherThreadHeld = pool.submit(() -> roomCoordinator.isHeldByCurrentThread(roomId));
                assertThat(otherThreadHeld.get(2, TimeUnit.SECONDS)).isFalse();
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
    }

    @Test
    @DisplayName("tryAcquire succeeds when available, returns null when held by another thread, and allows acquiring another room after failure")
    void tryAcquireSemanticsAndPostFailureAcquisition() throws Exception {
        String roomA = "room-1";
        String roomB = "room-2";
        try (LockLease lease = roomCoordinator.tryAcquire(roomA)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> attempt = pool.submit(() -> {
                    assertThat(roomCoordinator.tryAcquire(roomA)).isNull();
                    assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();
                    try (LockLease otherLease = roomCoordinator.acquire(roomB)) {
                        assertThat(otherLease).isNotNull();
                        return roomCoordinator.isHeldByCurrentThread(roomB);
                    }
                });
                assertThat(attempt.get(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomA)).isZero();
        assertThat(roomCoordinator.lockReservationCount(roomB)).isZero();
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
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
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

            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomB)).isZero();
        }

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
            assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread(roomA)).doesNotThrowAnyException();
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
    @DisplayName("exceptions inside try-with-resources release lock, decrement reservations, and clean context")
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
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();

        assertThatThrownBy(() -> {
            try (LockLease _ = roomCoordinator.tryAcquire(roomId)) {
                roomCoordinator.assertHeldByCurrentThread(roomId);
                throw error;
            }
        }).isSameAs(error);

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
    }

    @Test
    @DisplayName("closing a lease multiple times is idempotent")
    void closingLeaseMultipleTimesIsIdempotent() {
        String roomId = "room-1";
        LockLease lease = roomCoordinator.acquire(roomId);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);

        lease.close();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();

        assertThatCode(lease::close).doesNotThrowAnyException();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
    }

    @Test
    @DisplayName("closing a reentrant lease multiple times is idempotent and preserves the outer lock")
    void closingReentrantLeaseMultipleTimesIsIdempotentAndPreservesOuterLock() {
        String roomId = "room-1";
        String otherRoomId = "room-2";
        LockLease outer = roomCoordinator.acquire(roomId);
        LockLease inner = roomCoordinator.acquire(roomId);
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(2);

        inner.close();
        assertThatCode(inner::close).doesNotThrowAnyException();

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isEqualTo(1);
        assertThatCode(() -> roomCoordinator.assertHeldByCurrentThread(roomId)).doesNotThrowAnyException();
        assertThatThrownBy(() -> roomCoordinator.acquire(otherRoomId))
                .isInstanceOf(IllegalStateException.class);

        outer.close();
        assertThatCode(outer::close).doesNotThrowAnyException();

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
        try (LockLease other = roomCoordinator.acquire(otherRoomId)) {
            assertThat(other).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(otherRoomId)).isTrue();
        }
    }

    @Test
    @DisplayName("wrong-thread close throws IllegalMonitorStateException and preserves owner and caller thread state")
    void wrongThreadClosePreservesOwnerLockAndCallerContext() throws Exception {
        String roomA = "room-A";
        String roomB = "room-B";

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (LockLease leaseA = roomCoordinator.acquire(roomA)) {
            pool.submit(() -> {
                assertThatThrownBy(leaseA::close)
                        .isInstanceOf(IllegalMonitorStateException.class);
                assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isFalse();

                try (LockLease _ = roomCoordinator.acquire(roomB)) {
                    assertThatThrownBy(leaseA::close)
                            .isInstanceOf(IllegalMonitorStateException.class);
                    assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isTrue();
                    roomCoordinator.assertHeldByCurrentThread(roomB);
                }
                assertThat(roomCoordinator.isHeldByCurrentThread(roomB)).isFalse();
                return null;
            }).get(2, TimeUnit.SECONDS);

            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomA)).isEqualTo(1);
            assertThat(roomCoordinator.lockReservationCount(roomB)).isZero();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(roomCoordinator.activeLockCount()).isZero();
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
    @DisplayName("queued waiter sees complete compound mutation after lock release")
    void queuedWaiterSeesCompleteCompoundMutation() throws Exception {
        String roomId = "room-atomic-handoff";
        List<String> steps = new ArrayList<>();
        CountDownLatch waiterReady = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<String>> waiterSnapshot;
            try (LockLease _ = roomCoordinator.acquire(roomId)) {
                steps.add("step-1");
                waiterSnapshot = executor.submit(() -> {
                    waiterReady.countDown();
                    try (LockLease _ = roomCoordinator.acquire(roomId)) {
                        return List.copyOf(steps);
                    }
                });
                assertThat(waiterReady.await(2, TimeUnit.SECONDS)).isTrue();
                while (roomCoordinator.lockReservationCount(roomId) < 2) {
                    Thread.onSpinWait();
                }
                steps.add("step-2");
            }

            assertThat(waiterSnapshot.get(2, TimeUnit.SECONDS)).containsExactly("step-1", "step-2");
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(roomCoordinator.activeLockCount()).isZero();
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

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
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
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(totalExecutions.get()).isEqualTo(threadCount * iterationsPerThread);
        assertThat(maxObservedConcurrency.get()).isEqualTo(1);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.lockReservationCount(roomId)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
    }

    @Test
    @DisplayName("room independence: an operation held on room A does not block or delay operations on room B")
    void roomIndependenceBlockedRoomADoesNotDelayRoomB() throws Exception {
        String roomA = "room-A";
        String roomB = "room-B";

        CountDownLatch roomAEntered = new CountDownLatch(1);
        CountDownLatch releaseRoomA = new CountDownLatch(1);
        AtomicBoolean roomAFinished = new AtomicBoolean(false);

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Future<Void> taskA = executor.submit(() -> {
                try (LockLease _ = roomCoordinator.acquire(roomA)) {
                    roomAEntered.countDown();
                    releaseRoomA.await(5, TimeUnit.SECONDS);
                }
                roomAFinished.set(true);
                return null;
            });

            assertThat(roomAEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(roomCoordinator.lockReservationCount(roomA)).isEqualTo(1);
            assertThat(roomAFinished.get()).isFalse();

            Future<String> taskB = executor.submit(() -> {
                try (LockLease _ = roomCoordinator.acquire(roomB)) {
                    roomCoordinator.assertHeldByCurrentThread(roomB);
                    return "room-B-done";
                }
            });

            assertThat(taskB.get(2, TimeUnit.SECONDS)).isEqualTo("room-B-done");
            assertThat(roomAFinished.get()).isFalse();

            releaseRoomA.countDown();
            taskA.get(2, TimeUnit.SECONDS);
            assertThat(roomAFinished.get()).isTrue();
        } finally {
            releaseRoomA.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(roomCoordinator.lockReservationCount(roomA)).isZero();
        assertThat(roomCoordinator.lockReservationCount(roomB)).isZero();
        assertThat(roomCoordinator.activeLockCount()).isZero();
    }
}
