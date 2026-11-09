package com.pokergame.model;

import com.pokergame.enums.ScheduledGameTask;

import java.util.List;
import java.util.Map;

/**
 * Pure helper and collaborator logic for scheduled timer tasks, task generation tokens,
 * and state revision numbering.
 * Package-private collaborator extracted from {@link Game}.
 */
final class GameScheduling {

    /** Default initial state revision when a new numbered hand begins. */
    static final long INITIAL_HAND_REVISION = 1L;

    private GameScheduling() {
        // Utility collaborator class, prevent instantiation
    }

    /**
     * Records delayed work as domain state before a runtime timer is created, closing
     * the crash window between a committed game transition and scheduler submission.
     * Updates the deadline and increments the monotonic task generation.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param scheduledTaskGenerations live map of task generations
     * @param task delayed work type
     * @param deadlineEpochMs absolute execution deadline
     */
    static void scheduleTask(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task,
            long deadlineEpochMs) {
        if (scheduledTaskDeadlines == null || scheduledTaskGenerations == null || task == null) {
            return;
        }
        scheduledTaskDeadlines.put(task, deadlineEpochMs);
        scheduledTaskGenerations.merge(task, 1L, Long::sum);
    }

    /**
     * Clears durable intent in the same mutation that performs the work, making
     * overdue recovery execution idempotent. Increments the monotonic task generation.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param scheduledTaskGenerations live map of task generations
     * @param task completed delayed work type
     */
    static void clearTask(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task) {
        if (scheduledTaskDeadlines == null || scheduledTaskGenerations == null || task == null) {
            return;
        }
        scheduledTaskDeadlines.remove(task);
        scheduledTaskGenerations.merge(task, 1L, Long::sum);
    }

    /**
     * Alias for {@link #clearTask(Map, Map, ScheduledGameTask)}.
     */
    static void clearScheduledTask(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task) {
        clearTask(scheduledTaskDeadlines, scheduledTaskGenerations, task);
    }

    /**
     * Invalidates all durable progression tasks except terminal cleanup.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param scheduledTaskGenerations live map of task generations
     */
    static void invalidateProgressionTasks(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            Map<ScheduledGameTask, Long> scheduledTaskGenerations) {
        if (scheduledTaskDeadlines == null || scheduledTaskGenerations == null) {
            return;
        }
        for (ScheduledGameTask task : List.copyOf(scheduledTaskDeadlines.keySet())) {
            if (task != ScheduledGameTask.CLEANUP) {
                clearTask(scheduledTaskDeadlines, scheduledTaskGenerations, task);
            }
        }
    }

    /**
     * Returns the scheduled deadline for a task, or null if unscheduled.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param task delayed work type
     * @return absolute deadline in epoch milliseconds, or null
     */
    static Long getDeadline(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            ScheduledGameTask task) {
        if (scheduledTaskDeadlines == null || task == null) {
            return null;
        }
        return scheduledTaskDeadlines.get(task);
    }

    /**
     * Alias for {@link #getDeadline(Map, ScheduledGameTask)}.
     */
    static Long getScheduledTaskDeadline(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            ScheduledGameTask task) {
        return getDeadline(scheduledTaskDeadlines, task);
    }

    /**
     * Alias for {@link #getDeadline(Map, ScheduledGameTask)}.
     */
    static Long getTaskDeadline(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            ScheduledGameTask task) {
        return getDeadline(scheduledTaskDeadlines, task);
    }

    /**
     * Returns the generation token for a delayed task, or 0 if unrecorded.
     *
     * @param scheduledTaskGenerations live map of task generations
     * @param task delayed work type
     * @return current monotonic generation token
     */
    static long getGeneration(
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task) {
        if (scheduledTaskGenerations == null || task == null) {
            return 0L;
        }
        return scheduledTaskGenerations.getOrDefault(task, 0L);
    }

    /**
     * Alias for {@link #getGeneration(Map, ScheduledGameTask)}.
     */
    static long getScheduledTaskGeneration(
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task) {
        return getGeneration(scheduledTaskGenerations, task);
    }

    /**
     * Alias for {@link #getGeneration(Map, ScheduledGameTask)}.
     */
    static long getTaskGeneration(
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            ScheduledGameTask task) {
        return getGeneration(scheduledTaskGenerations, task);
    }

