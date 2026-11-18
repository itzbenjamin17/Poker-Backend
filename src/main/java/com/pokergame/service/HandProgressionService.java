package com.pokergame.service;

import com.pokergame.config.GameTimingProperties;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deals new hands, processes turn and betting-round outcomes, advances all-in streets, and
 * schedules post-showdown transitions.
 */
@Component
class HandProgressionService {
    private static final Logger logger = LoggerFactory.getLogger(HandProgressionService.class);
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry timerRegistry;
    private final GameTaskScheduler taskScheduler;
    private final GameCompletionHandler completionHandler;
    private final GameStateService gameStateService;
    private final GameTimingProperties timings;

    HandProgressionService(
            ActiveGameRegistry activeGames,
            GameTimerRegistry timerRegistry,
            GameTaskScheduler taskScheduler,
            GameCompletionHandler completionHandler,
            GameStateService gameStateService,
            GameTimingProperties timings) {
        this.activeGames = activeGames;
        this.timerRegistry = timerRegistry;
        this.taskScheduler = taskScheduler;
        this.completionHandler = completionHandler;
        this.gameStateService = gameStateService;
        this.timings = timings;
    }

    /**
     * Deals the next hand for {@code gameId} if the game is ready for a new hand, or settles the
     * game if elimination leaves a single winner.
     *
     * @param gameId the unique ID of the game
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean startNewHand(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Cannot start new hand - game {} not found", gameId);
            return false;
        }
        if (game.isForfeitPending()) {
            return false;
        }
        if (game.isGameOver()) {
            completionHandler.handleGameEnd(gameId);
            return false;
        }
        if (game.getHandNumber() > 0 && game.endedBeforeShowdown()) {
            logger.warn("Ignoring new-hand request for unsettled game {} hand {}", gameId, game.getHandNumber());
            return false;
        }

        logger.info("Starting new hand for game: {}", gameId);
        timerRegistry.cancelProgressionForGame(gameId);
        game.invalidateProgressionTasks();
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.CLEANUP);

        // Reset before dealing so a decided game preserves the previous final board cleanly.
        boolean gameEnded = game.resetForNewHand();
        if (gameEnded) {
            logger.info("Game {} became over after reset", gameId);
            completionHandler.handleGameEnd(gameId);
            return false;
        }

        game.beginHand();
        game.dealHoleCards();
        game.postBlinds();
        boolean openReadyGateNow = applyTurnOutcome(game, game.advanceAfterBlinds());

        logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                gameId,
                game.getCurrentPlayer().getName(),
                game.getCurrentPhase(),
                game.getPot());
        return openReadyGateNow;
    }

    /**
     * Applies the result of forced blinds or a player departure without an immediate auto-advance alert.
     *
     * @param game    the active game instance
     * @param outcome the resulting turn state
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyTurnOutcome(Game game, TurnOutcome outcome) {
        return applyTurnOutcome(game, outcome, false);
    }

    /**
     * Applies a turn, forced-blind, or departure outcome by scheduling the next timer and
     * broadcasting the resulting table state.
     *
     * @param game                       the active game instance
     * @param outcome                    the resulting turn state
     * @param notifyImmediateAutoAdvance {@code true} to send an immediate all-in notice banner
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyTurnOutcome(Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        String gameId = game.getGameId();
        switch (outcome.type()) {
            case NEXT_PLAYER, PHASE_ADVANCED -> {
                taskScheduler.cancelTask(game, ScheduledGameTask.AUTO_ADVANCE);
                taskScheduler.scheduleTurnTimeout(gameId);
                gameStateService.broadcastGameState(game);
            }
            case AUTO_ADVANCING -> {
                taskScheduler.cancelTask(game, ScheduledGameTask.TURN_TIMEOUT);
                if (!game.isAutoAdvancing()) {
                    taskScheduler.scheduleAutoAdvance(gameId);
                }
                if (notifyImmediateAutoAdvance) {
                    gameStateService.broadcastAutoAdvanceNotification(game);
                } else {
                    gameStateService.broadcastGameStateWithAutoAdvance(game, GameStateService.AUTO_ADVANCE_MESSAGE);
                }
            }
            case SHOWDOWN -> {
                taskScheduler.cancelTask(game, ScheduledGameTask.AUTO_ADVANCE);
                taskScheduler.cancelTask(game, ScheduledGameTask.TURN_TIMEOUT);
                gameStateService.broadcastShowdownResults(game);
                return schedulePostShowdownProgression(game);
            }
        }
        return false;
    }

    /**
     * Executes an auto-advance timer step and either schedules the next street or transitions to
     * post-hand readiness/game-end handling when showdown completes.
     *
     * @param game the active game instance
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyAutoAdvanceTimeout(Game game) {
        if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
            return false;
        }
        boolean complete = dealNextStreet(game);
        if (complete) {
            return schedulePostShowdownProgression(game);
        }
        taskScheduler.scheduleAutoAdvance(game.getGameId());
        return false;
    }

    private boolean dealNextStreet(Game game) {
        return switch (game.getCurrentPhase()) {
            case PRE_FLOP -> {
                game.dealFlop();
                gameStateService.broadcastGameStateWithAutoAdvance(game, "Dealing flop...");
                yield false;
            }
            case FLOP -> {
                game.dealTurn();
                gameStateService.broadcastGameStateWithAutoAdvance(game, "Dealing turn...");
                yield false;
            }
            case TURN -> {
                game.dealRiver();
                gameStateService.broadcastGameStateWithAutoAdvance(game, "Dealing river...");
                yield false;
            }
            case RIVER -> {
                game.conductShowdown();
                gameStateService.broadcastShowdownResults(game);
                gameStateService.broadcastAutoAdvanceComplete(game);
                yield true;
            }
            case SHOWDOWN -> throw new IllegalStateException(
                    "Unexpected phase during auto-advance: " + game.getCurrentPhase());
        };
    }

    private boolean schedulePostShowdownProgression(Game game) {
        String gameId = game.getGameId();
        if (game.isGameDecided()) {
            taskScheduler.scheduleGameEnd(gameId, timings.finalShowdownDisplayDelayMs());
            return false;
        }
        return taskScheduler.scheduleReadyCountdownOpen(gameId, timings.roundEndDisplayDelayMs());
    }
}
