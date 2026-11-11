package com.pokergame.service;

import com.pokergame.util.LockLease;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();

        try (LockLease lease = roomCoordinator.acquire(roomId)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(1);
            assertDoesNotThrow(() -> roomCoordinator.assertHeldByCurrentThread(roomId));

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<LockLease> concurrent = pool.submit(() -> roomCoordinator.tryAcquire(roomId));
                assertThat(concurrent.get(2, TimeUnit.SECONDS)).isNull();

                Future<Boolean> otherThreadHeld = pool.submit(() -> roomCoordinator.isHeldByCurrentThread(roomId));
                assertThat(otherThreadHeld.get(2, TimeUnit.SECONDS)).isFalse();
            } finally {
                stopExecutor(pool);
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("tryAcquire succeeds when available and returns null when held by another thread")
    void tryAcquireSemantics() throws Exception {
        String roomId = "room-1";
        try (LockLease lease = roomCoordinator.tryAcquire(roomId)) {
            assertThat(lease).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<LockLease> attempt = pool.submit(() -> roomCoordinator.tryAcquire(roomId));
                assertThat(attempt.get(2, TimeUnit.SECONDS)).isNull();

                Future<LockLease> timedAttempt = pool.submit(() ->
                        roomCoordinator.tryAcquire(roomId, 50, TimeUnit.MILLISECONDS));
                assertThat(timedAttempt.get(2, TimeUnit.SECONDS)).isNull();
            } finally {
                stopExecutor(pool);
            }
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
    }

    @Test
    @DisplayName("reentrancy on the same room joins the boundary without deadlocking")
    void sameRoomReentrancyJoinsBoundary() {
        String roomId = "room-1";

        try (LockLease outer = roomCoordinator.acquire(roomId)) {
            assertThat(outer).isNotNull();
            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(1);

            try (LockLease inner = roomCoordinator.acquire(roomId)) {
                assertThat(inner).isNotNull();
                assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
                assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(2);

                roomCoordinator.coordinate(roomId, () -> {
                    assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
                    assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(3);
                });

                assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(2);
            }

            assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
            assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(1);
        }

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("cross-room nested coordination throws IllegalStateException across all entry points")
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

            assertThatThrownBy(() -> roomCoordinator.tryAcquire(roomB, 100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomA)
                    .hasMessageContaining(roomB);

            assertThatThrownBy(() -> roomCoordinator.coordinate(roomB, () -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomA)
                    .hasMessageContaining(roomB);

            assertThatThrownBy(() -> roomCoordinator.coordinate(roomB, () -> "result"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(roomA)
                    .hasMessageContaining(roomB);

            // Room A lease must still be held and unaffected
            assertThat(roomCoordinator.isHeldByCurrentThread(roomA)).isTrue();
            assertThat(roomCoordinator.getLockRegistry().reservationCount(roomB)).isEqualTo(0);
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
    @DisplayName("coordinate Runnable and Supplier execute under lease and return expected values")
    void coordinateEntryPoints() {
        String roomId = "room-1";
        AtomicBoolean ran = new AtomicBoolean(false);

        roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            ran.set(true);
        });
        assertThat(ran.get()).isTrue();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();

        String result = roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            return "computed-val";
        });
        assertThat(result).isEqualTo("computed-val");
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
    }

    @Test
    @DisplayName("exceptions inside coordinate release lease, decrement reservations, and clean context")
    void exceptionSafetyReleasesLeaseAndDecrementsReservations() {
        String roomId = "room-1";
        RuntimeException error = new IllegalStateException("Simulated business error");

        assertThatThrownBy(() -> roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            throw error;
        })).isSameAs(error);

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);

        assertThatThrownBy(() -> roomCoordinator.coordinate(roomId, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomId);
            throw error;
        })).isSameAs(error);

        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("closing lease multiple times is idempotent and does not break reentrancy count")
    void closingLeaseMultipleTimesIsIdempotent() {
        String roomId = "room-1";
        LockLease lease = roomCoordinator.acquire(roomId);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(1);

        lease.close();
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);

        assertDoesNotThrow(lease::close);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
    }

    @Test
    @DisplayName("null arguments reject cleanly with NullPointerException")
    void nullArgumentsValidation() {
        assertThatThrownBy(() -> roomCoordinator.acquire(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.tryAcquire(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.tryAcquire(null, 1, TimeUnit.SECONDS))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.coordinate(null, () -> {}))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.coordinate("room-1", (Runnable) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.coordinate(null, () -> "val"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> roomCoordinator.coordinate("room-1", (java.util.function.Supplier<String>) null))
                .isInstanceOf(NullPointerException.class);
    }
}
