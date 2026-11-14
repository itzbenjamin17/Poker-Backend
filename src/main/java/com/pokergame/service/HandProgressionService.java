package com.pokergame.service;

import com.pokergame.config.LifecycleTimingProperties;
import com.pokergame.dto.internal.TurnOutcome;
import com.pokergame.enums.GamePhase;
import com.pokergame.enums.ScheduledGameTask;
import com.pokergame.model.Game;
import com.pokergame.model.Player;
import java.util.List;
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
    static final long FINAL_SHOWDOWN_DISPLAY_DELAY_MS = 6000;
    private final ActiveGameRegistry activeGames;
    private final GameTimerRegistry runtimeTimers;
    private final GameTaskScheduler scheduling;
    private final GameCompletionHandler completion;
    private final GameStateService gameStateService;
    private final LifecycleTimingProperties timings;

    HandProgressionService(
            ActiveGameRegistry activeGames,
            GameTimerRegistry runtimeTimers,
            GameTaskScheduler scheduling,
            GameCompletionHandler completion,
            GameStateService gameStateService,
            LifecycleTimingProperties timings) {
        this.activeGames = activeGames;
        this.runtimeTimers = runtimeTimers;
        this.scheduling = scheduling;
        this.completion = completion;
        this.gameStateService = gameStateService;
        this.timings = timings;
    }

    /**
     * Deals the next hand for {@code gameId} if the game is ready for a new hand, or settles the
     * game if tournament elimination leaves a winner.
     *
     * @param gameId the unique ID of the game
     * @return a follow-up transition when the ready gate should open immediately, or {@code null}
     */
    PostHandTransition startNewHand(String gameId) {
        Game game = activeGames.get(gameId);
        if (game == null) {
            logger.warn("Cannot start new hand - game {} not found", gameId);
            return null;
        }
        logger.info("Starting new hand for game: {}", gameId);
        if (game.isForfeitPending()) {
            return null;
        }
        if (game.isGameOver()) {
            if (!game.isTerminalCarryOverSettled()) {
                completion.handleGameEnd(gameId, false);
            }
            return null;
        }
        if (game.getHandNumber() > 0 && (game.getCurrentPhase() != GamePhase.SHOWDOWN || !game.isHandSettled())) {
            logger.warn("Ignoring new-hand request for unsettled game {} hand {}", gameId, game.getHandNumber());
            return null;
        }

        runtimeTimers.cancelProgressionForGame(gameId);
        game.invalidateProgressionTasks();
        game.closeReadyCountdown();
        game.clearScheduledTask(ScheduledGameTask.CLEANUP);

        // Reset before dealing so a finished tournament preserves the previous final board cleanly.
        boolean gameEnded = game.resetForNewHand();
        if (gameEnded) {
            logger.warn("Game {} became over after reset", gameId);
            completion.handleGameEnd(gameId, false);
            return null;
        }

        game.beginHand();
        game.dealHoleCards();
        game.postBlinds();
        PostHandTransition transition = applyTurnOutcome(gameId, game, game.reconcileAfterForcedBlinds());

        logger.info("New hand started successfully for game: {} | Current player: {} | Phase: {} | Pot: {}",
                gameId,
                game.getCurrentPlayer() == null ? "none" : game.getCurrentPlayer().getName(),
                game.getCurrentPhase(),
                game.getPot());
        return transition;
    }

    /**
     * Applies the result of forced blinds or a player departure without an immediate auto-advance alert.
     *
     * @param gameId  the unique ID of the game
     * @param game    the active game instance
     * @param outcome the resulting turn state
     * @return a follow-up transition when the ready gate should open immediately, or {@code null}
     */
    PostHandTransition applyTurnOutcome(String gameId, Game game, TurnOutcome outcome) {
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
     * @return a follow-up transition when the ready gate should open immediately, or {@code null}
     */
    PostHandTransition applyTurnOutcome(
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
                gameStateService.broadcastGameState(gameId, game);
            }
            case AUTO_ADVANCING -> {
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (game.getScheduledTaskDeadline(ScheduledGameTask.AUTO_ADVANCE) == null) {
                    scheduling.scheduleAutoAdvance(gameId);
                }
                if (notifyImmediateAutoAdvance) {
                    gameStateService.broadcastAutoAdvanceNotification(gameId, game);
                } else {
                    gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Auto-advancing...");
                }
            }
            case SHOWDOWN -> {
                game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
                game.clearScheduledTask(ScheduledGameTask.TURN_TIMEOUT);
                runtimeTimers.cancelTask(gameId, ScheduledGameTask.TURN_TIMEOUT);
                if (schedulePostShowdownProgression(gameId, game)) {
                    return new PostHandTransition(game, outcome);
                }
                broadcastShowdown(gameId, game, outcome);
            }
        }
        return null;
    }

    /**
     * Broadcasts a showdown whose publication was deferred until after the ready gate opened.
     *
     * @param gameId     the unique ID of the game
     * @param transition the post-hand transition holding the pending showdown outcome
     */
    void broadcastPendingShowdown(String gameId, PostHandTransition transition) {
        if (transition.pendingShowdown() != null) {
            broadcastShowdown(gameId, transition.game(), transition.pendingShowdown());
        }
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
        if (game.isHandSettled() || game.isTerminalCarryOverSettled()) {
            logger.info("Skipping stale auto-advance callback for settled game {}", gameId);
            return true;
        }
        return switch (game.getCurrentPhase()) {
            case PRE_FLOP -> {
                game.dealFlop();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing flop...");
                yield false;
            }
            case FLOP -> {
                game.dealTurn();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing turn...");
                yield false;
            }
            case TURN -> {
                game.dealRiver();
                gameStateService.broadcastGameStateWithAutoAdvance(gameId, game, "Dealing river...");
                yield false;
            }
            case RIVER -> {
                int potBeforeDistribution = game.getPot();
                List<Player> winners = game.conductShowdown();
                int winningsPerPlayer = winners.isEmpty() ? 0 : potBeforeDistribution / winners.size();
                gameStateService.broadcastShowdownResults(gameId, game, winners, winningsPerPlayer);
                gameStateService.broadcastAutoAdvanceComplete(gameId, game);
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
     * @return a post-hand transition if readiness should open immediately, or {@code null}
     */
    PostHandTransition applyAutoAdvanceTimeout(String gameId, Game game) {
        if (game.getCurrentPhase() == GamePhase.SHOWDOWN) {
            game.clearScheduledTask(ScheduledGameTask.AUTO_ADVANCE);
            return null;
        }
        boolean complete = advanceAutoAdvanceStep(gameId);
        if (complete) {
            return schedulePostShowdownProgression(gameId, game) ? new PostHandTransition(game, null) : null;
        }
        scheduling.scheduleAutoAdvance(gameId);
        return null;
    }

    private void broadcastShowdown(String gameId, Game game, TurnOutcome outcome) {
        gameStateService.broadcastShowdownResults(gameId, game, outcome.winners(), outcome.winningsPerPlayer());
    }

    private boolean schedulePostShowdownProgression(String gameId, Game game) {
        if (game.isTournamentOver()) {
            scheduling.scheduleGameEnd(gameId, FINAL_SHOWDOWN_DISPLAY_DELAY_MS);
            return false;
        }
        return scheduling.scheduleReadyCountdownOpen(gameId, timings.roundEndDisplayDelayMs())
                == GameTaskScheduler.ReadyOpening.OPEN_IMMEDIATELY;
    }

    /**
     * Follow-up transition returned when the ready gate should open immediately before
     * broadcasting a pending showdown result.
     *
     * @param game            the active game instance
     * @param pendingShowdown the showdown outcome to broadcast after opening readiness, or {@code null}
     */
    record PostHandTransition(Game game, TurnOutcome pendingShowdown) {
    }
}
