package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.PostSaveActions;
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
    private static final long AUTO_ADVANCE_STEP_DELAY_MS = 4000;
    static final long TURN_ALLOWANCE_MS = 60_000;
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final RoomService roomService;
    private final Clock clock;
    private final ObjectProvider<GameLifecycleService> gameLifecycleProvider;

    GameTaskScheduler(
            ActiveGameRegistry activeGames,
            GameTimerRegistry timerRegistry,
            RoomService roomService,
            Clock clock,
            ObjectProvider<GameLifecycleService> gameLifecycleProvider) {
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.roomService = roomService;
        this.clock = clock;
        this.gameLifecycleProvider = gameLifecycleProvider;
    }

    /**
     * Captures a timer descriptor for {@code task} tied to {@code game}'s current hand and
     * schedule count so callbacks from cancelled or replaced timers are ignored.
     *
     * @param gameId   the room/game ID
     * @param game     the active game instance
     * @param task     the timer type
     * @param deadline target execution epoch time in milliseconds
     * @return an immutable timer descriptor
     */
    static ScheduledTimer createTimer(String gameId, Game game, ScheduledGameTask task, long deadline) {
        return new ScheduledTimer(gameId, task, deadline, game.getHandNumber(), game.getScheduledTaskGeneration(task));
    }

    /**
     * Lists all active timer descriptors currently recorded on {@code game}.
     *
     * @param gameId the room/game ID
     * @param game   the active game instance
     * @return immutable descriptors for every timer stored on {@code game}
     */
    static List<ScheduledTimer> listActiveTimers(String gameId, Game game) {
        return game.getScheduledTaskDeadlinesSnapshot().entrySet().stream()
                .map(entry -> createTimer(gameId, game, entry.getKey(), entry.getValue()))
                .toList();
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
     * Schedules final tournament completion after displaying the final showdown board.
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
        scheduleTask(gameId, ScheduledGameTask.AUTO_ADVANCE, clock.millis() + AUTO_ADVANCE_STEP_DELAY_MS);
    }

    /**
     * Schedules the current player's turn timeout timer.
     *
     * @param gameId the room/game ID
     */
    void scheduleTurnTimeout(String gameId) {
        scheduleTask(gameId, ScheduledGameTask.TURN_TIMEOUT, clock.millis() + TURN_ALLOWANCE_MS);
    }

    /**
     * Registers the room's inactivity timer for {@code deadlineEpochMs}.
     *
     * @param gameId          the room/game ID
     * @param deadlineEpochMs target inactivity expiration epoch time in milliseconds
     */
    void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        roomService.getRoom(gameId);
        timerRegistry.scheduleInactivity(gameId, deadlineEpochMs, clock.millis(),
                () -> gameLifecycleProvider.getObject().abandonGameIfInactive(gameId, deadlineEpochMs));
    }

    /**
     * Extends the inactivity deadline for an active game if it has not yet finished.
     *
     * @param gameId the room/game ID
     */
    void resetGameInactivity(String gameId) {
        Game game = activeGames.get(gameId);
        if (game != null) {
            resetGameInactivity(gameId, game);
        }
    }

    /**
     * Extends {@code game}'s inactivity deadline by the configured allowance and reschedules its timer.
     *
     * @param gameId the room/game ID
     * @param game   the active game instance
     */
    void resetGameInactivity(String gameId, Game game) {
        if (game == null || game.isGameOver()) {
            return;
        }
        long now = clock.millis();
        Room room = roomService.getRoom(gameId);
        if (room != null) {
            room.extendGameInactivityDeadline(now);
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
        if (!matches(game, timer.task(), timer.deadline(), timer.handNumber(), timer.generation())) {
            return;
        }
        timerRegistry.schedule(timer.gameId(), timer.task(), timer.deadline(),
                () -> gameLifecycleProvider.getObject().executeScheduledTask(
                        timer.gameId(), timer.task(), timer.deadline(), timer.handNumber(), timer.generation()));
    }

    /**
     * Validates that a firing timer callback still matches the current hand and generation,
     * marks one-shot timers as claimed, and cancels stale game-over futures.
     *
     * @param gameId     the room/game ID
     * @param game       the current game instance, or {@code null} if already removed
     * @param task       the timer type that fired
     * @param deadline   the captured deadline
     * @param handNumber the captured hand number
     * @param generation the captured timer generation
     * @return {@code true} if the timer callback should execute, {@code false} otherwise
     */
    boolean claim(String gameId, Game game, ScheduledGameTask task, long deadline, long handNumber, long generation) {
        if (game == null) {
            return false;
        }
        if (task == ScheduledGameTask.READY_TIMEOUT) {
            return matches(game, task, deadline, handNumber, generation);
        }
        return switch (game.claimScheduledTaskExecution(task, deadline, handNumber, generation)) {
            case IGNORE -> false;
            case CANCEL_GAME_OVER -> {
                timerRegistry.cancelTask(gameId, task);
                yield false;
            }
            case EXECUTE -> true;
        };
    }

    private void scheduleTask(String gameId, ScheduledGameTask task, long deadline) {
        Game game = activeGames.get(gameId);
        if (game == null || game.isForfeitPending()) {
            return;
        }
        game.scheduleTask(task, deadline);
        registerTimerAfterSave(createTimer(gameId, game, task, deadline));
    }

    private static long requireNonNegativeDelay(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0: " + delayMs);
        }
        return delayMs;
    }

    private static boolean matches(Game game, ScheduledGameTask task, long deadline, long handNumber, long generation) {
        return game != null
                && game.getHandNumber() == handNumber
                && game.getScheduledTaskGeneration(task) == generation
                && Objects.equals(game.getScheduledTaskDeadline(task), deadline);
    }

    /**
     * Immutable identifier of a scheduled timer for a specific hand, generation, and deadline.
     *
     * @param gameId     the room/game ID
     * @param task       the timer type
     * @param deadline   target execution epoch time in milliseconds
     * @param handNumber hand number active when the timer was scheduled
     * @param generation generation counter for the timer type
     */
    record ScheduledTimer(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation) {
    }
}
