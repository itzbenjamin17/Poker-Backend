package com.pokergame.model;

import com.pokergame.enums.ScheduledGameTask;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Owns scheduled task deadlines, monotonic task schedule counts, and state revision numbers for {@link Game}.
 */
final class GameScheduling {

    static final long INITIAL_HAND_REVISION = 1L;

    private final Map<ScheduledGameTask, Long> deadlines;
    private final Map<ScheduledGameTask, Long> generations;
    private long stateRevision;

    /** Creates empty task scheduling and revision state for a new game. */
    GameScheduling() {
        this.deadlines = new EnumMap<>(ScheduledGameTask.class);
        this.generations = new EnumMap<>(ScheduledGameTask.class);
        this.stateRevision = 0L;
    }

    /**
     * Reconstructs task scheduling and state revision from saved snapshot fields.
     *
     * @param deadlines     saved task deadlines keyed by task type
     * @param generations   saved task schedule counters keyed by task type
     * @param stateRevision saved public state revision counter
     */
    GameScheduling(
            Map<ScheduledGameTask, Long> deadlines,
            Map<ScheduledGameTask, Long> generations,
            long stateRevision) {
        this.deadlines = new EnumMap<>(ScheduledGameTask.class);
        if (deadlines != null) {
            this.deadlines.putAll(deadlines);
        }
        this.generations = new EnumMap<>(ScheduledGameTask.class);
        if (generations != null) {
            this.generations.putAll(generations);
        }
        this.stateRevision = stateRevision;
    }

    /**
     * Records the deadline for {@code task} and increments its schedule count so earlier timers
     * for the same task are ignored.
     *
     * @param task            delayed task type
     * @param deadlineEpochMs target execution epoch time in milliseconds
     */
    void scheduleTask(ScheduledGameTask task, long deadlineEpochMs) {
        if (task == null) {
            return;
        }
        deadlines.put(task, deadlineEpochMs);
        generations.merge(task, 1L, Long::sum);
    }

    /**
     * Removes the scheduled deadline for {@code task} and increments its schedule count so any
     * in-flight timer callback is ignored.
     *
     * @param task delayed task type
     */
    void clearTask(ScheduledGameTask task) {
        if (task == null) {
            return;
        }
        deadlines.remove(task);
        generations.merge(task, 1L, Long::sum);
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
        return task == null ? null : deadlines.get(task);
    }

    long getGeneration(ScheduledGameTask task) {
        return task == null ? 0L : generations.getOrDefault(task, 0L);
    }

    Map<ScheduledGameTask, Long> deadlinesSnapshot() {
        return Map.copyOf(deadlines);
    }

    Map<ScheduledGameTask, Long> generationsSnapshot() {
        return Map.copyOf(generations);
    }

    /**
     * Validates whether a firing timer callback still matches current game state and claims
     * non-cleanup tasks for one-shot execution.
     *
     * @param forfeitPending     whether a forfeit is currently pending
     * @param gameOver           whether the game has concluded
     * @param currentHandNumber  current hand number on the game
     * @param task               task type that fired
     * @param expectedDeadline   deadline captured when the timer was scheduled
     * @param expectedHandNumber hand number captured when the timer was scheduled
     * @param expectedGeneration schedule count captured when the timer was scheduled
     * @return {@code true} if the task was claimed for execution, {@code false} if stale,
     *         cancelled on game over, or blocked by a pending forfeit
     */
    boolean claimTask(
            boolean forfeitPending,
            boolean gameOver,
            long currentHandNumber,
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedGeneration) {
        if (forfeitPending) {
            return false;
        }
        if ((task != ScheduledGameTask.CLEANUP && currentHandNumber != expectedHandNumber)
                || getGeneration(task) != expectedGeneration) {
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

    long getStateRevision() {
        return stateRevision;
    }

    void incrementStateRevision() {
        stateRevision++;
    }

    void resetStateRevisionForNewHand() {
        stateRevision = INITIAL_HAND_REVISION;
    }
}
