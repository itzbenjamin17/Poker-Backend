package com.pokergame.util;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded coordination for tests that deliberately hold a production monitor. */
public final class ConcurrentTestSupport {
    private ConcurrentTestSupport() {}

    /** Waits until the worker has reached the specified monitor, rather than merely started. */
    public static void awaitBlockedOn(AtomicReference<Thread> worker, Object monitor) {
        await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(5)).until(() -> {
            Thread thread = worker.get();
            if (thread == null) {
                return false;
            }
            var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
            if (info == null) {
                return false;
            }
            if (info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockInfo() != null
                    && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)
                    && info.getLockInfo().getClassName().equals(monitor.getClass().getName())) {
                return true;
            }
            if ((info.getThreadState() == Thread.State.WAITING || info.getThreadState() == Thread.State.TIMED_WAITING)
                    && info.getLockInfo() != null
                    && (info.getLockInfo().getClassName().contains("Sync")
                            || info.getLockInfo().getClassName().contains("ReentrantLock"))) {
                return true;
            }
            return false;
        });
    }

    /** Interrupts outstanding workers and checks that the pool has released its threads. */
    public static void stopExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
    }
}
