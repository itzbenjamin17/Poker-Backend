package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Room;
import com.pokergame.persistence.transaction.DurableTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Records durable timer intent, submits accepted work, and validates callbacks under the room lease. */
final class GameTaskScheduler {
    private static final Logger logger = LoggerFactory.getLogger(GameTaskScheduler.class);
    private static final long AUTO_ADVANCE_STEP_DELAY_MS = 4000;
    static final long TURN_ALLOWANCE_MS = 60_000;

    enum Claim { IGNORE, CANCEL, EXECUTE }

    enum ReadyOpening { DEFERRED, OPEN_IMMEDIATELY }

    /** Identifies a scheduled task for a specific hand, generation, and epoch-millisecond deadline. */
    record TaskIntent(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation) { }

    /** Executes scheduled work using the values captured at registration. */
    @FunctionalInterface
    interface ScheduledTaskExecutor {
        void execute(String gameId, ScheduledGameTask task, long deadline, long handNumber, long generation);
    }

    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final RoomCoordinator roomCoordinator;
    private final RoomService roomService;
    private final Supplier<Clock> clock;
    private final ScheduledTaskExecutor executeTask;
    private final BiConsumer<String, Long> executeInactivity;

    GameTaskScheduler(ActiveGameRegistry activeGames, GameTimerRegistry timerRegistry, RoomCoordinator roomCoordinator,
            RoomService roomService, Supplier<Clock> clock, ScheduledTaskExecutor executeTask,
            BiConsumer<String, Long> executeInactivity) {
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.roomCoordinator = roomCoordinator;
        this.roomService = roomService;
        this.clock = clock;
        this.executeTask = executeTask;
        this.executeInactivity = executeInactivity;
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

    void scheduleNewHand(String gameId, long delayMs) {
        persistScheduledTask(gameId, ScheduledGameTask.NEW_HAND, System.currentTimeMillis() + Math.max(0, delayMs));
    }

    /** Returns an immediate decision so readiness can run in the caller's transaction. */
    ReadyOpening scheduleReadyCountdownOpen(String gameId, long delayMs) {
        if (delayMs <= 0) return ReadyOpening.OPEN_IMMEDIATELY;
        persistScheduledTask(gameId, ScheduledGameTask.READY_OPEN, System.currentTimeMillis() + delayMs);
        return ReadyOpening.DEFERRED;
    }

    void scheduleGameCleanup(String gameId, long delayMs) {
        persistScheduledTask(gameId, ScheduledGameTask.CLEANUP, clock.get().millis() + Math.max(0, delayMs));
    }

    void scheduleGameEnd(String gameId, long delayMs) {
        persistScheduledTask(gameId, ScheduledGameTask.GAME_END, clock.get().millis() + Math.max(0, delayMs));
    }

    void scheduleAutoAdvance(String gameId) {
        persistScheduledTask(gameId, ScheduledGameTask.AUTO_ADVANCE, System.currentTimeMillis() + AUTO_ADVANCE_STEP_DELAY_MS);
    }

    void scheduleTurnTimeout(String gameId) {
        persistScheduledTask(gameId, ScheduledGameTask.TURN_TIMEOUT, clock.get().millis() + TURN_ALLOWANCE_MS);
    }

    /** Registers the room's captured inactivity deadline with the runtime timer owner. */
    void scheduleGameInactivity(String gameId, Long deadlineEpochMs) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        timerRegistry.scheduleInactivity(gameId, deadlineEpochMs, clock.get().millis(),
                () -> executeInactivity.accept(gameId, deadlineEpochMs));
    }

    void resetGameInactivity(String gameId) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game != null) resetGameInactivityUnderLock(gameId, game);
    }

    /**
     * Resets the active-game inactivity deadline to 15 minutes in the future under lock.
     *
     * @param gameId game identifier
     * @param game   game instance
     */
    void resetGameInactivityUnderLock(String gameId, Game game) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (!GameInactivityPolicy.canReset(game)) return;
        long newDeadline = GameInactivityPolicy.nextDeadline(clock.get().millis());
        Room room = roomService != null ? roomService.getRoom(gameId) : null;
        if (room != null) {
            room.setInactivityDeadlineEpochMs(newDeadline);
        }
        scheduleGameInactivity(gameId, newDeadline);
        logger.debug("Active-game inactivity deadline reset for game {} to {}", gameId, newDeadline);
    }

    /** Records intent first, submitting its immutable identity after the save attempt. */
    private void persistScheduledTask(String gameId, ScheduledGameTask task, long deadline) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        Game game = activeGames.get(gameId);
        if (game == null || game.isForfeitPending()) return;
        game.scheduleTask(task, deadline);
        submitAfterCommit(captureIntent(gameId, game, task, deadline));
    }

    /** Submits an already recorded task after the current save attempt. */
    void submitAfterCommit(TaskIntent intent) {
        DurableTransactionContext.afterCommit(() -> scheduleRuntimeTask(intent));
    }

    /** Registers current immutable intent, including when rebuilding timers after rollback. */
    void scheduleRuntimeTask(TaskIntent intent) {
        roomCoordinator.assertHeldByCurrentThread(intent.gameId());
        Game game = activeGames.get(intent.gameId());
        if (!matches(game, intent.task(), intent.deadline(), intent.handNumber(), intent.generation())) return;
        timerRegistry.schedule(intent.gameId(), intent.task(), intent.deadline(),
                () -> executeTask.execute(intent.gameId(), intent.task(), intent.deadline(), intent.handNumber(), intent.generation()));
    }

    /**
     * Validates the callback under the caller's lease. The deadline-only auto-advance compatibility
     * entry may supply a null generation. Ready timeouts keep their intent until readiness applies
     * the gate transition and clears it.
     */
    Claim claim(String gameId, Game game, ScheduledGameTask task, long deadline, long handNumber, Long expectedGeneration) {
        roomCoordinator.assertHeldByCurrentThread(gameId);
        if (game == null) return Claim.IGNORE;
        long generation = expectedGeneration != null ? expectedGeneration : game.getScheduledTaskGeneration(task);
        if (task == ScheduledGameTask.READY_TIMEOUT) {
            return matches(game, task, deadline, handNumber, generation) ? Claim.EXECUTE : Claim.IGNORE;
        }
        return switch (game.claimScheduledTaskExecution(task, deadline, handNumber, generation)) {
            case IGNORE -> Claim.IGNORE;
            case CANCEL_GAME_OVER -> {
                timerRegistry.cancelTask(gameId, task);
                yield Claim.CANCEL;
            }
            case EXECUTE -> Claim.EXECUTE;
        };
    }

    private static boolean matches(Game game, ScheduledGameTask task, long deadline, long handNumber, long generation) {
        return game != null && game.getHandNumber() == handNumber
                && game.getScheduledTaskGeneration(task) == generation
                && Objects.equals(game.getScheduledTaskDeadline(task), deadline);
    }
}
