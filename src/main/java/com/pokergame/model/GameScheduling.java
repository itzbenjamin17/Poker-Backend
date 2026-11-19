package com.pokergame.model;

import com.pokergame.enums.ScheduledGameTask;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Owns scheduled task run times and monotonic task schedule counts for {@link Game}.
 */
final class GameScheduling {

    private final Map<ScheduledGameTask, Long> scheduledTimes;
    private final Map<ScheduledGameTask, Long> timerVersions;

    /** Creates empty task scheduling state for a new game. */
    GameScheduling() {
        this.scheduledTimes = new EnumMap<>(ScheduledGameTask.class);
        this.timerVersions = new EnumMap<>(ScheduledGameTask.class);
    }

    /**
     * Reconstructs task scheduling state from saved snapshot fields.
     *
     * @param scheduledTimes saved task run times keyed by task type
     * @param timerVersions  saved task schedule counters keyed by task type
     */
    GameScheduling(
            Map<ScheduledGameTask, Long> scheduledTimes,
            Map<ScheduledGameTask, Long> timerVersions) {
        this.scheduledTimes = new EnumMap<>(ScheduledGameTask.class);
        if (scheduledTimes != null) {
            this.scheduledTimes.putAll(scheduledTimes);
        }
        this.timerVersions = new EnumMap<>(ScheduledGameTask.class);
        if (timerVersions != null) {
            this.timerVersions.putAll(timerVersions);
        }
    }

    /**
     * Records the run time for {@code task} and increments its schedule count so earlier timers
     * for the same task are ignored.
     *
     * @param task         delayed task type
     * @param runAtEpochMs target execution epoch time in milliseconds
     */
    void scheduleTask(ScheduledGameTask task, long runAtEpochMs) {
        scheduledTimes.put(task, runAtEpochMs);
        timerVersions.merge(task, 1L, Long::sum);
    }

    /**
     * Removes the scheduled run time for {@code task} and increments its schedule count so any
     * in-flight timer callback is ignored.
     *
     * @param task delayed task type
     */
    void clearTask(ScheduledGameTask task) {
        scheduledTimes.remove(task);
        timerVersions.merge(task, 1L, Long::sum);
    }

    /**
     * Clears all scheduled gameplay tasks except {@link ScheduledGameTask#CLEANUP} and increments
     * their schedule counts.
     */
    void invalidateProgressionTasks() {
        for (ScheduledGameTask task : List.copyOf(scheduledTimes.keySet())) {
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

    /**
     * Checks whether a scheduled timer should still run, and clears one-time tasks (everything
     * except {@link ScheduledGameTask#CLEANUP}) so they only run once.
     *
     * @param pausedForDisconnectedSurvivor whether the game is paused awaiting a disconnected survivor
     * @param currentHandNumber             current hand number on the game
     * @param task                          task type that fired
     * @param expectedRunAtEpochMs          scheduled run time captured when the timer was scheduled
     * @param expectedHandNumber            hand number captured when the timer was scheduled
     * @param expectedTimerVersion          schedule count captured when the timer was scheduled
     * @return {@code true} if the task is still current and can start, {@code false} if stale or
     *         blocked while paused for a disconnected survivor
     */
    boolean startScheduledTaskIfCurrent(
            boolean pausedForDisconnectedSurvivor,
            long currentHandNumber,
            ScheduledGameTask task,
            long expectedRunAtEpochMs,
            long expectedHandNumber,
            long expectedTimerVersion) {
        if (pausedForDisconnectedSurvivor) {
            return false;
        }
        if ((task != ScheduledGameTask.CLEANUP && currentHandNumber != expectedHandNumber)
                || getTimerVersion(task) != expectedTimerVersion) {
            return false;
        }
        Long currentRunAt = getScheduledTime(task);
        if (currentRunAt == null || currentRunAt != expectedRunAtEpochMs) {
            return false;
        }
        if (task != ScheduledGameTask.CLEANUP) {
            clearTask(task);
        }
        return true;
    }

    /**
     * Returns whether the scheduled room-cleanup time has arrived or passed at {@code nowEpochMs}.
     *
     * @param nowEpochMs current epoch timestamp in milliseconds
     * @return {@code true} if cleanup is scheduled and due
     */
    boolean isCleanupExpired(long nowEpochMs) {
        Long cleanupRunAt = getScheduledTime(ScheduledGameTask.CLEANUP);
        return cleanupRunAt != null && nowEpochMs >= cleanupRunAt;
    }

    Long getScheduledTime(ScheduledGameTask task) {
        return scheduledTimes.get(task);
    }

    long getTimerVersion(ScheduledGameTask task) {
        return timerVersions.getOrDefault(task, 0L);
    }

    Map<ScheduledGameTask, Long> scheduledTimesSnapshot() {
        return Map.copyOf(scheduledTimes);
    }

    Map<ScheduledGameTask, Long> timerVersionsSnapshot() {
        return Map.copyOf(timerVersions);
    }
}