    /**
     * Copies scheduled deadlines for snapshot capture without exposing the live map.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @return immutable task-to-deadline map
     */
    static Map<ScheduledGameTask, Long> getScheduledTaskDeadlinesSnapshot(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines) {
        if (scheduledTaskDeadlines == null) {
            return Map.of();
        }
        return Map.copyOf(scheduledTaskDeadlines);
    }

    /**
     * Alias for {@link #getScheduledTaskDeadlinesSnapshot(Map)}.
     */
    static Map<ScheduledGameTask, Long> copyDeadlines(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines) {
        return getScheduledTaskDeadlinesSnapshot(scheduledTaskDeadlines);
    }

    /**
     * Copies task generations for snapshot capture without exposing the live map.
     *
     * @param scheduledTaskGenerations live map of task generations
     * @return immutable task-to-generation map
     */
    static Map<ScheduledGameTask, Long> getScheduledTaskGenerationsSnapshot(
            Map<ScheduledGameTask, Long> scheduledTaskGenerations) {
        if (scheduledTaskGenerations == null) {
            return Map.of();
        }
        return Map.copyOf(scheduledTaskGenerations);
    }

    /**
     * Alias for {@link #getScheduledTaskGenerationsSnapshot(Map)}.
     */
    static Map<ScheduledGameTask, Long> copyGenerations(
            Map<ScheduledGameTask, Long> scheduledTaskGenerations) {
        return getScheduledTaskGenerationsSnapshot(scheduledTaskGenerations);
    }

    /**
     * Computes the next state revision by incrementing monotonically.
     *
     * @param currentRevision current revision counter
     * @return incremented revision
     */
    static long incrementStateRevision(long currentRevision) {
        return currentRevision + 1L;
    }

    /**
     * Returns the baseline state revision when a new hand begins.
     *
     * @return initial hand revision (1L)
     */
    static long resetStateRevisionForNewHand() {
        return INITIAL_HAND_REVISION;
    }

    /**
     * Evaluates whether a scheduled task callback can proceed with execution against the
     * current domain state. If eligible, non-cleanup tasks are cleared from domain intent
     * to ensure idempotent execution.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param scheduledTaskGenerations live map of task generations
     * @param forfeitPending whether a forfeit is currently pending
     * @param gameOver whether the game is over
     * @param currentHandNumber current game hand number
     * @param task delayed work type
     * @param expectedDeadline deadline captured when scheduled
     * @param expectedHandNumber hand number captured when scheduled
     * @param expectedGeneration generation token captured when scheduled
     * @return claim outcome (EXECUTE, CANCEL_GAME_OVER, or IGNORE)
     */
    static Game.TaskExecutionClaim claimTaskExecution(
            Map<ScheduledGameTask, Long> scheduledTaskDeadlines,
            Map<ScheduledGameTask, Long> scheduledTaskGenerations,
            boolean forfeitPending,
            boolean gameOver,
            long currentHandNumber,
            ScheduledGameTask task,
            long expectedDeadline,
            long expectedHandNumber,
            long expectedGeneration) {
        if (forfeitPending) {
            return Game.TaskExecutionClaim.IGNORE;
        }
        if (currentHandNumber != expectedHandNumber
                || getGeneration(scheduledTaskGenerations, task) != expectedGeneration) {
            return Game.TaskExecutionClaim.IGNORE;
        }
        if (gameOver && task != ScheduledGameTask.CLEANUP) {
            clearTask(scheduledTaskDeadlines, scheduledTaskGenerations, task);
            return Game.TaskExecutionClaim.CANCEL_GAME_OVER;
        }
        Long currentDeadline = getDeadline(scheduledTaskDeadlines, task);
        if (currentDeadline == null || currentDeadline != expectedDeadline) {
            return Game.TaskExecutionClaim.IGNORE;
        }
        if (task != ScheduledGameTask.CLEANUP) {
            clearTask(scheduledTaskDeadlines, scheduledTaskGenerations, task);
        }
        return Game.TaskExecutionClaim.EXECUTE;
    }

    /**
     * Determines if a game has expired based on its cleanup task deadline.
     *
     * @param scheduledTaskDeadlines live map of task deadlines
     * @param nowEpochMs current epoch millisecond timestamp
     * @return true if cleanup deadline exists and current time is at or past it
     */
    static boolean isExpired(Map<ScheduledGameTask, Long> scheduledTaskDeadlines, long nowEpochMs) {
        Long cleanupDeadline = getDeadline(scheduledTaskDeadlines, ScheduledGameTask.CLEANUP);
        return cleanupDeadline != null && nowEpochMs >= cleanupDeadline;
    }
}
