package com.pokergame.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyedLockRegistryTest {

    private KeyedLockRegistry<String> lockRegistry;

    @BeforeEach
    void setUp() {
        lockRegistry = new KeyedLockRegistry<>();
    }

    @Test
    @DisplayName("acquire provides mutual exclusion for same key and releases entry when closed")
    void acquireProvidesMutualExclusionAndCleansUp() throws Exception {
        String key = "room-1";
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);

        try (LockLease lease = lockRegistry.acquire(key)) {
            assertThat(lease).isNotNull();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();
            assertThat(lockRegistry.activeKeyCount()).isEqualTo(1);

            // Concurrent tryAcquire from another thread must fail
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<LockLease> concurrent = pool.submit(() -> lockRegistry.tryAcquire(key));
                assertThat(concurrent.get(2, TimeUnit.SECONDS)).isNull();
            } finally {
                stopExecutor(pool);
            }
            // Failed tryAcquire should not leave an orphaned reservation
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
        }

        // After close, reservation reaches 0 and entry is removed
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.isLocked(key)).isFalse();
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("different keys operate independently without contention")
    void differentKeysAreIndependent() {
        try (LockLease lease1 = lockRegistry.acquire("room-1")) {
            assertThat(lease1).isNotNull();
            try (LockLease lease2 = lockRegistry.tryAcquire("room-2")) {
                assertThat(lease2).isNotNull();
                assertThat(lockRegistry.activeKeyCount()).isEqualTo(2);
            }
            assertThat(lockRegistry.reservationCount("room-2")).isEqualTo(0);
        }
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("failed tryAcquire releases reservation immediately")
    void failedTryAcquireReleasesReservation() throws Exception {
        String key = "room-1";
        CountDownLatch heldLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (LockLease _ = lockRegistry.acquire(key)) {
                heldLatch.countDown();
                doneLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(heldLatch.await(2, TimeUnit.SECONDS)).isTrue();

        // While held, tryAcquire fails
        LockLease failed = lockRegistry.tryAcquire(key);
        assertThat(failed).isNull();
        // Reservation should remain 1 (only the holder)
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);

        doneLatch.countDown();
        holder.join(2000);

        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("nested acquisition increments reservation and remains locked until outer lease closes")
    void nestedAcquisitionMaintainsLockUntilOuterCloses() {
        String key = "room-1";

        try (LockLease _ = lockRegistry.acquire(key)) {
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();

            try (LockLease _ = lockRegistry.acquire(key)) {
                assertThat(lockRegistry.reservationCount(key)).isEqualTo(2);
                assertThat(lockRegistry.isLocked(key)).isTrue();
            }

            // Inner lease closed, but outer is still held
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();
        }

        // Outer closed, completely freed
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.isLocked(key)).isFalse();
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("closing lease multiple times is idempotent")
    void closingLeaseMultipleTimesIsIdempotent() {
        String key = "room-1";
        LockLease lease = lockRegistry.acquire(key);
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);

        lease.close();
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);

        // Subsequent closes must be no-ops
        assertDoesNotThrow(lease::close);
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }

    @Test
    void wrongThreadClosePreservesLockAndReservation() throws Exception {
        String key = "room-1";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (LockLease lease = lockRegistry.acquire(key)) {
            pool.submit(() -> {
                assertThrows(IllegalMonitorStateException.class, lease::close);
                try (LockLease competingLease = lockRegistry.tryAcquire(key)) {
                    assertThat(competingLease).isNull();
                }
            }).get(2, TimeUnit.SECONDS);

            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isHeldByCurrentThread(key)).isTrue();
        } finally {
            stopExecutor(pool);
        }

        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("isHeldByCurrentThread reflects only the owning thread")
    void isHeldByCurrentThreadReflectsOnlyOwningThread() throws Exception {
        String key = "room-1";
        assertThat(lockRegistry.isHeldByCurrentThread(key)).isFalse();
        assertThat(lockRegistry.isHeldByCurrentThread(null)).isFalse();

        try (LockLease lease = lockRegistry.acquire(key)) {
            assertThat(lockRegistry.isHeldByCurrentThread(key)).isTrue();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> otherThreadHeld = pool.submit(() -> lockRegistry.isHeldByCurrentThread(key));
                assertThat(otherThreadHeld.get(2, TimeUnit.SECONDS)).isFalse();
            } finally {
                stopExecutor(pool);
            }
        }

        assertThat(lockRegistry.isHeldByCurrentThread(key)).isFalse();
    }

    @Test
    void concurrentWaitersAndReleasesMaintainMutualExclusion() throws Exception {
        String key = "concurrent-key";
        int threads = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger activeExecutions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                workers.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        try (LockLease _ = lockRegistry.acquire(key)) {
                            try {
                                assertThat(activeExecutions.incrementAndGet()).isEqualTo(1);
                                Thread.yield();
                            } finally {
                                activeExecutions.decrementAndGet();
                            }
                        }
                    }
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
            assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
        } finally {
            stopExecutor(executor);
        }
    }
}
