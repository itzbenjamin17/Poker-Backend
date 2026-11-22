package com.pokergame.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link KeyedLockRegistry}. */
@Tag("unit")
class KeyedLockRegistryTest {

    private KeyedLockRegistry<String> lockRegistry;

    @BeforeEach
    void setUp() {
        lockRegistry = new KeyedLockRegistry<>();
    }

    @Test
    @DisplayName("null keys throw NullPointerException on acquire and tryAcquire")
    void nullKeyThrowsNullPointerException() {
        assertThatThrownBy(() -> lockRegistry.acquire(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> lockRegistry.tryAcquire(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("acquire provides mutual exclusion for same key and releases entry when closed")
    void acquireProvidesMutualExclusionAndCleansUp() throws Exception {
        String key = "room-1";
        assertThat(lockRegistry.activeKeyCount()).isZero();

        try (LockLease lease = lockRegistry.acquire(key)) {
            assertThat(lease).isNotNull();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();
            assertThat(lockRegistry.activeKeyCount()).isEqualTo(1);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<LockLease> concurrent = pool.submit(() -> lockRegistry.tryAcquire(key));
                assertThat(concurrent.get(2, TimeUnit.SECONDS)).isNull();
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
        }

        assertThat(lockRegistry.reservationCount(key)).isZero();
        assertThat(lockRegistry.isLocked(key)).isFalse();
        assertThat(lockRegistry.activeKeyCount()).isZero();
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
            assertThat(lockRegistry.reservationCount("room-2")).isZero();
        }
        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("failed tryAcquire releases reservation immediately")
    void failedTryAcquireReleasesReservation() throws Exception {
        String key = "room-1";
        CountDownLatch acquiredLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);

        ExecutorService holderPool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = holderPool.submit(() -> {
                try (LockLease _ = lockRegistry.acquire(key)) {
                    acquiredLatch.countDown();
                    doneLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(acquiredLatch.await(2, TimeUnit.SECONDS)).isTrue();

            LockLease failed = lockRegistry.tryAcquire(key);
            assertThat(failed).isNull();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);

            doneLatch.countDown();
            holder.get(2, TimeUnit.SECONDS);
        } finally {
            doneLatch.countDown();
            holderPool.shutdownNow();
            holderPool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(lockRegistry.reservationCount(key)).isZero();
        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("nested acquisition via acquire and tryAcquire increments reservation and inner double-close preserves outer lock")
    void nestedAcquisitionAndReentrantDoubleClosePreserveOuterLock() {
        String key = "room-1";

        try (LockLease _ = lockRegistry.acquire(key)) {
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();

            LockLease innerAcquire = lockRegistry.acquire(key);
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(2);

            LockLease innerTry = lockRegistry.tryAcquire(key);
            assertThat(innerTry).isNotNull();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(3);

            innerTry.close();
            assertThatCode(innerTry::close).doesNotThrowAnyException();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(2);
            assertThat(lockRegistry.isLocked(key)).isTrue();

            innerAcquire.close();
            assertThatCode(innerAcquire::close).doesNotThrowAnyException();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isLocked(key)).isTrue();
            assertThat(lockRegistry.isHeldByCurrentThread(key)).isTrue();
        }

        assertThat(lockRegistry.reservationCount(key)).isZero();
        assertThat(lockRegistry.isLocked(key)).isFalse();
        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("closing lease multiple times is idempotent")
    void closingLeaseMultipleTimesIsIdempotent() {
        String key = "room-1";
        LockLease lease = lockRegistry.acquire(key);
        assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);

        lease.close();
        assertThat(lockRegistry.reservationCount(key)).isZero();
        assertThat(lockRegistry.activeKeyCount()).isZero();

        assertThatCode(lease::close).doesNotThrowAnyException();
        assertThat(lockRegistry.reservationCount(key)).isZero();
        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("wrong-thread close throws IllegalMonitorStateException and preserves lock and reservation")
    void wrongThreadClosePreservesLockAndReservation() throws Exception {
        String key = "room-1";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (LockLease lease = lockRegistry.acquire(key)) {
            pool.submit(() -> {
                assertThatThrownBy(lease::close)
                        .isInstanceOf(IllegalMonitorStateException.class);
                try (LockLease competingLease = lockRegistry.tryAcquire(key)) {
                    assertThat(competingLease).isNull();
                }
            }).get(2, TimeUnit.SECONDS);

            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
            assertThat(lockRegistry.isHeldByCurrentThread(key)).isTrue();
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(lockRegistry.activeKeyCount()).isZero();
    }

    @Test
    @DisplayName("isHeldByCurrentThread reflects only the owning thread")
    void isHeldByCurrentThreadReflectsOnlyOwningThread() throws Exception {
        String key = "room-1";
        assertThat(lockRegistry.isHeldByCurrentThread(key)).isFalse();
        assertThat(lockRegistry.isHeldByCurrentThread(null)).isFalse();

        try (LockLease _ = lockRegistry.acquire(key)) {
            assertThat(lockRegistry.isHeldByCurrentThread(key)).isTrue();

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> otherThreadHeld = pool.submit(() -> lockRegistry.isHeldByCurrentThread(key));
                assertThat(otherThreadHeld.get(2, TimeUnit.SECONDS)).isFalse();
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        assertThat(lockRegistry.isHeldByCurrentThread(key)).isFalse();
    }

    @Test
    @DisplayName("queued waiter transitions reservation count 2 -> 1 -> 0 across handoff")
    void queuedWaiterHandoffTransitionsReservationCount() throws Exception {
        String key = "handoff-key";
        CountDownLatch waiterQueued = new CountDownLatch(1);
        CountDownLatch waiterAcquired = new CountDownLatch(1);
        CountDownLatch releaseWaiter = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Void> waiter;
            try (LockLease _ = lockRegistry.acquire(key)) {
                assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);
                waiter = pool.submit(() -> {
                    waiterQueued.countDown();
                    try (LockLease _ = lockRegistry.acquire(key)) {
                        waiterAcquired.countDown();
                        releaseWaiter.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                });
                assertThat(waiterQueued.await(2, TimeUnit.SECONDS)).isTrue();
                while (lockRegistry.reservationCount(key) < 2) {
                    Thread.onSpinWait();
                }
                assertThat(lockRegistry.reservationCount(key)).isEqualTo(2);
            }

            assertThat(waiterAcquired.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(lockRegistry.reservationCount(key)).isEqualTo(1);

            releaseWaiter.countDown();
            waiter.get(2, TimeUnit.SECONDS);
            assertThat(lockRegistry.reservationCount(key)).isZero();
            assertThat(lockRegistry.activeKeyCount()).isZero();
        } finally {
            releaseWaiter.countDown();
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("concurrent waiters and releases maintain mutual exclusion")
    void concurrentWaitersAndReleasesMaintainMutualExclusion() throws Exception {
        String key = "concurrent-key";
        int threads = 10;
        int iterations = 100;
        AtomicInteger activeExecutions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
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
            assertThat(lockRegistry.reservationCount(key)).isZero();
            assertThat(lockRegistry.activeKeyCount()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
