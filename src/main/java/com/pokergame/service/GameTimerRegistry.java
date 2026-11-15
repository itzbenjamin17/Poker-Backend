package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Tracks active {@link TaskScheduler} futures for each game's timers so at most one timer of
 * each kind is scheduled per game at a time and stale timers can be cancelled safely.
 */
@Component
class GameTimerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(GameTimerRegistry.class);
    private final TaskScheduler taskScheduler;
    private final Clock clock;

    private final Map<RuntimeTaskKey, RuntimeTask> runtimeTasks = new ConcurrentHashMap<>();

    GameTimerRegistry(@Qualifier("taskScheduler") TaskScheduler taskScheduler, Clock clock) {
        this.taskScheduler = taskScheduler;
        this.clock = clock;
    }

    /**
     * Schedules (or replaces) a named game timer to run at {@code deadline}.
     *
     * @param gameId   the room/game ID
     * @param task     the timer type to schedule
     * @param deadline target execution epoch time in milliseconds
     * @param callback action to execute when the timer fires
     */
    void schedule(String gameId, ScheduledGameTask task, long deadline, Runnable callback) {
        long now = clock.millis();
        long delay = Math.max(0, deadline - now);
        logger.debug("Scheduling timer {} for game {} with delayMs={} deadline={}", task, gameId, delay, deadline);
        register(new RuntimeTaskKey(gameId, task), Instant.ofEpochMilli(now + delay), callback);
    }

    /**
     * Schedules (or replaces) the room-backed game inactivity timer.
     *
     * @param gameId   the room/game ID
     * @param deadline target expiration epoch time in milliseconds
     * @param now      current epoch time in milliseconds used to compute remaining delay
     * @param callback action to execute if inactivity expires
     */
    void scheduleInactivity(String gameId, Long deadline, long now, Runnable callback) {
        if (gameId == null || deadline == null) {
            return;
        }
        long delay = Math.max(0, deadline - now);
        register(new RuntimeTaskKey(gameId, null), Instant.ofEpochMilli(clock.millis() + delay), callback);
    }

    /**
     * Cancels a single scheduled game timer without interrupting already running work.
     *
     * @param gameId the room/game ID
     * @param task   the timer type to cancel, or {@code null} for the inactivity timer
     */
    void cancelTask(String gameId, ScheduledGameTask task) {
        RuntimeTask registration = runtimeTasks.remove(new RuntimeTaskKey(gameId, task));
        if (registration != null) {
            registration.cancel();
        }
    }

    /**
     * Cancels the inactivity timer for {@code gameId}.
     *
     * @param gameId the room/game ID
     */
    void cancelInactivity(String gameId) {
        if (gameId != null) {
            cancelTask(gameId, null);
        }
    }

    /**
     * Cancels all hand-progression timers for a game while keeping inactivity and cleanup timers.
     *
     * @param gameId the room/game ID
     */
    void cancelProgressionForGame(String gameId) {
        cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
        cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
        cancelTask(gameId, ScheduledGameTask.READY_OPEN);
        cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
        cancelTask(gameId, ScheduledGameTask.NEW_HAND);
    }

    /**
     * Cancels timers for a game, optionally retaining the room cleanup timer.
     *
     * @param gameId      the room/game ID
     * @param keepCleanup {@code true} to keep {@link ScheduledGameTask#CLEANUP} scheduled
     */
    void cancelAllForGame(String gameId, boolean keepCleanup) {
        runtimeTasks.forEach((key, registration) -> {
            if (key.gameId().equals(gameId)
                    && (!keepCleanup || key.task() != ScheduledGameTask.CLEANUP)
                    && runtimeTasks.remove(key, registration)) {
                registration.cancel();
            }
        });
    }

    private void register(RuntimeTaskKey key, Instant executionTime, Runnable callback) {
        RuntimeTask registration = new RuntimeTask();
        RuntimeTask replaced = runtimeTasks.put(key, registration);
        if (replaced != null) {
            replaced.cancel();
        }
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(() -> {
                // Ignore callbacks whose registration was replaced or cancelled before firing.
                if (runtimeTasks.get(key) != registration) {
                    return;
                }
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

    /** Key identifying one timer slot for a room; a {@code null} task denotes inactivity. */
    private record RuntimeTaskKey(String gameId, ScheduledGameTask task) {
    }

    /** Tracks completion or cancellation that can occur before the scheduler returns its future. */
    private static final class RuntimeTask {
        private volatile ScheduledFuture<?> future;
        private volatile boolean completed;

        void attach(ScheduledFuture<?> value) {
            future = value;
            if (completed && value != null) {
                value.cancel(false);
            }
        }

        void complete() {
            completed = true;
        }

        void cancel() {
            completed = true;
            ScheduledFuture<?> value = future;
            if (value != null) {
                value.cancel(false);
            }
        }
    }
}
