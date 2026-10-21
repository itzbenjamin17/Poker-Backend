package com.pokergame.util;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
                pool.shutdown();
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
            try (LockLease ignored = lockRegistry.acquire(key)) {
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

        try (LockLease outer = lockRegistry.acquire(key)) {
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();

            try (LockLease inner = lockRegistry.acquire(key)) {
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
    @DisplayName("deterministically queue a waiter during final release: at most one operation executes at once")
    void concurrentWaitersAndReleasesMaintainMutualExclusion() throws Exception {
        String key = "concurrent-key";
        int threads = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger activeExecutions = new AtomicInteger(0);
        AtomicBoolean overlapDetected = new AtomicBoolean(false);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterations; i++) {
                        try (LockLease lease = lockRegistry.acquire(key)) {
                            int current = activeExecutions.incrementAndGet();
                            if (current > 1) {
                                overlapDetected.set(true);
                            }
                            Thread.yield();
                            activeExecutions.decrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);

        assertThat(overlapDetected.get()).as("No concurrent executions should overlap").isFalse();
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(0);
        assertThat(lockRegistry.activeKeyCount()).isEqualTo(0);
    }
}
