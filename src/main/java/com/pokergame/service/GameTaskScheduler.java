package com.pokergame.service;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.PostSaveActions;
import com.pokergame.util.RoomCoordinator;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Records scheduled timers on {@link Game} before saving, registers the runtime timers after
 * saving completes, and verifies when a timer fires that it has not been cancelled or replaced.
 */
@Component
class GameTaskScheduler {
    private static final Logger logger = LoggerFactory.getLogger(GameTaskScheduler.class);
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final RoomService roomService;
    private final RoomCoordinator roomCoordinator;
    private final Clock clock;
    private final GameTimingProperties timings;
    private final ObjectProvider<GameLifecycleService> gameLifecycleProvider;

    GameTaskScheduler(
            ActiveGameRegistry activeGames,
            GameTimerRegistry timerRegistry,
            RoomService roomService,
            RoomCoordinator roomCoordinator,
            Clock clock,
            GameTimingProperties timings,
            ObjectProvider<GameLifecycleService> gameLifecycleProvider) {
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.roomService = roomService;
        this.roomCoordinator = roomCoordinator;
        this.clock = clock;
        this.timings = timings;
        this.gameLifecycleProvider = gameLifecycleProvider;
    }

    /**
     * Captures a timer descriptor for {@code task} tied to {@code game}'s current hand and
     * schedule count so callbacks from cancelled or replaced timers are ignored.
     *
     * @param game         the active game instance
     * @param task         the timer type
     * @param runAtEpochMs target execution epoch time in milliseconds
     * @return an immutable timer descriptor
     */
    static ScheduledTimer createTimer(Game game, ScheduledGameTask task, long runAtEpochMs) {
        return new ScheduledTimer(
                game.getGameId(), task, runAtEpochMs, game.getHandNumber(), game.getTimerVersion(task));
    }

