package com.pokergame.service;

import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import com.pokergame.service.GameTimerRegistry.TaskIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Orchestrates ready-gate transitions and their effects under the caller's room lease. */
final class GameReadinessCoordinator {
    private static final Logger logger = LoggerFactory.getLogger(GameReadinessCoordinator.class);

    enum Progression { NONE, START_HAND, END_GAME }

    /** Returns the lifecycle decision and any timeout intent for an opened gate. */
    record Opening(Progression progression, TaskIntent timeout) { }

    private final GameStateService gameStateService;
    private final GameTimerRegistry runtimeTimers;

    GameReadinessCoordinator(GameStateService gameStateService, GameTimerRegistry runtimeTimers) {
        this.gameStateService = gameStateService;
        this.runtimeTimers = runtimeTimers;
    }

    /** Applies the opening transition and publishes readiness before any hand progression. */
    Opening open(String gameId, Game game, long deadline) {
        return switch (game.startOrWaitReadyCountdown(deadline)) {
            case IGNORE -> new Opening(Progression.NONE, null);
            case TOURNAMENT_OVER -> new Opening(Progression.END_GAME, null);
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(gameId, game);
                yield new Opening(Progression.NONE, null);
            }
            case ALL_READY -> {
                gameStateService.broadcastGameState(gameId, game);
                complete(gameId, game);
                yield new Opening(Progression.START_HAND, null);
            }
            case OPENED -> {
                TaskIntent intent = GameTimerRegistry.captureIntent(gameId, game, ScheduledGameTask.READY_TIMEOUT, deadline);
                gameStateService.broadcastGameState(gameId, game);
                yield new Opening(Progression.NONE, intent);
            }
        };
    }

    /** Applies an eligible player's readiness and reports whether the next hand should start. */
    boolean setReady(String gameId, Game game, String playerName, boolean ready, Runnable resetInactivity) {
        Player player = game.findPlayerByName(playerName);
        boolean previouslyReady = player != null && player.getIsReadyForNextHand();
        game.setPlayerReadyForNextHand(playerName, ready);
        if (GameInactivityPolicy.isReadinessActivity(previouslyReady, ready)) {
            resetInactivity.run();
        }
        logger.debug("Player {} {} ready for next hand in game {}", playerName, ready ? "marked" : "unmarked", gameId);
        if (ready && game.areAllReadyEligiblePlayersReady()) {
            complete(gameId, game);
            return true;
        }
        gameStateService.broadcastGameState(gameId, game);
        return false;
    }

    /** Expires the matching gate and reports whether the next hand should start. */
    boolean expire(String gameId, Game game, long expectedDeadline) {
        if (game == null) {
            cancelTimeout(gameId);
            return false;
        }
        return switch (game.expireReadyCountdown(expectedDeadline)) {
            case IGNORE_STALE -> false;
            case WAITING_FOR_PLAYERS -> {
                cancelTimeout(gameId);
                gameStateService.broadcastGameState(gameId, game);
                yield false;
            }
            case FORCED_READY -> {
                complete(gameId, game);
                yield true;
            }
        };
    }

    /** Cancels the obsolete timers and closes the ready gate before the next deal. */
    void complete(String gameId, Game game) {
        cancelTimeout(gameId);
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.READY_TIMEOUT);
        game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
    }

    /** Cancels the runtime ready timeout without changing the gate. */
    void cancelTimeout(String gameId) {
        runtimeTimers.cancelTask(gameId, ScheduledGameTask.READY_TIMEOUT);
    }
}
