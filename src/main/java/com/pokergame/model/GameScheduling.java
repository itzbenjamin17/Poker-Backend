package com.pokergame.model;

import com.pokergame.enums.ScheduledGameTask;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Owns scheduled task deadlines and monotonic task schedule counts for {@link Game}.
 */
final class GameScheduling {

    private final Map<ScheduledGameTask, Long> deadlines;
    private final Map<ScheduledGameTask, Long> timerVersions;

    /** Creates empty task scheduling state for a new game. */
    GameScheduling() {
        this.deadlines = new EnumMap<>(ScheduledGameTask.class);
        this.timerVersions = new EnumMap<>(ScheduledGameTask.class);
    }

    /**
     * Reconstructs task scheduling state from saved snapshot fields.
     *
     * @param deadlines     saved task deadlines keyed by task type
     * @param timerVersions saved task schedule counters keyed by task type
     */
    GameScheduling(
            Map<ScheduledGameTask, Long> deadlines,
            Map<ScheduledGameTask, Long> timerVersions) {
        this.deadlines = new EnumMap<>(ScheduledGameTask.class);
        if (deadlines != null) {
            this.deadlines.putAll(deadlines);
        }
        this.timerVersions = new EnumMap<>(ScheduledGameTask.class);
        if (timerVersions != null) {
            this.timerVersions.putAll(timerVersions);
        }
    }

    /**
     * Records the deadline for {@code task} and increments its schedule count so earlier timers
     * for the same task are ignored.
     *
     * @param task            delayed task type
     * @param deadlineEpochMs target execution epoch time in milliseconds
     */
    void scheduleTask(ScheduledGameTask task, long deadlineEpochMs) {
        deadlines.put(task, deadlineEpochMs);
        timerVersions.merge(task, 1L, Long::sum);
    }

    /**
     * Removes the scheduled deadline for {@code task} and increments its schedule count so any
     * in-flight timer callback is ignored.
     *
     * @param task delayed task type
     */
    void clearTask(ScheduledGameTask task) {
        deadlines.remove(task);
        timerVersions.merge(task, 1L, Long::sum);
    }

    /**
     * Clears all scheduled gameplay tasks except {@link ScheduledGameTask#CLEANUP} and increments
     * their schedule counts.
     */
    void invalidateProgressionTasks() {
        for (ScheduledGameTask task : List.copyOf(deadlines.keySet())) {
            if (task != ScheduledGameTask.CLEANUP) {
                clearTask(task);
            }
        }
    }

    /**
     * Clears every scheduled task (including {@link ScheduledGameTask#CLEANUP}) and increments
     * their schedule counts so any in-flight timer callback is ignored.
     */
    void clearAllTasks() {
        for (ScheduledGameTask task : ScheduledGameTask.values()) {
            clearTask(task);
        }
    }

    Long getDeadline(ScheduledGameTask task) {
        return deadlines.get(task);
    }

    long getTimerVersion(ScheduledGameTask task) {
        return timerVersions.getOrDefault(task, 0L);
    }

    Map<ScheduledGameTask, Long> deadlinesSnapshot() {
        return Map.copyOf(deadlines);
    }

    Map<ScheduledGameTask, Long> timerVersionsSnapshot() {
        return Map.copyOf(timerVersions);
    }

    /**
     * Validates whether a firing timer callback still matches current game state and claims
     * non-cleanup tasks for one-shot execution.
     *
     * @param pausedForDisconnectedSurvivor whether the game is paused awaiting a disconnected survivor
     * @param gameOver                      whether the game has concluded
     * @param currentHandNumber             current hand number on the game
     * @param task                          task type that fired
     * @param expectedDeadline              deadline captured when the timer was scheduled
     * @param expectedHandNumber            hand number captured when the timer was scheduled
     * @param expectedTimerVersion          schedule count captured when the timer was scheduled
     * @return {@code true} if the task was claimed for execution, {@code false} if stale,
     *         cancelled on game over, or blocked while paused for a disconnected survivor
     */
    boolean claimTask(
            boolean pausedForDisconnectedSurvivor,
            boolean gameOver,
            long currentHandNumber,
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedTimerVersion) {
        if (pausedForDisconnectedSurvivor) {
            return false;
        }
        if ((task != ScheduledGameTask.CLEANUP && currentHandNumber != expectedHandNumber)
                || getTimerVersion(task) != expectedTimerVersion) {
            return false;
        }
        if (gameOver && task != ScheduledGameTask.CLEANUP) {
            clearTask(task);
            return false;
        }
        Long currentDeadline = getDeadline(task);
        if (currentDeadline == null || currentDeadline != expectedDeadline) {
            return false;
        }
        if (task != ScheduledGameTask.CLEANUP) {
            clearTask(task);
        }
        return true;
    }

    /**
     * Returns whether the room-cleanup grace deadline has arrived or passed at {@code nowEpochMs}.
     *
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return {@code true} if a cleanup deadline is armed and expired
     */
    boolean isCleanupExpired(long nowEpochMs) {
        Long cleanupDeadline = getDeadline(ScheduledGameTask.CLEANUP);
        return cleanupDeadline != null && nowEpochMs >= cleanupDeadline;
    }
}