    /**
     * Lists all active timer descriptors currently recorded on {@code game}.
     *
     * @param game the active game instance
     * @return immutable descriptors for every timer stored on {@code game}
     */
    static List<ScheduledTimer> listActiveTimers(Game game) {
        return game.toSnapshot().scheduledTimes().entrySet().stream()
                .map(entry -> createTimer(game, entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Cancels {@code task} on {@code game}: clears its recorded deadline (which also makes any
     * callback already in flight stale) and cancels its runtime timer. Does nothing for a task
     * that is not scheduled.
     *
     * @param game the active game instance
     * @param task the timer type to cancel
     */
    void cancelTask(Game game, ScheduledGameTask task) {
        game.clearScheduledTask(task);
        timerRegistry.cancelTask(game.getGameId(), task);
    }

    /**
     * Schedules the post-showdown delay before opening the next-hand ready gate, returning
     * {@code true} when readiness should open immediately ({@code delayMs == 0}).
     *
     * @param gameId  the room/game ID
     * @param delayMs delay before opening the ready gate, in milliseconds ({@code >= 0})
     * @return {@code true} if the ready gate should open immediately, {@code false} if deferred
     * @throws IllegalArgumentException if {@code delayMs} is negative
     */
    boolean scheduleReadyCountdownOpen(String gameId, long delayMs) {
        if (requireNonNegativeDelay(delayMs) == 0) {
            return true;
        }
        scheduleTask(gameId, ScheduledGameTask.READY_OPEN, clock.millis() + delayMs);
        return false;
    }

    /**
     * Schedules final game and room cleanup after the configured post-game delay.
     *
     * @param gameId  the room/game ID
     * @param delayMs delay before deleting game and room state, in milliseconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code delayMs} is negative
     */
    void scheduleGameCleanup(String gameId, long delayMs) {
        scheduleTask(gameId, ScheduledGameTask.CLEANUP, clock.millis() + requireNonNegativeDelay(delayMs));
    }

    /**
     * Schedules final game completion after displaying the final showdown board.
     *
     * @param gameId  the room/game ID
     * @param delayMs display delay in milliseconds ({@code >= 0})
     * @throws IllegalArgumentException if {@code delayMs} is negative
     */
    void scheduleGameEnd(String gameId, long delayMs) {
        scheduleTask(gameId, ScheduledGameTask.GAME_END, clock.millis() + requireNonNegativeDelay(delayMs));
    }

    /**
     * Schedules the next all-in auto-advance step.
     *
     * @param gameId the room/game ID
     */
    void scheduleAutoAdvance(String gameId) {
        scheduleTask(gameId, ScheduledGameTask.AUTO_ADVANCE, clock.millis() + timings.autoAdvanceStepDelayMs());
    }

    /**
     * Schedules the current player's turn timeout timer.
     *
     * @param gameId the room/game ID
     */
    void scheduleTurnTimeout(String gameId) {
        scheduleTask(gameId, ScheduledGameTask.TURN_TIMEOUT, clock.millis() + timings.turnAllowanceMs());
    }

    /**
     * Registers the room's inactivity timer for {@code deadlineEpochMs}.
     *
     * @param gameId          the room/game ID
     * @param deadlineEpochMs target inactivity expiration epoch time in milliseconds
     */
    void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        timerRegistry.scheduleInactivity(gameId, deadlineEpochMs, clock.millis(),
                () -> gameLifecycleProvider.getObject().abandonGameIfInactive(gameId, deadlineEpochMs));
    }

    /**
     * Extends {@code game}'s inactivity deadline by the configured allowance and reschedules its
     * timer if the game has not yet finished.
     *
     * @param game the active game instance
     */
    void resetGameInactivity(Game game) {
        if (game.isGameOver()) {
            return;
        }
        String gameId = game.getGameId();
        long now = clock.millis();
        Room room = roomService.getRoom(gameId);
        if (room != null) {
            room.extendGameInactivityDeadline(now, timings.gameInactivityAllowanceMs());
            long newDeadline = room.getInactivityDeadlineEpochMs();
            scheduleGameInactivity(gameId, newDeadline);
            logger.debug("Active-game inactivity deadline reset for game {} to {}", gameId, newDeadline);
        }
    }

    /**
     * Queues runtime timer registration to run after the current state save finishes.
     *
     * @param timer the timer descriptor captured from the saved game state
     */
    void registerTimerAfterSave(ScheduledTimer timer) {
        PostSaveActions.runAfterSave(() -> registerTimer(timer));
    }

    /**
     * Registers a runtime timer immediately if {@code timer} still matches the active game state.
     *
     * @param timer the timer descriptor to register
     */
    void registerTimer(ScheduledTimer timer) {
        Game game = activeGames.get(timer.gameId());
        if (!matches(game, timer.task(), timer.runAtEpochMs(), timer.handNumber(), timer.timerVersion())) {
            return;
        }
        timerRegistry.schedule(timer.gameId(), timer.task(), timer.runAtEpochMs(),
                () -> gameLifecycleProvider.getObject().executeScheduledTask(
                        timer.gameId(), timer.task(), timer.runAtEpochMs(), timer.handNumber(), timer.timerVersion()));
    }

    /**
     * Claims a firing timer for execution if it still matches {@code game}'s active hand,
     * schedule count, and run time, cancelling any non-cleanup future once the game is over.
     *
     * @param game         the current game instance, or {@code null} if already removed
     * @param task         the timer type that fired
     * @param runAtEpochMs the scheduled run time captured when the timer was scheduled
     * @param handNumber   the hand number captured when the timer was scheduled
     * @param timerVersion the schedule count captured when the timer was scheduled
     * @return {@code true} if the timer callback should execute, {@code false} otherwise
     */
    boolean claimTimer(Game game, ScheduledGameTask task, long runAtEpochMs, long handNumber, long timerVersion) {
        if (game == null) {
            return false;
        }
        if (game.isGameOver() && task != ScheduledGameTask.CLEANUP) {
            cancelTask(game, task);
            return false;
        }
        return game.startScheduledTaskIfCurrent(task, runAtEpochMs, handNumber, timerVersion);
    }

    private void scheduleTask(String gameId, ScheduledGameTask task, long runAtEpochMs) {
        Game game = activeGames.get(gameId);
        // Quiet no-op by design: the game may already have been removed, and while a forfeit is
        // pending the game is paused for a disconnected survivor, so no timer may be scheduled.
        if (game == null || game.isPausedForDisconnectedSurvivor()) {
            return;
        }
        game.scheduleTask(task, runAtEpochMs);
        registerTimerAfterSave(createTimer(game, task, runAtEpochMs));
    }

    private static long requireNonNegativeDelay(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0: " + delayMs);
        }
        return delayMs;
    }

    private static boolean matches(Game game, ScheduledGameTask task, long runAtEpochMs, long handNumber, long timerVersion) {
        return game != null
                && game.getHandNumber() == handNumber
                && game.getTimerVersion(task) == timerVersion
                && Objects.equals(game.getScheduledTaskTime(task), runAtEpochMs);
    }

    /**
     * Immutable identifier of a scheduled timer for a specific hand, timer version, and run time.
     *
     * @param gameId       the room/game ID
     * @param task         the timer type
     * @param runAtEpochMs target execution epoch time in milliseconds
     * @param handNumber   hand number active when the timer was scheduled
     * @param timerVersion timer version counter for the timer type
     */
    record ScheduledTimer(String gameId, ScheduledGameTask task, long runAtEpochMs, long handNumber, long timerVersion) {
    }
}
