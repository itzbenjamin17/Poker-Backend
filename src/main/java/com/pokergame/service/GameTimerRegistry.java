package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages in-memory scheduler registrations for task deadlines stored on a game or its room.
 * Each game and task type has at most one current registration. Recovery can rebuild
 * registrations from persisted deadlines, and overdue work is scheduled immediately.
 *
 * <p>The task scheduler validates durable intent before registering a future. The callback
 * checks its registration identity because a cancellation or replacement may race with submission.
 */
final class GameTimerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(GameTimerRegistry.class);

    private final TaskScheduler taskScheduler;

    GameTimerRegistry(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    private final Map<RuntimeTaskKey, RuntimeTask> runtimeTasks = new ConcurrentHashMap<>();

    /** A null task identifies the room-backed inactivity timer. */
    private record RuntimeTaskKey(String gameId, ScheduledGameTask task) { }

    /** Tracks completion or cancellation that can occur before the scheduler returns the future. */
    private static final class RuntimeTask {
        private volatile ScheduledFuture<?> future;
        private volatile boolean completed;

        void attach(ScheduledFuture<?> value) {
            future = value;
            if (completed && value != null) value.cancel(false);
        }

        void complete() {
            completed = true;
        }

        void cancel() {
            completed = true;
            ScheduledFuture<?> value = future;
            if (value != null) value.cancel(false);
        }
    }

    /** Registers the validated task's process-local future. */
    void schedule(String gameId, ScheduledGameTask task, long deadline, Runnable callback) {
        long delay = Math.max(0, deadline - System.currentTimeMillis());
        logger.debug("Scheduling runtime task {} for game {} with delayMs={} deadline={}", task, gameId, delay, deadline);
        register(new RuntimeTaskKey(gameId, task), Instant.now().plusMillis(delay), callback);
    }

    /** Registers room-backed inactivity work using the remaining time on the authoritative clock. */
    void scheduleInactivity(String gameId, Long deadline, long now, Runnable callback) {
        if (gameId == null || taskScheduler == null || deadline == null || deadline <= 0) return;
        long delay = Math.max(0, deadline - now);
        register(new RuntimeTaskKey(gameId, null),
                Instant.ofEpochMilli(System.currentTimeMillis() + delay), callback);
    }

    private void register(RuntimeTaskKey key, Instant executionTime, Runnable callback) {
        RuntimeTask registration = new RuntimeTask();
        RuntimeTask replaced = runtimeTasks.put(key, registration);
        if (replaced != null) replaced.cancel();
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                // A cancellation or replacement can win before the scheduler returns its future.
                if (runtimeTasks.get(key) != registration) return;
                try {
                    callback.run();
                } catch (Exception e) {
                    logger.error("Scheduled task {} failed for game {}",
                            key.task() == null ? "INACTIVITY" : key.task(), key.gameId(), e);
                } finally {
                    registration.complete();
                    runtimeTasks.remove(key, registration);
                }
            }, executionTime);
            registration.attach(future);
            if (future == null) {
                registration.complete();
                runtimeTasks.remove(key, registration);
            }
        } catch (RuntimeException error) {
            registration.complete();
            runtimeTasks.remove(key, registration);
            throw error;
        }
    }

    /** Removes and cancels a single runtime registration without interrupting running work. */
    void cancelTask(String gameId, ScheduledGameTask task) {
        RuntimeTask registration = runtimeTasks.remove(new RuntimeTaskKey(gameId, task));
        if (registration != null) registration.cancel();
    }

    /** Cancels the room-backed inactivity registration. */
    void cancelInactivity(String gameId) {
        if (gameId != null) cancelTask(gameId, null);
    }

    /** Cancels work tied to the previous hand while retaining inactivity and terminal timers. */
    void cancelProgressionForGame(String gameId) {
        cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
        cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
        cancelTask(gameId, ScheduledGameTask.READY_OPEN);
        cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
        cancelTask(gameId, ScheduledGameTask.NEW_HAND);
    }

    /** Cancels a game's runtime registrations, optionally retaining its cleanup registration. */
    void cancelAllForGame(String gameId, boolean keepCleanup) {
        runtimeTasks.forEach((key, registration) -> {
            if (key.gameId().equals(gameId)
                    && (!keepCleanup || key.task() != ScheduledGameTask.CLEANUP)
                    && runtimeTasks.remove(key, registration)) registration.cancel();
        });
    }

    /** Returns the number of tracked game tasks, excluding room-backed inactivity. */
    int countForGame(String gameId) {
        return (int) runtimeTasks.keySet().stream().filter(k -> k.task() != null && k.gameId().equals(gameId)).count();
    }
}
