package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages in-memory scheduler registrations for task deadlines stored on a game or its room.
 * Each game and task type has at most one current registration. Recovery can rebuild
 * registrations from persisted deadlines, and overdue work is scheduled immediately.
 *
 * <p>Registration checks the deadline, hand number, and task generation under the
 * room coordination lease. The callback must revalidate those values at the durable mutation seam
 * because a cancelled or replaced callback may already be running.
 */
final class GameTimerRegistry {
    private static final Logger logger = LoggerFactory.getLogger(GameTimerRegistry.class);

    private final TaskScheduler taskScheduler;

    GameTimerRegistry(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /** Executes scheduled work using the values captured at registration. */
    @FunctionalInterface
    interface ScheduledTaskExecutor {
        void execute(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation);
    }

    private final Map<RuntimeTaskKey, RuntimeTask> runtimeTasks = new ConcurrentHashMap<>();

    /** A null task identifies the room-backed inactivity timer. */
    private record RuntimeTaskKey(String gameId, ScheduledGameTask task) { }
    /** Identifies a scheduled task for a specific hand, generation, and epoch-millisecond deadline. */
    record TaskIntent(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation) { }

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

    /** Captures the task identity while the caller holds the room coordination lease. */
    static TaskIntent captureIntent(String gameId, Game game, ScheduledGameTask task, long deadline) {
        return new TaskIntent(gameId, task, deadline, game.getHandNumber(), game.getScheduledTaskGeneration(task));
    }

    /** Captures all currently scheduled task intents while the caller holds the room coordination lease. */
    static List<TaskIntent> captureAllIntents(String gameId, Game game) {
        return game.getScheduledTaskDeadlinesSnapshot().entrySet().stream()
                .map(entry -> captureIntent(gameId, game, entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Registers current task intent and cancels any previous registration for the same game and task type.
     *
     * @param intent task identity captured from the game
     * @param game current game, or {@code null} if it has been removed
     * @param callback executor that revalidates the task before mutating the game
     */
    void schedule(TaskIntent intent, Game game, ScheduledTaskExecutor callback) {
        String gameId = intent.gameId();
        ScheduledGameTask task = intent.task();
        long deadline = intent.deadline();
        if (game == null) return;
        long delay = Math.max(0, deadline - System.currentTimeMillis());
        logger.debug("Scheduling runtime task {} for game {} with delayMs={} deadline={}", task, gameId, delay, deadline);
        RuntimeTaskKey key = new RuntimeTaskKey(gameId, task);
        if (game.getHandNumber() != intent.handNumber()
                || game.getScheduledTaskGeneration(task) != intent.generation()
                || !Objects.equals(game.getScheduledTaskDeadline(task), deadline)) return;
        register(key, Instant.now().plusMillis(delay),
                () -> callback.execute(gameId, task, deadline, intent.handNumber(), intent.generation()));
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
