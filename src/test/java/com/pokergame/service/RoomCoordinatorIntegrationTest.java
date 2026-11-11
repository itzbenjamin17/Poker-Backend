package com.pokergame.service;

import com.pokergame.util.LockLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

import static com.pokergame.util.ConcurrentTestSupport.stopExecutor;
import static org.assertj.core.api.Assertions.assertThat;

class RoomCoordinatorIntegrationTest {

    private RoomCoordinator roomCoordinator;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        roomCoordinator = new RoomCoordinator();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            stopExecutor(executor);
        }
    }

    @Test
    @DisplayName("same-room contention strictly serializes operations using barriers and latches")
    void sameRoomContentionSerializesOperationsBounded() throws Exception {
        String roomId = "room-contended";
        int threadCount = 8;
        int iterationsPerThread = 25;

        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger activeExecutions = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);
        AtomicInteger totalExecutions = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                for (int iter = 0; iter < iterationsPerThread; iter++) {
                    roomCoordinator.coordinate(roomId, () -> {
                        int current = activeExecutions.incrementAndGet();
                        maxObservedConcurrency.updateAndGet(max -> Math.max(max, current));

                        // Invariant check: exactly one thread must be executing inside the room lease
                        assertThat(current).isEqualTo(1);
                        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isTrue();

                        totalExecutions.incrementAndGet();
                        activeExecutions.decrementAndGet();
                    });
                }
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        assertThat(totalExecutions.get()).isEqualTo(threadCount * iterationsPerThread);
        assertThat(maxObservedConcurrency.get()).isEqualTo(1);
        assertThat(roomCoordinator.isHeldByCurrentThread(roomId)).isFalse();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomId)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("room independence: an operation blocked on room A does not block or delay operations on room B")
    void roomIndependenceBlockedRoomADoesNotDelayRoomB() throws Exception {
        String roomA = "room-A";
        String roomB = "room-B";

        CountDownLatch roomAHeldLatch = new CountDownLatch(1);
        CountDownLatch roomAReleaseLatch = new CountDownLatch(1);
        AtomicBoolean roomAFinished = new AtomicBoolean(false);

        // Task A enters room A coordination and blocks until roomAReleaseLatch is released
        Future<Void> taskA = executor.submit(() -> {
            roomCoordinator.coordinate(roomA, () -> {
                roomAHeldLatch.countDown();
                try {
                    boolean released = roomAReleaseLatch.await(5, TimeUnit.SECONDS);
                    assertThat(released).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
            roomAFinished.set(true);
            return null;
        });

        // Wait until task A actually holds room A's lease
        assertThat(roomAHeldLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomA)).isGreaterThanOrEqualTo(1);
        assertThat(roomAFinished.get()).isFalse();

        // While Room A is still deliberately blocked, execute work on Room B
        Future<String> taskB = executor.submit(() -> roomCoordinator.coordinate(roomB, () -> {
            roomCoordinator.assertHeldByCurrentThread(roomB);
            return "room-B-done";
        }));

        // Room B must finish immediately within bounded time, despite Room A being blocked
        String resultB = taskB.get(2, TimeUnit.SECONDS);
        assertThat(resultB).isEqualTo("room-B-done");
        assertThat(roomAFinished.get()).isFalse(); // Room A is STILL blocked!

        // Now release Room A
        roomAReleaseLatch.countDown();
        taskA.get(2, TimeUnit.SECONDS);
        assertThat(roomAFinished.get()).isTrue();

        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomA)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().reservationCount(roomB)).isEqualTo(0);
        assertThat(roomCoordinator.getLockRegistry().activeKeyCount()).isEqualTo(0);
    }
}
