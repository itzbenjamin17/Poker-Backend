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
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler scheduling;
    private final GameCompletionHandler completion;
    private final GameStateService gameStateService;
    private final GameTimingProperties timings;

    HandProgressionService(
            ActiveGameRegistry activeGames,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler scheduling,
            GameCompletionHandler completion,
            GameStateService gameStateService,
            GameTimingProperties timings) {
        this.activeGames = activeGames;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.completion = completion;
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
        logger.info("Starting new hand for game: {}", gameId);
        if (game.isForfeitPending()) {
            return false;
        }
        if (game.isGameOver()) {
            if (!game.isFinalPotSettled()) {
                completion.handleGameEnd(gameId);
            }
            return false;
        }
        if (game.getHandNumber() > 0 && (game.getCurrentPhase() != GamePhase.SHOWDOWN || !game.isHandSettled())) {
            logger.warn("Ignoring new-hand request for unsettled game {} hand {}", gameId, game.getHandNumber());
            return false;
        }

        runtimeTimers.cancelProgressionForGame(gameId);
        game.invalidateProgressionTasks();
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.CLEANUP);

        // Reset before dealing so a decided game preserves the previous final board cleanly.
        boolean gameEnded = game.resetForNewHand();
        if (gameEnded) {
            logger.warn("Game {} became over after reset", gameId);
            completion.handleGameEnd(gameId);
            return false;
        }

        game.beginHand();
        game.dealHoleCards();
        game.postBlinds();
        boolean openReadyGateNow = applyTurnOutcome(gameId, game, game.advanceAfterBlinds());

        logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                gameId,
                game.getCurrentPlayer() == null ? "none" : game.getCurrentPlayer().getName(),
                game.getCurrentPhase(),
                game.getPot());
        return openReadyGateNow;
    }

    /**
     * Applies the result of forced blinds or a player departure without an immediate auto-advance alert.
     *
     * @param gameId  the unique ID of the game
     * @param game    the active game instance
     * @param outcome the resulting turn state
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
        return applyTurnOutcome(gameId, game, outcome, false);
    }

    /**
     * Applies a turn, forced-blind, or departure outcome by scheduling the next timer and
     * broadcasting the resulting table state.
     *
     * @param gameId                     the unique ID of the game
     * @param game                       the active game instance
     * @param outcome                    the resulting turn state
     * @param notifyImmediateAutoAdvance {@code true} to send an immediate all-in notice banner
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyTurnOutcome(
            String gameId, Game game, TurnOutcome outcome, boolean notifyImmediateAutoAdvance) {
        switch (outcome.type()) {
            case NEXT_PLAYER, PHASE_ADVANCED -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.AUTO_ADVANCE);
                if (game.getCurrentPlayer() != null) {
                    scheduling.scheduleTurnTimeout(gameId);
                } else {
                    game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                    runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                }
                gameStateService.broadcastGameState(game);
            }
            case AUTO_ADVANCING -> {
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (!game.isAutoAdvancing()) {
                    scheduling.scheduleAutoAdvance(gameId);
                }
                if (notifyImmediateAutoAdvance) {
                    gameStateService.broadcastAutoAdvanceNotification(game);
                } else {
                    gameStateService.broadcastGameStateWithAutoAdvance(game, GameStateService.AUTO_ADVANCE_MESSAGE);
                }
            }
            case SHOWDOWN -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                gameStateService.broadcastShowdownResults(game);
                return schedulePostShowdownProgression(gameId, game);
            }
        }
        return false;
    }

    /**
     * Deals the next street or resolves showdown for a single all-in auto-advance step.
     *
     * @param gameId the unique ID of the game
     * @return {@code true} if the hand has reached showdown or finished
     */
    boolean advanceAutoAdvanceStep(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null || game.isForfeitPending() || game.isGameOver()) {
            return true;
        }
        if (game.isHandSettled() || game.isFinalPotSettled()) {
            logger.info("Skipping stale auto-advance callback for settled game {}", gameId);
            return true;
        }
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
            case SHOWDOWN -> {
                logger.warn("Auto-advance step invoked for game {} already in SHOWDOWN - skipping duplicate distribution",
                        gameId);
                yield true;
            }
        };
    }

    /**
     * Executes an auto-advance timer step and either schedules the next street or transitions to
     * post-hand readiness/game-end handling when showdown completes.
     *
     * @param gameId the unique ID of the game
     * @param game   the active game instance
     * @return {@code true} if the ready gate should open immediately, {@code false} otherwise
     */
    boolean applyAutoAdvanceTimeout(String gameId, Game game) {
        if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
            game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
            return false;
        }
        boolean complete = advanceAutoAdvanceStep(gameId);
        if (complete) {
            return schedulePostShowdownProgression(gameId, game);
        }
        scheduling.scheduleAutoAdvance(gameId);
        return false;
    }

    private boolean schedulePostShowdownProgression(String gameId, Game game) {
        if (game.isGameDecided()) {
            scheduling.scheduleGameEnd(gameId, timings.finalShowdownDisplayDelayMs());
            return false;
        }
        return scheduling.scheduleReadyCountdownOpen(gameId, timings.roundEndDisplayDelayMs());
    }
}
